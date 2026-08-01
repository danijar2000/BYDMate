package com.bydmate.app.stations.model

/** Пустое множество означает «все» — так фильтр по умолчанию ничего не режет. */
data class FilterState(
    val networks: Set<ChargeNetwork> = emptySet(),
    val powers: Set<PowerBucket> = emptySet(),
    val types: Set<ConnectorType> = emptySet(),
    val maxPrice: Double? = null,
) {
    val isDefault: Boolean
        get() = networks.isEmpty() && powers.isEmpty() && types.isEmpty() && maxPrice == null
}

/** Варианты выпадающего списка максимальной цены. Реальный разброс — 10…16 сом. */
val PRICE_OPTIONS: List<Double?> = listOf(null, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0)

/**
 * Станция проходит фильтр, если у неё есть хотя бы один коннектор,
 * удовлетворяющий ОДНОВРЕМЕННО всем активным условиям.
 *
 * Это главное правило: AND внутри одного коннектора, OR между коннекторами.
 * Если проверять условия по станции целиком, запрос «120 кВт + CCS2» покажет
 * станцию, где 120 кВт стоит на GB/T, а CCS2 — на 20 кВт. Человек приедет,
 * а зарядиться не сможет.
 */
fun Station.matches(filter: FilterState): Boolean {
    if (filter.networks.isNotEmpty() && network !in filter.networks) return false

    if (filter.types.isEmpty() && filter.powers.isEmpty() && filter.maxPrice == null) return true

    return connectors.any { connector ->
        (filter.types.isEmpty() || connector.type in filter.types) &&
            (filter.powers.isEmpty() || filter.powers.any { connector.power in it }) &&
            // Неизвестная цена фильтр проходит: станция не должна молча
            // исчезать из-за пробела в данных — в карточке будет «цена не указана».
            (filter.maxPrice == null || connector.price == null || connector.price <= filter.maxPrice)
    }
}
