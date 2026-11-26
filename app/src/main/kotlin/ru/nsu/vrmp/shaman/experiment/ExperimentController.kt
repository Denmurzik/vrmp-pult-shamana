package ru.nsu.vrmp.shaman.experiment

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.nsu.vrmp.shaman.core.experiment.BlockResult
import ru.nsu.vrmp.shaman.core.experiment.ExperimentBlock
import ru.nsu.vrmp.shaman.core.experiment.ExperimentLogEntry
import ru.nsu.vrmp.shaman.core.experiment.ExperimentLogSink
import ru.nsu.vrmp.shaman.core.experiment.ExperimentPlan
import ru.nsu.vrmp.shaman.core.experiment.ExperimentProtocol
import ru.nsu.vrmp.shaman.core.experiment.ExperimentSession
import ru.nsu.vrmp.shaman.core.experiment.InputCondition
import ru.nsu.vrmp.shaman.core.feedback.CommandFeedbackKind
import ru.nsu.vrmp.shaman.core.feedback.FeedbackPlanner
import ru.nsu.vrmp.shaman.core.game.CommandErrorReason
import ru.nsu.vrmp.shaman.core.game.CommandOutcome
import ru.nsu.vrmp.shaman.core.game.GameEngine
import ru.nsu.vrmp.shaman.core.game.SpiritType
import ru.nsu.vrmp.shaman.core.input.TouchAction
import ru.nsu.vrmp.shaman.core.input.TouchFrame
import ru.nsu.vrmp.shaman.core.recognition.Gesture
import ru.nsu.vrmp.shaman.core.recognition.TouchRecognizer
import ru.nsu.vrmp.shaman.feedback.FeedbackPlayer
import ru.nsu.vrmp.shaman.feedback.NoOpFeedbackPlayer
import ru.nsu.vrmp.shaman.game.ArbiterAction
import ru.nsu.vrmp.shaman.game.BannerTone
import ru.nsu.vrmp.shaman.game.GameUiState
import ru.nsu.vrmp.shaman.game.TouchCommandArbiter

enum class ExperimentStage {
    SETUP,
    RUNNING_BLOCK,
    RESULTS,
}

data class ExperimentUiState(
    val participantInput: String = "P01",
    val inputError: String? = null,
    val stage: ExperimentStage = ExperimentStage.SETUP,
    val plan: ExperimentPlan = ExperimentProtocol.planForParticipant("P01"),
    val currentBlock: ExperimentBlock? = null,
    val results: List<BlockResult> = emptyList(),
    val loggedEventCount: Int = 0,
    val activeGameUi: GameUiState? = null,
)

/**
 * Coordinates experimental sessions: participant ID -> Latin square condition & seed ->
 * `GameEngine(experimentMode = true)` -> structured log sink (ready for SessionRecorder #8).
 */
class ExperimentController(
    private val clockNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    private val feedbackPlayer: FeedbackPlayer = NoOpFeedbackPlayer,
    private val externalLogSink: ExperimentLogSink = ExperimentLogSink {},
) {
    private val engine = GameEngine()
    private val touchRecognizer = TouchRecognizer()
    private val arbiter = TouchCommandArbiter()
    private val feedbackPlanner = FeedbackPlanner()

    private val recordedLog = mutableListOf<ExperimentLogEntry>()
    private val combinedSink = ExperimentLogSink { entry ->
        recordedLog += entry
        externalLogSink.record(entry)
    }

    private var session = ExperimentSession("P01", combinedSink)
    private var scannerYawRad = 0.0
    private var bannerTitle = "Блок эксперимента"
    private var bannerSubtitle = "Доп. события отключены · полный лог"
    private var bannerTone = BannerTone.NEUTRAL

    var uiState by mutableStateOf(
        ExperimentUiState(
            participantInput = "P01",
            plan = session.plan,
            currentBlock = session.currentBlock(),
        ),
    )
        private set

    fun onParticipantIdChanged(raw: String) {
        val cleaned = raw.uppercase().take(4)
        val parsed = runCatching { ExperimentProtocol.planForParticipant(cleaned) }
        if (parsed.isSuccess) {
            val plan = parsed.getOrThrow()
            recordedLog.clear()
            session = ExperimentSession(plan.participantId, combinedSink)
            uiState = uiState.copy(
                participantInput = cleaned,
                inputError = null,
                plan = plan,
                currentBlock = session.currentBlock(),
            )
        } else {
            uiState = uiState.copy(
                participantInput = cleaned,
                inputError = "Формат кода: P01–P99",
            )
        }
    }

    fun startCurrentBlock() {
        if (uiState.inputError != null || session.isFinished) return
        val block = session.currentBlock() ?: return
        val now = clockNanos()
        val config = session.startCurrentBlock(tNanos = now, roundDurationNanos = 45_000_000_000L)

        touchRecognizer.reset()
        arbiter.reset()
        feedbackPlanner.reset()
        scannerYawRad = 0.0
        val started = engine.startRound(seed = block.seed, startNanos = now, config = config)
        session.recordGameEvents(started.events)

        bannerTitle = "Условие: ${conditionLabel(block.condition)}"
        bannerSubtitle = "Сид ${block.seed} · доп. события отключены"
        bannerTone = BannerTone.NEUTRAL

        uiState = uiState.copy(
            stage = ExperimentStage.RUNNING_BLOCK,
            currentBlock = block,
            loggedEventCount = recordedLog.size,
            activeGameUi = GameUiState(
                roundNumber = block.position,
                seed = block.seed,
                game = started.state,
                arbiter = arbiter.snapshot(now),
                bannerTitle = bannerTitle,
                bannerSubtitle = bannerSubtitle,
                bannerTone = bannerTone,
            ),
        )
    }

    fun tickBlock() {
        val active = uiState.activeGameUi ?: return
        val now = clockNanos()
        val res = engine.update(now, scannerYawRad)
        session.recordGameEvents(res.events)
        val snap = arbiter.advance(now)
        if (!res.state.finished) {
            feedbackPlanner.poll(res.state.beamProximity, attackActive = false, tNanos = now)?.let(feedbackPlayer::play)
        }
        uiState = uiState.copy(
            loggedEventCount = recordedLog.size,
            activeGameUi = active.copy(game = res.state, arbiter = snap),
        )
    }

    fun onSwipeScan(deltaYawRad: Double) {
        val active = uiState.activeGameUi ?: return
        val now = clockNanos()
        touchRecognizer.onFrame(TouchFrame(now, TouchAction.Scan(deltaYawRad)))
        scannerYawRad = GameEngine.normalizeAngleRad(scannerYawRad + deltaYawRad)
        val res = engine.update(now, scannerYawRad)
        session.recordGameEvents(res.events)
        val snap = arbiter.advance(now)
        uiState = uiState.copy(
            loggedEventCount = recordedLog.size,
            activeGameUi = active.copy(game = res.state, arbiter = snap),
        )
    }

    fun onGestureButton(gesture: Gesture) {
        val active = uiState.activeGameUi ?: return
        val now = clockNanos()
        val events = touchRecognizer.onFrame(TouchFrame(now, TouchAction.Press(gesture)))
        arbiter.advance(now)
        for (event in events) {
            val (_, action) = arbiter.onEvent(event)
            if (action != null) {
                executeAction(action)
            }
        }
        uiState = uiState.copy(
            loggedEventCount = recordedLog.size,
            activeGameUi = active.copy(
                game = engine.currentState(),
                arbiter = arbiter.snapshot(now),
                bannerTitle = bannerTitle,
                bannerSubtitle = bannerSubtitle,
                bannerTone = bannerTone,
            ),
        )
    }

    fun finishCurrentBlock() {
        val state = engine.currentState()
        val now = clockNanos()
        session.completeCurrentBlock(
            score = state.score,
            errors = state.errors,
            misses = state.misses,
            cancelled = state.cancelledCount,
            tNanos = now,
        )
        uiState = uiState.copy(
            stage = if (session.isFinished) ExperimentStage.RESULTS else ExperimentStage.SETUP,
            currentBlock = session.currentBlock(),
            results = session.results.toList(),
            loggedEventCount = recordedLog.size,
            activeGameUi = null,
        )
    }

    fun resetSession() {
        val id = uiState.plan.participantId
        recordedLog.clear()
        session = ExperimentSession(id, combinedSink)
        uiState = ExperimentUiState(
            participantInput = id,
            plan = session.plan,
            currentBlock = session.currentBlock(),
        )
    }

    private fun executeAction(action: ArbiterAction) {
        when (action) {
            is ArbiterAction.Apply -> {
                val res = engine.applyCommand(action.commandId, action.gesture, action.tNanos)
                session.recordGameEvents(res.events)
                when (val outcome = res.outcome) {
                    is CommandOutcome.Success -> {
                        feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.CONFIRMED))
                        bannerTitle = if (outcome.spiritType == SpiritType.WANDERING) "Дух пойман" else "Злой дух изгнан"
                        bannerSubtitle = "Записано в лог эксперимента"
                        bannerTone = BannerTone.SUCCESS
                    }

                    is CommandOutcome.Error -> {
                        feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.ERROR))
                        bannerTitle = when (outcome.reason) {
                            CommandErrorReason.NO_SPIRIT_IN_CONE -> "Промах: вне конуса"
                            CommandErrorReason.WRONG_GESTURE -> "Неверная команда"
                            else -> "Ошибка команды"
                        }
                        bannerSubtitle = "Можно отменить в течение 2 с"
                        bannerTone = BannerTone.ERROR
                    }

                    else -> Unit
                }
            }

            is ArbiterAction.Cancel -> {
                val res = engine.cancelCommand(action.commandId, action.tNanos)
                session.recordGameEvents(res.events)
                if (res.outcome is CommandOutcome.Cancelled) {
                    feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.CANCELLED))
                    bannerTitle = "Команда отменена"
                    bannerSubtitle = "Откат записан в лог"
                    bannerTone = BannerTone.WARNING
                }
            }
        }
    }

    companion object {
        fun conditionLabel(condition: InputCondition): String = when (condition) {
            InputCondition.TOUCH -> "Тач"
            InputCondition.THRESHOLD -> "Пороги"
            InputCondition.DTW -> "DTW"
            InputCondition.CAMERA -> "Камера"
        }
    }
}
