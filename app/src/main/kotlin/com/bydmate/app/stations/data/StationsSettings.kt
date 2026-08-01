package com.bydmate.app.stations.data

import android.content.Context
import android.content.SharedPreferences
import com.bydmate.app.stations.model.ChargeNetwork
import com.bydmate.app.stations.model.ConnectorType
import com.bydmate.app.stations.model.FilterState
import com.bydmate.app.stations.model.PowerBucket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Какие блоки фильтров показывать. Скрытый фильтр обязан сбрасывать значение. */
data class FilterVisibility(
    val networks: Boolean = true,
    val powers: Boolean = true,
    val types: Boolean = true,
    val price: Boolean = true,
)

enum class FilterKind { NETWORKS, POWERS, TYPES, PRICE }

enum class NavigatorApp(val id: String, val title: String, val packageName: String?) {
    ASK("ask", "Спрашивать каждый раз", null),
    DGIS("dgis", "2GIS", "ru.dublgis.dgismobile"),
    YANDEX_NAVI("ynavi", "Яндекс Навигатор", "ru.yandex.yandexnavi"),
    YANDEX_MAPS("ymaps", "Яндекс Карты", "ru.yandex.yandexmaps"),
    GOOGLE_MAPS("gmaps", "Google Карты", "com.google.android.apps.maps");

    companion object {
        /** 2GIS — навигатор по умолчанию (требование плана). */
        fun byId(id: String?): NavigatorApp = entries.firstOrNull { it.id == id } ?: DGIS
    }
}

data class ChargeSettings(
    val navigator: NavigatorApp = NavigatorApp.DGIS,
    /**
     * Стартовый охват карты в метрах. Храним радиус, а не уровень зума:
     * конкретный зум пересчитывается под ширину экрана, и настройка
     * одинаково выглядит на любом дисплее.
     */
    val defaultRadiusMeters: Double = 5_000.0,
    val filterVisibility: FilterVisibility = FilterVisibility(),
)

val RADIUS_OPTIONS = listOf(2_000.0, 5_000.0, 10_000.0, 20_000.0)

/**
 * Настройки и значения фильтров на SharedPreferences (DataStore в BYDMate не
 * заведён — тянуть зависимость ради четырёх ключей незачем). Каждое поле
 * дублируется в StateFlow, чтобы Compose пересобирался сразу.
 */
class StationsSettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("charge_stations_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<ChargeSettings> = _settings.asStateFlow()

    private val _filters = MutableStateFlow(readFilters())
    val filters: StateFlow<FilterState> = _filters.asStateFlow()

    fun setNavigator(app: NavigatorApp) {
        prefs.edit().putString(KEY_NAVIGATOR, app.id).apply()
        _settings.value = readSettings()
    }

    fun setRadius(meters: Double) {
        prefs.edit().putFloat(KEY_RADIUS, meters.toFloat()).apply()
        _settings.value = readSettings()
    }

    /**
     * Скрытый фильтр сбрасывает своё значение. Иначе он продолжил бы
     * действовать невидимо, и станции «пропадали» бы без объяснения.
     */
    fun setFilterVisible(kind: FilterKind, visible: Boolean) {
        val e = prefs.edit()
        when (kind) {
            FilterKind.NETWORKS -> {
                e.putBoolean(KEY_SHOW_NETWORKS, visible)
                if (!visible) e.remove(KEY_F_NETWORKS)
            }
            FilterKind.POWERS -> {
                e.putBoolean(KEY_SHOW_POWERS, visible)
                if (!visible) e.remove(KEY_F_POWERS)
            }
            FilterKind.TYPES -> {
                e.putBoolean(KEY_SHOW_TYPES, visible)
                if (!visible) e.remove(KEY_F_TYPES)
            }
            FilterKind.PRICE -> {
                e.putBoolean(KEY_SHOW_PRICE, visible)
                if (!visible) e.remove(KEY_F_MAX_PRICE)
            }
        }
        e.apply()
        _settings.value = readSettings()
        _filters.value = readFilters()
    }

    fun setFilters(state: FilterState) {
        prefs.edit()
            .putStringSet(KEY_F_NETWORKS, state.networks.map { it.id }.toSet())
            .putStringSet(KEY_F_POWERS, state.powers.map { it.name }.toSet())
            .putStringSet(KEY_F_TYPES, state.types.map { it.name }.toSet())
            .apply { state.maxPrice?.let { putFloat(KEY_F_MAX_PRICE, it.toFloat()) } ?: remove(KEY_F_MAX_PRICE) }
            .apply()
        _filters.value = readFilters()
    }

    fun resetFilters() = setFilters(FilterState())

    private fun readSettings() = ChargeSettings(
        navigator = NavigatorApp.byId(prefs.getString(KEY_NAVIGATOR, null)),
        defaultRadiusMeters = prefs.getFloat(KEY_RADIUS, 5_000f).toDouble(),
        filterVisibility = FilterVisibility(
            networks = prefs.getBoolean(KEY_SHOW_NETWORKS, true),
            powers = prefs.getBoolean(KEY_SHOW_POWERS, true),
            types = prefs.getBoolean(KEY_SHOW_TYPES, true),
            price = prefs.getBoolean(KEY_SHOW_PRICE, true),
        ),
    )

    private fun readFilters() = FilterState(
        networks = prefs.getStringSet(KEY_F_NETWORKS, null).orEmpty()
            .mapNotNull { ChargeNetwork.byId(it) }.toSet(),
        powers = prefs.getStringSet(KEY_F_POWERS, null).orEmpty()
            .mapNotNull { name -> PowerBucket.entries.firstOrNull { it.name == name } }.toSet(),
        types = prefs.getStringSet(KEY_F_TYPES, null).orEmpty()
            .mapNotNull { name -> ConnectorType.entries.firstOrNull { it.name == name } }.toSet(),
        maxPrice = if (prefs.contains(KEY_F_MAX_PRICE)) prefs.getFloat(KEY_F_MAX_PRICE, 0f).toDouble() else null,
    )

    private companion object {
        const val KEY_NAVIGATOR = "navigator"
        const val KEY_RADIUS = "radius"
        const val KEY_SHOW_NETWORKS = "show_networks"
        const val KEY_SHOW_POWERS = "show_powers"
        const val KEY_SHOW_TYPES = "show_types"
        const val KEY_SHOW_PRICE = "show_price"
        const val KEY_F_NETWORKS = "f_networks"
        const val KEY_F_POWERS = "f_powers"
        const val KEY_F_TYPES = "f_types"
        const val KEY_F_MAX_PRICE = "f_max_price"
    }
}
