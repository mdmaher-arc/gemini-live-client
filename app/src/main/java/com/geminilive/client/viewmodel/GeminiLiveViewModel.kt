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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("gemini_prefs")
private val API_KEY_PREF = stringPreferencesKey("api_key")
private val SYSTEM_PROMPT_PREF = stringPreferencesKey("system_prompt")
private val MODEL_ID_PREF = stringPreferencesKey("model_id")
private val VOICE_NAME_PREF = stringPreferencesKey("voice_name")

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * GEMINI LIVE VIEWMODEL — SESSION COORDINATOR
 * ═══════════════════════════════════════════════════════════════════════════
 */
class GeminiLiveViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "GeminiLiveVM"
    }

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

    private val _systemPrompt = MutableStateFlow<String>(GeminiLiveWebSocketClient.DEFAULT_SYSTEM_PROMPT)
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _modelId = MutableStateFlow(GeminiLiveWebSocketClient.DEFAULT_MODEL)
    val modelId: StateFlow<String> = _modelId.asStateFlow()

    private val _voiceName = MutableStateFlow("Puck")
    val voiceName: StateFlow<String> = _voiceName.asStateFlow()

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

    private fun loadPreferences() {
        viewModelScope.launch {
            val prefs = getApplication<Application>().dataStore.data.first()
            _apiKey.value = prefs[API_KEY_PREF] ?: ""
            _systemPrompt.value = prefs[SYSTEM_PROMPT_PREF] ?: GeminiLiveWebSocketClient.DEFAULT_SYSTEM_PROMPT
            val savedModel = prefs[MODEL_ID_PREF] ?: GeminiLiveWebSocketClient.DEFAULT_MODEL
            _modelId.value = if (savedModel.contains("2.0") || savedModel.contains("flash-exp")) {
                GeminiLiveWebSocketClient.DEFAULT_MODEL
            } else {
                savedModel
            }
            _voiceName.value = prefs[VOICE_NAME_PREF] ?: "Puck"
        }
    }

    fun saveApiKey(key: String) {
        val clean = key.trim().replace("\n", "").replace("\r", "").replace("\"", "")
        _apiKey.value = clean
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { prefs ->
                prefs[API_KEY_PREF] = clean
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

    fun saveModelId(model: String) {
        _modelId.value = model
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { prefs ->
                prefs[MODEL_ID_PREF] = model
            }
        }
    }

    fun saveVoiceName(voice: String) {
        _voiceName.value = voice
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { prefs ->
                prefs[VOICE_NAME_PREF] = voice
            }
        }
    }

    private fun setupCallbacks() {
        // Stream audio chunk outward to Gemini
        audioEngine.onPcmCaptured = { samples ->
            if (_sessionState.value == SessionState.LISTENING) {
                wsClient.sendAudioChunk(samples)
            }
        }

        // FFT visualizer data
        audioEngine.onFftData = { bands, rms ->
            _fftData.value = FftData(bands = bands, overallLevel = rms)
        }

        // Local barge-in kill-switch
        audioEngine.onBargeInDetected = {
            viewModelScope.launch {
                Log.d(TAG, "Barge-in triggered locally — sending interrupt to Gemini")
                wsClient.sendInterrupt()
                _sessionState.value = SessionState.LISTENING
                hapticClick()
            }
        }

        // Automatic VAD speech completion
        audioEngine.onSpeechFinished = {
            viewModelScope.launch {
                if (_sessionState.value == SessionState.LISTENING) {
                    Log.d(TAG, "VAD speech pause detected — sending turnComplete to Gemini")
                    _sessionState.value = SessionState.THINKING
                    wsClient.sendTurnComplete()
                }
            }
        }

        // Playback finished -> back to listening
        audioEngine.onPlaybackFinished = {
            viewModelScope.launch {
                if (_sessionState.value == SessionState.SPEAKING) {
                    commitAiTranscript()
                    _sessionState.value = SessionState.LISTENING
                }
            }
        }

        audioEngine.onCaptureLevel = { level -> _captureLevel.value = level }
        audioEngine.onPlaybackLevel = { level -> _playbackLevel.value = level }

        // WebSocket events
        wsClient.onConnected = {
            viewModelScope.launch {
                _sessionState.value = SessionState.CONNECTED
                _errorMessage.value = null
                Log.d(TAG, "WebSocket connected — waiting for setup complete")
            }
        }

        // Handshake confirmed by Gemini server
        wsClient.onSetupComplete = {
            viewModelScope.launch {
                Log.d(TAG, "Gemini session setup complete! Starting microphone capture.")
                _sessionState.value = SessionState.LISTENING
                _errorMessage.value = null
                audioEngine.startCapture()
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
                Log.e(TAG, "Session Error: $error")
                audioEngine.stopCapture()
                audioEngine.stopPlayback()
                _errorMessage.value = error
                _sessionState.value = SessionState.ERROR
                hapticError()
            }
        }

        // Play incoming audio chunks from Gemini
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
            }
        }

        wsClient.onInputTranscript = { text ->
            viewModelScope.launch { _userTranscript.value = text }
        }

        wsClient.onTextTranscript = { text ->
            viewModelScope.launch {
                _aiTranscript.value = _aiTranscript.value + text
            }
        }
    }

    fun startSession() {
        val rawKey = _apiKey.value
        val cleanKey = rawKey.trim().replace("\n", "").replace("\r", "").replace("\"", "").replace("'", "")
        if (cleanKey.isBlank()) {
            _errorMessage.value = "Please enter your Google AI Studio API key in Settings."
            return
        }

        _sessionState.value = SessionState.CONNECTING
        _errorMessage.value = null

        audioEngine.initPlayback(GeminiAudioEngine.PLAYBACK_SAMPLE_RATE)
        wsClient.connect(cleanKey, _systemPrompt.value, _modelId.value, _voiceName.value)
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

    fun manualInterrupt() {
        if (_sessionState.value == SessionState.SPEAKING) {
            audioEngine.stopPlayback()
            wsClient.sendInterrupt()
            commitAiTranscript()
            _sessionState.value = SessionState.LISTENING
            hapticClick()
        }
    }

    fun triggerManualSend() {
        if (_sessionState.value == SessionState.LISTENING) {
            Log.d(TAG, "Manual turn complete triggered by user")
            _sessionState.value = SessionState.THINKING
            wsClient.sendTurnComplete()
            hapticClick()
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

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
