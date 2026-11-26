package ru.nsu.vrmp.shaman.core.recording

/** Top-level category of a recorded session under `data/<kind>/<session_id>/`. */
enum class SessionKind(val dirName: String) {
    GAME("game"),
    EXPERIMENT("experiment"),
    DATASET("dataset"),
}

/**
 * Metadata stored in `meta.json` for a recorded session.
 * Never store real participant names — only anonymous `P01..P99` IDs or `null`.
 */
data class SessionMeta(
    val sessionId: String,
    val kind: SessionKind,
    val participantId: String? = null,
    val condition: String? = null,
    val seed: Long? = null,
    val device: String = "unknown",
    val appVersion: String = "0.1.0",
    val gitSha: String = "unknown",
    val recognizerParams: Map<String, String> = emptyMap(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    val normalizedParticipantId: String? = participantId?.let(::validateParticipantId)

    init {
        require(schemaVersion >= 1) { "schemaVersion must be >= 1, got $schemaVersion" }
        validateSessionId(sessionId)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1

        private val PARTICIPANT_PATTERN = Regex("^P(0[1-9]|[1-9][0-9])$")
        private val SESSION_ID_PATTERN = Regex("^[A-Za-z0-9._-]+$")

        fun validateParticipantId(raw: String): String {
            val normalized = raw.trim().uppercase()
            require(PARTICIPANT_PATTERN.matches(normalized)) {
                "Participant ID must follow P01..P99 format, got '$raw'"
            }
            return normalized
        }

        fun validateSessionId(raw: String): String {
            require(raw.isNotBlank()) { "sessionId must not be blank" }
            require(SESSION_ID_PATTERN.matches(raw) && raw != "." && raw != ".." && !raw.contains("..")) {
                "sessionId contains unsafe path characters: '$raw'"
            }
            return raw
        }
    }
}

/**
 * Ground-truth interval label for dataset sessions (`labels.csv`).
 * [gesture] is typically `"catch"`, `"banish"`, `"cancel"`, or `"null"`.
 * [speed] is typically `"slow"`, `"normal"`, `"fast"`, or a null-activity name (e.g. `"walk"`).
 */
data class LabelEntry(
    val tStartNanos: Long,
    val tEndNanos: Long,
    val gesture: String,
    val speed: String,
) {
    init {
        require(tStartNanos <= tEndNanos) { "tStartNanos ($tStartNanos) > tEndNanos ($tEndNanos)" }
        require(gesture.isNotBlank()) { "gesture must not be blank" }
        require(speed.isNotBlank()) { "speed must not be blank" }
    }
}

/** One JSONL line in `events.jsonl`. */
data class SessionEventRecord(
    val tNanos: Long,
    val source: String,
    val type: String,
    val payload: Map<String, Any?> = emptyMap(),
) {
    init {
        require(source.isNotBlank()) { "source must not be blank" }
        require(type.isNotBlank()) { "type must not be blank" }
    }
}
