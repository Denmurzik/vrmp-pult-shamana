package ru.nsu.vrmp.shaman.dataset

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.nsu.vrmp.shaman.core.dataset.DatasetCaptureConfig
import ru.nsu.vrmp.shaman.core.dataset.DatasetCaptureMode
import ru.nsu.vrmp.shaman.core.dataset.DatasetCaptureSession
import ru.nsu.vrmp.shaman.core.dataset.DatasetCaptureState
import ru.nsu.vrmp.shaman.core.dataset.DatasetCue
import ru.nsu.vrmp.shaman.core.dataset.GestureSpeed
import ru.nsu.vrmp.shaman.core.dataset.NullActivity
import ru.nsu.vrmp.shaman.core.feedback.CommandFeedbackKind
import ru.nsu.vrmp.shaman.core.feedback.FeedbackPlanner
import ru.nsu.vrmp.shaman.core.input.HandFrame
import ru.nsu.vrmp.shaman.core.input.ImuFrame
import ru.nsu.vrmp.shaman.core.recognition.Gesture
import ru.nsu.vrmp.shaman.core.recording.LabelEntry
import ru.nsu.vrmp.shaman.core.recording.SessionKind
import ru.nsu.vrmp.shaman.core.recording.SessionRecorder
import ru.nsu.vrmp.shaman.feedback.FeedbackPlayer
import ru.nsu.vrmp.shaman.feedback.NoOpFeedbackPlayer
import ru.nsu.vrmp.shaman.game.BannerTone
import ru.nsu.vrmp.shaman.recording.AndroidSessionStorage
import ru.nsu.vrmp.shaman.recording.SessionShareHelper
import java.io.File

data class DatasetUiState(
    val capture: DatasetCaptureState,
    val participantInput: String = capture.participantId,
    val participantError: String? = null,
    val sessionId: String? = null,
    val imuFramesRecorded: Int = 0,
    val handFramesRecorded: Int = 0,
    val hasMotionSensors: Boolean = true,
    val canExport: Boolean = false,
    val bannerTitle: String = "Готов к записи датасета",
    val bannerSubtitle: String = "Выберите жест и скорость или нулевой поток, затем нажмите «Старт»",
    val bannerTone: BannerTone = BannerTone.NEUTRAL,
)

/**
 * Coordinates [DatasetCaptureSession] (`:core`) with Android IMU/Hand streams,
 * [SessionRecorder] (`AndroidSessionStorage`), [FeedbackPlayer], and ZIP export ([SessionShareHelper]).
 */
class DatasetController(
    private val context: Context,
    private val storage: AndroidSessionStorage = AndroidSessionStorage(context),
    private val feedbackPlayer: FeedbackPlayer = NoOpFeedbackPlayer,
    private val hasMotionSensors: Boolean = true,
    private val config: DatasetCaptureConfig = DatasetCaptureConfig(),
    private val clockNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
) {
    private val recorderLock = Any()
    private var activeRecorder: SessionRecorder? = null
    private var lastSessionDir: File? = null
    private var sessionCounter: Int = 0

    private val session = DatasetCaptureSession(
        participantId = "P01",
        config = config,
        onLabelCommitted = ::writeCommittedLabel,
    )

    var uiState by mutableStateOf(
        DatasetUiState(
            capture = session.currentState(),
            hasMotionSensors = hasMotionSensors,
        ),
    )
        private set

    fun onParticipantInputChanged(raw: String) {
        val trimmed = raw.trim().uppercase()
        if (DatasetCaptureSession.isValidParticipantId(trimmed)) {
            if (trimmed != session.currentState().participantId) {
                closeActiveRecorder()
            }
            val next = session.setParticipantId(trimmed)
            uiState = uiState.copy(
                capture = next,
                participantInput = raw,
                participantError = null,
                sessionId = activeRecorder?.meta?.sessionId,
                canExport = lastSessionDir != null,
            )
        } else {
            uiState = uiState.copy(
                participantInput = raw,
                participantError = "Формат ID: P01–P99 (без имён)",
            )
        }
    }

    fun onSelectMode(mode: DatasetCaptureMode) {
        val next = session.selectMode(mode)
        uiState = uiState.copy(
            capture = next,
            bannerTitle = if (mode == DatasetCaptureMode.GESTURE) "Режим жестов (2.5 с)" else "Нулевой поток (2 мин)",
            bannerSubtitle = next.promptText,
            bannerTone = BannerTone.NEUTRAL,
        )
    }

    fun onSelectGesture(gesture: Gesture) {
        val next = session.selectGesture(gesture)
        uiState = uiState.copy(capture = next)
    }

    fun onSelectSpeed(speed: GestureSpeed) {
        val next = session.selectSpeed(speed)
        uiState = uiState.copy(capture = next)
    }

    fun onSelectNullActivity(activity: NullActivity) {
        val next = session.selectNullActivity(activity)
        uiState = uiState.copy(capture = next)
    }

    fun onStartTrial() {
        if (uiState.participantError != null) {
            feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.ERROR))
            return
        }
        ensureRecorderOpen()
        val result = session.startTrial(clockNanos())
        playCues(result.cues)
        uiState = uiState.copy(
            capture = result.state,
            sessionId = activeRecorder?.meta?.sessionId,
            canExport = lastSessionDir != null,
            bannerTitle = "Приготовьтесь…",
            bannerSubtitle = result.state.promptText,
            bannerTone = BannerTone.NEUTRAL,
        )
    }

    fun onTick() {
        val result = session.onTick(clockNanos())
        if (result.cues.isNotEmpty()) {
            playCues(result.cues)
        }
        val (title, subtitle, tone) = when {
            DatasetCue.RECORDING_STARTED in result.cues ->
                Triple("Идёт запись!", result.state.promptText, BannerTone.WARNING)
            DatasetCue.RECORDING_FINISHED in result.cues ->
                Triple("Запись завершена", "Подтвердите сэмпл или повторите попытку", BannerTone.SUCCESS)
            else ->
                Triple(uiState.bannerTitle, uiState.bannerSubtitle, uiState.bannerTone)
        }
        uiState = uiState.copy(
            capture = result.state,
            bannerTitle = title,
            bannerSubtitle = subtitle,
            bannerTone = tone,
        )
    }

    fun onStopEarly() {
        val result = session.stopEarly(clockNanos())
        playCues(result.cues)
        uiState = uiState.copy(
            capture = result.state,
            bannerTitle = "Запись остановлена",
            bannerSubtitle = "Подтвердите сэмпл или повторите попытку",
            bannerTone = BannerTone.SUCCESS,
        )
    }

    fun onConfirmTrial() {
        val result = session.confirmLastTrial()
        feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.CONFIRMED))
        uiState = uiState.copy(
            capture = result.state,
            canExport = lastSessionDir != null,
            bannerTitle = "Сэмпл сохранён (#${result.state.committedCount})",
            bannerSubtitle = "Следующая подсказка: ${result.state.promptText}",
            bannerTone = BannerTone.SUCCESS,
        )
    }

    fun onRepeatTrial() {
        val result = session.repeatLastTrial(clockNanos())
        feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.CANCELLED))
        playCues(result.cues)
        uiState = uiState.copy(
            capture = result.state,
            bannerTitle = "Повтор попытки…",
            bannerSubtitle = result.state.promptText,
            bannerTone = BannerTone.NEUTRAL,
        )
    }

    fun onCancelTrial() {
        val result = session.cancel()
        feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.CANCELLED))
        uiState = uiState.copy(
            capture = result.state,
            bannerTitle = "Запись отменена",
            bannerSubtitle = result.state.promptText,
            bannerTone = BannerTone.NEUTRAL,
        )
    }

    /** Feeds an IMU frame from [ru.nsu.vrmp.shaman.calibration.ImuSampler] into the active [SessionRecorder]. */
    fun onImuFrame(frame: ImuFrame) {
        val recorded = synchronized(recorderLock) {
            val rec = activeRecorder ?: return@synchronized false
            rec.recordImu(frame)
            true
        }
        if (recorded) {
            uiState = uiState.copy(imuFramesRecorded = uiState.imuFramesRecorded + 1)
        }
    }

    /** Optional hook for MediaPipe camera [HandFrame] stream when camera capture is attached. */
    fun onHandFrame(frame: HandFrame) {
        val recorded = synchronized(recorderLock) {
            val rec = activeRecorder ?: return@synchronized false
            rec.recordHand(frame)
            true
        }
        if (recorded) {
            uiState = uiState.copy(handFramesRecorded = uiState.handFramesRecorded + 1)
        }
    }

    /** Flushes and exports the current dataset session as a ZIP archive via Android Share Sheet. */
    fun onExportZip(activityContext: Context) {
        val dir = synchronized(recorderLock) {
            activeRecorder?.flush()
            lastSessionDir
        }
        if (dir == null || !dir.isDirectory) {
            feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.ERROR))
            uiState = uiState.copy(
                bannerTitle = "Нет сессии для экспорта",
                bannerSubtitle = "Сначала запишите хотя бы один сэмпл",
                bannerTone = BannerTone.ERROR,
            )
            return
        }

        runCatching {
            SessionShareHelper.shareSession(
                context = activityContext,
                storage = storage,
                sessionDir = dir,
                chooserTitle = "Экспорт датасета (${dir.name})",
            )
        }.onSuccess { zipFile ->
            uiState = uiState.copy(
                bannerTitle = "Архив готов",
                bannerSubtitle = zipFile.name,
                bannerTone = BannerTone.SUCCESS,
            )
        }.onFailure { error ->
            feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.ERROR))
            uiState = uiState.copy(
                bannerTitle = "Ошибка экспорта",
                bannerSubtitle = error.message ?: "Не удалось упаковать сессию",
                bannerTone = BannerTone.ERROR,
            )
        }
    }

    fun close() {
        closeActiveRecorder()
    }

    private fun ensureRecorderOpen() {
        synchronized(recorderLock) {
            if (activeRecorder != null) return
            sessionCounter += 1
            val participantId = session.currentState().participantId
            val timestampPart = (clockNanos() / 1_000_000L).coerceAtLeast(0L)
            val sessionId = "${participantId}_dataset_${timestampPart}_$sessionCounter"
            val meta = AndroidSessionStorage.defaultDeviceMeta(
                context = context,
                sessionId = sessionId,
                kind = SessionKind.DATASET,
                participantId = participantId,
            )
            val recorder = storage.openRecorder(meta = meta, writeLabelsFile = true)
            activeRecorder = recorder
            lastSessionDir = recorder.sessionDir.toFile()
        }
    }

    private fun writeCommittedLabel(entry: LabelEntry) {
        synchronized(recorderLock) {
            val rec = activeRecorder ?: return
            rec.recordLabel(entry)
            rec.flush()
        }
    }

    private fun closeActiveRecorder() {
        synchronized(recorderLock) {
            activeRecorder?.runCatching { close() }
            activeRecorder = null
        }
    }

    private fun playCues(cues: List<DatasetCue>) {
        cues.forEach { cue ->
            when (cue) {
                DatasetCue.COUNTDOWN_TICK ->
                    feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.CANDIDATE))
                DatasetCue.RECORDING_STARTED ->
                    feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.CONFIRMED))
                DatasetCue.RECORDING_FINISHED ->
                    feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.CONFIRMED))
            }
        }
    }
}
