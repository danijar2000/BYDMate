package com.bydmate.app.stations.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.stations.core.StationsConnectivity
import com.bydmate.app.stations.core.StationsLocationTracker
import com.bydmate.app.stations.core.UserPosition
import com.bydmate.app.stations.data.ChargeSettings
import com.bydmate.app.stations.data.FilterKind
import com.bydmate.app.stations.data.NavigatorApp
import com.bydmate.app.stations.data.RefreshOutcome
import com.bydmate.app.stations.data.StationsRepository
import com.bydmate.app.stations.data.StationsSettingsRepository
import com.bydmate.app.stations.model.ConnectorType
import com.bydmate.app.stations.model.FilterState
import com.bydmate.app.stations.model.Station
import com.bydmate.app.stations.model.matches
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StationsSyncState(
    val loading: Boolean = false,
    val initialLoad: Boolean = false,
    val lastOutcome: RefreshOutcome? = null,
)

@HiltViewModel
class StationsViewModel @Inject constructor(
    private val repository: StationsRepository,
    private val settingsRepository: StationsSettingsRepository,
    connectivity: StationsConnectivity,
    locationTracker: StationsLocationTracker,
) : ViewModel() {

    /** Есть ли интернет прямо сейчас — для индикатора и режима кэша карты. */
    val online: StateFlow<Boolean> = connectivity.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val allStations: StateFlow<List<Station>> = repository.observeStations()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val filters: StateFlow<FilterState> = settingsRepository.filters
    val settings: StateFlow<ChargeSettings> = settingsRepository.settings
    val lastSuccessAt: StateFlow<Long?> = repository.lastSuccessAt

    /** Отфильтрованные станции — это и есть то, что видно на карте. */
    val stations: StateFlow<List<Station>> =
        combine(allStations, filters) { list, filter -> list.filter { it.matches(filter) } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Типы коннекторов, реально встречающиеся в данных — из них строятся чипы. */
    val availableTypes: StateFlow<List<ConnectorType>> = allStations
        .map { list -> list.flatMap { it.connectors }.map { it.type }.distinct().sortedBy { it.ordinal } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _sync = MutableStateFlow(StationsSyncState())
    val sync: StateFlow<StationsSyncState> = _sync.asStateFlow()

    private val _selected = MutableStateFlow<Station?>(null)
    val selected: StateFlow<Station?> = _selected.asStateFlow()

    val position: StateFlow<UserPosition?> = locationTracker.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            // Первый запуск скачивает слепок целиком; дальше — тот же эндпоинт
            // с If-None-Match.
            val first = repository.isEmpty()
            _sync.value = _sync.value.copy(loading = true, initialLoad = first)
            val outcome = repository.refresh()
            _sync.value = _sync.value.copy(loading = false, initialLoad = false, lastOutcome = outcome)
            startAutoRefresh()
        }
    }

    /**
     * Обновление раз в 5 минут. Таймер привязан к ViewModel, то есть работает
     * только пока вкладка открыта. Фоновой синхронизации нет намеренно —
     * бережём трафик и батарею головного устройства.
     */
    private fun startAutoRefresh() {
        viewModelScope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                runRefresh()
            }
        }
    }

    fun refreshNow() = viewModelScope.launch { runRefresh() }

    private suspend fun runRefresh() {
        if (_sync.value.loading) return
        _sync.value = _sync.value.copy(loading = true)
        val outcome = repository.refresh()
        _sync.value = _sync.value.copy(loading = false, lastOutcome = outcome)
    }

    fun select(station: Station?) {
        _selected.value = station
    }

    fun setFilters(state: FilterState) = settingsRepository.setFilters(state)
    fun resetFilters() = settingsRepository.resetFilters()
    fun setNavigator(app: NavigatorApp) = settingsRepository.setNavigator(app)
    fun setRadius(meters: Double) = settingsRepository.setRadius(meters)
    fun setFilterVisible(kind: FilterKind, visible: Boolean) =
        settingsRepository.setFilterVisible(kind, visible)

    private companion object {
        const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L
    }
}
