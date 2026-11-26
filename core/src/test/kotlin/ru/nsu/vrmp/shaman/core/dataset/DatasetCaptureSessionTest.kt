package ru.nsu.vrmp.shaman.core.dataset

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.nsu.vrmp.shaman.core.recognition.Gesture
import ru.nsu.vrmp.shaman.core.recording.LabelEntry
import ru.nsu.vrmp.shaman.core.recording.SessionKind
import ru.nsu.vrmp.shaman.core.recording.SessionMeta
import ru.nsu.vrmp.shaman.core.recording.SessionRecorder
import java.nio.file.Files
import java.nio.file.Path

class DatasetCaptureSessionTest {

    @Test
    fun validatesAnonymousParticipantIdAndRejectsNames() {
        val session = DatasetCaptureSession(participantId = " p07 ")
        assertEquals("P07", session.currentState().participantId)

        session.setParticipantId("P12")
        assertEquals("P12", session.currentState().participantId)

        assertTrue(DatasetCaptureSession.isValidParticipantId("P01"))
        assertTrue(DatasetCaptureSession.isValidParticipantId("p99"))
        assertFalse(DatasetCaptureSession.isValidParticipantId("Ivan"))
        assertFalse(DatasetCaptureSession.isValidParticipantId("P00"))
        assertFalse(DatasetCaptureSession.isValidParticipantId("P100"))
        assertFalse(DatasetCaptureSession.isValidParticipantId(""))

        assertThrows(IllegalArgumentException::class.java) {
            DatasetCaptureSession(participantId = "Alice")
        }
        assertThrows(IllegalArgumentException::class.java) {
            session.setParticipantId("Dmitry")
        }
    }

    @Test
    fun gestureTrialRunsCountdownThen2500msWindowAndEmitsLabelOnConfirm() {
        val emitted = mutableListOf<LabelEntry>()
        val session = DatasetCaptureSession(
            participantId = "P01",
            onLabelCommitted = emitted::add,
        )
        session.selectGesture(Gesture.BANISH)
        session.selectSpeed(GestureSpeed.FAST)

        assertEquals("Круг · быстро", session.currentState().promptText)

        val t0 = 10_000_000_000L
        val start = session.startTrial(t0)
        assertEquals(DatasetCapturePhase.COUNTDOWN, start.state.phase)
        assertEquals(3, start.state.countdownSecondsRemaining)
        assertTrue(DatasetCue.COUNTDOWN_TICK in start.cues)

        val tick1 = session.onTick(t0 + 1_000_000_000L)
        assertEquals(DatasetCapturePhase.COUNTDOWN, tick1.state.phase)
        assertEquals(2, tick1.state.countdownSecondsRemaining)
        assertTrue(DatasetCue.COUNTDOWN_TICK in tick1.cues)

        val tick2 = session.onTick(t0 + 2_000_000_000L)
        assertEquals(DatasetCapturePhase.COUNTDOWN, tick2.state.phase)
        assertEquals(1, tick2.state.countdownSecondsRemaining)
        assertTrue(DatasetCue.COUNTDOWN_TICK in tick2.cues)

        val tRecStart = t0 + 3_000_000_000L
        val recStart = session.onTick(tRecStart)
        assertEquals(DatasetCapturePhase.RECORDING, recStart.state.phase)
        assertEquals(0f, recStart.state.progress, 1e-4f)
        assertTrue(DatasetCue.RECORDING_STARTED in recStart.cues)

        val mid = session.onTick(tRecStart + 1_250_000_000L)
        assertEquals(DatasetCapturePhase.RECORDING, mid.state.phase)
        assertEquals(0.5f, mid.state.progress, 1e-4f)

        val tRecEnd = tRecStart + 2_500_000_000L
        val finished = session.onTick(tRecEnd)
        assertEquals(DatasetCapturePhase.REVIEW, finished.state.phase)
        assertTrue(DatasetCue.RECORDING_FINISHED in finished.cues)
        assertEquals(
            LabelEntry(tRecStart, tRecEnd, "banish", "fast"),
            finished.state.pendingLabel,
        )
        assertTrue(emitted.isEmpty(), "Label must not be written before confirm")

        val confirmed = session.confirmLastTrial()
        assertEquals(DatasetCapturePhase.IDLE, confirmed.state.phase)
        assertNull(confirmed.state.pendingLabel)
        assertEquals(LabelEntry(tRecStart, tRecEnd, "banish", "fast"), confirmed.emittedLabel)
        assertEquals(listOf(LabelEntry(tRecStart, tRecEnd, "banish", "fast")), emitted)
        assertEquals(1, confirmed.state.committedCount)
    }

    @Test
    fun repeatLastTrialDiscardsPendingLabelAndRestartsSamePrompt() {
        val emitted = mutableListOf<LabelEntry>()
        val session = DatasetCaptureSession(onLabelCommitted = emitted::add)
        session.selectGesture(Gesture.CATCH)
        session.selectSpeed(GestureSpeed.SLOW)

        val t0 = 1_000_000_000L
        session.startTrial(t0)
        session.onTick(t0 + 3_000_000_000L)
        val review = session.onTick(t0 + 5_500_000_000L)
        assertEquals(DatasetCapturePhase.REVIEW, review.state.phase)

        val tRepeat = t0 + 7_000_000_000L
        val repeated = session.repeatLastTrial(tRepeat)
        assertEquals(DatasetCapturePhase.COUNTDOWN, repeated.state.phase)
        assertNull(repeated.state.pendingLabel)
        assertEquals(Gesture.CATCH, repeated.state.selectedGesture)
        assertEquals(GestureSpeed.SLOW, repeated.state.selectedSpeed)
        assertTrue(emitted.isEmpty())
        assertEquals(0, repeated.state.committedCount)
    }

    @Test
    fun cancelDuringCountdownOrRecordingAbortsWithoutWritingLabel() {
        val emitted = mutableListOf<LabelEntry>()
        val session = DatasetCaptureSession(onLabelCommitted = emitted::add)

        session.startTrial(1_000_000_000L)
        val cancelledFromCountdown = session.cancel()
        assertEquals(DatasetCapturePhase.IDLE, cancelledFromCountdown.state.phase)
        assertNull(cancelledFromCountdown.state.pendingLabel)

        session.startTrial(5_000_000_000L)
        session.onTick(8_000_000_000L)
        assertEquals(DatasetCapturePhase.RECORDING, session.currentState().phase)
        val cancelledFromRecording = session.cancel()
        assertEquals(DatasetCapturePhase.IDLE, cancelledFromRecording.state.phase)
        assertNull(cancelledFromRecording.state.pendingLabel)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun nullStreamRecordsTwoMinuteActivityWithNullGestureLabel() {
        val emitted = mutableListOf<LabelEntry>()
        val session = DatasetCaptureSession(onLabelCommitted = emitted::add)
        session.selectMode(DatasetCaptureMode.NULL_STREAM)
        session.selectNullActivity(NullActivity.POCKET)

        assertTrue(session.currentState().promptText.contains("Карман"))

        val t0 = 2_000_000_000L
        session.startTrial(t0)
        val tRecStart = t0 + 3_000_000_000L
        session.onTick(tRecStart)
        assertEquals(DatasetCapturePhase.RECORDING, session.currentState().phase)

        val halfway = session.onTick(tRecStart + 60_000_000_000L)
        assertEquals(DatasetCapturePhase.RECORDING, halfway.state.phase)
        assertEquals(0.5f, halfway.state.progress, 1e-4f)

        val tRecEnd = tRecStart + 120_000_000_000L
        val finished = session.onTick(tRecEnd)
        assertEquals(DatasetCapturePhase.REVIEW, finished.state.phase)
        assertEquals(
            LabelEntry(tRecStart, tRecEnd, "null", "pocket"),
            finished.state.pendingLabel,
        )

        val confirmed = session.confirmLastTrial()
        assertEquals(LabelEntry(tRecStart, tRecEnd, "null", "pocket"), confirmed.emittedLabel)
        assertEquals(listOf(LabelEntry(tRecStart, tRecEnd, "null", "pocket")), emitted)
    }

    @Test
    fun nullStreamStopEarlyEntersReviewWithActualElapsedWindow() {
        val session = DatasetCaptureSession()
        session.selectMode(DatasetCaptureMode.NULL_STREAM)
        session.selectNullActivity(NullActivity.WALK)

        val t0 = 1_000_000_000L
        session.startTrial(t0)
        val tRecStart = t0 + 3_000_000_000L
        session.onTick(tRecStart)

        val tEarlyStop = tRecStart + 45_000_000_000L
        val stopped = session.stopEarly(tEarlyStop)
        assertEquals(DatasetCapturePhase.REVIEW, stopped.state.phase)
        assertEquals(
            LabelEntry(tRecStart, tEarlyStop, "null", "walk"),
            stopped.state.pendingLabel,
        )
    }

    @Test
    fun guidedModeCoversAllNineGestureSpeedCombinations() {
        val session = DatasetCaptureSession()
        session.selectGesture(Gesture.CATCH)
        session.selectSpeed(GestureSpeed.SLOW)

        val seen = mutableSetOf<Pair<String, String>>()
        var t = 1_000_000_000L
        repeat(9) {
            session.startTrial(t)
            t += 3_000_000_000L
            session.onTick(t)
            t += 2_500_000_000L
            session.onTick(t)
            val label = session.confirmLastTrial().emittedLabel!!
            seen += label.gesture to label.speed
            t += 1_000_000_000L
        }

        assertEquals(9, seen.size)
        assertEquals(9, session.currentState().committedCount)
    }

    @Test
    fun stateMachineIsDeterministicForIdenticalTimestampSequences() {
        fun runScenario(): DatasetCaptureState {
            val s = DatasetCaptureSession(participantId = "P05")
            s.selectGesture(Gesture.CANCEL)
            s.selectSpeed(GestureSpeed.FAST)
            s.startTrial(100_000_000L)
            s.onTick(1_100_000_000L)
            s.onTick(3_100_000_000L)
            s.onTick(4_000_000_000L)
            s.onTick(5_600_000_000L)
            s.confirmLastTrial()
            return s.currentState()
        }

        assertEquals(runScenario(), runScenario())
    }

    @Test
    fun integratesWithSessionRecorderWritingLabelsCsv(@TempDir tempDir: Path) {
        val meta = SessionMeta(
            sessionId = "P02_dataset_01",
            kind = SessionKind.DATASET,
            participantId = "P02",
        )
        SessionRecorder(rootDir = tempDir, meta = meta).use { recorder ->
            val session = DatasetCaptureSession(
                participantId = "P02",
                onLabelCommitted = recorder::recordLabel,
            )

            // 1. Gesture trial: banish / normal
            session.selectGesture(Gesture.BANISH)
            session.selectSpeed(GestureSpeed.NORMAL)
            session.startTrial(1_000_000_000L)
            session.onTick(4_000_000_000L)
            session.onTick(6_500_000_000L)
            session.confirmLastTrial()

            // 2. Null-stream trial: typing
            session.selectMode(DatasetCaptureMode.NULL_STREAM)
            session.selectNullActivity(NullActivity.TYPING)
            session.startTrial(10_000_000_000L)
            session.onTick(13_000_000_000L)
            session.onTick(133_000_000_000L)
            session.confirmLastTrial()
        }

        val labelsFile = tempDir.resolve("data/dataset/P02_dataset_01/labels.csv")
        val lines = Files.readAllLines(labelsFile)
        assertEquals(
            listOf(
                "t_start_ns,t_end_ns,gesture,speed",
                "4000000000,6500000000,banish,normal",
                "13000000000,133000000000,null,typing",
            ),
            lines,
        )
    }
}
