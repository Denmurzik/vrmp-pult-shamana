package ru.nsu.vrmp.shaman.core.recording

import ru.nsu.vrmp.shaman.core.experiment.ExperimentLogEntry
import ru.nsu.vrmp.shaman.core.game.GameEvent
import ru.nsu.vrmp.shaman.core.input.TouchAction
import ru.nsu.vrmp.shaman.core.input.TouchFrame
import ru.nsu.vrmp.shaman.core.recognition.RecognizerEvent
import java.util.Locale

/** Deterministic, zero-dependency JSON serializer for `meta.json` and `events.jsonl`. */
object SessionJson {

    fun escapeString(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append(String.format(Locale.US, "\\u%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    fun toJsonValue(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> if (value) "true" else "false"
        is Byte, is Short, is Int, is Long -> value.toString()
        is Double -> formatDouble(value)
        is Float -> formatDouble(value.toDouble())
        is String -> "\"${escapeString(value)}\""
        is Enum<*> -> "\"${escapeString(value.name)}\""
        is Map<*, *> -> value.entries
            .joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
                "\"${escapeString(k.toString())}\":${toJsonValue(v)}"
            }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { toJsonValue(it) }
        else -> "\"${escapeString(value.toString())}\""
    }

    fun serializeMeta(meta: SessionMeta): String {
        val sortedParams = meta.recognizerParams.toSortedMap()
        val paramsBlock = if (sortedParams.isEmpty()) {
            "{}"
        } else {
            sortedParams.entries.joinToString(
                prefix = "{\n",
                postfix = "\n  }",
                separator = ",\n",
            ) { (k, v) ->
                "    \"${escapeString(k)}\": \"${escapeString(v)}\""
            }
        }

        return buildString {
            appendLine("{")
            appendLine("  \"schema_version\": ${meta.schemaVersion},")
            appendLine("  \"session_id\": ${toJsonValue(meta.sessionId)},")
            appendLine("  \"kind\": ${toJsonValue(meta.kind.dirName)},")
            appendLine("  \"participant_id\": ${toJsonValue(meta.normalizedParticipantId)},")
            appendLine("  \"condition\": ${toJsonValue(meta.condition)},")
            appendLine("  \"seed\": ${toJsonValue(meta.seed)},")
            appendLine("  \"device\": ${toJsonValue(meta.device)},")
            appendLine("  \"app_version\": ${toJsonValue(meta.appVersion)},")
            appendLine("  \"git_sha\": ${toJsonValue(meta.gitSha)},")
            appendLine("  \"recognizer_params\": $paramsBlock")
            append("}")
        }
    }

    fun serializeEvent(record: SessionEventRecord): String {
        val fields = linkedMapOf<String, Any?>(
            "t_ns" to record.tNanos,
            "source" to record.source,
            "type" to record.type,
            "payload" to record.payload,
        )
        return toJsonValue(fields)
    }

    fun fromTouchFrame(frame: TouchFrame): SessionEventRecord = when (val action = frame.action) {
        is TouchAction.Press -> SessionEventRecord(
            tNanos = frame.tNanos,
            source = "touch",
            type = "press",
            payload = mapOf("gesture" to action.gesture.name.lowercase(Locale.US)),
        )
        is TouchAction.Scan -> SessionEventRecord(
            tNanos = frame.tNanos,
            source = "touch",
            type = "scan",
            payload = mapOf("delta_yaw_rad" to action.deltaYawRad),
        )
    }

    fun fromRecognizerEvent(event: RecognizerEvent, recognizerId: String? = null): SessionEventRecord {
        val base = linkedMapOf<String, Any?>()
        if (recognizerId != null) {
            base["recognizer"] = recognizerId
        }
        return when (event) {
            is RecognizerEvent.Candidate -> {
                base["gesture"] = event.gesture.name.lowercase(Locale.US)
                SessionEventRecord(event.tNanos, "recognizer", "candidate", base)
            }
            is RecognizerEvent.Detected -> {
                base["gesture"] = event.gesture.name.lowercase(Locale.US)
                base["confidence"] = event.confidence
                base["t_start_ns"] = event.tStart
                base["t_end_ns"] = event.tEnd
                base["t_emit_ns"] = event.tEmit
                SessionEventRecord(event.tNanos, "recognizer", "detected", base)
            }
            is RecognizerEvent.Rejected -> {
                base["reason"] = event.reason
                SessionEventRecord(event.tNanos, "recognizer", "rejected", base)
            }
        }
    }

    fun fromGameEvent(event: GameEvent): SessionEventRecord = when (event) {
        is GameEvent.RoundStarted -> SessionEventRecord(
            event.tNanos,
            "game",
            "round_started",
            mapOf("seed" to event.seed, "experiment_mode" to event.experimentMode),
        )
        is GameEvent.SpiritSpawned -> SessionEventRecord(
            event.tNanos,
            "game",
            "spirit_spawned",
            mapOf(
                "spirit_id" to event.spirit.id,
                "spirit_type" to event.spirit.type.name,
                "azimuth_rad" to event.spirit.azimuthRad,
                "distance" to event.spirit.distance,
                "trap_type" to event.spirit.trapType.name,
                "spawned_at_ns" to event.spirit.spawnedAtNanos,
                "expires_at_ns" to event.spirit.expiresAtNanos,
            ),
        )
        is GameEvent.SpiritResolved -> SessionEventRecord(
            event.tNanos,
            "game",
            "spirit_resolved",
            mapOf(
                "spirit_id" to event.spiritId,
                "spirit_type" to event.spiritType.name,
                "gesture" to event.gesture.name.lowercase(Locale.US),
            ),
        )
        is GameEvent.SpiritMissed -> SessionEventRecord(
            event.tNanos,
            "game",
            "spirit_missed",
            mapOf("spirit_id" to event.spiritId, "spirit_type" to event.spiritType.name),
        )
        is GameEvent.CommandRejected -> SessionEventRecord(
            event.tNanos,
            "game",
            "command_rejected",
            mapOf("command_id" to event.commandId, "reason" to event.reason.name),
        )
        is GameEvent.CommandCancelled -> SessionEventRecord(
            event.tNanos,
            "game",
            "command_cancelled",
            mapOf("command_id" to event.commandId, "reverted_success" to event.revertedSuccess),
        )
        is GameEvent.AttackStarted -> SessionEventRecord(
            event.tNanos,
            "game",
            "attack_started",
            mapOf("spirit_id" to event.spiritId, "deadline_ns" to event.deadlineNanos),
        )
        is GameEvent.AttackDodged -> SessionEventRecord(
            event.tNanos,
            "game",
            "attack_dodged",
            mapOf("spirit_id" to event.spiritId),
        )
        is GameEvent.AttackHit -> SessionEventRecord(
            event.tNanos,
            "game",
            "attack_hit",
            mapOf("spirit_id" to event.spiritId, "lives_remaining" to event.livesRemaining),
        )
        is GameEvent.FogChanged -> SessionEventRecord(
            event.tNanos,
            "game",
            "fog_changed",
            mapOf("active" to event.active),
        )
        is GameEvent.TrapChanged -> SessionEventRecord(
            event.tNanos,
            "game",
            "trap_changed",
            mapOf("trap" to event.trap.name),
        )
        is GameEvent.ManaRefilled -> SessionEventRecord(
            event.tNanos,
            "game",
            "mana_refilled",
            mapOf("mana" to event.mana),
        )
        is GameEvent.RoundEnded -> SessionEventRecord(
            event.tNanos,
            "game",
            "round_ended",
            mapOf(
                "score" to event.score,
                "lives" to event.lives,
                "errors" to event.errors,
                "misses" to event.misses,
                "cancelled" to event.cancelledCount,
            ),
        )
    }

    fun fromExperimentLogEntry(entry: ExperimentLogEntry): SessionEventRecord = when (entry) {
        is ExperimentLogEntry.BlockStarted -> SessionEventRecord(
            tNanos = entry.tNanos,
            source = "experiment",
            type = "block_started",
            payload = mapOf(
                "participant_id" to entry.participantId,
                "position" to entry.position,
                "condition" to entry.condition.id,
                "seed" to entry.seed,
            ),
        )
        is ExperimentLogEntry.GameEventRecorded -> {
            val gameRecord = fromGameEvent(entry.event)
            SessionEventRecord(
                tNanos = entry.tNanos,
                source = "experiment",
                type = "game_event",
                payload = mapOf(
                    "participant_id" to entry.participantId,
                    "position" to entry.position,
                    "condition" to entry.condition.id,
                    "event_type" to gameRecord.type,
                    "event_payload" to gameRecord.payload,
                ),
            )
        }
        is ExperimentLogEntry.BlockEnded -> SessionEventRecord(
            tNanos = entry.tNanos,
            source = "experiment",
            type = "block_ended",
            payload = mapOf(
                "participant_id" to entry.participantId,
                "position" to entry.position,
                "condition" to entry.condition.id,
                "seed" to entry.result.block.seed,
                "score" to entry.result.score,
                "errors" to entry.result.errors,
                "misses" to entry.result.misses,
                "cancelled" to entry.result.cancelled,
            ),
        )
    }

    private fun formatDouble(value: Double): String {
        if (!value.isFinite()) return "null"
        val formatted = String.format(Locale.US, "%.6f", value)
            .trimEnd('0')
            .trimEnd('.')
        return if (formatted == "-0") "0" else formatted
    }
}
