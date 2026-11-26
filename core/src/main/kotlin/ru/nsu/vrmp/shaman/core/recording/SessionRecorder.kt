package ru.nsu.vrmp.shaman.core.recording

import ru.nsu.vrmp.shaman.core.experiment.ExperimentLogEntry
import ru.nsu.vrmp.shaman.core.experiment.ExperimentLogSink
import ru.nsu.vrmp.shaman.core.game.GameEvent
import ru.nsu.vrmp.shaman.core.input.HandFrame
import ru.nsu.vrmp.shaman.core.input.ImuFrame
import ru.nsu.vrmp.shaman.core.input.InputFrame
import ru.nsu.vrmp.shaman.core.input.TouchFrame
import ru.nsu.vrmp.shaman.core.recognition.RecognizerEvent
import java.io.BufferedWriter
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Pure-JVM buffered recorder that writes a session in the repository format:
 * `<rootDir>/data/<kind>/<sessionId>/` containing `meta.json`, `imu.csv`, `hand.csv`,
 * `events.jsonl`, and (for dataset sessions or when labels are recorded) `labels.csv`.
 */
class SessionRecorder(
    rootDir: Path,
    val meta: SessionMeta,
    writeLabelsFile: Boolean = (meta.kind == SessionKind.DATASET),
) : AutoCloseable {

    constructor(
        rootDir: File,
        meta: SessionMeta,
        writeLabelsFile: Boolean = (meta.kind == SessionKind.DATASET),
    ) : this(rootDir.toPath(), meta, writeLabelsFile)

    val relativeSessionPath: String = "data/${meta.kind.dirName}/${meta.sessionId}"
    val sessionDir: Path = rootDir.resolve("data").resolve(meta.kind.dirName).resolve(meta.sessionId)

    private val lock = Any()
    private val imuWriter: BufferedWriter
    private val handWriter: BufferedWriter
    private val eventsWriter: BufferedWriter
    private var labelsWriter: BufferedWriter? = null
    private var closed = false

    init {
        Files.createDirectories(sessionDir)
        Files.writeString(
            sessionDir.resolve(META_FILE),
            SessionJson.serializeMeta(meta),
            StandardCharsets.UTF_8,
        )

        imuWriter = Files.newBufferedWriter(sessionDir.resolve(IMU_FILE), StandardCharsets.UTF_8)
        imuWriter.write(IMU_HEADER)
        imuWriter.newLine()

        handWriter = Files.newBufferedWriter(sessionDir.resolve(HAND_FILE), StandardCharsets.UTF_8)
        handWriter.write(HAND_HEADER)
        handWriter.newLine()

        eventsWriter = Files.newBufferedWriter(sessionDir.resolve(EVENTS_FILE), StandardCharsets.UTF_8)

        if (writeLabelsFile) {
            ensureLabelsWriter()
        }
    }

    fun recordFrame(frame: InputFrame) {
        when (frame) {
            is ImuFrame -> recordImu(frame)
            is HandFrame -> recordHand(frame)
            is TouchFrame -> recordTouch(frame)
        }
    }

    fun recordImu(frame: ImuFrame) {
        synchronized(lock) {
            checkOpen()
            imuWriter.write(
                String.format(
                    Locale.US,
                    "%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f",
                    frame.tNanos,
                    frame.linAcc.x,
                    frame.linAcc.y,
                    frame.linAcc.z,
                    frame.gyro.x,
                    frame.gyro.y,
                    frame.gyro.z,
                    frame.rotation.w,
                    frame.rotation.x,
                    frame.rotation.y,
                    frame.rotation.z,
                ),
            )
            imuWriter.newLine()
        }
    }

    fun recordHand(frame: HandFrame) {
        synchronized(lock) {
            checkOpen()
            val sb = StringBuilder(512)
            sb.append(frame.tNanos)
            sb.append(',')
            sb.append(if (frame.present) "true" else "false")
            if (frame.present) {
                for (lm in frame.landmarks) {
                    sb.append(',')
                    sb.append(String.format(Locale.US, "%.6f", lm.x))
                    sb.append(',')
                    sb.append(String.format(Locale.US, "%.6f", lm.y))
                    sb.append(',')
                    sb.append(String.format(Locale.US, "%.6f", lm.z))
                }
            } else {
                // Keep exact 65 columns (2 + 21 * 3) with empty landmark cells when hand is absent.
                repeat(HandFrame.LANDMARK_COUNT * 3) {
                    sb.append(',')
                }
            }
            handWriter.write(sb.toString())
            handWriter.newLine()
        }
    }

    fun recordTouch(frame: TouchFrame) {
        recordEvent(SessionJson.fromTouchFrame(frame))
    }

    fun recordLabel(entry: LabelEntry) {
        synchronized(lock) {
            checkOpen()
            val writer = ensureLabelsWriter()
            writer.write(
                "${entry.tStartNanos},${entry.tEndNanos},${escapeCsv(entry.gesture)},${escapeCsv(entry.speed)}",
            )
            writer.newLine()
        }
    }

    fun recordRecognizerEvent(event: RecognizerEvent, recognizerId: String? = null) {
        recordEvent(SessionJson.fromRecognizerEvent(event, recognizerId))
    }

    fun recordGameEvent(event: GameEvent) {
        recordEvent(SessionJson.fromGameEvent(event))
    }

    fun recordExperimentEvent(entry: ExperimentLogEntry) {
        recordEvent(SessionJson.fromExperimentLogEntry(entry))
    }

    fun recordEvent(record: SessionEventRecord) {
        synchronized(lock) {
            checkOpen()
            eventsWriter.write(SessionJson.serializeEvent(record))
            eventsWriter.newLine()
        }
    }

    /** Adapts this recorder into an [ExperimentLogSink] for `ExperimentSession` / `ExperimentController`. */
    fun asExperimentLogSink(): ExperimentLogSink = ExperimentLogSink { entry ->
        recordExperimentEvent(entry)
    }

    fun flush() {
        synchronized(lock) {
            if (closed) return
            imuWriter.flush()
            handWriter.flush()
            eventsWriter.flush()
            labelsWriter?.flush()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            var firstError: Throwable? = null
            for (writer in listOfNotNull(imuWriter, handWriter, eventsWriter, labelsWriter)) {
                try {
                    writer.flush()
                    writer.close()
                } catch (t: Throwable) {
                    if (firstError == null) firstError = t else firstError.addSuppressed(t)
                }
            }
            if (firstError != null) throw firstError
        }
    }

    private fun ensureLabelsWriter(): BufferedWriter {
        val existing = labelsWriter
        if (existing != null) return existing
        val writer = Files.newBufferedWriter(sessionDir.resolve(LABELS_FILE), StandardCharsets.UTF_8)
        writer.write(LABELS_HEADER)
        writer.newLine()
        labelsWriter = writer
        return writer
    }

    private fun checkOpen() {
        check(!closed) { "SessionRecorder for '${meta.sessionId}' is already closed" }
    }

    private fun escapeCsv(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (!needsQuotes) value else "\"${value.replace("\"", "\"\"")}\""
    }

    companion object {
        const val META_FILE = "meta.json"
        const val IMU_FILE = "imu.csv"
        const val HAND_FILE = "hand.csv"
        const val LABELS_FILE = "labels.csv"
        const val EVENTS_FILE = "events.jsonl"

        const val IMU_HEADER = "t_ns,ax,ay,az,gx,gy,gz,qw,qx,qy,qz"
        const val LABELS_HEADER = "t_start_ns,t_end_ns,gesture,speed"

        val HAND_HEADER: String = buildString {
            append("t_ns,present")
            for (i in 0 until HandFrame.LANDMARK_COUNT) {
                append(",x").append(i).append(",y").append(i).append(",z").append(i)
            }
        }
    }
}
