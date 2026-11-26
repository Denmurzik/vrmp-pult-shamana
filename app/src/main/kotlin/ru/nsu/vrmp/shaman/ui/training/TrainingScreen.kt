package ru.nsu.vrmp.shaman.ui.training

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.nsu.vrmp.shaman.core.recognition.Gesture
import ru.nsu.vrmp.shaman.feedback.AndroidFeedbackPlayer
import ru.nsu.vrmp.shaman.game.BannerTone
import ru.nsu.vrmp.shaman.training.TrainingController
import ru.nsu.vrmp.shaman.training.TrainingUiState
import ru.nsu.vrmp.shaman.ui.theme.ShamanTheme

@Composable
fun TrainingRoute(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val feedbackPlayer = remember { AndroidFeedbackPlayer(context) }
    DisposableEffect(feedbackPlayer) {
        onDispose { feedbackPlayer.release() }
    }

    val controller = remember(feedbackPlayer) {
        TrainingController(feedbackPlayer = feedbackPlayer)
    }

    TrainingScreen(
        state = controller.uiState,
        onPracticeGesture = controller::onTouchPractice,
        onRestart = controller::restart,
        modifier = modifier,
    )
}

@Composable
fun TrainingScreen(
    state: TrainingUiState,
    onPracticeGesture: (Gesture) -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val training = state.training
    val currentGesture = training.currentGesture
    val currentStep = training.currentStep

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Тренировка",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
            )
            OutlinedButton(
                onClick = onRestart,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Сбросить")
            }
        }

        if (training.allCompleted || currentGesture == null || currentStep == null) {
            TrainingSummaryCard(
                state = state,
                onRestart = onRestart,
            )
        } else {
            Text(
                text = "Команда ${training.currentIndex + 1} из ${training.steps.size}",
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
            )
            Text(
                text = gestureTitle(currentGesture),
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onSurface,
            )
            Text(
                text = gestureHint(currentGesture),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )

            GestureIllustrationCard(gesture = currentGesture)

            val progress = (currentStep.successCount.toFloat() / training.targetReps.toFloat()).coerceIn(0f, 1f)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Успешные повторы · ${currentStep.successCount} из ${training.targetReps}",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface,
                    )
                    Text(
                        text = "Попыток: ${currentStep.attemptCount}",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                LinearWavyProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            TrainingFeedbackBanner(state)

            Button(
                onClick = { onPracticeGesture(currentGesture) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp),
            ) {
                Text("Выполнить: ${gestureButtonLabel(currentGesture)}")
            }

            // Extra gesture buttons let the user test wrong-gesture feedback too.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Gesture.entries.filter { it != currentGesture }.forEach { other ->
                    FilledTonalButton(
                        onClick = { onPracticeGesture(other) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Text(gestureButtonLabel(other))
                    }
                }
            }
        }
    }
}

@Composable
private fun GestureIllustrationCard(gesture: Gesture) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Canvas(modifier = Modifier.size(132.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val ringColor = colors.outline.copy(alpha = 0.4f)
                drawCircle(color = ringColor, radius = size.minDimension * 0.46f, style = Stroke(2.dp.toPx()))
                drawCircle(color = ringColor, radius = size.minDimension * 0.30f, style = Stroke(1.5.dp.toPx()))

                // Phone outline
                val phoneW = size.width * 0.24f
                val phoneH = size.height * 0.40f
                drawRoundRect(
                    color = colors.primary,
                    topLeft = Offset(center.x - phoneW / 2f, center.y - phoneH / 2f + 10.dp.toPx()),
                    size = Size(phoneW, phoneH),
                    cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                    style = Stroke(width = 2.5.dp.toPx()),
                )

                when (gesture) {
                    Gesture.CATCH -> drawUpArrow(center, colors.tertiary)
                    Gesture.BANISH -> drawCircleArrow(center, size.minDimension * 0.38f, colors.tertiary)
                    Gesture.CANCEL -> drawShakeWaves(center, colors.tertiary)
                }
            }

            Text(
                text = gestureMotionCaption(gesture),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawUpArrow(center: Offset, color: Color) {
    val startY = center.y + 4.dp.toPx()
    val endY = center.y - 46.dp.toPx()
    drawLine(
        color = color,
        start = Offset(center.x, startY),
        end = Offset(center.x, endY),
        strokeWidth = 3.dp.toPx(),
        cap = StrokeCap.Round,
    )
    val head = Path().apply {
        moveTo(center.x, endY - 4.dp.toPx())
        lineTo(center.x - 8.dp.toPx(), endY + 8.dp.toPx())
        lineTo(center.x + 8.dp.toPx(), endY + 8.dp.toPx())
        close()
    }
    drawPath(head, color = color)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCircleArrow(
    center: Offset,
    radius: Float,
    color: Color,
) {
    drawArc(
        color = color,
        startAngle = -70f,
        sweepAngle = 290f,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawShakeWaves(center: Offset, color: Color) {
    val offset = 28.dp.toPx()
    drawLine(
        color = color,
        start = Offset(center.x - offset, center.y - 12.dp.toPx()),
        end = Offset(center.x - offset - 10.dp.toPx(), center.y + 12.dp.toPx()),
        strokeWidth = 3.dp.toPx(),
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = Offset(center.x + offset, center.y - 12.dp.toPx()),
        end = Offset(center.x + offset + 10.dp.toPx(), center.y + 12.dp.toPx()),
        strokeWidth = 3.dp.toPx(),
        cap = StrokeCap.Round,
    )
}

@Composable
private fun TrainingFeedbackBanner(state: TrainingUiState) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (state.bannerTone) {
        BannerTone.SUCCESS -> colors.tertiaryContainer to colors.onTertiaryContainer
        BannerTone.WARNING -> colors.primaryContainer to colors.onPrimaryContainer
        BannerTone.ERROR -> colors.errorContainer to colors.onErrorContainer
        BannerTone.NEUTRAL -> colors.surfaceContainerHigh to colors.onSurfaceVariant
    }

    AnimatedContent(
        targetState = Triple(state.bannerTitle, state.bannerSubtitle, state.bannerTone),
        label = "training_banner",
    ) { (title, subtitle, _) ->
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = container,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = content)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.88f))
            }
        }
    }
}

@Composable
private fun TrainingSummaryCard(
    state: TrainingUiState,
    onRestart: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Тренировка пройдена",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onSurface,
            )
            Text(
                text = "Всего попыток (скорость обучения): ${state.training.totalAttempts}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            state.training.steps.forEach { step ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = colors.surfaceContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(gestureTitle(step.gesture), style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${step.successCount}/${state.training.targetReps} · попыток: ${step.attemptCount}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
            Button(
                onClick = onRestart,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                Text("Пройти снова")
            }
        }
    }
}

private fun gestureTitle(gesture: Gesture): String = when (gesture) {
    Gesture.CATCH -> "Поймать духа"
    Gesture.BANISH -> "Изгнать духа"
    Gesture.CANCEL -> "Отменить команду"
}

private fun gestureButtonLabel(gesture: Gesture): String = when (gesture) {
    Gesture.CATCH -> "Поймать"
    Gesture.BANISH -> "Изгнать"
    Gesture.CANCEL -> "Отмена"
}

private fun gestureHint(gesture: Gesture): String = when (gesture) {
    Gesture.CATCH -> "Сделайте короткий резкий рывок телефоном вверх (или нажмите «Поймать» в тач-режиме)."
    Gesture.BANISH -> "Опишите круг телефоном перед собой (или нажмите «Изгнать» в тач-режиме)."
    Gesture.CANCEL -> "Встряхните телефон в течение 2 с после команды (или нажмите «Отмена»)."
}

private fun gestureMotionCaption(gesture: Gesture): String = when (gesture) {
    Gesture.CATCH -> "Рывок телефона вверх"
    Gesture.BANISH -> "Круг телефоном"
    Gesture.CANCEL -> "Встряхивание телефона"
}

@Preview(showBackground = true)
@Composable
private fun TrainingScreenPreview() {
    val controller = remember { TrainingController() }
    ShamanTheme(darkTheme = true) {
        Surface {
            TrainingScreen(
                state = controller.uiState,
                onPracticeGesture = {},
                onRestart = {},
            )
        }
    }
}
