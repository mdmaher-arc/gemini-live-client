package com.geminilive.client.network

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * GEMINI LIVE MULTIMODAL API — FULL-DUPLEX WEBSOCKET CLIENT
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Implements the Gemini Live Multimodal API bidirectional WebSocket protocol.
 * The concrete model ID lives in [DEFAULT_MODEL] / [EXTENDED_THINKING_MODEL].
 *
 * Endpoint:
 *   wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key={API_KEY}
 *
 * Protocol Flow:
 *   1. Connect over WebSocket.
 *   2. Send BidiGenerateContentSetup frame with model name and generation config.
 *   3. Wait for server acknowledgment: {"setupComplete": {}}.
 *   4. Stream raw 16kHz 16-bit PCM chunks wrapped in realtimeInput.mediaChunks.
 *   5. Receive 24kHz PCM chunks wrapped in serverContent.modelTurn.parts[].inlineData.
 *   6. Handle interruptions (barge-in) and turn completion.
 */
class GeminiLiveWebSocketClient {

    companion object {
        private const val TAG = "GeminiWS"
        private const val GEMINI_WS_BASE = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        
        // ── Official Gemini Live API model IDs ───────────────────────────────
        // Verified against ai.google.dev/gemini-api/docs/models → Live API.
        const val DEFAULT_MODEL = "models/gemini-3.8-live"
        const val EXTENDED_THINKING_MODEL = "models/gemini-3.8-live-extended-thinking"

        /**
         * The models this client knows how to talk to. Used to validate persisted
         * settings, so a stale value written by an older app version (for example
         * the retired `gemini-2.0-flash-exp`) can never be sent to the server.
         */
        val SUPPORTED_MODELS: List<String> = listOf(DEFAULT_MODEL, EXTENDED_THINKING_MODEL)

        /**
         * Model used for the single automatic retry when the server rejects the
         * requested model. Deliberately different from [DEFAULT_MODEL].
         */
        const val FALLBACK_MODEL = EXTENDED_THINKING_MODEL

        const val DEFAULT_SYSTEM_PROMPT = """You are a highly intelligent, warm, and empathetic personal AI assistant. You speak in a natural, conversational tone, like a knowledgeable best friend. Keep your responses concise but complete. Use natural speech patterns including brief acknowledgements ("Got it", "Sure", "Of course"). You understand context, emotion in the user's voice, and adapt your tone accordingly. You can handle any topic: information, analysis, creative writing, coding, math, and more."""
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // Keep open continuously
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var apiKey: String = ""
    private var systemPrompt: String = ""
    private var modelId: String = DEFAULT_MODEL
    private var voiceName: String = "Puck"
    private var userInitiatedDisconnect = false

    /** Guards the one-shot retry with [FALLBACK_MODEL] so we can never loop. */
    private var hasRetriedWithFallback = false

    // ── Callbacks ────────────────────────────────────────────────────────────
    var onConnected: (() -> Unit)? = null
    var onSetupComplete: (() -> Unit)? = null
    var onDisconnected: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /** Decoded 24kHz PCM short samples for jitter buffer playback */
    var onAudioResponseChunk: ((ShortArray) -> Unit)? = null

    /** Called when Gemini completes a full response turn */
    var onTurnComplete: (() -> Unit)? = null

    /** Text transcript of AI speech */
    var onTextTranscript: ((String) -> Unit)? = null

    /** Text transcript of user speech recognized by Gemini */
    var onInputTranscript: ((String) -> Unit)? = null

    val isConnected: Boolean get() = webSocket != null

    // ═══════════════════════════════════════════════════════════════════════
    // Connection Management
    // ═══════════════════════════════════════════════════════════════════════

    fun connect(
        apiKey: String,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        model: String = DEFAULT_MODEL,
        voice: String = "Puck"
    ) {
        val cleanKey = apiKey.trim().replace("\n", "").replace("\r", "").replace("\"", "")
        this.apiKey = cleanKey
        this.systemPrompt = systemPrompt
        this.modelId = resolveModel(model)
        this.voiceName = if (voice.isNotBlank()) voice else "Puck"

        // Close any previous socket first, then clear the flags. Order matters:
        // disconnect() itself sets userInitiatedDisconnect = true, so resetting
        // before it would make an unexpected close look like a clean exit.
        disconnect()
        this.userInitiatedDisconnect = false
        this.hasRetriedWithFallback = false

        openSocket()
    }

    /**
     * Opens the WebSocket using the current [apiKey], [modelId] and [systemPrompt].
     * Separate from [connect] so that [retryWithFallback] can reopen the socket
     * without resetting the retry guard.
     */
    private fun openSocket() {
        val url = "$GEMINI_WS_BASE?key=$apiKey"
        Log.d(TAG, "Connecting to Gemini Live WebSocket (model=$modelId, voice=$voiceName)...")

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected (HTTP ${response.code}) — sending setup frame")
                sendSetup(ws)
                onConnected?.invoke()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                try {
                    val text = bytes.utf8()
                    handleServerMessage(text)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing binary message: ${e.message}", e)
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closing by server: code=$code, reason='$reason'")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed: code=$code, reason='$reason'")
                webSocket = null
                if (!userInitiatedDisconnect) {
                    // Code 1008 is what the server sends when it refuses our setup
                    // frame — usually because the requested model is unavailable.
                    // Retry once with the fallback model before surfacing an error.
                    if (code == 1008 && retryWithFallback()) return

                    val errorDetail = formatClosureError(code, reason)
                    onError?.invoke(errorDetail)
                } else {
                    onDisconnected?.invoke("Session ended")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val errBody = try { response?.body?.string() } catch (_: Exception) { null }
                val code = response?.code
                val detail = buildString {
                    if (code != null) {
                        append("HTTP $code: ")
                        when (code) {
                            400 -> append("Bad Request — Check API key & permissions")
                            403 -> append("Forbidden — Gemini Live API may not be enabled for this key")
                            404 -> append("Endpoint/Model not found ($modelId)")
                            429 -> append("Rate limit exceeded — Please wait and retry")
                            else -> append(response.message)
                        }
                    } else {
                        append("Network connection failed: ${t.message ?: "Unknown error"}")
                    }
                    if (!errBody.isNullOrBlank()) {
                        append(" ($errBody)")
                    }
                }
                Log.e(TAG, "WebSocket failure: $detail", t)
                webSocket = null

                // A 404, or an explicit "model … not found" body, means the model we
                // asked for is unavailable — retry once with the fallback model.
                if (!userInitiatedDisconnect &&
                    indicatesModelRejected(code, errBody) &&
                    retryWithFallback()
                ) {
                    return
                }

                onError?.invoke(detail)
            }
        })
    }

    /**
     * Returns [model] when it is one of [SUPPORTED_MODELS], otherwise [DEFAULT_MODEL].
     *
     * This replaces the previous substring check, which silently rewrote values it
     * did not recognise and could also mangle a perfectly valid model name.
     */
    private fun resolveModel(model: String): String {
        val normalized = model.trim()
        return if (normalized in SUPPORTED_MODELS) normalized else DEFAULT_MODEL
    }

    /** True when an error response indicates the requested model is unavailable. */
    private fun indicatesModelRejected(code: Int?, body: String?): Boolean {
        if (code == 404) return true
        if (body.isNullOrBlank()) return false
        val lower = body.lowercase()
        return lower.contains("model") &&
            (lower.contains("not found") || lower.contains("not supported") || lower.contains("unsupported"))
    }

    /**
     * Reopens the socket against [FALLBACK_MODEL]. Performs at most one retry per
     * connection attempt, and never retries onto the model that just failed.
     *
     * @return true when a retry was started, so the caller can skip its error path.
     */
    private fun retryWithFallback(): Boolean {
        if (hasRetriedWithFallback || modelId == FALLBACK_MODEL) return false
        hasRetriedWithFallback = true
        modelId = FALLBACK_MODEL
        Log.w(TAG, "Model rejected — retrying once with fallback model $FALLBACK_MODEL")
        openSocket()
        return true
    }

    private fun formatClosureError(code: Int, reason: String): String {
        return when (code) {
            1000 -> if (reason.isNotBlank()) "Server closed: $reason" else "Session completed"
            1007 -> "Invalid API Key or payload: ${if (reason.isNotBlank()) reason else "Please verify your Google AI Studio key"}"
            1008 -> "Policy violation / Model error: ${if (reason.isNotBlank()) reason else "Model rejected setup"}"
            1006 -> "Connection interrupted unexpectedly (code 1006)"
            else -> "Connection closed ($code): ${if (reason.isNotBlank()) reason else "Unknown reason"}"
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Protocol Messages
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Sends the required initial setup frame using camelCase per Google Protobuf JSON mapping.
     */
    private fun sendSetup(ws: WebSocket) {
        val setupPayload = mapOf(
            "setup" to mapOf(
                "model" to modelId,
                "generationConfig" to mapOf(
                    "responseModalities" to listOf("AUDIO"),
                    "speechConfig" to mapOf(
                        "voiceConfig" to mapOf(
                            "prebuiltVoiceConfig" to mapOf(
                                "voiceName" to voiceName
                            )
                        )
                    )
                ),
                "systemInstruction" to mapOf(
                    "parts" to listOf(
                        mapOf("text" to systemPrompt)
                    )
                )
            )
        )

        val json = gson.toJson(setupPayload)
        val sent = ws.send(json)
        Log.d(TAG, "Sent setup frame: model=$modelId, voice=$voiceName (success=$sent)")
    }

    /**
     * Streams a 10ms chunk of 16kHz 16-bit PCM audio to Gemini.
     * Wrapped in realtimeInput.mediaChunks array per official spec.
     */
    fun sendAudioChunk(pcmSamples: ShortArray) {
        val ws = webSocket ?: return

        // Little-endian PCM byte conversion
        val byteBuffer = ByteBuffer.allocate(pcmSamples.size * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcmSamples) byteBuffer.putShort(sample)
        val pcmBytes = byteBuffer.array()

        val base64Audio = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)

        val message = mapOf(
            "realtimeInput" to mapOf(
                "mediaChunks" to listOf(
                    mapOf(
                        "mimeType" to "audio/pcm;rate=16000",
                        "data" to base64Audio
                    )
                )
            )
        )

        ws.send(gson.toJson(message))
    }

    /**
     * Signals to Gemini that the user's speech turn is complete and it should generate audio now.
     */
    fun sendTurnComplete() {
        val ws = webSocket ?: return
        val message = mapOf(
            "clientContent" to mapOf(
                "turnComplete" to true
            )
        )
        val json = gson.toJson(message)
        val sent = ws.send(json)
        Log.d(TAG, "Sent clientContent turnComplete to Gemini (sent=$sent)")
    }

    /**
     * Sends an interruption / turn completion signal to Gemini when barge-in is triggered.
     */
    fun sendInterrupt() {
        val ws = webSocket ?: return
        val message = mapOf(
            "clientContent" to mapOf(
                "turns" to emptyList<Any>(),
                "turnComplete" to true
            )
        )
        ws.send(gson.toJson(message))
        Log.d(TAG, "Barge-in interrupt sent to Gemini")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Server Message Parsing
    // ═══════════════════════════════════════════════════════════════════════

    private fun handleServerMessage(json: String) {
        if (json.isBlank()) return
        try {
            val root = gson.fromJson(json, JsonObject::class.java) ?: return

            // ── 1. Setup Acknowledgement ────────────────────────────────
            if (root.has("setupComplete")) {
                Log.d(TAG, "Gemini Live setup complete confirmed by server! Ready for audio streaming.")
                onSetupComplete?.invoke()
                return
            }

            // ── Session Resumption ──────────────────────────────────────
            if (root.has("sessionResumptionUpdate")) {
                Log.d(TAG, "Gemini session resumption handle updated")
                return
            }

            // ── 2. Server Content & Audio Stream ───────────────────────
            if (root.has("serverContent")) {
                val serverContent = root.getAsJsonObject("serverContent")

                // Handle model response parts (audio + text)
                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getAsJsonObject("modelTurn")
                    val parts = modelTurn.getAsJsonArray("parts")
                    parts?.forEach { partEl ->
                        val part = partEl.asJsonObject
                        if (part.has("inlineData")) {
                            val inlineData = part.getAsJsonObject("inlineData")
                            val b64 = inlineData.get("data")?.asString ?: return@forEach
                            val mimeType = inlineData.get("mimeType")?.asString ?: ""
                            val pcmBytes = Base64.decode(b64, Base64.NO_WRAP)
                            val samples = bytesToShorts(pcmBytes)
                            onAudioResponseChunk?.invoke(samples)
                        } else if (part.has("text")) {
                            val text = part.get("text").asString
                            if (text.isNotBlank()) onTextTranscript?.invoke(text)
                        }
                    }
                }

                // Turn complete signal
                if (serverContent.has("turnComplete") && serverContent.get("turnComplete").asBoolean) {
                    Log.d(TAG, "Gemini response turn finished")
                    onTurnComplete?.invoke()
                }

                // Server-confirmed barge-in interruption
                if (serverContent.has("interrupted") && serverContent.get("interrupted").asBoolean) {
                    Log.d(TAG, "Server confirmed turn interrupted")
                }
            }

            // ── 3. Transcripts ───────────────────────────────────────────
            if (root.has("inputTranscription")) {
                val text = root.getAsJsonObject("inputTranscription").get("text")?.asString ?: ""
                if (text.isNotBlank()) onInputTranscript?.invoke(text)
            }

            if (root.has("outputTranscription")) {
                val text = root.getAsJsonObject("outputTranscription").get("text")?.asString ?: ""
                if (text.isNotBlank()) onTextTranscript?.invoke(text)
            }

        } catch (e: Exception) {
            Log.w(TAG, "Error handling server message: ${e.message}")
        }
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in shorts.indices) shorts[i] = buf.short
        return shorts
    }

    fun disconnect() {
        userInitiatedDisconnect = true
        try {
            webSocket?.close(1000, "User disconnected")
        } catch (e: Exception) {
            Log.w(TAG, "Disconnect error: ${e.message}")
        }
        webSocket = null
    }
}
