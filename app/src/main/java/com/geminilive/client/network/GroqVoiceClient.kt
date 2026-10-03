package com.geminilive.client.network

import android.util.Log
import com.geminilive.client.data.ConversationTurn
import com.geminilive.client.data.TurnRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * GROQ VOICE CLIENT — SPEECH-TO-SPEECH PIPELINE
 *
 * Implements ultra-fast voice interaction using Groq LPUs:
 * 1. Speech-to-Text: Groq Whisper (whisper-large-v3-turbo) at ~216x real-time speed (~150ms)
 * 2. LLM Intelligence: Groq Llama 3.3 70B (llama-3.3-70b-versatile) at 300+ tokens/sec (~200ms)
 *
 * Combined with local VAD and device TTS, achieves sub-500ms full conversational voice turns!
 */
class GroqVoiceClient {

    companion object {
        private const val TAG = "GroqVoiceClient"
        const val DEFAULT_LLM_MODEL = "llama-3.3-70b-versatile"
        const val DEFAULT_WHISPER_MODEL = "whisper-large-v3-turbo"

        private const val GROQ_BASE_URL = "https://api.groq.com/openai/v1"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Converts raw 16-bit PCM mono samples into a standard 44-byte RIFF/WAVE byte array.
     */
    fun pcmToWav(samples: ShortArray, sampleRate: Int = 16000): ByteArray {
        val pcmDataSize = samples.size * 2
        val totalFileSize = 36 + pcmDataSize
        val byteRate = sampleRate * 1 * 2
        val blockAlign = 1 * 2

        val buffer = ByteBuffer.allocate(44 + pcmDataSize).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buffer.put('R'.code.toByte()); buffer.put('I'.code.toByte()); buffer.put('F'.code.toByte()); buffer.put('F'.code.toByte())
        buffer.putInt(totalFileSize)
        buffer.put('W'.code.toByte()); buffer.put('A'.code.toByte()); buffer.put('V'.code.toByte()); buffer.put('E'.code.toByte())

        // fmt subchunk
        buffer.put('f'.code.toByte()); buffer.put('m'.code.toByte()); buffer.put('t'.code.toByte()); buffer.put(' '.code.toByte())
        buffer.putInt(16) // Subchunk1Size for PCM
        buffer.putShort(1.toShort()) // AudioFormat = 1 (PCM)
        buffer.putShort(1.toShort()) // NumChannels = 1 (Mono)
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(16.toShort()) // BitsPerSample = 16

        // data subchunk
        buffer.put('d'.code.toByte()); buffer.put('a'.code.toByte()); buffer.put('t'.code.toByte()); buffer.put('a'.code.toByte())
        buffer.putInt(pcmDataSize)

        for (s in samples) {
            buffer.putShort(s)
        }

        return buffer.array()
    }

    /**
     * Transcribes recorded audio via Groq Whisper API.
     */
    suspend fun transcribeAudio(
        apiKey: String,
        audioSamples: ShortArray,
        model: String = DEFAULT_WHISPER_MODEL
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Groq API key is missing. Please set it in Settings."))
            }

            if (audioSamples.isEmpty()) {
                return@withContext Result.success("")
            }

            val wavBytes = pcmToWav(audioSamples, 16000)
            Log.d(TAG, "Sending " + audioSamples.size + " samples to Groq Whisper (" + model + ")")

            val mediaTypeWav = "audio/wav".toMediaTypeOrNull()
            val fileBody = wavBytes.toRequestBody(mediaTypeWav)

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("temperature", "0")
                .addFormDataPart("response_format", "json")
                .addFormDataPart("file", "speech.wav", fileBody)
                .build()

            val request = Request.Builder()
                .url(GROQ_BASE_URL + "/audio/transcriptions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("User-Agent", "GroqVoiceClient/1.0")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = try {
                    val errJson = JSONObject(responseBody)
                    errJson.getJSONObject("error").getString("message")
                } catch (_: Exception) {
                    "HTTP " + response.code + ": " + responseBody
                }
                return@withContext Result.failure(Exception("Groq Whisper Error: " + errorMsg))
            }

            val json = JSONObject(responseBody)
            val transcript = json.optString("text", "").trim()
            Log.d(TAG, "Groq Whisper transcribed: '" + transcript + "'")
            Result.success(transcript)
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed: " + e.message, e)
            Result.failure(e)
        }
    }

    /**
     * Generates a conversational response using a Groq Llama model.
     */
    suspend fun generateResponse(
        apiKey: String,
        systemPrompt: String,
        userMessage: String,
        history: List<ConversationTurn>,
        model: String = DEFAULT_LLM_MODEL
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Groq API key is missing. Please set it in Settings."))
            }

            val messagesArray = JSONArray()

            // System prompt
            val sysPromptContent = if (systemPrompt.isNotBlank()) {
                systemPrompt + "\nKeep answers direct, friendly, and concise (1-3 sentences) suitable for spoken conversation without markdown or asterisks."
            } else {
                "You are a helpful voice assistant. Keep answers natural, friendly, and concise (1-3 sentences) suitable for spoken conversation without markdown or asterisks."
            }
            messagesArray.put(JSONObject().put("role", "system").put("content", sysPromptContent))

            // Last 6 turns for conversational context
            for (turn in history.takeLast(6)) {
                val role = if (turn.role == TurnRole.USER) "user" else "assistant"
                messagesArray.put(JSONObject().put("role", role).put("content", turn.text))
            }

            // Current user message
            messagesArray.put(JSONObject().put("role", "user").put("content", userMessage))

            val jsonBody = JSONObject().apply {
                put("model", model)
                put("messages", messagesArray)
                put("temperature", 0.7)
                put("max_tokens", 512)
            }

            val request = Request.Builder()
                .url(GROQ_BASE_URL + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "GroqVoiceClient/1.0")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = try {
                    val errJson = JSONObject(responseBody)
                    errJson.getJSONObject("error").getString("message")
                } catch (_: Exception) {
                    "HTTP " + response.code + ": " + responseBody
                }
                return@withContext Result.failure(Exception("Groq LLM Error: " + errorMsg))
            }

            val json = JSONObject(responseBody)
            val reply = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            Log.d(TAG, "Groq LLM reply: '" + reply + "'")
            Result.success(reply)
        } catch (e: Exception) {
            Log.e(TAG, "Chat completion failed: " + e.message, e)
            Result.failure(e)
        }
    }
}
