package moe.rukamori.archivetune.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.blurEffect

val LocalGlassEffectEnabled = compositionLocalOf { false }
val LocalGlassEffectHazeState = staticCompositionLocalOf<HazeState?> { null }

@Stable
data class GlassEffectStyle(
    val blurStyle: HazeBlurStyle,
    val highlightBrush: Brush,
    val borderColor: Color,
)

object GlassEffectDefaults {
    @Composable
    fun style(darkTheme: Boolean = isSystemInDarkTheme()): GlassEffectStyle = remember(darkTheme) {
        if (darkTheme) darkStyle() else lightStyle()
    }

    fun lightStyle(): GlassEffectStyle = GlassEffectStyle(
        blurStyle = HazeBlurStyle(
            blurRadius = 20.dp,
            colorEffects = listOf(
                HazeColorEffect.tint(Color.White.copy(alpha = 0.08f)),
            ),
            noiseFactor = 0.08f,
        ),
        highlightBrush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.25f),
                Color.Transparent,
            ),
        ),
        borderColor = Color.White.copy(alpha = 0.15f),
    )

    fun darkStyle(): GlassEffectStyle = GlassEffectStyle(
        blurStyle = HazeBlurStyle(
            blurRadius = 20.dp,
            colorEffects = listOf(
                HazeColorEffect.tint(Color.Black.copy(alpha = 0.18f)),
            ),
            noiseFactor = 0.08f,
        ),
        highlightBrush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.18f),
                Color.Transparent,
            ),
        ),
        borderColor = Color.White.copy(alpha = 0.12f),
    )
}

@Composable
fun rememberGlassEffectHazeState(): HazeState = rememberHazeState()

private fun Modifier.applyGlassEffectSource(
    hazeState: HazeState?,
    enabled: Boolean = true,
): Modifier = if (!enabled || hazeState == null) {
    this
} else {
    hazeSource(state = hazeState)
}

@Composable
fun Modifier.glassEffectSource(
    enabled: Boolean = LocalGlassEffectEnabled.current,
    hazeState: HazeState? = LocalGlassEffectHazeState.current,
): Modifier = applyGlassEffectSource(
    hazeState = hazeState,
    enabled = enabled,
)

private fun Modifier.applyGlassEffect(
    hazeState: HazeState?,
    enabled: Boolean,
    style: GlassEffectStyle,
    shape: Shape,
): Modifier = if (!enabled || hazeState == null) {
    this
} else {
    clip(shape)
        .hazeEffect(state = hazeState) {
            inputScale = HazeInputScale.Auto
            blurEffect {
                this.style = style.blurStyle
            }
        }
        .background(style.highlightBrush, shape)
        .border(1.dp, style.borderColor, shape)
}

@Composable
fun Modifier.glassEffect(
    enabled: Boolean = LocalGlassEffectEnabled.current,
    hazeState: HazeState? = LocalGlassEffectHazeState.current,
    style: GlassEffectStyle = GlassEffectDefaults.style(),
    shape: Shape = RectangleShape,
): Modifier = applyGlassEffect(
    hazeState = hazeState,
    enabled = enabled,
    style = style,
    shape = shape,
)
