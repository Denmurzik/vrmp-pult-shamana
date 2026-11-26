package ru.nsu.vrmp.shaman.core.training

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.nsu.vrmp.shaman.core.input.InputFrame
import ru.nsu.vrmp.shaman.core.input.TouchAction
import ru.nsu.vrmp.shaman.core.input.TouchFrame
import ru.nsu.vrmp.shaman.core.recognition.Gesture
import ru.nsu.vrmp.shaman.core.recognition.GestureRecognizer
import ru.nsu.vrmp.shaman.core.recognition.RecognizerEvent
import ru.nsu.vrmp.shaman.core.recognition.TouchRecognizer

class GestureTrainerTest {

    private class FakeRecognizer(private val script: (InputFrame) -> List<RecognizerEvent>) : GestureRecognizer {
        override val id: String = "fake"
        var resetCount = 0
        override fun reset() {
            resetCount++
        }
        override fun onFrame(frame: InputFrame): List<RecognizerEvent> = script(frame)
    }

    @Test
    fun `requires 3 successful repetitions per gesture and advances through all gestures`() {
        val trainer = GestureTrainer(TouchRecognizer(), targetReps = 3)
        assertEquals(Gesture.CATCH, trainer.currentState().currentGesture)

        repeat(2) { i ->
            val res = trainer.onFrame(TouchFrame((i + 1) * 100L, TouchAction.Press(Gesture.CATCH)))
            assertEquals(TrainingAttemptStatus.SUCCESS, res.state.lastStatus)
            assertEquals(Gesture.CATCH, res.state.currentGesture)
            assertEquals(i + 1, res.state.steps[0].successCount)
        }

        // 3rd success completes CATCH and moves to BANISH
        val stepDone = trainer.onFrame(TouchFrame(300L, TouchAction.Press(Gesture.CATCH)))
        assertEquals(TrainingAttemptStatus.STEP_COMPLETED, stepDone.state.lastStatus)
        assertEquals(Gesture.BANISH, stepDone.state.currentGesture)
        assertTrue(stepDone.state.steps[0].completed)

        // Complete BANISH (3 reps) and CANCEL (3 reps)
        repeat(3) { i ->
            trainer.onFrame(TouchFrame((4 + i) * 100L, TouchAction.Press(Gesture.BANISH)))
        }
        assertEquals(Gesture.CANCEL, trainer.currentState().currentGesture)

        repeat(2) { i ->
            trainer.onFrame(TouchFrame((7 + i) * 100L, TouchAction.Press(Gesture.CANCEL)))
        }
        val finished = trainer.onFrame(TouchFrame(900L, TouchAction.Press(Gesture.CANCEL)))
        assertEquals(TrainingAttemptStatus.ALL_COMPLETED, finished.state.lastStatus)
        assertTrue(finished.state.allCompleted)
        assertNull(finished.state.currentGesture)
        assertEquals(9, finished.state.totalAttempts)
    }

    @Test
    fun `wrong gestures and rejections increment attempts without incrementing successes`() {
        val fake = FakeRecognizer { frame ->
            when (frame.tNanos) {
                1L -> listOf(RecognizerEvent.Candidate(Gesture.CATCH, 1L))
                2L -> listOf(RecognizerEvent.Rejected(2L, "too short"))
                3L -> listOf(RecognizerEvent.Detected(Gesture.BANISH, 0.9, 2L, 3L, 3L))
                4L -> listOf(RecognizerEvent.Detected(Gesture.CATCH, 0.95, 3L, 4L, 4L))
                else -> emptyList()
            }
        }
        val trainer = GestureTrainer(fake, targetReps = 3)

        val cand = trainer.onFrame(TouchFrame(1L, TouchAction.Scan(0.0))).state
        assertEquals(TrainingAttemptStatus.CANDIDATE, cand.lastStatus)
        assertEquals(0, cand.steps[0].attemptCount)

        val rej = trainer.onFrame(TouchFrame(2L, TouchAction.Scan(0.0))).state
        assertEquals(TrainingAttemptStatus.REJECTED, rej.lastStatus)
        assertEquals(1, rej.steps[0].attemptCount)
        assertEquals(0, rej.steps[0].successCount)

        val wrong = trainer.onFrame(TouchFrame(3L, TouchAction.Scan(0.0))).state
        assertEquals(TrainingAttemptStatus.WRONG_GESTURE, wrong.lastStatus)
        assertEquals(2, wrong.steps[0].attemptCount)
        assertEquals(0, wrong.steps[0].successCount)

        val ok = trainer.onFrame(TouchFrame(4L, TouchAction.Scan(0.0))).state
        assertEquals(TrainingAttemptStatus.SUCCESS, ok.lastStatus)
        assertEquals(3, ok.steps[0].attemptCount)
        assertEquals(1, ok.steps[0].successCount)
        assertFalse(ok.allCompleted)
    }

    @Test
    fun `reset clears all progress and resets recognizer`() {
        val fake = FakeRecognizer { listOf(RecognizerEvent.Detected(Gesture.CATCH, 1.0, it.tNanos, it.tNanos, it.tNanos)) }
        val trainer = GestureTrainer(fake, targetReps = 3)
        trainer.onFrame(TouchFrame(10L, TouchAction.Press(Gesture.CATCH)))
        assertEquals(1, trainer.currentState().totalAttempts)

        trainer.reset()
        assertEquals(0, trainer.currentState().totalAttempts)
        assertEquals(Gesture.CATCH, trainer.currentState().currentGesture)
        assertTrue(fake.resetCount >= 2)
    }
}
