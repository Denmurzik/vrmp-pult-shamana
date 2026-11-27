package ru.nsu.vrmp.shaman.bench

import ru.nsu.vrmp.shaman.core.input.InputFrame
import ru.nsu.vrmp.shaman.core.recognition.Gesture

enum class EvaluationMode(val cliName: String) {
    STANDARD("standard"),
    PERSONAL("personal"),
    LOSO("loso");

    companion object {
        fun parse(raw: String): EvaluationMode =
            entries.firstOrNull { it.cliName.equals(raw.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown mode '$raw'. Expected one of: ${entries.joinToString { it.cliName }}")
    }
}

/** One extracted labeled segment used as a template for DTW personal/LOSO evaluation. */
data class TemplateSegment(
    val participantId: String,
    val sessionId: String,
    val label: LabelInterval,
    val frames: List<InputFrame>,
)

/** One evaluation fold consisting of template segments (train) and held-out sessions (test). */
data class EvaluationFold(
    val name: String,
    val templates: List<TemplateSegment>,
    val evalSessions: List<RecordingSession>,
)

/** Confusion matrix label including NONE (miss or false positive). */
enum class ConfusionLabel {
    CATCH, BANISH, CANCEL, NONE;

    companion object {
        fun fromGesture(gesture: Gesture?): ConfusionLabel = when (gesture) {
            Gesture.CATCH -> CATCH
            Gesture.BANISH -> BANISH
            Gesture.CANCEL -> CANCEL
            null -> NONE
        }
    }
}

/** Confusion matrix indexed by (trueLabel, predictedLabel). */
data class ConfusionMatrix(
    val counts: Map<Pair<ConfusionLabel, ConfusionLabel>, Int>,
) {
    operator fun get(trueLabel: ConfusionLabel, predictedLabel: ConfusionLabel): Int =
        counts[trueLabel to predictedLabel] ?: 0
}

/** Precision / recall / F1 metrics for a single gesture class or macro average. */
data class ClassMetrics(
    val tp: Int,
    val fp: Int,
    val fn: Int,
    val precision: Double,
    val recall: Double,
    val f1: Double,
)

/** Metrics sliced by gesture execution speed. */
data class SpeedMetrics(
    val speed: GestureSpeed,
    val support: Int,
    val tp: Int,
    val fp: Int,
    val fn: Int,
    val precision: Double,
    val recall: Double,
    val f1: Double,
)

/** Recognition latency statistics in milliseconds. */
data class LatencyStats(
    val medianMs: Double,
    val p95Ms: Double,
    val endToLabelMedianMs: Double,
    val endToLabelP95Ms: Double,
)

/** Per-frame CPU time statistics in microseconds. */
data class CpuStats(
    val meanUs: Double,
    val p95Us: Double,
    val totalFrames: Long,
)

/** Aggregated offline benchmark evaluation report. */
data class EvaluationReport(
    val recognizerId: String,
    val mode: EvaluationMode,
    val perClass: Map<Gesture, ClassMetrics>,
    val macro: ClassMetrics,
    val confusionMatrix: ConfusionMatrix,
    val nullFalsePositives: Int,
    val nullDurationMinutes: Double,
    val fpPerMinute: Double,
    val bySpeed: Map<GestureSpeed, SpeedMetrics>,
    val latency: LatencyStats,
    val cpu: CpuStats,
)
