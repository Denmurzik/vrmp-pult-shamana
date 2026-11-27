package ru.nsu.vrmp.shaman.core.recognition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.nsu.vrmp.shaman.core.calibration.CalibrationManager
import ru.nsu.vrmp.shaman.core.input.HandFrame
import ru.nsu.vrmp.shaman.core.input.ImuFrame
import ru.nsu.vrmp.shaman.core.input.TouchAction
import ru.nsu.vrmp.shaman.core.input.TouchFrame
import ru.nsu.vrmp.shaman.core.math.Quat
import ru.nsu.vrmp.shaman.core.math.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class DtwRecognizerTest {

    // -------------------------------------------------------------------------
    // DTW math & preprocessing tests
    // -------------------------------------------------------------------------

    @Test
    fun `dtw distance of identical sequences is zero`() {
        val seq = listOf(
            doubleArrayOf(1.0, -2.0, 0.5),
            doubleArrayOf(2.0, 0.0, -1.5),
            doubleArrayOf(-1.0, 3.0, 2.0),
        )
        assertEquals(0.0, Dtw.distance(seq, seq, sakoeChibaBandRatio = 0.15), 1e-12)
    }

    @Test
    fun `dtw distance matches hand-calculated value and is symmetric`() {
        // 1D: a = [0, 1, 2], b = [1, 2, 3]
        // Optimal path: (0,0)[1] -> (1,0)[0] -> (2,1)[0] -> (2,2)[1], sum = 2.0, path len = 4 -> 0.5
        val a = listOf(doubleArrayOf(0.0), doubleArrayOf(1.0), doubleArrayOf(2.0))
        val b = listOf(doubleArrayOf(1.0), doubleArrayOf(2.0), doubleArrayOf(3.0))

        val dAB = Dtw.distance(a, b, sakoeChibaBandRatio = 0.5)
        val dBA = Dtw.distance(b, a, sakoeChibaBandRatio = 0.5)
        assertEquals(0.5, dAB, 1e-12)
        assertEquals(dAB, dBA, 1e-12)
    }

    @Test
    fun `dtw time-warped sequence has lower distance than unwarped Euclidean`() {
        // Stretched copy of [0, 2, 4, 2, 0]
        val fast = listOf(0.0, 2.0, 4.0, 2.0, 0.0).map { doubleArrayOf(it) }
        val slow = listOf(0.0, 0.0, 2.0, 2.0, 4.0, 4.0, 2.0, 2.0, 0.0, 0.0).map { doubleArrayOf(it) }

        val dtwDist = Dtw.distance(fast, slow, sakoeChibaBandRatio = 0.5)
        assertEquals(0.0, dtwDist, 1e-12)
    }

    @Test
    fun `sakoe-chiba band restricts warping window`() {
        // Step transition shifted by 2 indices in length-5 sequences:
        // a = [0, 0, 0, 10, 10], b = [0, 10, 10, 10, 10]
        // With band = 1 (ratio 0.15 -> ceil(0.75) = 1), b[1]=10 can only align to a[0..2]=0, so cost > 0.
        // With band = 3 (ratio 0.5 -> ceil(2.5) = 3), (0,0)->(1,0)->(2,0)->(3,1)->(4,2)->(4,3)->(4,4) has cost 0.
        val a = listOf(0.0, 0.0, 0.0, 10.0, 10.0).map { doubleArrayOf(it) }
        val b = listOf(0.0, 10.0, 10.0, 10.0, 10.0).map { doubleArrayOf(it) }

        val narrowDist = Dtw.distance(a, b, sakoeChibaBandRatio = 0.15)
        val wideDist = Dtw.distance(a, b, sakoeChibaBandRatio = 0.5)

        assertTrue(narrowDist > 1.0, "Expected narrow band to block 2-step shift, got $narrowDist")
        assertEquals(0.0, wideDist, 1e-12)
    }

    @Test
    fun `z-normalization produces zero mean unit std and keeps constant channels at zero`() {
        // Channel 0: [2, 4, 6, 8] -> mean = 5, pop std = sqrt(5)
        // Channel 1: [7, 7, 7, 7] -> constant -> [0, 0, 0, 0]
        val seq = listOf(
            doubleArrayOf(2.0, 7.0),
            doubleArrayOf(4.0, 7.0),
            doubleArrayOf(6.0, 7.0),
            doubleArrayOf(8.0, 7.0),
        )
        val norm = Dtw.zNormalize(seq)
        val expectedStd = sqrt(5.0)
        assertEquals((2.0 - 5.0) / expectedStd, norm[0][0], 1e-12)
        assertEquals((4.0 - 5.0) / expectedStd, norm[1][0], 1e-12)
        assertEquals((6.0 - 5.0) / expectedStd, norm[2][0], 1e-12)
        assertEquals((8.0 - 5.0) / expectedStd, norm[3][0], 1e-12)
        norm.forEach { assertEquals(0.0, it[1], 1e-12) }
    }

    @Test
    fun `resample interpolates uniformly to target length`() {
        val seq = listOf(doubleArrayOf(0.0, 10.0), doubleArrayOf(10.0, 0.0))
        val resampled = Dtw.resample(seq, targetLength = 5)
        assertEquals(5, resampled.size)
        assertEquals(0.0, resampled[0][0], 1e-12)
        assertEquals(2.5, resampled[1][0], 1e-12)
        assertEquals(5.0, resampled[2][0], 1e-12)
        assertEquals(7.5, resampled[3][0], 1e-12)
        assertEquals(10.0, resampled[4][0], 1e-12)
        assertEquals(5.0, resampled[2][1], 1e-12)
    }

    // -------------------------------------------------------------------------
    // Streaming recognition tests
    // -------------------------------------------------------------------------

    @Test
    fun `classifies three distinct synthetic gestures in streaming mode`() {
        val recognizer = DtwRecognizer(defaultTemplates())
        assertEquals("dtw", recognizer.id)

        val stream = buildList {
            var t = 0L
            addAll(quietFrames(startNanos = t, count = 10))
            t += 10 * STEP_NS

            addAll(catchFrames(startNanos = t, durationMs = 400))
            t += 400_000_000L
            addAll(quietFrames(startNanos = t, count = 15))
            t += 15 * STEP_NS

            addAll(banishFrames(startNanos = t, durationMs = 700))
            t += 700_000_000L
            addAll(quietFrames(startNanos = t, count = 15))
            t += 15 * STEP_NS

            addAll(cancelFrames(startNanos = t, durationMs = 500))
            t += 500_000_000L
            addAll(quietFrames(startNanos = t, count = 15))
        }

        val detected = stream.flatMap { recognizer.onFrame(it) }
            .filterIsInstance<RecognizerEvent.Detected>()

        assertEquals(
            listOf(Gesture.CATCH, Gesture.BANISH, Gesture.CANCEL),
            detected.map { it.gesture },
        )
        detected.forEach { ev ->
            assertTrue(ev.confidence in 0.5..1.0, "Expected high confidence, got ${ev.confidence}")
            assertTrue(ev.tStart < ev.tEnd && ev.tEnd <= ev.tEmit)
        }
    }

    @Test
    fun `recognizes speed-stretched and speed-compressed trajectory gesture`() {
        // H2: DTW speed invariance on BANISH (circle) at 0.55x and 1.8x duration
        val recognizer = DtwRecognizer(defaultTemplates())

        val slowStream = buildList {
            addAll(banishFrames(startNanos = 0L, durationMs = 1200))
            addAll(quietFrames(startNanos = 1_200_000_000L, count = 15))
        }
        val slowDetected = slowStream.flatMap { recognizer.onFrame(it) }
            .filterIsInstance<RecognizerEvent.Detected>()
        assertEquals(listOf(Gesture.BANISH), slowDetected.map { it.gesture })

        recognizer.reset()

        val fastStream = buildList {
            addAll(banishFrames(startNanos = 0L, durationMs = 350))
            addAll(quietFrames(startNanos = 350_000_000L, count = 15))
        }
        val fastDetected = fastStream.flatMap { recognizer.onFrame(it) }
            .filterIsInstance<RecognizerEvent.Detected>()
        assertEquals(listOf(Gesture.BANISH), fastDetected.map { it.gesture })
    }

    @Test
    fun `rejects unknown gesture when distance exceeds threshold`() {
        val recognizer = DtwRecognizer(
            templates = listOf(DtwTemplate(Gesture.CATCH, catchFrames(0L, 400))),
            config = DtwRecognizer.Config(rejectThreshold = 0.6),
        )

        // Feed a completely different motion pattern (BANISH circle)
        val stream = buildList {
            addAll(banishFrames(startNanos = 0L, durationMs = 700))
            addAll(quietFrames(startNanos = 700_000_000L, count = 15))
        }

        val events = stream.flatMap { recognizer.onFrame(it) }
        assertTrue(events.none { it is RecognizerEvent.Detected }, "Expected no detection, got $events")
        assertTrue(
            events.any { it is RecognizerEvent.Rejected && it.reason.contains("threshold", ignoreCase = true) },
            "Expected distance rejection, got $events",
        )
    }

    @Test
    fun `rejects segment shorter than minDurationNanos`() {
        val recognizer = DtwRecognizer(
            templates = defaultTemplates(),
            config = DtwRecognizer.Config(minDurationNanos = 200_000_000L),
        )

        // 60ms spike followed by quiet hold
        val stream = buildList {
            addAll(catchFrames(startNanos = 0L, durationMs = 60))
            addAll(quietFrames(startNanos = 60_000_000L, count = 15))
        }

        val events = stream.flatMap { recognizer.onFrame(it) }
        assertTrue(events.none { it is RecognizerEvent.Detected })
        assertTrue(
            events.any { it is RecognizerEvent.Rejected && it.reason.contains("short", ignoreCase = true) },
            "Expected short segment rejection, got $events",
        )
    }

    @Test
    fun `rejects cleanly when templates list is empty`() {
        val recognizer = DtwRecognizer(templates = emptyList())

        val stream = buildList {
            addAll(catchFrames(startNanos = 0L, durationMs = 400))
            addAll(quietFrames(startNanos = 400_000_000L, count = 15))
        }

        val events = stream.flatMap { recognizer.onFrame(it) }
        assertTrue(events.none { it is RecognizerEvent.Detected })
        assertTrue(
            events.any { it is RecognizerEvent.Rejected && it.reason.contains("template", ignoreCase = true) },
            "Expected no-templates rejection, got $events",
        )
    }

    @Test
    fun `null stream with low-amplitude noise produces zero detections`() {
        val recognizer = DtwRecognizer(defaultTemplates())

        val events = (0 until 200).flatMap { i ->
            val t = i * STEP_NS
            val noise = 0.15 * sin(i * 0.3)
            recognizer.onFrame(
                ImuFrame(
                    tNanos = t,
                    linAcc = Vec3(noise, -noise * 0.5, noise * 0.8),
                    gyro = Vec3(noise * 0.1, noise * 0.1, -noise * 0.1),
                    rotation = Quat.IDENTITY,
                ),
            )
        }

        assertTrue(events.isEmpty(), "Expected zero events on quiet/noisy null stream, got $events")
    }

    @Test
    fun `reset restores deterministic behavior`() {
        val recognizer = DtwRecognizer(defaultTemplates())
        val stream = buildList {
            addAll(catchFrames(startNanos = 0L, durationMs = 400))
            addAll(quietFrames(startNanos = 400_000_000L, count = 15))
        }

        val firstRun = stream.flatMap { recognizer.onFrame(it) }
        recognizer.reset()
        val secondRun = stream.flatMap { recognizer.onFrame(it) }

        assertEquals(firstRun, secondRun)
        assertTrue(firstRun.any { it is RecognizerEvent.Detected && it.gesture == Gesture.CATCH })
    }

    @Test
    fun `ignores non-ImuFrame and handles NaN or Infinite frames safely`() {
        val recognizer = DtwRecognizer(defaultTemplates())

        assertTrue(recognizer.onFrame(TouchFrame(1L, TouchAction.Press(Gesture.CATCH))).isEmpty())
        assertTrue(recognizer.onFrame(HandFrame.absent(2L)).isEmpty())
        assertTrue(
            recognizer.onFrame(
                ImuFrame(
                    tNanos = 3L,
                    linAcc = Vec3(Double.NaN, 0.0, 0.0),
                    gyro = Vec3(0.0, Double.POSITIVE_INFINITY, 0.0),
                    rotation = Quat.IDENTITY,
                ),
            ).isEmpty(),
        )

        // Recognizer still works cleanly afterwards
        val stream = buildList {
            addAll(catchFrames(startNanos = 100_000_000L, durationMs = 400))
            addAll(quietFrames(startNanos = 500_000_000L, count = 15))
        }
        val detected = stream.flatMap { recognizer.onFrame(it) }
            .filterIsInstance<RecognizerEvent.Detected>()
        assertEquals(listOf(Gesture.CATCH), detected.map { it.gesture })
    }

    @Test
    fun `converts CalibrationProfile templates into DtwTemplates`() {
        val manager = CalibrationManager(targetTemplatesPerGesture = 2)
        manager.captureZeroHeading(Quat.IDENTITY)

        repeat(2) { manager.recordImuTemplate(catchFrames(0L, 400)) }
        repeat(2) { manager.recordImuTemplate(banishFrames(0L, 700)) }
        repeat(2) { manager.recordImuTemplate(cancelFrames(0L, 500)) }

        val profile = manager.buildProfile()
        val dtwTemplates = DtwTemplate.fromCalibration(profile)

        assertEquals(6, dtwTemplates.size)
        assertEquals(2, dtwTemplates.count { it.gesture == Gesture.CATCH })
        assertEquals(2, dtwTemplates.count { it.gesture == Gesture.BANISH })
        assertEquals(2, dtwTemplates.count { it.gesture == Gesture.CANCEL })

        val recognizer = DtwRecognizer(dtwTemplates)
        val stream = buildList {
            addAll(banishFrames(startNanos = 0L, durationMs = 700))
            addAll(quietFrames(startNanos = 700_000_000L, count = 15))
        }
        val detected = stream.flatMap { recognizer.onFrame(it) }
            .filterIsInstance<RecognizerEvent.Detected>()
        assertEquals(listOf(Gesture.BANISH), detected.map { it.gesture })
    }

    @Test
    fun `featureSet options restrict channels cleanly`() {
        val gyroOnlyConfig = DtwRecognizer.Config(featureSet = FeatureSet.GYRO_ONLY)
        val accOnlyConfig = DtwRecognizer.Config(featureSet = FeatureSet.LIN_ACC_ONLY)

        val gyroRec = DtwRecognizer(defaultTemplates(), gyroOnlyConfig)
        val accRec = DtwRecognizer(defaultTemplates(), accOnlyConfig)

        val stream = buildList {
            addAll(banishFrames(startNanos = 0L, durationMs = 700))
            addAll(quietFrames(startNanos = 700_000_000L, count = 15))
        }

        val gyroDetected = stream.flatMap { gyroRec.onFrame(it) }.filterIsInstance<RecognizerEvent.Detected>()
        val accDetected = stream.flatMap { accRec.onFrame(it) }.filterIsInstance<RecognizerEvent.Detected>()

        assertEquals(listOf(Gesture.BANISH), gyroDetected.map { it.gesture })
        assertEquals(listOf(Gesture.BANISH), accDetected.map { it.gesture })
    }

    @Test
    fun `invalid config throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            DtwRecognizer(emptyList(), DtwRecognizer.Config(sakoeChibaBandRatio = -0.1))
        }
        assertThrows<IllegalArgumentException> {
            DtwRecognizer(emptyList(), DtwRecognizer.Config(energyStartThreshold = 1.0, energyEndThreshold = 2.0))
        }
    }

    // -------------------------------------------------------------------------
    // Synthetic IMU gesture generators (50 Hz, step = 20 ms)
    // -------------------------------------------------------------------------

    private fun defaultTemplates(): List<DtwTemplate> = listOf(
        DtwTemplate(Gesture.CATCH, catchFrames(0L, 400)),
        DtwTemplate(Gesture.BANISH, banishFrames(0L, 700)),
        DtwTemplate(Gesture.CANCEL, cancelFrames(0L, 500)),
    )

    private fun quietFrames(startNanos: Long, count: Int): List<ImuFrame> =
        (0 until count).map { i ->
            ImuFrame(
                tNanos = startNanos + i * STEP_NS,
                linAcc = Vec3.ZERO,
                gyro = Vec3.ZERO,
                rotation = Quat.IDENTITY,
            )
        }

    /** Upward flick: strong positive Y pulse followed by negative rebound + pitch gyro. */
    private fun catchFrames(startNanos: Long, durationMs: Long): List<ImuFrame> {
        val count = (durationMs / 20L).toInt().coerceAtLeast(3)
        return (0 until count).map { i ->
            val u = i.toDouble() / (count - 1).coerceAtLeast(1)
            val ay = 10.0 * sin(2.0 * PI * u)
            val az = 3.0 * sin(PI * u)
            val gx = 4.0 * sin(PI * u)
            ImuFrame(
                tNanos = startNanos + i * STEP_NS,
                linAcc = Vec3(0.2, ay, az),
                gyro = Vec3(gx, 0.1, 0.0),
                rotation = Quat.IDENTITY,
            )
        }
    }

    /** Circle: smooth 2D sinusoidal acceleration in XY + steady Z angular velocity. */
    private fun banishFrames(startNanos: Long, durationMs: Long): List<ImuFrame> {
        val count = (durationMs / 20L).toInt().coerceAtLeast(3)
        return (0 until count).map { i ->
            val u = i.toDouble() / (count - 1).coerceAtLeast(1)
            val angle = 2.0 * PI * u
            val ax = 6.0 * cos(angle)
            val ay = 6.0 * sin(angle)
            val gz = 5.0 * sin(PI * u) + 1.5
            ImuFrame(
                tNanos = startNanos + i * STEP_NS,
                linAcc = Vec3(ax, ay, 0.3),
                gyro = Vec3(0.2 * cos(angle), 0.2 * sin(angle), gz),
                rotation = Quat.IDENTITY,
            )
        }
    }

    /** Shake: rapid high-frequency oscillation along X + yaw oscillation. */
    private fun cancelFrames(startNanos: Long, durationMs: Long): List<ImuFrame> {
        val count = (durationMs / 20L).toInt().coerceAtLeast(3)
        return (0 until count).map { i ->
            val u = i.toDouble() / (count - 1).coerceAtLeast(1)
            val ax = 9.0 * sin(6.0 * PI * u)
            val gy = 5.5 * cos(6.0 * PI * u)
            ImuFrame(
                tNanos = startNanos + i * STEP_NS,
                linAcc = Vec3(ax, 0.4, 0.2),
                gyro = Vec3(0.1, gy, 0.2),
                rotation = Quat.IDENTITY,
            )
        }
    }

    companion object {
        private const val STEP_NS = 20_000_000L // 50 Hz
    }
}
