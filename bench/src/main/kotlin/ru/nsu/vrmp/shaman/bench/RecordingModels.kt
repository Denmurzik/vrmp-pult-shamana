package ru.nsu.vrmp.shaman.bench

import ru.nsu.vrmp.shaman.core.input.InputFrame
import ru.nsu.vrmp.shaman.core.recognition.Gesture

/** Metadata parsed from `meta.json` inside a recording session directory. */
data class SessionMeta(
    val sessionId: String,
    val participantId: String,
    val condition: String = "",
    val seed: Long? = null,
    val extras: Map<String, String> = emptyMap(),
)

/** Execution speed of a labeled gesture interval from `labels.csv`. */
enum class GestureSpeed(val wireName: String) {
    SLOW("slow"),
    NORMAL("normal"),
    FAST("fast"),
    UNKNOWN("unknown");

    companion object {
        fun parse(raw: String?): GestureSpeed = when (raw?.trim()?.lowercase()) {
            "slow" -> SLOW
            "normal" -> NORMAL
            "fast" -> FAST
            else -> UNKNOWN
        }
    }
}

/** One ground-truth gesture interval from `labels.csv`. */
data class LabelInterval(
    val tStartNs: Long,
    val tEndNs: Long,
    val gesture: Gesture,
    val speed: GestureSpeed = GestureSpeed.UNKNOWN,
) {
    init {
        require(tStartNs <= tEndNs) { "tStartNs ($tStartNs) > tEndNs ($tEndNs)" }
    }
}

/** Complete recording session loaded from `data/<kind>/<session_id>/`. */
data class RecordingSession(
    val sessionId: String,
    val meta: SessionMeta,
    val frames: List<InputFrame>,
    val labels: List<LabelInterval>,
) {
    /** True when the recording contains no positive gesture labels (null stream). */
    val isNullStream: Boolean get() = labels.isEmpty()

    /** Total span of frames in nanoseconds (`0` if fewer than 2 frames). */
    val durationNanos: Long
        get() = if (frames.size >= 2) (frames.last().tNanos - frames.first().tNanos).coerceAtLeast(0L) else 0L
}
