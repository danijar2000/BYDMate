package com.bydmate.app.stations.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/** Результат GET /v1/stations. */
sealed class StationsFetch {
    /** Свежий слепок + ETag для следующего условного запроса. */
    data class Fresh(val stations: List<StationEntity>, val connectors: List<ConnectorEntity>, val etag: String?) : StationsFetch()

    /** 304 — слепок не менялся, ничего не перезаписывать. */
    object NotModified : StationsFetch()
}

/**
 * Единственный источник данных: сервер-агрегатор. В сети зарядных операторов
 * приложение не ходит вообще.
 *
 * Заголовок Accept-Encoding НЕ ставим вручную: OkHttp добавляет gzip сам и
 * прозрачно распаковывает ответ; ручной заголовок повесил бы распаковку на нас.
 *
 * ETag приходит через Cloudflare со слабым префиксом W/ — отправляем обратно
 * в If-None-Match как есть, не разбирая.
 */
class StationsApi(private val baseUrl: String = BASE_URL) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun fetchStations(etag: String?): StationsFetch = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/v1/stations")
            .apply { etag?.let { header("If-None-Match", it) } }
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 304 -> StationsFetch.NotModified
                !response.isSuccessful ->
                    throw IOException("HTTP ${response.code}: ${response.body?.string()?.take(200)}")
                else -> {
                    val body = response.body?.string() ?: throw IOException("пустой ответ")
                    val (stations, connectors) = parse(body)
                    StationsFetch.Fresh(stations, connectors, response.header("ETag"))
                }
            }
        }
    }

    companion object {
        const val BASE_URL = "https://charge.cqcode.org"

        /** Разбор StationsResponse. org.json — уже в зависимостях приложения. */
        fun parse(body: String): Pair<List<StationEntity>, List<ConnectorEntity>> {
            val root = JSONObject(body)
            val array = root.getJSONArray("stations")
            val stations = ArrayList<StationEntity>(array.length())
            val connectors = ArrayList<ConnectorEntity>()
            for (i in 0 until array.length()) {
                val s = array.getJSONObject(i)
                val stationId = s.getString("id")
                stations += StationEntity(
                    stationId = stationId,
                    network = s.getString("network"),
                    name = s.getString("name"),
                    address = s.optStringOrNull("address"),
                    lat = s.getDouble("lat"),
                    lng = s.getDouble("lng"),
                    promotions = s.optJSONArray("promotions")?.let { arr ->
                        List(arr.length()) { arr.getString(it) }
                    }.orEmpty(),
                    free = s.optIntOrNull("free"),
                    busy = s.optIntOrNull("busy"),
                    offline = s.optIntOrNull("offline"),
                    total = s.getInt("total"),
                    statusStale = s.optBoolean("status_stale", false),
                    statusUpdatedAt = s.optStringOrNull("status_updated_at")?.let(::parseRfc3339),
                    updatedAt = s.optStringOrNull("updated_at")?.let(::parseRfc3339) ?: 0L,
                )
                val cArr = s.getJSONArray("connectors")
                for (j in 0 until cArr.length()) {
                    val c = cArr.getJSONObject(j)
                    connectors += ConnectorEntity(
                        // id сервера уникален лишь внутри станции — префиксуем.
                        connectorId = "$stationId#${c.getString("id")}",
                        stationId = stationId,
                        type = c.getString("type"),
                        rawType = c.optStringOrNull("raw_type"),
                        power = c.getDouble("power"),
                        price = if (c.isNull("price")) null else c.getDouble("price"),
                        priceText = c.optStringOrNull("price_text"),
                    )
                }
            }
            return stations to connectors
        }

        /** RFC 3339 → epoch millis; и Z, и смещения. Мусор → null (лучше без даты, чем падение). */
        private fun parseRfc3339(value: String): Long? = runCatching {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        }.recoverCatching {
            Instant.parse(value).toEpochMilli()
        }.getOrNull()

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (isNull(key)) null else optInt(key)
    }
}
