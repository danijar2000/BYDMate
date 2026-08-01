package com.bydmate.app.stations.data

import android.content.Context
import android.util.Log
import com.bydmate.app.stations.model.ChargeNetwork
import com.bydmate.app.stations.model.Connector
import com.bydmate.app.stations.model.ConnectorType
import com.bydmate.app.stations.model.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Итог одного обновления — для индикатора в шапке карты. */
sealed class RefreshOutcome {
    object Updated : RefreshOutcome()
    object NotModified : RefreshOutcome()
    data class Failed(val message: String) : RefreshOutcome()
}

/**
 * Слепок сервера в Room + условные запросы. Вся работа с базой идёт из одной
 * корутины за раз (refresh не реентерабелен на уровне ViewModel), поэтому
 * пул транзакций Room не конкурирует.
 */
class StationsRepository(
    context: Context,
    private val db: StationsDatabase,
    private val api: StationsApi,
) {
    /** ETag и время успеха живут в prefs: терять их при пересоздании базы не страшно. */
    private val prefs = context.getSharedPreferences("charge_stations_sync", Context.MODE_PRIVATE)

    private val _lastSuccessAt = MutableStateFlow(prefs.getLong(KEY_LAST_SUCCESS, 0L).takeIf { it > 0 })
    val lastSuccessAt: StateFlow<Long?> = _lastSuccessAt.asStateFlow()

    fun observeStations(): Flow<List<Station>> = db.dao().observeAll()
        .map { rows ->
            rows.mapNotNull { row ->
                val network = ChargeNetwork.byId(row.station.network) ?: return@mapNotNull null
                Station(
                    id = row.station.stationId,
                    network = network,
                    name = row.station.name,
                    address = row.station.address,
                    lat = row.station.lat,
                    lng = row.station.lng,
                    promotions = row.station.promotions,
                    free = row.station.free,
                    busy = row.station.busy,
                    offline = row.station.offline,
                    total = row.station.total,
                    statusStale = row.station.statusStale,
                    statusUpdatedAt = row.station.statusUpdatedAt,
                    connectors = row.connectors.map { c ->
                        Connector(
                            id = c.connectorId,
                            type = ConnectorType.byName(c.type),
                            rawType = c.rawType,
                            power = c.power,
                            price = c.price,
                            priceText = c.priceText,
                        )
                    },
                )
            }
        }
        .flowOn(Dispatchers.Default)

    suspend fun isEmpty(): Boolean = db.dao().count() == 0

    /**
     * Один цикл обновления: условный GET, на 200 — полная замена слепка,
     * на 304 — только отметка успеха. ETag отправляется как пришёл
     * (вместе с W/ от Cloudflare) и никогда не разбирается.
     */
    suspend fun refresh(): RefreshOutcome = try {
        when (val fetch = api.fetchStations(etag = prefs.getString(KEY_ETAG, null))) {
            is StationsFetch.Fresh -> {
                db.dao().replaceAll(fetch.stations, fetch.connectors)
                prefs.edit()
                    .putString(KEY_ETAG, fetch.etag)
                    .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
                    .apply()
                _lastSuccessAt.value = System.currentTimeMillis()
                Log.i(TAG, "слепок обновлён: ${fetch.stations.size} станций, etag=${fetch.etag}")
                RefreshOutcome.Updated
            }
            StationsFetch.NotModified -> {
                prefs.edit().putLong(KEY_LAST_SUCCESS, System.currentTimeMillis()).apply()
                _lastSuccessAt.value = System.currentTimeMillis()
                RefreshOutcome.NotModified
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "обновление станций не удалось: ${e.message}")
        RefreshOutcome.Failed(e.message ?: "ошибка сети")
    }

    private companion object {
        const val TAG = "ChargeStations"
        const val KEY_ETAG = "etag"
        const val KEY_LAST_SUCCESS = "last_success_at"
    }
}
