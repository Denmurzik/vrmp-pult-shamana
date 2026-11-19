package ru.nsu.vrmp.shaman.core.input

import ru.nsu.vrmp.shaman.core.math.Quat
import ru.nsu.vrmp.shaman.core.math.Vec3
import ru.nsu.vrmp.shaman.core.recognition.Gesture

/**
 * One sample of user input. All frames share a single monotonic clock:
 * Android `elapsedRealtimeNanos` (the clock of `SensorEvent.timestamp`).
 * Recognizers must use only [tNanos] for timing — never wall-clock time.
 */
sealed interface InputFrame {
    val tNanos: Long
}

/** Motion sensors sample in device coordinates. */
data class ImuFrame(
    override val tNanos: Long,
    /** Linear acceleration without gravity, m/s² (`TYPE_LINEAR_ACCELERATION`). */
    val linAcc: Vec3,
    /** Angular velocity, rad/s (`TYPE_GYROSCOPE`). */
    val gyro: Vec3,
    /** Device → world orientation (`TYPE_GAME_ROTATION_VECTOR`). */
    val rotation: Quat,
) : InputFrame

/** One camera frame processed by MediaPipe Hand Landmarker. */
data class HandFrame(
    override val tNanos: Long,
    val present: Boolean,
    /** 21 MediaPipe hand landmarks (normalized image x, y; relative depth z), empty if no hand. */
    val landmarks: List<Vec3>,
) : InputFrame {
    init {
        require(if (present) landmarks.size == LANDMARK_COUNT else landmarks.isEmpty()) {
            "present=$present requires ${if (present) LANDMARK_COUNT else 0} landmarks, got ${landmarks.size}"
        }
    }

    companion object {
        const val LANDMARK_COUNT = 21

        fun absent(tNanos: Long) = HandFrame(tNanos, present = false, landmarks = emptyList())
    }
}

/** Touch input of the baseline (conventional) control scheme. */
data class TouchFrame(
    override val tNanos: Long,
    val action: TouchAction,
) : InputFrame

sealed interface TouchAction {
    /** A command button was pressed. */
    data class Press(val gesture: Gesture) : TouchAction

    /** Horizontal swipe turning the scanner by [deltaYawRad] (positive = clockwise). */
    data class Scan(val deltaYawRad: Double) : TouchAction
}
