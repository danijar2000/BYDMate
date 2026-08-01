package com.bydmate.app.stations.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.bydmate.app.service.TrackingService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class UserPosition(
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Float,
    /** Направление в градусах, 0 = север. null — датчика нет и GPS стоит. */
    val bearing: Float?,
)

/**
 * Положение машины и направление стрелки.
 *
 * Координаты берутся из уже работающего GPS BYDMate ([TrackingService.lastLocation]) —
 * второй подписчик LocationManager здесь был бы лишним. Азимут — с датчика
 * TYPE_ROTATION_VECTOR, а не из Location.bearing: bearing есть только при
 * движении, у стоящей машины стрелка выродилась бы в точку. Если датчика в
 * головном устройстве нет, остаётся GPS-bearing как запасной вариант.
 */
class StationsLocationTracker(private val context: Context) {

    fun observe(): Flow<UserPosition?> =
        combine(TrackingService.lastLocation, sensorAzimuth()) { location, azimuth ->
            location?.let {
                UserPosition(
                    lat = it.latitude,
                    lng = it.longitude,
                    accuracyMeters = it.accuracy,
                    bearing = azimuth ?: it.bearing.takeIf { _ -> it.hasBearing() },
                )
            }
        }

    private fun sensorAzimuth(): Flow<Float?> = callbackFlow {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor == null) {
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }

        trySend(null)
        val smoother = AzimuthSmoother()
        var lastEmit = 0L

        val listener = object : SensorEventListener {
            private val rotation = FloatArray(9)
            private val orientation = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                val degrees = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
                val smoothed = smoother.next(degrees)
                // Не чаще 10 Гц: иначе перерисовка карты съедает кадры.
                val now = System.currentTimeMillis()
                if (now - lastEmit >= 100) {
                    lastEmit = now
                    trySend(smoothed)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)

        awaitClose { sensorManager.unregisterListener(listener) }
    }
}

/**
 * Сглаживание азимута. Усреднять сами градусы нельзя: на переходе 359° → 0°
 * получился бы разворот стрелки через весь круг. Поэтому усредняем синус и
 * косинус угла.
 */
private class AzimuthSmoother(private val alpha: Float = 0.15f) {
    private var sinValue = 0f
    private var cosValue = 0f
    private var initialized = false

    fun next(degrees: Float): Float {
        val radians = Math.toRadians(degrees.toDouble())
        val s = sin(radians).toFloat()
        val c = cos(radians).toFloat()
        if (!initialized) {
            sinValue = s
            cosValue = c
            initialized = true
        } else {
            sinValue += alpha * (s - sinValue)
            cosValue += alpha * (c - cosValue)
        }
        return ((Math.toDegrees(atan2(sinValue, cosValue).toDouble()).toFloat()) + 360f) % 360f
    }
}
