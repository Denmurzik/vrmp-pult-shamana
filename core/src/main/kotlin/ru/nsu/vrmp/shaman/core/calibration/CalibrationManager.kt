package ru.nsu.vrmp.shaman.core.calibration

import ru.nsu.vrmp.shaman.core.input.HandFrame
import ru.nsu.vrmp.shaman.core.input.ImuFrame
import ru.nsu.vrmp.shaman.core.math.Quat
import ru.nsu.vrmp.shaman.core.math.Vec3
import ru.nsu.vrmp.shaman.core.recognition.Gesture

enum class CalibrationStep {
    ZERO_HEADING,
    IMU_TEMPLATES,
    CAMERA_CHECK,
    COMPLETED,
}

data class ImuTemplate(
    val gesture: Gesture,
    val samples: List<ImuFrame>,
) {
    init {
        require(samples.isNotEmpty()) { "ImuTemplate requires at least 1 sample" }
    }
}

data class CameraCheckStatus(
    val lightingOk: Boolean = false,
    val handVisible: Boolean = false,
    val lumaMean: Double = 0.0,
) {
    val isReady: Boolean get() = lightingOk && handVisible
}

data class CalibrationProfile(
    val zeroHeadingRotation: Quat,
    val templates: Map<Gesture, List<ImuTemplate>>,
    val cameraVerified: Boolean,
)

data class CalibrationState(
    val step: CalibrationStep,
    val zeroHeadingCaptured: Boolean,
    val zeroHeadingRotation: Quat,
    val targetTemplatesPerGesture: Int,
    val currentTemplateGesture: Gesture?,
    val templateCounts: Map<Gesture, Int>,
    val cameraStatus: CameraCheckStatus,
    val cameraVerified: Boolean,
) {
    val isImuCalibrated: Boolean
        get() = Gesture.entries.all { (templateCounts[it] ?: 0) >= targetTemplatesPerGesture }
}

/**
 * Calibration state machine per docs/SPEC.md §4:
 * 1) zero heading orientation,
 * 2) 5 IMU templates per gesture for DTW,
 * 3) camera visibility and lighting verification.
 */
class CalibrationManager(
    private val targetTemplatesPerGesture: Int = DEFAULT_TEMPLATES_PER_GESTURE,
) {
    private var step = CalibrationStep.ZERO_HEADING
    private var zeroHeadingCaptured = false
    private var zeroHeadingRotation = Quat.IDENTITY
    private val templates = Gesture.entries.associateWith { mutableListOf<ImuTemplate>() }
    private var gestureIndex = 0
    private var cameraStatus = CameraCheckStatus()
    private var cameraVerified = false

    init {
        require(targetTemplatesPerGesture > 0) {
            "targetTemplatesPerGesture must be positive, got $targetTemplatesPerGesture"
        }
    }

    fun reset(): CalibrationState {
        step = CalibrationStep.ZERO_HEADING
        zeroHeadingCaptured = false
        zeroHeadingRotation = Quat.IDENTITY
        templates.values.forEach { it.clear() }
        gestureIndex = 0
        cameraStatus = CameraCheckStatus()
        cameraVerified = false
        return currentState()
    }

    fun captureZeroHeading(rotation: Quat): CalibrationState {
        zeroHeadingRotation = rotation.normalized()
        zeroHeadingCaptured = true
        if (step == CalibrationStep.ZERO_HEADING) {
            step = CalibrationStep.IMU_TEMPLATES
        }
        return currentState()
    }

    fun recordImuTemplate(samples: List<ImuFrame>): CalibrationState {
        require(samples.size >= MIN_TEMPLATE_SAMPLES) {
            "Template requires at least $MIN_TEMPLATE_SAMPLES IMU samples, got ${samples.size}"
        }
        check(step == CalibrationStep.IMU_TEMPLATES) {
            "Cannot record IMU template during step $step"
        }

        val gesture = Gesture.entries[gestureIndex]
        val list = templates.getValue(gesture)
        list += ImuTemplate(gesture, samples.toList())

        if (list.size >= targetTemplatesPerGesture) {
            if (gestureIndex < Gesture.entries.lastIndex) {
                gestureIndex += 1
            } else {
                step = CalibrationStep.CAMERA_CHECK
            }
        }
        return currentState()
    }

    fun evaluateCamera(
        frames: List<HandFrame>,
        lumaMean: Double,
        minLuma: Double = MIN_ACCEPTABLE_LUMA,
        maxLuma: Double = MAX_ACCEPTABLE_LUMA,
    ): CameraCheckStatus {
        val lightingOk = lumaMean in minLuma..maxLuma
        val handVisible = frames.any { it.present }
        cameraStatus = CameraCheckStatus(
            lightingOk = lightingOk,
            handVisible = handVisible,
            lumaMean = lumaMean,
        )
        return cameraStatus
    }

    fun confirmCameraReady(): CalibrationState {
        check(cameraStatus.isReady) {
            "Camera readiness check must pass lighting and hand visibility before confirming"
        }
        cameraVerified = true
        step = CalibrationStep.COMPLETED
        return currentState()
    }

    fun skipCameraCheck(): CalibrationState {
        check(step == CalibrationStep.CAMERA_CHECK) {
            "Can only skip camera check during CAMERA_CHECK step"
        }
        cameraVerified = false
        step = CalibrationStep.COMPLETED
        return currentState()
    }

    fun currentState(): CalibrationState = CalibrationState(
        step = step,
        zeroHeadingCaptured = zeroHeadingCaptured,
        zeroHeadingRotation = zeroHeadingRotation,
        targetTemplatesPerGesture = targetTemplatesPerGesture,
        currentTemplateGesture = if (step == CalibrationStep.IMU_TEMPLATES) Gesture.entries[gestureIndex] else null,
        templateCounts = Gesture.entries.associateWith { templates.getValue(it).size },
        cameraStatus = cameraStatus,
        cameraVerified = cameraVerified,
    )

    fun buildProfile(): CalibrationProfile = CalibrationProfile(
        zeroHeadingRotation = zeroHeadingRotation,
        templates = Gesture.entries.associateWith { templates.getValue(it).toList() },
        cameraVerified = cameraVerified,
    )

    companion object {
        const val DEFAULT_TEMPLATES_PER_GESTURE = 5
        const val MIN_TEMPLATE_SAMPLES = 5
        const val MIN_ACCEPTABLE_LUMA = 45.0
        const val MAX_ACCEPTABLE_LUMA = 235.0
    }
}

/** Deterministic, dependency-free codec for storing [CalibrationProfile] on device. */
object CalibrationProfileCodec {
    private const val MAGIC = "SHAMAN_CAL_V1"

    fun encode(profile: CalibrationProfile): String = buildString {
        appendLine(MAGIC)
        val q = profile.zeroHeadingRotation
        appendLine("ZERO ${q.w} ${q.x} ${q.y} ${q.z}")
        appendLine("CAMERA ${profile.cameraVerified}")
        Gesture.entries.forEach { gesture ->
            profile.templates[gesture].orEmpty().forEach { template ->
                appendLine("TEMPLATE ${gesture.name} ${template.samples.size}")
                template.samples.forEach { f ->
                    appendLine(
                        "${f.tNanos} ${f.linAcc.x} ${f.linAcc.y} ${f.linAcc.z} " +
                            "${f.gyro.x} ${f.gyro.y} ${f.gyro.z} " +
                            "${f.rotation.w} ${f.rotation.x} ${f.rotation.y} ${f.rotation.z}",
                    )
                }
            }
        }
    }

    fun decode(raw: String): CalibrationProfile {
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.firstOrNull() == MAGIC) { "Unsupported calibration format" }

        var zero = Quat.IDENTITY
        var cameraVerified = false
        val templates = Gesture.entries.associateWith { mutableListOf<ImuTemplate>() }

        var idx = 1
        while (idx < lines.size) {
            val parts = lines[idx].split(' ')
            when (parts[0]) {
                "ZERO" -> {
                    require(parts.size == 5) { "Invalid ZERO line" }
                    zero = Quat(parts[1].toDouble(), parts[2].toDouble(), parts[3].toDouble(), parts[4].toDouble())
                    idx++
                }

                "CAMERA" -> {
                    require(parts.size == 2) { "Invalid CAMERA line" }
                    cameraVerified = parts[1].toBooleanStrict()
                    idx++
                }

                "TEMPLATE" -> {
                    require(parts.size == 3) { "Invalid TEMPLATE line" }
                    val gesture = Gesture.valueOf(parts[1])
                    val count = parts[2].toInt()
                    idx++
                    val samples = ArrayList<ImuFrame>(count)
                    repeat(count) {
                        val s = lines[idx++].split(' ')
                        require(s.size == 11) { "Invalid IMU sample line" }
                        samples += ImuFrame(
                            tNanos = s[0].toLong(),
                            linAcc = Vec3(s[1].toDouble(), s[2].toDouble(), s[3].toDouble()),
                            gyro = Vec3(s[4].toDouble(), s[5].toDouble(), s[6].toDouble()),
                            rotation = Quat(s[7].toDouble(), s[8].toDouble(), s[9].toDouble(), s[10].toDouble()),
                        )
                    }
                    templates.getValue(gesture) += ImuTemplate(gesture, samples)
                }

                else -> throw IllegalArgumentException("Unknown tag: ${parts[0]}")
            }
        }

        return CalibrationProfile(
            zeroHeadingRotation = zero,
            templates = templates.mapValues { it.value.toList() },
            cameraVerified = cameraVerified,
        )
    }
}
