package ru.nsu.vrmp.shaman.core.arbiter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.nsu.vrmp.shaman.core.recognition.Gesture
import ru.nsu.vrmp.shaman.core.recognition.RecognizerEvent

class CommandArbiterTest {

    private companion object {
        const val MS = 1_000_000L
        const val SEC = 1_000_000_000L
        const val T0 = 10_000_000_000L
    }

    private fun detected(gesture: Gesture, tNanos: Long, confidence: Double = 0.95) =
        RecognizerEvent.Detected(
            gesture = gesture,
            confidence = confidence,
            tStart = tNanos - 50 * MS,
            tEnd = tNanos,
            tEmit = tNanos,
        )

    @Test
    fun `initial state is idle and not cancellable`() {
        val arbiter = CommandArbiter()
        val snap = arbiter.snapshot(T0)
        assertEquals(ArbiterPhase.IDLE, snap.phase)
        assertNull(snap.lastGesture)
        assertNull(snap.activeCommandId)
        assertEquals(0L, snap.undoRemainingNanos)
        assertFalse(snap.canCancel)
    }

    @Test
    fun `idle to candidate to rejected`() {
        val arbiter = CommandArbiter()

        val cand = arbiter.onEvent(RecognizerEvent.Candidate(Gesture.CATCH, T0))
        assertEquals(ArbiterPhase.CANDIDATE, cand.state.phase)
        assertEquals(Gesture.CATCH, cand.state.lastGesture)
        assertEquals(listOf(ArbiterEvent.CandidateStarted(Gesture.CATCH, T0)), cand.events)

        val rej = arbiter.onEvent(RecognizerEvent.Rejected(T0 + 120 * MS, "too_short"))
        assertEquals(ArbiterPhase.REJECTED, rej.state.phase)
        assertEquals("too_short", rej.state.rejectReason)
        assertEquals(listOf(ArbiterEvent.CommandRejected(T0 + 120 * MS, "too_short")), rej.events)
    }

    @Test
    fun `idle to candidate to confirmed to committed at exact 2s boundary and idempotent advance`() {
        val arbiter = CommandArbiter()

        arbiter.onEvent(RecognizerEvent.Candidate(Gesture.BANISH, T0))
        val conf = arbiter.onEvent(detected(Gesture.BANISH, T0 + 100 * MS))
        val confirmedAt = T0 + 100 * MS

        assertEquals(ArbiterPhase.CONFIRMED, conf.state.phase)
        assertEquals(1L, conf.state.activeCommandId)
        assertEquals(2 * SEC, conf.state.undoRemainingNanos)
        assertTrue(conf.state.canCancel)
        assertEquals(
            listOf(ArbiterEvent.CommandConfirmed(1L, Gesture.BANISH, confirmedAt)),
            conf.events,
        )

        val justBefore = arbiter.advance(confirmedAt + 2 * SEC - 1L)
        assertEquals(ArbiterPhase.CONFIRMED, justBefore.state.phase)
        assertEquals(1L, justBefore.state.undoRemainingNanos)
        assertTrue(justBefore.state.canCancel)
        assertTrue(justBefore.events.isEmpty())

        val atBoundary = arbiter.advance(confirmedAt + 2 * SEC)
        assertEquals(ArbiterPhase.COMMITTED, atBoundary.state.phase)
        assertNull(atBoundary.state.activeCommandId)
        assertEquals(0L, atBoundary.state.undoRemainingNanos)
        assertFalse(atBoundary.state.canCancel)
        assertEquals(
            listOf(ArbiterEvent.CommandCommitted(1L, confirmedAt + 2 * SEC)),
            atBoundary.events,
        )

        // Second advance at same and later timestamp is idempotent
        val repeatSame = arbiter.advance(confirmedAt + 2 * SEC)
        assertEquals(ArbiterPhase.COMMITTED, repeatSame.state.phase)
        assertTrue(repeatSame.events.isEmpty())

        val repeatLater = arbiter.advance(confirmedAt + 5 * SEC)
        assertEquals(ArbiterPhase.COMMITTED, repeatLater.state.phase)
        assertTrue(repeatLater.events.isEmpty())
    }

    @Test
    fun `confirmed to cancelled within undo window`() {
        val arbiter = CommandArbiter()
        arbiter.onEvent(detected(Gesture.CATCH, T0))

        val cancelAt = T0 + 1_200 * MS
        val cancelled = arbiter.onEvent(detected(Gesture.CANCEL, cancelAt))
        assertEquals(ArbiterPhase.CANCELLED, cancelled.state.phase)
        assertEquals(Gesture.CANCEL, cancelled.state.lastGesture)
        assertNull(cancelled.state.activeCommandId)
        assertFalse(cancelled.state.canCancel)
        assertEquals(listOf(ArbiterEvent.CommandCancelled(1L, cancelAt)), cancelled.events)

        // Advancing past original 2s deadline must NOT emit CommandCommitted
        val afterDeadline = arbiter.advance(T0 + 3 * SEC)
        assertEquals(ArbiterPhase.CANCELLED, afterDeadline.state.phase)
        assertTrue(afterDeadline.events.isEmpty())
    }

    @Test
    fun `cancel works inside 400ms refractory period`() {
        val arbiter = CommandArbiter()
        arbiter.onEvent(detected(Gesture.CATCH, T0))

        val candCancel = arbiter.onEvent(RecognizerEvent.Candidate(Gesture.CANCEL, T0 + 50 * MS))
        assertEquals(listOf(ArbiterEvent.CandidateStarted(Gesture.CANCEL, T0 + 50 * MS)), candCancel.events)

        val cancelAt = T0 + 100 * MS
        val cancelled = arbiter.onEvent(detected(Gesture.CANCEL, cancelAt))
        assertEquals(ArbiterPhase.CANCELLED, cancelled.state.phase)
        assertNull(cancelled.state.activeCommandId)
        assertEquals(listOf(ArbiterEvent.CommandCancelled(1L, cancelAt)), cancelled.events)
    }

    @Test
    fun `cancel at exact 2s boundary commits old command and rejects cancel`() {
        val arbiter = CommandArbiter()
        arbiter.onEvent(detected(Gesture.CATCH, T0))

        val boundary = T0 + 2 * SEC
        val res = arbiter.onEvent(detected(Gesture.CANCEL, boundary))
        assertEquals(ArbiterPhase.REJECTED, res.state.phase)
        assertNull(res.state.activeCommandId)
        assertFalse(res.state.canCancel)
        assertEquals(
            listOf(
                ArbiterEvent.CommandCommitted(1L, boundary),
                ArbiterEvent.CommandRejected(boundary, CommandArbiter.REASON_NO_ACTIVE_COMMAND),
            ),
            res.events,
        )
    }

    @Test
    fun `cancel with no active command is rejected`() {
        val arbiter = CommandArbiter()

        // In IDLE
        val idleCancel = arbiter.onEvent(detected(Gesture.CANCEL, T0))
        assertEquals(ArbiterPhase.REJECTED, idleCancel.state.phase)
        assertEquals(
            listOf(ArbiterEvent.CommandRejected(T0, CommandArbiter.REASON_NO_ACTIVE_COMMAND)),
            idleCancel.events,
        )

        // After COMMITTED
        arbiter.onEvent(detected(Gesture.BANISH, T0 + SEC))
        arbiter.advance(T0 + 3 * SEC)
        val committedCancel = arbiter.onEvent(detected(Gesture.CANCEL, T0 + 3_500 * MS))
        assertEquals(ArbiterPhase.REJECTED, committedCancel.state.phase)
        assertEquals(
            listOf(ArbiterEvent.CommandRejected(T0 + 3_500 * MS, CommandArbiter.REASON_NO_ACTIVE_COMMAND)),
            committedCancel.events,
        )

        // After CANCELLED
        arbiter.onEvent(detected(Gesture.CATCH, T0 + 5 * SEC))
        arbiter.onEvent(detected(Gesture.CANCEL, T0 + 5_500 * MS))
        val doubleCancel = arbiter.onEvent(detected(Gesture.CANCEL, T0 + 5_700 * MS))
        assertEquals(ArbiterPhase.REJECTED, doubleCancel.state.phase)
        assertEquals(
            listOf(ArbiterEvent.CommandRejected(T0 + 5_700 * MS, CommandArbiter.REASON_NO_ACTIVE_COMMAND)),
            doubleCancel.events,
        )
    }

    @Test
    fun `refractory period suppresses duplicate non-cancel commands and commits previous at 400ms`() {
        val arbiter = CommandArbiter()
        val first = arbiter.onEvent(detected(Gesture.CATCH, T0))
        assertEquals(listOf(ArbiterEvent.CommandConfirmed(1L, Gesture.CATCH, T0)), first.events)

        // Within 400ms: candidate and detected for CATCH/BANISH are suppressed
        val suppressedCand = arbiter.onEvent(RecognizerEvent.Candidate(Gesture.BANISH, T0 + 200 * MS))
        assertEquals(ArbiterPhase.CONFIRMED, suppressedCand.state.phase)
        assertEquals(1L, suppressedCand.state.activeCommandId)
        assertTrue(suppressedCand.events.isEmpty())

        val suppressedDet = arbiter.onEvent(detected(Gesture.BANISH, T0 + 399 * MS))
        assertEquals(ArbiterPhase.CONFIRMED, suppressedDet.state.phase)
        assertEquals(1L, suppressedDet.state.activeCommandId)
        assertTrue(suppressedDet.events.isEmpty())

        // At exact 400ms: auto-commits command #1 and confirms command #2
        val secondAt = T0 + 400 * MS
        val second = arbiter.onEvent(detected(Gesture.BANISH, secondAt))
        assertEquals(ArbiterPhase.CONFIRMED, second.state.phase)
        assertEquals(2L, second.state.activeCommandId)
        assertEquals(Gesture.BANISH, second.state.lastGesture)
        assertEquals(2 * SEC, second.state.undoRemainingNanos)
        assertEquals(
            listOf(
                ArbiterEvent.CommandCommitted(1L, secondAt),
                ArbiterEvent.CommandConfirmed(2L, Gesture.BANISH, secondAt),
            ),
            second.events,
        )
    }

    @Test
    fun `candidate during undo window does not break subsequent cancel or expiration`() {
        val arbiter = CommandArbiter()
        arbiter.onEvent(detected(Gesture.CATCH, T0))

        // Candidate arrives after refractory (500ms) during undo window
        val cand = arbiter.onEvent(RecognizerEvent.Candidate(Gesture.BANISH, T0 + 500 * MS))
        assertEquals(ArbiterPhase.CANDIDATE, cand.state.phase)
        assertEquals(1L, cand.state.activeCommandId)
        assertTrue(cand.state.canCancel)
        assertEquals(listOf(ArbiterEvent.CandidateStarted(Gesture.BANISH, T0 + 500 * MS)), cand.events)

        // Even if that candidate is rejected, command #1 remains cancellable
        val rej = arbiter.onEvent(RecognizerEvent.Rejected(T0 + 700 * MS, "ambiguous"))
        assertEquals(1L, rej.state.activeCommandId)
        assertTrue(rej.state.canCancel)

        // CANCEL before 2s deadline cancels command #1
        val cancelAt = T0 + 900 * MS
        val cancelled = arbiter.onEvent(detected(Gesture.CANCEL, cancelAt))
        assertEquals(ArbiterPhase.CANCELLED, cancelled.state.phase)
        assertEquals(listOf(ArbiterEvent.CommandCancelled(1L, cancelAt)), cancelled.events)

        // Second check: candidate during undo window still commits old command when 2s elapses
        val secondAt = T0 + 3 * SEC
        arbiter.onEvent(detected(Gesture.BANISH, secondAt))
        arbiter.onEvent(RecognizerEvent.Candidate(Gesture.CATCH, secondAt + 1_500 * MS))
        val expired = arbiter.advance(secondAt + 2 * SEC)
        assertEquals(listOf(ArbiterEvent.CommandCommitted(2L, secondAt + 2 * SEC)), expired.events)
        assertNull(expired.state.activeCommandId)
        assertFalse(expired.state.canCancel)
    }

    @Test
    fun `reset restores exact initial state and determinism`() {
        val arbiter = CommandArbiter()

        fun runSequence(): List<ArbiterEvent> {
            val out = mutableListOf<ArbiterEvent>()
            out += arbiter.onEvent(RecognizerEvent.Candidate(Gesture.CATCH, T0)).events
            out += arbiter.onEvent(detected(Gesture.CATCH, T0 + 100 * MS)).events
            out += arbiter.onEvent(detected(Gesture.BANISH, T0 + 600 * MS)).events
            out += arbiter.advance(T0 + 3 * SEC).events
            return out
        }

        val firstRun = runSequence()
        arbiter.reset()

        val afterReset = arbiter.snapshot(T0)
        assertEquals(ArbiterPhase.IDLE, afterReset.phase)
        assertNull(afterReset.lastGesture)
        assertNull(afterReset.activeCommandId)
        assertNull(afterReset.rejectReason)
        assertEquals(0L, afterReset.undoRemainingNanos)

        val secondRun = runSequence()
        assertEquals(firstRun, secondRun)
    }

    @Test
    fun `custom config timing and validation`() {
        assertThrows<IllegalArgumentException> { ArbiterConfig(undoWindowNanos = 0L) }
        assertThrows<IllegalArgumentException> { ArbiterConfig(refractoryPeriodNanos = -1L) }

        val arbiter = CommandArbiter(
            ArbiterConfig(
                undoWindowNanos = 1_000 * MS,
                refractoryPeriodNanos = 200 * MS,
            ),
        )
        arbiter.onEvent(detected(Gesture.CATCH, T0))
        assertTrue(arbiter.onEvent(detected(Gesture.BANISH, T0 + 199 * MS)).events.isEmpty())
        val confirmed2 = arbiter.onEvent(detected(Gesture.BANISH, T0 + 200 * MS))
        assertEquals(2, confirmed2.events.size)
        assertTrue(arbiter.advance(T0 + 1_199 * MS).events.isEmpty())
        val committed2 = arbiter.advance(T0 + 1_200 * MS)
        assertEquals(listOf(ArbiterEvent.CommandCommitted(2L, T0 + 1_200 * MS)), committed2.events)
    }
}
