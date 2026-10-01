package com.geminilive.client.viewmodel

import android.app.Application
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geminilive.client.audio.GeminiAudioEngine
import com.geminilive.client.data.ConversationTurn
import com.geminilive.client.data.FftData
import com.geminilive.client.data.SessionState
import com.geminilive.client.data.TurnRole
import com.geminilive.client.network.GeminiLiveWebSocketClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("gemini_prefs")
private val API_KEY_PREF = stringPreferencesKey("api_key")
private val SYSTEM_PROMPT_PREF = stringPreferencesKey("system_prompt")

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * GEMINI LIVE VIEWMODEL — SESSION COORDINATOR
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Coordinates the audio engine and WebSocket client, managing the full-duplex
 * session lifecycle:
 *
 *   IDLE → CONNECTING → CONNECTED → LISTENING ↔ SPEAKING
 *
 * All 7 client-side tasks flow through here:
 *   1. AEC / NS / AGC → GeminiAudioEngine handles at capture level
 *   2. Beamforming → Hardware via VOICE_COMMUNICATION source
 *   3. Low-Latency Audio → GeminiAudioEngine (AAudio path)
 *   4. Full-Duplex Stream → sendAudioChunk on every 10ms PCM frame
 *   5. Adaptive Jitter Buffer → GeminiAudioEngine.enqueuePcm()
 *   6. Barge-In Kill-Switch → onBargeInDetected → sendInterrupt + stopPlayback
 *   7. FFT Visualizer → fftData StateFlow → Compose GPU canvas
 */
class GeminiLiveViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "GeminiLiveVM"
    }

    // ── Core components ──────────────────────────────────────────────────────
    private val audioEngine = GeminiAudioEngine(application)
    private val wsClient = GeminiLiveWebSocketClient()

    // ── UI State ─────────────────────────────────────────────────────────────
    private val _sessionState = MutableStateFlow(SessionState.IDLE)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _fftData = MutableStateFlow(FftData())
    val fftData: StateFlow<FftData> = _fftData.asStateFlow()

    private val _captureLevel = MutableStateFlow(0f)
    val captureLevel: StateFlow<Float> = _captureLevel.asStateFlow()

    private val _playbackLevel = MutableStateFlow(0f)
    val playbackLevel: StateFlow<Float> = _playbackLevel.asStateFlow()

    private val _conversation = MutableStateFlow<List<ConversationTurn>>(emptyList())
    val conversation: StateFlow<List<ConversationTurn>> = _conversation.asStateFlow()

    private val _userTranscript = MutableStateFlow("")
    val userTranscript: StateFlow<String> = _userTranscript.asStateFlow()

    private val _aiTranscript = MutableStateFlow("")
    val aiTranscript: StateFlow<String> = _aiTranscript.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _systemPrompt = MutableStateFlow(GeminiLiveWebSocketClient.DEFAULT_SYSTEM_PROMPT)
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    // Haptic vibrator
    private val vibrator: Vibrator? by lazy {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            (application.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            application.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    init {
        loadPreferences()
        setupCallbacks()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Preferences
    // ═════════════════════════════════════════════════════════════════════════

    private fun loadPreferences() {
        viewModelScope.launch {
            val prefs = getApplication<Application>().dataStore.data.first()
            _apiKey.value = prefs[API_KEY_PREF] ?: ""
            _systemPrompt.value = prefs[SYSTEM_PROMPT_PREF] ?: GeminiLiveWebSocketClient.DEFAULT_SYSTEM_PROMPT
        }
    }

    fun saveApiKey(key: String) {
        _apiKey.value = key.trim()
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { prefs ->
                prefs[API_KEY_PREF] = key.trim()
            }
        }
    }

    fun saveSystemPrompt(prompt: String) {
        _systemPrompt.value = prompt
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { prefs ->
                prefs[SYSTEM_PROMPT_PREF] = prompt
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Callback Wiring
    // ═════════════════════════════════════════════════════════════════════════

    private fun setupCallbacks() {
        // ── TASK 4: Every 10ms PCM chunk → send to Gemini ─────────────────
        audioEngine.onPcmCaptured = { samples ->
            if (_sessionState.value == SessionState.LISTENING) {
                wsClient.sendAudioChunk(samples)
            }
        }

        // ── TASK 7: FFT data → UI visualizer StateFlow ─────────────────────
        audioEngine.onFftData = { bands, rms ->
            _fftData.value = FftData(bands = bands, overallLevel = rms)
        }

        // ── TASK 6: Barge-in detected locally → interrupt Gemini ──────────
        audioEngine.onBargeInDetected = {
            viewModelScope.launch {
                Log.d(TAG, "Barge-in! Sending interrupt to Gemini server")
                wsClient.sendInterrupt()
                // Already stopped local playback in AudioEngine — zero latency
                _sessionState.value = SessionState.LISTENING
                hapticClick()
            }
        }

        // ── Playback finished → transition back to LISTENING ──────────────
        audioEngine.onPlaybackFinished = {
            viewModelScope.launch {
                if (_sessionState.value == SessionState.SPEAKING) {
                    Log.d(TAG, "Playback finished → returning to LISTENING")
                    commitAiTranscript()
                    _sessionState.value = SessionState.LISTENING
                    // No need to call anything — audio capture is already running full-duplex
                }
            }
        }

        // ── Volume levels → UI indicators ─────────────────────────────────
        audioEngine.onCaptureLevel = { level -> _captureLevel.value = level }
        audioEngine.onPlaybackLevel = { level -> _playbackLevel.value = level }

        // ── WebSocket Callbacks ────────────────────────────────────────────
        wsClient.onConnected = {
            viewModelScope.launch {
                _sessionState.value = SessionState.CONNECTED
                _errorMessage.value = null
                Log.d(TAG, "Connected to Gemini Live")
                hapticSuccess()
            }
        }

        wsClient.onDisconnected = { reason ->
            viewModelScope.launch {
                Log.d(TAG, "Disconnected: $reason")
                audioEngine.stopCapture()
                audioEngine.stopPlayback()
                _sessionState.value = SessionState.IDLE
                _captureLevel.value = 0f
                _playbackLevel.value = 0f
            }
        }

        wsClient.onError = { error ->
            viewModelScope.launch {
                _errorMessage.value = error
                _sessionState.value = SessionState.ERROR
                audioEngine.stopCapture()
                audioEngine.stopPlayback()
                hapticError()
            }
        }

        // ── Incoming audio from Gemini → TASK 5: Jitter buffer ───────────
        wsClient.onAudioResponseChunk = { samples ->
            if (_sessionState.value != SessionState.SPEAKING) {
                viewModelScope.launch {
                    _sessionState.value = SessionState.SPEAKING
                    hapticClick()
                }
            }
            audioEngine.enqueuePcm(samples)
        }

        wsClient.onTurnComplete = {
            viewModelScope.launch {
                audioEngine.markStreamEnd()
                Log.d(TAG, "Gemini turn complete — draining audio buffer")
            }
        }

        // ── Transcripts ───────────────────────────────────────────────────
        wsClient.onInputTranscript = { text ->
            viewModelScope.launch { _userTranscript.value = text }
        }

        wsClient.onTextTranscript = { text ->
            viewModelScope.launch {
                _aiTranscript.value = _aiTranscript.value + text
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Session Control
    // ═════════════════════════════════════════════════════════════════════════

    fun startSession() {
        val key = _apiKey.value.trim()
        if (key.isBlank()) {
            _errorMessage.value = "Please enter your Google AI Studio API key in Settings."
            return
        }

        _sessionState.value = SessionState.CONNECTING
        _errorMessage.value = null

        // Initialize playback engine (pre-warms AudioTrack for instant first response)
        audioEngine.initPlayback(GeminiAudioEngine.PLAYBACK_SAMPLE_RATE)

        // Connect to Gemini Live WebSocket
        wsClient.connect(key, _systemPrompt.value)

        // Start full-duplex audio capture immediately after connecting
        viewModelScope.launch {
            // Brief delay for WS to open before sending audio
            kotlinx.coroutines.delay(600)
            if (_sessionState.value == SessionState.CONNECTED) {
                _sessionState.value = SessionState.LISTENING
                audioEngine.startCapture()
                hapticSuccess()
            }
        }
    }

    fun endSession() {
        audioEngine.stopCapture()
        audioEngine.stopPlayback()
        wsClient.disconnect()
        _sessionState.value = SessionState.IDLE
        _captureLevel.value = 0f
        _playbackLevel.value = 0f
        _fftData.value = FftData()
        commitUserTranscript()
        commitAiTranscript()
    }

    /**
     * TASK 6: User manually pressed "Interrupt" while Gemini is speaking.
     * Local speaker stops immediately, interrupt signal sent to server.
     */
    fun manualInterrupt() {
        if (_sessionState.value == SessionState.SPEAKING) {
            audioEngine.stopPlayback()       // LOCAL kill — instant
            wsClient.sendInterrupt()         // Tell server to halt generation
            commitAiTranscript()
            _sessionState.value = SessionState.LISTENING
            hapticClick()
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Conversation transcript management
    // ═════════════════════════════════════════════════════════════════════════

    private fun commitUserTranscript() {
        val text = _userTranscript.value.trim()
        if (text.isNotBlank()) {
            _conversation.value = _conversation.value + ConversationTurn(TurnRole.USER, text)
            _userTranscript.value = ""
        }
    }

    private fun commitAiTranscript() {
        val text = _aiTranscript.value.trim()
        if (text.isNotBlank()) {
            _conversation.value = _conversation.value + ConversationTurn(TurnRole.ASSISTANT, text)
            _aiTranscript.value = ""
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TASK 7: Haptic Feedback
    // ═════════════════════════════════════════════════════════════════════════

    private fun hapticClick() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(20)
            }
        } catch (_: Exception) {}
    }

    private fun hapticSuccess() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 60, 30), -1))
            }
        } catch (_: Exception) {}
    }

    private fun hapticError() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 40, 80), -1))
            }
        } catch (_: Exception) {}
    }

    override fun onCleared() {
        super.onCleared()
        endSession()
        audioEngine.release()
    }
}
