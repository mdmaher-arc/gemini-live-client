package com.geminilive.client.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.geminilive.client.data.FftData
import com.geminilive.client.data.SessionState
import com.geminilive.client.ui.theme.AccentEnd
import com.geminilive.client.ui.theme.AccentMid
import com.geminilive.client.ui.theme.AccentStart
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * REAL-TIME WAVEFORM VISUALIZER — GPU Canvas
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * TASK 7: Renders the 64 FFT frequency bands as a radial waveform (orb).
 * Runs entirely on the GPU via Compose Canvas — zero CPU compositing cost.
 *
 * Modes:
 *   IDLE → Gently pulsing orb (breathing animation)
 *   LISTENING → Spiky polar FFT bars responding to user voice
 *   THINKING → Rotating orbital rings
 *   SPEAKING → Fluid sine wave bars responding to AI audio
 *   ERROR → Red pulse
 */
@Composable
fun WaveformVisualizer(
    fftData: FftData,
    sessionState: SessionState,
    captureLevel: Float,
    playbackLevel: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")

    // Breathing animation for IDLE state
    val breathe by infiniteTransition.animateFloat(
        initialValue = 0.85f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "breathe"
    )

    // Rotation for THINKING state
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000), repeatMode = RepeatMode.Restart
        ), label = "rotation"
    )

    val colors = when (sessionState) {
        SessionState.LISTENING -> listOf(AccentStart, AccentMid)
        SessionState.THINKING  -> listOf(AccentMid, AccentEnd)
        SessionState.SPEAKING  -> listOf(AccentEnd, AccentStart)
        SessionState.ERROR     -> listOf(Color(0xFFDB4437), Color(0xFFFF5722))
        SessionState.CONNECTED -> listOf(AccentStart, AccentEnd)
        else -> listOf(Color(0xFF3A3A4A), Color(0xFF5A5A7A))
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxRadius = minOf(size.width, size.height) / 2f * 0.82f

        when (sessionState) {
            SessionState.IDLE, SessionState.CONNECTED -> {
                drawBreathingOrb(cx, cy, maxRadius * breathe, colors)
            }
            SessionState.THINKING, SessionState.CONNECTING -> {
                rotate(degrees = rotation, pivot = Offset(cx, cy)) {
                    drawOrbitalRings(cx, cy, maxRadius, colors)
                }
            }
            SessionState.LISTENING -> {
                drawListeningWave(cx, cy, maxRadius, fftData, captureLevel, colors)
            }
            SessionState.SPEAKING -> {
                drawSpeakingWave(cx, cy, maxRadius, fftData, playbackLevel, colors)
            }
            SessionState.ERROR -> {
                drawBreathingOrb(cx, cy, maxRadius * 0.6f * breathe, colors)
            }
        }
    }
}

// ── IDLE: Soft glowing orb ───────────────────────────────────────────────────
private fun DrawScope.drawBreathingOrb(
    cx: Float, cy: Float, radius: Float, colors: List<Color>
) {
    // Outer glow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(colors[0].copy(alpha = 0.12f), Color.Transparent),
            center = Offset(cx, cy), radius = radius * 1.6f
        ),
        center = Offset(cx, cy), radius = radius * 1.6f
    )
    // Core orb
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(colors[1].copy(alpha = 0.85f), colors[0].copy(alpha = 0.4f)),
            center = Offset(cx, cy), radius = radius
        ),
        center = Offset(cx, cy), radius = radius
    )
}

// ── THINKING: Orbital rings ──────────────────────────────────────────────────
private fun DrawScope.drawOrbitalRings(
    cx: Float, cy: Float, radius: Float, colors: List<Color>
) {
    val brush = Brush.sweepGradient(colors = colors + listOf(Color.Transparent), center = Offset(cx, cy))
    for (i in 0 until 3) {
        val r = radius * (0.5f + i * 0.18f)
        drawCircle(
            brush = brush, radius = r, center = Offset(cx, cy),
            style = Stroke(width = 3f - i * 0.5f)
        )
    }
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(colors[0].copy(alpha = 0.6f), Color.Transparent),
            center = Offset(cx, cy), radius = radius * 0.4f
        ),
        center = Offset(cx, cy), radius = radius * 0.4f
    )
}

// ── LISTENING: Radial polar waveform driven by mic FFT ───────────────────────
private fun DrawScope.drawListeningWave(
    cx: Float, cy: Float, maxRadius: Float,
    fftData: FftData, level: Float, colors: List<Color>
) {
    val bands = fftData.bands
    val numBands = bands.size.coerceAtLeast(1)
    val baseRadius = maxRadius * 0.38f
    val maxSpike = maxRadius * 0.55f

    val path = Path()
    var firstX = 0f
    var firstY = 0f

    for (i in 0 until numBands) {
        val angle = (2.0 * PI * i / numBands - PI / 2.0).toFloat()
        val bandVal = bands[i].coerceIn(0f, 1f)
        val r = baseRadius + bandVal * maxSpike * (0.5f + level * 2f)
        val x = cx + r * cos(angle)
        val y = cy + r * sin(angle)
        if (i == 0) {
            path.moveTo(x, y)
            firstX = x; firstY = y
        } else {
            path.lineTo(x, y)
        }
    }
    path.close()

    // Fill
    drawPath(
        path = path,
        brush = Brush.radialGradient(
            colors = listOf(colors[0].copy(alpha = 0.5f), colors[1].copy(alpha = 0.1f)),
            center = Offset(cx, cy), radius = maxRadius
        )
    )
    // Stroke
    drawPath(
        path = path,
        brush = Brush.sweepGradient(colors = colors + listOf(colors[0]), center = Offset(cx, cy)),
        style = Stroke(width = 2.5f, cap = StrokeCap.Round)
    )

    // Core glow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(colors[0].copy(alpha = 0.6f + level * 0.4f), Color.Transparent),
            center = Offset(cx, cy), radius = baseRadius * 0.8f
        ),
        center = Offset(cx, cy), radius = baseRadius * 0.8f
    )
}

// ── SPEAKING: Fluid sine-based wave driven by speaker audio ──────────────────
private fun DrawScope.drawSpeakingWave(
    cx: Float, cy: Float, maxRadius: Float,
    fftData: FftData, level: Float, colors: List<Color>
) {
    val bands = fftData.bands
    val numBands = bands.size.coerceAtLeast(1)
    val baseRadius = maxRadius * 0.42f

    // Multi-layer wave for depth
    for (layer in 0 until 3) {
        val layerScale = 1f - layer * 0.12f
        val alpha = 0.8f - layer * 0.25f
        val path = Path()

        for (i in 0 until numBands) {
            val angle = (2.0 * PI * i / numBands - PI / 2.0).toFloat()
            val bandVal = bands[i % bands.size].coerceIn(0f, 1f)
            val wave = sin(i.toFloat() * 3f + layer * PI.toFloat() / 3f) * 0.3f + 0.7f
            val r = (baseRadius + bandVal * maxRadius * 0.45f * wave) * layerScale
            val x = cx + r * cos(angle)
            val y = cy + r * sin(angle)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        drawPath(
            path = path,
            brush = Brush.sweepGradient(
                colors = listOf(
                    colors[0].copy(alpha = alpha),
                    colors[1].copy(alpha = alpha * 0.5f),
                    colors[0].copy(alpha = alpha)
                ),
                center = Offset(cx, cy)
            ),
            style = Stroke(width = 2f - layer * 0.4f, cap = StrokeCap.Round)
        )
    }

    // Glowing center
    val glowRadius = baseRadius * (0.5f + level * 0.5f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(colors[1].copy(alpha = 0.7f), Color.Transparent),
            center = Offset(cx, cy), radius = glowRadius
        ),
        center = Offset(cx, cy), radius = glowRadius
    )
}
