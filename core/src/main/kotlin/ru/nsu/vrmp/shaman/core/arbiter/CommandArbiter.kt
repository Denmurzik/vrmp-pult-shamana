package ru.nsu.vrmp.shaman.core.arbiter

import ru.nsu.vrmp.shaman.core.recognition.Gesture
import ru.nsu.vrmp.shaman.core.recognition.RecognizerEvent

/**
 * Lifecycle phase of a command in [CommandArbiter] (see docs/SPEC.md §3).
 */
enum class ArbiterPhase {
    IDLE,
    CANDIDATE,
    CONFIRMED,
    COMMITTED,
    CANCELLED,
    REJECTED,
}

/**
 * Timing configuration for [CommandArbiter] in nanoseconds.
 */
data class ArbiterConfig(
    val undoWindowNanos: Long = DEFAULT_UNDO_WINDOW_NANOS,
    val refractoryPeriodNanos: Long = DEFAULT_REFRACTORY_PERIOD_NANOS,
) {
    init {
        require(undoWindowNanos > 0L) { "undoWindowNanos must be > 0, got $undoWindowNanos" }
        require(refractoryPeriodNanos >= 0L) {
            "refractoryPeriodNanos must be >= 0, got $refractoryPeriodNanos"
        }
    }

    companion object {
        const val DEFAULT_UNDO_WINDOW_NANOS = 2_000_000_000L
        const val DEFAULT_REFRACTORY_PERIOD_NANOS = 400_000_000L
    }
}

/**
 * Immutable snapshot of [CommandArbiter] at a specific timestamp.
 */
data class ArbiterState(
    val phase: ArbiterPhase = ArbiterPhase.IDLE,
    val lastGesture: Gesture? = null,
    val activeCommandId: Long? = null,
    val undoRemainingNanos: Long = 0L,
    val rejectReason: String? = null,
) {
    val canCancel: Boolean
        get() = activeCommandId != null && undoRemainingNanos > 0L
}

/**
 * Deterministic events emitted by [CommandArbiter] for game execution, feedback, and logging.
 */
sealed interface ArbiterEvent {
    val tNanos: Long

    data class CandidateStarted(
        val gesture: Gesture,
        override val tNanos: Long,
    ) : ArbiterEvent

    data class CommandConfirmed(
        val commandId: Long,
        val gesture: Gesture,
        override val tNanos: Long,
    ) : ArbiterEvent

    data class CommandCommitted(
        val commandId: Long,
        override val tNanos: Long,
    ) : ArbiterEvent

    data class CommandCancelled(
        val commandId: Long,
        override val tNanos: Long,
    ) : ArbiterEvent

    data class CommandRejected(
        override val tNanos: Long,
        val reason: String,
    ) : ArbiterEvent
}

/**
 * Combined return value of [CommandArbiter.onEvent] and [CommandArbiter.advance].
 */
data class ArbiterResult(
    val state: ArbiterState,
    val events: List<ArbiterEvent>,
)

/**
 * Deterministic command lifecycle state machine shared across all gesture recognizers:
 *
 * ```
 * IDLE -> CANDIDATE -> CONFIRMED -> (2s undo window) -> COMMITTED
 *                   \-> REJECTED            \-> CANCELLED (Gesture.CANCEL within undo window)
 * ```
 *
 * Uses only frame/event timestamps (`tNanos`), never wall-clock time.
 */
class CommandArbiter(
    private val config: ArbiterConfig = ArbiterConfig(),
) {
    private var phase: ArbiterPhase = ArbiterPhase.IDLE
    private var lastGesture: Gesture? = null
    private var pendingCommandId: Long? = null
    private var pendingConfirmedAtNanos: Long = Long.MIN_VALUE
    private var refractoryUntilNanos: Long = Long.MIN_VALUE
    private var nextCommandId: Long = 1L
    private var rejectReason: String? = null

    fun reset() {
        phase = ArbiterPhase.IDLE
        lastGesture = null
        pendingCommandId = null
        pendingConfirmedAtNanos = Long.MIN_VALUE
        refractoryUntilNanos = Long.MIN_VALUE
        nextCommandId = 1L
        rejectReason = null
    }

    /**
     * Advances internal timers to [tNanos], committing an expired undo window at most once.
     */
    fun advance(tNanos: Long): ArbiterResult {
        val events = mutableListOf<ArbiterEvent>()
        expirePendingIfNeeded(tNanos, events)
        return ArbiterResult(snapshot(tNanos), events)
    }

    /**
     * Processes a [RecognizerEvent] at its timestamp (`event.tNanos`), returning the updated
     * [ArbiterState] and all [ArbiterEvent]s emitted in chronological order.
     */
    fun onEvent(event: RecognizerEvent): ArbiterResult {
        val t = event.tNanos
        val events = mutableListOf<ArbiterEvent>()
        expirePendingIfNeeded(t, events)

        when (event) {
            is RecognizerEvent.Candidate -> handleCandidate(event, events)
            is RecognizerEvent.Rejected -> handleRejected(event, events)
            is RecognizerEvent.Detected -> handleDetected(event, events)
        }

        return ArbiterResult(snapshot(t), events)
    }

    fun snapshot(tNanos: Long): ArbiterState {
        val id = pendingCommandId
        val undoRemaining = if (id != null) {
            ((pendingConfirmedAtNanos + config.undoWindowNanos) - tNanos).coerceAtLeast(0L)
        } else {
            0L
        }
        return ArbiterState(
            phase = phase,
            lastGesture = lastGesture,
            activeCommandId = if (undoRemaining > 0L) id else null,
            undoRemainingNanos = undoRemaining,
            rejectReason = rejectReason,
        )
    }

    private fun expirePendingIfNeeded(tNanos: Long, events: MutableList<ArbiterEvent>) {
        val id = pendingCommandId ?: return
        if (tNanos >= pendingConfirmedAtNanos + config.undoWindowNanos) {
            pendingCommandId = null
            pendingConfirmedAtNanos = Long.MIN_VALUE
            if (phase != ArbiterPhase.CANDIDATE) {
                phase = ArbiterPhase.COMMITTED
            }
            events += ArbiterEvent.CommandCommitted(commandId = id, tNanos = tNanos)
        }
    }

    private fun handleCandidate(event: RecognizerEvent.Candidate, events: MutableList<ArbiterEvent>) {
        val t = event.tNanos
        if (event.gesture != Gesture.CANCEL && t < refractoryUntilNanos) {
            return
        }
        phase = ArbiterPhase.CANDIDATE
        lastGesture = event.gesture
        rejectReason = null
        events += ArbiterEvent.CandidateStarted(gesture = event.gesture, tNanos = t)
    }

    private fun handleRejected(event: RecognizerEvent.Rejected, events: MutableList<ArbiterEvent>) {
        phase = ArbiterPhase.REJECTED
        rejectReason = event.reason
        events += ArbiterEvent.CommandRejected(tNanos = event.tNanos, reason = event.reason)
    }

    private fun handleDetected(event: RecognizerEvent.Detected, events: MutableList<ArbiterEvent>) {
        val t = event.tEmit
        if (event.gesture == Gesture.CANCEL) {
            val targetId = pendingCommandId
            if (targetId != null) {
                pendingCommandId = null
                pendingConfirmedAtNanos = Long.MIN_VALUE
                phase = ArbiterPhase.CANCELLED
                lastGesture = Gesture.CANCEL
                rejectReason = null
                events += ArbiterEvent.CommandCancelled(commandId = targetId, tNanos = t)
            } else {
                phase = ArbiterPhase.REJECTED
                rejectReason = REASON_NO_ACTIVE_COMMAND
                events += ArbiterEvent.CommandRejected(tNanos = t, reason = REASON_NO_ACTIVE_COMMAND)
            }
            return
        }

        if (t < refractoryUntilNanos) {
            return
        }

        val previousId = pendingCommandId
        if (previousId != null) {
            pendingCommandId = null
            pendingConfirmedAtNanos = Long.MIN_VALUE
            events += ArbiterEvent.CommandCommitted(commandId = previousId, tNanos = t)
        }

        val commandId = nextCommandId++
        pendingCommandId = commandId
        pendingConfirmedAtNanos = t
        refractoryUntilNanos = t + config.refractoryPeriodNanos
        phase = ArbiterPhase.CONFIRMED
        lastGesture = event.gesture
        rejectReason = null
        events += ArbiterEvent.CommandConfirmed(
            commandId = commandId,
            gesture = event.gesture,
            tNanos = t,
        )
    }

    companion object {
        const val REASON_NO_ACTIVE_COMMAND = "no_active_command"
    }
}
