package com.bydmate.app.stations

import com.bydmate.app.stations.model.ChargeNetwork
import com.bydmate.app.stations.model.Connector
import com.bydmate.app.stations.model.ConnectorType
import com.bydmate.app.stations.model.FilterState
import com.bydmate.app.stations.model.PowerBucket
import com.bydmate.app.stations.model.Station
import com.bydmate.app.stations.model.matches
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationFilterTest {

    private fun station(vararg connectors: Connector) = Station(
        id = "spark:1", network = ChargeNetwork.SPARK, name = "s", address = null,
        lat = 42.8, lng = 74.5, promotions = emptyList(),
        free = 1, busy = 0, offline = 0, total = connectors.size,
        statusStale = false, statusUpdatedAt = null,
        connectors = connectors.toList(),
    )

    private fun connector(
        type: ConnectorType = ConnectorType.GBT_DC,
        power: Double = 60.0,
        price: Double? = 14.0,
    ) = Connector("c", type, null, power, price, null)

    @Test
    fun `условия проверяются по одному коннектору целиком`() {
        // 120 кВт стоит на GB/T, а CCS2 — на 20 кВт: станция НЕ должна пройти
        // фильтр «ULTRAFAST + CCS2», иначе человек приедет и не зарядится.
        val s = station(
            connector(type = ConnectorType.GBT_DC, power = 120.0),
            connector(type = ConnectorType.CCS2, power = 20.0),
        )
        val filter = FilterState(
            powers = setOf(PowerBucket.ULTRAFAST),
            types = setOf(ConnectorType.CCS2),
        )
        assertFalse(s.matches(filter))
        // А один коннектор, удовлетворяющий обоим условиям сразу — проходит.
        val ok = station(connector(type = ConnectorType.CCS2, power = 120.0))
        assertTrue(ok.matches(filter))
    }

    @Test
    fun `коннектор с неизвестной ценой проходит ценовой фильтр`() {
        val s = station(connector(price = null))
        assertTrue(s.matches(FilterState(maxPrice = 12.0)))
    }

    @Test
    fun `ценовой фильтр режет дорогие коннекторы`() {
        val s = station(connector(price = 16.0))
        assertFalse(s.matches(FilterState(maxPrice = 12.0)))
        assertTrue(s.matches(FilterState(maxPrice = 16.0)))
    }

    @Test
    fun `фильтр по сети`() {
        val s = station(connector())
        assertTrue(s.matches(FilterState(networks = setOf(ChargeNetwork.SPARK))))
        assertFalse(s.matches(FilterState(networks = setOf(ChargeNetwork.WEWAY))))
    }

    @Test
    fun `пустой фильтр пропускает всё`() {
        assertTrue(station(connector()).matches(FilterState()))
    }
}
