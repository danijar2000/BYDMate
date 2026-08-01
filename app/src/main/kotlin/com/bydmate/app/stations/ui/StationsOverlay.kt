package com.bydmate.app.stations.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.view.MotionEvent
import com.bydmate.app.stations.model.Station
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.hypot

/**
 * Все станции рисуются одним слоем на Canvas.
 *
 * Причина: сотни отдельных osmdroid Marker заметно просаживают
 * панорамирование — каждый из них View-подобный объект со своим драуаблом и
 * хит-тестом. Здесь один проход по видимой области и ручное попадание по
 * ближайшей точке.
 */
class StationsOverlay(
    private val density: Float,
    private val onTap: (Station) -> Unit,
) : Overlay() {

    var stations: List<Station> = emptyList()

    /** Ниже этого зума подписи не читаются — рисуем просто точки. */
    private val labelZoom = 12.0

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val reusablePoint = Point()
    private val geo = GeoPoint(0.0, 0.0)

    /** Экранные позиции последней отрисовки — по ним же ищем попадание пальцем. */
    private val hitBoxes = ArrayList<Pair<Station, Point>>()

    override fun draw(canvas: Canvas, projection: Projection) {
        val zoom = projection.zoomLevel
        val detailed = zoom >= labelZoom
        val radius = if (detailed) 11f * density else 4f * density

        ring.strokeWidth = if (detailed) 3f * density else 1.5f * density
        label.textSize = 12f * density

        hitBoxes.clear()
        val width = canvas.width
        val height = canvas.height
        val margin = (radius * 3).toInt()

        for (station in stations) {
            geo.setCoords(station.lat, station.lng)
            projection.toPixels(geo, reusablePoint)
            val x = reusablePoint.x
            val y = reusablePoint.y
            // Отсечение по видимой области: рисуем только то, что на экране.
            if (x < -margin || y < -margin || x > width + margin || y > height + margin) continue

            fill.color = fillColor(station)
            ring.color = station.network.colorArgb
            canvas.drawCircle(x.toFloat(), y.toFloat(), radius, fill)
            canvas.drawCircle(x.toFloat(), y.toFloat(), radius, ring)

            // Протухшая занятость — серый маркер БЕЗ счётчика: цифра часовой
            // давности вводила бы в заблуждение сильнее, чем её отсутствие.
            if (detailed && !station.isStale) {
                val baseline = y + (label.textSize / 3f)
                canvas.drawText(station.free.toString(), x.toFloat(), baseline, label)
            }
            hitBoxes += station to Point(x, y)
        }
    }

    private fun fillColor(station: Station): Int = when {
        station.isStale -> 0xFF9E9E9E.toInt()  // данные устарели
        station.hasFree -> 0xFF43A047.toInt()  // есть свободные
        else -> 0xFFE53935.toInt()             // все заняты
    }

    override fun onSingleTapConfirmed(event: MotionEvent, mapView: MapView): Boolean {
        val touchRadius = 24f * density
        var best: Station? = null
        var bestDistance = Float.MAX_VALUE
        for ((station, point) in hitBoxes) {
            val distance = hypot(event.x - point.x, event.y - point.y)
            if (distance <= touchRadius && distance < bestDistance) {
                bestDistance = distance
                best = station
            }
        }
        return best?.let { onTap(it); true } ?: false
    }
}
