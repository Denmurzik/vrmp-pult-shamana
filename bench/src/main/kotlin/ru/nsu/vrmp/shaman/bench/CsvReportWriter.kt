package ru.nsu.vrmp.shaman.bench

import ru.nsu.vrmp.shaman.core.recognition.Gesture
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** Writes [EvaluationReport] tables (`metrics.csv`, `confusion_matrix.csv`, `speed_f1.csv`) using `Locale.US`. */
object CsvReportWriter {

    fun write(report: EvaluationReport, outPath: Path): List<Path> {
        val (outDir, metricsFile) = if (outPath.fileName.toString().endsWith(".csv", ignoreCase = true)) {
            val parent = outPath.toAbsolutePath().parent
            parent to outPath
        } else {
            outPath to outPath.resolve("metrics.csv")
        }
        Files.createDirectories(outDir)

        val confusionFile = outDir.resolve("confusion_matrix.csv")
        val speedFile = outDir.resolve("speed_f1.csv")

        Files.writeString(metricsFile, formatMetricsCsv(report))
        Files.writeString(confusionFile, formatConfusionCsv(report.confusionMatrix))
        Files.writeString(speedFile, formatSpeedCsv(report))

        return listOf(metricsFile, confusionFile, speedFile)
    }

    private fun formatMetricsCsv(report: EvaluationReport): String = buildString {
        appendLine(
            "recognizer,mode,class,tp,fp,fn,precision,recall,f1," +
                "fp_per_min,null_duration_min,latency_median_ms,latency_p95_ms," +
                "end_to_label_median_ms,end_to_label_p95_ms,cpu_mean_us,cpu_p95_us,total_frames",
        )
        for (gesture in Gesture.entries) {
            val m = report.perClass.getValue(gesture)
            appendMetricsRow(report, gesture.name, m)
        }
        appendMetricsRow(report, "MACRO", report.macro)
    }

    private fun StringBuilder.appendMetricsRow(
        report: EvaluationReport,
        label: String,
        m: ClassMetrics,
    ) {
        appendLine(
            listOf(
                report.recognizerId,
                report.mode.cliName,
                label,
                m.tp.toString(),
                m.fp.toString(),
                m.fn.toString(),
                fmt(m.precision),
                fmt(m.recall),
                fmt(m.f1),
                fmt(report.fpPerMinute),
                fmt(report.nullDurationMinutes),
                fmt(report.latency.medianMs),
                fmt(report.latency.p95Ms),
                fmt(report.latency.endToLabelMedianMs),
                fmt(report.latency.endToLabelP95Ms),
                fmt(report.cpu.meanUs),
                fmt(report.cpu.p95Us),
                report.cpu.totalFrames.toString(),
            ).joinToString(","),
        )
    }

    private fun formatConfusionCsv(cm: ConfusionMatrix): String = buildString {
        appendLine("true_class,${ConfusionLabel.entries.joinToString(",") { it.name }}")
        for (trueLabel in ConfusionLabel.entries) {
            val row = ConfusionLabel.entries.joinToString(",") { predLabel ->
                cm[trueLabel, predLabel].toString()
            }
            appendLine("${trueLabel.name},$row")
        }
    }

    private fun formatSpeedCsv(report: EvaluationReport): String = buildString {
        appendLine("recognizer,mode,speed,support,tp,fp,fn,precision,recall,f1")
        for (speed in listOf(GestureSpeed.SLOW, GestureSpeed.NORMAL, GestureSpeed.FAST)) {
            val m = report.bySpeed.getValue(speed)
            appendLine(
                listOf(
                    report.recognizerId,
                    report.mode.cliName,
                    speed.wireName,
                    m.support.toString(),
                    m.tp.toString(),
                    m.fp.toString(),
                    m.fn.toString(),
                    fmt(m.precision),
                    fmt(m.recall),
                    fmt(m.f1),
                ).joinToString(","),
            )
        }
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.6f", value)
}
