package ru.nsu.vrmp.shaman.core.recognition

import ru.nsu.vrmp.shaman.core.calibration.CalibrationProfile
import ru.nsu.vrmp.shaman.core.calibration.ImuTemplate
import ru.nsu.vrmp.shaman.core.input.ImuFrame

/** Labeled IMU sequence used as a reference template by [DtwRecognizer]. */
data class DtwTemplate(
    val gesture: Gesture,
    val samples: List<ImuFrame>,
) {
    init {
        require(samples.isNotEmpty()) { "DtwTemplate requires at least 1 sample" }
    }

    companion object {
        fun fromImuTemplate(template: ImuTemplate): DtwTemplate =
            DtwTemplate(gesture = template.gesture, samples = template.samples)

        fun fromCalibration(profile: CalibrationProfile): List<DtwTemplate> =
            Gesture.entries.flatMap { gesture ->
                profile.templates[gesture].orEmpty().map(::fromImuTemplate)
            }
    }
}
