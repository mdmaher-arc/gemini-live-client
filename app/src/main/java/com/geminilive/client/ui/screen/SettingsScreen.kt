package com.geminilive.client.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geminilive.client.data.AiProvider
import com.geminilive.client.network.GeminiLiveWebSocketClient
import com.geminilive.client.network.GroqVoiceClient
import com.geminilive.client.ui.theme.AccentEnd
import com.geminilive.client.ui.theme.AccentMid
import com.geminilive.client.ui.theme.AccentStart
import com.geminilive.client.ui.theme.DarkBackground
import com.geminilive.client.ui.theme.DarkBorder
import com.geminilive.client.ui.theme.DarkCard
import com.geminilive.client.ui.theme.DarkSubtext
import com.geminilive.client.ui.theme.DarkText
import com.geminilive.client.ui.theme.GeminiGreen
import com.geminilive.client.viewmodel.GeminiLiveViewModel

@Composable
fun SettingsScreen(
    viewModel: GeminiLiveViewModel,
    onBack: () -> Unit
) {
    val activeProvider by viewModel.provider.collectAsState()
    val savedApiKey by viewModel.apiKey.collectAsState()
    val savedPrompt by viewModel.systemPrompt.collectAsState()
    val savedModel by viewModel.modelId.collectAsState()
    val savedVoice by viewModel.voiceName.collectAsState()

    val savedGroqKey by viewModel.groqApiKey.collectAsState()
    val savedGroqLlm by viewModel.groqLlmModel.collectAsState()
    val savedGroqWhisper by viewModel.groqWhisperModel.collectAsState()

    var selectedProvider by remember(activeProvider) { mutableStateOf(activeProvider) }
    var apiKeyText by remember(savedApiKey) { mutableStateOf(savedApiKey) }
    var promptText by remember(savedPrompt) { mutableStateOf(savedPrompt) }
    var selectedModel by remember(savedModel) { mutableStateOf(savedModel) }
    var selectedVoice by remember(savedVoice) { mutableStateOf(savedVoice) }

    var groqKeyText by remember(savedGroqKey) { mutableStateOf(savedGroqKey) }
    var selectedGroqLlm by remember(savedGroqLlm) { mutableStateOf(savedGroqLlm) }
    var selectedGroqWhisper by remember(savedGroqWhisper) { mutableStateOf(savedGroqWhisper) }

    var apiKeyVisible by remember { mutableStateOf(false) }
    var groqKeyVisible by remember { mutableStateOf(false) }
    var saveStatus by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()

    val voices = listOf("Puck", "Aoede", "Charon", "Fenrir", "Kore")
    val geminiModels = listOf(
        "models/gemini-3.8-live" to "Gemini 3.8 Live (Official Live Audio)",
        "models/gemini-3.8-live-extended-thinking" to "Gemini 3.8 Live (Extended Thinking)"
    )

    val groqLlmModels = listOf(
        "llama-3.3-70b-versatile" to "Llama 3.3 70B (Fast & Intelligent)",
        "llama-3.1-8b-instant" to "Llama 3.1 8B (Ultra Low Latency)",
        "mixtral-8x7b-32768" to "Mixtral 8x7B (MoE)"
    )

    val groqWhisperModels = listOf(
        "whisper-large-v3-turbo" to "Whisper Large v3 Turbo (216x Real-Time Speed)",
        "whisper-large-v3" to "Whisper Large v3 (Maximum Accuracy)"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(scrollState)
        ) {
            Spacer(Modifier.height(16.dp))

            // ── Top Bar ──────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = DarkText)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Assistant Settings",
                    color = DarkText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── SECTION 1: AI Engine Provider ────────────────────────────
            Text(
                text = "VOICE AI ENGINE",
                color = AccentEnd,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilterChip(
                    selected = selectedProvider == AiProvider.GROQ,
                    onClick = { selectedProvider = AiProvider.GROQ },
                    label = { Text("⚡ Groq Voice (Whisper + Llama)") },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentStart,
                        selectedLabelColor = Color.White,
                        containerColor = DarkCard,
                        labelColor = DarkSubtext
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                FilterChip(
                    selected = selectedProvider == AiProvider.GEMINI,
                    onClick = { selectedProvider = AiProvider.GEMINI },
                    label = { Text("✨ Gemini Live (WebSocket)") },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentStart,
                        selectedLabelColor = Color.White,
                        containerColor = DarkCard,
                        labelColor = DarkSubtext
                    ),
                    shape = RoundedCornerShape(10.dp)
                )
            }

            Spacer(Modifier.height(24.dp))

            // ── PROVIDER-SPECIFIC SETTINGS ───────────────────────────────
            if (selectedProvider == AiProvider.GROQ) {
                // ── GROQ SETTINGS ────────────────────────────────────────
                Text(
                    text = "GROQ API CREDENTIALS",
                    color = AccentEnd,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Get your free API key at console.groq.com/keys",
                    color = DarkSubtext,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = groqKeyText,
                    onValueChange = { groqKeyText = it },
                    label = { Text("Groq API Key (gsk_...)", color = DarkSubtext) },
                    visualTransformation = if (groqKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { groqKeyVisible = !groqKeyVisible }) {
                            Icon(
                                if (groqKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle key",
                                tint = DarkSubtext
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = DarkText,
                        unfocusedTextColor = DarkText,
                        focusedBorderColor = AccentMid,
                        unfocusedBorderColor = DarkBorder,
                        focusedContainerColor = DarkCard,
                        unfocusedContainerColor = DarkCard
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Spacer(Modifier.height(20.dp))

                Text(
                    text = "GROQ LLM MODEL (BRAIN)",
                    color = AccentEnd,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(8.dp))

                groqLlmModels.forEach { (modelId, desc) ->
                    val isSelected = selectedGroqLlm == modelId
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) AccentStart.copy(alpha = 0.15f) else DarkCard)
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) AccentStart else DarkBorder,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { selectedGroqLlm = modelId }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Column {
                            Text(
                                text = modelId,
                                color = if (isSelected) Color.White else DarkText,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(text = desc, color = DarkSubtext, fontSize = 11.sp)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                Text(
                    text = "GROQ SPEECH-TO-TEXT MODEL (EARS)",
                    color = AccentEnd,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(8.dp))

                groqWhisperModels.forEach { (modelId, desc) ->
                    val isSelected = selectedGroqWhisper == modelId
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) AccentStart.copy(alpha = 0.15f) else DarkCard)
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) AccentStart else DarkBorder,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { selectedGroqWhisper = modelId }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Column {
                            Text(
                                text = modelId,
                                color = if (isSelected) Color.White else DarkText,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(text = desc, color = DarkSubtext, fontSize = 11.sp)
                        }
                    }
                }
            } else {
                // ── GEMINI SETTINGS ──────────────────────────────────────
                Text(
                    text = "GOOGLE AI STUDIO API KEY",
                    color = AccentEnd,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Get your free key from aistudio.google.com",
                    color = DarkSubtext,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = apiKeyText,
                    onValueChange = { apiKeyText = it },
                    label = { Text("Gemini API Key", color = DarkSubtext) },
                    visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                if (apiKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle key",
                                tint = DarkSubtext
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = DarkText,
                        unfocusedTextColor = DarkText,
                        focusedBorderColor = AccentMid,
                        unfocusedBorderColor = DarkBorder,
                        focusedContainerColor = DarkCard,
                        unfocusedContainerColor = DarkCard
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Spacer(Modifier.height(20.dp))

                Text(
                    text = "GEMINI LIVE MODEL",
                    color = AccentEnd,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(8.dp))

                geminiModels.forEach { (modelId, desc) ->
                    val isSelected = selectedModel == modelId
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) AccentStart.copy(alpha = 0.15f) else DarkCard)
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) AccentStart else DarkBorder,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { selectedModel = modelId }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Column {
                            Text(
                                text = modelId,
                                color = if (isSelected) Color.White else DarkText,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(text = desc, color = DarkSubtext, fontSize = 11.sp)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                Text(
                    text = "GEMINI VOICE",
                    color = AccentEnd,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    voices.forEach { voice ->
                        FilterChip(
                            selected = selectedVoice == voice,
                            onClick = { selectedVoice = voice },
                            label = { Text(voice) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentStart,
                                selectedLabelColor = Color.White,
                                containerColor = DarkCard,
                                labelColor = DarkSubtext
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── SYSTEM PROMPT ────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SYSTEM INSTRUCTION",
                    color = AccentEnd,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                IconButton(
                    onClick = { promptText = GeminiLiveWebSocketClient.DEFAULT_SYSTEM_PROMPT },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Reset prompt",
                        tint = DarkSubtext,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = promptText,
                onValueChange = { promptText = it },
                label = { Text("System Instruction", color = DarkSubtext) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = DarkText,
                    unfocusedTextColor = DarkText,
                    focusedBorderColor = AccentMid,
                    unfocusedBorderColor = DarkBorder,
                    focusedContainerColor = DarkCard,
                    unfocusedContainerColor = DarkCard
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(28.dp))

            // ── Save Button ───────────────────────────────────────────────
            Button(
                onClick = {
                    viewModel.saveProvider(selectedProvider)
                    viewModel.saveApiKey(apiKeyText)
                    viewModel.saveSystemPrompt(promptText)
                    viewModel.saveModelId(selectedModel)
                    viewModel.saveVoiceName(selectedVoice)

                    viewModel.saveGroqApiKey(groqKeyText)
                    viewModel.saveGroqLlmModel(selectedGroqLlm)
                    viewModel.saveGroqWhisperModel(selectedGroqWhisper)

                    saveStatus = "Settings saved successfully!"
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentStart,
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Default.Save, contentDescription = "Save", modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save Configuration", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }

            if (saveStatus != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = saveStatus ?: "",
                    color = GeminiGreen,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}
