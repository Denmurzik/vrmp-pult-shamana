package ru.nsu.vrmp.shaman.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.nsu.vrmp.shaman.core.game.GameState
import ru.nsu.vrmp.shaman.core.game.SpiritType
import ru.nsu.vrmp.shaman.core.game.VisibleSpirit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Scanner radar card showing only spirits inside the active scanner cone (±15°, or ±7.5° in fog).
 * Horizontal drag turns the scanner beam continuously.
 */
@Composable
fun RadarArcCard(
    gameState: GameState,
    onSwipeScan: (deltaYawRad: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    var widthPx by remember { mutableFloatStateOf(1f) }
    val headingDeg = ((Math.toDegrees(gameState.scannerYawRad) % 360.0 + 360.0) % 360.0).roundToInt()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "Радар · $headingDeg°",
                    style = MaterialTheme.typography.titleSmall,
                    color = colorScheme.onSurfaceVariant,
                )
                val coneDeg = Math.toDegrees(gameState.effectiveConeHalfAngleRad).roundToInt()
                Text(
                    text = if (gameState.fogActive) "Туман ±$coneDeg°" else "Конус ±$coneDeg°",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (gameState.fogActive) colorScheme.tertiary else colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.84f)
                    .aspectRatio(1f)
                    .padding(vertical = 8.dp)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            val w = widthPx.coerceAtLeast(1f)
                            val deltaRad = (dragAmount / w) * PI * 1.4
                            onSwipeScan(deltaRad)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    widthPx = size.width
                    drawRadar(
                        gameState = gameState,
                        ringColor = colorScheme.outline.copy(alpha = 0.45f),
                        coneFill = colorScheme.primary.copy(alpha = if (gameState.fogActive) 0.14f else 0.22f),
                        beamColor = colorScheme.primary,
                        wanderingColor = colorScheme.tertiary,
                        evilColor = colorScheme.error,
                    )
                }
            }

            Text(
                text = "Свайп влево или вправо вращает сканер",
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun DrawScope.drawRadar(
    gameState: GameState,
    ringColor: Color,
    coneFill: Color,
    beamColor: Color,
    wanderingColor: Color,
    evilColor: Color,
) {
    val radius = size.minDimension / 2f * 0.94f
    val center = Offset(size.width / 2f, size.height / 2f)

    // Concentric range rings
    for (i in 1..4) {
        drawCircle(
            color = ringColor,
            radius = radius * (i / 4f),
            center = center,
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }

    // Crosshair lines
    drawLine(
        color = ringColor.copy(alpha = 0.35f),
        start = Offset(center.x - radius, center.y),
        end = Offset(center.x + radius, center.y),
        strokeWidth = 1.dp.toPx(),
    )
    drawLine(
        color = ringColor.copy(alpha = 0.35f),
        start = Offset(center.x, center.y - radius),
        end = Offset(center.x, center.y + radius),
        strokeWidth = 1.dp.toPx(),
    )

    // Rotating rim ticks to reinforce swipe movement visually
    val tickStepRad = PI / 6.0
    for (k in 0 until 12) {
        val angle = -PI / 2.0 + k * tickStepRad - gameState.scannerYawRad
        val outer = Offset(
            center.x + (radius * cos(angle)).toFloat(),
            center.y + (radius * sin(angle)).toFloat(),
        )
        val inner = Offset(
            center.x + ((radius - 7.dp.toPx()) * cos(angle)).toFloat(),
            center.y + ((radius - 7.dp.toPx()) * sin(angle)).toFloat(),
        )
        drawLine(color = ringColor, start = inner, end = outer, strokeWidth = 1.5.dp.toPx())
    }

    // Active scanner cone centered at 12 o'clock (-90 degrees)
    val coneHalfDeg = Math.toDegrees(gameState.effectiveConeHalfAngleRad).toFloat()
    drawArc(
        color = coneFill,
        startAngle = -90f - coneHalfDeg,
        sweepAngle = coneHalfDeg * 2f,
        useCenter = true,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
    )

    // Center beam line
    drawLine(
        color = beamColor.copy(alpha = 0.85f),
        start = center,
        end = Offset(center.x, center.y - radius),
        strokeWidth = 2.dp.toPx(),
    )

    // Render ONLY spirits inside the cone (gameState.visibleSpirits)
    gameState.visibleSpirits.forEach { visible ->
        drawVisibleSpirit(center, radius, visible, wanderingColor, evilColor)
    }

    // Center emitter dot
    drawCircle(
        color = beamColor,
        radius = 4.5.dp.toPx(),
        center = center,
    )
}

private fun DrawScope.drawVisibleSpirit(
    center: Offset,
    radius: Float,
    visible: VisibleSpirit,
    wanderingColor: Color,
    evilColor: Color,
) {
    // 12 o'clock is -PI/2; positive relativeAngleRad sits clockwise to the right.
    val angle = -PI / 2.0 + visible.relativeAngleRad
    val r = (radius * visible.spirit.distance.toFloat()).coerceIn(radius * 0.15f, radius * 0.92f)
    val pos = Offset(
        center.x + (r * cos(angle)).toFloat(),
        center.y + (r * sin(angle)).toFloat(),
    )

    when (visible.spirit.type) {
        SpiritType.WANDERING -> {
            drawCircle(
                color = wanderingColor.copy(alpha = 0.28f),
                radius = 15.dp.toPx(),
                center = pos,
            )
            drawCircle(
                color = wanderingColor,
                radius = 7.5.dp.toPx(),
                center = pos,
            )
        }

        SpiritType.EVIL -> {
            val outerR = 14.dp.toPx()
            val innerR = 8.dp.toPx()
            drawCircle(
                color = evilColor.copy(alpha = 0.25f),
                radius = outerR,
                center = pos,
            )
            val diamond = Path().apply {
                moveTo(pos.x, pos.y - innerR)
                lineTo(pos.x + innerR, pos.y)
                lineTo(pos.x, pos.y + innerR)
                lineTo(pos.x - innerR, pos.y)
                close()
            }
            drawPath(path = diamond, color = evilColor)
        }
    }
}
