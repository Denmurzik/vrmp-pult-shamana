package ru.nsu.vrmp.shaman.core.recognition

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.nsu.vrmp.shaman.core.input.HandFrame
import ru.nsu.vrmp.shaman.core.math.Vec3

class CameraRecognizerTest {
    @Test
    fun `closing an open hand reports one catch with frame timestamps`() {
        val recognizer = CameraRecognizer()
        assertTrue(recognizer.onFrame(hand(0)).isEmpty())
        assertEquals(
            listOf(RecognizerEvent.Candidate(Gesture.CATCH, 20 * MS)),
            recognizer.onFrame(hand(20, flex = 0.5)),
        )
        val detections = recognizer.onFrame(hand(40, flex = 1.0))
            .filterIsInstance<RecognizerEvent.Detected>()
        assertEquals(Gesture.CATCH, detections.single().gesture)
        assertEquals(20 * MS, detections.single().tStart)
        assertEquals(40 * MS, detections.single().tEnd)
        assertEquals(40 * MS, detections.single().tEmit)
        assertTrue(recognizer.onFrame(hand(60, flex = 1.0)).isEmpty())
    }

    @Test
    fun `a loop with the index fingertip banishes in either direction`() {
        for (direction in listOf(-1, 1)) {
            val recognizer = CameraRecognizer()
            val events = (0..16).flatMap { step ->
                val angle = direction * step * PI / 8
                val tip = Vec3(0.49 + 0.08 * cos(angle), 0.37 + 0.08 * sin(angle), 0.0)
                recognizer.onFrame(hand(step * 50L, indexTip = tip))
            }
            val candidate = events.filterIsInstance<RecognizerEvent.Candidate>().first()
            val detection = events.filterIsInstance<RecognizerEvent.Detected>().single()
            assertEquals(Gesture.BANISH, candidate.gesture)
            assertEquals(Gesture.BANISH, detection.gesture)
            assertEquals(0L, detection.tStart)
            assertTrue(candidate.tNanos < detection.tEmit)
        }
    }

    @Test
    fun `two significant horizontal reversals within 800 ms cancel`() {
        val recognizer = CameraRecognizer()
        val events = listOf(0.0, 0.11, 0.104, -0.02, 0.10).flatMapIndexed { i, x ->
            recognizer.onFrame(hand(i * 200L, shiftX = x))
        }
        val candidate = events.filterIsInstance<RecognizerEvent.Candidate>().single()
        val detection = events.filterIsInstance<RecognizerEvent.Detected>().single()
        assertEquals(RecognizerEvent.Candidate(Gesture.CANCEL, 600 * MS), candidate)
        assertEquals(Gesture.CANCEL, detection.gesture)
        assertEquals(0L, detection.tStart)
        assertEquals(800 * MS, detection.tEnd)
    }

    @Test
    fun `reversals spread over more than 800 ms do not cancel`() {
        val recognizer = CameraRecognizer()
        val events = listOf(0L to 0.0, 200L to 0.11, 460L to -0.02, 600L to -0.02, 810L to 0.10)
            .flatMap { (time, x) -> recognizer.onFrame(hand(time, shiftX = x)) }
        assertTrue(events.none { it is RecognizerEvent.Detected })
    }

    @Test
    fun `a hand lost for over 300 ms rejects and disarms an active fist candidate`() {
        val recognizer = CameraRecognizer()
        recognizer.onFrame(hand(0))
        recognizer.onFrame(hand(20, flex = 0.5))
        assertTrue(recognizer.onFrame(HandFrame.absent(320 * MS)).isEmpty())
        assertEquals(
            listOf(RecognizerEvent.Rejected(321 * MS, "hand lost")),
            recognizer.onFrame(HandFrame.absent(321 * MS)),
        )
        assertTrue(recognizer.onFrame(hand(340, flex = 1.0)).isEmpty())
        recognizer.onFrame(hand(360))
        val events = recognizer.onFrame(hand(380, flex = 1.0))
        assertEquals(Gesture.CATCH, events.filterIsInstance<RecognizerEvent.Detected>().single().gesture)
    }

    @Test
    fun `a pointing hand does not arm the fist gesture`() {
        val recognizer = CameraRecognizer()
        val open = hand(0)
        val pointing = hand(0, flex = 1.0).landmarks.toMutableList()
        for (point in 5..8) pointing[point] = open.landmarks[point]
        recognizer.onFrame(HandFrame(0, true, pointing))
        assertTrue(recognizer.onFrame(hand(40, flex = 1.0)).isEmpty())
    }

    private fun hand(
        timeMs: Long,
        flex: Double = 0.0,
        shiftX: Double = 0.0,
        indexTip: Vec3? = null,
    ): HandFrame {
        val points = MutableList(HandFrame.LANDMARK_COUNT) { Vec3(0.5 + shiftX, 0.7, 0.0) }
        for ((finger, mcp) in listOf(5, 9, 13, 17).withIndex()) {
            val x = 0.44 + 0.04 * finger + shiftX
            points[mcp] = Vec3(x, 0.55, 0.0)
            points[mcp + 1] = Vec3(x, 0.50, 0.0)
            points[mcp + 2] = Vec3(x, 0.46 + flex * 0.06, -flex * 0.04)
            points[mcp + 3] = Vec3(x, 0.42 + flex * 0.13, -flex * 0.025)
        }
        if (indexTip != null) points[8] = indexTip
        return HandFrame(timeMs * MS, true, points)
    }

    private companion object {
        const val MS = 1_000_000L
    }
}
