package dev.malzbro.wristshot.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import dev.malzbro.wristshot.core.config.RecorderConfig
import dev.malzbro.wristshot.core.model.RawSample
import dev.malzbro.wristshot.core.sensor.StreamAligner
import dev.malzbro.wristshot.core.session.SensorDescription
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Sensor layer: listens to the motion sensors and turns their separate streams
 * into time-aligned [RawSample]s (one per accelerometer sample).
 *
 * Sensors used:
 *  - accelerometer (includes gravity), m/s^2
 *  - gyroscope, rad/s
 *  - gravity (virtual sensor), m/s^2 - tells which way is down
 *  - game rotation vector (quaternion, no magnetometer, so indoor steel/rebar
 *    in the gym does not disturb it)
 */
class MotionSensorSource(
    private val sensorManager: SensorManager,
    onSample: (RawSample) -> Unit,
) : SensorEventListener {

    private val accel: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravity: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val rotation: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val aligner = StreamAligner(
        hasGyro = gyro != null,
        hasGravity = gravity != null,
        hasRotation = rotation != null,
        onSample = onSample,
    )

    val hasAccelerometer: Boolean get() = accel != null

    /** Start listening. Callbacks (and therefore onSample) run on [handler]'s thread. */
    fun start(handler: Handler) {
        for (sensor in listOfNotNull(accel, gyro, gravity, rotation)) {
            sensorManager.registerListener(
                this,
                sensor,
                RecorderConfig.SAMPLING_PERIOD_US,
                RecorderConfig.MAX_REPORT_LATENCY_US,
                handler,
            )
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /** Write out samples still waiting for alignment. Call on the sensor thread after [stop]. */
    fun flushPending() = aligner.flush()

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        val t = event.timestamp
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> aligner.onAccel(t, v[0], v[1], v[2])
            Sensor.TYPE_GYROSCOPE -> aligner.onGyro(t, v[0], v[1], v[2])
            Sensor.TYPE_GRAVITY -> aligner.onGravity(t, v[0], v[1], v[2])
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                // values[3] (w) is optional on some devices; rebuild it if missing.
                val w = if (v.size > 3) v[3] else sqrt(max(0f, 1f - v[0] * v[0] - v[1] * v[1] - v[2] * v[2]))
                aligner.onRotation(t, v[0], v[1], v[2], w)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    /** Sensor names/ranges for the session's meta.json. */
    fun describe(): List<SensorDescription> = listOf(
        "accelerometer" to accel,
        "gyroscope" to gyro,
        "gravity" to gravity,
        "game_rotation_vector" to rotation,
    ).mapNotNull { (role, s) ->
        s?.let {
            SensorDescription(
                role = role,
                name = it.name,
                vendor = it.vendor,
                type = it.type,
                maxRange = it.maximumRange.toDouble(),
                resolution = it.resolution.toDouble(),
                minDelayUs = it.minDelay,
            )
        }
    }
}
