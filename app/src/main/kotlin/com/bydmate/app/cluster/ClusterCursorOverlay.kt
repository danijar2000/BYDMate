package com.bydmate.app.cluster

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * The finger dot drawn on the instrument panel while the touchpad is in use.
 *
 * The panel has no touchscreen, so nothing on it ever shows where the "pointer" is — without this
 * the driver is dragging a map blind. It is a tiny window moved with `updateViewLayout` rather
 * than a full-panel overlay: repositioning a 56 px window is far cheaper than redrawing the whole
 * cluster at finger rate.
 *
 * [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE] is not optional. The dot sits directly above the
 * projected app on the same display, and without it the cursor would eat the very events this
 * feature injects — the pointer would swallow every tap it is supposed to illustrate.
 *
 * Main thread only, like every other WindowManager caller in this package.
 */
object ClusterCursorOverlay {

    private const val TAG = "ClusterCursor"
    private const val SIZE_PX = 56

    private var view: CursorView? = null
    private var manager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null

    /** Places (or moves) the dot to [x], [y] in [display]'s pixel space. */
    fun show(context: Context, display: Display, x: Int, y: Int) {
        val displayContext = context.createDisplayContext(display)
        val wm = displayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val existing = view
        val lp = params
        if (existing != null && lp != null && manager === wm) {
            lp.x = x - SIZE_PX / 2
            lp.y = y - SIZE_PX / 2
            runCatching { wm.updateViewLayout(existing, lp) }
                .onFailure { Log.w(TAG, "move failed: ${it.message}"); hide() }
            return
        }

        hide()
        val fresh = CursorView(displayContext)
        val freshParams = WindowManager.LayoutParams(
            SIZE_PX,
            SIZE_PX,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x - SIZE_PX / 2
            this.y = y - SIZE_PX / 2
        }
        runCatching { wm.addView(fresh, freshParams) }
            .onSuccess {
                view = fresh
                params = freshParams
                manager = wm
            }
            .onFailure { Log.w(TAG, "addView failed: ${it.message}") }
    }

    fun hide() {
        val current = view ?: return
        runCatching { manager?.removeView(current) }
            .onFailure { Log.w(TAG, "removeView failed: ${it.message}") }
        view = null
        params = null
        manager = null
    }

    /** A filled dot with a ring — readable over both a dark map and a light UI panel. */
    private class CursorView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(140, 255, 255, 255)
            style = Paint.Style.FILL
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        override fun onDraw(canvas: Canvas) {
            val c = width / 2f
            canvas.drawCircle(c, c, c - 6f, fill)
            canvas.drawCircle(c, c, c - 6f, ring)
        }
    }
}
