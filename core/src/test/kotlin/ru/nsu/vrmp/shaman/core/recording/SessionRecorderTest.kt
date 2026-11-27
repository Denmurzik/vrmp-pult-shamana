package ru.nsu.vrmp.shaman.core.recording

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.nsu.vrmp.shaman.core.experiment.ExperimentProtocol
import ru.nsu.vrmp.shaman.core.experiment.ExperimentSession
import ru.nsu.vrmp.shaman.core.game.CommandErrorReason
import ru.nsu.vrmp.shaman.core.game.GameEvent
import ru.nsu.vrmp.shaman.core.game.Spirit
import ru.nsu.vrmp.shaman.core.game.SpiritType
import ru.nsu.vrmp.shaman.core.game.TrapType
import ru.nsu.vrmp.shaman.core.input.HandFrame
import ru.nsu.vrmp.shaman.core.input.ImuFrame
import ru.nsu.vrmp.shaman.core.input.TouchAction
import ru.nsu.vrmp.shaman.core.input.TouchFrame
import ru.nsu.vrmp.shaman.core.math.Quat
import ru.nsu.vrmp.shaman.core.math.Vec3
import ru.nsu.vrmp.shaman.core.recognition.Gesture
import ru.nsu.vrmp.shaman.core.recognition.RecognizerEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipFile

class SessionRecorderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `creates exact folder layout and writes valid escaped meta json`() {
        val meta = SessionMeta(
            sessionId = "20261126_P01_dtw_01",
            kind = SessionKind.EXPERIMENT,
            participantId = "p01",
            condition = "dtw",
            seed = 1101L,
            device = "Google \"Pixel 8\"\nPro",
            appVersion = "0.1.0",
            gitSha = "abc1234",
            recognizerParams = mapOf(
                "band" to "0.15",
                "threshold" to "1.8",
            ),
        )

        SessionRecorder(tempDir, meta).use { recorder ->
            assertEquals(
                tempDir.resolve("data").resolve("experiment").resolve("20261126_P01_dtw_01"),
                recorder.sessionDir,
            )
            assertEquals("data/experiment/20261126_P01_dtw_01", recorder.relativeSessionPath)
        }

        val sessionDir = tempDir.resolve("data/experiment/20261126_P01_dtw_01")
        assertTrue(Files.isDirectory(sessionDir))
        assertTrue(Files.isRegularFile(sessionDir.resolve("meta.json")))
        assertTrue(Files.isRegularFile(sessionDir.resolve("imu.csv")))
        assertTrue(Files.isRegularFile(sessionDir.resolve("hand.csv")))
        assertTrue(Files.isRegularFile(sessionDir.resolve("events.jsonl")))
        assertFalse(Files.exists(sessionDir.resolve("labels.csv")))

        val metaJson = Files.readString(sessionDir.resolve("meta.json"))
        assertTrue(metaJson.contains("\"schema_version\": 1"))
        assertTrue(metaJson.contains("\"session_id\": \"20261126_P01_dtw_01\""))
        assertTrue(metaJson.contains("\"kind\": \"experiment\""))
        assertTrue(metaJson.contains("\"participant_id\": \"P01\""))
        assertTrue(metaJson.contains("\"condition\": \"dtw\""))
        assertTrue(metaJson.contains("\"seed\": 1101"))
        assertTrue(metaJson.contains("\"device\": \"Google \\\"Pixel 8\\\"\\nPro\""))
        assertTrue(metaJson.contains("\"band\": \"0.15\""))
        assertTrue(metaJson.contains("\"threshold\": \"1.8\""))
    }

    @Test
    fun `rejects non-anonymous participant id and unsafe session id`() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionMeta(
                sessionId = "ok_session",
                kind = SessionKind.EXPERIMENT,
                participantId = "Ivan",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionMeta(
                sessionId = "../escape",
                kind = SessionKind.GAME,
            )
        }
    }

    @Test
    fun `writes imu csv with exact header and Locale US decimal dots`() {
        val originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ru-RU"))
        try {
            val meta = SessionMeta(sessionId = "imu_01", kind = SessionKind.GAME)
            SessionRecorder(tempDir, meta).use { recorder ->
                recorder.recordFrame(
                    ImuFrame(
                        tNanos = 1_500_000_000L,
                        linAcc = Vec3(1.25, -2.5, 9.8125),
                        gyro = Vec3(0.1, -0.2, 3.14159),
                        rotation = Quat(0.707107, 0.0, 0.707107, 0.0),
                    ),
                )
            }

            val lines = Files.readAllLines(tempDir.resolve("data/game/imu_01/imu.csv"))
            assertEquals(2, lines.size)
            assertEquals("t_ns,ax,ay,az,gx,gy,gz,qw,qx,qy,qz", lines[0])
            val cols = lines[1].split(",")
            assertEquals(11, cols.size)
            assertEquals("1500000000", cols[0])
            assertEquals(1.25, cols[1].toDouble(), 1e-6)
            assertEquals(-2.5, cols[2].toDouble(), 1e-6)
            assertEquals(9.8125, cols[3].toDouble(), 1e-6)
            assertEquals(0.1, cols[4].toDouble(), 1e-6)
            assertEquals(-0.2, cols[5].toDouble(), 1e-6)
            assertEquals(3.14159, cols[6].toDouble(), 1e-5)
            assertEquals(0.707107, cols[7].toDouble(), 1e-6)
            assertEquals(0.0, cols[8].toDouble(), 1e-6)
            assertEquals(0.707107, cols[9].toDouble(), 1e-6)
            assertEquals(0.0, cols[10].toDouble(), 1e-6)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `writes hand csv with exact 65 columns for present true and false`() {
        val meta = SessionMeta(sessionId = "hand_01", kind = SessionKind.GAME)
        val landmarks = List(HandFrame.LANDMARK_COUNT) { idx ->
            Vec3(idx * 0.01, idx * 0.02, -idx * 0.005)
        }

        SessionRecorder(tempDir, meta).use { recorder ->
            recorder.recordFrame(HandFrame(tNanos = 100L, present = true, landmarks = landmarks))
            recorder.recordFrame(HandFrame.absent(tNanos = 200L))
        }

        val lines = Files.readAllLines(tempDir.resolve("data/game/hand_01/hand.csv"))
        assertEquals(3, lines.size)

        val headerCols = lines[0].split(",")
        assertEquals(65, headerCols.size)
        assertEquals("t_ns", headerCols[0])
        assertEquals("present", headerCols[1])
        assertEquals("x0", headerCols[2])
        assertEquals("y0", headerCols[3])
        assertEquals("z0", headerCols[4])
        assertEquals("x20", headerCols[62])
        assertEquals("y20", headerCols[63])
        assertEquals("z20", headerCols[64])

        val presentCols = lines[1].split(",")
        assertEquals(65, presentCols.size)
        assertEquals("100", presentCols[0])
        assertEquals("true", presentCols[1])
        assertEquals(0.0, presentCols[2].toDouble(), 1e-6)
        assertEquals(0.20, presentCols[62].toDouble(), 1e-6)
        assertEquals(0.40, presentCols[63].toDouble(), 1e-6)
        assertEquals(-0.10, presentCols[64].toDouble(), 1e-6)

        val absentCols = lines[2].split(",")
        assertEquals(65, absentCols.size)
        assertEquals("200", absentCols[0])
        assertEquals("false", absentCols[1])
        assertTrue(absentCols.drop(2).all { it.isEmpty() })
    }

    @Test
    fun `writes labels csv for dataset session with exact header and entries`() {
        val meta = SessionMeta(
            sessionId = "ds_P02_01",
            kind = SessionKind.DATASET,
            participantId = "P02",
        )

        SessionRecorder(tempDir, meta).use { recorder ->
            recorder.recordLabel(LabelEntry(1000L, 1800L, "catch", "normal"))
            recorder.recordLabel(LabelEntry(3000L, 4200L, "banish", "fast"))
            recorder.recordLabel(LabelEntry(5000L, 9000L, "null", "walk"))
        }

        val lines = Files.readAllLines(tempDir.resolve("data/dataset/ds_P02_01/labels.csv"))
        assertEquals(
            listOf(
                "t_start_ns,t_end_ns,gesture,speed",
                "1000,1800,catch,normal",
                "3000,4200,banish,fast",
                "5000,9000,null,walk",
            ),
            lines,
        )
    }

    @Test
    fun `writes events jsonl for recognizer, game, touch, and experiment log sink`() {
        val meta = SessionMeta(
            sessionId = "exp_P01_01",
            kind = SessionKind.EXPERIMENT,
            participantId = "P01",
            condition = "touch",
            seed = 1101L,
        )

        SessionRecorder(tempDir, meta).use { recorder ->
            recorder.recordFrame(TouchFrame(tNanos = 50L, action = TouchAction.Press(Gesture.CATCH)))
            recorder.recordRecognizerEvent(
                RecognizerEvent.Candidate(Gesture.CATCH, tNanos = 100L),
                recognizerId = "threshold",
            )
            recorder.recordRecognizerEvent(
                RecognizerEvent.Detected(
                    gesture = Gesture.CATCH,
                    confidence = 0.92,
                    tStart = 100L,
                    tEnd = 400L,
                    tEmit = 420L,
                ),
                recognizerId = "threshold",
            )
            recorder.recordRecognizerEvent(
                RecognizerEvent.Rejected(tNanos = 500L, reason = "too_short"),
                recognizerId = "threshold",
            )
            recorder.recordGameEvent(
                GameEvent.SpiritSpawned(
                    tNanos = 600L,
                    spirit = Spirit(
                        id = 1,
                        type = SpiritType.WANDERING,
                        azimuthRad = 0.25,
                        distance = 0.8,
                        trapType = TrapType.AMBER,
                        spawnedAtNanos = 600L,
                        expiresAtNanos = 20_600L,
                    ),
                ),
            )
            recorder.recordGameEvent(
                GameEvent.CommandRejected(
                    tNanos = 700L,
                    commandId = 12L,
                    reason = CommandErrorReason.WRONG_GESTURE,
                ),
            )

            val experimentSession = ExperimentSession("P01", recorder.asExperimentLogSink())
            experimentSession.startCurrentBlock(tNanos = 1000L)
            experimentSession.recordGameEvents(
                listOf(GameEvent.RoundStarted(tNanos = 1000L, seed = 1101L, experimentMode = true)),
            )
            experimentSession.completeCurrentBlock(
                score = 7,
                errors = 1,
                misses = 0,
                cancelled = 1,
                tNanos = ExperimentProtocol.TASK_ROUND_DURATION_NANOS,
            )
        }

        val lines = Files.readAllLines(tempDir.resolve("data/experiment/exp_P01_01/events.jsonl"))
        assertEquals(9, lines.size)
        lines.forEach { line ->
            assertTrue(line.startsWith("{") && line.endsWith("}"), "Not a JSON object: $line")
            assertTrue(line.contains("\"t_ns\":"), "Missing t_ns: $line")
            assertTrue(line.contains("\"source\":"), "Missing source: $line")
            assertTrue(line.contains("\"type\":"), "Missing type: $line")
            assertFalse(line.contains("\n"), "Unescaped newline in JSONL")
        }

        assertTrue(lines[0].contains("\"source\":\"touch\"") && lines[0].contains("\"type\":\"press\""))
        assertTrue(lines[1].contains("\"source\":\"recognizer\"") && lines[1].contains("\"type\":\"candidate\""))
        assertTrue(lines[2].contains("\"type\":\"detected\"") && lines[2].contains("\"confidence\":0.92"))
        assertTrue(lines[3].contains("\"type\":\"rejected\"") && lines[3].contains("\"reason\":\"too_short\""))
        assertTrue(lines[4].contains("\"source\":\"game\"") && lines[4].contains("\"type\":\"spirit_spawned\""))
        assertTrue(lines[5].contains("\"type\":\"command_rejected\"") && lines[5].contains("\"WRONG_GESTURE\""))
        assertTrue(lines[6].contains("\"source\":\"experiment\"") && lines[6].contains("\"type\":\"block_started\""))
        assertTrue(lines[7].contains("\"source\":\"experiment\"") && lines[7].contains("\"type\":\"game_event\""))
        assertTrue(lines[8].contains("\"source\":\"experiment\"") && lines[8].contains("\"type\":\"block_ended\""))
    }

    @Test
    fun `session archive creates valid zip preserving data kind sessionId paths`() {
        val meta = SessionMeta(
            sessionId = "zip_test_01",
            kind = SessionKind.DATASET,
            participantId = "P03",
        )
        val sessionDir: Path
        SessionRecorder(tempDir, meta).use { recorder ->
            sessionDir = recorder.sessionDir
            recorder.recordFrame(ImuFrame(10L, Vec3.ZERO, Vec3.ZERO, Quat.IDENTITY))
            recorder.recordLabel(LabelEntry(10L, 20L, "catch", "normal"))
            recorder.recordEvent(SessionEventRecord(15L, "marker", "note", mapOf("k" to "v")))
        }

        val zipPath = tempDir.resolve("export/zip_test_01.zip")
        SessionArchive.zipSession(tempDir, sessionDir, zipPath)

        assertTrue(Files.isRegularFile(zipPath))
        ZipFile(zipPath.toFile()).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertEquals(
                setOf(
                    "data/dataset/zip_test_01/events.jsonl",
                    "data/dataset/zip_test_01/hand.csv",
                    "data/dataset/zip_test_01/imu.csv",
                    "data/dataset/zip_test_01/labels.csv",
                    "data/dataset/zip_test_01/meta.json",
                ),
                names,
            )
            val metaEntry = zip.getEntry("data/dataset/zip_test_01/meta.json")
            val content = zip.getInputStream(metaEntry).bufferedReader().readText()
            assertTrue(content.contains("\"session_id\": \"zip_test_01\""))
        }
    }

    @Test
    fun `close is idempotent and flush persists partial session`() {
        val meta = SessionMeta(sessionId = "partial_01", kind = SessionKind.GAME)
        val recorder = SessionRecorder(tempDir, meta)
        recorder.recordFrame(ImuFrame(42L, Vec3.UNIT_X, Vec3.UNIT_Y, Quat.IDENTITY))
        recorder.flush()

        val imuLinesBeforeClose = Files.readAllLines(recorder.sessionDir.resolve("imu.csv"))
        assertEquals(2, imuLinesBeforeClose.size)

        recorder.close()
        recorder.close() // must not throw
    }
}
