package com.bydmate.app.stations

import com.bydmate.app.stations.data.StationsApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationsApiParseTest {

    private val body = """
        {
          "generated_at": "2026-08-01T10:00:00Z",
          "count": 2,
          "stations": [
            {
              "id": "spark:1234",
              "network": "spark",
              "name": "ТРЦ Бишкек Парк",
              "address": "ул. Киевская 148",
              "lat": 42.8746,
              "lng": 74.5698,
              "promotions": ["Скидка 10 % с 01-00 ночи до 09-00 утра"],
              "free": 2, "busy": 1, "offline": 0, "total": 3,
              "status_stale": false,
              "status_updated_at": "2026-08-01T09:58:00+06:00",
              "updated_at": "2026-08-01T09:58:00+06:00",
              "connectors": [
                {"id": "1", "type": "GBT_DC", "power": 60.0, "price": 14.0, "price_text": "14 сом/кВт·ч"},
                {"id": "2", "type": "SOME_NEW_TYPE", "raw_type": "Weird plug", "power": 7.0, "price": null}
              ]
            },
            {
              "id": "redpay:9",
              "network": "redpay",
              "name": "Протухшая",
              "lat": 42.9, "lng": 74.6,
              "free": null, "busy": null, "offline": null, "total": 4,
              "status_stale": true,
              "updated_at": "2026-08-01T05:00:00Z",
              "connectors": []
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `разбор полного ответа сервера`() {
        val (stations, connectors) = StationsApi.parse(body)
        assertEquals(2, stations.size)
        assertEquals(2, connectors.size)

        val spark = stations.first { it.stationId == "spark:1234" }
        assertEquals("spark", spark.network)
        assertEquals(2, spark.free)
        assertEquals(3, spark.total)
        assertEquals(listOf("Скидка 10 % с 01-00 ночи до 09-00 утра"), spark.promotions)
        assertTrue("RFC3339 со смещением +06:00 должен разбираться", spark.statusUpdatedAt!! > 0)
    }

    @Test
    fun `протухшая станция сохраняет null занятость и флаг stale`() {
        val (stations, _) = StationsApi.parse(body)
        val stale = stations.first { it.stationId == "redpay:9" }
        assertNull(stale.free)
        assertNull(stale.busy)
        assertTrue(stale.statusStale)
        // Координаты и total при этом валидны.
        assertEquals(4, stale.total)
        assertNull(stale.address)
    }

    @Test
    fun `коннектор с price null и без price_text`() {
        val (_, connectors) = StationsApi.parse(body)
        val unknown = connectors.first { it.connectorId == "spark:1234#2" }
        assertNull(unknown.price)
        assertNull(unknown.priceText)
        // Незнакомый тип сервера сохраняется как есть — в UNKNOWN его превратит чтение.
        assertEquals("SOME_NEW_TYPE", unknown.type)
        assertEquals("Weird plug", unknown.rawType)
    }

    @Test
    fun `id коннектора уникален между станциями за счёт префикса`() {
        val (_, connectors) = StationsApi.parse(body)
        assertEquals(connectors.size, connectors.map { it.connectorId }.distinct().size)
        assertTrue(connectors.all { it.connectorId.startsWith(it.stationId + "#") })
    }
}
