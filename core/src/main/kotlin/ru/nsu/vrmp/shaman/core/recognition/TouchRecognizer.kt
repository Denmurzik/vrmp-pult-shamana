package ru.nsu.vrmp.shaman.core.recognition

import ru.nsu.vrmp.shaman.core.input.InputFrame
import ru.nsu.vrmp.shaman.core.input.TouchAction
import ru.nsu.vrmp.shaman.core.input.TouchFrame

/**
 * Baseline control: a button press is an instant, certain gesture.
 * Lets touch input share the arbiter and logging with the other recognizers.
 * Scan swipes are not gestures and are ignored here.
 */
class TouchRecognizer : GestureRecognizer {
    override val id: String = "touch"

    override fun reset() = Unit

    override fun onFrame(frame: InputFrame): List<RecognizerEvent> {
        if (frame !is TouchFrame) return emptyList()
        val action = frame.action as? TouchAction.Press ?: return emptyList()
        val t = frame.tNanos
        return listOf(RecognizerEvent.Detected(action.gesture, confidence = 1.0, tStart = t, tEnd = t, tEmit = t))
    }
}
