package com.bydmate.app.ui.touchpad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydmate.app.R
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.NavyDeep
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary

/**
 * "Touchpad" tab: drag here, the projected app on the instrument panel follows.
 *
 * The pad keeps the panel's aspect ratio and maps positions absolutely rather than as a laptop
 * trackpad would — a point on the pad is the same point on the panel. With a relative mapping the
 * driver would have to hunt for the pointer first, which is exactly the kind of looking-away this
 * is meant to avoid.
 */
@Composable
fun ClusterTouchpadScreen(
    viewModel: ClusterTouchpadViewModel = hiltViewModel()
) {
    // Re-read on every recomposition: the projection can start or stop while this tab is open.
    val target = viewModel.target()
    var localTouch by remember { mutableStateOf<Offset?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.touchpad_title),
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(
                if (target != null) R.string.touchpad_hint else R.string.touchpad_inactive
            ),
            color = if (target != null) TextSecondary else TextMuted,
            fontSize = 13.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        val ratio = if (target != null && target.height > 0) {
            target.width.toFloat() / target.height.toFloat()
        } else {
            PANEL_RATIO
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .background(CardSurface, RoundedCornerShape(12.dp))
                .border(
                    width = if (target != null) 2.dp else 1.dp,
                    color = if (target != null) AccentGreen else CardBorder,
                    shape = RoundedCornerShape(12.dp)
                )
                .pointerInput(target?.displayId) {
                    if (target == null) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitPointerEvent().changes.firstOrNull { it.pressed }
                                ?: continue
                            localTouch = down.position
                            viewModel.onDown(
                                (down.position.x / size.width).coerceIn(0f, 1f),
                                (down.position.y / size.height).coerceIn(0f, 1f)
                            )
                            down.consume()

                            // Follow this pointer until it lifts. Every sampled position is sent;
                            // the view model collapses whatever the binder cannot keep up with.
                            var last = down
                            while (last.pressed) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == last.id } ?: break
                                localTouch = change.position
                                val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                                val ny = (change.position.y / size.height).coerceIn(0f, 1f)
                                if (change.pressed) viewModel.onMove(nx, ny) else viewModel.onUp(nx, ny)
                                change.consume()
                                last = change
                            }
                            localTouch = null
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (target == null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Dashboard,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.touchpad_start_projection),
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                }
            } else {
                // Local echo of the finger, so the pad itself shows what the panel is being told.
                localTouch?.let { point ->
                    Box(
                        modifier = Modifier
                            .size(1.dp)
                            .background(Color.Transparent)
                    )
                    Canvas(point)
                }
                if (localTouch == null) {
                    Text(
                        text = stringResource(R.string.touchpad_ready),
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

/** Draws the local finger echo at [point] (pad pixel space). */
@Composable
private fun Canvas(point: Offset) {
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        drawCircle(
            color = AccentGreen.copy(alpha = 0.35f),
            radius = 26f,
            center = point
        )
        drawCircle(
            color = AccentGreen,
            radius = 26f,
            center = point,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
        )
    }
}

/** Cluster panel is 1280x480; used until a live projection reports its real window. */
private const val PANEL_RATIO = 1280f / 480f
