package ru.nsu.vrmp.shaman.core.game

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.nsu.vrmp.shaman.core.recognition.Gesture
import kotlin.math.PI

class GameEngineTest {

    private companion object {
        const val SEC = 1_000_000_000L
        const val MS = 1_000_000L
    }

    private fun scriptedSpawner(vararg specs: SpiritBlueprint): SpiritSpawner =
        SpiritSpawner { index, _ -> specs.getOrNull(index) }

    @Test
    fun `same seed produces identical spirit sequence`() {
        val config = GameConfig(
            roundDurationNanos = 30 * SEC,
            spiritSpawnIntervalNanos = 1 * SEC,
            maxActiveSpirits = 5,
            experimentMode = true,
        )
        val engineA = GameEngine()
        val engineB = GameEngine()

        engineA.startRound(seed = 42L, startNanos = 0L, config = config)
        engineB.startRound(seed = 42L, startNanos = 0L, config = config)

        val spawnedA = mutableListOf<Spirit>()
        val spawnedB = mutableListOf<Spirit>()

        for (step in 0..10) {
            val t = step * SEC
            engineA.update(t, scannerYawRad = 0.0).events
                .filterIsInstance<GameEvent.SpiritSpawned>()
                .mapTo(spawnedA) { it.spirit }
            engineB.update(t, scannerYawRad = 0.0).events
                .filterIsInstance<GameEvent.SpiritSpawned>()
                .mapTo(spawnedB) { it.spirit }
        }

        assertTrue(spawnedA.size >= 3)
        assertEquals(spawnedA, spawnedB)
    }

    @Test
    fun `different seeds produce different spirit sequences`() {
        val config = GameConfig(
            spiritSpawnIntervalNanos = 1 * SEC,
            maxActiveSpirits = 4,
            experimentMode = true,
        )
        val engineA = GameEngine()
        val engineB = GameEngine()
        engineA.startRound(seed = 1L, startNanos = 0L, config = config)
        engineB.startRound(seed = 999L, startNanos = 0L, config = config)

        val spawnedA = (0..5).flatMap { step ->
            engineA.update(step * SEC, 0.0).events.filterIsInstance<GameEvent.SpiritSpawned>().map { it.spirit }
        }
        val spawnedB = (0..5).flatMap { step ->
            engineB.update(step * SEC, 0.0).events.filterIsInstance<GameEvent.SpiritSpawned>().map { it.spirit }
        }

        assertNotEquals(spawnedA, spawnedB)
    }

    @Test
    fun `scanner cone is plus minus 15 degrees with angle wraparound`() {
        val spawner = scriptedSpawner(
            SpiritBlueprint(SpiritType.WANDERING, Math.toRadians(175.0), 0.5, TrapType.AMBER),
        )
        val engine = GameEngine()
        engine.startRound(
            seed = 0L,
            startNanos = 0L,
            config = GameConfig(experimentMode = true),
            spawner = spawner,
        )

        // Scanner at -175 deg -> angular distance across +-180 boundary is 10 deg (inside +-15 deg)
        val inside = engine.update(100 * MS, scannerYawRad = Math.toRadians(-175.0)).state
        assertEquals(1, inside.visibleSpirits.size)
        assertEquals(Math.toRadians(-10.0), inside.visibleSpirits[0].relativeAngleRad, 1e-6)

        // Scanner at -169 deg -> angular distance is 16 deg (outside +-15 deg)
        val outside = engine.update(200 * MS, scannerYawRad = Math.toRadians(-169.0)).state
        assertTrue(outside.visibleSpirits.isEmpty())

        // Exact boundary 14.9 deg inside vs 15.1 deg outside
        val nearInside = engine.update(300 * MS, scannerYawRad = Math.toRadians(160.1)).state
        assertEquals(1, nearInside.visibleSpirits.size)
        val nearOutside = engine.update(400 * MS, scannerYawRad = Math.toRadians(159.9)).state
        assertTrue(nearOutside.visibleSpirits.isEmpty())
    }

    @Test
    fun `correct catch and banish remove spirits and add score`() {
        val spawner = scriptedSpawner(
            SpiritBlueprint(SpiritType.WANDERING, 0.0, 0.4, TrapType.AMBER),
            SpiritBlueprint(SpiritType.EVIL, PI / 2, 0.7, TrapType.AZURE),
        )
        val engine = GameEngine()
        engine.startRound(
            seed = 0L,
            startNanos = 0L,
            config = GameConfig(spiritSpawnIntervalNanos = 0L, maxActiveSpirits = 2, experimentMode = true),
            spawner = spawner,
        )

        engine.update(100 * MS, scannerYawRad = 0.0)
        val catchRes = engine.applyCommand(commandId = 10L, gesture = Gesture.CATCH, tNanos = 200 * MS)
        assertEquals(CommandOutcome.Success(10L, 1, SpiritType.WANDERING, 1), catchRes.outcome)
        assertEquals(1, catchRes.state.score)
        assertTrue(catchRes.state.visibleSpirits.isEmpty())

        engine.update(300 * MS, scannerYawRad = PI / 2)
        val banishRes = engine.applyCommand(commandId = 11L, gesture = Gesture.BANISH, tNanos = 400 * MS)
        assertEquals(CommandOutcome.Success(11L, 2, SpiritType.EVIL, 1), banishRes.outcome)
        assertEquals(2, banishRes.state.score)
        assertEquals(0, banishRes.state.activeSpiritCount)
    }

    @Test
    fun `wrong gesture or miss increments errors and decrements lives until round ends`() {
        val spawner = scriptedSpawner(
            SpiritBlueprint(SpiritType.WANDERING, 0.0, 0.5, TrapType.AMBER),
        )
        val engine = GameEngine()
        engine.startRound(
            seed = 0L,
            startNanos = 0L,
            config = GameConfig(initialLives = 2, experimentMode = true),
            spawner = spawner,
        )

        // In cone, wrong gesture (BANISH instead of CATCH)
        engine.update(100 * MS, scannerYawRad = 0.0)
        val wrong = engine.applyCommand(1L, Gesture.BANISH, 150 * MS)
        assertEquals(CommandOutcome.Error(1L, CommandErrorReason.WRONG_GESTURE), wrong.outcome)
        assertEquals(1, wrong.state.errors)
        assertEquals(1, wrong.state.lives)
        assertFalse(wrong.state.finished)

        // Outside cone -> miss, drops last life -> round finishes
        engine.update(200 * MS, scannerYawRad = PI)
        val miss = engine.applyCommand(2L, Gesture.CATCH, 250 * MS)
        assertEquals(CommandOutcome.Error(2L, CommandErrorReason.NO_SPIRIT_IN_CONE), miss.outcome)
        assertEquals(2, miss.state.errors)
        assertEquals(0, miss.state.lives)
        assertTrue(miss.state.finished)

        // Command after round finished is rejected
        val after = engine.applyCommand(3L, Gesture.CATCH, 300 * MS)
        assertEquals(CommandOutcome.Error(3L, CommandErrorReason.ROUND_FINISHED), after.outcome)
    }

    @Test
    fun `cancel restores spirit and reverts score without counting as error`() {
        val spawner = scriptedSpawner(
            SpiritBlueprint(SpiritType.WANDERING, 0.0, 0.5, TrapType.AMBER),
        )
        val engine = GameEngine()
        engine.startRound(0L, startNanos = 0L, config = GameConfig(experimentMode = true), spawner = spawner)
        engine.update(100 * MS, scannerYawRad = 0.0)

        val res = engine.applyCommand(42L, Gesture.CATCH, 200 * MS)
        assertEquals(1, res.state.score)
        assertEquals(0, res.state.activeSpiritCount)

        val cancel = engine.cancelCommand(42L, 500 * MS)
        assertEquals(CommandOutcome.Cancelled(42L, revertedSuccess = true), cancel.outcome)
        assertEquals(0, cancel.state.score)
        assertEquals(0, cancel.state.errors)
        assertEquals(1, cancel.state.cancelledCount)
        assertEquals(1, cancel.state.activeSpiritCount)
        assertEquals(1, cancel.state.visibleSpirits.size)

        // Re-cancelling the same commandId is idempotent
        val cancelAgain = engine.cancelCommand(42L, 600 * MS)
        assertEquals(1, cancelAgain.state.cancelledCount)
        assertEquals(0, cancelAgain.state.score)
    }

    @Test
    fun `duplicate commandId returns cached outcome without double applying`() {
        val spawner = scriptedSpawner(
            SpiritBlueprint(SpiritType.WANDERING, 0.0, 0.5, TrapType.AMBER),
        )
        val engine = GameEngine()
        engine.startRound(0L, startNanos = 0L, config = GameConfig(experimentMode = true), spawner = spawner)
        engine.update(100 * MS, scannerYawRad = 0.0)

        val first = engine.applyCommand(7L, Gesture.CATCH, 200 * MS)
        val second = engine.applyCommand(7L, Gesture.CATCH, 250 * MS)
        assertEquals(first.outcome, second.outcome)
        assertEquals(1, second.state.score)
    }

    @Test
    fun `round ends when duration elapses and spirits expire as misses`() {
        val spawner = scriptedSpawner(
            SpiritBlueprint(SpiritType.WANDERING, 0.0, 0.5, TrapType.AMBER),
        )
        val engine = GameEngine()
        engine.startRound(
            seed = 0L,
            startNanos = 10 * SEC,
            config = GameConfig(roundDurationNanos = 45 * SEC, spiritLifetimeNanos = 5 * SEC, experimentMode = true),
            spawner = spawner,
        )

        val expired = engine.update(15 * SEC, scannerYawRad = PI)
        assertEquals(1, expired.state.misses)
        assertEquals(0, expired.state.activeSpiritCount)
        assertTrue(expired.events.any { it is GameEvent.SpiritMissed })

        val ended = engine.update(55 * SEC, scannerYawRad = PI)
        assertTrue(ended.state.finished)
        assertEquals(0L, ended.state.remainingNanos)
        assertTrue(ended.events.any { it is GameEvent.RoundEnded })
    }

    @Test
    fun `normal mode enforces trap color mana and fog`() {
        val spawner = scriptedSpawner(
            SpiritBlueprint(SpiritType.WANDERING, 0.0, 0.5, TrapType.AZURE),
        )
        val engine = GameEngine()
        engine.startRound(
            seed = 0L,
            startNanos = 0L,
            config = GameConfig(
                initialMana = 1,
                maxMana = 3,
                fogStartAfterNanos = 1 * SEC,
                fogDurationNanos = 10 * SEC,
                experimentMode = false,
            ),
            spawner = spawner,
        )

        // After fogStartAfterNanos, fog activates and narrows cone to 7.5 deg
        val narrow = engine.update(1_100 * MS, scannerYawRad = Math.toRadians(10.0)).state
        assertTrue(narrow.fogActive)
        assertTrue(narrow.visibleSpirits.isEmpty())

        // Dispersing fog restores full +-15 deg cone
        val cleared = engine.disperseFog(1_200 * MS).state
        assertFalse(cleared.fogActive)
        assertEquals(1, cleared.visibleSpirits.size)

        // Wrong trap color fails
        engine.update(1_300 * MS, scannerYawRad = 0.0)
        val wrongTrap = engine.applyCommand(1L, Gesture.CATCH, 1_350 * MS)
        assertEquals(CommandOutcome.Error(1L, CommandErrorReason.WRONG_TRAP), wrongTrap.outcome)

        // Switching trap succeeds and consumes mana
        engine.switchTrap(TrapType.AZURE, 1_400 * MS)
        val ok = engine.applyCommand(2L, Gesture.CATCH, 1_450 * MS)
        assertEquals(CommandOutcome.Success(2L, 1, SpiritType.WANDERING, 1), ok.outcome)
        assertEquals(0, ok.state.mana)

        // Altar refills mana
        val refilled = engine.activateAltar(1_500 * MS).state
        assertEquals(3, refilled.mana)
    }

    @Test
    fun `evil attack hits after 1_5s unless player hides`() {
        val spawner = scriptedSpawner(
            SpiritBlueprint(SpiritType.EVIL, PI, 0.5, TrapType.AMBER),
        )
        val engine = GameEngine()
        engine.startRound(
            seed = 0L,
            startNanos = 0L,
            config = GameConfig(
                initialLives = 3,
                attackDelayNanos = 2 * SEC,
                attackWindowNanos = 1_500 * MS,
                attackCooldownNanos = 1 * SEC,
                fogStartAfterNanos = 0L,
                experimentMode = false,
            ),
            spawner = spawner,
        )

        // At 2s, evil spirit starts attacking
        val warning = engine.update(2 * SEC, scannerYawRad = 0.0).state
        assertEquals(3, warning.lives)
        assertTrue(warning.pendingAttack != null)

        // Dodged by hiding before deadline (3.5s)
        val hidden = engine.setHidden(true, 2_500 * MS)
        val afterDodge = engine.update(3_600 * MS, scannerYawRad = 0.0)
        assertEquals(3, afterDodge.state.lives)
        assertNull(afterDodge.state.pendingAttack)
        assertTrue(hidden.events.any { it is GameEvent.AttackDodged } || afterDodge.events.any { it is GameEvent.AttackDodged })

        // Uncover and wait for next attack (starts at ~4.6s, hits at ~6.1s)
        engine.setHidden(false, 4 * SEC)
        val nextAttack = engine.update(4_700 * MS, scannerYawRad = 0.0).state
        assertTrue(nextAttack.pendingAttack != null)

        val hit = engine.update(6_300 * MS, scannerYawRad = 0.0)
        assertEquals(2, hit.state.lives)
        assertNull(hit.state.pendingAttack)
        assertTrue(hit.events.any { it is GameEvent.AttackHit })
    }

    @Test
    fun `cancelling failed command restores life and clears error`() {
        val spawner = scriptedSpawner(
            SpiritBlueprint(SpiritType.WANDERING, 0.0, 0.5, TrapType.AMBER),
        )
        val engine = GameEngine()
        engine.startRound(0L, startNanos = 0L, config = GameConfig(initialLives = 1, experimentMode = true), spawner = spawner)
        engine.update(100 * MS, scannerYawRad = PI)

        val failed = engine.applyCommand(9L, Gesture.CATCH, 200 * MS)
        assertEquals(0, failed.state.lives)
        assertEquals(1, failed.state.errors)
        assertTrue(failed.state.finished)

        val cancelled = engine.cancelCommand(9L, 500 * MS)
        assertEquals(CommandOutcome.Cancelled(9L, revertedSuccess = false), cancelled.outcome)
        assertEquals(1, cancelled.state.lives)
        assertEquals(0, cancelled.state.errors)
        assertEquals(1, cancelled.state.cancelledCount)
        assertFalse(cancelled.state.finished)
    }

    @Test
    fun `experiment mode ignores trap color mana fog and attacks`() {
        val spawner = scriptedSpawner(
            SpiritBlueprint(SpiritType.WANDERING, 0.0, 0.5, TrapType.AZURE),
            SpiritBlueprint(SpiritType.EVIL, 0.0, 0.5, TrapType.AMBER),
        )
        val engine = GameEngine()
        val started = engine.startRound(
            seed = 0L,
            startNanos = 0L,
            config = GameConfig(
                initialMana = 0,
                fogStartAfterNanos = 500 * MS,
                attackDelayNanos = 500 * MS,
                spiritSpawnIntervalNanos = 0L,
                maxActiveSpirits = 2,
                experimentMode = true,
            ),
            spawner = spawner,
        )
        assertTrue(started.events.any { it is GameEvent.RoundStarted })

        val stateAfterWait = engine.update(2 * SEC, scannerYawRad = 0.0).state
        assertFalse(stateAfterWait.fogActive)
        assertNull(stateAfterWait.pendingAttack)

        // Catch succeeds despite 0 mana and default AMBER trap on an AZURE spirit
        val catchRes = engine.applyCommand(1L, Gesture.CATCH, 2_100 * MS)
        assertEquals(CommandOutcome.Success(1L, 1, SpiritType.WANDERING, 1), catchRes.outcome)
    }
}
