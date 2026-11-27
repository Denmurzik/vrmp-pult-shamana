package ru.nsu.vrmp.shaman.bench

import ru.nsu.vrmp.shaman.core.recognition.Gesture
import ru.nsu.vrmp.shaman.core.recognition.GestureRecognizer
import ru.nsu.vrmp.shaman.core.recognition.RecognizerEvent
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Streams frames through a [GestureRecognizer] and computes offline benchmark metrics. */
class StreamingEvaluator(
    val toleranceMs: Long = 500L,
) {
    init {
        require(toleranceMs >= 0L) { "toleranceMs must be >= 0, got $toleranceMs" }
    }

    private val toleranceNs: Long = toleranceMs * 1_000_000L

    fun evaluate(
        sessions: List<RecordingSession>,
        recognizer: GestureRecognizer,
        mode: EvaluationMode = EvaluationMode.STANDARD,
    ): EvaluationReport {
        val fold = EvaluationFold(name = mode.cliName, templates = emptyList(), evalSessions = sessions)
        return evaluateFolds(
            folds = listOf(fold),
            recognizerId = recognizer.id,
            mode = mode,
            factory = RecognizerFactory { recognizer },
        )
    }

    fun evaluateFolds(
        folds: List<EvaluationFold>,
        recognizerId: String,
        mode: EvaluationMode,
        factory: RecognizerFactory,
    ): EvaluationReport {
        val confusion = mutableMapOf<Pair<ConfusionLabel, ConfusionLabel>, Int>()
        val speedTp = mutableMapOf<GestureSpeed, Int>().withDefault { 0 }
        val speedFp = mutableMapOf<GestureSpeed, Int>().withDefault { 0 }
        val speedFn = mutableMapOf<GestureSpeed, Int>().withDefault { 0 }
        val selfLatenciesMs = mutableListOf<Double>()
        val endToLabelLatenciesMs = mutableListOf<Double>()
        val cpuFrameUs = mutableListOf<Double>()

        var nullFalsePositives = 0
        var nullDurationNanos = 0L

        for (fold in folds) {
            val recognizer = factory.create(fold.templates)
            for (session in fold.evalSessions) {
                recognizer.reset()
                val detected = mutableListOf<RecognizerEvent.Detected>()

                for (frame in session.frames) {
                    val t0 = System.nanoTime()
                    val events = recognizer.onFrame(frame)
                    val dtNs = System.nanoTime() - t0
                    cpuFrameUs += dtNs / 1_000.0

                    for (event in events) {
                        if (event is RecognizerEvent.Detected) {
                            detected += event
                        }
                    }
                }

                if (session.isNullStream) {
                    nullDurationNanos += session.durationNanos
                    nullFalsePositives += detected.size
                    for (det in detected) {
                        val key = ConfusionLabel.NONE to ConfusionLabel.fromGesture(det.gesture)
                        confusion[key] = (confusion[key] ?: 0) + 1
                    }
                } else {
                    scoreLabeledSession(
                        labels = session.labels,
                        detections = detected,
                        confusion = confusion,
                        speedTp = speedTp,
                        speedFp = speedFp,
                        speedFn = speedFn,
                        selfLatenciesMs = selfLatenciesMs,
                        endToLabelLatenciesMs = endToLabelLatenciesMs,
                    )
                }
            }
        }

        val cm = ConfusionMatrix(confusion.toMap())
        val perClass = Gesture.entries.associateWith { gesture ->
            val label = ConfusionLabel.fromGesture(gesture)
            val tp = cm[label, label]
            val fp = ConfusionLabel.entries.filter { it != label }.sumOf { t -> cm[t, label] }
            val fn = ConfusionLabel.entries.filter { it != label }.sumOf { p -> cm[label, p] }
            computeClassMetrics(tp, fp, fn)
        }

        val macroPrecision = perClass.values.map { it.precision }.average()
        val macroRecall = perClass.values.map { it.recall }.average()
        val macroF1 = perClass.values.map { it.f1 }.average()
        val macro = ClassMetrics(
            tp = perClass.values.sumOf { it.tp },
            fp = perClass.values.sumOf { it.fp },
            fn = perClass.values.sumOf { it.fn },
            precision = macroPrecision,
            recall = macroRecall,
            f1 = macroF1,
        )

        val bySpeed = listOf(GestureSpeed.SLOW, GestureSpeed.NORMAL, GestureSpeed.FAST).associateWith { speed ->
            val tp = speedTp.getValue(speed)
            val fp = speedFp.getValue(speed)
            val fn = speedFn.getValue(speed)
            val m = computeClassMetrics(tp, fp, fn)
            SpeedMetrics(
                speed = speed,
                support = tp + fn,
                tp = tp,
                fp = fp,
                fn = fn,
                precision = m.precision,
                recall = m.recall,
                f1 = m.f1,
            )
        }

        val nullDurationMinutes = nullDurationNanos / 60_000_000_000.0
        val fpPerMinute = if (nullDurationMinutes > 0.0) nullFalsePositives / nullDurationMinutes else 0.0

        val latency = LatencyStats(
            medianMs = percentile(selfLatenciesMs, 0.50),
            p95Ms = percentile(selfLatenciesMs, 0.95),
            endToLabelMedianMs = percentile(endToLabelLatenciesMs, 0.50),
            endToLabelP95Ms = percentile(endToLabelLatenciesMs, 0.95),
        )

        val cpu = CpuStats(
            meanUs = if (cpuFrameUs.isEmpty()) 0.0 else cpuFrameUs.average(),
            p95Us = percentile(cpuFrameUs, 0.95),
            totalFrames = cpuFrameUs.size.toLong(),
        )

        return EvaluationReport(
            recognizerId = recognizerId,
            mode = mode,
            perClass = perClass,
            macro = macro,
            confusionMatrix = cm,
            nullFalsePositives = nullFalsePositives,
            nullDurationMinutes = nullDurationMinutes,
            fpPerMinute = fpPerMinute,
            bySpeed = bySpeed,
            latency = latency,
            cpu = cpu,
        )
    }

    private fun scoreLabeledSession(
        labels: List<LabelInterval>,
        detections: List<RecognizerEvent.Detected>,
        confusion: MutableMap<Pair<ConfusionLabel, ConfusionLabel>, Int>,
        speedTp: MutableMap<GestureSpeed, Int>,
        speedFp: MutableMap<GestureSpeed, Int>,
        speedFn: MutableMap<GestureSpeed, Int>,
        selfLatenciesMs: MutableList<Double>,
        endToLabelLatenciesMs: MutableList<Double>,
    ) {
        val matched = BooleanArray(labels.size)
        val sortedDetections = detections.sortedBy { it.tEmit }

        for (det in sortedDetections) {
            val candidates = labels.indices.filter { idx ->
                if (matched[idx]) return@filter false
                val label = labels[idx]
                det.tEnd >= label.tStartNs - toleranceNs && det.tStart <= label.tEndNs + toleranceNs
            }

            if (candidates.isEmpty()) {
                val key = ConfusionLabel.NONE to ConfusionLabel.fromGesture(det.gesture)
                confusion[key] = (confusion[key] ?: 0) + 1
                val nearest = labels.minByOrNull { distanceToInterval(det.tEnd, it.tStartNs, it.tEndNs) }
                if (nearest != null) {
                    speedFp[nearest.speed] = speedFp.getValue(nearest.speed) + 1
                }
                continue
            }

            val bestIdx = candidates.minWith(
                compareBy<Int>(
                    { if (labels[it].gesture == det.gesture) 0 else 1 },
                    { abs(det.tEnd - labels[it].tEndNs) },
                    { it },
                ),
            )
            matched[bestIdx] = true
            val label = labels[bestIdx]

            val key = ConfusionLabel.fromGesture(label.gesture) to ConfusionLabel.fromGesture(det.gesture)
            confusion[key] = (confusion[key] ?: 0) + 1

            if (label.gesture == det.gesture) {
                selfLatenciesMs += (det.tEmit - det.tEnd) / 1_000_000.0
                endToLabelLatenciesMs += (det.tEmit - label.tEndNs) / 1_000_000.0
                speedTp[label.speed] = speedTp.getValue(label.speed) + 1
            } else {
                speedFn[label.speed] = speedFn.getValue(label.speed) + 1
                speedFp[label.speed] = speedFp.getValue(label.speed) + 1
            }
        }

        for (idx in labels.indices) {
            if (!matched[idx]) {
                val label = labels[idx]
                val key = ConfusionLabel.fromGesture(label.gesture) to ConfusionLabel.NONE
                confusion[key] = (confusion[key] ?: 0) + 1
                speedFn[label.speed] = speedFn.getValue(label.speed) + 1
            }
        }
    }

    private fun distanceToInterval(t: Long, start: Long, end: Long): Long = when {
        t < start -> start - t
        t > end -> t - end
        else -> 0L
    }

    private fun computeClassMetrics(tp: Int, fp: Int, fn: Int): ClassMetrics {
        val precision = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp)
        val recall = if (tp + fn == 0) 0.0 else tp.toDouble() / (tp + fn)
        val f1 = if (precision + recall == 0.0) 0.0 else 2.0 * precision * recall / (precision + recall)
        return ClassMetrics(tp = tp, fp = fp, fn = fn, precision = precision, recall = recall, f1 = f1)
    }

    internal fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val pos = p * (sorted.size - 1)
        val lo = floor(pos).toInt()
        val hi = ceil(pos).toInt()
        val frac = pos - lo
        return sorted[lo] + frac * (sorted[hi] - sorted[lo])
    }
}
