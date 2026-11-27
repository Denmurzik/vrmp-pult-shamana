package ru.nsu.vrmp.shaman.bench

import ru.nsu.vrmp.shaman.core.recognition.Gesture

/** Builds leak-free train/test folds for STANDARD, PERSONAL, and LOSO evaluation modes. */
object DtwSplitPlanner {

    fun plan(
        sessions: List<RecordingSession>,
        mode: EvaluationMode,
        personalTemplatesPerGesture: Int = 5,
    ): List<EvaluationFold> {
        require(personalTemplatesPerGesture >= 0) {
            "personalTemplatesPerGesture must be >= 0, got $personalTemplatesPerGesture"
        }
        return when (mode) {
            EvaluationMode.STANDARD -> listOf(
                EvaluationFold(
                    name = "standard",
                    templates = emptyList(),
                    evalSessions = sessions,
                ),
            )
            EvaluationMode.PERSONAL -> planPersonal(sessions, personalTemplatesPerGesture)
            EvaluationMode.LOSO -> planLoso(sessions)
        }
    }

    private fun planPersonal(
        sessions: List<RecordingSession>,
        k: Int,
    ): List<EvaluationFold> {
        val byParticipant = sessions.groupBy { it.meta.participantId }.toSortedMap()
        return byParticipant.map { (participantId, pSessions) ->
            val takenCount = mutableMapOf<Gesture, Int>().withDefault { 0 }
            val templates = mutableListOf<TemplateSegment>()
            val evalSessions = mutableListOf<RecordingSession>()

            for (session in pSessions.sortedBy { it.sessionId }) {
                if (session.isNullStream) {
                    evalSessions += session
                    continue
                }

                val templateLabels = mutableListOf<LabelInterval>()
                val remainingLabels = mutableListOf<LabelInterval>()

                for (label in session.labels.sortedBy { it.tStartNs }) {
                    val count = takenCount.getValue(label.gesture)
                    if (count < k) {
                        takenCount[label.gesture] = count + 1
                        templateLabels += label
                        val segFrames = session.frames.filter { it.tNanos in label.tStartNs..label.tEndNs }
                        templates += TemplateSegment(
                            participantId = participantId,
                            sessionId = session.sessionId,
                            label = label,
                            frames = segFrames,
                        )
                    } else {
                        remainingLabels += label
                    }
                }

                if (remainingLabels.isNotEmpty()) {
                    val excludedRanges = templateLabels.map { it.tStartNs..it.tEndNs }
                    val evalFrames = if (excludedRanges.isEmpty()) {
                        session.frames
                    } else {
                        session.frames.filter { frame -> excludedRanges.none { frame.tNanos in it } }
                    }
                    evalSessions += session.copy(frames = evalFrames, labels = remainingLabels)
                }
            }

            EvaluationFold(
                name = "personal_$participantId",
                templates = templates,
                evalSessions = evalSessions,
            )
        }
    }

    private fun planLoso(sessions: List<RecordingSession>): List<EvaluationFold> {
        val byParticipant = sessions.groupBy { it.meta.participantId }.toSortedMap()
        val segmentsByParticipant = byParticipant.mapValues { (participantId, pSessions) ->
            pSessions.sortedBy { it.sessionId }.flatMap { session ->
                session.labels.sortedBy { it.tStartNs }.map { label ->
                    TemplateSegment(
                        participantId = participantId,
                        sessionId = session.sessionId,
                        label = label,
                        frames = session.frames.filter { it.tNanos in label.tStartNs..label.tEndNs },
                    )
                }
            }
        }

        return byParticipant.map { (heldOutParticipant, heldOutSessions) ->
            val templates = segmentsByParticipant
                .filterKeys { it != heldOutParticipant }
                .values
                .flatten()
            EvaluationFold(
                name = "loso_$heldOutParticipant",
                templates = templates,
                evalSessions = heldOutSessions.sortedBy { it.sessionId },
            )
        }
    }
}
