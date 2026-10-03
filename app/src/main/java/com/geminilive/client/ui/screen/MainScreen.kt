package com.geminilive.client.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geminilive.client.data.AiProvider
import com.geminilive.client.data.SessionState
import com.geminilive.client.data.TurnRole
import com.geminilive.client.ui.component.WaveformVisualizer
import com.geminilive.client.ui.theme.AccentEnd
import com.geminilive.client.ui.theme.AccentMid
import com.geminilive.client.ui.theme.AccentStart
import com.geminilive.client.ui.theme.DarkBackground
import com.geminilive.client.ui.theme.DarkBorder
import com.geminilive.client.ui.theme.DarkCard
import com.geminilive.client.ui.theme.DarkSubtext
import com.geminilive.client.ui.theme.DarkText
import com.geminilive.client.viewmodel.GeminiLiveViewModel

@Composable
fun MainScreen(viewModel: GeminiLiveViewModel) {
    val sessionState by viewModel.sessionState.collectAsState()
    val spectrumData by viewModel.spectrumData.collectAsState()
    val captureLevel by viewModel.captureLevel.collectAsState()
    val playbackLevel by viewModel.playbackLevel.collectAsState()
    val conversation by viewModel.conversation.collectAsState()
    val userTranscript by viewModel.userTranscript.collectAsState()
    val aiTranscript by viewModel.aiTranscript.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val provider by viewModel.provider.collectAsState()

    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(viewModel = viewModel, onBack = { showSettings = false })
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // ── Gradient background ──────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AccentStart.copy(alpha = 0.06f),
                            DarkBackground
                        ),
                        radius = 1200f
                    )
                )
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Header ───────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (provider == AiProvider.GEMINI) "Gemini Live" else "Groq Voice",
                        color = DarkText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = sessionState.label(provider),
                        color = sessionState.labelColor(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = DarkSubtext)
                }
            }

            // ── Conversation history ─────────────────────────────────────
            val listState = rememberLazyListState()
            LaunchedEffect(conversation.size) {
                if (conversation.isNotEmpty()) listState.animateScrollToItem(conversation.size - 1)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(conversation) { turn ->
                    ConversationBubble(
                        text = turn.text,
                        isUser = turn.role == TurnRole.USER
                    )
                }

                // Live user transcript
                if (userTranscript.isNotBlank()) {
                    item {
                        ConversationBubble(text = userTranscript + "…", isUser = true, isLive = true)
                    }
                }

                // Live AI transcript
                if (aiTranscript.isNotBlank()) {
                    item {
                        ConversationBubble(text = aiTranscript, isUser = false, isLive = true)
                    }
                }
            }

            // ── Error banner ─────────────────────────────────────────────
            AnimatedVisibility(visible = errorMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF5C1010))
                        .clickable { viewModel.clearError() }
                        .padding(12.dp)
                ) {
                    Text(
                        text = "⚠ ${errorMessage ?: ""}  (tap to dismiss)",
                        color = Color(0xFFFFCDD2),
                        fontSize = 13.sp
                    )
                }
            }

            // ── Central Orb Visualizer ───────────────────────────────────
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(280.dp)
                    .padding(16.dp)
            ) {
                WaveformVisualizer(
                    spectrumData = spectrumData,
                    sessionState = sessionState,
                    captureLevel = captureLevel,
                    playbackLevel = playbackLevel,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ── Status label under orb ───────────────────────────────────
            AnimatedContent(
                targetState = sessionState.statusText(provider),
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                label = "status"
            ) { text ->
                Text(
                    text = text,
                    color = DarkSubtext,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // ── Action buttons ────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 48.dp, top = 8.dp)
            ) {
                when (sessionState) {
                    SessionState.IDLE, SessionState.ERROR -> {
                        StartButton(onClick = { viewModel.startSession() })
                    }
                    SessionState.CONNECTING -> {
                        LoadingButton()
                    }
                    SessionState.CONNECTED, SessionState.LISTENING -> {
                        Button(
                            onClick = { viewModel.triggerManualSend() },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentStart,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = "Done Speaking / Send", modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        EndButton(onClick = { viewModel.endSession() })
                    }
                    SessionState.THINKING -> {
                        LoadingButton()
                        Spacer(Modifier.width(16.dp))
                        EndButton(onClick = { viewModel.endSession() })
                    }
                    SessionState.SPEAKING -> {
                        InterruptButton(onClick = { viewModel.manualInterrupt() })
                        Spacer(Modifier.width(8.dp))
                        EndButton(onClick = { viewModel.endSession() })
                    }
                }
            }
        }
    }
}

// ── Conversation bubble ───────────────────────────────────────────────────────
@Composable
private fun ConversationBubble(text: String, isUser: Boolean, isLive: Boolean = false) {
    val bgColor = if (isUser) {
        Brush.horizontalGradient(listOf(AccentStart.copy(alpha = 0.3f), AccentMid.copy(alpha = 0.2f)))
    } else {
        Brush.horizontalGradient(listOf(DarkCard, DarkCard))
    }
    val borderColor = if (isUser) AccentStart.copy(alpha = 0.4f) else DarkBorder
    val alpha = if (isLive) 0.7f else 1f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (isUser) 32.dp else 0.dp,
                end = if (isUser) 0.dp else 32.dp
            )
    ) {
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
                .background(bgColor)
                .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            Text(
                text = text,
                color = DarkText.copy(alpha = alpha),
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    }
}

// ── Buttons ───────────────────────────────────────────────────────────────────
@Composable
private fun StartButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White
        ),
        modifier = Modifier
            .size(80.dp)
            .background(
                Brush.sweepGradient(listOf(AccentStart, AccentMid, AccentEnd, AccentStart)),
                CircleShape
            )
    ) {
        Icon(Icons.Default.Mic, contentDescription = "Start", modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun EndButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF4A1010),
            contentColor = Color(0xFFFF5555)
        ),
        modifier = Modifier.size(64.dp)
    ) {
        Icon(Icons.Default.CallEnd, contentDescription = "End", modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun InterruptButton(onClick: () -> Unit) {
    val scale by animateFloatAsState(1f, label = "int")
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = AccentMid.copy(alpha = 0.3f),
            contentColor = AccentMid
        ),
        modifier = Modifier.size(64.dp).scale(scale)
    ) {
        Icon(Icons.Default.PauseCircle, contentDescription = "Interrupt", modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun LoadingButton() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(DarkCard)
            .border(2.dp, AccentStart.copy(alpha = 0.5f), CircleShape)
    ) {
        Text("…", color = AccentStart, fontSize = 24.sp)
    }
}

// ── Helper extensions ─────────────────────────────────────────────────────────
private fun SessionState.label(provider: AiProvider) = when (this) {
    SessionState.IDLE -> if (provider == AiProvider.GEMINI) "Ready (Gemini)" else "Ready (Groq)"
    SessionState.CONNECTING -> "Connecting…"
    SessionState.CONNECTED -> "Connected"
    SessionState.LISTENING -> "● Listening"
    SessionState.THINKING -> if (provider == AiProvider.GEMINI) "⟳ Gemini Thinking" else "⟳ Groq Thinking"
    SessionState.SPEAKING -> if (provider == AiProvider.GEMINI) "▶ Gemini Speaking" else "▶ Groq Speaking"
    SessionState.ERROR -> "Error"
}

@Composable
private fun SessionState.labelColor() = when (this) {
    SessionState.LISTENING -> Color(0xFF00E676)
    SessionState.SPEAKING -> AccentEnd
    SessionState.THINKING -> AccentMid
    SessionState.ERROR -> Color(0xFFFF5555)
    else -> DarkSubtext
}

private fun SessionState.statusText(provider: AiProvider) = when (this) {
    SessionState.IDLE -> if (provider == AiProvider.GEMINI) "Tap the mic to begin with Gemini Live" else "Tap the mic to begin with Groq Voice"
    SessionState.CONNECTING -> if (provider == AiProvider.GEMINI) "Connecting to Gemini Live…" else "Starting Groq Voice session…"
    SessionState.CONNECTED -> "Connected — starting microphone…"
    SessionState.LISTENING -> if (provider == AiProvider.GEMINI) "Speak naturally. Gemini is listening." else "Speak naturally. Groq Whisper is listening."
    SessionState.THINKING -> if (provider == AiProvider.GEMINI) "Gemini is thinking…" else "Groq Whisper & Llama processing…"
    SessionState.SPEAKING -> "Tap pause to interrupt"
    SessionState.ERROR -> "Error encountered. Check settings & retry."
}
