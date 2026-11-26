package ru.nsu.vrmp.shaman.core.training

import ru.nsu.vrmp.shaman.core.input.InputFrame
import ru.nsu.vrmp.shaman.core.recognition.Gesture
import ru.nsu.vrmp.shaman.core.recognition.GestureRecognizer
import ru.nsu.vrmp.shaman.core.recognition.RecognizerEvent

enum class TrainingAttemptStatus {
    IDLE,
    CANDIDATE,
    SUCCESS,
    WRONG_GESTURE,
    REJECTED,
    STEP_COMPLETED,
    ALL_COMPLETED,
}

data class TrainingStep(
    val gesture: Gesture,
    val successCount: Int = 0,
    val attemptCount: Int = 0,
    val completed: Boolean = false,
)

data class TrainingState(
    val steps: List<TrainingStep>,
    val currentIndex: Int,
    val targetReps: Int,
    val lastStatus: TrainingAttemptStatus,
    val lastRejectReason: String? = null,
) {
    val allCompleted: Boolean get() = steps.all { it.completed }
    val currentGesture: Gesture? get() = if (allCompleted) null else steps.getOrNull(currentIndex)?.gesture
    val currentStep: TrainingStep? get() = if (allCompleted) null else steps.getOrNull(currentIndex)
    val totalAttempts: Int get() = steps.sumOf { it.attemptCount }
}

data class TrainingUpdate(
    val state: TrainingState,
    val events: List<RecognizerEvent>,
)

/**
 * Interactive gesture training state machine: requires [targetReps] successful
 * executions per gesture while counting total attempts (learning-speed metric).
 */
class GestureTrainer(
    private val recognizer: GestureRecognizer,
    private val targetReps: Int = 3,
    private val gestures: List<Gesture> = Gesture.entries,
) {
    private var steps = gestures.map { TrainingStep(it) }.toMutableList()
    private var currentIndex = 0
    private var lastStatus = TrainingAttemptStatus.IDLE
    private var lastRejectReason: String? = null

    init {
        require(targetReps > 0) { "targetReps must be positive, got $targetReps" }
        require(gestures.isNotEmpty()) { "gestures must not be empty" }
        recognizer.reset()
    }

    fun reset() {
        steps = gestures.map { TrainingStep(it) }.toMutableList()
        currentIndex = 0
        lastStatus = TrainingAttemptStatus.IDLE
        lastRejectReason = null
        recognizer.reset()
    }

    fun onFrame(frame: InputFrame): TrainingUpdate {
        if (steps.all { it.completed }) {
            return TrainingUpdate(currentState(), emptyList())
        }

        val events = recognizer.onFrame(frame)
        for (event in events) {
            if (steps.all { it.completed }) break
            processEvent(event)
        }
        return TrainingUpdate(currentState(), events)
    }

    fun currentState(): TrainingState = TrainingState(
        steps = steps.toList(),
        currentIndex = currentIndex,
        targetReps = targetReps,
        lastStatus = lastStatus,
        lastRejectReason = lastRejectReason,
    )

    private fun processEvent(event: RecognizerEvent) {
        val step = steps[currentIndex]
        when (event) {
            is RecognizerEvent.Candidate -> {
                lastStatus = TrainingAttemptStatus.CANDIDATE
                lastRejectReason = null
            }

            is RecognizerEvent.Rejected -> {
                steps[currentIndex] = step.copy(attemptCount = step.attemptCount + 1)
                lastStatus = TrainingAttemptStatus.REJECTED
                lastRejectReason = event.reason
            }

            is RecognizerEvent.Detected -> {
                val updatedAttempts = step.attemptCount + 1
                if (event.gesture == step.gesture) {
                    val updatedSuccess = step.successCount + 1
                    val done = updatedSuccess >= targetReps
                    steps[currentIndex] = step.copy(
                        successCount = updatedSuccess,
                        attemptCount = updatedAttempts,
                        completed = done,
                    )
                    lastRejectReason = null
                    if (done) {
                        if (currentIndex < steps.lastIndex) {
                            currentIndex += 1
                            lastStatus = TrainingAttemptStatus.STEP_COMPLETED
                        } else {
                            lastStatus = TrainingAttemptStatus.ALL_COMPLETED
                        }
                        recognizer.reset()
                    } else {
                        lastStatus = TrainingAttemptStatus.SUCCESS
                    }
                } else {
                    steps[currentIndex] = step.copy(attemptCount = updatedAttempts)
                    lastStatus = TrainingAttemptStatus.WRONG_GESTURE
                    lastRejectReason = null
                }
            }
        }
    }
}
