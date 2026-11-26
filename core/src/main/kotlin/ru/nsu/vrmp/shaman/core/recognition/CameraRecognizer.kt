package ru.nsu.vrmp.shaman.core.recognition

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import ru.nsu.vrmp.shaman.core.input.HandFrame
import ru.nsu.vrmp.shaman.core.input.InputFrame
import ru.nsu.vrmp.shaman.core.math.Vec3

/** Recognizes discrete commands from one hand tracked by MediaPipe. */
class CameraRecognizer : GestureRecognizer {
    override val id: String = "camera"

    private var lastHandAt: Long? = null
    private var fistArmed = false
    private var candidate: RecognizerEvent.Candidate? = null
    private val motion = ArrayDeque<MotionSample>()

    override fun reset() {
        lastHandAt = null
        fistArmed = false
        candidate = null
        motion.clear()
    }

    override fun onFrame(frame: InputFrame): List<RecognizerEvent> {
        if (frame !is HandFrame) return emptyList()
        val time = frame.tNanos
        val events = mutableListOf<RecognizerEvent>()
        if (lastHandAt?.let { time - it > HAND_LOSS_NS } == true) {
            events.rejectCandidate(time, "hand lost")
            reset()
        }
        if (!frame.present || frame.landmarks.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) {
            return events
        }

        val points = frame.landmarks
        val palmLength = (points[MIDDLE_MCP] - points[WRIST]).norm()
        if (palmLength <= MIN_PALM_LENGTH) return events
        lastHandAt = time
        val curls = FINGER_BASES.map { fingerCurl(points, it) }
        val curl = curls.min()
        val openHand = curls.max() <= OPEN_HAND_CURL
        if (curl <= OPEN_HAND_CURL && candidate?.gesture == Gesture.CATCH) {
            events.rejectCandidate(time, "hand reopened")
            fistArmed = false
        }
        if (openHand) fistArmed = true
        if (candidate?.let { it.gesture == Gesture.CATCH && time - it.tNanos > FIST_WINDOW_NS } == true) {
            events.rejectCandidate(time, "fist timed out")
            fistArmed = false
        }
        if (fistArmed && curl >= FIST_CANDIDATE_CURL) {
            events.startCandidate(Gesture.CATCH, time)
            motion.clear()
            if (curl >= CLOSED_HAND_CURL) {
                events.detect(Gesture.CATCH, curl, checkNotNull(candidate).tNanos, time)
                fistArmed = false
            }
            return events
        }
        if (candidate?.gesture == Gesture.CATCH || curl >= CLOSED_HAND_CURL) return events

        candidate?.let {
            val window = if (it.gesture == Gesture.CANCEL) WAVE_WINDOW_NS else CIRCLE_WINDOW_NS
            if (time - it.tNanos > window) events.rejectCandidate(time, "motion timed out")
        }
        val palm = (FINGER_BASES.fold(points[WRIST]) { sum, index -> sum + points[index] } / 5.0).copy(z = 0.0)
        val sample = MotionSample(time, palm, points[INDEX_TIP].copy(z = 0.0))
        if (!recordMotion(sample, palmLength)) return events

        val wave = waveEvidence(time, palmLength)
        val circle = if (wave?.complete == true) null else circleEvidence(palmLength)
        val confirmed = wave?.takeIf { it.complete } ?: circle?.takeIf { it.complete }
        if (confirmed != null) {
            events.startCandidate(confirmed.gesture, time)
            events.detect(confirmed.gesture, confirmed.confidence, confirmed.startedAt, time)
            fistArmed = false
            motion.clear()
        } else if (candidate == null) {
            (wave ?: circle)?.let { events.startCandidate(it.gesture, time) }
        }
        return events
    }

    private fun MutableList<RecognizerEvent>.startCandidate(gesture: Gesture, time: Long) {
        if (candidate?.gesture == gesture) return
        rejectCandidate(time, "another gesture recognized")
        val next = RecognizerEvent.Candidate(gesture, time)
        candidate = next
        add(next)
    }

    private fun MutableList<RecognizerEvent>.rejectCandidate(time: Long, reason: String) {
        if (candidate != null) add(RecognizerEvent.Rejected(time, reason))
        candidate = null
    }

    private fun MutableList<RecognizerEvent>.detect(gesture: Gesture, confidence: Double, start: Long, time: Long) {
        add(RecognizerEvent.Detected(gesture, confidence, start, time, time))
        candidate = null
    }

    private fun fingerCurl(points: List<Vec3>, base: Int): Double {
        val length = (base until base + 3).sumOf { (points[it + 1] - points[it]).norm() }
        if (length == 0.0) return 0.0
        return (1.0 - (points[base + 3] - points[base]).norm() / length).coerceIn(0.0, 1.0)
    }

    private fun recordMotion(sample: MotionSample, palmLength: Double): Boolean {
        while (motion.isNotEmpty() && sample.time - motion.first().time > CIRCLE_WINDOW_NS) {
            motion.removeFirst()
        }
        val previous = motion.lastOrNull()
        if (previous != null) {
            val minStep = palmLength * MOTION_STEP_RATIO
            if ((sample.palm - previous.palm).norm() < minStep && (sample.tip - previous.tip).norm() < minStep) {
                if (motion.size == 1) motion[0] = sample
                return false
            }
            if (previous.time == sample.time) motion.removeLast()
        }
        motion.addLast(sample)
        return true
    }

    private fun waveEvidence(time: Long, palmLength: Double): MotionEvidence? {
        val samples = motion.dropWhile { time - it.time > WAVE_WINDOW_NS }
        val start = samples.first()
        val minStep = palmLength * WAVE_STEP_RATIO
        var direction = 0
        var extreme = start.palm.x
        var reversals = 0
        for (sample in samples) {
            val x = sample.palm.x
            val delta = x - extreme
            if (direction == 0) {
                if (abs(delta) < minStep) continue
                direction = if (delta > 0) 1 else -1
                extreme = x
            } else if (delta * direction >= 0) {
                extreme = x
            } else if (abs(delta) >= minStep) {
                direction = -direction
                extreme = x
                reversals++
            }
        }
        if (reversals == 0) return null
        val width = samples.maxOf { it.palm.x } - samples.minOf { it.palm.x }
        val height = samples.maxOf { it.palm.y } - samples.minOf { it.palm.y }
        if (height > width * MAX_WAVE_VERTICAL_RATIO) return null
        return MotionEvidence(Gesture.CANCEL, start.time, reversals >= 2, 1.0)
    }

    private fun circleEvidence(palmLength: Double): MotionEvidence? {
        var partial: MotionEvidence? = null
        for (start in 0..(motion.size - MIN_CIRCLE_SAMPLES)) {
            val samples = motion.subList(start, motion.size)
            val width = samples.maxOf { it.tip.x } - samples.minOf { it.tip.x }
            val height = samples.maxOf { it.tip.y } - samples.minOf { it.tip.y }
            if (minOf(width, height) < palmLength * MIN_CIRCLE_SPAN_RATIO) continue
            if (minOf(width, height) < maxOf(width, height) * MIN_CIRCLE_ASPECT) continue

            val center = samples.fold(Vec3.ZERO) { sum, sample -> sum + sample.tip } / samples.size.toDouble()
            var previous = samples.first().tip - center
            var shortestRadius = previous.norm()
            var radiusSum = shortestRadius
            var winding = 0.0
            var totalTurn = 0.0
            for (i in 1 until samples.size) {
                val current = samples[i].tip - center
                val radius = current.norm()
                shortestRadius = minOf(shortestRadius, radius)
                radiusSum += radius
                val turn = atan2(previous.x * current.y - previous.y * current.x, previous dot current)
                winding += turn
                totalTurn += abs(turn)
                previous = current
            }
            val meanRadius = radiusSum / samples.size
            if (shortestRadius < meanRadius * MIN_CIRCLE_RADIUS_RATIO) continue
            val angle = abs(winding)
            if (angle < CIRCLE_CANDIDATE_ANGLE || angle < totalTurn * MIN_TURN_CONSISTENCY) continue
            val endGap = (samples.last().tip - samples.first().tip).norm()
            val closed = angle >= MIN_CIRCLE_ANGLE && endGap <= meanRadius * MAX_CIRCLE_GAP_RATIO
            val evidence = MotionEvidence(Gesture.BANISH, samples.first().time, closed, (angle / (2 * PI)).coerceAtMost(1.0))
            if (closed) return evidence
            if (partial == null) partial = evidence
        }
        return partial
    }

    private data class MotionSample(val time: Long, val palm: Vec3, val tip: Vec3)

    private data class MotionEvidence(
        val gesture: Gesture,
        val startedAt: Long,
        val complete: Boolean,
        val confidence: Double,
    )

    private companion object {
        const val WRIST = 0
        const val INDEX_TIP = 8
        const val MIDDLE_MCP = 9
        val FINGER_BASES = intArrayOf(5, MIDDLE_MCP, 13, 17)
        const val FIST_WINDOW_NS = 700_000_000L
        const val HAND_LOSS_NS = 300_000_000L
        const val WAVE_WINDOW_NS = 800_000_000L
        const val CIRCLE_WINDOW_NS = 1_500_000_000L
        const val OPEN_HAND_CURL = 0.12
        const val FIST_CANDIDATE_CURL = 0.17
        const val CLOSED_HAND_CURL = 0.45
        const val MIN_PALM_LENGTH = 1e-6
        const val MOTION_STEP_RATIO = 0.045
        const val WAVE_STEP_RATIO = 0.4
        const val MAX_WAVE_VERTICAL_RATIO = 0.7
        const val MIN_CIRCLE_SAMPLES = 8
        const val MIN_CIRCLE_SPAN_RATIO = 0.5
        const val MIN_CIRCLE_ASPECT = 0.45
        const val MIN_CIRCLE_RADIUS_RATIO = 0.35
        const val CIRCLE_CANDIDATE_ANGLE = PI * 0.9
        const val MIN_CIRCLE_ANGLE = PI * 5.0 / 3.0
        const val MIN_TURN_CONSISTENCY = 0.8
        const val MAX_CIRCLE_GAP_RATIO = 1.15
    }
}
