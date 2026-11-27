package ru.nsu.vrmp.shaman.core.orientation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.nsu.vrmp.shaman.core.calibration.CalibrationProfile
import ru.nsu.vrmp.shaman.core.input.ImuFrame
import ru.nsu.vrmp.shaman.core.math.Quat
import ru.nsu.vrmp.shaman.core.math.Vec3
import kotlin.math.PI

private const val EPS = 1e-6

/**
 * Builds a device→world orientation quaternion from:
 * - [yawCwRad]: clockwise heading around world +Z (positive = turning right),
 * - [pitchRad]: pitching the top of the phone (+Y) upward around device +X,
 * - [rollRad]: rolling the phone around its long axis (+Y).
 */
private fun orientation(
    yawCwRad: Double,
    pitchRad: Double = 0.0,
    rollRad: Double = 0.0,
): Quat {
    val qYaw = Quat.fromAxisAngle(Vec3.UNIT_Z, -yawCwRad)
    val qPitch = Quat.fromAxisAngle(Vec3.UNIT_X, pitchRad)
    val qRoll = Quat.fromAxisAngle(Vec3.UNIT_Y, rollRad)
    return (qYaw * qPitch * qRoll).normalized()
}

private fun deg(degrees: Double): Double = degrees * PI / 180.0

class HeadingTrackerTest {

    @Test
    fun `identity orientation gives zero heading`() {
        val tracker = HeadingTracker()
        assertEquals(0.0, tracker.update(Quat.IDENTITY), EPS)
        assertEquals(0.0, tracker.currentYawRad, EPS)
    }

    @Test
    fun `pure yaw turns follow clockwise positive convention and wrap to minus pi to pi`() {
        val tracker = HeadingTracker()

        assertEquals(PI / 2, tracker.update(orientation(yawCwRad = PI / 2)), EPS)
        assertEquals(-PI / 2, tracker.update(orientation(yawCwRad = -PI / 2)), EPS)
        assertEquals(-PI, tracker.update(orientation(yawCwRad = PI)), EPS)
        assertEquals(-PI, tracker.update(orientation(yawCwRad = -PI)), EPS)
        assertEquals(-deg(160.0), tracker.update(orientation(yawCwRad = deg(200.0))), EPS)
        assertEquals(deg(160.0), tracker.update(orientation(yawCwRad = -deg(200.0))), EPS)
    }

    @Test
    fun `calibrated non-zero reference offsets heading and handles wraparound`() {
        val tracker = HeadingTracker()
        tracker.calibrateZero(orientation(yawCwRad = deg(60.0)))

        assertEquals(0.0, tracker.update(orientation(yawCwRad = deg(60.0))), EPS)
        assertEquals(deg(30.0), tracker.update(orientation(yawCwRad = deg(90.0))), EPS)
        assertEquals(-deg(45.0), tracker.update(orientation(yawCwRad = deg(15.0))), EPS)

        // Wraparound across ±180°
        tracker.calibrateZero(orientation(yawCwRad = deg(170.0)))
        assertEquals(deg(30.0), tracker.update(orientation(yawCwRad = -deg(160.0))), EPS)
        assertEquals(-deg(20.0), tracker.update(orientation(yawCwRad = deg(150.0))), EPS)
    }

    @Test
    fun `initial calibration works with CalibrationProfile and ImuFrame overload`() {
        val zeroQuat = orientation(yawCwRad = deg(45.0), pitchRad = deg(50.0))
        val profile = CalibrationProfile(
            zeroHeadingRotation = zeroQuat,
            templates = emptyMap(),
            cameraVerified = true,
        )
        val tracker = HeadingTracker(profile.zeroHeadingRotation)
        val frame = ImuFrame(
            tNanos = 1_000_000L,
            linAcc = Vec3.ZERO,
            gyro = Vec3.ZERO,
            rotation = orientation(yawCwRad = deg(75.0), pitchRad = deg(65.0)),
        )

        assertEquals(deg(30.0), tracker.update(frame), EPS)
    }

    @Test
    fun `upright pitch and roll keep yaw stable`() {
        val tracker = HeadingTracker()
        val targetYaw = deg(35.0)
        val pitches = listOf(0.0, 45.0, 60.0, 75.0, 80.0, 85.0)
        val rolls = listOf(-30.0, -25.0, 0.0, 25.0, 30.0)

        for (p in pitches) {
            for (r in rolls) {
                val yaw = tracker.update(orientation(yawCwRad = targetYaw, pitchRad = deg(p), rollRad = deg(r)))
                assertEquals(targetYaw, yaw, EPS, "pitch=$p roll=$r")
            }
        }
    }

    @Test
    fun `vertical phone near and at 90 degree pitch avoids gimbal lock`() {
        val tracker = HeadingTracker()
        val targetYaw = -deg(50.0)

        for (p in listOf(85.0, 87.0, 88.0, 89.0, 90.0)) {
            val yaw = tracker.update(orientation(yawCwRad = targetYaw, pitchRad = deg(p), rollRad = 0.0))
            assertTrue(yaw.isFinite(), "yaw must be finite at pitch=$p")
            assertEquals(targetYaw, yaw, EPS, "pitch=$p")
        }
    }

    @Test
    fun `calibration at one tilt remains accurate at a different tilt`() {
        val tracker = HeadingTracker()
        tracker.calibrateZero(orientation(yawCwRad = deg(60.0), pitchRad = deg(40.0), rollRad = deg(10.0)))

        val actual = tracker.update(
            orientation(yawCwRad = deg(105.0), pitchRad = deg(75.0), rollRad = -deg(20.0)),
        )
        assertEquals(deg(45.0), actual, EPS)
    }

    @Test
    fun `reset and recalibration are deterministic`() {
        val a = HeadingTracker()
        val b = HeadingTracker()

        a.calibrateZero(orientation(yawCwRad = deg(90.0)))
        assertEquals(deg(30.0), a.update(orientation(yawCwRad = deg(120.0))), EPS)

        a.reset()
        assertEquals(0.0, a.zeroYawRad, EPS)
        assertEquals(0.0, a.currentYawRad, EPS)
        assertEquals(deg(120.0), a.update(orientation(yawCwRad = deg(120.0))), EPS)

        val sequence = listOf(
            orientation(deg(10.0), deg(60.0), deg(15.0)),
            orientation(deg(-80.0), deg(80.0), -deg(20.0)),
            orientation(deg(175.0), deg(90.0), 0.0),
        )
        a.reset()
        b.reset()
        for (q in sequence) {
            assertEquals(a.update(q), b.update(q), 0.0)
        }
    }

    @Test
    fun `non-unit zero and non-finite quaternions are handled safely`() {
        val tracker = HeadingTracker()
        val unit = orientation(yawCwRad = deg(40.0), pitchRad = deg(50.0))
        val scaled = Quat(unit.w * 5.0, unit.x * 5.0, unit.y * 5.0, unit.z * 5.0)

        assertEquals(deg(40.0), tracker.update(scaled), EPS)

        // Zero quaternion falls back to identity (0 rad)
        assertEquals(0.0, tracker.update(Quat(0.0, 0.0, 0.0, 0.0)), EPS)

        // NaN / Infinity in update must not corrupt state or return NaN
        val validYaw = tracker.update(orientation(yawCwRad = deg(25.0)))
        assertEquals(deg(25.0), validYaw, EPS)
        assertEquals(deg(25.0), tracker.update(Quat(Double.NaN, 0.0, 0.0, 0.0)), EPS)
        assertEquals(deg(25.0), tracker.update(Quat(Double.POSITIVE_INFINITY, 1.0, 0.0, 0.0)), EPS)

        // NaN in calibrateZero must not poison calibration
        tracker.calibrateZero(Quat(Double.NaN, 0.0, 0.0, 0.0))
        assertTrue(tracker.zeroYawRad.isFinite())
        assertEquals(deg(25.0), tracker.update(orientation(yawCwRad = deg(25.0))), EPS)
    }
}
