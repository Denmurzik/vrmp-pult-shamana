package ru.nsu.vrmp.shaman.core.math

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.PI

private const val EPS = 1e-9

private fun assertVec(expected: Vec3, actual: Vec3) {
    assertEquals(expected.x, actual.x, EPS, "x")
    assertEquals(expected.y, actual.y, EPS, "y")
    assertEquals(expected.z, actual.z, EPS, "z")
}

class Vec3Test {
    @Test
    fun `arithmetic and products`() {
        val a = Vec3(1.0, 2.0, 3.0)
        val b = Vec3(4.0, -5.0, 6.0)
        assertVec(Vec3(5.0, -3.0, 9.0), a + b)
        assertVec(Vec3(-3.0, 7.0, -3.0), a - b)
        assertVec(Vec3(2.0, 4.0, 6.0), a * 2.0)
        assertEquals(12.0, a dot b, EPS)
        assertVec(Vec3.UNIT_Z, Vec3.UNIT_X cross Vec3.UNIT_Y)
    }

    @Test
    fun `normalization keeps zero vector`() {
        assertEquals(1.0, Vec3(3.0, 4.0, 0.0).normalized().norm(), EPS)
        assertVec(Vec3.ZERO, Vec3.ZERO.normalized())
    }
}

class QuatTest {
    @Test
    fun `identity does not rotate`() {
        assertVec(Vec3(1.0, 2.0, 3.0), Quat.IDENTITY.rotate(Vec3(1.0, 2.0, 3.0)))
    }

    @Test
    fun `quarter turn around z maps x to y`() {
        val q = Quat.fromAxisAngle(Vec3.UNIT_Z, PI / 2)
        assertVec(Vec3.UNIT_Y, q.rotate(Vec3.UNIT_X))
        assertVec(Vec3.UNIT_Z, q.rotate(Vec3.UNIT_Z))
    }

    @Test
    fun `product composes rotations`() {
        val a = Quat.fromAxisAngle(Vec3.UNIT_Z, PI / 2)
        val b = Quat.fromAxisAngle(Vec3.UNIT_X, PI / 2)
        val v = Vec3(0.3, -1.2, 0.7)
        assertVec(a.rotate(b.rotate(v)), (a * b).rotate(v))
    }

    @Test
    fun `conjugate undoes rotation`() {
        val q = Quat.fromAxisAngle(Vec3(1.0, 1.0, 0.0), 1.1)
        val v = Vec3(0.5, 2.0, -1.0)
        assertVec(v, q.conjugate().rotate(q.rotate(v)))
    }

    @Test
    fun `normalization`() {
        assertEquals(1.0, Quat(2.0, 0.0, 0.0, 0.0).normalized().norm(), EPS)
        assertEquals(Quat.IDENTITY, Quat(0.0, 0.0, 0.0, 0.0).normalized())
    }
}
