package ru.nsu.vrmp.shaman.core.experiment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExperimentProtocolTest {

    @Test
    fun `participant IDs follow Williams 4x4 balanced latin square`() {
        val p1 = ExperimentProtocol.planForParticipant("P01")
        val p2 = ExperimentProtocol.planForParticipant("P02")
        val p3 = ExperimentProtocol.planForParticipant("P03")
        val p4 = ExperimentProtocol.planForParticipant("P04")
        val p5 = ExperimentProtocol.planForParticipant("P05")

        assertEquals(
            listOf(InputCondition.TOUCH, InputCondition.THRESHOLD, InputCondition.CAMERA, InputCondition.DTW),
            p1.blocks.map { it.condition },
        )
        assertEquals(
            listOf(InputCondition.THRESHOLD, InputCondition.DTW, InputCondition.TOUCH, InputCondition.CAMERA),
            p2.blocks.map { it.condition },
        )
        assertEquals(
            listOf(InputCondition.DTW, InputCondition.CAMERA, InputCondition.THRESHOLD, InputCondition.TOUCH),
            p3.blocks.map { it.condition },
        )
        assertEquals(
            listOf(InputCondition.CAMERA, InputCondition.TOUCH, InputCondition.DTW, InputCondition.THRESHOLD),
            p4.blocks.map { it.condition },
        )
        // P05 wraps around to row 1
        assertEquals(p1.blocks.map { it.condition }, p5.blocks.map { it.condition })
    }

    @Test
    fun `seeds are bound to block position across all participants`() {
        val p1 = ExperimentProtocol.planForParticipant("P01")
        val p2 = ExperimentProtocol.planForParticipant("P02")

        assertEquals(listOf(1101L, 1201L, 1301L, 1401L), p1.blocks.map { it.seed })
        assertEquals(p1.blocks.map { it.seed }, p2.blocks.map { it.seed })
    }

    @Test
    fun `participant ID validation accepts P01 to P99 and normalizes lowercase`() {
        val p = ExperimentProtocol.planForParticipant("p07")
        assertEquals("P07", p.participantId)
        assertThrows(IllegalArgumentException::class.java) {
            ExperimentProtocol.planForParticipant("Ivan")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExperimentProtocol.planForParticipant("P00")
        }
    }

    @Test
    fun `experiment session progresses through blocks with experimentMode enabled and logs to sink`() {
        val logged = mutableListOf<ExperimentLogEntry>()
        val sink = ExperimentLogSink { entry -> logged += entry }
        val session = ExperimentSession("P01", sink)

        assertEquals(1, session.currentBlock()?.position)
        assertFalse(session.isFinished)

        val config = session.startCurrentBlock(tNanos = 1_000L)
        assertTrue(config.experimentMode)
        assertTrue(logged.any { it is ExperimentLogEntry.BlockStarted })

        session.completeCurrentBlock(
            score = 5,
            errors = 1,
            misses = 0,
            cancelled = 1,
            tNanos = 180_000_000_000L,
        )
        assertEquals(2, session.currentBlock()?.position)
        assertEquals(1, session.results.size)
        assertTrue(logged.any { it is ExperimentLogEntry.BlockEnded })

        // Finish remaining 3 blocks
        repeat(3) {
            session.startCurrentBlock(tNanos = 200_000_000_000L)
            session.completeCurrentBlock(score = 4, errors = 0, misses = 1, cancelled = 0, tNanos = 380_000_000_000L)
        }
        assertTrue(session.isFinished)
        assertEquals(4, session.results.size)
    }
}
