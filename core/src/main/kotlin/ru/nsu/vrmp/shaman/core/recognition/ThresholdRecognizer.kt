package ru.nsu.vrmp.shaman.core.recognition

import ru.nsu.vrmp.shaman.core.input.ImuFrame
import ru.nsu.vrmp.shaman.core.input.InputFrame
import ru.nsu.vrmp.shaman.core.math.Vec3
import kotlin.math.PI
import kotlin.math.abs

/**
 * Threshold-based IMU gesture recognizer (pure Kotlin/JVM, deterministic, frame-clock only).
 *
 * Applies exponential moving average (EMA) low-pass filtering to linear acceleration (`linAcc`)
 * and angular velocity (`gyro`), then tracks three gestures:
 * - [Gesture.CATCH]: upward flick (`+linAcc.y` in Android device coordinates) with start/end
 *   hysteresis and min/max duration bounds.
 * - [Gesture.CANCEL]: shake with at least [Config.shakeMinSignChanges] alternating strong
 *   acceleration peaks along an axis within [Config.shakeWindowMs].
 * - [Gesture.BANISH]: circle rotation around the screen normal (`gyro.z`, either CW or CCW)
 *   accumulating at least [Config.banishTargetAngleRad] within [Config.banishMaxDurationMs].
 */
class ThresholdRecognizer(
    private val config: Config = Config(),
) : GestureRecognizer {

    data class Config(
        /** EMA smoothing factor in `(0, 1]`. `1.0` disables smoothing. */
        val emaAlpha: Double = 0.35,

        // CATCH (upward flick along +Y in device coordinates)
        val catchStartThreshold: Double = 6.0,
        val catchEndThreshold: Double = 2.0,
        val catchMinDurationMs: Long = 60L,
        val catchMaxDurationMs: Long = 500L,
        val catchPeakForFullConfidence: Double = 14.0,

        // CANCEL (shake: >= shakeMinSignChanges sign flips of strong acceleration within shakeWindowMs)
        val shakeThreshold: Double = 7.0,
        val shakeReleaseThreshold: Double = 2.5,
        val shakeWindowMs: Long = 600L,
        val shakeMinSignChanges: Int = 3,
        val shakeCandidateSignChanges: Int = 2,
        val shakeCooldownMs: Long = 300L,

        // BANISH (circle: accumulated |gyro.z| angle >= 300 deg within <= 1.5 s)
        val banishAngularRateStart: Double = 1.5,
        val banishAngularRateStop: Double = 0.5,
        val banishCandidateAngleRad: Double = Math.toRadians(60.0),
        val banishTargetAngleRad: Double = Math.toRadians(300.0),
        val banishMinDurationMs: Long = 200L,
        val banishMaxDurationMs: Long = 1500L,
        val banishPauseTimeoutMs: Long = 300L,
    ) {
        init {
            require(emaAlpha > 0.0 && emaAlpha <= 1.0) { "emaAlpha must be in (0, 1], got $emaAlpha" }
            require(catchStartThreshold > 0.0) { "catchStartThreshold must be > 0" }
            require(catchEndThreshold in 0.0..<catchStartThreshold) { "catchEndThreshold must be in [0, catchStartThreshold)" }
            require(catchMinDurationMs >= 0L) { "catchMinDurationMs must be >= 0" }
            require(catchMaxDurationMs > catchMinDurationMs) { "catchMaxDurationMs must be > catchMinDurationMs" }
            require(catchPeakForFullConfidence > catchStartThreshold) { "catchPeakForFullConfidence must be > catchStartThreshold" }
            require(shakeThreshold > 0.0) { "shakeThreshold must be > 0" }
            require(shakeReleaseThreshold in 0.0..<shakeThreshold) { "shakeReleaseThreshold must be in [0, shakeThreshold)" }
            require(shakeWindowMs > 0L) { "shakeWindowMs must be > 0" }
            require(shakeMinSignChanges >= 1) { "shakeMinSignChanges must be >= 1" }
            require(shakeCandidateSignChanges in 1..shakeMinSignChanges) { "shakeCandidateSignChanges must be in 1..shakeMinSignChanges" }
            require(shakeCooldownMs >= 0L) { "shakeCooldownMs must be >= 0" }
            require(banishAngularRateStart > 0.0) { "banishAngularRateStart must be > 0" }
            require(banishAngularRateStop in 0.0..<banishAngularRateStart) { "banishAngularRateStop must be in [0, banishAngularRateStart)" }
            require(banishCandidateAngleRad > 0.0) { "banishCandidateAngleRad must be > 0" }
            require(banishTargetAngleRad >= banishCandidateAngleRad) { "banishTargetAngleRad must be >= banishCandidateAngleRad" }
            require(banishMinDurationMs >= 0L) { "banishMinDurationMs must be >= 0" }
            require(banishMaxDurationMs > banishMinDurationMs) { "banishMaxDurationMs must be > banishMinDurationMs" }
            require(banishPauseTimeoutMs > 0L) { "banishPauseTimeoutMs must be > 0" }
        }
    }

    private enum class CatchState { IDLE, ACTIVE, WAIT_RELEASE }
    private enum class BanishState { IDLE, TRACKING, WAIT_RELEASE }

    private class ShakeAxisState {
        var lastSign: Int = 0
        val peakTimes = ArrayDeque<Long>()
        val peakMagnitudes = ArrayDeque<Double>()

        fun reset() {
            lastSign = 0
            peakTimes.clear()
            peakMagnitudes.clear()
        }
    }

    override val id: String = "threshold"

    private val catchMinDurationNanos = config.catchMinDurationMs * NANOS_PER_MS
    private val catchMaxDurationNanos = config.catchMaxDurationMs * NANOS_PER_MS
    private val shakeWindowNanos = config.shakeWindowMs * NANOS_PER_MS
    private val shakeCooldownNanos = config.shakeCooldownMs * NANOS_PER_MS
    private val banishMinDurationNanos = config.banishMinDurationMs * NANOS_PER_MS
    private val banishMaxDurationNanos = config.banishMaxDurationMs * NANOS_PER_MS
    private val banishPauseTimeoutNanos = config.banishPauseTimeoutMs * NANOS_PER_MS

    // Signal filter state
    private var hasEma = false
    private var smoothAcc = Vec3.ZERO
    private var smoothGyro = Vec3.ZERO
    private var lastFrameNanos: Long? = null

    // CATCH state
    private var catchState = CatchState.IDLE
    private var catchStartNanos = 0L
    private var catchPeak = 0.0

    // BANISH state
    private var banishState = BanishState.IDLE
    private var banishDirection = 0.0
    private var banishStartNanos = 0L
    private var banishAccumulatedRad = 0.0
    private var banishCandidateEmitted = false
    private var banishLowRateSinceNanos: Long? = null

    // CANCEL (shake) state
    private val shakeAxes = Array(3) { ShakeAxisState() }
    private var shakeCandidateEmitted = false
    private var shakeWaitingRelease = false
    private var shakeCooldownUntilNanos = Long.MIN_VALUE

    override fun reset() {
        hasEma = false
        smoothAcc = Vec3.ZERO
        smoothGyro = Vec3.ZERO
        lastFrameNanos = null

        catchState = CatchState.IDLE
        catchStartNanos = 0L
        catchPeak = 0.0

        banishState = BanishState.IDLE
        banishDirection = 0.0
        banishStartNanos = 0L
        banishAccumulatedRad = 0.0
        banishCandidateEmitted = false
        banishLowRateSinceNanos = null

        for (axis in shakeAxes) axis.reset()
        shakeCandidateEmitted = false
        shakeWaitingRelease = false
        shakeCooldownUntilNanos = Long.MIN_VALUE
    }

    override fun onFrame(frame: InputFrame): List<RecognizerEvent> {
        if (frame !is ImuFrame) return emptyList()
        if (!frame.linAcc.isFinite() || !frame.gyro.isFinite()) return emptyList()

        val prevT = lastFrameNanos
        if (prevT != null && frame.tNanos < prevT) return emptyList()
        val dtSec = if (prevT == null) 0.0 else (frame.tNanos - prevT) / NANOS_PER_SEC
        lastFrameNanos = frame.tNanos

        if (!hasEma) {
            smoothAcc = frame.linAcc
            smoothGyro = frame.gyro
            hasEma = true
        } else {
            val a = config.emaAlpha
            val oneMinusA = 1.0 - a
            smoothAcc = smoothAcc * oneMinusA + frame.linAcc * a
            smoothGyro = smoothGyro * oneMinusA + frame.gyro * a
        }

        val events = mutableListOf<RecognizerEvent>()
        updateCatch(frame.tNanos, smoothAcc.y, events)
        updateBanish(frame.tNanos, dtSec, smoothGyro.z, events)
        updateShake(frame.tNanos, smoothAcc, events)
        return events
    }

    private fun updateCatch(t: Long, accY: Double, out: MutableList<RecognizerEvent>) {
        when (catchState) {
            CatchState.IDLE -> {
                if (accY >= config.catchStartThreshold) {
                    catchState = CatchState.ACTIVE
                    catchStartNanos = t
                    catchPeak = accY
                    out += RecognizerEvent.Candidate(Gesture.CATCH, t)
                }
            }

            CatchState.ACTIVE -> {
                if (accY > catchPeak) catchPeak = accY
                val elapsed = t - catchStartNanos
                if (elapsed > catchMaxDurationNanos) {
                    catchState = CatchState.WAIT_RELEASE
                    out += RecognizerEvent.Rejected(t, "catch_too_long")
                } else if (accY <= config.catchEndThreshold) {
                    catchState = CatchState.IDLE
                    if (elapsed < catchMinDurationNanos) {
                        out += RecognizerEvent.Rejected(t, "catch_too_short")
                    } else {
                        val norm = ((catchPeak - config.catchStartThreshold) /
                            (config.catchPeakForFullConfidence - config.catchStartThreshold)).coerceIn(0.0, 1.0)
                        val confidence = 0.5 + 0.5 * norm
                        out += RecognizerEvent.Detected(
                            gesture = Gesture.CATCH,
                            confidence = confidence,
                            tStart = catchStartNanos,
                            tEnd = t,
                            tEmit = t,
                        )
                    }
                }
            }

            CatchState.WAIT_RELEASE -> {
                if (accY <= config.catchEndThreshold) {
                    catchState = CatchState.IDLE
                }
            }
        }
    }

    private fun updateBanish(t: Long, dtSec: Double, gyroZ: Double, out: MutableList<RecognizerEvent>) {
        when (banishState) {
            BanishState.IDLE -> {
                if (abs(gyroZ) >= config.banishAngularRateStart) {
                    startBanishTracking(t, gyroZ)
                }
            }

            BanishState.TRACKING -> {
                if (gyroZ * banishDirection > 0.0) {
                    banishAccumulatedRad += abs(gyroZ) * dtSec
                    if (abs(gyroZ) >= config.banishAngularRateStop) {
                        banishLowRateSinceNanos = null
                    } else if (banishLowRateSinceNanos == null) {
                        banishLowRateSinceNanos = t
                    }
                } else if (abs(gyroZ) >= config.banishAngularRateStart) {
                    if (banishCandidateEmitted) {
                        out += RecognizerEvent.Rejected(t, "banish_direction_reversed")
                    }
                    startBanishTracking(t, gyroZ)
                    return
                } else if (banishLowRateSinceNanos == null) {
                    banishLowRateSinceNanos = t
                }

                if (!banishCandidateEmitted && banishAccumulatedRad >= config.banishCandidateAngleRad) {
                    banishCandidateEmitted = true
                    out += RecognizerEvent.Candidate(Gesture.BANISH, t)
                }

                val elapsed = t - banishStartNanos
                if (banishAccumulatedRad >= config.banishTargetAngleRad) {
                    if (elapsed in banishMinDurationNanos..banishMaxDurationNanos) {
                        val extraSpan = (2.0 * PI - config.banishTargetAngleRad).coerceAtLeast(1e-6)
                        val completeness = ((banishAccumulatedRad - config.banishTargetAngleRad) / extraSpan)
                            .coerceIn(0.0, 1.0)
                        val confidence = 0.75 + 0.25 * completeness
                        out += RecognizerEvent.Detected(
                            gesture = Gesture.BANISH,
                            confidence = confidence,
                            tStart = banishStartNanos,
                            tEnd = t,
                            tEmit = t,
                        )
                    } else if (banishCandidateEmitted) {
                        val reason = if (elapsed < banishMinDurationNanos) "banish_too_short" else "banish_timeout"
                        out += RecognizerEvent.Rejected(t, reason)
                    }
                    banishState = BanishState.WAIT_RELEASE
                    return
                }

                if (elapsed > banishMaxDurationNanos) {
                    if (banishCandidateEmitted) {
                        out += RecognizerEvent.Rejected(t, "banish_timeout")
                    }
                    banishState = if (abs(gyroZ) >= config.banishAngularRateStop) {
                        BanishState.WAIT_RELEASE
                    } else {
                        BanishState.IDLE
                    }
                } else {
                    val lowSince = banishLowRateSinceNanos
                    if (lowSince != null && t - lowSince >= banishPauseTimeoutNanos) {
                        if (banishCandidateEmitted) {
                            out += RecognizerEvent.Rejected(t, "banish_incomplete")
                        }
                        banishState = BanishState.IDLE
                    }
                }
            }

            BanishState.WAIT_RELEASE -> {
                if (abs(gyroZ) < config.banishAngularRateStop) {
                    banishState = BanishState.IDLE
                }
            }
        }
    }

    private fun startBanishTracking(t: Long, gyroZ: Double) {
        banishState = BanishState.TRACKING
        banishDirection = if (gyroZ >= 0.0) 1.0 else -1.0
        banishStartNanos = t
        banishAccumulatedRad = 0.0
        banishCandidateEmitted = false
        banishLowRateSinceNanos = null
    }

    private fun updateShake(t: Long, acc: Vec3, out: MutableList<RecognizerEvent>) {
        if (shakeWaitingRelease) {
            if (t >= shakeCooldownUntilNanos && acc.norm() <= config.shakeReleaseThreshold) {
                shakeWaitingRelease = false
            }
            return
        }

        val cutoff = t - shakeWindowNanos
        for (i in 0..2) {
            val axis = shakeAxes[i]
            while (axis.peakTimes.isNotEmpty() && axis.peakTimes.first() < cutoff) {
                axis.peakTimes.removeFirst()
                axis.peakMagnitudes.removeFirst()
            }
            if (axis.peakTimes.isEmpty()) {
                axis.lastSign = 0
            }

            val value = when (i) {
                0 -> acc.x
                1 -> acc.y
                else -> acc.z
            }
            val mag = abs(value)
            if (mag >= config.shakeThreshold) {
                val sign = if (value > 0.0) 1 else -1
                if (axis.lastSign == 0 || sign != axis.lastSign) {
                    axis.lastSign = sign
                    axis.peakTimes.addLast(t)
                    axis.peakMagnitudes.addLast(mag)
                } else if (axis.peakMagnitudes.isNotEmpty() && mag > axis.peakMagnitudes.last()) {
                    axis.peakMagnitudes[axis.peakMagnitudes.lastIndex] = mag
                }
            }
        }

        val bestAxis = shakeAxes.maxBy { it.peakTimes.size }
        val detectPeaks = config.shakeMinSignChanges + 1
        val candidatePeaks = config.shakeCandidateSignChanges + 1

        if (bestAxis.peakTimes.size >= detectPeaks) {
            if (!shakeCandidateEmitted) {
                out += RecognizerEvent.Candidate(Gesture.CANCEL, t)
            }
            val tStart = bestAxis.peakTimes.first()
            val avgMag = bestAxis.peakMagnitudes.average()
            val norm = ((avgMag - config.shakeThreshold) / config.shakeThreshold).coerceIn(0.0, 1.0)
            val confidence = 0.5 + 0.5 * norm
            out += RecognizerEvent.Detected(
                gesture = Gesture.CANCEL,
                confidence = confidence,
                tStart = tStart,
                tEnd = t,
                tEmit = t,
            )
            for (axis in shakeAxes) axis.reset()
            shakeCandidateEmitted = false
            shakeWaitingRelease = true
            shakeCooldownUntilNanos = t + shakeCooldownNanos
        } else if (bestAxis.peakTimes.size >= candidatePeaks) {
            if (!shakeCandidateEmitted) {
                shakeCandidateEmitted = true
                out += RecognizerEvent.Candidate(Gesture.CANCEL, t)
            }
        } else if (shakeCandidateEmitted) {
            shakeCandidateEmitted = false
            out += RecognizerEvent.Rejected(t, "cancel_timeout")
        }
    }

    private fun Vec3.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val NANOS_PER_SEC = 1_000_000_000.0
    }
}
