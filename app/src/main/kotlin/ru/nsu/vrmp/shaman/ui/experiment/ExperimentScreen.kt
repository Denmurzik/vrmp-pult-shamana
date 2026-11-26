package ru.nsu.vrmp.shaman.ui.experiment

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.nsu.vrmp.shaman.core.recognition.Gesture
import ru.nsu.vrmp.shaman.experiment.ExperimentController
import ru.nsu.vrmp.shaman.experiment.ExperimentStage
import ru.nsu.vrmp.shaman.experiment.ExperimentUiState
import ru.nsu.vrmp.shaman.feedback.AndroidFeedbackPlayer
import ru.nsu.vrmp.shaman.ui.game.GameScreen
import ru.nsu.vrmp.shaman.ui.theme.ShamanTheme

@Composable
fun ExperimentRoute(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val feedbackPlayer = remember { AndroidFeedbackPlayer(context) }
    DisposableEffect(feedbackPlayer) {
        onDispose { feedbackPlayer.release() }
    }

    val controller = remember(feedbackPlayer) {
        ExperimentController(feedbackPlayer = feedbackPlayer)
    }
    val state = controller.uiState

    LaunchedEffect(state.stage, state.activeGameUi?.game?.finished) {
        while (
            controller.uiState.stage == ExperimentStage.RUNNING_BLOCK &&
            controller.uiState.activeGameUi?.game?.finished == false
        ) {
            delay(50L)
            controller.tickBlock()
        }
    }

    ExperimentScreen(
        state = state,
        onParticipantChange = controller::onParticipantIdChanged,
        onStartBlock = controller::startCurrentBlock,
        onSwipeScan = controller::onSwipeScan,
        onCatch = { controller.onGestureButton(Gesture.CATCH) },
        onBanish = { controller.onGestureButton(Gesture.BANISH) },
        onCancel = { controller.onGestureButton(Gesture.CANCEL) },
        onCompleteBlock = controller::finishCurrentBlock,
        onResetSession = controller::resetSession,
        modifier = modifier,
    )
}

@Composable
fun ExperimentScreen(
    state: ExperimentUiState,
    onParticipantChange: (String) -> Unit,
    onStartBlock: () -> Unit,
    onSwipeScan: (Double) -> Unit,
    onCatch: () -> Unit,
    onBanish: () -> Unit,
    onCancel: () -> Unit,
    onCompleteBlock: () -> Unit,
    onResetSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state.stage) {
        ExperimentStage.RUNNING_BLOCK -> {
            val activeGame = state.activeGameUi
            if (activeGame != null) {
                Column(modifier = modifier.fillMaxSize()) {
                    GameScreen(
                        state = activeGame,
                        onSwipeScan = onSwipeScan,
                        onCatch = onCatch,
                        onBanish = onBanish,
                        onCancel = onCancel,
                        onNewRound = onCompleteBlock,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = onCompleteBlock,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .heightIn(min = 48.dp),
                    ) {
                        Text(
                            if (activeGame.game.finished) "Перейти к следующему блоку" else "Завершить блок",
                        )
                    }
                }
            }
        }

        ExperimentStage.SETUP -> ExperimentSetupView(
            state = state,
            onParticipantChange = onParticipantChange,
            onStartBlock = onStartBlock,
            modifier = modifier,
        )

        ExperimentStage.RESULTS -> ExperimentResultsView(
            state = state,
            onResetSession = onResetSession,
            modifier = modifier,
        )
    }
}

@Composable
private fun ExperimentSetupView(
    state: ExperimentUiState,
    onParticipantChange: (String) -> Unit,
    onStartBlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val block = state.currentBlock
    val locksParticipant = state.results.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Режим экспериментатора",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurfaceVariant,
        )
        Text(
            text = "Эксперимент",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.onSurface,
        )
        Text(
            text = "Порядок по латинскому квадрату, сид по позиции, доп. события отключены.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )

        OutlinedTextField(
            value = state.participantInput,
            onValueChange = onParticipantChange,
            label = { Text("Код участника (P01–P12)") },
            enabled = !locksParticipant,
            isError = state.inputError != null,
            supportingText = state.inputError?.let { err -> { Text(err) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (block != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                InfoMetricCard(
                    value = "${block.position} / ${state.plan.blocks.size}",
                    label = "Позиция",
                    modifier = Modifier.weight(1f),
                )
                InfoMetricCard(
                    value = block.seed.toString(),
                    label = "Сид",
                    modifier = Modifier.weight(1f),
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = colors.surfaceContainer,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = ExperimentController.conditionLabel(block.condition),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                    )
                    Text(
                        text = "Ряд латинского квадрата ${state.plan.latinSquareRow} · ${block.condition.id}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = colors.tertiaryContainer,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Готово к записи блока",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onTertiaryContainer,
                )
                Text(
                    text = "Записано событий в лог: ${state.loggedEventCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onTertiaryContainer.copy(alpha = 0.88f),
                )
            }
        }

        Button(
            onClick = onStartBlock,
            enabled = state.inputError == null && block != null,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp),
        ) {
            Text("Начать блок")
        }
    }
}

@Composable
private fun ExperimentResultsView(
    state: ExperimentUiState,
    onResetSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val totalScore = state.results.sumOf { it.score }
    val totalErrors = state.results.sumOf { it.errors }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Сессия завершена",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.onSurface,
        )
        Text(
            text = "${state.plan.participantId} · пройдены все ${state.results.size} блока · событий в логе: ${state.loggedEventCount}",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InfoMetricCard(
                value = "${state.results.size} / ${state.plan.blocks.size}",
                label = "Блоки",
                modifier = Modifier.weight(1f),
            )
            InfoMetricCard(
                value = totalScore.toString(),
                label = "Успешно",
                modifier = Modifier.weight(1f),
            )
            InfoMetricCard(
                value = totalErrors.toString(),
                label = "Ошибки",
                modifier = Modifier.weight(1f),
            )
        }

        state.results.forEach { result ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = colors.surfaceContainer,
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "${result.block.position} · ${ExperimentController.conditionLabel(result.block.condition)} (сид ${result.block.seed})",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface,
                    )
                    Text(
                        text = "Счёт: ${result.score} · Ошибки: ${result.errors} · Пропуски: ${result.misses} · Отмены: ${result.cancelled}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }

        Button(
            onClick = onResetSession,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        ) {
            Text("Новая сессия")
        }
    }
}

@Composable
private fun InfoMetricCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ExperimentScreenPreview() {
    val controller = remember { ExperimentController() }
    ShamanTheme(darkTheme = true) {
        Surface {
            ExperimentScreen(
                state = controller.uiState,
                onParticipantChange = {},
                onStartBlock = {},
                onSwipeScan = {},
                onCatch = {},
                onBanish = {},
                onCancel = {},
                onCompleteBlock = {},
                onResetSession = {},
            )
        }
    }
}
