package ru.nsu.vrmp.shaman.core.recognition

import ru.nsu.vrmp.shaman.core.input.InputFrame

/**
 * Common interface of all input methods (threshold, DTW, camera, touch).
 * The game and the command arbiter never know which implementation is active.
 *
 * Contract:
 * - Deterministic: the same frame sequence after [reset] yields the same events.
 * - Timing comes only from [InputFrame.tNanos]; no wall clock, no unseeded randomness.
 * - Frames arrive in non-decreasing [InputFrame.tNanos] order. Frames of types the
 *   recognizer does not use must be ignored (return an empty list).
 */
interface GestureRecognizer {
    /** Stable identifier used in logs and benchmark output: "threshold", "dtw", "camera", "touch". */
    val id: String

    /** Drops all internal state (between rounds, sessions, or benchmark recordings). */
    fun reset()

    /** Consumes one frame and returns the events it produced (usually empty). */
    fun onFrame(frame: InputFrame): List<RecognizerEvent>
}
