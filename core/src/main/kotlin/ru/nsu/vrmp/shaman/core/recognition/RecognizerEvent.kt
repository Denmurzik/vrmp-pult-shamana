package ru.nsu.vrmp.shaman.core.recognition

/** Output of a [GestureRecognizer]. Timestamps use the input-frame clock (nanoseconds). */
sealed interface RecognizerEvent {
    val tNanos: Long

    /** Movement started to look like [gesture]; used for early "candidate" feedback. */
    data class Candidate(
        val gesture: Gesture,
        override val tNanos: Long,
    ) : RecognizerEvent

    /**
     * A gesture was recognized. [tStart]..[tEnd] is the gesture extent in the input stream,
     * [tEmit] is the timestamp of the frame on which the decision was made
     * (recognition latency = `tEmit - tEnd`).
     */
    data class Detected(
        val gesture: Gesture,
        val confidence: Double,
        val tStart: Long,
        val tEnd: Long,
        val tEmit: Long,
    ) : RecognizerEvent {
        override val tNanos: Long get() = tEmit

        init {
            require(confidence in 0.0..1.0) { "confidence must be in [0, 1], got $confidence" }
            require(tStart <= tEnd) { "tStart ($tStart) > tEnd ($tEnd)" }
            require(tEnd <= tEmit) { "tEnd ($tEnd) > tEmit ($tEmit)" }
        }
    }

    /** A candidate was dropped (too short, too long, ambiguous, hand lost, ...). */
    data class Rejected(
        override val tNanos: Long,
        val reason: String,
    ) : RecognizerEvent
}
