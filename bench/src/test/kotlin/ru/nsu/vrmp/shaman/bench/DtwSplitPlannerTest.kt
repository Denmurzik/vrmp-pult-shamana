package ru.nsu.vrmp.shaman.bench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.nsu.vrmp.shaman.core.input.ImuFrame
import ru.nsu.vrmp.shaman.core.math.Quat
import ru.nsu.vrmp.shaman.core.math.Vec3
import ru.nsu.vrmp.shaman.core.recognition.Gesture

class DtwSplitPlannerTest {

    private fun makeParticipantSession(
        participantId: String,
        sessionId: String,
        counts: Map<Gesture, Int>,
    ): RecordingSession {
        val frames = mutableListOf<ImuFrame>()
        val labels = mutableListOf<LabelInterval>()
        var t = 1_000_000_000L
        for ((gesture, count) in counts) {
            repeat(count) {
                val start = t
                val mid = t + 100_000_000L
                val end = t + 200_000_000L
                frames += ImuFrame(start, Vec3.ZERO, Vec3.ZERO, Quat.IDENTITY)
                frames += ImuFrame(mid, Vec3.UNIT_Y, Vec3.ZERO, Quat.IDENTITY)
                frames += ImuFrame(end, Vec3.ZERO, Vec3.ZERO, Quat.IDENTITY)
                labels += LabelInterval(start, end, gesture, GestureSpeed.NORMAL)
                t += 1_000_000_000L
            }
        }
        return RecordingSession(
            sessionId = sessionId,
            meta = SessionMeta(sessionId, participantId, "dataset"),
            frames = frames,
            labels = labels,
        )
    }

    private fun makeNullSession(participantId: String, sessionId: String) = RecordingSession(
        sessionId = sessionId,
        meta = SessionMeta(sessionId, participantId, "null"),
        frames = listOf(
            ImuFrame(0L, Vec3.ZERO, Vec3.ZERO, Quat.IDENTITY),
            ImuFrame(60_000_000_000L, Vec3.ZERO, Vec3.ZERO, Quat.IDENTITY),
        ),
        labels = emptyList(),
    )

    @Test
    fun `personal mode takes first 5 per gesture as templates and excludes them from eval without leakage`() {
        val p1 = makeParticipantSession("P01", "p1_s1", mapOf(Gesture.CATCH to 7, Gesture.BANISH to 6))
        val p1Null = makeNullSession("P01", "p1_null")
        val p2 = makeParticipantSession("P02", "p2_s1", mapOf(Gesture.CATCH to 6, Gesture.CANCEL to 5))

        val folds = DtwSplitPlanner.plan(listOf(p1, p1Null, p2), EvaluationMode.PERSONAL, personalTemplatesPerGesture = 5)
        assertEquals(2, folds.size)

        val f1 = folds.first { it.name == "personal_P01" }
        assertEquals(5, f1.templates.count { it.label.gesture == Gesture.CATCH })
        assertEquals(5, f1.templates.count { it.label.gesture == Gesture.BANISH })
        assertTrue(f1.templates.all { it.participantId == "P01" })
        assertTrue(f1.templates.all { it.frames.size == 3 })

        // Remaining eval labels for P01: 2 CATCH + 1 BANISH, plus null session retained
        val evalLabels = f1.evalSessions.flatMap { it.labels }
        assertEquals(2, evalLabels.count { it.gesture == Gesture.CATCH })
        assertEquals(1, evalLabels.count { it.gesture == Gesture.BANISH })
        assertTrue(f1.evalSessions.any { it.isNullStream && it.sessionId == "p1_null" })

        // Strict no-leakage invariant: no template label or frame timestamp appears in non-null eval sessions
        val templateRanges = f1.templates.map { it.label.tStartNs..it.label.tEndNs }
        val nonNullEvalFrames = f1.evalSessions.filterNot { it.isNullStream }.flatMap { it.frames }
        assertTrue(nonNullEvalFrames.none { frame -> templateRanges.any { frame.tNanos in it } })
    }

    @Test
    fun `loso mode holds out each participant and uses only other participants as templates`() {
        val p1 = makeParticipantSession("P01", "p1_s1", mapOf(Gesture.CATCH to 2))
        val p2 = makeParticipantSession("P02", "p2_s1", mapOf(Gesture.CATCH to 3))
        val p3 = makeParticipantSession("P03", "p3_s1", mapOf(Gesture.BANISH to 2))

        val folds = DtwSplitPlanner.plan(listOf(p1, p2, p3), EvaluationMode.LOSO)
        assertEquals(3, folds.size)

        val f1 = folds.first { it.name == "loso_P01" }
        assertTrue(f1.evalSessions.all { it.meta.participantId == "P01" })
        assertEquals(1, f1.evalSessions.size)
        // Templates come strictly from P02 (3) + P03 (2) = 5, zero from P01
        assertEquals(5, f1.templates.size)
        assertTrue(f1.templates.none { it.participantId == "P01" })
    }
}
