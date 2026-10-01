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
 * Implements the Gemini 2.0 Flash Multimodal Live API bidirectional WebSocket protocol.
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
        
        // Official Gemini Multimodal Live API model IDs
        const val DEFAULT_MODEL = "models/gemini-2.0-flash-exp"
        const val FALLBACK_MODEL = "models/gemini-2.0-flash"

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
        this.modelId = if (model.isNotBlank()) model else DEFAULT_MODEL
        this.voiceName = if (voice.isNotBlank()) voice else "Puck"
        this.userInitiatedDisconnect = false

        disconnect()

        val url = "$GEMINI_WS_BASE?key=$cleanKey"
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
                Log.w(TAG, "Received unexpected binary message: ${bytes.size} bytes")
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closing by server: code=$code, reason='$reason'")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed: code=$code, reason='$reason'")
                webSocket = null
                if (!userInitiatedDisconnect) {
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
                onError?.invoke(detail)
            }
        })
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
        try {
            val root = gson.fromJson(json, JsonObject::class.java)

            // ── 1. Setup Acknowledgement ────────────────────────────────
            if (root.has("setupComplete")) {
                Log.d(TAG, "Gemini Live setup complete confirmed by server! Ready for audio streaming.")
                onSetupComplete?.invoke()
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
