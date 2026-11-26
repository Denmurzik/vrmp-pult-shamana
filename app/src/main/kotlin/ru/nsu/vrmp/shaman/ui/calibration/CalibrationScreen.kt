package ru.nsu.vrmp.shaman.ui.calibration

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.nsu.vrmp.shaman.calibration.CalibrationController
import ru.nsu.vrmp.shaman.calibration.CalibrationUiState
import ru.nsu.vrmp.shaman.calibration.FileCalibrationStore
import ru.nsu.vrmp.shaman.calibration.ImuSampler
import ru.nsu.vrmp.shaman.core.calibration.CalibrationStep
import ru.nsu.vrmp.shaman.core.input.HandFrame
import ru.nsu.vrmp.shaman.core.math.Vec3
import ru.nsu.vrmp.shaman.core.recognition.Gesture
import ru.nsu.vrmp.shaman.feedback.AndroidFeedbackPlayer
import ru.nsu.vrmp.shaman.game.BannerTone
import ru.nsu.vrmp.shaman.ui.theme.ShamanTheme

@Composable
fun CalibrationRoute(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val store = remember { FileCalibrationStore(context) }
    val sampler = remember { ImuSampler(context) }
    val feedbackPlayer = remember { AndroidFeedbackPlayer(context) }
    val scope = rememberCoroutineScope()

    DisposableEffect(sampler, feedbackPlayer) {
        sampler.startListening()
        onDispose {
            sampler.stopListening()
            feedbackPlayer.release()
        }
    }

    val controller = remember(store, feedbackPlayer) {
        CalibrationController(store = store, feedbackPlayer = feedbackPlayer)
    }

    CalibrationScreen(
        state = controller.uiState,
        hasMotionSensors = sampler.hasMotionSensors,
        onCaptureZero = { controller.captureZeroHeading(sampler.currentRotation()) },
        onRecordTemplate = {
            if (controller.uiState.isRecordingImu) return@CalibrationScreen
            controller.startImuRecording()
            sampler.beginRecording()
            scope.launch {
                delay(1_500L)
                val samples = sampler.finishRecording()
                controller.finishImuRecording(samples, allowFallback = !sampler.hasMotionSensors)
            }
        },
        onRunCameraCheck = {
            // Until CameraX + MediaPipe (#13) lands, evaluate a sample frame with valid lighting.
            val sampleHand = HandFrame(
                tNanos = System.nanoTime(),
                present = true,
                landmarks = List(HandFrame.LANDMARK_COUNT) { Vec3(0.5, 0.5, 0.0) },
            )
            controller.evaluateCamera(listOf(sampleHand), lumaMean = 128.0)
        },
        onConfirmCamera = controller::confirmCameraReady,
        onSkipCamera = controller::skipCameraCheck,
        onReset = controller::reset,
        modifier = modifier,
    )
}

@Composable
fun CalibrationScreen(
    state: CalibrationUiState,
    hasMotionSensors: Boolean,
    onCaptureZero: () -> Unit,
    onRecordTemplate: () -> Unit,
    onRunCameraCheck: () -> Unit,
    onConfirmCamera: () -> Unit,
    onSkipCamera: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val cal = state.calibration

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
                text = "Калибровка",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
            )
            OutlinedButton(
                onClick = onReset,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Сбросить")
            }
        }

        when (cal.step) {
            CalibrationStep.ZERO_HEADING -> ZeroHeadingStep(
                onCaptureZero = onCaptureZero,
            )

            CalibrationStep.IMU_TEMPLATES -> ImuTemplatesStep(
                state = state,
                hasMotionSensors = hasMotionSensors,
                onRecordTemplate = onRecordTemplate,
            )

            CalibrationStep.CAMERA_CHECK -> CameraCheckStep(
                state = state,
                onRunCameraCheck = onRunCameraCheck,
                onConfirmCamera = onConfirmCamera,
                onSkipCamera = onSkipCamera,
            )

            CalibrationStep.COMPLETED -> CalibrationCompletedView(
                state = state,
                onReset = onReset,
            )
        }

        CalibrationBanner(state)
    }
}

@Composable
private fun ZeroHeadingStep(onCaptureZero: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = "Шаг 1 из 3 · ориентация",
        style = MaterialTheme.typography.labelLarge,
        color = colors.primary,
    )
    Text(
        text = "Нулевое направление",
        style = MaterialTheme.typography.headlineMedium,
        color = colors.onSurface,
    )
    Text(
        text = "Направьте телефон вперёд и удерживайте его в исходном положении.",
        style = MaterialTheme.typography.bodyLarge,
        color = colors.onSurfaceVariant,
    )

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
            Canvas(modifier = Modifier.size(120.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(colors.outline.copy(alpha = 0.4f), size.minDimension * 0.45f, style = Stroke(2.dp.toPx()))
                drawCircle(colors.outline.copy(alpha = 0.4f), size.minDimension * 0.30f, style = Stroke(1.5.dp.toPx()))
                val phoneW = size.width * 0.26f
                val phoneH = size.height * 0.42f
                drawRoundRect(
                    color = colors.primary,
                    topLeft = Offset(center.x - phoneW / 2f, center.y - phoneH / 2f),
                    size = Size(phoneW, phoneH),
                    cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                    style = Stroke(2.5.dp.toPx()),
                )
            }
            Text(
                text = "Исходное направление",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }

    UpcomingStepCard("Далее · эталоны IMU", "По 5 повторов каждого жеста для DTW")
    UpcomingStepCard("Проверка камеры", "Положение руки и освещение")

    Button(
        onClick = onCaptureZero,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp),
    ) {
        Text("Зафиксировать")
    }
}

@Composable
private fun ImuTemplatesStep(
    state: CalibrationUiState,
    hasMotionSensors: Boolean,
    onRecordTemplate: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val cal = state.calibration
    val gesture = cal.currentTemplateGesture ?: Gesture.CATCH
    val count = cal.templateCounts[gesture] ?: 0
    val progress = (count.toFloat() / cal.targetTemplatesPerGesture.toFloat()).coerceIn(0f, 1f)

    Text(
        text = "Шаг 2 из 3 · эталоны IMU",
        style = MaterialTheme.typography.labelLarge,
        color = colors.primary,
    )
    Text(
        text = "Эталон: ${gestureLabel(gesture)}",
        style = MaterialTheme.typography.headlineMedium,
        color = colors.onSurface,
    )
    Text(
        text = if (hasMotionSensors) {
            "Нажмите «Записать повтор» и выполните жест за 1.5 с."
        } else {
            "Датчики IMU не найдены на устройстве — будет записан тестовый эталон."
        },
        style = MaterialTheme.typography.bodyLarge,
        color = colors.onSurfaceVariant,
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Повторов · $count из ${cal.targetTemplatesPerGesture}",
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
        )
        LinearWavyProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Gesture.entries.forEach { g ->
            val gCount = cal.templateCounts[g] ?: 0
            Surface(
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                color = if (g == gesture) colors.primaryContainer else colors.surfaceContainer,
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(gestureLabel(g), style = MaterialTheme.typography.labelMedium)
                    Text("$gCount / ${cal.targetTemplatesPerGesture}", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }

    Button(
        onClick = onRecordTemplate,
        enabled = !state.isRecordingImu,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp),
    ) {
        Text(if (state.isRecordingImu) "Запись 1.5 с…" else "Записать повтор")
    }
}

@Composable
private fun CameraCheckStep(
    state: CalibrationUiState,
    onRunCameraCheck: () -> Unit,
    onConfirmCamera: () -> Unit,
    onSkipCamera: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val cam = state.calibration.cameraStatus

    Text(
        text = "Шаг 3 из 3 · камера",
        style = MaterialTheme.typography.labelLarge,
        color = colors.primary,
    )
    Text(
        text = "Проверка камеры и освещения",
        style = MaterialTheme.typography.headlineMedium,
        color = colors.onSurface,
    )
    Text(
        text = "Убедитесь, что ладонь помещается в кадр и освещение равномерное.",
        style = MaterialTheme.typography.bodyLarge,
        color = colors.onSurfaceVariant,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        UpcomingStepCard(
            title = "Освещение",
            subtitle = if (cam.lightingOk) "В норме" else "Ожидает проверки",
            modifier = Modifier.weight(1f),
        )
        UpcomingStepCard(
            title = "Видимость руки",
            subtitle = if (cam.handVisible) "Кисть видна" else "Ожидает проверки",
            modifier = Modifier.weight(1f),
        )
    }

    Button(
        onClick = onRunCameraCheck,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
    ) {
        Text("Проверить камеру")
    }

    if (cam.isReady) {
        FilledTonalButton(
            onClick = onConfirmCamera,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        ) {
            Text("Завершить калибровку")
        }
    }

    OutlinedButton(
        onClick = onSkipCamera,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Text("Пропустить камеру (только IMU)")
    }
}

@Composable
private fun CalibrationCompletedView(
    state: CalibrationUiState,
    onReset: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val cal = state.calibration
    val totalTemplates = cal.templateCounts.values.sum()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Калибровка готова", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Нулевое направление: задано · Эталоны DTW: $totalTemplates · " +
                    "Камера: ${if (cal.cameraVerified) "проверена" else "пропущена"}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Button(
                onClick = onReset,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                Text("Перекалибровать")
            }
        }
    }
}

@Composable
private fun UpcomingStepCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CalibrationBanner(state: CalibrationUiState) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (state.bannerTone) {
        BannerTone.SUCCESS -> colors.tertiaryContainer to colors.onTertiaryContainer
        BannerTone.WARNING -> colors.primaryContainer to colors.onPrimaryContainer
        BannerTone.ERROR -> colors.errorContainer to colors.onErrorContainer
        BannerTone.NEUTRAL -> colors.surfaceContainerHigh to colors.onSurfaceVariant
    }

    AnimatedContent(
        targetState = Triple(state.bannerTitle, state.bannerSubtitle, state.bannerTone),
        label = "calibration_banner",
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

private fun gestureLabel(gesture: Gesture): String = when (gesture) {
    Gesture.CATCH -> "Поймать"
    Gesture.BANISH -> "Изгнать"
    Gesture.CANCEL -> "Отмена"
}

@Preview(showBackground = true)
@Composable
private fun CalibrationScreenPreview() {
    val controller = remember { CalibrationController() }
    ShamanTheme(darkTheme = true) {
        Surface {
            CalibrationScreen(
                state = controller.uiState,
                hasMotionSensors = true,
                onCaptureZero = {},
                onRecordTemplate = {},
                onRunCameraCheck = {},
                onConfirmCamera = {},
                onSkipCamera = {},
                onReset = {},
            )
        }
    }
}
