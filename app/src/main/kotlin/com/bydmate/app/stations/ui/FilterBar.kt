package com.bydmate.app.stations.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bydmate.app.stations.data.FilterVisibility
import com.bydmate.app.stations.model.ChargeNetwork
import com.bydmate.app.stations.model.ConnectorType
import com.bydmate.app.stations.model.FilterState
import com.bydmate.app.stations.model.PRICE_OPTIONS
import com.bydmate.app.stations.model.PowerBucket

/**
 * Панель фильтров. Значения сохраняются в prefs, поэтому переживают
 * перезапуск приложения; в конце — кнопка сброса.
 */
@Composable
fun StationsFilterBar(
    state: FilterState,
    visibility: FilterVisibility,
    availableTypes: List<ConnectorType>,
    onChange: (FilterState) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (visibility.networks) {
            ChargeNetwork.entries.forEach { network ->
                val selected = network in state.networks
                FilterChip(
                    selected = selected,
                    onClick = { onChange(state.copy(networks = state.networks.toggle(network))) },
                    label = { Text(network.title) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(network.colorArgb).copy(alpha = 0.2f),
                    ),
                )
            }
        }

        if (visibility.powers) {
            PowerBucket.entries.forEach { bucket ->
                FilterChip(
                    selected = bucket in state.powers,
                    onClick = { onChange(state.copy(powers = state.powers.toggle(bucket))) },
                    label = { Text(bucket.title) },
                )
            }
        }

        if (visibility.types) {
            availableTypes.forEach { type ->
                FilterChip(
                    selected = type in state.types,
                    onClick = { onChange(state.copy(types = state.types.toggle(type))) },
                    label = { Text(type.title) },
                )
            }
        }

        if (visibility.price) {
            PriceDropdown(
                selected = state.maxPrice,
                onSelect = { onChange(state.copy(maxPrice = it)) },
            )
        }

        FilterChip(
            selected = false,
            enabled = !state.isDefault,
            onClick = onReset,
            label = { Text("Сбросить") },
            leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
        )
    }
}

@Composable
private fun PriceDropdown(selected: Double?, onSelect: (Double?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    FilterChip(
        selected = selected != null,
        onClick = { expanded = true },
        label = { Text(selected?.let { "до ${it.toInt()} сом" } ?: "Цена") },
        trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        PRICE_OPTIONS.forEach { price ->
            DropdownMenuItem(
                text = { Text(price?.let { "до ${it.toInt()} сом/кВт·ч" } ?: "Любая цена") },
                onClick = {
                    onSelect(price)
                    expanded = false
                },
            )
        }
    }
}

private fun <T> Set<T>.toggle(item: T): Set<T> =
    if (item in this) this - item else this + item
