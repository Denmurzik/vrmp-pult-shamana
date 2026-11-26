package ru.nsu.vrmp.shaman.bench

import ru.nsu.vrmp.shaman.core.input.HandFrame
import ru.nsu.vrmp.shaman.core.input.ImuFrame
import ru.nsu.vrmp.shaman.core.input.InputFrame
import ru.nsu.vrmp.shaman.core.math.Quat
import ru.nsu.vrmp.shaman.core.math.Vec3
import ru.nsu.vrmp.shaman.core.recognition.Gesture
import java.nio.file.Files
import java.nio.file.Path

/** Loads recording sessions from `data/<kind>/<session_id>/` directories. */
object RecordingLoader {

    private val SESSION_MARKERS = setOf("meta.json", "imu.csv", "hand.csv")

    fun loadSession(sessionDir: Path): RecordingSession {
        require(Files.isDirectory(sessionDir)) { "Session path is not a directory: $sessionDir" }
        val defaultId = sessionDir.fileName?.toString() ?: "session"

        val metaPath = sessionDir.resolve("meta.json")
        val meta = if (Files.isRegularFile(metaPath)) {
            parseMetaJson(Files.readString(metaPath), defaultId)
        } else {
            SessionMeta(sessionId = defaultId, participantId = "unknown")
        }

        val frames = mutableListOf<InputFrame>()
        val imuPath = sessionDir.resolve("imu.csv")
        if (Files.isRegularFile(imuPath)) {
            frames += parseImuCsv(imuPath)
        }
        val handPath = sessionDir.resolve("hand.csv")
        if (Files.isRegularFile(handPath)) {
            frames += parseHandCsv(handPath)
        }
        frames.sortBy { it.tNanos }

        val labelsPath = sessionDir.resolve("labels.csv")
        val labels = if (Files.isRegularFile(labelsPath)) {
            parseLabelsCsv(labelsPath)
        } else {
            emptyList()
        }

        return RecordingSession(
            sessionId = meta.sessionId,
            meta = meta,
            frames = frames,
            labels = labels,
        )
    }

    fun loadDataset(inputDir: Path): List<RecordingSession> {
        require(Files.exists(inputDir)) { "Input directory does not exist: $inputDir" }
        if (hasSessionFiles(inputDir)) {
            return listOf(loadSession(inputDir))
        }
        val sessionDirs = Files.walk(inputDir).use { stream ->
            stream
                .filter { Files.isDirectory(it) && it != inputDir && hasSessionFiles(it) }
                .sorted(Comparator.comparing { inputDir.relativize(it).toString().replace('\\', '/') })
                .toList()
        }
        return sessionDirs.map { loadSession(it) }
    }

    private fun hasSessionFiles(dir: Path): Boolean =
        Files.isDirectory(dir) && SESSION_MARKERS.any { Files.isRegularFile(dir.resolve(it)) }

    internal fun parseImuCsv(path: Path): List<ImuFrame> {
        val result = mutableListOf<ImuFrame>()
        Files.newBufferedReader(path).useLines { lines ->
            var first = true
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (first) {
                    first = false
                    if (line.startsWith("t_ns", ignoreCase = true)) continue
                }
                val cols = line.split(',')
                require(cols.size >= 11) { "Invalid imu.csv row (expected 11 columns): $line" }
                result += ImuFrame(
                    tNanos = cols[0].trim().toLong(),
                    linAcc = Vec3(cols[1].trim().toDouble(), cols[2].trim().toDouble(), cols[3].trim().toDouble()),
                    gyro = Vec3(cols[4].trim().toDouble(), cols[5].trim().toDouble(), cols[6].trim().toDouble()),
                    rotation = Quat(
                        w = cols[7].trim().toDouble(),
                        x = cols[8].trim().toDouble(),
                        y = cols[9].trim().toDouble(),
                        z = cols[10].trim().toDouble(),
                    ),
                )
            }
        }
        return result
    }

    internal fun parseHandCsv(path: Path): List<HandFrame> {
        val result = mutableListOf<HandFrame>()
        Files.newBufferedReader(path).useLines { lines ->
            var first = true
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (first) {
                    first = false
                    if (line.startsWith("t_ns", ignoreCase = true)) continue
                }
                val cols = line.split(',')
                require(cols.size >= 2) { "Invalid hand.csv row (expected at least 2 columns): $line" }
                val tNanos = cols[0].trim().toLong()
                val presentRaw = cols[1].trim()
                val present = presentRaw.equals("true", ignoreCase = true) || presentRaw == "1"
                if (!present) {
                    result += HandFrame.absent(tNanos)
                } else {
                    val expectedCols = 2 + HandFrame.LANDMARK_COUNT * 3
                    require(cols.size >= expectedCols) {
                        "Invalid hand.csv row with present=true (expected $expectedCols columns, got ${cols.size})"
                    }
                    val landmarks = List(HandFrame.LANDMARK_COUNT) { idx ->
                        val base = 2 + idx * 3
                        Vec3(
                            cols[base].trim().toDouble(),
                            cols[base + 1].trim().toDouble(),
                            cols[base + 2].trim().toDouble(),
                        )
                    }
                    result += HandFrame(tNanos = tNanos, present = true, landmarks = landmarks)
                }
            }
        }
        return result
    }

    internal fun parseLabelsCsv(path: Path): List<LabelInterval> {
        val result = mutableListOf<LabelInterval>()
        Files.newBufferedReader(path).useLines { lines ->
            var first = true
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (first) {
                    first = false
                    if (line.startsWith("t_start_ns", ignoreCase = true)) continue
                }
                val cols = line.split(',')
                require(cols.size >= 3) { "Invalid labels.csv row (expected >= 3 columns): $line" }
                val gestureRaw = cols[2].trim()
                if (gestureRaw.isEmpty() || gestureRaw.equals("null", ignoreCase = true)) {
                    continue
                }
                val gesture = Gesture.valueOf(gestureRaw.uppercase())
                val speed = GestureSpeed.parse(cols.getOrNull(3))
                result += LabelInterval(
                    tStartNs = cols[0].trim().toLong(),
                    tEndNs = cols[1].trim().toLong(),
                    gesture = gesture,
                    speed = speed,
                )
            }
        }
        result.sortBy { it.tStartNs }
        return result
    }

    internal fun parseMetaJson(json: String, defaultSessionId: String): SessionMeta {
        val map = FlatJsonObjectParser(json).parseObject()
        val sessionId = map["session_id"]?.takeIf { it.isNotBlank() } ?: defaultSessionId
        val participantId = map["participant_id"]?.takeIf { it.isNotBlank() } ?: "unknown"
        val condition = map["condition"].orEmpty()
        val seed = map["seed"]?.toLongOrNull()
        val reserved = setOf("session_id", "participant_id", "condition", "seed")
        val extras = map.filterKeys { it !in reserved }
        return SessionMeta(
            sessionId = sessionId,
            participantId = participantId,
            condition = condition,
            seed = seed,
            extras = extras,
        )
    }

    /** Zero-dependency parser for top-level key/value pairs of a JSON object. */
    private class FlatJsonObjectParser(private val src: String) {
        private var pos = 0

        fun parseObject(): Map<String, String> {
            val out = linkedMapOf<String, String>()
            skipWs()
            if (pos >= src.length || src[pos] != '{') return out
            pos++ // '{'
            while (pos < src.length) {
                skipWs()
                if (pos >= src.length || src[pos] == '}') {
                    if (pos < src.length) pos++
                    break
                }
                val key = parseString()
                skipWs()
                if (pos < src.length && src[pos] == ':') pos++
                skipWs()
                val value = parseValueAsString()
                if (value != null) {
                    out[key] = value
                }
                skipWs()
                if (pos < src.length && src[pos] == ',') pos++
            }
            return out
        }

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun parseString(): String {
            if (pos >= src.length || src[pos] != '"') return ""
            pos++ // opening '"'
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                if (c == '"') break
                if (c == '\\' && pos < src.length) {
                    when (val esc = src[pos++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (pos + 4 <= src.length) {
                                val hex = src.substring(pos, pos + 4)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun parseValueAsString(): String? {
            if (pos >= src.length) return null
            return when (src[pos]) {
                '"' -> parseString()
                '{', '[' -> parseNestedRaw()
                else -> {
                    val start = pos
                    while (pos < src.length && src[pos] != ',' && src[pos] != '}' && !src[pos].isWhitespace()) {
                        pos++
                    }
                    val token = src.substring(start, pos)
                    if (token == "null") null else token
                }
            }
        }

        private fun parseNestedRaw(): String {
            val open = src[pos]
            val close = if (open == '{') '}' else ']'
            val start = pos
            var depth = 0
            var inString = false
            while (pos < src.length) {
                val c = src[pos++]
                if (inString) {
                    if (c == '\\' && pos < src.length) pos++
                    else if (c == '"') inString = false
                } else {
                    when (c) {
                        '"' -> inString = true
                        open -> depth++
                        close -> {
                            depth--
                            if (depth == 0) break
                        }
                    }
                }
            }
            return src.substring(start, pos)
        }
    }
}
