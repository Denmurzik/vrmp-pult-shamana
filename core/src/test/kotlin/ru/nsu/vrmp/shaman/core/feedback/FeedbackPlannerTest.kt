package ru.nsu.vrmp.shaman.core.feedback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeedbackPlannerTest {

    private companion object {
        const val MS = 1_000_000L
    }

    @Test
    fun `closer beam produces stronger faster vibration and higher pitch`() {
        val nearFar = FeedbackPlanner.describeProximity(0.2)!!
        val nearCenter = FeedbackPlanner.describeProximity(0.95)!!

        assertTrue(nearCenter.amplitude > nearFar.amplitude)
        assertTrue(nearCenter.intervalMs < nearFar.intervalMs)
        assertTrue(nearCenter.frequencyHz > nearFar.frequencyHz)
    }

    @Test
    fun `zero or negligible proximity produces no pulse`() {
        assertNull(FeedbackPlanner.describeProximity(0.0))
        assertNull(FeedbackPlanner.describeProximity(0.04))
    }

    @Test
    fun `proximity pulses respect interval and reset`() {
        val planner = FeedbackPlanner()
        val first = planner.poll(proximity = 0.8, attackActive = false, tNanos = 1_000 * MS)
        assertTrue(first is FeedbackCue.ProximityPulse)

        // Immediately afterwards -> suppressed until interval elapses
        val tooSoon = planner.poll(proximity = 0.8, attackActive = false, tNanos = 1_050 * MS)
        assertNull(tooSoon)

        val intervalMs = (first as FeedbackCue.ProximityPulse).intervalMs
        val next = planner.poll(proximity = 0.8, attackActive = false, tNanos = (1_000 + intervalMs) * MS)
        assertTrue(next is FeedbackCue.ProximityPulse)

        planner.reset()
        val afterReset = planner.poll(proximity = 0.8, attackActive = false, tNanos = 100 * MS)
        assertTrue(afterReset is FeedbackCue.ProximityPulse)
    }

    @Test
    fun `attack active overrides proximity with attack cue`() {
        val planner = FeedbackPlanner()
        val cue = planner.poll(proximity = 0.9, attackActive = true, tNanos = 1_000 * MS)
        assertEquals(FeedbackCue.CommandFeedback(CommandFeedbackKind.ATTACK), cue)

        val tooSoon = planner.poll(proximity = 0.9, attackActive = true, tNanos = 1_100 * MS)
        assertNull(tooSoon)

        val nextAttack = planner.poll(proximity = 0.9, attackActive = true, tNanos = 1_450 * MS)
        assertEquals(FeedbackCue.CommandFeedback(CommandFeedbackKind.ATTACK), nextAttack)
    }

    @Test
    fun `command cues expose distinct patterns for candidate confirmed cancelled error and attack`() {
        val cues = CommandFeedbackKind.entries.map { FeedbackPlanner.commandCue(it) }
        assertEquals(CommandFeedbackKind.entries.size, cues.toSet().size)
        val candidate = FeedbackPlanner.patternFor(CommandFeedbackKind.CANDIDATE)
        val error = FeedbackPlanner.patternFor(CommandFeedbackKind.ERROR)
        val attack = FeedbackPlanner.patternFor(CommandFeedbackKind.ATTACK)
        assertTrue(error.amplitude > candidate.amplitude)
        assertTrue(attack.timingsMs.size >= 4)
    }
}
