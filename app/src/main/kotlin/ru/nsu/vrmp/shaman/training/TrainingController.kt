package ru.nsu.vrmp.shaman.training

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.nsu.vrmp.shaman.core.feedback.CommandFeedbackKind
import ru.nsu.vrmp.shaman.core.feedback.FeedbackPlanner
import ru.nsu.vrmp.shaman.core.input.InputFrame
import ru.nsu.vrmp.shaman.core.input.TouchAction
import ru.nsu.vrmp.shaman.core.input.TouchFrame
import ru.nsu.vrmp.shaman.core.recognition.Gesture
import ru.nsu.vrmp.shaman.core.recognition.GestureRecognizer
import ru.nsu.vrmp.shaman.core.recognition.TouchRecognizer
import ru.nsu.vrmp.shaman.core.training.GestureTrainer
import ru.nsu.vrmp.shaman.core.training.TrainingAttemptStatus
import ru.nsu.vrmp.shaman.core.training.TrainingState
import ru.nsu.vrmp.shaman.feedback.FeedbackPlayer
import ru.nsu.vrmp.shaman.feedback.NoOpFeedbackPlayer
import ru.nsu.vrmp.shaman.game.BannerTone

data class TrainingUiState(
    val training: TrainingState,
    val bannerTitle: String,
    val bannerSubtitle: String,
    val bannerTone: BannerTone,
)

/**
 * Coordinates [GestureTrainer] with any [GestureRecognizer] and [FeedbackPlayer].
 */
class TrainingController(
    recognizer: GestureRecognizer = TouchRecognizer(),
    private val clockNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    private val feedbackPlayer: FeedbackPlayer = NoOpFeedbackPlayer,
) {
    private val trainer = GestureTrainer(recognizer, targetReps = 3)

    var uiState by mutableStateOf(buildUiState(trainer.currentState()))
        private set

    fun onInputFrame(frame: InputFrame) {
        val update = trainer.onFrame(frame)
        emitFeedback(update.state)
        uiState = buildUiState(update.state)
    }

    fun onTouchPractice(gesture: Gesture) {
        onInputFrame(TouchFrame(clockNanos(), TouchAction.Press(gesture)))
    }

    fun restart() {
        trainer.reset()
        uiState = buildUiState(trainer.currentState())
    }

    private fun emitFeedback(state: TrainingState) {
        when (state.lastStatus) {
            TrainingAttemptStatus.CANDIDATE ->
                feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.CANDIDATE))

            TrainingAttemptStatus.SUCCESS,
            TrainingAttemptStatus.STEP_COMPLETED,
            TrainingAttemptStatus.ALL_COMPLETED ->
                feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.CONFIRMED))

            TrainingAttemptStatus.WRONG_GESTURE,
            TrainingAttemptStatus.REJECTED ->
                feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.ERROR))

            TrainingAttemptStatus.IDLE -> Unit
        }
    }

    private fun buildUiState(state: TrainingState): TrainingUiState {
        val remaining = state.currentStep?.let { state.targetReps - it.successCount } ?: 0
        val (title, subtitle, tone) = when (state.lastStatus) {
            TrainingAttemptStatus.IDLE -> Triple(
                "Готово к тренировке",
                "Выполните команду 3 раза подряд",
                BannerTone.NEUTRAL,
            )

            TrainingAttemptStatus.CANDIDATE -> Triple(
                "Движение начато",
                "Завершите жест до подтверждения",
                BannerTone.NEUTRAL,
            )

            TrainingAttemptStatus.SUCCESS -> Triple(
                "Жест распознан",
                "Осталось успешных повторов: $remaining",
                BannerTone.SUCCESS,
            )

            TrainingAttemptStatus.STEP_COMPLETED -> Triple(
                "Команда освоена",
                "Переходим к следующей команде",
                BannerTone.SUCCESS,
            )

            TrainingAttemptStatus.ALL_COMPLETED -> Triple(
                "Обучение завершено",
                "Все команды освоены · Всего попыток: ${state.totalAttempts}",
                BannerTone.SUCCESS,
            )

            TrainingAttemptStatus.WRONG_GESTURE -> Triple(
                "Выполнен другой жест",
                "Повторите текущую команду по подсказке",
                BannerTone.ERROR,
            )

            TrainingAttemptStatus.REJECTED -> Triple(
                "Жест не засчитан",
                state.lastRejectReason ?: "Попробуйте выполнить движение чётче",
                BannerTone.ERROR,
            )
        }
        return TrainingUiState(
            training = state,
            bannerTitle = title,
            bannerSubtitle = subtitle,
            bannerTone = tone,
        )
    }
}
