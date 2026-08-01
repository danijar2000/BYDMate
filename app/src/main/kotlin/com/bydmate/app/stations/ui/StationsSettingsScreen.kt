package com.bydmate.app.stations.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import com.bydmate.app.stations.data.FilterKind
import com.bydmate.app.stations.data.NavigatorApp
import com.bydmate.app.stations.data.RADIUS_OPTIONS

/**
 * Вкладка настроек карты станций. ViewModel общий с картой — сохранённое здесь
 * применяется на карте сразу.
 */
@Composable
fun StationsSettingsScreen(viewModel: StationsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        Text(
            "Настройки станций",
            Modifier.padding(start = 20.dp, top = 16.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        SectionTitle("Навигатор для маршрутов")
        NavigatorApp.entries.forEach { app ->
            val installed = NavigatorLauncher.isInstalled(context, app)
            Row(
                Modifier.fillMaxWidth()
                    .clickable { viewModel.setNavigator(app) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = settings.navigator == app,
                    onClick = { viewModel.setNavigator(app) },
                )
                Column(Modifier.padding(start = 8.dp)) {
                    Text(app.title, style = MaterialTheme.typography.bodyLarge)
                    if (!installed) {
                        Text(
                            "не установлен — откроется веб-версия",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionTitle("Охват карты при запуске")
        Text(
            "Радиус вокруг машины, который помещается на экран",
            Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RADIUS_OPTIONS.forEach { meters ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable { viewModel.setRadius(meters) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = settings.defaultRadiusMeters == meters,
                    onClick = { viewModel.setRadius(meters) },
                )
                Text(
                    "${(meters / 1000).toInt()} км",
                    Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionTitle("Показывать фильтры")
        FilterToggle("Сеть", settings.filterVisibility.networks) {
            viewModel.setFilterVisible(FilterKind.NETWORKS, it)
        }
        FilterToggle("Мощность", settings.filterVisibility.powers) {
            viewModel.setFilterVisible(FilterKind.POWERS, it)
        }
        FilterToggle("Тип коннектора", settings.filterVisibility.types) {
            viewModel.setFilterVisible(FilterKind.TYPES, it)
        }
        FilterToggle("Цена", settings.filterVisibility.price) {
            viewModel.setFilterVisible(FilterKind.PRICE, it)
        }
        Text(
            "Скрытый фильтр сбрасывает своё значение — иначе он продолжал бы " +
                "действовать невидимо.",
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "ChargeKG 1.0.0",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            // Атрибуция обязательна по лицензии OpenStreetMap.
            Text(
                "Карта: © OpenStreetMap contributors",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Данные: charge.cqcode.org",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun FilterToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
