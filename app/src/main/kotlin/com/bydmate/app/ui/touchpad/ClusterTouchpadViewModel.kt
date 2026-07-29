package com.bydmate.app.ui.touchpad

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.cluster.ClusterProjectionManager
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Turns gestures made on the centre screen into touch events on the instrument panel.
 *
 * Positions arrive normalized (0..1 inside the pad) and are mapped to the projected window at
 * dispatch time, not at capture time: [ClusterProjectionManager.injectionTarget] changes whenever
 * the projection is rebuilt or resized, and resolving it late is what keeps a gesture landing in
 * the right place instead of on a display that no longer exists.
 *
 * Single pointer only. The wire protocol carries one coordinate pair per event, so pinch-zoom
 * cannot be expressed — pan and tap can, which is what a map needs on a panel this shape.
 */
@HiltViewModel
class ClusterTouchpadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val helper: HelperClient,
    private val bootstrap: HelperBootstrap,
) : ViewModel() {

    private data class Touch(val action: Int, val normX: Float, val normY: Float, val downTime: Long)

    /** Unbounded so a burst of MOVEs never blocks the UI thread; the pump collapses the backlog. */
    private val events = Channel<Touch>(Channel.UNLIMITED)

    private var gestureDownTime = 0L

    init {
        viewModelScope.launch { pump() }
    }

    /** True while something is actually projected — the pad is useless otherwise. */
    fun target(): ClusterProjectionManager.InjectionTarget? =
        ClusterProjectionManager.injectionTarget(context)

    fun onDown(normX: Float, normY: Float) {
        gestureDownTime = SystemClock.uptimeMillis()
        offer(MotionEvent.ACTION_DOWN, normX, normY)
    }

    fun onMove(normX: Float, normY: Float) = offer(MotionEvent.ACTION_MOVE, normX, normY)

    fun onUp(normX: Float, normY: Float) = offer(MotionEvent.ACTION_UP, normX, normY)

    fun onCancel() {
        offer(MotionEvent.ACTION_CANCEL, 0f, 0f)
    }

    private fun offer(action: Int, normX: Float, normY: Float) {
        val down = gestureDownTime.takeIf { it != 0L } ?: SystemClock.uptimeMillis().also {
            // A MOVE/UP with no preceding DOWN (pad entered mid-gesture) would otherwise carry
            // downTime 0 and be dropped by the framework as a malformed stream.
            gestureDownTime = it
        }
        events.trySend(Touch(action, normX, normY, down))
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) gestureDownTime = 0L
    }

    /**
     * Drains the queue, dropping only MOVEs that a newer MOVE has already superseded.
     *
     * Each event is one binder round trip. Sending every sampled position would put the pointer
     * seconds behind the finger under load, while dropping a DOWN or an UP would strand the
     * gesture — so staleness is collapsed and ordering is preserved.
     */
    private suspend fun pump() {
        for (first in events) {
            var pending = first
            while (true) {
                val next = events.tryReceive().getOrNull() ?: break
                if (pending.action == MotionEvent.ACTION_MOVE && next.action == MotionEvent.ACTION_MOVE) {
                    pending = next
                } else {
                    dispatch(pending)
                    pending = next
                }
            }
            dispatch(pending)
        }
    }

    private suspend fun dispatch(touch: Touch) {
        val target = ClusterProjectionManager.injectionTarget(context) ?: return
        if (touch.action == MotionEvent.ACTION_DOWN && !bootstrap.ensureRunning()) return

        val x = target.left + touch.normX * target.width
        val y = target.top + touch.normY * target.height
        helper.injectMotion(target.displayId, touch.action, x, y, touch.downTime)

        val released = touch.action == MotionEvent.ACTION_UP || touch.action == MotionEvent.ACTION_CANCEL
        ClusterProjectionManager.cursorAt(
            context,
            if (released) null else touch.normX,
            touch.normY
        )
    }

    override fun onCleared() {
        ClusterProjectionManager.cursorAt(context, null)
        events.close()
        super.onCleared()
    }
}
