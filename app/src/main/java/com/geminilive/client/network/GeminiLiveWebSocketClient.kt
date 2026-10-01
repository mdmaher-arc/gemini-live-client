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
 * GEMINI LIVE MULTIMODAL API — WEBSOCKET CLIENT
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Implements the Gemini 2.0 Flash Live API bidirectional WebSocket protocol.
 *
 * Protocol flow:
 *   1. Connect to: wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key={API_KEY}
 *   2. Send SETUP message with model configuration and system prompt.
 *   3. Stream raw 16kHz 16-bit PCM audio via realtime_input messages.
 *   4. Receive server content messages containing 24kHz PCM audio chunks.
 *   5. Server sends turn_complete when response is finished.
 *
 * TASK 4 — Full-Duplex Bidirectional:
 *   Audio upload and response download happen simultaneously on separate threads.
 *   OkHttp's WebSocket provides HTTP/1.1 upgrade with TCP full-duplex channels.
 */
class GeminiLiveWebSocketClient {

    companion object {
        private const val TAG = "GeminiWS"
        private const val GEMINI_WS_BASE = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val MODEL_ID = "models/gemini-2.0-flash-live-001"

        const val DEFAULT_SYSTEM_PROMPT = """You are a highly intelligent, warm, and empathetic personal AI assistant. You speak in a natural, conversational tone, like a knowledgeable best friend. Keep your responses concise but complete. Use natural speech patterns including brief acknowledgements ("Got it", "Sure", "Of course"). You understand context, emotion in the user's voice, and adapt your tone accordingly. You can handle any topic: information, analysis, creative writing, coding, math, and more."""
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)

    // ── OkHttp client: aggressive keep-alive, TCP_NODELAY, no read timeout ──
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // Infinite — we stream audio continuously
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)       // Keep WebSocket alive
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var apiKey: String = ""
    private var systemPrompt: String = ""

    // ── Callbacks ────────────────────────────────────────────────────────────
    var onConnected: (() -> Unit)? = null
    var onDisconnected: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /** Called with raw 24kHz 16-bit PCM shorts decoded from Gemini audio response */
    var onAudioResponseChunk: ((ShortArray) -> Unit)? = null

    /** Called when Gemini completes a full response turn */
    var onTurnComplete: (() -> Unit)? = null

    /** Called when Gemini sends a text transcript of the response */
    var onTextTranscript: ((String) -> Unit)? = null

    /** Called with the transcript of what Gemini heard from the user */
    var onInputTranscript: ((String) -> Unit)? = null

    val isConnected: Boolean get() = webSocket != null

    // ═══════════════════════════════════════════════════════════════════════
    // Connection
    // ═══════════════════════════════════════════════════════════════════════

    fun connect(apiKey: String, systemPrompt: String = DEFAULT_SYSTEM_PROMPT) {
        this.apiKey = apiKey
        this.systemPrompt = systemPrompt

        disconnect()

        val url = "$GEMINI_WS_BASE?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .build()

        Log.d(TAG, "Connecting to Gemini Live WebSocket...")

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened — sending setup message")
                sendSetup(ws)
                onConnected?.invoke()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                // Binary messages not expected from Gemini Live (it uses base64 JSON)
                Log.w(TAG, "Unexpected binary message: ${bytes.size} bytes")
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
                webSocket = null
                onDisconnected?.invoke(reason)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                webSocket = null
                onError?.invoke(t.message ?: "Connection failed")
            }
        })
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Protocol Messages
    // ═══════════════════════════════════════════════════════════════════════

    private fun sendSetup(ws: WebSocket) {
        val setup = mapOf(
            "setup" to mapOf(
                "model" to MODEL_ID,
                "generation_config" to mapOf(
                    "response_modalities" to listOf("AUDIO"),
                    "speech_config" to mapOf(
                        "voice_config" to mapOf(
                            "prebuilt_voice_config" to mapOf(
                                "voice_name" to "Aoede"  // Natural, warm voice
                            )
                        )
                    )
                ),
                "system_instruction" to mapOf(
                    "parts" to listOf(
                        mapOf("text" to systemPrompt)
                    )
                ),
                "input_audio_transcription" to mapOf<String, Any>(),   // Get user speech transcript
                "output_audio_transcription" to mapOf<String, Any>()   // Get AI speech transcript
            )
        )

        val json = gson.toJson(setup)
        val sent = ws.send(json)
        Log.d(TAG, "Setup sent (success=$sent)")
    }

    /**
     * TASK 4: Send a 10ms chunk of 16kHz 16-bit PCM audio to Gemini.
     * Called every 10ms by the audio capture engine.
     * The PCM bytes are base64-encoded per the Gemini Live REST/WS spec.
     */
    fun sendAudioChunk(pcmSamples: ShortArray) {
        val ws = webSocket ?: return

        // Convert ShortArray to little-endian byte array
        val byteBuffer = ByteBuffer.allocate(pcmSamples.size * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcmSamples) byteBuffer.putShort(sample)
        val pcmBytes = byteBuffer.array()

        val base64Audio = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)

        val message = mapOf(
            "realtime_input" to mapOf(
                "audio" to mapOf(
                    "data" to base64Audio,
                    "mime_type" to "audio/pcm;rate=16000"
                )
            )
        )

        ws.send(gson.toJson(message))
    }

    /**
     * TASK 6: Signal Gemini to stop generating (barge-in or manual interrupt).
     * This sends an activity_end signal so the server halts the current response.
     */
    fun sendInterrupt() {
        val ws = webSocket ?: return
        val message = mapOf(
            "client_content" to mapOf(
                "turns" to emptyList<Any>(),
                "turn_complete" to true
            )
        )
        ws.send(gson.toJson(message))
        Log.d(TAG, "Interrupt (barge-in) sent to Gemini")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Server Message Parsing
    // ═══════════════════════════════════════════════════════════════════════

    private fun handleServerMessage(json: String) {
        try {
            val root = gson.fromJson(json, JsonObject::class.java)

            // ── Audio response content ─────────────────────────────────
            if (root.has("serverContent")) {
                val serverContent = root.getAsJsonObject("serverContent")

                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getAsJsonObject("modelTurn")
                    val parts = modelTurn.getAsJsonArray("parts")
                    parts?.forEach { partEl ->
                        val part = partEl.asJsonObject
                        when {
                            part.has("inlineData") -> {
                                // Audio response chunk
                                val inlineData = part.getAsJsonObject("inlineData")
                                val b64 = inlineData.get("data")?.asString ?: return@forEach
                                val mimeType = inlineData.get("mimeType")?.asString ?: ""
                                val pcmBytes = Base64.decode(b64, Base64.NO_WRAP)
                                val sampleRate = extractSampleRate(mimeType) // e.g. 24000
                                val samples = bytesToShorts(pcmBytes)
                                onAudioResponseChunk?.invoke(samples)
                            }
                            part.has("text") -> {
                                val text = part.get("text").asString
                                if (text.isNotBlank()) onTextTranscript?.invoke(text)
                            }
                        }
                    }
                }

                // Turn complete signal
                if (serverContent.has("turnComplete") && serverContent.get("turnComplete").asBoolean) {
                    Log.d(TAG, "Gemini turn complete")
                    onTurnComplete?.invoke()
                }
            }

            // ── Input audio transcript (what Gemini heard from you) ──
            if (root.has("inputTranscription")) {
                val text = root.getAsJsonObject("inputTranscription")
                    .get("text")?.asString ?: ""
                if (text.isNotBlank()) onInputTranscript?.invoke(text)
            }

            // ── Output audio transcript ───────────────────────────────
            if (root.has("outputTranscription")) {
                val text = root.getAsJsonObject("outputTranscription")
                    .get("text")?.asString ?: ""
                if (text.isNotBlank()) onTextTranscript?.invoke(text)
            }

        } catch (e: Exception) {
            Log.w(TAG, "Error parsing server message: ${e.message}")
        }
    }

    private fun extractSampleRate(mimeType: String): Int {
        return try {
            val rateStr = mimeType.substringAfter("rate=").substringBefore(";")
            rateStr.toInt()
        } catch (_: Exception) { 24000 }
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in shorts.indices) shorts[i] = buf.short
        return shorts
    }

    fun disconnect() {
        try {
            webSocket?.close(1000, "User disconnected")
        } catch (e: Exception) {
            Log.w(TAG, "Disconnect error: ${e.message}")
        }
        webSocket = null
    }
}
