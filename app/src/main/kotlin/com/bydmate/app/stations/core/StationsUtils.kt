package com.bydmate.app.stations.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

object StationsGeo {

    /** Центр Бишкека — стартовая позиция карты, если геопозиции ещё нет. */
    const val BISHKEK_LAT = 42.8746
    const val BISHKEK_LNG = 74.5698

    /** Расстояние по большому кругу, метры. */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(sqrt(a))
    }

    /**
     * Уровень зума, при котором экран шириной [screenPx] покрывает круг
     * радиусом [radiusMeters]. Разрешение веб-меркатора:
     * m/px = 156543.034 · cos(lat) / 2^z.
     */
    fun zoomForRadius(radiusMeters: Double, lat: Double, screenPx: Int): Double {
        val metersPerPixel = 2 * radiusMeters / screenPx
        return ln(156543.034 * cos(Math.toRadians(lat)) / metersPerPixel) / ln(2.0)
    }
}

object StationsTimeFormat {

    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** «только что» / «5 минут назад» / «в 14:32» — индикатор свежести данных. */
    fun ago(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = (now - timestamp) / 60_000
        return when {
            minutes < 1 -> "только что"
            minutes < 60 -> "$minutes ${plural(minutes, "минуту", "минуты", "минут")} назад"
            minutes < 24 * 60 -> "в ${clockFormat.format(Date(timestamp))}"
            else -> "более суток назад"
        }
    }

    private fun plural(n: Long, one: String, few: String, many: String): String {
        val mod100 = n % 100
        if (mod100 in 11..14) return many
        return when (n % 10) {
            1L -> one
            2L, 3L, 4L -> few
            else -> many
        }
    }
}

/**
 * Есть ли сейчас интернет. Нужно для двух вещей: показать в шапке, что данные
 * офлайновые, и перевести osmdroid в режим кэша, чтобы он не долбился за
 * тайлами в пустоту.
 */
class StationsConnectivity(private val context: Context) {

    fun isOnline(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun observe(): Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }

        trySend(isOnline())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(isOnline()) }
            override fun onLost(network: Network) { trySend(isOnline()) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(isOnline())
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        manager.registerNetworkCallback(request, callback)

        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}
