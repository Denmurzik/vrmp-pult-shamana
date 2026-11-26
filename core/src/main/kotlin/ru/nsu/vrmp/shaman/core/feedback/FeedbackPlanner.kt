package ru.nsu.vrmp.shaman.core.feedback

import kotlin.math.pow
import kotlin.math.roundToInt

enum class CommandFeedbackKind {
    CANDIDATE,
    CONFIRMED,
    CANCELLED,
    ERROR,
    ATTACK,
}

sealed interface FeedbackCue {
    data class ProximityPulse(
        val proximity: Double,
        val intervalMs: Long,
        val amplitude: Int,
        val frequencyHz: Double,
    ) : FeedbackCue

    data class CommandFeedback(
        val kind: CommandFeedbackKind,
    ) : FeedbackCue
}

data class CuePattern(
    val timingsMs: LongArray,
    val amplitudes: IntArray,
    val tonesHz: DoubleArray,
    val toneDurationMs: Int,
) {
    val amplitude: Int get() = amplitudes.maxOrNull() ?: 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CuePattern) return false
        return timingsMs.contentEquals(other.timingsMs) &&
            amplitudes.contentEquals(other.amplitudes) &&
            tonesHz.contentEquals(other.tonesHz) &&
            toneDurationMs == other.toneDurationMs
    }

    override fun hashCode(): Int {
        var result = timingsMs.contentHashCode()
        result = 31 * result + amplitudes.contentHashCode()
        result = 31 * result + tonesHz.contentHashCode()
        result = 31 * result + toneDurationMs
        return result
    }
}

/**
 * Deterministic planner mapping beam proximity and command/attack events to haptic & audio cues.
 */
class FeedbackPlanner {

    private var nextProximityAtNanos = Long.MIN_VALUE
    private var nextAttackAtNanos = Long.MIN_VALUE

    fun reset() {
        nextProximityAtNanos = Long.MIN_VALUE
        nextAttackAtNanos = Long.MIN_VALUE
    }

    fun poll(proximity: Double, attackActive: Boolean, tNanos: Long): FeedbackCue? {
        if (attackActive) {
            if (tNanos >= nextAttackAtNanos) {
                nextAttackAtNanos = tNanos + ATTACK_INTERVAL_MS * NS_PER_MS
                return FeedbackCue.CommandFeedback(CommandFeedbackKind.ATTACK)
            }
            return null
        }

        val pulse = describeProximity(proximity) ?: return null
        if (tNanos >= nextProximityAtNanos) {
            nextProximityAtNanos = tNanos + pulse.intervalMs * NS_PER_MS
            return pulse
        }
        return null
    }

    companion object {
        private const val NS_PER_MS = 1_000_000L
        private const val MIN_PROXIMITY = 0.05
        private const val MIN_INTERVAL_MS = 140L
        private const val MAX_INTERVAL_MS = 900L
        private const val MIN_AMPLITUDE = 45
        private const val MAX_AMPLITUDE = 255
        private const val MIN_FREQ_HZ = 260.0
        private const val MAX_FREQ_HZ = 880.0
        private const val ATTACK_INTERVAL_MS = 400L

        fun describeProximity(proximity: Double): FeedbackCue.ProximityPulse? {
            if (proximity < MIN_PROXIMITY) return null
            val p = ((proximity - MIN_PROXIMITY) / (1.0 - MIN_PROXIMITY)).coerceIn(0.0, 1.0)
            // Quadratic curve keeps high-alignment differences crisp near the target.
            val curved = p.pow(1.4)
            val intervalMs = (MAX_INTERVAL_MS - curved * (MAX_INTERVAL_MS - MIN_INTERVAL_MS)).roundToInt().toLong()
            val amplitude = (MIN_AMPLITUDE + curved * (MAX_AMPLITUDE - MIN_AMPLITUDE)).roundToInt().coerceIn(1, 255)
            val frequencyHz = MIN_FREQ_HZ + curved * (MAX_FREQ_HZ - MIN_FREQ_HZ)
            return FeedbackCue.ProximityPulse(
                proximity = proximity.coerceIn(0.0, 1.0),
                intervalMs = intervalMs,
                amplitude = amplitude,
                frequencyHz = frequencyHz,
            )
        }

        fun commandCue(kind: CommandFeedbackKind): FeedbackCue.CommandFeedback =
            FeedbackCue.CommandFeedback(kind)

        fun patternFor(kind: CommandFeedbackKind): CuePattern = when (kind) {
            CommandFeedbackKind.CANDIDATE -> CuePattern(
                timingsMs = longArrayOf(0L, 25L),
                amplitudes = intArrayOf(0, 90),
                tonesHz = doubleArrayOf(520.0),
                toneDurationMs = 45,
            )

            CommandFeedbackKind.CONFIRMED -> CuePattern(
                timingsMs = longArrayOf(0L, 55L, 35L, 70L),
                amplitudes = intArrayOf(0, 180, 0, 230),
                tonesHz = doubleArrayOf(660.0, 880.0),
                toneDurationMs = 65,
            )

            CommandFeedbackKind.CANCELLED -> CuePattern(
                timingsMs = longArrayOf(0L, 40L, 40L, 40L),
                amplitudes = intArrayOf(0, 150, 0, 110),
                tonesHz = doubleArrayOf(600.0, 380.0),
                toneDurationMs = 60,
            )

            CommandFeedbackKind.ERROR -> CuePattern(
                timingsMs = longArrayOf(0L, 90L, 50L, 110L),
                amplitudes = intArrayOf(0, 255, 0, 255),
                tonesHz = doubleArrayOf(210.0, 175.0),
                toneDurationMs = 95,
            )

            CommandFeedbackKind.ATTACK -> CuePattern(
                timingsMs = longArrayOf(0L, 60L, 50L, 60L, 50L, 90L),
                amplitudes = intArrayOf(0, 255, 0, 255, 0, 255),
                tonesHz = doubleArrayOf(960.0, 720.0, 960.0),
                toneDurationMs = 70,
            )
        }
    }
}
