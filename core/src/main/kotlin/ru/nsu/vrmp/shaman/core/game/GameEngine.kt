package ru.nsu.vrmp.shaman.core.game

import ru.nsu.vrmp.shaman.core.recognition.Gesture
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random

/**
 * Deterministic round engine for "Пульт шамана".
 * Uses only caller-provided [tNanos] timestamps and seeded [Random] — no wall-clock time.
 */
class GameEngine {

    private var config = GameConfig()
    private var spawner: SpiritSpawner = SeededSpiritSpawner(0L)
    private var initialized = false

    private var roundStartedAtNanos = 0L
    private var nowNanos = 0L
    private var finished = false
    private var scannerYawRad = 0.0

    private var score = 0
    private var lives = 0
    private var mana = 0
    private var activeTrap = TrapType.AMBER
    private var fogActive = false
    private var fogClearedUntilNanos = 0L
    private var fogEndsAtNanos = 0L
    private var nextFogAtNanos = Long.MAX_VALUE

    private var pendingAttack: AttackState? = null
    private var nextAttackAllowedAtNanos = 0L
    private var isHidden = false

    private var errors = 0
    private var misses = 0
    private var cancelledCount = 0

    private var nextSpiritId = 1
    private var spawnIndex = 0
    private var lastSpawnNanos = 0L

    private val activeSpirits = mutableListOf<Spirit>()
    private val commandRecords = mutableMapOf<Long, CommandRecord>()

    private data class CommandRecord(
        val outcome: CommandOutcome,
        val removedSpirit: Spirit? = null,
        val scoreDelta: Int = 0,
        val manaSpent: Int = 0,
        val errorDelta: Int = 0,
        val lifeDelta: Int = 0,
        var cancelOutcome: CommandOutcome.Cancelled? = null,
    )

    fun startRound(
        seed: Long,
        startNanos: Long,
        config: GameConfig = GameConfig(),
        spawner: SpiritSpawner? = null,
    ): UpdateResult {
        this.config = config
        this.spawner = spawner ?: SeededSpiritSpawner(seed)
        this.initialized = true

        this.roundStartedAtNanos = startNanos
        this.nowNanos = startNanos
        this.finished = false
        this.scannerYawRad = 0.0

        this.score = 0
        this.lives = config.initialLives
        this.mana = config.initialMana
        this.activeTrap = TrapType.AMBER
        this.fogActive = false
        this.fogClearedUntilNanos = startNanos
        this.fogEndsAtNanos = startNanos
        this.nextFogAtNanos = if (!config.experimentMode && config.fogStartAfterNanos > 0L) {
            startNanos + config.fogStartAfterNanos
        } else {
            Long.MAX_VALUE
        }

        this.pendingAttack = null
        this.nextAttackAllowedAtNanos = startNanos
        this.isHidden = false

        this.errors = 0
        this.misses = 0
        this.cancelledCount = 0

        this.nextSpiritId = 1
        this.spawnIndex = 0
        this.lastSpawnNanos = startNanos

        this.activeSpirits.clear()
        this.commandRecords.clear()

        val events = mutableListOf<GameEvent>(
            GameEvent.RoundStarted(startNanos, seed, config.experimentMode),
        )
        spawnAvailableSpirits(startNanos, events)
        return UpdateResult(currentState(), events)
    }

    fun update(tNanos: Long, scannerYawRad: Double = currentState().scannerYawRad): UpdateResult {
        ensureInitialized()
        val now = maxOf(tNanos, nowNanos)
        nowNanos = now
        this.scannerYawRad = normalizeAngleRad(scannerYawRad)

        val events = mutableListOf<GameEvent>()
        if (finished) {
            return UpdateResult(currentState(), events)
        }

        expireSpirits(now, events)
        updateFog(now, events)
        updateAttack(now, events)
        if (!finished) {
            spawnAvailableSpirits(now, events)
        }
        checkTimeLimit(now, events)

        return UpdateResult(currentState(), events)
    }

    fun applyCommand(commandId: Long, gesture: Gesture, tNanos: Long): CommandResult {
        ensureInitialized()
        val events = update(tNanos).events.toMutableList()
        val now = nowNanos

        commandRecords[commandId]?.let { existing ->
            return CommandResult(existing.outcome, currentState(), events)
        }

        if (gesture == Gesture.CANCEL) {
            val outcome = CommandOutcome.Error(commandId, CommandErrorReason.INVALID_COMMAND)
            commandRecords[commandId] = CommandRecord(outcome)
            return CommandResult(outcome, currentState(), events)
        }

        if (finished) {
            val outcome = CommandOutcome.Error(commandId, CommandErrorReason.ROUND_FINISHED)
            commandRecords[commandId] = CommandRecord(outcome)
            return CommandResult(outcome, currentState(), events)
        }

        val target = computeVisibleSpirits().firstOrNull()
        if (target == null) {
            return rejectCommand(commandId, CommandErrorReason.NO_SPIRIT_IN_CONE, deductLife = true, now, events)
        }

        val spirit = target.spirit
        val expectedGesture = when (spirit.type) {
            SpiritType.WANDERING -> Gesture.CATCH
            SpiritType.EVIL -> Gesture.BANISH
        }
        if (gesture != expectedGesture) {
            return rejectCommand(commandId, CommandErrorReason.WRONG_GESTURE, deductLife = true, now, events)
        }

        var manaSpent = 0
        if (spirit.type == SpiritType.WANDERING && !config.experimentMode) {
            if (activeTrap != spirit.trapType) {
                return rejectCommand(commandId, CommandErrorReason.WRONG_TRAP, deductLife = true, now, events)
            }
            if (mana <= 0) {
                return rejectCommand(commandId, CommandErrorReason.NO_MANA, deductLife = false, now, events)
            }
            mana -= 1
            manaSpent = 1
        }

        activeSpirits.removeAll { it.id == spirit.id }
        if (pendingAttack?.spiritId == spirit.id) {
            pendingAttack = null
        }
        score += 1

        val outcome = CommandOutcome.Success(commandId, spirit.id, spirit.type, scoreDelta = 1)
        commandRecords[commandId] = CommandRecord(
            outcome = outcome,
            removedSpirit = spirit,
            scoreDelta = 1,
            manaSpent = manaSpent,
        )
        events += GameEvent.SpiritResolved(now, spirit.id, spirit.type, gesture)
        return CommandResult(outcome, currentState(), events)
    }

    fun cancelCommand(commandId: Long, tNanos: Long): CommandResult {
        ensureInitialized()
        val events = update(tNanos).events.toMutableList()
        val now = nowNanos

        val record = commandRecords[commandId]
            ?: return CommandResult(
                CommandOutcome.CancelIgnored(commandId, "Unknown commandId"),
                currentState(),
                events,
            )

        record.cancelOutcome?.let { alreadyCancelled ->
            return CommandResult(alreadyCancelled, currentState(), events)
        }

        val revertedSuccess = record.removedSpirit != null
        record.removedSpirit?.let { restored ->
            if (activeSpirits.none { it.id == restored.id }) {
                val safeExpiry = maxOf(restored.expiresAtNanos, now + 1_000_000_000L)
                activeSpirits += restored.copy(expiresAtNanos = safeExpiry)
            }
        }
        score = (score - record.scoreDelta).coerceAtLeast(0)
        mana = minOf(config.maxMana, mana + record.manaSpent)
        errors = (errors - record.errorDelta).coerceAtLeast(0)
        lives = minOf(config.initialLives, lives + record.lifeDelta)

        if (finished && lives > 0 && now < roundStartedAtNanos + config.roundDurationNanos) {
            finished = false
        }

        cancelledCount += 1
        val cancelOutcome = CommandOutcome.Cancelled(commandId, revertedSuccess)
        record.cancelOutcome = cancelOutcome
        events += GameEvent.CommandCancelled(now, commandId, revertedSuccess)
        return CommandResult(cancelOutcome, currentState(), events)
    }

    fun setHidden(hidden: Boolean, tNanos: Long): UpdateResult {
        ensureInitialized()
        if (config.experimentMode) {
            return update(tNanos)
        }
        this.isHidden = hidden
        return update(tNanos)
    }

    fun disperseFog(tNanos: Long): UpdateResult {
        ensureInitialized()
        if (config.experimentMode) {
            return update(tNanos)
        }
        val baseEvents = update(tNanos).events.toMutableList()
        val now = nowNanos
        fogClearedUntilNanos = now + config.fogDisperseDurationNanos
        nextFogAtNanos = maxOf(nextFogAtNanos, fogClearedUntilNanos + config.fogStartAfterNanos)
        if (fogActive) {
            fogActive = false
            baseEvents += GameEvent.FogChanged(now, active = false)
        }
        return UpdateResult(currentState(), baseEvents)
    }

    fun switchTrap(trap: TrapType? = null, tNanos: Long): UpdateResult {
        ensureInitialized()
        if (config.experimentMode) {
            return update(tNanos)
        }
        val baseEvents = update(tNanos).events.toMutableList()
        val nextTrap = trap ?: if (activeTrap == TrapType.AMBER) TrapType.AZURE else TrapType.AMBER
        if (nextTrap != activeTrap) {
            activeTrap = nextTrap
            baseEvents += GameEvent.TrapChanged(nowNanos, nextTrap)
        }
        return UpdateResult(currentState(), baseEvents)
    }

    fun activateAltar(tNanos: Long): UpdateResult {
        ensureInitialized()
        if (config.experimentMode) {
            return update(tNanos)
        }
        val baseEvents = update(tNanos).events.toMutableList()
        if (mana < config.maxMana) {
            mana = config.maxMana
            baseEvents += GameEvent.ManaRefilled(nowNanos, mana)
        }
        return UpdateResult(currentState(), baseEvents)
    }

    fun currentState(): GameState {
        ensureInitialized()
        val elapsed = (nowNanos - roundStartedAtNanos).coerceAtLeast(0L)
        val remaining = (config.roundDurationNanos - elapsed).coerceAtLeast(0L)
        val effectiveCone = effectiveConeHalfAngleRad()
        val visible = computeVisibleSpirits(effectiveCone)
        val attack = pendingAttack?.let {
            it.copy(remainingNanos = (it.deadlineNanos - nowNanos).coerceAtLeast(0L))
        }
        return GameState(
            roundStartedAtNanos = roundStartedAtNanos,
            nowNanos = nowNanos,
            remainingNanos = remaining,
            finished = finished,
            scannerYawRad = scannerYawRad,
            effectiveConeHalfAngleRad = effectiveCone,
            score = score,
            lives = lives,
            mana = mana,
            maxMana = config.maxMana,
            activeTrap = activeTrap,
            fogActive = fogActive,
            fogClearedUntilNanos = fogClearedUntilNanos,
            pendingAttack = attack,
            isHidden = isHidden,
            activeSpiritCount = activeSpirits.size,
            visibleSpirits = visible,
            beamProximity = computeBeamProximity(),
            errors = errors,
            misses = misses,
            cancelledCount = cancelledCount,
            experimentMode = config.experimentMode,
        )
    }

    private fun rejectCommand(
        commandId: Long,
        reason: CommandErrorReason,
        deductLife: Boolean,
        now: Long,
        events: MutableList<GameEvent>,
    ): CommandResult {
        errors += 1
        var lifeDelta = 0
        if (deductLife && lives > 0) {
            lives -= 1
            lifeDelta = 1
        }
        val outcome = CommandOutcome.Error(commandId, reason)
        commandRecords[commandId] = CommandRecord(
            outcome = outcome,
            errorDelta = 1,
            lifeDelta = lifeDelta,
        )
        events += GameEvent.CommandRejected(now, commandId, reason)
        if (lives <= 0) {
            finishRound(now, events)
        }
        return CommandResult(outcome, currentState(), events)
    }

    private fun expireSpirits(now: Long, events: MutableList<GameEvent>) {
        val iterator = activeSpirits.iterator()
        while (iterator.hasNext()) {
            val spirit = iterator.next()
            if (now >= spirit.expiresAtNanos) {
                iterator.remove()
                misses += 1
                if (pendingAttack?.spiritId == spirit.id) {
                    pendingAttack = null
                }
                events += GameEvent.SpiritMissed(now, spirit.id, spirit.type)
            }
        }
    }

    private fun updateFog(now: Long, events: MutableList<GameEvent>) {
        if (config.experimentMode || config.fogStartAfterNanos <= 0L) return

        if (fogActive && now >= fogEndsAtNanos) {
            fogActive = false
            nextFogAtNanos = now + config.fogStartAfterNanos
            events += GameEvent.FogChanged(now, active = false)
        } else if (!fogActive && now >= nextFogAtNanos && now >= fogClearedUntilNanos) {
            fogActive = true
            fogEndsAtNanos = now + config.fogDurationNanos
            events += GameEvent.FogChanged(now, active = true)
        }
    }

    private fun updateAttack(now: Long, events: MutableList<GameEvent>) {
        if (config.experimentMode) return

        val attack = pendingAttack
        if (attack != null) {
            if (isHidden) {
                pendingAttack = null
                nextAttackAllowedAtNanos = now + config.attackCooldownNanos
                events += GameEvent.AttackDodged(now, attack.spiritId)
            } else if (now >= attack.deadlineNanos) {
                pendingAttack = null
                nextAttackAllowedAtNanos = now + config.attackCooldownNanos
                lives = (lives - 1).coerceAtLeast(0)
                events += GameEvent.AttackHit(now, attack.spiritId, lives)
                if (lives <= 0) {
                    finishRound(now, events)
                }
            }
            return
        }

        if (isHidden || now < nextAttackAllowedAtNanos) return
        val attacker = activeSpirits.firstOrNull {
            it.type == SpiritType.EVIL && (now - it.spawnedAtNanos) >= config.attackDelayNanos
        } ?: return

        val deadline = now + config.attackWindowNanos
        pendingAttack = AttackState(
            spiritId = attacker.id,
            startedAtNanos = now,
            deadlineNanos = deadline,
            remainingNanos = config.attackWindowNanos,
        )
        events += GameEvent.AttackStarted(now, attacker.id, deadline)
    }

    private fun spawnAvailableSpirits(now: Long, events: MutableList<GameEvent>) {
        while (activeSpirits.size < config.maxActiveSpirits) {
            val due = activeSpirits.isEmpty() || (now - lastSpawnNanos) >= config.spiritSpawnIntervalNanos
            if (!due) break

            val blueprint = spawner.nextSpirit(spawnIndex, now) ?: break
            spawnIndex += 1
            lastSpawnNanos = now
            val spirit = Spirit(
                id = nextSpiritId++,
                type = blueprint.type,
                azimuthRad = normalizeAngleRad(blueprint.azimuthRad),
                distance = blueprint.distance.coerceIn(0.15, 0.95),
                trapType = blueprint.trapType,
                spawnedAtNanos = now,
                expiresAtNanos = now + config.spiritLifetimeNanos,
            )
            activeSpirits += spirit
            events += GameEvent.SpiritSpawned(now, spirit)
        }
    }

    private fun checkTimeLimit(now: Long, events: MutableList<GameEvent>) {
        if (!finished && now >= roundStartedAtNanos + config.roundDurationNanos) {
            finishRound(now, events)
        }
    }

    private fun finishRound(now: Long, events: MutableList<GameEvent>) {
        if (finished) return
        finished = true
        pendingAttack = null
        events += GameEvent.RoundEnded(now, score, lives, errors, misses, cancelledCount)
    }

    private fun effectiveConeHalfAngleRad(): Double =
        if (fogActive && !config.experimentMode) config.fogConeHalfAngleRad else config.coneHalfAngleRad

    private fun computeVisibleSpirits(coneHalf: Double = effectiveConeHalfAngleRad()): List<VisibleSpirit> =
        activeSpirits.mapNotNull { spirit ->
            val diff = shortestAngleDiffRad(spirit.azimuthRad, scannerYawRad)
            val absDiff = abs(diff)
            if (absDiff <= coneHalf) {
                val alignment = (1.0 - absDiff / coneHalf).coerceIn(0.0, 1.0)
                VisibleSpirit(spirit, diff, alignment)
            } else {
                null
            }
        }.sortedBy { abs(it.relativeAngleRad) }

    private fun computeBeamProximity(): Double {
        val minDiff = activeSpirits.minOfOrNull { abs(shortestAngleDiffRad(it.azimuthRad, scannerYawRad)) }
            ?: return 0.0
        return (1.0 - minDiff / PI).coerceIn(0.0, 1.0)
    }

    private fun ensureInitialized() {
        check(initialized) { "GameEngine.startRound must be called first" }
    }

    companion object {
        /** Normalizes an angle in radians to `[-PI, PI)`. */
        fun normalizeAngleRad(angleRad: Double): Double {
            var a = (angleRad + PI) % (2.0 * PI)
            if (a < 0.0) a += 2.0 * PI
            return a - PI
        }

        /** Signed shortest angular distance `target - source` in `[-PI, PI]`. */
        fun shortestAngleDiffRad(targetRad: Double, sourceRad: Double): Double {
            val diff = normalizeAngleRad(targetRad - sourceRad)
            return if (diff == -PI) PI else diff
        }
    }
}

/** Deterministic spirit generator backed by a fixed-seed [Random]. */
private class SeededSpiritSpawner(seed: Long) : SpiritSpawner {
    private val random = Random(seed)

    override fun nextSpirit(index: Int, spawnedAtNanos: Long): SpiritBlueprint {
        val type = if (random.nextBoolean()) SpiritType.WANDERING else SpiritType.EVIL
        val azimuth = random.nextDouble(-PI, PI)
        val distance = random.nextDouble(0.25, 0.9)
        val trap = if (random.nextBoolean()) TrapType.AMBER else TrapType.AZURE
        return SpiritBlueprint(type, azimuth, distance, trap)
    }
}
