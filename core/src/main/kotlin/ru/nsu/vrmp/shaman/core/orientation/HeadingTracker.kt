package ru.nsu.vrmp.shaman.core.orientation

import ru.nsu.vrmp.shaman.core.input.ImuFrame
import ru.nsu.vrmp.shaman.core.math.Quat
import ru.nsu.vrmp.shaman.core.math.Vec3
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Converts a device→world orientation quaternion (`TYPE_GAME_ROTATION_VECTOR`) into a
 * tilt-robust relative heading (azimuth) in `[-PI, PI)`, where positive values mean
 * turning clockwise (right) when viewed from above — matching `TouchAction.Scan` and
 * `GameEngine.scannerYawRad`.
 *
 * Tilt robustness: projects the phone's top axis (`+Y_device`) onto the horizontal world
 * XY plane, which is invariant to roll around the device's long axis for all pitches away
 * from vertical. Near `|pitch| == 90°` (gimbal-lock zone where `+Y_device` aligns with
 * world `±Z`), smoothly blends to the orthogonal screen-normal axis (`-Z_device` when
 * upright, `+Z_device` when upside-down).
 */
class HeadingTracker(
    initialZeroRotation: Quat = Quat.IDENTITY,
) {
    /** Calibrated zero azimuth in world coordinates, in `[-PI, PI)`. */
    var zeroYawRad: Double = extractWorldYawOrNull(initialZeroRotation) ?: 0.0
        private set

    /** Last valid relative heading in `[-PI, PI)` (positive = clockwise). */
    var currentYawRad: Double = 0.0
        private set

    /**
     * Sets the calibrated zero heading from a device→world orientation quaternion
     * (e.g. `CalibrationProfile.zeroHeadingRotation`). Non-finite inputs are ignored.
     */
    fun calibrateZero(rotation: Quat) {
        val yaw = extractWorldYawOrNull(rotation) ?: return
        zeroYawRad = yaw
        currentYawRad = 0.0
    }

    /** Resets calibration to world identity (`0.0` rad) and clears [currentYawRad]. */
    fun reset() {
        zeroYawRad = 0.0
        currentYawRad = 0.0
    }

    /**
     * Updates and returns the relative heading in `[-PI, PI)` (positive = clockwise).
     * Non-finite quaternions leave [currentYawRad] unchanged.
     */
    fun update(rotation: Quat): Double {
        val worldYaw = extractWorldYawOrNull(rotation) ?: return currentYawRad
        val relative = normalizeAngleRad(worldYaw - zeroYawRad)
        currentYawRad = relative
        return relative
    }

    /** Convenience overload extracting [ImuFrame.rotation]. */
    fun update(frame: ImuFrame): Double = update(frame.rotation)

    companion object {
        // Horizontal projection thresholds of +Y_device for smooth fallback to ±Z_device near |pitch| = 90°.
        private const val GIMBAL_LOW = 0.02
        private const val GIMBAL_HIGH = 0.06
        private const val TWO_PI = 2.0 * PI

        /**
         * Extracts tilt-robust world azimuth in `[-PI, PI)` (positive = clockwise).
         * Returns `0.0` for non-finite or zero quaternions.
         */
        fun worldYawRad(rotation: Quat): Double = extractWorldYawOrNull(rotation) ?: 0.0

        /** Normalizes an angle in radians to `[-PI, PI)`. */
        fun normalizeAngleRad(angleRad: Double): Double {
            if (!angleRad.isFinite()) return 0.0
            var a = (angleRad + PI) % TWO_PI
            if (a < 0.0) a += TWO_PI
            var result = a - PI
            if (result >= PI) result -= TWO_PI
            return if (result == 0.0) 0.0 else result
        }

        private fun extractWorldYawOrNull(rotation: Quat): Double? {
            if (!rotation.w.isFinite() ||
                !rotation.x.isFinite() ||
                !rotation.y.isFinite() ||
                !rotation.z.isFinite()
            ) {
                return null
            }
            val norm = rotation.norm()
            if (!norm.isFinite()) return null
            val q = if (norm == 0.0) {
                Quat.IDENTITY
            } else {
                Quat(rotation.w / norm, rotation.x / norm, rotation.y / norm, rotation.z / norm)
            }

            val topWorld = q.rotate(Vec3.UNIT_Y)
            val topHoriz = hypot(topWorld.x, topWorld.y)

            val dirX: Double
            val dirY: Double
            if (topHoriz >= GIMBAL_HIGH) {
                dirX = topWorld.x / topHoriz
                dirY = topWorld.y / topHoriz
            } else {
                // Near vertical: back of phone (-Z) points forward when upright (topWorld.z >= 0),
                // front of phone (+Z) points forward when inverted (topWorld.z < 0).
                val zAxis = if (topWorld.z >= 0.0) -Vec3.UNIT_Z else Vec3.UNIT_Z
                val fallbackWorld = q.rotate(zAxis)
                val fallbackHoriz = hypot(fallbackWorld.x, fallbackWorld.y)
                if (fallbackHoriz == 0.0) return 0.0

                val fX = fallbackWorld.x / fallbackHoriz
                val fY = fallbackWorld.y / fallbackHoriz

                if (topHoriz <= GIMBAL_LOW) {
                    dirX = fX
                    dirY = fY
                } else {
                    val alpha = (topHoriz - GIMBAL_LOW) / (GIMBAL_HIGH - GIMBAL_LOW)
                    val tX = topWorld.x / topHoriz
                    val tY = topWorld.y / topHoriz
                    val bX = alpha * tX + (1.0 - alpha) * fX
                    val bY = alpha * tY + (1.0 - alpha) * fY
                    if (hypot(bX, bY) > 0.0) {
                        dirX = bX
                        dirY = bY
                    } else {
                        dirX = fX
                        dirY = fY
                    }
                }
            }

            return normalizeAngleRad(atan2(dirX, dirY))
        }
    }
}
