package ru.nsu.vrmp.shaman.game

import ru.nsu.vrmp.shaman.core.recognition.Gesture
import ru.nsu.vrmp.shaman.core.recognition.RecognizerEvent

enum class ArbiterPhase {
    IDLE,
    CANDIDATE,
    CONFIRMED,
    COMMITTED,
    CANCELLED,
    REJECTED,
}

data class ArbiterSnapshot(
    val phase: ArbiterPhase = ArbiterPhase.IDLE,
    val lastGesture: Gesture? = null,
    val activeCommandId: Long? = null,
    val undoRemainingNanos: Long = 0L,
    val rejectReason: String? = null,
) {
    val canCancel: Boolean
        get() = phase == ArbiterPhase.CONFIRMED && activeCommandId != null && undoRemainingNanos > 0L
}

sealed interface ArbiterAction {
    data class Apply(val commandId: Long, val gesture: Gesture, val tNanos: Long) : ArbiterAction
    data class Cancel(val commandId: Long, val tNanos: Long) : ArbiterAction
}

/**
 * Local command arbiter following SPEC.md §3 (2s undo window, 400ms refractory period).
 * Temporary UI-layer coordinator until `:core` CommandArbiter (#3) lands.
 */
class TouchCommandArbiter {

    private var phase = ArbiterPhase.IDLE
    private var lastGesture: Gesture? = null
    private var activeCommandId: Long? = null
    private var confirmedAtNanos = Long.MIN_VALUE
    private var refractoryUntilNanos = Long.MIN_VALUE
    private var nextCommandId = 1L
    private var rejectReason: String? = null

    fun reset() {
        phase = ArbiterPhase.IDLE
        lastGesture = null
        activeCommandId = null
        confirmedAtNanos = Long.MIN_VALUE
        refractoryUntilNanos = Long.MIN_VALUE
        nextCommandId = 1L
        rejectReason = null
    }

    fun advance(tNanos: Long): ArbiterSnapshot {
        if (phase == ArbiterPhase.CONFIRMED && tNanos >= confirmedAtNanos + UNDO_WINDOW_NANOS) {
            phase = ArbiterPhase.COMMITTED
            activeCommandId = null
        }
        return snapshot(tNanos)
    }

    fun onEvent(event: RecognizerEvent): Pair<ArbiterSnapshot, ArbiterAction?> {
        val t = event.tNanos
        advance(t)

        return when (event) {
            is RecognizerEvent.Candidate -> {
                if (t < refractoryUntilNanos) {
                    snapshot(t) to null
                } else {
                    phase = ArbiterPhase.CANDIDATE
                    lastGesture = event.gesture
                    rejectReason = null
                    snapshot(t) to null
                }
            }

            is RecognizerEvent.Rejected -> {
                phase = ArbiterPhase.REJECTED
                rejectReason = event.reason
                snapshot(t) to null
            }

            is RecognizerEvent.Detected -> handleDetected(event)
        }
    }

    private fun handleDetected(event: RecognizerEvent.Detected): Pair<ArbiterSnapshot, ArbiterAction?> {
        val t = event.tEmit
        if (event.gesture == Gesture.CANCEL) {
            val targetId = activeCommandId
            return if (phase == ArbiterPhase.CONFIRMED && targetId != null && t < confirmedAtNanos + UNDO_WINDOW_NANOS) {
                phase = ArbiterPhase.CANCELLED
                activeCommandId = null
                lastGesture = Gesture.CANCEL
                rejectReason = null
                snapshot(t) to ArbiterAction.Cancel(targetId, t)
            } else {
                phase = ArbiterPhase.REJECTED
                rejectReason = "Нет команды для отмены"
                snapshot(t) to null
            }
        }

        if (t < refractoryUntilNanos) {
            return snapshot(t) to null
        }

        val commandId = nextCommandId++
        phase = ArbiterPhase.CONFIRMED
        lastGesture = event.gesture
        activeCommandId = commandId
        confirmedAtNanos = t
        refractoryUntilNanos = t + REFRACTORY_NANOS
        rejectReason = null

        return snapshot(t) to ArbiterAction.Apply(commandId, event.gesture, t)
    }

    fun snapshot(tNanos: Long): ArbiterSnapshot {
        val undoRemaining = if (phase == ArbiterPhase.CONFIRMED && activeCommandId != null) {
            ((confirmedAtNanos + UNDO_WINDOW_NANOS) - tNanos).coerceAtLeast(0L)
        } else {
            0L
        }
        return ArbiterSnapshot(
            phase = phase,
            lastGesture = lastGesture,
            activeCommandId = activeCommandId,
            undoRemainingNanos = undoRemaining,
            rejectReason = rejectReason,
        )
    }

    companion object {
        const val UNDO_WINDOW_NANOS = 2_000_000_000L
        const val REFRACTORY_NANOS = 400_000_000L
    }
}
