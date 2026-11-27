package ru.nsu.vrmp.shaman.bench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.nsu.vrmp.shaman.core.input.ImuFrame
import ru.nsu.vrmp.shaman.core.input.InputFrame
import ru.nsu.vrmp.shaman.core.math.Quat
import ru.nsu.vrmp.shaman.core.math.Vec3
import ru.nsu.vrmp.shaman.core.recognition.Gesture
import ru.nsu.vrmp.shaman.core.recognition.GestureRecognizer
import ru.nsu.vrmp.shaman.core.recognition.RecognizerEvent

class StreamingEvaluatorTest {

    private class ScriptedRecognizer(
        override val id: String = "scripted",
        private val eventsByTimestamp: Map<Long, List<RecognizerEvent>>,
    ) : GestureRecognizer {
        var resetCount = 0
            private set

        override fun reset() {
            resetCount++
        }

        override fun onFrame(frame: InputFrame): List<RecognizerEvent> =
            eventsByTimestamp[frame.tNanos].orEmpty()
    }

    private fun imuFrame(tMs: Long) = ImuFrame(
        tNanos = tMs * 1_000_000L,
        linAcc = Vec3.ZERO,
        gyro = Vec3.ZERO,
        rotation = Quat.IDENTITY,
    )

    @Test
    fun `computes exact precision recall f1 confusion matrix duplicate FP speed f1 and latency`() {
        val ms = 1_000_000L
        val labels = listOf(
            LabelInterval(1_000 * ms, 1_500 * ms, Gesture.CATCH, GestureSpeed.SLOW),
            LabelInterval(4_000 * ms, 4_500 * ms, Gesture.CATCH, GestureSpeed.NORMAL),
            LabelInterval(7_000 * ms, 7_500 * ms, Gesture.BANISH, GestureSpeed.FAST),
            LabelInterval(10_000 * ms, 10_500 * ms, Gesture.BANISH, GestureSpeed.NORMAL),
            LabelInterval(13_000 * ms, 13_500 * ms, Gesture.CANCEL, GestureSpeed.SLOW),
        )
        val frames = listOf(1_600L, 1_700L, 4_550L, 7_580L, 10_600L, 13_540L).map { imuFrame(it) }

        val events = mapOf(
            // TP CATCH #1: self latency = 1600 - 1550 = 50ms, endToLabel = 1600 - 1500 = 100ms
            1_600 * ms to listOf(
                RecognizerEvent.Detected(Gesture.CATCH, 0.9, 1_050 * ms, 1_550 * ms, 1_600 * ms),
            ),
            // Duplicate CATCH inside same interval -> FP CATCH (NONE -> CATCH)
            1_700 * ms to listOf(
                RecognizerEvent.Detected(Gesture.CATCH, 0.8, 1_200 * ms, 1_650 * ms, 1_700 * ms),
            ),
            // Misclassification on CATCH #2 -> predicted BANISH (CATCH -> BANISH)
            4_550 * ms to listOf(
                RecognizerEvent.Detected(Gesture.BANISH, 0.7, 4_100 * ms, 4_500 * ms, 4_550 * ms),
            ),
            // TP BANISH #1: self latency = 7580 - 7550 = 30ms, endToLabel = 7580 - 7500 = 80ms
            7_580 * ms to listOf(
                RecognizerEvent.Detected(Gesture.BANISH, 0.95, 7_050 * ms, 7_550 * ms, 7_580 * ms),
            ),
            // BANISH #2 missed -> (BANISH -> NONE)
            // TP CANCEL #1: self latency = 13540 - 13520 = 20ms, endToLabel = 13540 - 13500 = 40ms
            13_540 * ms to listOf(
                RecognizerEvent.Detected(Gesture.CANCEL, 0.99, 13_050 * ms, 13_520 * ms, 13_540 * ms),
            ),
        )

        val session = RecordingSession(
            sessionId = "eval_01",
            meta = SessionMeta("eval_01", "P01", "dataset"),
            frames = frames,
            labels = labels,
        )

        val recognizer = ScriptedRecognizer(eventsByTimestamp = events)
        val evaluator = StreamingEvaluator(toleranceMs = 500L)
        val report = evaluator.evaluate(listOf(session), recognizer)

        assertEquals(1, recognizer.resetCount)

        // Per-class metrics
        val catchMetrics = report.perClass.getValue(Gesture.CATCH)
        assertEquals(1, catchMetrics.tp)
        assertEquals(1, catchMetrics.fp)
        assertEquals(1, catchMetrics.fn)
        assertEquals(0.5, catchMetrics.precision, 1e-9)
        assertEquals(0.5, catchMetrics.recall, 1e-9)
        assertEquals(0.5, catchMetrics.f1, 1e-9)

        val banishMetrics = report.perClass.getValue(Gesture.BANISH)
        assertEquals(1, banishMetrics.tp)
        assertEquals(1, banishMetrics.fp)
        assertEquals(1, banishMetrics.fn)
        assertEquals(0.5, banishMetrics.precision, 1e-9)
        assertEquals(0.5, banishMetrics.recall, 1e-9)
        assertEquals(0.5, banishMetrics.f1, 1e-9)

        val cancelMetrics = report.perClass.getValue(Gesture.CANCEL)
        assertEquals(1, cancelMetrics.tp)
        assertEquals(0, cancelMetrics.fp)
        assertEquals(0, cancelMetrics.fn)
        assertEquals(1.0, cancelMetrics.precision, 1e-9)
        assertEquals(1.0, cancelMetrics.recall, 1e-9)
        assertEquals(1.0, cancelMetrics.f1, 1e-9)

        // Macro average: (0.5 + 0.5 + 1.0) / 3 = 2/3
        assertEquals(2.0 / 3.0, report.macro.precision, 1e-9)
        assertEquals(2.0 / 3.0, report.macro.recall, 1e-9)
        assertEquals(2.0 / 3.0, report.macro.f1, 1e-9)

        // Confusion matrix
        val cm = report.confusionMatrix
        assertEquals(1, cm[ConfusionLabel.CATCH, ConfusionLabel.CATCH])
        assertEquals(1, cm[ConfusionLabel.NONE, ConfusionLabel.CATCH])
        assertEquals(1, cm[ConfusionLabel.CATCH, ConfusionLabel.BANISH])
        assertEquals(1, cm[ConfusionLabel.BANISH, ConfusionLabel.BANISH])
        assertEquals(1, cm[ConfusionLabel.BANISH, ConfusionLabel.NONE])
        assertEquals(1, cm[ConfusionLabel.CANCEL, ConfusionLabel.CANCEL])

        // Speed F1:
        // SLOW: 2 labels (CATCH #1 TP, CANCEL #1 TP) + 1 duplicate FP attributed to nearest SLOW -> TP=2, FP=1, FN=0 -> P=2/3, R=1, F1=0.8
        val slow = report.bySpeed.getValue(GestureSpeed.SLOW)
        assertEquals(2, slow.support)
        assertEquals(2, slow.tp)
        assertEquals(1, slow.fp)
        assertEquals(0, slow.fn)
        assertEquals(0.8, slow.f1, 1e-9)

        // FAST: 1 label (BANISH #1 TP) -> TP=1, FP=0, FN=0 -> F1=1.0
        val fast = report.bySpeed.getValue(GestureSpeed.FAST)
        assertEquals(1, fast.support)
        assertEquals(1, fast.tp)
        assertEquals(1.0, fast.f1, 1e-9)

        // NORMAL: 2 labels (CATCH #2 misclassified, BANISH #2 missed) -> TP=0, FN=2 -> F1=0.0
        val normal = report.bySpeed.getValue(GestureSpeed.NORMAL)
        assertEquals(2, normal.support)
        assertEquals(0, normal.tp)
        assertEquals(2, normal.fn)
        assertEquals(0.0, normal.f1, 1e-9)

        // Latencies: self = [20, 30, 50] -> median 30, p95 48; endToLabel = [40, 80, 100] -> median 80, p95 98
        assertEquals(30.0, report.latency.medianMs, 1e-9)
        assertEquals(48.0, report.latency.p95Ms, 1e-9)
        assertEquals(80.0, report.latency.endToLabelMedianMs, 1e-9)
        assertEquals(98.0, report.latency.endToLabelP95Ms, 1e-9)

        // CPU stats
        assertEquals(6L, report.cpu.totalFrames)
        assertTrue(report.cpu.meanUs >= 0.0)
        assertTrue(report.cpu.p95Us >= 0.0)
    }

    @Test
    fun `computes false positives per minute on null streams`() {
        val ms = 1_000_000L
        // Session 1: 90 seconds (1.5 min), 2 FPs
        val null1 = RecordingSession(
            sessionId = "null_1",
            meta = SessionMeta("null_1", "P01", "null"),
            frames = listOf(imuFrame(0L), imuFrame(30_000L), imuFrame(60_000L), imuFrame(90_000L)),
            labels = emptyList(),
        )
        // Session 2: 30 seconds (0.5 min), 1 FP
        val null2 = RecordingSession(
            sessionId = "null_2",
            meta = SessionMeta("null_2", "P01", "null"),
            frames = listOf(imuFrame(0L), imuFrame(15_000L), imuFrame(30_000L)),
            labels = emptyList(),
        )

        val events = mapOf(
            15_000 * ms to listOf(RecognizerEvent.Detected(Gesture.CATCH, 0.6, 14_900 * ms, 15_000 * ms, 15_000 * ms)),
            30_000 * ms to listOf(RecognizerEvent.Detected(Gesture.BANISH, 0.6, 29_900 * ms, 30_000 * ms, 30_000 * ms)),
            60_000 * ms to listOf(RecognizerEvent.Detected(Gesture.CANCEL, 0.6, 59_900 * ms, 60_000 * ms, 60_000 * ms)),
        )
        // Note: null1 has timestamps 30_000 and 60_000 (2 FPs), null2 has 15_000 and 30_000 (2 FPs) -> let's use distinct timestamps
        val distinctEvents = mapOf(
            15_000 * ms to listOf(RecognizerEvent.Detected(Gesture.CATCH, 0.6, 14_900 * ms, 15_000 * ms, 15_000 * ms)),
            60_000 * ms to listOf(RecognizerEvent.Detected(Gesture.BANISH, 0.6, 59_900 * ms, 60_000 * ms, 60_000 * ms)),
            90_000 * ms to listOf(RecognizerEvent.Detected(Gesture.CANCEL, 0.6, 89_900 * ms, 90_000 * ms, 90_000 * ms)),
        )

        val recognizer = ScriptedRecognizer(eventsByTimestamp = distinctEvents)
        val report = StreamingEvaluator().evaluate(listOf(null1, null2), recognizer)

        assertEquals(2, recognizer.resetCount)
        assertEquals(3, report.nullFalsePositives)
        assertEquals(2.0, report.nullDurationMinutes, 1e-9)
        assertEquals(1.5, report.fpPerMinute, 1e-9)
    }
}
