package ru.nsu.vrmp.shaman.core.math

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Quaternion `w + xi + yj + zk`. Orientation quaternions map device coordinates to world
 * coordinates, matching Android's `SensorManager.getQuaternionFromVector` (w, x, y, z order).
 */
data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {

    /** Hamilton product: `(this * o).rotate(v) == this.rotate(o.rotate(v))`. */
    operator fun times(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    fun conjugate() = Quat(w, -x, -y, -z)

    fun norm(): Double = sqrt(w * w + x * x + y * y + z * z)

    /** Unit quaternion; a zero quaternion becomes [IDENTITY]. */
    fun normalized(): Quat {
        val n = norm()
        return if (n == 0.0) IDENTITY else Quat(w / n, x / n, y / n, z / n)
    }

    /** Rotates [v] by this quaternion (assumed unit length). */
    fun rotate(v: Vec3): Vec3 {
        val p = Quat(0.0, v.x, v.y, v.z)
        val r = this * p * conjugate()
        return Vec3(r.x, r.y, r.z)
    }

    companion object {
        val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)

        /** Rotation by [angleRad] around [axis] (right-hand rule). */
        fun fromAxisAngle(axis: Vec3, angleRad: Double): Quat {
            val a = axis.normalized()
            val half = angleRad / 2
            val s = sin(half)
            return Quat(cos(half), a.x * s, a.y * s, a.z * s)
        }
    }
}
