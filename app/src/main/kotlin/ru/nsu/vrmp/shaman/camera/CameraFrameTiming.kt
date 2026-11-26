package ru.nsu.vrmp.shaman.camera

internal data class CameraFrameTime(val frameNanos: Long, val taskMillis: Long, val exact: Boolean)

internal class CameraFrameTiming(private val realtime: Boolean) {
    private var clockOffset: Long? = null
    private var lastTaskMillis = -1L

    fun capture(sensorNanos: Long, arrivalNanos: Long): CameraFrameTime {
        val offset = if (realtime) 0L else clockOffset ?: (arrivalNanos - sensorNanos).also { clockOffset = it }
        val captureNanos = sensorNanos + offset
        // MediaPipe requires strictly increasing milliseconds, while HandFrame keeps nanoseconds.
        lastTaskMillis = maxOf(lastTaskMillis + 1, captureNanos / 1_000_000)
        return CameraFrameTime(captureNanos, lastTaskMillis, exact = realtime)
    }
}
