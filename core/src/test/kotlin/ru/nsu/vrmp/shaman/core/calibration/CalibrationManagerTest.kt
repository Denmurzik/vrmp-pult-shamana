package ru.nsu.vrmp.shaman.core.calibration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.nsu.vrmp.shaman.core.input.HandFrame
import ru.nsu.vrmp.shaman.core.input.ImuFrame
import ru.nsu.vrmp.shaman.core.math.Quat
import ru.nsu.vrmp.shaman.core.math.Vec3
import ru.nsu.vrmp.shaman.core.recognition.Gesture
import kotlin.math.PI

class CalibrationManagerTest {

    private fun sampleImuSegment(baseTime: Long = 1_000_000L): List<ImuFrame> =
        (0 until 10).map { i ->
            ImuFrame(
                tNanos = baseTime + i * 20_000_000L,
                linAcc = Vec3(0.0, i * 0.5, 1.0),
                gyro = Vec3(0.1, 0.0, 0.2),
                rotation = Quat.IDENTITY,
            )
        }

    private fun sampleHandFrame(present: Boolean, tNanos: Long = 100L): HandFrame =
        if (present) {
            HandFrame(tNanos, present = true, landmarks = List(HandFrame.LANDMARK_COUNT) { Vec3(0.5, 0.5, 0.0) })
        } else {
            HandFrame.absent(tNanos)
        }

    @Test
    fun `zero heading normalizes quaternion and advances to IMU templates`() {
        val manager = CalibrationManager(targetTemplatesPerGesture = 5)
        assertEquals(CalibrationStep.ZERO_HEADING, manager.currentState().step)

        val raw = Quat(2.0, 0.0, 0.0, 0.0)
        val state = manager.captureZeroHeading(raw)
        assertEquals(CalibrationStep.IMU_TEMPLATES, state.step)
        assertTrue(state.zeroHeadingCaptured)
        assertEquals(1.0, state.zeroHeadingRotation.norm(), 1e-9)
        assertEquals(Gesture.CATCH, state.currentTemplateGesture)
    }

    @Test
    fun `requires 5 IMU templates per gesture before camera check`() {
        val manager = CalibrationManager(targetTemplatesPerGesture = 5)
        manager.captureZeroHeading(Quat.fromAxisAngle(Vec3.UNIT_Z, PI / 4))

        // Rejects too-short segment
        assertThrows(IllegalArgumentException::class.java) {
            manager.recordImuTemplate(sampleImuSegment().take(2))
        }

        // Record 5 templates for CATCH -> advances to BANISH
        repeat(5) { idx ->
            val st = manager.recordImuTemplate(sampleImuSegment(idx * 1_000_000_000L))
            if (idx < 4) {
                assertEquals(Gesture.CATCH, st.currentTemplateGesture)
                assertEquals(idx + 1, st.templateCounts[Gesture.CATCH])
            } else {
                assertEquals(Gesture.BANISH, st.currentTemplateGesture)
            }
        }

        // Record 5 for BANISH and 5 for CANCEL -> advances to CAMERA_CHECK
        repeat(5) { idx -> manager.recordImuTemplate(sampleImuSegment(idx * 1_000_000_000L)) }
        assertEquals(Gesture.CANCEL, manager.currentState().currentTemplateGesture)

        repeat(5) { idx -> manager.recordImuTemplate(sampleImuSegment(idx * 1_000_000_000L)) }
        val afterImu = manager.currentState()
        assertEquals(CalibrationStep.CAMERA_CHECK, afterImu.step)
        assertTrue(afterImu.isImuCalibrated)
        assertEquals(5, afterImu.templateCounts[Gesture.CATCH])
        assertEquals(5, afterImu.templateCounts[Gesture.BANISH])
        assertEquals(5, afterImu.templateCounts[Gesture.CANCEL])
    }

    @Test
    fun `camera check validates both lighting and hand visibility`() {
        val manager = CalibrationManager(targetTemplatesPerGesture = 1)
        manager.captureZeroHeading(Quat.IDENTITY)
        repeat(3) { manager.recordImuTemplate(sampleImuSegment()) }
        assertEquals(CalibrationStep.CAMERA_CHECK, manager.currentState().step)

        // Too dark even if hand is present
        val dark = manager.evaluateCamera(listOf(sampleHandFrame(true)), lumaMean = 20.0)
        assertFalse(dark.lightingOk)
        assertTrue(dark.handVisible)
        assertFalse(dark.isReady)

        // Good lighting, no hand visible
        val noHand = manager.evaluateCamera(listOf(sampleHandFrame(false)), lumaMean = 120.0)
        assertTrue(noHand.lightingOk)
        assertFalse(noHand.handVisible)
        assertFalse(noHand.isReady)

        // Good lighting and visible hand -> allows confirming
        val ready = manager.evaluateCamera(listOf(sampleHandFrame(true)), lumaMean = 120.0)
        assertTrue(ready.isReady)
        val completed = manager.confirmCameraReady()
        assertEquals(CalibrationStep.COMPLETED, completed.step)
        assertTrue(completed.cameraVerified)
    }

    @Test
    fun `profile serialization round-trips zero heading and DTW templates`() {
        val manager = CalibrationManager(targetTemplatesPerGesture = 2)
        manager.captureZeroHeading(Quat.fromAxisAngle(Vec3.UNIT_Z, 0.5))
        repeat(6) { idx -> manager.recordImuTemplate(sampleImuSegment(idx * 500_000_000L)) }
        manager.evaluateCamera(listOf(sampleHandFrame(true)), lumaMean = 110.0)
        manager.confirmCameraReady()

        val original = manager.buildProfile()
        val encoded = CalibrationProfileCodec.encode(original)
        val decoded = CalibrationProfileCodec.decode(encoded)

        assertEquals(original.zeroHeadingRotation.w, decoded.zeroHeadingRotation.w, 1e-6)
        assertEquals(original.zeroHeadingRotation.z, decoded.zeroHeadingRotation.z, 1e-6)
        assertEquals(original.cameraVerified, decoded.cameraVerified)
        assertEquals(original.templates.keys, decoded.templates.keys)
        Gesture.entries.forEach { g ->
            assertEquals(original.templates[g]?.size, decoded.templates[g]?.size)
            assertEquals(
                original.templates[g]?.first()?.samples?.size,
                decoded.templates[g]?.first()?.samples?.size,
            )
        }
    }
}
