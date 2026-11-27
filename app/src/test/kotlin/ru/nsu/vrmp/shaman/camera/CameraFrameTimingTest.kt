package ru.nsu.vrmp.shaman.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CameraFrameTimingTest {
    @Test
    fun `realtime camera frames retain nanoseconds even in the same millisecond`() {
        val clock = CameraFrameTiming(realtime = true)
        val a = clock.capture(1_489_000, 9_000_000)
        val b = clock.capture(1_749_000, 10_000_000)
        assertEquals(1_489_000L, a.frameNanos)
        assertEquals(1_749_000L, b.frameNanos)
        assertEquals(1L, a.taskMillis)
        assertEquals(2L, b.taskMillis)
        assertTrue(a.exact)
    }

    @Test
    fun `unknown camera clock keeps relative capture times and flags the estimate`() {
        val clock = CameraFrameTiming(realtime = false)
        val a = clock.capture(200_000_000, 20_030_000_000)
        val b = clock.capture(240_000_000, 20_100_000_000)
        assertEquals(20_030_000_000L, a.frameNanos)
        assertEquals(20_070_000_000L, b.frameNanos)
        assertFalse(a.exact)
    }
}
