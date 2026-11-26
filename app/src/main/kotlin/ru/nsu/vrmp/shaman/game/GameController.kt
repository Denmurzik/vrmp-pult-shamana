package ru.nsu.vrmp.shaman.game

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.nsu.vrmp.shaman.core.feedback.CommandFeedbackKind
import ru.nsu.vrmp.shaman.core.feedback.FeedbackPlanner
import ru.nsu.vrmp.shaman.core.game.CommandErrorReason
import ru.nsu.vrmp.shaman.core.game.CommandOutcome
import ru.nsu.vrmp.shaman.core.game.GameConfig
import ru.nsu.vrmp.shaman.core.game.GameEngine
import ru.nsu.vrmp.shaman.core.game.GameState
import ru.nsu.vrmp.shaman.core.game.SpiritType
import ru.nsu.vrmp.shaman.core.input.TouchAction
import ru.nsu.vrmp.shaman.core.input.TouchFrame
import ru.nsu.vrmp.shaman.core.recognition.Gesture
import ru.nsu.vrmp.shaman.core.recognition.RecognizerEvent
import ru.nsu.vrmp.shaman.core.recognition.TouchRecognizer
import ru.nsu.vrmp.shaman.feedback.FeedbackPlayer
import ru.nsu.vrmp.shaman.feedback.NoOpFeedbackPlayer

enum class BannerTone {
    NEUTRAL,
    SUCCESS,
    WARNING,
    ERROR,
}

data class GameUiState(
    val roundNumber: Int,
    val seed: Long,
    val game: GameState,
    val arbiter: ArbiterSnapshot,
    val bannerTitle: String,
    val bannerSubtitle: String,
    val bannerTone: BannerTone,
)

/**
 * Coordinates touch input ([TouchRecognizer] -> [TouchCommandArbiter] -> [GameEngine])
 * and exposes immutable [GameUiState] for Compose.
 */
class GameController(
    private val clockNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    private val feedbackPlayer: FeedbackPlayer = NoOpFeedbackPlayer,
) {
    private val engine = GameEngine()
    private val touchRecognizer = TouchRecognizer()
    private val arbiter = TouchCommandArbiter()
    private val feedbackPlanner = FeedbackPlanner()

    private var roundNumber = 1
    private var currentSeed = 101L
    private var scannerYawRad = 0.0
    private var bannerTitle = "Ищите духов"
    private var bannerSubtitle = "Свайпайте по радару, чтобы навести луч (±15°)"
    private var bannerTone = BannerTone.NEUTRAL

    var uiState by mutableStateOf(startNewRoundInternal(resetRoundNumber = false))
        private set

    fun startNewRound() {
        roundNumber += 1
        currentSeed = roundNumber * 101L
        uiState = startNewRoundInternal(resetRoundNumber = false)
    }

    fun restartSameSeed() {
        uiState = startNewRoundInternal(resetRoundNumber = true)
    }

    fun tick() {
        val now = clockNanos()
        val gameResult = engine.update(now, scannerYawRad)
        val arbiterSnap = arbiter.advance(now)
        if (!gameResult.state.finished) {
            feedbackPlanner.poll(
                proximity = gameResult.state.beamProximity,
                attackActive = gameResult.state.pendingAttack != null,
                tNanos = now,
            )?.let(feedbackPlayer::play)
        }
        uiState = uiState.copy(
            game = gameResult.state,
            arbiter = arbiterSnap,
        )
    }

    fun onSwipeScan(deltaYawRad: Double) {
        val now = clockNanos()
        // Forward scan frame through TouchRecognizer so the input pipeline stays uniform.
        touchRecognizer.onFrame(TouchFrame(now, TouchAction.Scan(deltaYawRad)))
        scannerYawRad = GameEngine.normalizeAngleRad(scannerYawRad + deltaYawRad)
        val gameResult = engine.update(now, scannerYawRad)
        val arbiterSnap = arbiter.advance(now)
        if (!gameResult.state.finished) {
            feedbackPlanner.poll(
                proximity = gameResult.state.beamProximity,
                attackActive = gameResult.state.pendingAttack != null,
                tNanos = now,
            )?.let(feedbackPlayer::play)
        }
        uiState = uiState.copy(
            game = gameResult.state,
            arbiter = arbiterSnap,
        )
    }

    fun onGestureButton(gesture: Gesture) {
        val now = clockNanos()
        val events = touchRecognizer.onFrame(TouchFrame(now, TouchAction.Press(gesture)))
        arbiter.advance(now)
        for (event in events) {
            if (event is RecognizerEvent.Candidate) {
                feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.CANDIDATE))
            }
            val (_, action) = arbiter.onEvent(event)
            if (action != null) {
                executeArbiterAction(action)
            } else if (event is RecognizerEvent.Rejected) {
                feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.ERROR))
            }
        }
        val state = engine.currentState()
        uiState = GameUiState(
            roundNumber = roundNumber,
            seed = currentSeed,
            game = state,
            arbiter = arbiter.snapshot(now),
            bannerTitle = bannerTitle,
            bannerSubtitle = bannerSubtitle,
            bannerTone = bannerTone,
        )
    }

    private fun executeArbiterAction(action: ArbiterAction) {
        when (action) {
            is ArbiterAction.Apply -> {
                val res = engine.applyCommand(action.commandId, action.gesture, action.tNanos)
                when (val outcome = res.outcome) {
                    is CommandOutcome.Success -> {
                        feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.CONFIRMED))
                        bannerTitle = when (outcome.spiritType) {
                            SpiritType.WANDERING -> "Дух пойман"
                            SpiritType.EVIL -> "Злой дух изгнан"
                        }
                        bannerSubtitle = "Можно отменить в течение 2 с"
                        bannerTone = BannerTone.SUCCESS
                    }

                    is CommandOutcome.Error -> {
                        feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.ERROR))
                        bannerTitle = errorMessage(outcome.reason)
                        bannerSubtitle = "Можно отменить в течение 2 с"
                        bannerTone = BannerTone.ERROR
                    }

                    else -> Unit
                }
            }

            is ArbiterAction.Cancel -> {
                val res = engine.cancelCommand(action.commandId, action.tNanos)
                when (res.outcome) {
                    is CommandOutcome.Cancelled -> {
                        feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.CANCELLED))
                        bannerTitle = "Команда отменена"
                        bannerSubtitle = "Действие откачено без штрафа распознавания"
                        bannerTone = BannerTone.WARNING
                    }

                    else -> {
                        feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.ERROR))
                        bannerTitle = "Отмена недоступна"
                        bannerSubtitle = "Окно отмены истекло"
                        bannerTone = BannerTone.NEUTRAL
                    }
                }
            }
        }
    }

    private fun startNewRoundInternal(resetRoundNumber: Boolean): GameUiState {
        if (resetRoundNumber) {
            roundNumber = 1
            currentSeed = 101L
        }
        val now = clockNanos()
        touchRecognizer.reset()
        arbiter.reset()
        feedbackPlanner.reset()
        scannerYawRad = 0.0
        val started = engine.startRound(
            seed = currentSeed,
            startNanos = now,
            config = GameConfig(
                roundDurationNanos = 60_000_000_000L,
                experimentMode = true,
            ),
        )
        bannerTitle = "Раунд начался"
        bannerSubtitle = "Свайпайте по радару, чтобы найти духа в конусе ±15°"
        bannerTone = BannerTone.NEUTRAL
        return GameUiState(
            roundNumber = roundNumber,
            seed = currentSeed,
            game = started.state,
            arbiter = arbiter.snapshot(now),
            bannerTitle = bannerTitle,
            bannerSubtitle = bannerSubtitle,
            bannerTone = bannerTone,
        )
    }

    private fun errorMessage(reason: CommandErrorReason): String = when (reason) {
        CommandErrorReason.NO_SPIRIT_IN_CONE -> "Промах: в конусе нет духа"
        CommandErrorReason.WRONG_GESTURE -> "Неверная команда для этого духа"
        CommandErrorReason.WRONG_TRAP -> "Неверная ловушка"
        CommandErrorReason.NO_MANA -> "Недостаточно маны"
        CommandErrorReason.ROUND_FINISHED -> "Раунд уже завершён"
        CommandErrorReason.INVALID_COMMAND -> "Недопустимая команда"
    }
}
