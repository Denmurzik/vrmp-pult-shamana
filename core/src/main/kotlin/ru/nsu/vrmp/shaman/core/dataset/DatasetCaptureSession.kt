package ru.nsu.vrmp.shaman.core.dataset

import ru.nsu.vrmp.shaman.core.recognition.Gesture
import ru.nsu.vrmp.shaman.core.recording.LabelEntry
import ru.nsu.vrmp.shaman.core.recording.SessionMeta
import kotlin.math.ceil

enum class DatasetCaptureMode {
    GESTURE,
    NULL_STREAM,
}

enum class DatasetCapturePhase {
    IDLE,
    COUNTDOWN,
    RECORDING,
    REVIEW,
}

enum class GestureSpeed(val labelName: String, val displayNameRu: String) {
    SLOW("slow", "медленно"),
    NORMAL("normal", "обычно"),
    FAST("fast", "быстро"),
}

enum class NullActivity(val labelName: String, val displayNameRu: String) {
    WALK("walk", "Ходьба"),
    TABLE("table", "Взять со стола"),
    POCKET("pocket", "Карман"),
    TYPING("typing", "Печать"),
}

enum class DatasetCue {
    COUNTDOWN_TICK,
    RECORDING_STARTED,
    RECORDING_FINISHED,
}

data class DatasetCaptureConfig(
    val countdownDurationNanos: Long = DEFAULT_COUNTDOWN_NANOS,
    val gestureWindowNanos: Long = DEFAULT_GESTURE_WINDOW_NANOS,
    val nullStreamDurationNanos: Long = DEFAULT_NULL_STREAM_NANOS,
) {
    init {
        require(countdownDurationNanos >= 0L) { "countdownDurationNanos must be >= 0" }
        require(gestureWindowNanos > 0L) { "gestureWindowNanos must be > 0" }
        require(nullStreamDurationNanos > 0L) { "nullStreamDurationNanos must be > 0" }
    }

    companion object {
        const val DEFAULT_COUNTDOWN_NANOS: Long = 3_000_000_000L
        const val DEFAULT_GESTURE_WINDOW_NANOS: Long = 2_500_000_000L
        const val DEFAULT_NULL_STREAM_NANOS: Long = 120_000_000_000L
    }
}

data class DatasetCaptureState(
    val participantId: String,
    val mode: DatasetCaptureMode,
    val selectedGesture: Gesture,
    val selectedSpeed: GestureSpeed,
    val selectedNullActivity: NullActivity,
    val phase: DatasetCapturePhase,
    val promptText: String,
    val countdownSecondsRemaining: Int,
    val remainingNanos: Long,
    val progress: Float,
    val pendingLabel: LabelEntry?,
    val committedLabels: List<LabelEntry>,
    val countsByPromptKey: Map<String, Int>,
) {
    val committedCount: Int get() = committedLabels.size
}

data class DatasetStepResult(
    val state: DatasetCaptureState,
    val emittedLabel: LabelEntry? = null,
    val cues: List<DatasetCue> = emptyList(),
)

/**
 * Pure deterministic state machine for dataset collection mode:
 * - Guided/selectable gesture trials (3 gestures x 3 speeds, 3 s countdown, fixed 2.5 s window).
 * - Null-stream background activities (walk, table, pocket, typing; default 2 min).
 * - Confirm/repeat review step so bad takes are discarded before writing to `labels.csv`.
 *
 * Uses strictly monotonic [tNanos] timestamps — no wall-clock or Android dependencies.
 */
class DatasetCaptureSession(
    participantId: String = "P01",
    private val config: DatasetCaptureConfig = DatasetCaptureConfig(),
    private val onLabelCommitted: (LabelEntry) -> Unit = {},
) {
    private var participantIdValue: String = SessionMeta.validateParticipantId(participantId)
    private var mode: DatasetCaptureMode = DatasetCaptureMode.GESTURE
    private var selectedGesture: Gesture = Gesture.CATCH
    private var selectedSpeed: GestureSpeed = GestureSpeed.NORMAL
    private var selectedNullActivity: NullActivity = NullActivity.WALK

    private var phase: DatasetCapturePhase = DatasetCapturePhase.IDLE
    private var phaseStartNanos: Long = 0L
    private var lastTickNanos: Long = Long.MIN_VALUE
    private var lastCountdownSeconds: Int = 0
    private var remainingNanos: Long = config.gestureWindowNanos
    private var progress: Float = 0f
    private var pendingLabel: LabelEntry? = null
    private val committedLabels = mutableListOf<LabelEntry>()
    private val countsByPromptKey = linkedMapOf<String, Int>()

    fun setParticipantId(raw: String): DatasetCaptureState {
        participantIdValue = SessionMeta.validateParticipantId(raw)
        return currentState()
    }

    fun selectMode(newMode: DatasetCaptureMode): DatasetCaptureState {
        requireIdleOrReview()
        mode = newMode
        pendingLabel = null
        phase = DatasetCapturePhase.IDLE
        remainingNanos = currentWindowDurationNanos()
        progress = 0f
        return currentState()
    }

    fun selectGesture(gesture: Gesture): DatasetCaptureState {
        requireIdleOrReview()
        selectedGesture = gesture
        return currentState()
    }

    fun selectSpeed(speed: GestureSpeed): DatasetCaptureState {
        requireIdleOrReview()
        selectedSpeed = speed
        return currentState()
    }

    fun selectNullActivity(activity: NullActivity): DatasetCaptureState {
        requireIdleOrReview()
        selectedNullActivity = activity
        return currentState()
    }

    fun startTrial(tNanos: Long): DatasetStepResult {
        check(phase == DatasetCapturePhase.IDLE || phase == DatasetCapturePhase.REVIEW) {
            "Cannot start trial from phase $phase"
        }
        pendingLabel = null
        lastTickNanos = tNanos

        if (config.countdownDurationNanos == 0L) {
            phase = DatasetCapturePhase.RECORDING
            phaseStartNanos = tNanos
            remainingNanos = currentWindowDurationNanos()
            progress = 0f
            lastCountdownSeconds = 0
            return DatasetStepResult(
                state = currentState(),
                cues = listOf(DatasetCue.RECORDING_STARTED),
            )
        }

        phase = DatasetCapturePhase.COUNTDOWN
        phaseStartNanos = tNanos
        remainingNanos = config.countdownDurationNanos
        progress = 0f
        lastCountdownSeconds = nanosToCeilingSeconds(config.countdownDurationNanos)
        return DatasetStepResult(
            state = currentState(),
            cues = listOf(DatasetCue.COUNTDOWN_TICK),
        )
    }

    fun onTick(tNanos: Long): DatasetStepResult {
        if (phase != DatasetCapturePhase.COUNTDOWN && phase != DatasetCapturePhase.RECORDING) {
            return DatasetStepResult(currentState())
        }
        if (tNanos < lastTickNanos) {
            return DatasetStepResult(currentState())
        }
        lastTickNanos = tNanos

        return when (phase) {
            DatasetCapturePhase.COUNTDOWN -> handleCountdownTick(tNanos)
            DatasetCapturePhase.RECORDING -> handleRecordingTick(tNanos)
            else -> DatasetStepResult(currentState())
        }
    }

    fun stopEarly(tNanos: Long): DatasetStepResult {
        check(phase == DatasetCapturePhase.RECORDING) {
            "stopEarly is only valid during RECORDING (current=$phase)"
        }
        val effectiveEnd = maxOf(tNanos, phaseStartNanos)
        lastTickNanos = maxOf(lastTickNanos, effectiveEnd)
        return finishRecording(effectiveEnd)
    }

    fun confirmLastTrial(): DatasetStepResult {
        check(phase == DatasetCapturePhase.REVIEW) {
            "confirmLastTrial is only valid in REVIEW (current=$phase)"
        }
        val label = checkNotNull(pendingLabel) { "Missing pending label in REVIEW phase" }
        committedLabels += label
        val key = "${label.gesture}:${label.speed}"
        countsByPromptKey[key] = (countsByPromptKey[key] ?: 0) + 1
        pendingLabel = null
        phase = DatasetCapturePhase.IDLE
        progress = 0f
        onLabelCommitted(label)
        advanceGuidedPrompt()
        remainingNanos = currentWindowDurationNanos()
        return DatasetStepResult(
            state = currentState(),
            emittedLabel = label,
        )
    }

    fun repeatLastTrial(tNanos: Long): DatasetStepResult {
        check(phase == DatasetCapturePhase.REVIEW) {
            "repeatLastTrial is only valid in REVIEW (current=$phase)"
        }
        pendingLabel = null
        phase = DatasetCapturePhase.IDLE
        return startTrial(tNanos)
    }

    fun discardLastTrial(): DatasetStepResult {
        pendingLabel = null
        phase = DatasetCapturePhase.IDLE
        progress = 0f
        lastCountdownSeconds = 0
        remainingNanos = currentWindowDurationNanos()
        return DatasetStepResult(currentState())
    }

    fun cancel(): DatasetStepResult {
        pendingLabel = null
        phase = DatasetCapturePhase.IDLE
        progress = 0f
        lastCountdownSeconds = 0
        remainingNanos = currentWindowDurationNanos()
        return DatasetStepResult(currentState())
    }

    fun currentState(): DatasetCaptureState = DatasetCaptureState(
        participantId = participantIdValue,
        mode = mode,
        selectedGesture = selectedGesture,
        selectedSpeed = selectedSpeed,
        selectedNullActivity = selectedNullActivity,
        phase = phase,
        promptText = buildPromptText(),
        countdownSecondsRemaining = if (phase == DatasetCapturePhase.COUNTDOWN) lastCountdownSeconds else 0,
        remainingNanos = remainingNanos,
        progress = progress,
        pendingLabel = pendingLabel,
        committedLabels = committedLabels.toList(),
        countsByPromptKey = countsByPromptKey.toMap(),
    )

    private fun handleCountdownTick(tNanos: Long): DatasetStepResult {
        val elapsed = (tNanos - phaseStartNanos).coerceAtLeast(0L)
        if (elapsed >= config.countdownDurationNanos) {
            val recStart = phaseStartNanos + config.countdownDurationNanos
            phase = DatasetCapturePhase.RECORDING
            phaseStartNanos = recStart
            lastCountdownSeconds = 0
            val recElapsed = (tNanos - recStart).coerceAtLeast(0L)
            val window = currentWindowDurationNanos()
            if (recElapsed >= window) {
                val res = finishRecording(recStart + window)
                return res.copy(cues = listOf(DatasetCue.RECORDING_STARTED) + res.cues)
            }
            remainingNanos = (window - recElapsed).coerceAtLeast(0L)
            progress = (recElapsed.toDouble() / window.toDouble()).toFloat().coerceIn(0f, 1f)
            return DatasetStepResult(
                state = currentState(),
                cues = listOf(DatasetCue.RECORDING_STARTED),
            )
        }

        val rem = config.countdownDurationNanos - elapsed
        remainingNanos = rem
        progress = (elapsed.toDouble() / config.countdownDurationNanos.toDouble()).toFloat().coerceIn(0f, 1f)
        val sec = nanosToCeilingSeconds(rem)
        val cues = if (sec < lastCountdownSeconds) {
            lastCountdownSeconds = sec
            listOf(DatasetCue.COUNTDOWN_TICK)
        } else {
            emptyList()
        }
        return DatasetStepResult(state = currentState(), cues = cues)
    }

    private fun handleRecordingTick(tNanos: Long): DatasetStepResult {
        val window = currentWindowDurationNanos()
        val elapsed = (tNanos - phaseStartNanos).coerceAtLeast(0L)
        if (elapsed >= window) {
            return finishRecording(phaseStartNanos + window)
        }
        remainingNanos = (window - elapsed).coerceAtLeast(0L)
        progress = (elapsed.toDouble() / window.toDouble()).toFloat().coerceIn(0f, 1f)
        return DatasetStepResult(state = currentState())
    }

    private fun finishRecording(tEndNanos: Long): DatasetStepResult {
        val (gestureLabel, speedLabel) = when (mode) {
            DatasetCaptureMode.GESTURE -> gestureLabelName(selectedGesture) to selectedSpeed.labelName
            DatasetCaptureMode.NULL_STREAM -> NULL_GESTURE_LABEL to selectedNullActivity.labelName
        }
        pendingLabel = LabelEntry(
            tStartNanos = phaseStartNanos,
            tEndNanos = tEndNanos,
            gesture = gestureLabel,
            speed = speedLabel,
        )
        phase = DatasetCapturePhase.REVIEW
        remainingNanos = 0L
        progress = 1f
        return DatasetStepResult(
            state = currentState(),
            cues = listOf(DatasetCue.RECORDING_FINISHED),
        )
    }

    private fun advanceGuidedPrompt() {
        when (mode) {
            DatasetCaptureMode.GESTURE -> {
                val speeds = GestureSpeed.entries
                val gestures = Gesture.entries
                val nextSpeedIdx = selectedSpeed.ordinal + 1
                if (nextSpeedIdx < speeds.size) {
                    selectedSpeed = speeds[nextSpeedIdx]
                } else {
                    selectedSpeed = speeds[0]
                    selectedGesture = gestures[(selectedGesture.ordinal + 1) % gestures.size]
                }
            }

            DatasetCaptureMode.NULL_STREAM -> {
                val activities = NullActivity.entries
                selectedNullActivity = activities[(selectedNullActivity.ordinal + 1) % activities.size]
            }
        }
    }

    private fun currentWindowDurationNanos(): Long = when (mode) {
        DatasetCaptureMode.GESTURE -> config.gestureWindowNanos
        DatasetCaptureMode.NULL_STREAM -> config.nullStreamDurationNanos
    }

    private fun buildPromptText(): String = when (mode) {
        DatasetCaptureMode.GESTURE -> "${gestureDisplayNameRu(selectedGesture)} · ${selectedSpeed.displayNameRu}"
        DatasetCaptureMode.NULL_STREAM -> "Нулевой поток · ${selectedNullActivity.displayNameRu}"
    }

    private fun requireIdleOrReview() {
        check(phase == DatasetCapturePhase.IDLE || phase == DatasetCapturePhase.REVIEW) {
            "Cannot change selection during active capture ($phase)"
        }
    }

    private fun nanosToCeilingSeconds(nanos: Long): Int =
        ceil(nanos.toDouble() / 1_000_000_000.0).toInt().coerceAtLeast(1)

    companion object {
        const val NULL_GESTURE_LABEL: String = "null"

        fun isValidParticipantId(raw: String): Boolean =
            runCatching { SessionMeta.validateParticipantId(raw) }.isSuccess

        fun gestureLabelName(gesture: Gesture): String = when (gesture) {
            Gesture.CATCH -> "catch"
            Gesture.BANISH -> "banish"
            Gesture.CANCEL -> "cancel"
        }

        fun gestureDisplayNameRu(gesture: Gesture): String = when (gesture) {
            Gesture.CATCH -> "Рывок вверх"
            Gesture.BANISH -> "Круг"
            Gesture.CANCEL -> "Встряхивание"
        }
    }
}
