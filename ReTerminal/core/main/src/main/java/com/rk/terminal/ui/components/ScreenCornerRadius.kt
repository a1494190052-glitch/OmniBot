package com.rk.terminal.ui.components

import android.content.Context
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tracks whether any NavHost destination is currently animating between states
 * (programmatic transition or predictive-back seek), so the host can clip to
 * the screen's physical corner radius only while a transition is visible —
 * mirroring the main app's PredictiveBackPageTransition corner clip.
 */
class NavTransitionTracker {
    private var active by mutableIntStateOf(0)

    val running: Boolean
        get() = active > 0

    fun acquire() {
        active += 1
    }

    fun release() {
        if (active > 0) active -= 1
    }
}

@Composable
fun AnimatedContentScope.TrackNavTransition(tracker: NavTransitionTracker) {
    val transitioning = transition.currentState != transition.targetState
    DisposableEffect(transitioning) {
        if (transitioning) {
            tracker.acquire()
            onDispose { tracker.release() }
        } else {
            onDispose { }
        }
    }
}

/**
 * The device's physical screen corner radius, resolved the same way as the
 * main app's DisplayGeometryService: WindowInsets.getRoundedCorner on API 31+,
 * falling back to the framework `rounded_corner_radius_*` dimens, else 0.
 *
 * Note: `View.getRootWindowInsets()` returns null before the first layout
 * pass, which typically happens *after* initial composition, so the value is
 * resolved again once the view has been laid out.
 */
@Composable
fun rememberScreenCornerRadius(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    var radiusPx by remember { mutableFloatStateOf(-1f) }
    DisposableEffect(view) {
        fun update() {
            val px = resolveScreenCornerRadiusPx(view.context, view)
            if (px > 0f) {
                radiusPx = px
            }
        }
        update()
        // rootWindowInsets is only populated after the first layout — retry
        // after it, otherwise the radius would stay 0 forever.
        view.post { update() }
        onDispose { }
    }
    return if (radiusPx > 0f) with(density) { radiusPx.toDp() } else 0.dp
}

private fun resolveScreenCornerRadiusPx(context: Context, view: View): Float {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val insets = view.rootWindowInsets
        if (insets != null) {
            val radii =
                listOf(
                    insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT),
                    insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT),
                    insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT),
                    insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT),
                ).mapNotNull { it?.radius }
            if (radii.isNotEmpty()) {
                return radii.max().toFloat()
            }
        }
    }
    for (name in listOf("rounded_corner_radius_top", "rounded_corner_radius_bottom")) {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        if (id != 0) {
            return context.resources.getDimension(id)
        }
    }
    return 0f
}
