package ru.nsu.vrmp.shaman.core.input

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.nsu.vrmp.shaman.core.math.Vec3

class HandFrameTest {
    private val hand = List(HandFrame.LANDMARK_COUNT) { Vec3(it / 21.0, 0.5, 0.0) }

    @Test
    fun `present hand has 21 landmarks`() {
        assertTrue(HandFrame(1L, present = true, landmarks = hand).present)
    }

    @Test
    fun `absent hand has no landmarks`() {
        assertFalse(HandFrame.absent(1L).present)
    }

    @Test
    fun `wrong landmark count is rejected`() {
        assertThrows<IllegalArgumentException> { HandFrame(1L, present = true, landmarks = hand.drop(1)) }
        assertThrows<IllegalArgumentException> { HandFrame(1L, present = false, landmarks = hand) }
    }
}
