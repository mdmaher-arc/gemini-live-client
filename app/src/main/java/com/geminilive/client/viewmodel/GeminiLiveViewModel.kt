package com.geminilive.client.viewmodel

import android.app.Application
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geminilive.client.audio.GeminiAudioEngine
import com.geminilive.client.data.AiProvider
import com.geminilive.client.data.ConversationTurn
import com.geminilive.client.data.SpectrumData
import com.geminilive.client.data.SessionState
import com.geminilive.client.data.TurnRole
import com.geminilive.client.network.GeminiLiveWebSocketClient
import com.geminilive.client.network.GroqVoiceClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("gemini_prefs")
private val API_KEY_PREF = stringPreferencesKey("api_key")
private val SYSTEM_PROMPT_PREF = stringPreferencesKey("system_prompt")
private val MODEL_ID_PREF = stringPreferencesKey("model_id")
private val VOICE_NAME_PREF = stringPreferencesKey("voice_name")

private val AI_PROVIDER_PREF = stringPreferencesKey("ai_provider")
private val GROQ_API_KEY_PREF = stringPreferencesKey("groq_api_key")
private val GROQ_LLM_MODEL_PREF = stringPreferencesKey("groq_llm_model")
private val GROQ_WHISPER_MODEL_PREF = stringPreferencesKey("groq_whisper_model")

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * DUAL-ENGINE VOICE VIEWMODEL — GEMINI LIVE & GROQ VOICE
 * ═══════════════════════════════════════════════════════════════════════════
 */
class GeminiLiveViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "VoiceAssistantVM"
    }

    private val audioEngine = GeminiAudioEngine(application)
    private val wsClient = GeminiLiveWebSocketClient()
    private val groqClient = GroqVoiceClient()

    // ── Active Provider ──────────────────────────────────────────────────────
    private val _provider = MutableStateFlow(AiProvider.GEMINI)
    val provider: StateFlow<AiProvider> = _provider.asStateFlow()

    // ── UI State ─────────────────────────────────────────────────────────────
    private val _sessionState = MutableStateFlow(SessionState.IDLE)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _spectrumData = MutableStateFlow(SpectrumData())
    val spectrumData: StateFlow<SpectrumData> = _spectrumData.asStateFlow()

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

    // ── Gemini Config ────────────────────────────────────────────────────────
    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _systemPrompt = MutableStateFlow(GeminiLiveWebSocketClient.DEFAULT_SYSTEM_PROMPT)
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _modelId = MutableStateFlow(GeminiLiveWebSocketClient.DEFAULT_MODEL)
    val modelId: StateFlow<String> = _modelId.asStateFlow()

    private val _voiceName = MutableStateFlow("Puck")
    val voiceName: StateFlow<String> = _voiceName.asStateFlow()

    // ── Groq Config ──────────────────────────────────────────────────────────
    private val _groqApiKey = MutableStateFlow("")
    val groqApiKey: StateFlow<String> = _groqApiKey.asStateFlow()

    private val _groqLlmModel = MutableStateFlow(GroqVoiceClient.DEFAULT_LLM_MODEL)
    val groqLlmModel: StateFlow<String> = _groqLlmModel.asStateFlow()

    private val _groqWhisperModel = MutableStateFlow(GroqVoiceClient.DEFAULT_WHISPER_MODEL)
    val groqWhisperModel: StateFlow<String> = _groqWhisperModel.asStateFlow()

    // ── Groq Turn Audio Buffer ───────────────────────────────────────────────
    private val groqAudioChunks = ArrayList<ShortArray>()
    private val groqAudioLock = Any()

    // ── Android Text-To-Speech for Groq ──────────────────────────────────────
    private var tts: TextToSpeech? = null
    @Volatile private var isTtsReady = false

    private val vibrator: Vibrator? by lazy {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            (application.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            application.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    init {
        initTts(application)
        loadPreferences()
        setupCallbacks()
    }

    private fun initTts(context: Context) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _sessionState.value = SessionState.SPEAKING
                    }

                    override fun onDone(utteranceId: String?) {
                        viewModelScope.launch {
                            commitAiTranscript()
                            if (_sessionState.value == SessionState.SPEAKING) {
                                _sessionState.value = SessionState.LISTENING
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        viewModelScope.launch {
                            commitAiTranscript()
                            if (_sessionState.value == SessionState.SPEAKING) {
                                _sessionState.value = SessionState.LISTENING
                            }
                        }
                    }
                })
                Log.d(TAG, "Android TextToSpeech initialized successfully")
            } else {
                Log.e(TAG, "Android TextToSpeech initialization failed ($status)")
            }
        }
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

            val savedProvider = prefs[AI_PROVIDER_PREF] ?: AiProvider.GEMINI.name
            _provider.value = try {
                AiProvider.valueOf(savedProvider)
            } catch (_: Exception) {
                AiProvider.GEMINI
            }
            _groqApiKey.value = prefs[GROQ_API_KEY_PREF] ?: ""
            _groqLlmModel.value = prefs[GROQ_LLM_MODEL_PREF] ?: GroqVoiceClient.DEFAULT_LLM_MODEL
            _groqWhisperModel.value = prefs[GROQ_WHISPER_MODEL_PREF] ?: GroqVoiceClient.DEFAULT_WHISPER_MODEL
        }
    }

    fun saveProvider(provider: AiProvider) {
        _provider.value = provider
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { prefs ->
                prefs[AI_PROVIDER_PREF] = provider.name
            }
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

    fun saveGroqApiKey(key: String) {
        val clean = key.trim().replace("\n", "").replace("\r", "").replace("\"", "")
        _groqApiKey.value = clean
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { prefs ->
                prefs[GROQ_API_KEY_PREF] = clean
            }
        }
    }

    fun saveGroqLlmModel(model: String) {
        _groqLlmModel.value = model
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { prefs ->
                prefs[GROQ_LLM_MODEL_PREF] = model
            }
        }
    }

    fun saveGroqWhisperModel(model: String) {
        _groqWhisperModel.value = model
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { prefs ->
                prefs[GROQ_WHISPER_MODEL_PREF] = model
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
        // Stream audio chunk outward
        audioEngine.onPcmCaptured = { samples ->
            if (_sessionState.value == SessionState.LISTENING) {
                if (_provider.value == AiProvider.GEMINI) {
                    wsClient.sendAudioChunk(samples)
                } else {
                    // In Groq mode: accumulate chunk into current turn buffer
                    synchronized(groqAudioLock) {
                        groqAudioChunks.add(samples.clone())
                    }
                }
            }
        }

        // Spectrum visualizer data
        audioEngine.onSpectrumData = { bands, rms ->
            _spectrumData.value = SpectrumData(bands = bands, overallLevel = rms)
        }

        // Local barge-in kill-switch
        audioEngine.onBargeInDetected = {
            viewModelScope.launch {
                if (_provider.value == AiProvider.GEMINI) {
                    Log.d(TAG, "Barge-in triggered locally — sending interrupt to Gemini")
                    wsClient.sendInterrupt()
                    _sessionState.value = SessionState.LISTENING
                    hapticClick()
                } else {
                    Log.d(TAG, "Barge-in detected in Groq mode — stopping TTS")
                    try { tts?.stop() } catch (_: Exception) {}
                    commitAiTranscript()
                    _sessionState.value = SessionState.LISTENING
                    hapticClick()
                }
            }
        }

        // Automatic VAD speech completion
        audioEngine.onSpeechFinished = {
            viewModelScope.launch {
                if (_sessionState.value == SessionState.LISTENING) {
                    if (_provider.value == AiProvider.GEMINI) {
                        Log.d(TAG, "VAD speech pause detected — sending turnComplete to Gemini")
                        _sessionState.value = SessionState.THINKING
                        wsClient.sendTurnComplete()
                    } else {
                        Log.d(TAG, "VAD speech pause detected — processing turn with Groq")
                        processGroqTurn()
                    }
                }
            }
        }

        // Playback finished -> back to listening (for Gemini)
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

        // WebSocket events for Gemini Live
        wsClient.onConnected = {
            viewModelScope.launch {
                _sessionState.value = SessionState.CONNECTED
                _errorMessage.value = null
                Log.d(TAG, "WebSocket connected — waiting for setup complete")
            }
        }

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

    private fun processGroqTurn() {
        val totalSamples: Int
        val allSamples: ShortArray
        synchronized(groqAudioLock) {
            if (groqAudioChunks.isEmpty()) return
            totalSamples = groqAudioChunks.sumOf { it.size }
            allSamples = ShortArray(totalSamples)
            var offset = 0
            for (chunk in groqAudioChunks) {
                System.arraycopy(chunk, 0, allSamples, offset, chunk.size)
                offset += chunk.size
            }
            groqAudioChunks.clear()
        }

        // Only process if user spoke at least ~0.3s (4800 samples)
        if (totalSamples < 4800) {
            Log.d(TAG, "Audio chunk too short ($totalSamples samples), ignoring")
            return
        }

        viewModelScope.launch {
            _sessionState.value = SessionState.THINKING
            _userTranscript.value = "Transcribing with Groq Whisper…"

            val transcriptResult = groqClient.transcribeAudio(
                apiKey = _groqApiKey.value,
                audioSamples = allSamples,
                model = _groqWhisperModel.value
            )

            val transcript = transcriptResult.getOrElse { error ->
                _errorMessage.value = error.message ?: "Transcription failed"
                _sessionState.value = SessionState.LISTENING
                _userTranscript.value = ""
                return@launch
            }

            if (transcript.isBlank()) {
                Log.d(TAG, "Blank transcript from Whisper, returning to listening")
                _userTranscript.value = ""
                _sessionState.value = SessionState.LISTENING
                return@launch
            }

            _userTranscript.value = transcript
            commitUserTranscript()

            _aiTranscript.value = "Thinking with Llama…"
            val chatResult = groqClient.generateResponse(
                apiKey = _groqApiKey.value,
                systemPrompt = _systemPrompt.value,
                userMessage = transcript,
                history = _conversation.value,
                model = _groqLlmModel.value
            )

            val reply = chatResult.getOrElse { error ->
                _errorMessage.value = error.message ?: "Groq generation failed"
                _sessionState.value = SessionState.LISTENING
                _aiTranscript.value = ""
                return@launch
            }

            _aiTranscript.value = reply
            _sessionState.value = SessionState.SPEAKING
            hapticClick()

            if (isTtsReady && tts != null) {
                val utteranceId = "groq_reply_" + System.currentTimeMillis()
                tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            } else {
                commitAiTranscript()
                _sessionState.value = SessionState.LISTENING
            }
        }
    }

    fun startSession() {
        if (_provider.value == AiProvider.GEMINI) {
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
        } else {
            // Groq mode
            val rawGroqKey = _groqApiKey.value
            val cleanGroqKey = rawGroqKey.trim().replace("\n", "").replace("\r", "").replace("\"", "").replace("'", "")
            if (cleanGroqKey.isBlank()) {
                _errorMessage.value = "Please enter your Groq API key in Settings."
                return
            }

            _sessionState.value = SessionState.LISTENING
            _errorMessage.value = null
            synchronized(groqAudioLock) { groqAudioChunks.clear() }
            audioEngine.startCapture()
            hapticSuccess()
        }
    }

    fun endSession() {
        audioEngine.stopCapture()
        audioEngine.stopPlayback()
        if (_provider.value == AiProvider.GEMINI) {
            wsClient.disconnect()
        } else {
            try { tts?.stop() } catch (_: Exception) {}
            synchronized(groqAudioLock) { groqAudioChunks.clear() }
        }
        _sessionState.value = SessionState.IDLE
        _captureLevel.value = 0f
        _playbackLevel.value = 0f
        _spectrumData.value = SpectrumData()
        commitUserTranscript()
        commitAiTranscript()
    }

    fun manualInterrupt() {
        if (_sessionState.value == SessionState.SPEAKING) {
            if (_provider.value == AiProvider.GEMINI) {
                audioEngine.stopPlayback()
                wsClient.sendInterrupt()
            } else {
                try { tts?.stop() } catch (_: Exception) {}
            }
            commitAiTranscript()
            _sessionState.value = SessionState.LISTENING
            hapticClick()
        }
    }

    fun triggerManualSend() {
        if (_sessionState.value == SessionState.LISTENING) {
            hapticClick()
            if (_provider.value == AiProvider.GEMINI) {
                Log.d(TAG, "Manual turn complete triggered by user (Gemini)")
                _sessionState.value = SessionState.THINKING
                wsClient.sendTurnComplete()
            } else {
                Log.d(TAG, "Manual turn complete triggered by user (Groq)")
                processGroqTurn()
            }
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
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        audioEngine.release()
    }
}
