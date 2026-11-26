package ru.nsu.vrmp.shaman.calibration

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.nsu.vrmp.shaman.core.calibration.CalibrationManager
import ru.nsu.vrmp.shaman.core.calibration.CalibrationProfile
import ru.nsu.vrmp.shaman.core.calibration.CalibrationState
import ru.nsu.vrmp.shaman.core.calibration.CalibrationStep
import ru.nsu.vrmp.shaman.core.feedback.CommandFeedbackKind
import ru.nsu.vrmp.shaman.core.feedback.FeedbackPlanner
import ru.nsu.vrmp.shaman.core.input.HandFrame
import ru.nsu.vrmp.shaman.core.input.ImuFrame
import ru.nsu.vrmp.shaman.core.math.Quat
import ru.nsu.vrmp.shaman.core.math.Vec3
import ru.nsu.vrmp.shaman.feedback.FeedbackPlayer
import ru.nsu.vrmp.shaman.feedback.NoOpFeedbackPlayer
import ru.nsu.vrmp.shaman.game.BannerTone

data class CalibrationUiState(
    val calibration: CalibrationState,
    val isRecordingImu: Boolean = false,
    val hasSavedProfile: Boolean = false,
    val bannerTitle: String = "Подготовка к калибровке",
    val bannerSubtitle: String = "Направьте телефон вперёд и зафиксируйте ноль",
    val bannerTone: BannerTone = BannerTone.NEUTRAL,
)

/**
 * Coordinates the 3-step calibration flow ([CalibrationManager]) with persistent storage ([CalibrationStore]).
 */
class CalibrationController(
    private val store: CalibrationStore = InMemoryCalibrationStore(),
    private val feedbackPlayer: FeedbackPlayer = NoOpFeedbackPlayer,
    private val clockNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
) {
    private val manager = CalibrationManager()

    var uiState by mutableStateOf(
        CalibrationUiState(
            calibration = manager.currentState(),
            hasSavedProfile = store.load() != null,
        ),
    )
        private set

    fun captureZeroHeading(rotation: Quat) {
        val next = manager.captureZeroHeading(rotation)
        feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.CONFIRMED))
        uiState = uiState.copy(
            calibration = next,
            bannerTitle = "Нулевое направление зафиксировано",
            bannerSubtitle = "Запишите по 5 повторов каждого IMU-жеста для DTW",
            bannerTone = BannerTone.SUCCESS,
        )
    }

    fun startImuRecording() {
        uiState = uiState.copy(
            isRecordingImu = true,
            bannerTitle = "Идёт запись жеста…",
            bannerSubtitle = "Выполните жест в течение 1.5 с",
            bannerTone = BannerTone.NEUTRAL,
        )
    }

    fun finishImuRecording(samples: List<ImuFrame>, allowFallback: Boolean = false) {
        val effectiveSamples = if (samples.size >= CalibrationManager.MIN_TEMPLATE_SAMPLES) {
            samples
        } else if (allowFallback) {
            syntheticImuSamples(clockNanos())
        } else {
            feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.ERROR))
            uiState = uiState.copy(
                isRecordingImu = false,
                bannerTitle = "Недостаточно данных IMU",
                bannerSubtitle = "Повторите движение (получено ${samples.size} отсчётов)",
                bannerTone = BannerTone.ERROR,
            )
            return
        }

        val next = manager.recordImuTemplate(effectiveSamples)
        feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.CONFIRMED))
        val (title, subtitle) = if (next.step == CalibrationStep.CAMERA_CHECK) {
            "Эталоны IMU сохранены" to "Проверьте видимость руки и освещение камеры"
        } else {
            val g = next.currentTemplateGesture
            val count = g?.let { next.templateCounts[it] ?: 0 } ?: 0
            "Эталон записан ($count/${next.targetTemplatesPerGesture})" to "Продолжайте запись для DTW"
        }
        uiState = uiState.copy(
            calibration = next,
            isRecordingImu = false,
            bannerTitle = title,
            bannerSubtitle = subtitle,
            bannerTone = BannerTone.SUCCESS,
        )
    }

    fun evaluateCamera(frames: List<HandFrame>, lumaMean: Double) {
        val status = manager.evaluateCamera(frames, lumaMean)
        val next = manager.currentState()
        val (title, subtitle, tone) = if (status.isReady) {
            feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.CONFIRMED))
            Triple("Камера готова", "Освещение и рука в норме", BannerTone.SUCCESS)
        } else {
            feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.ERROR))
            val reason = when {
                !status.lightingOk -> "Неподходящее освещение (${lumaMean.toInt()})"
                else -> "Рука не обнаружена в кадре"
            }
            Triple("Проверка не пройдена", reason, BannerTone.ERROR)
        }
        uiState = uiState.copy(
            calibration = next,
            bannerTitle = title,
            bannerSubtitle = subtitle,
            bannerTone = tone,
        )
    }

    fun confirmCameraReady() {
        val next = manager.confirmCameraReady()
        saveProfile()
        feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.CONFIRMED))
        uiState = uiState.copy(
            calibration = next,
            hasSavedProfile = true,
            bannerTitle = "Калибровка завершена",
            bannerSubtitle = "Эталоны DTW и нулевое направление сохранены",
            bannerTone = BannerTone.SUCCESS,
        )
    }

    fun skipCameraCheck() {
        val next = manager.skipCameraCheck()
        saveProfile()
        feedbackPlayer.play(FeedbackPlanner.commandCue(CommandFeedbackKind.CONFIRMED))
        uiState = uiState.copy(
            calibration = next,
            hasSavedProfile = true,
            bannerTitle = "Калибровка IMU завершена",
            bannerSubtitle = "Профиль сохранён (проверка камеры пропущена)",
            bannerTone = BannerTone.WARNING,
        )
    }

    fun reset() {
        val next = manager.reset()
        uiState = CalibrationUiState(
            calibration = next,
            hasSavedProfile = store.load() != null,
        )
    }

    fun savedProfile(): CalibrationProfile? = store.load()

    private fun saveProfile() {
        store.save(manager.buildProfile())
    }

    private fun syntheticImuSamples(baseTime: Long): List<ImuFrame> =
        (0 until 15).map { i ->
            ImuFrame(
                tNanos = baseTime + i * 20_000_000L,
                linAcc = Vec3(0.0, 0.4 * i, 0.2),
                gyro = Vec3(0.05 * i, 0.0, 0.1),
                rotation = Quat.IDENTITY,
            )
        }
}
