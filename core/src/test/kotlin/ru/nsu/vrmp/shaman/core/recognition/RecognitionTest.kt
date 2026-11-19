package ru.nsu.vrmp.shaman.core.recognition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.nsu.vrmp.shaman.core.input.HandFrame
import ru.nsu.vrmp.shaman.core.input.ImuFrame
import ru.nsu.vrmp.shaman.core.input.TouchAction
import ru.nsu.vrmp.shaman.core.input.TouchFrame
import ru.nsu.vrmp.shaman.core.math.Quat
import ru.nsu.vrmp.shaman.core.math.Vec3

class DetectedEventTest {
    @Test
    fun `valid event`() {
        val e = RecognizerEvent.Detected(Gesture.CATCH, 0.8, tStart = 10, tEnd = 20, tEmit = 25)
        assertEquals(25L, e.tNanos)
    }

    @Test
    fun `invalid events are rejected`() {
        assertThrows<IllegalArgumentException> { RecognizerEvent.Detected(Gesture.CATCH, 1.5, 0, 1, 2) }
        assertThrows<IllegalArgumentException> { RecognizerEvent.Detected(Gesture.CATCH, 0.5, 5, 1, 6) }
        assertThrows<IllegalArgumentException> { RecognizerEvent.Detected(Gesture.CATCH, 0.5, 0, 5, 4) }
    }
}

class TouchRecognizerTest {
    private val recognizer = TouchRecognizer()

    @Test
    fun `button press becomes instant detection`() {
        val events = recognizer.onFrame(TouchFrame(100L, TouchAction.Press(Gesture.BANISH)))
        assertEquals(
            listOf(RecognizerEvent.Detected(Gesture.BANISH, 1.0, tStart = 100, tEnd = 100, tEmit = 100)),
            events,
        )
    }

    @Test
    fun `scan swipes and foreign frames are ignored`() {
        assertTrue(recognizer.onFrame(TouchFrame(1L, TouchAction.Scan(0.1))).isEmpty())
        assertTrue(recognizer.onFrame(ImuFrame(2L, Vec3.ZERO, Vec3.ZERO, Quat.IDENTITY)).isEmpty())
        assertTrue(recognizer.onFrame(HandFrame.absent(3L)).isEmpty())
    }
}
