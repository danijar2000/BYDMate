package com.bydmate.app.stations.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bydmate.app.stations.core.StationsGeo
import com.bydmate.app.stations.core.UserPosition
import com.bydmate.app.stations.model.Station
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.DirectedLocationOverlay
import kotlin.math.ln

/** Управление картой снаружи: зум и центрирование по кнопкам. */
class StationsMapController internal constructor(internal val map: MapView) {

    /**
     * Куда центрировать карту при первой разметке. Ставится, как только
     * приходит геопозиция; если её нет — останется Бишкек.
     */
    internal var pendingCenter: Pair<Double, Double>? = null

    fun zoomIn() = map.controller.zoomIn()
    fun zoomOut() = map.controller.zoomOut()

    fun centerOn(lat: Double, lng: Double, radiusMeters: Double? = null) {
        // Тот же порядок, что и при первом показе: сначала масштаб, потом центр.
        radiusMeters?.let { fitRadius(it, map.width) }
        map.controller.animateTo(GeoPoint(lat, lng), null, null)
    }

    /**
     * Точная подгонка зума под радиус.
     *
     * Формула ln(156543·cos(lat)/(m/px))/ln2 верна только для тайлов 256 px.
     * При isTilesScaledToDpi тайлы растягиваются под плотность экрана, и
     * фактический охват отличается примерно вдвое. Поэтому спрашиваем у самой
     * проекции, сколько пикселей приходится на радиус, и поправляем зум на
     * log2 отношения — одной итерации хватает, масштаб линеен по 2^zoom.
     */
    internal fun fitRadius(radiusMeters: Double, screenPx: Int) {
        if (screenPx <= 0) return
        val currentPx = map.projection.metersToPixels(radiusMeters.toFloat())
        if (currentPx <= 0f) return
        val desiredPx = screenPx / 2f
        val correction = ln((desiredPx / currentPx).toDouble()) / ln(2.0)
        val target = (map.zoomLevelDouble + correction)
            .coerceIn(map.minZoomLevel, map.maxZoomLevel)
        map.controller.setZoom(target)
    }

    /** Карта без сети рисует только то, что уже лежит в кэше тайлов. */
    fun setOnline(online: Boolean) {
        map.setUseDataConnection(online)
    }
}

@Composable
fun rememberStationsMapController(): StationsMapController {
    val context = LocalContext.current
    val map = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // Свои кнопки зума в Compose — встроенные выключаем.
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 5.0
            maxZoomLevel = 19.0
            isTilesScaledToDpi = true
        }
    }
    return remember(map) { StationsMapController(map) }
}

@Composable
fun StationsOsmMap(
    controller: StationsMapController,
    stations: List<Station>,
    userPosition: UserPosition?,
    online: Boolean,
    onStationTap: (Station) -> Unit,
    modifier: Modifier = Modifier,
    initialRadiusMeters: Double = 5_000.0,
) {
    val density = LocalContext.current.resources.displayMetrics.density
    val widthPx = LocalConfiguration.current.screenWidthDp * density

    val overlay = remember(controller) {
        StationsOverlay(density) { onStationTap(it) }.also { controller.map.overlays.add(it) }
    }

    /**
     * DirectedLocationOverlay, а не MyLocationNewOverlay: второй рисует стрелку
     * только при движении и у стоящей машины вырождается в точку.
     */
    val locationOverlay: DirectedLocationOverlay = remember(controller) {
        DirectedLocationOverlay(controller.map.context).apply {
            setEnabled(false)
            setShowAccuracy(true)
            controller.map.overlays.add(this)
        }
    }

    // Первый показ: центр и зум, при котором в экран попадает круг заданного
    // радиуса.
    //
    // Обязательно через addOnFirstLayoutListener: до первой разметки MapView
    // ещё не знает своего размера и молча игнорирует setZoom — карта
    // открывалась бы с охватом в десятки километров вместо пяти.
    remember(controller) {
        controller.map.addOnFirstLayoutListener { view, _, _, _, _ ->
            val screenPx = view.width.takeIf { it > 0 } ?: widthPx.toInt()
            val lat = controller.pendingCenter?.first ?: StationsGeo.BISHKEK_LAT
            val lng = controller.pendingCenter?.second ?: StationsGeo.BISHKEK_LNG
            // Порядок принципиален: setCenter ДО setZoom пересчитывает
            // прокрутку в пикселях старого масштаба, и после смены зума карта
            // уезжает на десятки километров. Сначала зум, потом центр, а
            // результат читать в следующем post {} — сразу после вызова
            // MapView возвращает старые значения.
            controller.map.controller.setZoom(
                StationsGeo.zoomForRadius(initialRadiusMeters, lat, screenPx)
            )
            controller.map.post {
                controller.map.controller.setCenter(GeoPoint(lat, lng))
                controller.map.post {
                    // Поправку масштаба считаем только когда центр уже верный:
                    // разрешение проекции зависит от широты, а до центрирования
                    // карта стоит в точке (0, 0) на экваторе.
                    controller.fitRadius(initialRadiusMeters, screenPx)
                }
            }
        }
        true
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.map.onResume()
                Lifecycle.Event.ON_PAUSE -> controller.map.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.map.onDetach()
        }
    }

    AndroidView(
        factory = { controller.map },
        modifier = modifier,
        update = {
            // Без сети osmdroid должен рисовать только кэш, иначе он будет
            // бесконечно пытаться дотянуться до тайлового сервера.
            controller.setOnline(online)
            overlay.stations = stations
            if (userPosition != null) {
                controller.pendingCenter = userPosition.lat to userPosition.lng
                locationOverlay.setEnabled(true)
                locationOverlay.location = GeoPoint(userPosition.lat, userPosition.lng)
                locationOverlay.setAccuracy(userPosition.accuracyMeters.toInt())
                userPosition.bearing?.let { locationOverlay.setBearing(it) }
            } else {
                locationOverlay.setEnabled(false)
            }
            it.invalidate()
        },
    )
}
