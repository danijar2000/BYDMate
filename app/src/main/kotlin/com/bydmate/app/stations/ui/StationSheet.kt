package com.bydmate.app.stations.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bydmate.app.stations.core.StationsGeo
import com.bydmate.app.stations.core.StationsTimeFormat
import com.bydmate.app.stations.model.ConnectorType
import com.bydmate.app.stations.model.Station

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationSheet(
    station: Station,
    userLat: Double?,
    userLng: Double?,
    onDismiss: () -> Unit,
    onRoute: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Прокрутка обязательна: у станции бывает до десятка коннекторов,
        // и без неё кнопка «Проложить маршрут» уезжает за нижний край.
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {

            Text(station.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            Row(
                Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(station.network.colorArgb).copy(alpha = 0.15f),
                ) {
                    Text(
                        station.network.title,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(station.network.colorArgb),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (userLat != null && userLng != null) {
                    val meters = StationsGeo.distanceMeters(userLat, userLng, station.lat, station.lng)
                    Text(
                        if (meters < 1000) "${meters.toInt()} м" else "%.1f км".format(meters / 1000),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            station.address?.takeIf { it.isNotBlank() }?.let {
                Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }

            // Протухшая занятость: счётчик скрываем, честно говорим почему.
            if (station.isStale) {
                Text(
                    "Данные о занятости устарели",
                    Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Свободно ${station.free} из ${station.total} коннекторов",
                    Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (station.hasFree) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                )
                if ((station.offline ?: 0) > 0) {
                    Text(
                        "Недоступно: ${station.offline}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Акции и ограничения доступа — как есть, из API. Скидку не
            // пересчитываем: сервер отдаёт базовый тариф даже внутри окна
            // скидки, поэтому любой расчёт был бы догадкой.
            if (station.promotions.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        station.promotions.forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 14.dp))

            Text("Коннекторы", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            station.connectors.forEach { connector ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            connector.type.title +
                                (connector.rawType?.takeIf { connector.type == ConnectorType.UNKNOWN }
                                    ?.let { " ($it)" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "${connector.power.toInt()} кВт",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        connector.priceText ?: "цена не указана",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (connector.price == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }

            station.statusUpdatedAt?.let {
                Text(
                    "Статусы обновлены ${StationsTimeFormat.ago(it)}",
                    Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(onClick = onRoute, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Icon(Icons.Default.Directions, contentDescription = null)
                Text("  Проложить маршрут")
            }
        }
    }
}
