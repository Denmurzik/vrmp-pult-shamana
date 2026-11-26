package ru.nsu.vrmp.shaman.ui.game

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.nsu.vrmp.shaman.core.game.SpiritType
import ru.nsu.vrmp.shaman.core.recognition.Gesture
import ru.nsu.vrmp.shaman.game.BannerTone
import ru.nsu.vrmp.shaman.game.GameController
import ru.nsu.vrmp.shaman.game.GameUiState
import ru.nsu.vrmp.shaman.ui.theme.ShamanTheme
import java.util.Locale

@Composable
fun GameRoute(modifier: Modifier = Modifier) {
    val controller = remember { GameController() }
    val state = controller.uiState

    LaunchedEffect(state.roundNumber, state.game.finished) {
        while (!controller.uiState.game.finished) {
            delay(50L)
            controller.tick()
        }
    }

    GameScreen(
        state = state,
        onSwipeScan = controller::onSwipeScan,
        onCatch = { controller.onGestureButton(Gesture.CATCH) },
        onBanish = { controller.onGestureButton(Gesture.BANISH) },
        onCancel = { controller.onGestureButton(Gesture.CANCEL) },
        onNewRound = controller::startNewRound,
        modifier = modifier,
    )
}

@Composable
fun GameScreen(
    state: GameUiState,
    onSwipeScan: (Double) -> Unit,
    onCatch: () -> Unit,
    onBanish: () -> Unit,
    onCancel: () -> Unit,
    onNewRound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val game = state.game
    val colors = MaterialTheme.colorScheme
    val totalRoundNanos = 60_000_000_000L
    val timeProgress = (game.remainingNanos.toFloat() / totalRoundNanos.toFloat()).coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Раунд ${state.roundNumber} · тач",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
            )
            OutlinedButton(
                onClick = onNewRound,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Заново")
            }
        }

        // HUD metrics row: Timer / Lives / Score
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HudMetricCard(
                value = formatTimer(game.remainingNanos),
                label = "До конца",
                modifier = Modifier.weight(1f),
            )
            HudMetricCard(
                value = game.lives.toString(),
                label = "Жизни",
                modifier = Modifier.weight(1f),
            )
            HudMetricCard(
                value = game.score.toString(),
                label = "Счёт",
                modifier = Modifier.weight(1f),
            )
        }

        LinearWavyProgressIndicator(
            progress = { timeProgress },
            modifier = Modifier.fillMaxWidth(),
        )

        // Radar arc (only spirits inside cone)
        RadarArcCard(
            gameState = game,
            onSwipeScan = onSwipeScan,
        )

        // Legend / visible target hint without leaking hidden spirits
        val targetHint = when (val first = game.visibleSpirits.firstOrNull()) {
            null -> "В конусе пусто"
            else -> when (first.spirit.type) {
                SpiritType.WANDERING -> "В луче: Блуждающий дух — Поймать"
                SpiritType.EVIL -> "В луче: Злой дух — Изгнать"
            }
        }
        Text(
            text = targetHint,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )

        // Command feedback & undo window banner
        CommandBanner(state = state)

        if (game.finished) {
            RoundSummaryCard(
                score = game.score,
                errors = game.errors,
                misses = game.misses,
                cancelled = game.cancelledCount,
                onNewRound = onNewRound,
            )
        } else {
            // Touch controls routed through TouchRecognizer -> CommandArbiter -> GameEngine
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onCatch,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 54.dp),
                ) {
                    Text("Поймать")
                }
                FilledTonalButton(
                    onClick = onBanish,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 54.dp),
                ) {
                    Text("Изгнать")
                }
            }

            val undoLabel = if (state.arbiter.canCancel) {
                val secs = (state.arbiter.undoRemainingNanos / 1_000_000_000.0)
                String.format(Locale.US, "Отмена (%.1f с)", secs)
            } else {
                "Отмена"
            }
            OutlinedButton(
                onClick = onCancel,
                enabled = state.arbiter.canCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                Text(undoLabel)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun HudMetricCard(
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

@Composable
private fun CommandBanner(state: GameUiState) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (state.bannerTone) {
        BannerTone.SUCCESS -> colors.tertiaryContainer to colors.onTertiaryContainer
        BannerTone.WARNING -> colors.primaryContainer to colors.onPrimaryContainer
        BannerTone.ERROR -> colors.errorContainer to colors.onErrorContainer
        BannerTone.NEUTRAL -> colors.surfaceContainerHigh to colors.onSurfaceVariant
    }

    AnimatedContent(
        targetState = Triple(state.bannerTitle, state.bannerSubtitle, state.bannerTone),
        label = "command_banner",
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
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = content,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.88f),
                )
            }
        }
    }
}

@Composable
private fun RoundSummaryCard(
    score: Int,
    errors: Int,
    misses: Int,
    cancelled: Int,
    onNewRound: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Раунд завершён",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Счёт: $score · Ошибки: $errors · Пропуски: $misses · Отмены: $cancelled",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onNewRound,
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                Text("Новый раунд")
            }
        }
    }
}

private fun formatTimer(nanos: Long): String {
    val totalSeconds = ((nanos + 999_999_999L) / 1_000_000_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

@Preview(showBackground = true)
@Composable
private fun GameScreenPreview() {
    var fakeTime = 1_000_000_000L
    val controller = remember { GameController(clockNanos = { fakeTime }) }
    ShamanTheme(darkTheme = true) {
        Surface {
            GameScreen(
                state = controller.uiState,
                onSwipeScan = {},
                onCatch = {},
                onBanish = {},
                onCancel = {},
                onNewRound = {},
            )
        }
    }
}
