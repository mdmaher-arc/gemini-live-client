package com.geminilive.client.data

/**
 * Supported AI engines.
 */
enum class AiProvider {
    GEMINI, // Google Gemini Live WebSocket API
    GROQ    // Groq Speech-to-Speech (Whisper Large v3 + Llama 3.3 70B)
}

/**
 * Session state for voice assistants.
 */
enum class SessionState {
    IDLE,           // App is idle
    CONNECTING,     // Initializing session
    CONNECTED,      // Ready to speak
    LISTENING,      // Microphone open, capturing audio
    THINKING,       // Processing voice turn / thinking
    SPEAKING,       // AI is speaking response
    ERROR           // Connection or API error
}

/**
 * Represents a single message turn in the conversation.
 */
data class ConversationTurn(
    val role: TurnRole,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis()
)

enum class TurnRole { USER, ASSISTANT }

/**
 * Per-band energy magnitudes for the waveform visualizer (0.0..1.0 per band).
 *
 * Produced by the audio engine's perceptual energy filterbank. Note that this is
 * *not* a true FFT/DFT transform — see `GeminiAudioEngine.computeEnergyBands`.
 */
data class SpectrumData(
    val bands: FloatArray = FloatArray(64),
    val overallLevel: Float = 0f
) {
    override fun equals(other: Any?): Boolean = other is SpectrumData &&
            bands.contentEquals(other.bands) && overallLevel == other.overallLevel
    override fun hashCode(): Int = 31 * bands.contentHashCode() + overallLevel.hashCode()
}
