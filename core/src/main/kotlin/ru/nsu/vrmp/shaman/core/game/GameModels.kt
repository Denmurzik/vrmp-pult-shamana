package ru.nsu.vrmp.shaman.core.game

import ru.nsu.vrmp.shaman.core.recognition.Gesture

enum class SpiritType {
    /** Must be caught with [Gesture.CATCH]. */
    WANDERING,

    /** Must be banished with [Gesture.BANISH]. */
    EVIL,
}

enum class TrapType {
    AMBER,
    AZURE,
}

data class SpiritBlueprint(
    val type: SpiritType,
    val azimuthRad: Double,
    val distance: Double,
    val trapType: TrapType,
)

fun interface SpiritSpawner {
    fun nextSpirit(index: Int, spawnedAtNanos: Long): SpiritBlueprint?
}

data class Spirit(
    val id: Int,
    val type: SpiritType,
    val azimuthRad: Double,
    val distance: Double,
    val trapType: TrapType,
    val spawnedAtNanos: Long,
    val expiresAtNanos: Long,
)

data class VisibleSpirit(
    val spirit: Spirit,
    /** Signed angle from scanner beam in radians; negative is left, positive is right. */
    val relativeAngleRad: Double,
    /** Alignment in [0.0, 1.0]; 1.0 means centered on the beam. */
    val alignment: Double,
)

data class AttackState(
    val spiritId: Int,
    val startedAtNanos: Long,
    val deadlineNanos: Long,
    val remainingNanos: Long,
)

data class GameConfig(
    val roundDurationNanos: Long = 60_000_000_000L,
    val coneHalfAngleRad: Double = Math.toRadians(15.0),
    val fogConeHalfAngleRad: Double = Math.toRadians(7.5),
    val fogStartAfterNanos: Long = 18_000_000_000L,
    val fogDurationNanos: Long = 12_000_000_000L,
    val fogDisperseDurationNanos: Long = 5_000_000_000L,
    val initialLives: Int = 3,
    val initialMana: Int = 5,
    val maxMana: Int = 5,
    val attackDelayNanos: Long = 6_000_000_000L,
    val attackWindowNanos: Long = 1_500_000_000L,
    val attackCooldownNanos: Long = 4_000_000_000L,
    val spiritLifetimeNanos: Long = 20_000_000_000L,
    val spiritSpawnIntervalNanos: Long = 4_000_000_000L,
    val maxActiveSpirits: Int = 2,
    val experimentMode: Boolean = false,
)

data class GameState(
    val roundStartedAtNanos: Long,
    val nowNanos: Long,
    val remainingNanos: Long,
    val finished: Boolean,
    val scannerYawRad: Double,
    val effectiveConeHalfAngleRad: Double,
    val score: Int,
    val lives: Int,
    val mana: Int,
    val maxMana: Int,
    val activeTrap: TrapType,
    val fogActive: Boolean,
    val fogClearedUntilNanos: Long,
    val pendingAttack: AttackState?,
    val isHidden: Boolean,
    val activeSpiritCount: Int,
    val visibleSpirits: List<VisibleSpirit>,
    /** Proximity in [0.0, 1.0] to the nearest active spirit, used for haptic/audio cues. */
    val beamProximity: Double,
    val errors: Int,
    val misses: Int,
    val cancelledCount: Int,
    val experimentMode: Boolean,
)

enum class CommandErrorReason {
    NO_SPIRIT_IN_CONE,
    WRONG_GESTURE,
    WRONG_TRAP,
    NO_MANA,
    ROUND_FINISHED,
    INVALID_COMMAND,
}

sealed interface CommandOutcome {
    val commandId: Long

    data class Success(
        override val commandId: Long,
        val spiritId: Int,
        val spiritType: SpiritType,
        val scoreDelta: Int,
    ) : CommandOutcome

    data class Error(
        override val commandId: Long,
        val reason: CommandErrorReason,
    ) : CommandOutcome

    data class Cancelled(
        override val commandId: Long,
        val revertedSuccess: Boolean,
    ) : CommandOutcome

    data class CancelIgnored(
        override val commandId: Long,
        val reason: String,
    ) : CommandOutcome
}

sealed interface GameEvent {
    val tNanos: Long

    data class RoundStarted(override val tNanos: Long, val seed: Long, val experimentMode: Boolean) : GameEvent
    data class SpiritSpawned(override val tNanos: Long, val spirit: Spirit) : GameEvent
    data class SpiritResolved(
        override val tNanos: Long,
        val spiritId: Int,
        val spiritType: SpiritType,
        val gesture: Gesture,
    ) : GameEvent

    data class SpiritMissed(override val tNanos: Long, val spiritId: Int, val spiritType: SpiritType) : GameEvent
    data class CommandRejected(override val tNanos: Long, val commandId: Long, val reason: CommandErrorReason) : GameEvent
    data class CommandCancelled(override val tNanos: Long, val commandId: Long, val revertedSuccess: Boolean) : GameEvent
    data class AttackStarted(override val tNanos: Long, val spiritId: Int, val deadlineNanos: Long) : GameEvent
    data class AttackDodged(override val tNanos: Long, val spiritId: Int) : GameEvent
    data class AttackHit(override val tNanos: Long, val spiritId: Int, val livesRemaining: Int) : GameEvent
    data class FogChanged(override val tNanos: Long, val active: Boolean) : GameEvent
    data class TrapChanged(override val tNanos: Long, val trap: TrapType) : GameEvent
    data class ManaRefilled(override val tNanos: Long, val mana: Int) : GameEvent
    data class RoundEnded(
        override val tNanos: Long,
        val score: Int,
        val lives: Int,
        val errors: Int,
        val misses: Int,
        val cancelledCount: Int,
    ) : GameEvent
}

data class UpdateResult(
    val state: GameState,
    val events: List<GameEvent>,
)

data class CommandResult(
    val outcome: CommandOutcome,
    val state: GameState,
    val events: List<GameEvent>,
)
