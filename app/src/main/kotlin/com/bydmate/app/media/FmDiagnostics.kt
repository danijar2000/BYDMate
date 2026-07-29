package com.bydmate.app.media

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * On-device probe for a built-in FM tuner, run from Settings → Радио.
 *
 * Android exposes no public tuner API — `RadioManager` sits behind the privileged
 * `ACCESS_BROADCAST_RADIO` permission — so this cannot tune anything. What it can do is answer
 * whether a tuner is reachable *at all* on this head unit, and through what: a broadcast-radio
 * HAL, an FM audio device, or (most likely on DiLink) a vendor app that owns the MCU link.
 *
 * Everything here is read-only and works without root. The report is meant to be copied out of
 * the app and read by a human, so it stays plain text and stays short.
 */
object FmDiagnostics {

    /** Package names that look like a radio/tuner app. */
    private val PACKAGE_HINT = Regex("radio|tuner|\\bfm\\b|\\.fm|fmradio", RegexOption.IGNORE_CASE)

    /** Lines worth keeping out of `service list` / `getprop`, which are thousands of lines long. */
    private val LINE_HINT = Regex("radio|tuner|\\bfm\\b", RegexOption.IGNORE_CASE)

    private const val MAX_COMPONENTS = 12
    private const val MAX_LINES = 25

    suspend fun collect(context: Context): String = withContext(Dispatchers.IO) {
        val out = StringBuilder()
        val pm = context.packageManager

        out.section("verdict")
        out.line(verdict(context, pm))

        out.section("device")
        out.line("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        out.line("Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT}")
        out.line("fingerprint: ${Build.FINGERPRINT}")

        out.section("system features")
        val features = runCatching {
            pm.systemAvailableFeatures.mapNotNull { it.name }.filter { PACKAGE_HINT.containsMatchIn(it) }
        }.getOrDefault(emptyList())
        out.line("android.hardware.broadcastradio: " + yesNo(pm.hasSystemFeature("android.hardware.broadcastradio")))
        out.line(if (features.isEmpty()) "no other radio-ish features" else features.joinToString("\n"))

        out.section("broadcast radio service")
        // The hidden Context.RADIO_SERVICE constant; null here means no HAL an app could ever reach.
        val radioService = runCatching { context.getSystemService("broadcastradio") }.getOrNull()
        out.line("getSystemService(\"broadcastradio\"): " + (radioService?.javaClass?.name ?: "null"))

        out.section("audio devices")
        out.line(audioDevices(context))

        out.section("candidate radio apps")
        out.line(radioApps(pm))

        out.section("service list")
        out.line(shell(listOf("service", "list"), filter = true))

        out.section("getprop")
        out.line(shell(listOf("getprop"), filter = true))

        out.toString().trim()
    }

    /**
     * Factory radio apps that can be opened — **system** packages only.
     *
     * The name filter alone is not enough: a sideloaded internet-radio player (Radio Record and
     * friends) carries "radio" in its package name too, and offering it as the car's stock tuner
     * is a lie. A real factory app ships in the system image, so that is the test.
     */
    fun launchableRadioApps(context: Context): List<String> {
        val pm = context.packageManager
        return installedPackages(pm).getOrDefault(emptyList())
            .filter { PACKAGE_HINT.containsMatchIn(it) && it != context.packageName }
            .filter { isSystemApp(pm, it) }
            .filter { pm.getLaunchIntentForPackage(it) != null }
            .sortedByDescending { it.startsWith("com.byd") }
    }

    /**
     * Reads the vendor verdict on the tuner before any of the softer signals.
     *
     * `sys.fm.hasfmchip` is set by the firmware itself and outranks everything below it: the
     * audio HAL advertises an FM_TUNER port straight out of the Qualcomm reference config
     * whether or not the chip was ever fitted, so that port alone proves nothing.
     */
    private fun verdict(context: Context, pm: PackageManager): String {
        val chipProp = shell(listOf("getprop", "sys.fm.hasfmchip"), filter = false).trim()
        val hasHal = pm.hasSystemFeature("android.hardware.broadcastradio") ||
            runCatching { context.getSystemService("broadcastradio") }.getOrNull() != null
        val stockApps = launchableRadioApps(context)

        val chipLine = when (chipProp) {
            "0" -> "sys.fm.hasfmchip=0 -> NO FM CHIP fitted"
            "1" -> "sys.fm.hasfmchip=1 -> FM chip present"
            else -> "sys.fm.hasfmchip unreadable ($chipProp)"
        }
        val summary = when {
            chipProp == "0" -> "FM tuner: NOT AVAILABLE (no hardware)"
            hasHal -> "FM tuner: possibly reachable via broadcast radio HAL"
            stockApps.isNotEmpty() -> "FM tuner: only through the stock app ${stockApps.first()}"
            else -> "FM tuner: no HAL, no stock app — not reachable from a normal app"
        }
        return "$summary\n$chipLine\nbroadcast radio HAL: " + yesNo(hasHal) +
            "\nstock radio apps: " + (stockApps.joinToString().ifEmpty { "none" })
    }

    /** Package listing can blow the binder buffer on a loaded head unit — say so, never fake "none". */
    private fun installedPackages(pm: PackageManager): Result<List<String>> = runCatching {
        pm.getInstalledApplications(0).map { it.packageName }
    }.recoverCatching {
        // Fallback: everything with a launcher entry. Smaller payload, misses headless packages.
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0
        ).map { it.activityInfo.packageName }.distinct()
    }

    private fun isSystemApp(pm: PackageManager, packageName: String): Boolean = runCatching {
        val flags = pm.getApplicationInfo(packageName, 0).flags
        flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }.getOrDefault(false)

    private fun audioDevices(context: Context): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "AudioManager unavailable"
        // Spelled out rather than GET_DEVICES_ALL, which is the same value but outside the
        // @IntDef lint accepts here. An FM tuner shows up as an input, the FM path as an output.
        val both = AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS
        val devices = runCatching { am.getDevices(both) }
            .getOrNull()
            ?: return "getDevices failed"
        val fm = devices.filter { it.type == AudioDeviceInfo.TYPE_FM || it.type == AudioDeviceInfo.TYPE_FM_TUNER }
        val header = "FM audio device present: " + yesNo(fm.isNotEmpty())
        val all = devices.joinToString("\n") { d ->
            "  type=${d.type} ${typeName(d.type)} ${if (d.isSource) "in" else "out"} \"${d.productName}\""
        }
        return "$header\n$all"
    }

    private fun radioApps(pm: PackageManager): String {
        val listing = installedPackages(pm)
        val all = listing.getOrElse {
            return "package listing FAILED: ${it.javaClass.simpleName}: ${it.message}"
        }
        val packages = all.filter { PACKAGE_HINT.containsMatchIn(it) }
        if (packages.isEmpty()) return "none installed (scanned ${all.size} packages)"

        return packages.joinToString("\n\n") { name ->
            val block = StringBuilder(name)
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString()
            }.getOrNull()
            block.append("  label=").append(label ?: "?")
            block.append("  system=").append(yesNo(isSystemApp(pm, name)))
            block.append("  launchable=").append(yesNo(pm.getLaunchIntentForPackage(name) != null))

            val flags = PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS
            val info = runCatching { pm.getPackageInfo(name, flags) }.getOrNull()
            if (info == null) {
                block.append("\n  (component list unavailable)")
            } else {
                block.append(exported("activities", info.activities?.map { it.name to it.exported }))
                block.append(exported("services", info.services?.map { it.name to it.exported }))
                block.append(exported("receivers", info.receivers?.map { it.name to it.exported }))
            }
            block.toString()
        }
    }

    private fun exported(kind: String, components: List<Pair<String, Boolean>>?): String {
        val shown = components.orEmpty().filter { it.second }
        if (shown.isEmpty()) return "\n  exported $kind: none"
        val head = shown.take(MAX_COMPONENTS).joinToString("\n") { "    ${it.first}" }
        val more = if (shown.size > MAX_COMPONENTS) "\n    …+${shown.size - MAX_COMPONENTS} more" else ""
        return "\n  exported $kind (${shown.size}):\n$head$more"
    }

    /**
     * Runs a read-only shell command. No root: `service` and `getprop` are ordinary binaries any
     * app may execute, and a refusal here is itself a useful answer, so failures are reported
     * rather than swallowed.
     */
    private fun shell(command: List<String>, filter: Boolean): String = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroy()
        val lines = text.lineSequence()
            .filter { it.isNotBlank() }
            .filter { !filter || LINE_HINT.containsMatchIn(it) }
            .toList()
        when {
            lines.isEmpty() -> "no matching lines"
            lines.size > MAX_LINES -> lines.take(MAX_LINES).joinToString("\n") +
                "\n…+${lines.size - MAX_LINES} more"
            else -> lines.joinToString("\n")
        }
    }.getOrElse { "failed: ${it.javaClass.simpleName}: ${it.message}" }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_FM -> "FM"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM_TUNER"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "MIC"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        AudioDeviceInfo.TYPE_BUS -> "BUS"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_AUX_LINE -> "AUX"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        else -> ""
    }

    private fun yesNo(value: Boolean) = if (value) "YES" else "no"

    private fun StringBuilder.section(title: String) {
        if (isNotEmpty()) append("\n")
        append("--- ").append(title).append(" ---\n")
    }

    private fun StringBuilder.line(text: String) {
        append(text).append("\n")
    }
}
