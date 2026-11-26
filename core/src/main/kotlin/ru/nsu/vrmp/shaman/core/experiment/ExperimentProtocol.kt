package ru.nsu.vrmp.shaman.core.experiment

import ru.nsu.vrmp.shaman.core.game.GameConfig
import ru.nsu.vrmp.shaman.core.game.GameEvent

enum class InputCondition(val id: String) {
    TOUCH("touch"),
    THRESHOLD("threshold"),
    DTW("dtw"),
    CAMERA("camera"),
}

data class ExperimentBlock(
    val position: Int,
    val condition: InputCondition,
    val seed: Long,
)

data class ExperimentPlan(
    val participantId: String,
    val latinSquareRow: Int,
    val blocks: List<ExperimentBlock>,
)

data class BlockResult(
    val block: ExperimentBlock,
    val score: Int,
    val errors: Int,
    val misses: Int,
    val cancelled: Int,
)

sealed interface ExperimentLogEntry {
    val participantId: String
    val position: Int
    val condition: InputCondition
    val tNanos: Long

    data class BlockStarted(
        override val participantId: String,
        override val position: Int,
        override val condition: InputCondition,
        val seed: Long,
        override val tNanos: Long,
    ) : ExperimentLogEntry

    data class GameEventRecorded(
        override val participantId: String,
        override val position: Int,
        override val condition: InputCondition,
        val event: GameEvent,
    ) : ExperimentLogEntry {
        override val tNanos: Long get() = event.tNanos
    }

    data class BlockEnded(
        override val participantId: String,
        override val position: Int,
        override val condition: InputCondition,
        val result: BlockResult,
        override val tNanos: Long,
    ) : ExperimentLogEntry
}

fun interface ExperimentLogSink {
    fun record(entry: ExperimentLogEntry)
}

/**
 * Balanced Williams 4×4 Latin square and position-bound seeds per docs/RESEARCH.md.
 */
object ExperimentProtocol {

    private val PARTICIPANT_PATTERN = Regex("^P(\\d{2})$")

    private val LATIN_SQUARE_ROWS: List<List<InputCondition>> = listOf(
        listOf(InputCondition.TOUCH, InputCondition.THRESHOLD, InputCondition.CAMERA, InputCondition.DTW),
        listOf(InputCondition.THRESHOLD, InputCondition.DTW, InputCondition.TOUCH, InputCondition.CAMERA),
        listOf(InputCondition.DTW, InputCondition.CAMERA, InputCondition.THRESHOLD, InputCondition.TOUCH),
        listOf(InputCondition.CAMERA, InputCondition.TOUCH, InputCondition.DTW, InputCondition.THRESHOLD),
    )

    private val POSITION_SEEDS: List<Long> = listOf(1101L, 1201L, 1301L, 1401L)

    const val TASK_ROUND_DURATION_NANOS: Long = 180_000_000_000L

    fun planForParticipant(rawId: String): ExperimentPlan {
        val normalized = rawId.trim().uppercase()
        val match = PARTICIPANT_PATTERN.matchEntire(normalized)
            ?: throw IllegalArgumentException("Participant ID must follow P01..P99 format, got '$rawId'")
        val number = match.groupValues[1].toInt()
        require(number >= 1) { "Participant number must be >= 1, got '$rawId'" }

        val rowIndex = (number - 1) % LATIN_SQUARE_ROWS.size
        val conditions = LATIN_SQUARE_ROWS[rowIndex]
        val blocks = conditions.mapIndexed { idx, condition ->
            ExperimentBlock(
                position = idx + 1,
                condition = condition,
                seed = POSITION_SEEDS[idx],
            )
        }
        return ExperimentPlan(
            participantId = normalized,
            latinSquareRow = rowIndex + 1,
            blocks = blocks,
        )
    }
}

/**
 * Tracks execution of the 4 experimental blocks for one participant and forwards
 * structured lifecycle/game events to [logSink] (to be backed by SessionRecorder #8).
 */
class ExperimentSession(
    participantId: String,
    private val logSink: ExperimentLogSink = ExperimentLogSink {},
) {
    val plan: ExperimentPlan = ExperimentProtocol.planForParticipant(participantId)

    private val _results = mutableListOf<BlockResult>()
    val results: List<BlockResult> get() = _results

    private var currentBlockIndex = 0
    val isFinished: Boolean get() = currentBlockIndex >= plan.blocks.size

    fun currentBlock(): ExperimentBlock? = plan.blocks.getOrNull(currentBlockIndex)

    fun startCurrentBlock(
        tNanos: Long,
        roundDurationNanos: Long = ExperimentProtocol.TASK_ROUND_DURATION_NANOS,
    ): GameConfig {
        val block = currentBlock()
            ?: throw IllegalStateException("Experiment session already finished")
        logSink.record(
            ExperimentLogEntry.BlockStarted(
                participantId = plan.participantId,
                position = block.position,
                condition = block.condition,
                seed = block.seed,
                tNanos = tNanos,
            ),
        )
        return GameConfig(
            roundDurationNanos = roundDurationNanos,
            experimentMode = true,
        )
    }

    fun recordGameEvents(events: List<GameEvent>) {
        val block = currentBlock() ?: return
        events.forEach { event ->
            logSink.record(
                ExperimentLogEntry.GameEventRecorded(
                    participantId = plan.participantId,
                    position = block.position,
                    condition = block.condition,
                    event = event,
                ),
            )
        }
    }

    fun completeCurrentBlock(
        score: Int,
        errors: Int,
        misses: Int,
        cancelled: Int,
        tNanos: Long,
    ): BlockResult {
        val block = currentBlock()
            ?: throw IllegalStateException("Experiment session already finished")
        val result = BlockResult(
            block = block,
            score = score,
            errors = errors,
            misses = misses,
            cancelled = cancelled,
        )
        _results += result
        logSink.record(
            ExperimentLogEntry.BlockEnded(
                participantId = plan.participantId,
                position = block.position,
                condition = block.condition,
                result = result,
                tNanos = tNanos,
            ),
        )
        currentBlockIndex += 1
        return result
    }
}
