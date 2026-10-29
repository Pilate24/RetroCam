package com.retrocam.app.director.detector

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Monitors hardware motion/orientation sensors to produce real-time roll and pitch
 * measurements for the artificial horizon guide.
 *
 * If no supported sensor is present on the hardware, reports isAvailable = false
 * so the app never shows a fake or frozen horizon.
 */
class HorizonSensorDetector(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "HorizonSensorDetector"
        const val LEVEL_THRESHOLD_DEGREES = 1.0f // within +/- 1.0 deg is considered level
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val isAvailable: Boolean = sensor != null

    var rollDegrees: Float = 0f
        private set
    var pitchDegrees: Float = 0f
        private set

    /**
     * Whether the current roll is within the level threshold (+/- 1.0 degree).
     */
    val isLevel: Boolean
        get() = isAvailable && kotlin.math.abs(rollDegrees) <= LEVEL_THRESHOLD_DEGREES

    var onHorizonUpdated: ((roll: Float, pitch: Float, isLevel: Boolean) -> Unit)? = null

    private var isListening = false
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    fun start() {
        if (!isAvailable || isListening) return
        val s = sensor ?: return
        try {
            sensorManager?.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
            isListening = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register sensor listener", e)
        }
    }

    fun stop() {
        if (!isListening) return
        try {
            sensorManager?.unregisterListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister sensor listener", e)
        }
        isListening = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                // orientationAngles[2] is roll in radians (-pi to +pi)
                // orientationAngles[1] is pitch in radians (-pi/2 to +pi/2)
                rollDegrees = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
                pitchDegrees = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
                onHorizonUpdated?.invoke(rollDegrees, pitchDegrees, isLevel)
            }
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                // Roll is rotation about Y axis in portrait
                rollDegrees = Math.toDegrees(kotlin.math.atan2(-x.toDouble(), y.toDouble())).toFloat()
                val g = kotlin.math.sqrt((x * x + y * y + z * z).toDouble())
                pitchDegrees = if (g > 0.001) Math.toDegrees(kotlin.math.asin((z / g).toDouble())).toFloat() else 0f
                onHorizonUpdated?.invoke(rollDegrees, pitchDegrees, isLevel)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
