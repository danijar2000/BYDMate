package com.bydmate.app.ui.touchpad

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.bydmate.app.cluster.ClusterProjectionManager

/**
 * Live value of the cluster-projection master switch.
 *
 * Projection settings live in their own SharedPreferences rather than the settings repository, so
 * there is no Flow to collect — this listens to the prefs directly and unregisters with the
 * composition. Used to keep the Touchpad tab out of the bar until projection is switched on.
 */
@Composable
fun rememberMirrorEnabled(): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var enabled by remember {
        mutableStateOf(prefs.getBoolean(ClusterProjectionManager.KEY_MIRROR_ENABLED, false))
    }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { store, key ->
            if (key == ClusterProjectionManager.KEY_MIRROR_ENABLED) {
                enabled = store.getBoolean(ClusterProjectionManager.KEY_MIRROR_ENABLED, false)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}
