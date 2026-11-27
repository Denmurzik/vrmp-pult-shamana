package ru.nsu.vrmp.shaman.bench

import java.nio.file.Path
import java.util.Locale

data class BenchArgs(
    val inputDir: Path,
    val recognizerId: String,
    val outPath: Path,
    val mode: EvaluationMode = EvaluationMode.STANDARD,
    val toleranceMs: Long = 500L,
    val templatesPerGesture: Int = 5,
)

/** CLI parser and runner for `:bench`. */
object BenchCli {

    const val USAGE: String =
        "Usage: bench --input <sessions-dir> --recognizer <id> --out <dir-or-csv> " +
            "[--mode standard|personal|loso] [--tolerance-ms 500] [--templates-per-gesture 5]"

    fun parseArgs(args: Array<String>): BenchArgs {
        var input: String? = null
        var recognizer: String? = null
        var out: String? = null
        var mode = EvaluationMode.STANDARD
        var toleranceMs = 500L
        var templatesPerGesture = 5

        var i = 0
        while (i < args.size) {
            val flag = args[i]
            fun nextValue(): String {
                require(i + 1 < args.size) { "Missing value for $flag\n$USAGE" }
                return args[++i]
            }
            when (flag) {
                "--input" -> input = nextValue()
                "--recognizer" -> recognizer = nextValue()
                "--out" -> out = nextValue()
                "--mode" -> mode = EvaluationMode.parse(nextValue())
                "--tolerance-ms" -> toleranceMs = nextValue().toLong()
                "--templates-per-gesture" -> templatesPerGesture = nextValue().toInt()
                else -> throw IllegalArgumentException("Unknown argument '$flag'\n$USAGE")
            }
            i++
        }

        require(!input.isNullOrBlank()) { "Missing required --input\n$USAGE" }
        require(!recognizer.isNullOrBlank()) { "Missing required --recognizer\n$USAGE" }
        require(!out.isNullOrBlank()) { "Missing required --out\n$USAGE" }

        return BenchArgs(
            inputDir = Path.of(input),
            recognizerId = recognizer.trim().lowercase(),
            outPath = Path.of(out),
            mode = mode,
            toleranceMs = toleranceMs,
            templatesPerGesture = templatesPerGesture,
        )
    }

    fun run(
        args: Array<String>,
        registry: RecognizerRegistry = RecognizerRegistry.default(),
    ): EvaluationReport {
        val parsed = parseArgs(args)
        val factory = registry.factoryFor(parsed.recognizerId)
        val sessions = RecordingLoader.loadDataset(parsed.inputDir)
        val folds = DtwSplitPlanner.plan(
            sessions = sessions,
            mode = parsed.mode,
            personalTemplatesPerGesture = parsed.templatesPerGesture,
        )
        val evaluator = StreamingEvaluator(toleranceMs = parsed.toleranceMs)
        val report = evaluator.evaluateFolds(
            folds = folds,
            recognizerId = parsed.recognizerId,
            mode = parsed.mode,
            factory = factory,
        )
        CsvReportWriter.write(report, parsed.outPath)
        return report
    }

    fun formatSummary(report: EvaluationReport): String = String.format(
        Locale.US,
        "bench[%s/%s]: macro_f1=%.4f (P=%.4f R=%.4f) fp_per_min=%.3f latency_med=%.2fms cpu_mean=%.2fus frames=%d",
        report.recognizerId,
        report.mode.cliName,
        report.macro.f1,
        report.macro.precision,
        report.macro.recall,
        report.fpPerMinute,
        report.latency.medianMs,
        report.cpu.meanUs,
        report.cpu.totalFrames,
    )
}
