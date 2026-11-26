package ru.nsu.vrmp.shaman.calibration

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import ru.nsu.vrmp.shaman.core.input.ImuFrame
import ru.nsu.vrmp.shaman.core.math.Quat
import ru.nsu.vrmp.shaman.core.math.Vec3

/**
 * Lightweight IMU listener used during calibration before the shared sensor stream (#4) lands.
 */
class ImuSampler(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val rotSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val accSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var latestAcc = Vec3.ZERO
    private var latestGyro = Vec3.ZERO
    private var latestRot = Quat.IDENTITY

    private var recording = false
    private val recorded = mutableListOf<ImuFrame>()

    val hasMotionSensors: Boolean
        get() = rotSensor != null && accSensor != null && gyroSensor != null

    fun startListening() {
        val sm = sensorManager ?: return
        rotSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stopListening() {
        sensorManager?.unregisterListener(this)
    }

    fun currentRotation(): Quat = latestRot

    fun beginRecording() {
        recorded.clear()
        recording = true
    }

    fun finishRecording(): List<ImuFrame> {
        recording = false
        return recorded.toList()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                latestAcc = Vec3(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
                if (recording) {
                    recorded += ImuFrame(event.timestamp, latestAcc, latestGyro, latestRot)
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                latestGyro = Vec3(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
            }

            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                val q = FloatArray(4)
                SensorManager.getQuaternionFromVector(q, event.values)
                latestRot = Quat(q[0].toDouble(), q[1].toDouble(), q[2].toDouble(), q[3].toDouble()).normalized()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
