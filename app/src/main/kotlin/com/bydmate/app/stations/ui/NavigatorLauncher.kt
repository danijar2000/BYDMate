package com.bydmate.app.stations.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.bydmate.app.stations.data.NavigatorApp
import java.util.Locale

/**
 * Открывает маршрут во внешнем навигаторе.
 *
 * Координаты форматируются через Locale.US: на русской локали %f даёт запятую
 * вместо точки, и ссылка молча ломается.
 */
object NavigatorLauncher {

    fun isInstalled(context: Context, app: NavigatorApp): Boolean {
        val packageName = app.packageName ?: return true
        return context.packageManager.getLaunchIntentForPackage(packageName) != null
    }

    fun route(context: Context, app: NavigatorApp, lat: Double, lng: Double, name: String) {
        val candidates = when (app) {
            NavigatorApp.ASK -> listOf(geoUri(lat, lng, name))
            NavigatorApp.DGIS -> listOf(
                // У 2GIS порядок обратный привычному: сначала долгота!
                deepLink("dgis://2gis.ru/routeSearch/rsType/car/to/${fmt(lng)},${fmt(lat)}", app),
                webLink("https://2gis.kg/routeSearch/rsType/car/to/${fmt(lng)},${fmt(lat)}"),
            )
            NavigatorApp.YANDEX_NAVI -> listOf(
                deepLink("yandexnavi://build_route_on_map?lat_to=${fmt(lat)}&lon_to=${fmt(lng)}", app),
                webLink("https://yandex.ru/maps/?rtext=~${fmt(lat)},${fmt(lng)}&rtt=auto"),
            )
            NavigatorApp.YANDEX_MAPS -> listOf(
                deepLink("yandexmaps://maps.yandex.ru/?rtext=~${fmt(lat)},${fmt(lng)}&rtt=auto", app),
                webLink("https://yandex.ru/maps/?rtext=~${fmt(lat)},${fmt(lng)}&rtt=auto"),
            )
            NavigatorApp.GOOGLE_MAPS -> listOf(
                deepLink("google.navigation:q=${fmt(lat)},${fmt(lng)}&mode=d", app),
                webLink("https://www.google.com/maps/dir/?api=1&destination=${fmt(lat)},${fmt(lng)}&travelmode=driving"),
            )
        }

        // Цепочка запасных вариантов: приложение → системный выбор по geo: →
        // веб-версия в браузере.
        val chain = candidates + geoUri(lat, lng, name)
        for (intent in chain) {
            if (intent.resolveActivity(context.packageManager) != null) {
                runCatching { context.startActivity(intent) }.onSuccess { return }
            }
        }
        // Последняя попытка — отдать системе и позволить ей самой решить.
        runCatching {
            context.startActivity(candidates.last())
        }.onFailure { if (it !is ActivityNotFoundException) throw it }
    }

    private fun deepLink(uri: String, app: NavigatorApp): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            app.packageName?.let { setPackage(it) }
        }

    private fun webLink(uri: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    private fun geoUri(lat: Double, lng: Double, name: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:${fmt(lat)},${fmt(lng)}?q=${fmt(lat)},${fmt(lng)}(${Uri.encode(name)})"))

    private fun fmt(value: Double): String = String.format(Locale.US, "%.6f", value)
}
