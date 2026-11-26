package ru.nsu.vrmp.shaman.ui.calibration

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import ru.nsu.vrmp.shaman.camera.CameraPreview
import ru.nsu.vrmp.shaman.camera.HandCamera
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

    val lifecycle = checkNotNull(LocalView.current.findViewTreeLifecycleOwner())
    var cameraActive by remember { mutableStateOf(false) }
    var cameraPreview by remember { mutableStateOf<CameraPreview?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraActive = granted && controller.uiState.calibration.step == CalibrationStep.CAMERA_CHECK
        cameraError = if (granted) null else "Для проверки нужен доступ к камере"
    }
    val cameraVisible = cameraActive && controller.uiState.calibration.step == CalibrationStep.CAMERA_CHECK
    if (cameraVisible) {
        DisposableEffect(lifecycle) {
            val camera = HandCamera(context, lifecycle, { cameraPreview = it }) {
                cameraError = "Не удалось запустить или обработать кадр фронтальной камеры"
                cameraActive = false
                cameraPreview = null
            }
            camera.start()
            onDispose { camera.close() }
        }
    }
    val stopCamera = {
        cameraActive = false
        cameraPreview = null
        cameraError = null
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
            val frame = cameraPreview
            when {
                frame != null -> controller.evaluateCamera(listOf(frame.result.frame), frame.luma)
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                    cameraError = null
                    cameraActive = true
                }
                else -> permission.launch(Manifest.permission.CAMERA)
            }
        },
        onConfirmCamera = { stopCamera(); controller.confirmCameraReady() },
        onSkipCamera = { stopCamera(); controller.skipCameraCheck() },
        onReset = { stopCamera(); controller.reset() },
        cameraPanel = {
            cameraPreview?.let { CameraDiagnostics(it) }
            if (cameraActive && cameraPreview == null) Text("Подготовка фронтальной камеры…")
            cameraError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        },
        cameraButtonLabel = if (cameraActive) "Проверить камеру" else "Включить камеру",
        cameraCheckEnabled = !cameraActive || cameraPreview != null,
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
    cameraPanel: @Composable () -> Unit = {},
    cameraButtonLabel: String = "Проверить камеру",
    cameraCheckEnabled: Boolean = true,
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
                cameraPanel = cameraPanel,
                buttonLabel = cameraButtonLabel,
                checkEnabled = cameraCheckEnabled,
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
    cameraPanel: @Composable () -> Unit,
    buttonLabel: String,
    checkEnabled: Boolean,
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

    cameraPanel()

    Button(
        onClick = onRunCameraCheck,
        enabled = checkEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
    ) {
        Text(buttonLabel)
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
private fun CameraDiagnostics(preview: CameraPreview) {
    val color = MaterialTheme.colorScheme.tertiary
    val bitmap = preview.bitmap
    val points = preview.result.frame.landmarks
    Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height).clip(MaterialTheme.shapes.large)) {
        Image(bitmap.asImageBitmap(), "Предпросмотр фронтальной камеры", Modifier.fillMaxSize().graphicsLayer { scaleX = -1f })
        Canvas(Modifier.fillMaxSize()) {
            fun project(index: Int) = Offset(
                ((1 - points[index].x) * size.width).toFloat(),
                (points[index].y * size.height).toFloat(),
            )
            if (points.isNotEmpty()) {
                for ((from, to) in HAND_EDGES) drawLine(color, project(from), project(to), 2.dp.toPx())
                points.indices.forEach { drawCircle(color, 3.dp.toPx(), project(it)) }
            }
        }
    }
    val fps = preview.result.fps?.let { "%.1f".format(it) } ?: "—"
    val hand = if (points.isEmpty()) "Кисть не видна" else "21 точка кисти"
    Text("$hand · $fps кадр/с", style = MaterialTheme.typography.labelLarge)
    if (!preview.result.exact) Text("Время камеры оценено. Для синхронного датасета нужен источник REALTIME.")
}

private val HAND_EDGES = listOf(
    0 to 1, 1 to 2, 2 to 3, 3 to 4,
    0 to 5, 5 to 6, 6 to 7, 7 to 8,
    5 to 9, 9 to 10, 10 to 11, 11 to 12,
    9 to 13, 13 to 14, 14 to 15, 15 to 16,
    13 to 17, 17 to 18, 18 to 19, 19 to 20, 17 to 0,
)

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
