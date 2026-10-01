package com.geminilive.client.data

/**
 * Gemini Live WebSocket protocol session state.
 */
enum class SessionState {
    IDLE,           // App is idle, not connected
    CONNECTING,     // WebSocket handshake in progress
    CONNECTED,      // Connected, ready to speak
    LISTENING,      // Microphone open, streaming user audio to Gemini
    THINKING,       // Audio sent, waiting for Gemini to begin responding
    SPEAKING,       // Gemini is playing response audio through speaker
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
 * Frequency band magnitudes for the FFT visualizer (0.0–1.0 per band).
 */
data class FftData(
    val bands: FloatArray = FloatArray(64),
    val overallLevel: Float = 0f
) {
    override fun equals(other: Any?): Boolean = other is FftData &&
            bands.contentEquals(other.bands) && overallLevel == other.overallLevel
    override fun hashCode(): Int = 31 * bands.contentHashCode() + overallLevel.hashCode()
}
