/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.utils

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun appBarScrollBehavior(
    state: TopAppBarState = rememberTopAppBarState(),
    canScroll: () -> Boolean = { true },
    snapAnimationSpec: AnimationSpec<Float>? = spring(stiffness = Spring.StiffnessMediumLow),
    flingAnimationSpec: DecayAnimationSpec<Float>? = rememberSplineBasedDecay(),
): TopAppBarScrollBehavior =
    AppBarScrollBehavior(
        state = state,
        snapAnimationSpec = snapAnimationSpec,
        flingAnimationSpec = flingAnimationSpec,
        canScroll = canScroll,
    )

@ExperimentalMaterial3Api
class AppBarScrollBehavior(
    override val state: TopAppBarState,
    override val snapAnimationSpec: AnimationSpec<Float>?,
    override val flingAnimationSpec: DecayAnimationSpec<Float>?,
    val canScroll: () -> Boolean = { true },
) : TopAppBarScrollBehavior {
    // The bar physically translates (quick-return), so it is not pinned.
    override val isPinned: Boolean = false
    override var nestedScrollConnection =
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!canScroll()) return Offset.Zero

                // The limit must be a negative value (set from the rendered header
                // height via onSizeChanged) before the bar is allowed to move.
                // Until then, do nothing to avoid sliding off-screen unbounded
                // (rememberTopAppBarState defaults heightOffsetLimit to -Float.MAX_VALUE).
                val limit = state.heightOffsetLimit
                if (limit >= 0f || limit == -Float.MAX_VALUE) return Offset.Zero

                // consumed.y < 0 -> content scrolled up   -> hide header
                // consumed.y > 0 -> content scrolled down  -> reveal header
                //
                // Couple the header to real content movement (consumed.y), and add
                // only POSITIVE leftover (available.y) so the header still reveals
                // immediately when the list is already at the top edge (child consumed
                // nothing). Negative leftover is discarded: at the bottom edge the list
                // is frozen (consumed.y == 0) yet emits a negative available.y, which
                // would otherwise hide the header while the content sits still and break
                // the sticky illusion.
                val delta = consumed.y + available.y.coerceAtLeast(0f)
                if (delta != 0f) {
                    state.contentOffset += consumed.y
                    // Explicit clamp: prevents fling overshoot beyond [limit, 0].
                    state.heightOffset = (state.heightOffset + delta).coerceIn(limit, 0f)
                    if (state.heightOffset == 0f) {
                        // Eliminate float precision drift when fully revealed.
                        state.contentOffset = 0f
                    }
                }

                // Never consume: content scrolls normally underneath the floating bar.
                return Offset.Zero
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity {
                if (!canScroll()) return Velocity.Zero

                val limit = state.heightOffsetLimit
                if (limit >= 0f || limit == -Float.MAX_VALUE) return Velocity.Zero

                // Already fully settled: nothing to do.
                if (state.heightOffset == 0f || state.heightOffset == limit) {
                    return Velocity.Zero
                }

                // Settle the partially-shown header to either fully revealed (0f) or
                // fully hidden (limit), like YouTube Music. Prefer the fling direction;
                // fall back to whichever edge is closer when the fling is gentle.
                //
                // Discard negative leftover velocity (available.y) for the same reason as
                // onPostScroll: at the bottom edge the frozen list emits a large negative
                // available.y while consumed.y ~= 0. Adding it would snap the header to
                // hidden though no content moved. Coercing to >= 0 makes velocityY fall
                // into the nearest-edge branch instead, while a genuine mid-list upward
                // fling still carries its negative velocity through consumed.y and hides.
                val velocityY = consumed.y + available.y.coerceAtLeast(0f)
                val target =
                    when {
                        velocityY < -SettleVelocityThreshold -> limit // toward end -> hide
                        velocityY > SettleVelocityThreshold -> 0f // toward top -> reveal
                        state.heightOffset < limit / 2f -> limit // more hidden than shown
                        else -> 0f
                    }

                if (target != state.heightOffset) {
                    animate(
                        initialValue = state.heightOffset,
                        targetValue = target,
                        animationSpec = snapAnimationSpec ?: spring(stiffness = Spring.StiffnessMediumLow),
                    ) { value, _ ->
                        state.heightOffset = value.coerceIn(limit, 0f)
                    }
                }
                if (state.heightOffset == 0f) {
                    state.contentOffset = 0f
                }

                // Non-consuming: don't claim leftover velocity from the content.
                return Velocity.Zero
            }
        }
}

private const val SettleVelocityThreshold = 300f

@OptIn(ExperimentalMaterial3Api::class)
suspend fun TopAppBarState.resetHeightOffset() {
    if (heightOffset != 0f) {
        animate(
            initialValue = heightOffset,
            targetValue = 0f,
        ) { value, _ ->
            heightOffset = value
        }
    }
}
