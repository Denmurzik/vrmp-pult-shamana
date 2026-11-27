package ru.nsu.vrmp.shaman.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.nsu.vrmp.shaman.core.input.HandFrame
import ru.nsu.vrmp.shaman.core.math.Vec3

class CameraFramePipelineTest {
    @Test
    fun `only the matching model result emits the original capture time and landmarks`() {
        val pipeline = CameraFramePipeline(realtime = true)
        val capture = pipeline.offer(12_345_678L, 12_345_678L)!!
        assertEquals(12L, capture.taskMillis)
        assertNull(pipeline.offer(14_000_000L, 14_000_000L))
        val points = List(21) { Vec3(it / 21.0, 0.5, -0.01) }
        assertNull(pipeline.result(13L, points))
        assertEquals(HandFrame(12_345_678L, true, points), pipeline.result(12L, points)?.frame)
        assertNull(pipeline.result(12L, points))
    }

    @Test
    fun `a dropped model result expires and a late callback cannot complete the next frame`() {
        val pipeline = CameraFramePipeline(realtime = true)
        val first = requireNotNull(pipeline.offer(100_000_000L, 110_000_000L))

        assertNull(pipeline.offer(1_099_000_000L, 1_109_000_000L))
        val next = requireNotNull(pipeline.offer(1_100_000_000L, 1_110_000_000L))
        assertNull(pipeline.result(first.taskMillis, emptyList()))
        assertEquals(HandFrame.absent(1_100_000_000L), pipeline.result(next.taskMillis, emptyList())?.frame)
    }

    @Test
    fun `missing hands still emit frames and count toward processed FPS`() {
        val pipeline = CameraFramePipeline(realtime = true)
        val first = pipeline.result(pipeline.offer(100_000_000L, 100_000_000L)!!.taskMillis, emptyList())!!
        val second = pipeline.result(pipeline.offer(150_000_000L, 150_000_000L)!!.taskMillis, List(20) { Vec3.ZERO })!!
        assertEquals(HandFrame.absent(100_000_000L), first.frame)
        assertEquals(HandFrame.absent(150_000_000L), second.frame)
        assertTrue(first.fps == null)
        assertEquals(20.0, second.fps)
    }
}
