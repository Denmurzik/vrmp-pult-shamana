package ru.nsu.vrmp.shaman.core.recognition

import ru.nsu.vrmp.shaman.core.input.ImuFrame
import ru.nsu.vrmp.shaman.core.input.InputFrame
import ru.nsu.vrmp.shaman.core.math.Vec3

/** IMU channels extracted into feature vectors for DTW matching. */
enum class FeatureSet {
    LIN_ACC_ONLY,
    GYRO_ONLY,
    LIN_ACC_AND_GYRO,
}

/**
 * Streaming IMU gesture recognizer using motion-energy segmentation with hysteresis
 * and 1-NN Dynamic Time Warping (DTW) with a Sakoe-Chiba band.
 */
class DtwRecognizer(
    templates: List<DtwTemplate>,
    private val config: Config = Config(),
) : GestureRecognizer {

    data class Config(
        val sakoeChibaBandRatio: Double = 0.15,
        val featureSet: FeatureSet = FeatureSet.LIN_ACC_AND_GYRO,
        val rejectThreshold: Double = 1.2,
        val energyStartThreshold: Double = 2.5,
        val energyEndThreshold: Double = 1.2,
        val gyroEnergyWeight: Double = 1.5,
        val minDurationNanos: Long = 150_000_000L,
        val maxDurationNanos: Long = 2_000_000_000L,
        val endHoldNanos: Long = 120_000_000L,
        val resampleLength: Int = 32,
        val emaAlpha: Double = 0.5,
    ) {
        init {
            require(sakoeChibaBandRatio in 0.0..1.0) {
                "sakoeChibaBandRatio must be in [0, 1], got $sakoeChibaBandRatio"
            }
            require(rejectThreshold >= 0.0 && rejectThreshold.isFinite()) {
                "rejectThreshold must be finite and non-negative, got $rejectThreshold"
            }
            require(energyStartThreshold > 0.0 && energyStartThreshold.isFinite()) {
                "energyStartThreshold must be finite and positive, got $energyStartThreshold"
            }
            require(energyEndThreshold >= 0.0 && energyEndThreshold <= energyStartThreshold) {
                "energyEndThreshold ($energyEndThreshold) must be in [0, energyStartThreshold ($energyStartThreshold)]"
            }
            require(gyroEnergyWeight >= 0.0 && gyroEnergyWeight.isFinite()) {
                "gyroEnergyWeight must be finite and non-negative, got $gyroEnergyWeight"
            }
            require(minDurationNanos >= 0L) {
                "minDurationNanos must be non-negative, got $minDurationNanos"
            }
            require(maxDurationNanos >= minDurationNanos && maxDurationNanos > 0L) {
                "maxDurationNanos ($maxDurationNanos) must be > 0 and >= minDurationNanos ($minDurationNanos)"
            }
            require(endHoldNanos >= 0L) {
                "endHoldNanos must be non-negative, got $endHoldNanos"
            }
            require(resampleLength >= 0) {
                "resampleLength must be non-negative, got $resampleLength"
            }
            require(emaAlpha > 0.0 && emaAlpha <= 1.0) {
                "emaAlpha must be in (0, 1], got $emaAlpha"
            }
        }
    }

    private data class PreparedTemplate(
        val gesture: Gesture,
        val features: List<DoubleArray>,
    )

    private val preparedTemplates: List<PreparedTemplate> = templates.mapNotNull { template ->
        val validFrames = template.samples.filter(::isFiniteFrame)
        if (validFrames.isEmpty()) {
            null
        } else {
            PreparedTemplate(template.gesture, preprocess(validFrames))
        }
    }

    private var hasSmoothedEnergy = false
    private var smoothedEnergy = 0.0

    private var inSegment = false
    private val segmentFrames = ArrayList<ImuFrame>()
    private var segmentStartNanos = 0L
    private var lastActiveNanos = 0L
    private var lowEnergyStartNanos: Long? = null

    override val id: String = "dtw"

    override fun reset() {
        hasSmoothedEnergy = false
        smoothedEnergy = 0.0
        clearSegment()
    }

    override fun onFrame(frame: InputFrame): List<RecognizerEvent> {
        if (frame !is ImuFrame) return emptyList()
        if (!isFiniteFrame(frame)) return emptyList()

        val rawEnergy = frame.linAcc.norm() + config.gyroEnergyWeight * frame.gyro.norm()
        val energy = if (!hasSmoothedEnergy) {
            hasSmoothedEnergy = true
            rawEnergy
        } else {
            config.emaAlpha * rawEnergy + (1.0 - config.emaAlpha) * smoothedEnergy
        }
        smoothedEnergy = energy

        if (!inSegment) {
            if (energy >= config.energyStartThreshold) {
                inSegment = true
                segmentFrames.clear()
                segmentFrames.add(frame)
                segmentStartNanos = frame.tNanos
                lastActiveNanos = frame.tNanos
                lowEnergyStartNanos = null
            }
            return emptyList()
        }

        segmentFrames.add(frame)

        if (rawEnergy > config.energyEndThreshold) {
            lastActiveNanos = frame.tNanos
        }
        if (energy > config.energyEndThreshold) {
            lowEnergyStartNanos = null
        } else if (lowEnergyStartNanos == null) {
            lowEnergyStartNanos = frame.tNanos
        }

        val lowStart = lowEnergyStartNanos
        val endedByQuietHold = lowStart != null && (frame.tNanos - lowStart) >= config.endHoldNanos
        val endedByMaxDuration = (frame.tNanos - segmentStartNanos) >= config.maxDurationNanos

        if (!endedByQuietHold && !endedByMaxDuration) {
            return emptyList()
        }

        val tStart = segmentStartNanos
        val tEmit = frame.tNanos
        val tEnd = if (endedByQuietHold) {
            lastActiveNanos.coerceIn(tStart, tEmit)
        } else {
            tEmit
        }

        val trimmedFrames = if (endedByQuietHold) {
            segmentFrames.filter { it.tNanos <= tEnd }
        } else {
            segmentFrames.toList()
        }

        clearSegment()

        return listOf(classifySegment(trimmedFrames, tStart = tStart, tEnd = tEnd, tEmit = tEmit))
    }

    private fun classifySegment(
        frames: List<ImuFrame>,
        tStart: Long,
        tEnd: Long,
        tEmit: Long,
    ): RecognizerEvent {
        val duration = tEnd - tStart
        if (duration < config.minDurationNanos || frames.isEmpty()) {
            return RecognizerEvent.Rejected(tEmit, "segment too short")
        }
        if (preparedTemplates.isEmpty()) {
            return RecognizerEvent.Rejected(tEmit, "no templates")
        }

        val query = preprocess(frames)
        var bestGesture: Gesture? = null
        var bestDistance = Double.POSITIVE_INFINITY

        for (template in preparedTemplates) {
            val d = Dtw.distance(query, template.features, config.sakoeChibaBandRatio)
            if (d < bestDistance) {
                bestDistance = d
                bestGesture = template.gesture
            }
        }

        val gesture = bestGesture
        if (gesture == null || !bestDistance.isFinite() || bestDistance > config.rejectThreshold) {
            return RecognizerEvent.Rejected(tEmit, "distance above threshold")
        }

        val confidence = if (config.rejectThreshold > 0.0) {
            (1.0 - bestDistance / config.rejectThreshold).coerceIn(0.0, 1.0)
        } else {
            if (bestDistance == 0.0) 1.0 else 0.0
        }

        return RecognizerEvent.Detected(
            gesture = gesture,
            confidence = confidence,
            tStart = tStart,
            tEnd = tEnd,
            tEmit = tEmit,
        )
    }

    private fun preprocess(frames: List<ImuFrame>): List<DoubleArray> {
        val raw = frames.map(::extractFeatures)
        val resampled = if (config.resampleLength > 0) {
            Dtw.resample(raw, config.resampleLength)
        } else {
            raw
        }
        return Dtw.zNormalize(resampled)
    }

    private fun extractFeatures(frame: ImuFrame): DoubleArray = when (config.featureSet) {
        FeatureSet.LIN_ACC_ONLY -> doubleArrayOf(frame.linAcc.x, frame.linAcc.y, frame.linAcc.z)
        FeatureSet.GYRO_ONLY -> doubleArrayOf(frame.gyro.x, frame.gyro.y, frame.gyro.z)
        FeatureSet.LIN_ACC_AND_GYRO -> doubleArrayOf(
            frame.linAcc.x,
            frame.linAcc.y,
            frame.linAcc.z,
            frame.gyro.x,
            frame.gyro.y,
            frame.gyro.z,
        )
    }

    private fun clearSegment() {
        inSegment = false
        segmentFrames.clear()
        segmentStartNanos = 0L
        lastActiveNanos = 0L
        lowEnergyStartNanos = null
    }

    private fun isFiniteFrame(frame: ImuFrame): Boolean =
        isFiniteVec(frame.linAcc) && isFiniteVec(frame.gyro)

    private fun isFiniteVec(v: Vec3): Boolean =
        v.x.isFinite() && v.y.isFinite() && v.z.isFinite()
}
