package ru.nsu.vrmp.shaman.camera

import ru.nsu.vrmp.shaman.core.input.HandFrame
import ru.nsu.vrmp.shaman.core.math.Vec3

internal data class CameraResult(val frame: HandFrame, val fps: Double?, val exact: Boolean)

internal class CameraFramePipeline(realtime: Boolean) {
    private val timing = CameraFrameTiming(realtime)
    private val samples = ArrayDeque<Long>()
    private var pending: CameraFrameTime? = null
    private var submittedAt = 0L

    fun offer(sensorNanos: Long, arrivalNanos: Long): CameraFrameTime? {
        if (pending != null && arrivalNanos - submittedAt < RESULT_TIMEOUT_NS) return null
        submittedAt = arrivalNanos
        return timing.capture(sensorNanos, arrivalNanos).also { pending = it }
    }

    fun cancelPending() {
        pending = null
    }

    fun result(taskMillis: Long, points: List<Vec3>): CameraResult? {
        val capture = pending ?: return null
        if (capture.taskMillis != taskMillis) return null
        cancelPending()
        val frame = if (points.size == HandFrame.LANDMARK_COUNT) {
            HandFrame(capture.frameNanos, true, points)
        } else {
            HandFrame.absent(capture.frameNanos)
        }
        samples.addLast(capture.frameNanos)
        while (capture.frameNanos - samples.first() > 1_000_000_000L) samples.removeFirst()
        val span = capture.frameNanos - samples.first()
        val fps = if (span > 0) (samples.size - 1) * 1e9 / span else null
        return CameraResult(frame, fps, capture.exact)
    }

    private companion object {
        const val RESULT_TIMEOUT_NS = 1_000_000_000L
    }
}
