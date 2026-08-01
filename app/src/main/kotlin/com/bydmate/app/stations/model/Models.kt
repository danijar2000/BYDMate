package com.bydmate.app.stations.model

/**
 * Сеть зарядных станций. Значения enum сервера /v1/stations (network).
 * Цвет — кольцо маркера на карте.
 */
enum class ChargeNetwork(val id: String, val title: String, val colorArgb: Int) {
    SPARK("spark", "SPARK", 0xFF4CB848.toInt()),
    WEWAY("weway", "We way", 0xFF1E88E5.toInt()),
    EVION("evion", "EVION", 0xFFFF9800.toInt()),
    CHARGE24("charge24", "Charge24", 0xFF8E24AA.toInt()),
    REDPAY("redpay", "RedPay", 0xFFE53935.toInt());

    companion object {
        fun byId(id: String): ChargeNetwork? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Нормализованный тип коннектора — сервер уже привёл названия сетей к единому
 * enum, клиент ничего не разбирает. Незнакомая строка от нового сервера
 * превращается в [UNKNOWN], а сырое значение показывается в карточке.
 */
enum class ConnectorType(val title: String) {
    GBT_DC("GB/T (DC)"),
    GBT_AC("GB/T (AC)"),
    CCS2("CCS2"),
    CCS1("CCS1"),
    CHADEMO("CHAdeMO"),
    TESLA("Tesla"),
    TYPE2_AC("Type 2 (AC)"),
    TYPE1_AC("Type 1 (AC)"),
    UNKNOWN("Другой");

    companion object {
        fun byName(raw: String?): ConnectorType =
            entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

/**
 * Бакеты мощности для фильтра. Границы — официальные диапазоны We way
 * (slow 7–22, medium 23–40, fast 41–60, ultrafast 61+); без бакетов фильтр
 * превратился бы в дюжину чипов из сырых значений 7…480 кВт.
 */
enum class PowerBucket(val title: String, val min: Double, val max: Double) {
    SLOW("до 22 кВт", 0.0, 22.0),
    MEDIUM("23–40 кВт", 22.001, 40.0),
    FAST("41–60 кВт", 40.001, 60.0),
    ULTRAFAST("от 61 кВт", 60.001, Double.MAX_VALUE);

    operator fun contains(power: Double): Boolean = power >= min && power <= max
}

/** Коннектор в том виде, в каком его показывает интерфейс. */
data class Connector(
    val id: String,
    val type: ConnectorType,
    /** Исходное название из API сети — показывается при type == UNKNOWN. */
    val rawType: String?,
    val power: Double,
    /** null — сеть цену не отдала; в интерфейсе «цена не указана». */
    val price: Double?,
    val priceText: String?,
)

/**
 * Станция как её отдаёт сервер. [free]/[busy]/[offline] могут быть null:
 * сервер помечает так протухшую занятость ([statusStale]), но координаты,
 * цены и типы разъёмов при этом валидны — станция показывается серым
 * маркером без счётчика, а не скрывается.
 */
data class Station(
    val id: String,
    val network: ChargeNetwork,
    val name: String,
    val address: String?,
    val lat: Double,
    val lng: Double,
    /** Акции и ограничения доступа текстом как есть, из API сети. */
    val promotions: List<String>,
    val free: Int?,
    val busy: Int?,
    val offline: Int?,
    val total: Int,
    val statusStale: Boolean,
    /** Когда занятость обновлялась в последний раз, epoch millis. */
    val statusUpdatedAt: Long?,
    val connectors: List<Connector>,
) {
    /** Занятость недостоверна — рисуем серым и без счётчика. */
    val isStale: Boolean get() = statusStale || free == null
    val hasFree: Boolean get() = (free ?: 0) > 0
}
