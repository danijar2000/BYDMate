package com.bydmate.app.stations.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.stations.core.StationsTimeFormat

/** Вкладка «Станции»: карта зарядных станций с сервера-агрегатора. */
@Composable
fun StationsMapScreen(viewModel: StationsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val stations by viewModel.stations.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val availableTypes by viewModel.availableTypes.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val position by viewModel.position.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val lastSuccessAt by viewModel.lastSuccessAt.collectAsStateWithLifecycle()

    val controller = rememberStationsMapController()

    Box(Modifier.fillMaxSize()) {
        StationsOsmMap(
            controller = controller,
            stations = stations,
            userPosition = position,
            online = online,
            onStationTap = { viewModel.select(it) },
            initialRadiusMeters = settings.defaultRadiusMeters,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusChip(
                stationCount = stations.size,
                freeCount = stations.sumOf { it.free ?: 0 },
                totalCount = stations.sumOf { it.total },
                staleCount = stations.count { it.isStale },
                lastSuccessAt = lastSuccessAt,
                online = online,
                loading = sync.loading,
                initialLoad = sync.initialLoad,
            )
            StationsFilterBar(
                state = filters,
                visibility = settings.filterVisibility,
                availableTypes = availableTypes,
                onChange = viewModel::setFilters,
                onReset = viewModel::resetFilters,
            )
        }

        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallFloatingActionButton(onClick = { controller.zoomIn() }) {
                Icon(Icons.Default.Add, contentDescription = "Приблизить")
            }
            SmallFloatingActionButton(onClick = { controller.zoomOut() }) {
                Icon(Icons.Default.Remove, contentDescription = "Отдалить")
            }
            SmallFloatingActionButton(
                onClick = {
                    position?.let {
                        controller.centerOn(it.lat, it.lng, settings.defaultRadiusMeters)
                    }
                },
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Моё местоположение")
            }
        }

        ExtendedFloatingActionButton(
            onClick = { viewModel.refreshNow() },
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
        ) {
            if (sync.loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Refresh, contentDescription = null)
            }
            Text("  Обновить")
        }
    }

    selected?.let { station ->
        StationSheet(
            station = station,
            userLat = position?.lat,
            userLng = position?.lng,
            onDismiss = { viewModel.select(null) },
            onRoute = {
                NavigatorLauncher.route(
                    context = context,
                    app = settings.navigator,
                    lat = station.lat,
                    lng = station.lng,
                    name = station.name,
                )
            },
        )
    }
}

@Composable
private fun StatusChip(
    stationCount: Int,
    freeCount: Int,
    totalCount: Int,
    staleCount: Int,
    lastSuccessAt: Long?,
    online: Boolean,
    loading: Boolean,
    initialLoad: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(
                text = if (initialLoad) "Первая загрузка данных…"
                else "Станций: $stationCount · свободно: $freeCount / $totalCount",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when {
                    loading -> "Обновление…"
                    lastSuccessAt == null && !online -> "Нет сети — данные ещё не загружены"
                    lastSuccessAt == null -> "Нет данных — проверьте интернет"
                    // Без сети главное — показать, насколько данные свежие.
                    !online -> "Нет сети · обновлено ${StationsTimeFormat.ago(lastSuccessAt)}"
                    staleCount > 0 -> "Обновлено ${StationsTimeFormat.ago(lastSuccessAt)} · у $staleCount станций данные устарели"
                    else -> "Обновлено ${StationsTimeFormat.ago(lastSuccessAt)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
