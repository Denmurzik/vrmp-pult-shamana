package ru.nsu.vrmp.shaman.game

import ru.nsu.vrmp.shaman.core.arbiter.ArbiterConfig
import ru.nsu.vrmp.shaman.core.arbiter.ArbiterEvent
import ru.nsu.vrmp.shaman.core.arbiter.ArbiterState
import ru.nsu.vrmp.shaman.core.arbiter.CommandArbiter
import ru.nsu.vrmp.shaman.core.recognition.Gesture
import ru.nsu.vrmp.shaman.core.recognition.RecognizerEvent

typealias ArbiterPhase = ru.nsu.vrmp.shaman.core.arbiter.ArbiterPhase
typealias ArbiterSnapshot = ArbiterState

sealed interface ArbiterAction {
    data class Apply(val commandId: Long, val gesture: Gesture, val tNanos: Long) : ArbiterAction
    data class Cancel(val commandId: Long, val tNanos: Long) : ArbiterAction
}

/**
 * UI-layer adapter delegating to `:core`'s [CommandArbiter].
 */
class TouchCommandArbiter(
    private val delegate: CommandArbiter = CommandArbiter(),
) {
    fun reset() {
        delegate.reset()
    }

    fun advance(tNanos: Long): ArbiterSnapshot = delegate.advance(tNanos).state

    fun onEvent(event: RecognizerEvent): Pair<ArbiterSnapshot, ArbiterAction?> {
        val result = delegate.onEvent(event)
        val action = result.events.firstNotNullOfOrNull { arbiterEvent ->
            when (arbiterEvent) {
                is ArbiterEvent.CommandConfirmed ->
                    ArbiterAction.Apply(arbiterEvent.commandId, arbiterEvent.gesture, arbiterEvent.tNanos)

                is ArbiterEvent.CommandCancelled ->
                    ArbiterAction.Cancel(arbiterEvent.commandId, arbiterEvent.tNanos)

                else -> null
            }
        }
        return result.state to action
    }

    fun snapshot(tNanos: Long): ArbiterSnapshot = delegate.snapshot(tNanos)

    companion object {
        const val UNDO_WINDOW_NANOS = ArbiterConfig.DEFAULT_UNDO_WINDOW_NANOS
        const val REFRACTORY_NANOS = ArbiterConfig.DEFAULT_REFRACTORY_PERIOD_NANOS
    }
}
