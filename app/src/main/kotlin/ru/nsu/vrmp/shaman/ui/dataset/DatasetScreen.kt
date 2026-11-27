package ru.nsu.vrmp.shaman.ui.dataset

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.nsu.vrmp.shaman.calibration.ImuSampler
import ru.nsu.vrmp.shaman.core.dataset.DatasetCaptureMode
import ru.nsu.vrmp.shaman.core.dataset.DatasetCapturePhase
import ru.nsu.vrmp.shaman.core.dataset.DatasetCaptureSession
import ru.nsu.vrmp.shaman.core.dataset.GestureSpeed
import ru.nsu.vrmp.shaman.core.dataset.NullActivity
import ru.nsu.vrmp.shaman.core.recognition.Gesture
import ru.nsu.vrmp.shaman.dataset.DatasetController
import ru.nsu.vrmp.shaman.dataset.DatasetUiState
import ru.nsu.vrmp.shaman.feedback.AndroidFeedbackPlayer
import ru.nsu.vrmp.shaman.game.BannerTone
import ru.nsu.vrmp.shaman.ui.theme.ShamanTheme
import java.util.Locale

@Composable
fun DatasetRoute(modifier: Modifier = Modifier) {
    val activityContext = LocalContext.current
    val appContext = activityContext.applicationContext
    val sampler = remember { ImuSampler(appContext) }
    val feedbackPlayer = remember { AndroidFeedbackPlayer(appContext) }
    val controller = remember(sampler, feedbackPlayer) {
        DatasetController(
            context = appContext,
            feedbackPlayer = feedbackPlayer,
            hasMotionSensors = sampler.hasMotionSensors,
        )
    }

    DisposableEffect(sampler, feedbackPlayer, controller) {
        sampler.onImuFrame = controller::onImuFrame
        sampler.startListening()
        onDispose {
            sampler.onImuFrame = null
            sampler.stopListening()
            controller.close()
            feedbackPlayer.release()
        }
    }

    val state = controller.uiState
    LaunchedEffect(state.capture.phase) {
        while (
            controller.uiState.capture.phase == DatasetCapturePhase.COUNTDOWN ||
            controller.uiState.capture.phase == DatasetCapturePhase.RECORDING
        ) {
            delay(40L)
            controller.onTick()
        }
    }

    DatasetScreen(
        state = state,
        onParticipantChange = controller::onParticipantInputChanged,
        onSelectMode = controller::onSelectMode,
        onSelectGesture = controller::onSelectGesture,
        onSelectSpeed = controller::onSelectSpeed,
        onSelectNullActivity = controller::onSelectNullActivity,
        onStart = controller::onStartTrial,
        onStopEarly = controller::onStopEarly,
        onCancel = controller::onCancelTrial,
        onConfirm = controller::onConfirmTrial,
        onRepeat = controller::onRepeatTrial,
        onExportZip = { controller.onExportZip(activityContext) },
        modifier = modifier,
    )
}

@Composable
fun DatasetScreen(
    state: DatasetUiState,
    onParticipantChange: (String) -> Unit,
    onSelectMode: (DatasetCaptureMode) -> Unit,
    onSelectGesture: (Gesture) -> Unit,
    onSelectSpeed: (GestureSpeed) -> Unit,
    onSelectNullActivity: (NullActivity) -> Unit,
    onStart: () -> Unit,
    onStopEarly: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onRepeat: () -> Unit,
    onExportZip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val capture = state.capture
    val controlsLocked = capture.phase == DatasetCapturePhase.COUNTDOWN ||
        capture.phase == DatasetCapturePhase.RECORDING

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Сбор размеченных записей",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurfaceVariant,
        )
        Text(
            text = "Датасет",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.onSurface,
        )

        DatasetBanner(
            title = state.bannerTitle,
            subtitle = state.bannerSubtitle,
            tone = state.bannerTone,
        )

        OutlinedTextField(
            value = state.participantInput,
            onValueChange = onParticipantChange,
            enabled = !controlsLocked,
            label = { Text("Анонимный ID участника (P01–P99)") },
            isError = state.participantError != null,
            supportingText = {
                Text(
                    state.participantError ?: if (state.hasMotionSensors) {
                        "Без имён и персональных данных"
                    } else {
                        "Датчики IMU не найдены на устройстве"
                    },
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        ModeSwitchRow(
            selected = capture.mode,
            enabled = !controlsLocked,
            onSelect = onSelectMode,
        )

        if (capture.mode == DatasetCaptureMode.GESTURE) {
            GestureSelectorSection(
                selectedGesture = capture.selectedGesture,
                selectedSpeed = capture.selectedSpeed,
                enabled = !controlsLocked,
                onSelectGesture = onSelectGesture,
                onSelectSpeed = onSelectSpeed,
            )
        } else {
            NullActivitySelectorSection(
                selectedActivity = capture.selectedNullActivity,
                enabled = !controlsLocked,
                onSelectActivity = onSelectNullActivity,
            )
        }

        CapturePromptCard(
            state = state,
            onStart = onStart,
            onStopEarly = onStopEarly,
            onCancel = onCancel,
            onConfirm = onConfirm,
            onRepeat = onRepeat,
        )

        SessionSummaryCard(
            state = state,
            onExportZip = onExportZip,
        )
    }
}

@Composable
private fun ModeSwitchRow(
    selected: DatasetCaptureMode,
    enabled: Boolean,
    onSelect: (DatasetCaptureMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SelectableOptionButton(
            label = "Жесты (2.5 с)",
            selected = selected == DatasetCaptureMode.GESTURE,
            enabled = enabled,
            onClick = { onSelect(DatasetCaptureMode.GESTURE) },
            modifier = Modifier.weight(1f),
        )
        SelectableOptionButton(
            label = "Нулевые (2 мин)",
            selected = selected == DatasetCaptureMode.NULL_STREAM,
            enabled = enabled,
            onClick = { onSelect(DatasetCaptureMode.NULL_STREAM) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun GestureSelectorSection(
    selectedGesture: Gesture,
    selectedSpeed: GestureSpeed,
    enabled: Boolean,
    onSelectGesture: (Gesture) -> Unit,
    onSelectSpeed: (GestureSpeed) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Жест",
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Gesture.entries.forEach { gesture ->
                    SelectableOptionButton(
                        label = DatasetCaptureSession.gestureDisplayNameRu(gesture),
                        selected = selectedGesture == gesture,
                        enabled = enabled,
                        onClick = { onSelectGesture(gesture) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Text(
                text = "Скорость",
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GestureSpeed.entries.forEach { speed ->
                    SelectableOptionButton(
                        label = speed.displayNameRu.replaceFirstChar { it.titlecase(Locale.getDefault()) },
                        selected = selectedSpeed == speed,
                        enabled = enabled,
                        onClick = { onSelectSpeed(speed) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun NullActivitySelectorSection(
    selectedActivity: NullActivity,
    enabled: Boolean,
    onSelectActivity: (NullActivity) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Фоновая активность (2 мин)",
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
            val rows = NullActivity.entries.chunked(2)
            rows.forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowItems.forEach { activity ->
                        SelectableOptionButton(
                            label = activity.displayNameRu,
                            selected = selectedActivity == activity,
                            enabled = enabled,
                            onClick = { onSelectActivity(activity) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CapturePromptCard(
    state: DatasetUiState,
    onStart: () -> Unit,
    onStopEarly: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onRepeat: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val capture = state.capture

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = capture.promptText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )

            when (capture.phase) {
                DatasetCapturePhase.IDLE -> {
                    val windowLabel = if (capture.mode == DatasetCaptureMode.GESTURE) "Окно 2.5 с · отсчёт 3 с" else "Окно 2 мин · отсчёт 3 с"
                    Text(
                        text = windowLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Button(
                        onClick = onStart,
                        enabled = state.participantError == null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                    ) {
                        Text("Старт")
                    }
                }

                DatasetCapturePhase.COUNTDOWN -> {
                    Text(
                        text = "${capture.countdownSecondsRemaining}",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                    )
                    LinearWavyProgressIndicator(
                        progress = { capture.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text("Отмена")
                    }
                }

                DatasetCapturePhase.RECORDING -> {
                    Text(
                        text = formatRemaining(capture.remainingNanos, capture.mode),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.tertiary,
                    )
                    LinearWavyProgressIndicator(
                        progress = { capture.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (capture.mode == DatasetCaptureMode.NULL_STREAM) {
                            FilledTonalButton(
                                onClick = onStopEarly,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                            ) {
                                Text("Стоп и сохранить")
                            }
                        }
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                        ) {
                            Text("Отмена")
                        }
                    }
                }

                DatasetCapturePhase.REVIEW -> {
                    val pending = capture.pendingLabel
                    val durationSec = pending?.let { (it.tEndNanos - it.tStartNanos) / 1_000_000_000.0 } ?: 0.0
                    Text(
                        text = String.format(Locale.US, "Записано %.1f с (%s / %s)", durationSec, pending?.gesture.orEmpty(), pending?.speed.orEmpty()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onRepeat,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp),
                        ) {
                            Text("Повторить")
                        }
                        Button(
                            onClick = onConfirm,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp),
                        ) {
                            Text("Подтвердить")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionSummaryCard(
    state: DatasetUiState,
    onExportZip: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val capture = state.capture
    val currentKey = when (capture.mode) {
        DatasetCaptureMode.GESTURE ->
            "${DatasetCaptureSession.gestureLabelName(capture.selectedGesture)}:${capture.selectedSpeed.labelName}"
        DatasetCaptureMode.NULL_STREAM ->
            "${DatasetCaptureSession.NULL_GESTURE_LABEL}:${capture.selectedNullActivity.labelName}"
    }
    val currentCount = capture.countsByPromptKey[currentKey] ?: 0

    Surface(
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Сэмплов в сессии: ${capture.committedCount}",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                )
                Text(
                    text = "По текущему: $currentCount",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            Text(
                text = "Кадры IMU: ${state.imuFramesRecorded} · Кадры руки: ${state.handFramesRecorded}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            state.sessionId?.let { id ->
                Text(
                    text = "Сессия: $id",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                onClick = onExportZip,
                enabled = state.canExport,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text("Экспорт ZIP")
            }
        }
    }
}

@Composable
private fun SelectableOptionButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 44.dp),
        ) {
            Text(label, maxLines = 1)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 44.dp),
        ) {
            Text(label, maxLines = 1)
        }
    }
}

@Composable
private fun DatasetBanner(
    title: String,
    subtitle: String,
    tone: BannerTone,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        BannerTone.NEUTRAL -> colors.surfaceContainer to colors.onSurface
        BannerTone.SUCCESS -> colors.primaryContainer to colors.onPrimaryContainer
        BannerTone.WARNING -> colors.tertiaryContainer to colors.onTertiaryContainer
        BannerTone.ERROR -> colors.errorContainer to colors.onErrorContainer
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = container,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = content,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = content,
            )
        }
    }
}

private fun formatRemaining(remainingNanos: Long, mode: DatasetCaptureMode): String {
    val totalSeconds = (remainingNanos.coerceAtLeast(0L) / 1_000_000_000.0)
    return if (mode == DatasetCaptureMode.GESTURE) {
        String.format(Locale.US, "%.1f с", totalSeconds)
    } else {
        val secInt = kotlin.math.ceil(totalSeconds).toInt()
        val mins = secInt / 60
        val secs = secInt % 60
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}

@Preview(showBackground = true)
@Composable
private fun DatasetScreenPreview() {
    val session = DatasetCaptureSession(participantId = "P01")
    session.selectGesture(Gesture.BANISH)
    session.selectSpeed(GestureSpeed.FAST)
    ShamanTheme(darkTheme = true) {
        DatasetScreen(
            state = DatasetUiState(
                capture = session.currentState(),
                canExport = true,
                imuFramesRecorded = 420,
            ),
            onParticipantChange = {},
            onSelectMode = {},
            onSelectGesture = {},
            onSelectSpeed = {},
            onSelectNullActivity = {},
            onStart = {},
            onStopEarly = {},
            onCancel = {},
            onConfirm = {},
            onRepeat = {},
            onExportZip = {},
        )
    }
}
