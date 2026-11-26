package ru.nsu.vrmp.shaman.core.recognition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.nsu.vrmp.shaman.core.input.HandFrame
import ru.nsu.vrmp.shaman.core.input.ImuFrame
import ru.nsu.vrmp.shaman.core.input.InputFrame
import ru.nsu.vrmp.shaman.core.input.TouchAction
import ru.nsu.vrmp.shaman.core.input.TouchFrame
import ru.nsu.vrmp.shaman.core.math.Quat
import ru.nsu.vrmp.shaman.core.math.Vec3
import kotlin.math.PI
import kotlin.math.sin

class ThresholdRecognizerTest {

    private companion object {
        const val MS = 1_000_000L
        const val STEP_MS = 20L // 50 Hz
    }

    private fun imu(
        tMs: Long,
        ax: Double = 0.0,
        ay: Double = 0.0,
        az: Double = 0.0,
        gx: Double = 0.0,
        gy: Double = 0.0,
        gz: Double = 0.0,
    ): ImuFrame = ImuFrame(
        tNanos = tMs * MS,
        linAcc = Vec3(ax, ay, az),
        gyro = Vec3(gx, gy, gz),
        rotation = Quat.IDENTITY,
    )

    private fun feedAll(recognizer: GestureRecognizer, frames: List<InputFrame>): List<RecognizerEvent> =
        frames.flatMap { recognizer.onFrame(it) }

    @Test
    fun `id is threshold and zero-arg constructor works`() {
        val recognizer = ThresholdRecognizer()
        assertEquals("threshold", recognizer.id)
    }

    @Test
    fun `invalid config is rejected`() {
        assertThrows<IllegalArgumentException> { ThresholdRecognizer.Config(emaAlpha = 0.0) }
        assertThrows<IllegalArgumentException> { ThresholdRecognizer.Config(emaAlpha = 1.5) }
        assertThrows<IllegalArgumentException> {
            ThresholdRecognizer.Config(catchStartThreshold = 5.0, catchEndThreshold = 5.0)
        }
        assertThrows<IllegalArgumentException> {
            ThresholdRecognizer.Config(banishMinDurationMs = 500L, banishMaxDurationMs = 400L)
        }
    }

    @Test
    fun `non-imu frames are ignored`() {
        val recognizer = ThresholdRecognizer()
        val frames = listOf(
            TouchFrame(10 * MS, TouchAction.Press(Gesture.CATCH)),
            TouchFrame(20 * MS, TouchAction.Scan(0.5)),
            HandFrame.absent(30 * MS),
        )
        assertTrue(feedAll(recognizer, frames).isEmpty())
    }

    @Test
    fun `non-finite values are safely ignored without corrupting state`() {
        val recognizer = ThresholdRecognizer()
        val badFrames = listOf(
            imu(0L, ay = Double.NaN),
            imu(20L, ay = Double.POSITIVE_INFINITY),
            imu(40L, gz = Double.NEGATIVE_INFINITY),
        )
        assertTrue(feedAll(recognizer, badFrames).isEmpty())

        // After bad frames, a valid CATCH gesture must still be detected cleanly.
        val validFrames = buildCatchFrames(startMs = 100L, peakAy = 11.0, durationMs = 160L)
        val events = feedAll(recognizer, validFrames)
        assertEquals(1, events.filterIsInstance<RecognizerEvent.Detected>().count { it.gesture == Gesture.CATCH })
    }

    @Test
    fun `catch upward flick emits candidate and detected with valid timestamps and confidence`() {
        val recognizer = ThresholdRecognizer()
        val frames = buildCatchFrames(startMs = 100L, peakAy = 12.0, durationMs = 180L)
        val events = feedAll(recognizer, frames)

        val candidates = events.filterIsInstance<RecognizerEvent.Candidate>()
        val detected = events.filterIsInstance<RecognizerEvent.Detected>()

        assertEquals(1, candidates.size, "Expected exactly one Candidate, got $events")
        assertEquals(Gesture.CATCH, candidates.single().gesture)
        assertEquals(1, detected.size, "Expected exactly one Detected, got $events")

        val d = detected.single()
        assertEquals(Gesture.CATCH, d.gesture)
        assertTrue(d.confidence in 0.5..1.0, "Unexpected confidence ${d.confidence}")
        assertTrue(d.tStart <= d.tEnd && d.tEnd <= d.tEmit)
        assertTrue(candidates.single().tNanos == d.tStart)
    }

    @Test
    fun `catch impulse that is too short is rejected`() {
        val recognizer = ThresholdRecognizer(ThresholdRecognizer.Config(emaAlpha = 1.0))
        val frames = listOf(
            imu(0L, ay = 0.0),
            imu(20L, ay = 10.0),
            imu(40L, ay = 10.0),
            imu(50L, ay = 0.0), // duration = 30 ms < 60 ms
            imu(80L, ay = 0.0),
        )
        val events = feedAll(recognizer, frames)

        assertTrue(events.filterIsInstance<RecognizerEvent.Detected>().isEmpty(), "Expected no Detected, got $events")
        assertTrue(events.any { it is RecognizerEvent.Candidate && it.gesture == Gesture.CATCH })
        assertTrue(events.any { it is RecognizerEvent.Rejected })
    }

    @Test
    fun `catch sustained acceleration exceeding max duration is rejected without double trigger`() {
        val recognizer = ThresholdRecognizer(ThresholdRecognizer.Config(emaAlpha = 1.0))
        val frames = buildList {
            add(imu(0L, ay = 0.0))
            // Sustained 9 m/s² for 700 ms (> 500 ms max duration)
            for (t in 20L..720L step STEP_MS) {
                add(imu(t, ay = 9.0))
            }
            for (t in 740L..820L step STEP_MS) {
                add(imu(t, ay = 0.0))
            }
        }
        val events = feedAll(recognizer, frames)

        assertTrue(events.filterIsInstance<RecognizerEvent.Detected>().isEmpty(), "Expected no Detected, got $events")
        assertEquals(1, events.filterIsInstance<RecognizerEvent.Candidate>().size, "Must not re-trigger while held")
        assertEquals(1, events.filterIsInstance<RecognizerEvent.Rejected>().size)
    }

    @Test
    fun `banish circle CCW and CW of at least 300 degrees within 1_5s are detected`() {
        for (sign in listOf(1.0, -1.0)) {
            val recognizer = ThresholdRecognizer()
            val frames = buildCircleFrames(startMs = 0L, rateRadPerSec = sign * 6.0, durationMs = 1100L)
            val events = feedAll(recognizer, frames)

            val candidates = events.filterIsInstance<RecognizerEvent.Candidate>().filter { it.gesture == Gesture.BANISH }
            val detected = events.filterIsInstance<RecognizerEvent.Detected>().filter { it.gesture == Gesture.BANISH }

            assertEquals(1, candidates.size, "sign=$sign expected 1 Candidate, got $events")
            assertEquals(1, detected.size, "sign=$sign expected 1 Detected, got $events")
            val d = detected.single()
            assertTrue(d.confidence in 0.5..1.0)
            assertTrue(d.tEnd - d.tStart <= 1500L * MS)
        }
    }

    @Test
    fun `banish circle under 300 degrees is rejected when rotation stops`() {
        val recognizer = ThresholdRecognizer()
        // 3.5 rad/s for 1.0 s ≈ 3.5 rad (~200 deg < 300 deg), then stop for 500 ms
        val frames = buildCircleFrames(startMs = 0L, rateRadPerSec = 3.5, durationMs = 1000L, tailMs = 500L)
        val events = feedAll(recognizer, frames)

        assertTrue(events.filterIsInstance<RecognizerEvent.Detected>().isEmpty(), "Expected no Detected, got $events")
        assertTrue(events.any { it is RecognizerEvent.Candidate && it.gesture == Gesture.BANISH })
        assertTrue(events.any { it is RecognizerEvent.Rejected })
    }

    @Test
    fun `banish circle taking longer than 1_5s is rejected`() {
        val recognizer = ThresholdRecognizer()
        // 2.5 rad/s needs ~2.1 s to reach 300 deg (> 1.5 s window)
        val frames = buildCircleFrames(startMs = 0L, rateRadPerSec = 2.5, durationMs = 2400L, tailMs = 300L)
        val events = feedAll(recognizer, frames)

        assertTrue(events.filterIsInstance<RecognizerEvent.Detected>().isEmpty(), "Expected no Detected, got $events")
        assertTrue(events.any { it is RecognizerEvent.Rejected })
    }

    @Test
    fun `cancel shake with at least 3 sign flips within 600ms is detected once`() {
        val recognizer = ThresholdRecognizer()
        // 3.5 Hz sine wave along X with amplitude 12 m/s² for 560 ms -> 4 alternating half-cycles (3 sign changes)
        val frames = buildShakeFrames(startMs = 0L, amplitude = 12.0, freqHz = 3.5, durationMs = 560L, tailMs = 400L)
        val events = feedAll(recognizer, frames)

        val candidates = events.filterIsInstance<RecognizerEvent.Candidate>().filter { it.gesture == Gesture.CANCEL }
        val detected = events.filterIsInstance<RecognizerEvent.Detected>().filter { it.gesture == Gesture.CANCEL }

        assertEquals(1, candidates.size, "Expected 1 CANCEL Candidate, got $events")
        assertEquals(1, detected.size, "Expected 1 CANCEL Detected, got $events")
        val d = detected.single()
        assertTrue(d.confidence in 0.5..1.0)
        assertTrue(d.tEnd - d.tStart <= 600L * MS)
    }

    @Test
    fun `cancel shake with fewer than 3 sign flips is not detected`() {
        val recognizer = ThresholdRecognizer()
        // Only 2 peaks (+ then -), i.e., 1 sign flip
        val frames = buildShakeFrames(startMs = 0L, amplitude = 12.0, freqHz = 3.5, durationMs = 260L, tailMs = 700L)
        val events = feedAll(recognizer, frames)

        assertTrue(events.filterIsInstance<RecognizerEvent.Detected>().isEmpty(), "Expected no Detected, got $events")
    }

    @Test
    fun `cancel shake spread over more than 600ms is not detected`() {
        val recognizer = ThresholdRecognizer(ThresholdRecognizer.Config(emaAlpha = 1.0))
        // 4 alternating peaks spaced 250 ms apart -> total span 750 ms > 600 ms
        val frames = buildList {
            add(imu(0L, ax = 0.0))
            add(imu(50L, ax = 10.0))
            add(imu(100L, ax = 0.0))
            add(imu(300L, ax = -10.0))
            add(imu(350L, ax = 0.0))
            add(imu(550L, ax = 10.0))
            add(imu(600L, ax = 0.0))
            add(imu(800L, ax = -10.0))
            add(imu(850L, ax = 0.0))
            add(imu(1500L, ax = 0.0))
        }
        val events = feedAll(recognizer, frames)

        assertTrue(events.filterIsInstance<RecognizerEvent.Detected>().isEmpty(), "Expected no Detected, got $events")
    }

    @Test
    fun `null stream of gentle walking and tilting produces zero detections`() {
        val recognizer = ThresholdRecognizer()
        // 10 seconds of gentle walking (~2.5 m/s² oscillation) and slow turning (~0.6 rad/s)
        val frames = (0L..10_000L step STEP_MS).map { t ->
            val sec = t / 1000.0
            imu(
                tMs = t,
                ax = 2.0 * sin(2.0 * PI * 1.8 * sec),
                ay = 2.5 * sin(2.0 * PI * 2.0 * sec),
                az = 1.5 * sin(2.0 * PI * 1.5 * sec),
                gx = 0.3 * sin(2.0 * PI * 0.5 * sec),
                gy = 0.4 * sin(2.0 * PI * 0.4 * sec),
                gz = 0.6 * sin(2.0 * PI * 0.3 * sec),
            )
        }
        val events = feedAll(recognizer, frames)

        assertTrue(events.filterIsInstance<RecognizerEvent.Detected>().isEmpty(), "Expected zero false positives, got $events")
    }

    @Test
    fun `reset restores initial state and recognition is deterministic`() {
        val recognizer = ThresholdRecognizer()
        val stream = buildCatchFrames(startMs = 0L, peakAy = 11.0, durationMs = 160L) +
            buildCircleFrames(startMs = 1000L, rateRadPerSec = 6.0, durationMs = 1100L) +
            buildShakeFrames(startMs = 3000L, amplitude = 12.0, freqHz = 3.5, durationMs = 560L, tailMs = 400L)

        val firstRun = feedAll(recognizer, stream)
        recognizer.reset()
        val secondRun = feedAll(recognizer, stream)

        assertEquals(firstRun, secondRun)
        val detectedGestures = firstRun.filterIsInstance<RecognizerEvent.Detected>().map { it.gesture }
        assertEquals(listOf(Gesture.CATCH, Gesture.BANISH, Gesture.CANCEL), detectedGestures)
    }

    @Test
    fun `custom config changes detection sensitivity`() {
        // Moderate upward acceleration of 5.0 m/s²: below default (6.0), above sensitive config (4.0)
        val frames = buildCatchFrames(startMs = 0L, peakAy = 5.0, durationMs = 180L)

        val defaultEvents = feedAll(ThresholdRecognizer(), frames)
        assertTrue(defaultEvents.filterIsInstance<RecognizerEvent.Detected>().isEmpty())

        val sensitive = ThresholdRecognizer(
            ThresholdRecognizer.Config(
                catchStartThreshold = 4.0,
                catchEndThreshold = 1.5,
                catchPeakForFullConfidence = 8.0,
            ),
        )
        val sensitiveEvents = feedAll(sensitive, frames)
        assertEquals(1, sensitiveEvents.filterIsInstance<RecognizerEvent.Detected>().count { it.gesture == Gesture.CATCH })
    }

    private fun buildCatchFrames(startMs: Long, peakAy: Double, durationMs: Long): List<ImuFrame> = buildList {
        add(imu(startMs, ay = 0.0))
        var t = startMs + STEP_MS
        while (t <= startMs + durationMs) {
            val phase = (t - startMs).toDouble() / durationMs.toDouble()
            val ay = peakAy * sin(PI * phase)
            add(imu(t, ay = ay))
            t += STEP_MS
        }
        // Tail to let EMA settle below end threshold
        for (i in 1..10) {
            add(imu(t, ay = 0.0))
            t += STEP_MS
        }
    }

    private fun buildCircleFrames(
        startMs: Long,
        rateRadPerSec: Double,
        durationMs: Long,
        tailMs: Long = 400L,
    ): List<ImuFrame> = buildList {
        add(imu(startMs, gz = 0.0))
        var t = startMs + STEP_MS
        while (t <= startMs + durationMs) {
            add(imu(t, gz = rateRadPerSec))
            t += STEP_MS
        }
        val endTail = t + tailMs
        while (t <= endTail) {
            add(imu(t, gz = 0.0))
            t += STEP_MS
        }
    }

    private fun buildShakeFrames(
        startMs: Long,
        amplitude: Double,
        freqHz: Double,
        durationMs: Long,
        tailMs: Long = 400L,
    ): List<ImuFrame> = buildList {
        add(imu(startMs, ax = 0.0))
        var t = startMs + STEP_MS
        while (t <= startMs + durationMs) {
            val elapsedSec = (t - startMs) / 1000.0
            val ax = amplitude * sin(2.0 * PI * freqHz * elapsedSec)
            add(imu(t, ax = ax))
            t += STEP_MS
        }
        val endTail = t + tailMs
        while (t <= endTail) {
            add(imu(t, ax = 0.0))
            t += STEP_MS
        }
    }
}
