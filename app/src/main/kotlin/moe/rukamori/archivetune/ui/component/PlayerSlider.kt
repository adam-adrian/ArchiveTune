/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberControlledSliderState(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
): SliderState {
    val sliderState =
        remember(valueRange, steps) {
            SliderState(
                value = value.coerceIn(valueRange),
                steps = steps,
                trackRange = valueRange,
            )
        }
    LaunchedEffect(sliderState, value) {
        sliderState.value = value.coerceIn(valueRange)
    }
    return sliderState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSliderTrack(
    sliderState: SliderState,
    modifier: Modifier = Modifier,
    colors: SliderColors = SliderDefaults.colors(),
    trackHeight: Dp = 10.dp,
) {
    val inactiveTrackColor = colors.inactiveTrackColor
    val activeTrackColor = colors.activeTrackColor
    val inactiveTickColor = colors.inactiveTickColor
    val activeTickColor = colors.activeTickColor
    val valueRange = sliderState.trackRange
    Canvas(
        modifier
            .fillMaxWidth()
            .height(trackHeight),
    ) {
        drawTrack(
            stepsToTickFractions(sliderState.steps),
            0f,
            calcFraction(
                valueRange.start,
                valueRange.endInclusive,
                sliderState.value.coerceIn(valueRange.start, valueRange.endInclusive),
            ),
            inactiveTrackColor,
            activeTrackColor,
            inactiveTickColor,
            activeTickColor,
            trackHeight,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerWavySliderTrack(
    sliderState: SliderState,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    colors: SliderColors = SliderDefaults.colors(),
    strokeWidth: Dp = 6.dp,
    amplitude: Dp = 2.dp,
    wavelength: Dp = 32.dp,
) {
    val inactiveTrackColor = colors.inactiveTrackColor
    val activeTrackColor = colors.activeTrackColor
    val valueRange = sliderState.trackRange

    val animatedAmplitude by animateDpAsState(
        targetValue = if (isPlaying) amplitude else 0.dp,
        animationSpec = tween(durationMillis = 300),
        label = "wavy-slider-amplitude",
    )

    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var lastFrameNanos = 0L
        val radiansPerSecond = (2.0 * PI / 1.5).toFloat()
        while (isActive) {
            val frameNanos = withFrameNanos { it }
            if (lastFrameNanos != 0L) {
                val deltaSeconds = ((frameNanos - lastFrameNanos).coerceAtLeast(0L)) / 1_000_000_000f
                phase = (phase + deltaSeconds * radiansPerSecond) % (2.0 * PI).toFloat()
            }
            lastFrameNanos = frameNanos
        }
    }

    val wavePath = remember { Path() }
    val canvasHeight = strokeWidth + amplitude * 2

    Canvas(
        modifier
            .fillMaxWidth()
            .height(canvasHeight),
    ) {
        val activeFraction =
            calcFraction(
                valueRange.start,
                valueRange.endInclusive,
                sliderState.value.coerceIn(valueRange.start, valueRange.endInclusive),
            )
        val isRtl = layoutDirection == LayoutDirection.Rtl
        val startX = if (isRtl) size.width else 0f
        val endX = if (isRtl) 0f else size.width
        val direction = if (isRtl) -1f else 1f
        val activeLength = size.width * activeFraction
        val activeEndX = startX + direction * activeLength
        val strokeWidthPx = strokeWidth.toPx()
        val amplitudePx = animatedAmplitude.toPx()
        val wavelengthPx = wavelength.toPx().coerceAtLeast(1f)

        if (activeFraction < 1f) {
            drawLine(
                color = inactiveTrackColor,
                start = Offset(activeEndX, center.y),
                end = Offset(endX, center.y),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round,
            )
        }

        if (activeLength <= 0f) return@Canvas

        if (amplitudePx <= 0.1f) {
            drawLine(
                color = activeTrackColor,
                start = Offset(startX, center.y),
                end = Offset(activeEndX, center.y),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round,
            )
        } else {
            val twoPi = (2.0 * PI).toFloat()
            val taperLength = (wavelengthPx * 0.5f).coerceAtMost(activeLength)
            val stepPx = (wavelengthPx / 20f).coerceAtLeast(2f)

            wavePath.rewind()
            var distance = 0f
            while (true) {
                val remaining = activeLength - distance
                val taper =
                    if (taperLength > 0f && remaining < taperLength) {
                        (remaining / taperLength).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                val x = startX + direction * distance
                val y = center.y + sin(twoPi * distance / wavelengthPx - phase) * amplitudePx * taper
                if (distance == 0f) {
                    wavePath.moveTo(x, y)
                } else {
                    wavePath.lineTo(x, y)
                }
                if (distance >= activeLength) break
                distance = (distance + stepPx).coerceAtMost(activeLength)
            }

            drawPath(
                path = wavePath,
                color = activeTrackColor,
                style =
                    Stroke(
                        width = strokeWidthPx,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
            )
        }
    }
}

private fun DrawScope.drawTrack(
    tickFractions: FloatArray,
    activeRangeStart: Float,
    activeRangeEnd: Float,
    inactiveTrackColor: Color,
    activeTrackColor: Color,
    inactiveTickColor: Color,
    activeTickColor: Color,
    trackHeight: Dp = 2.dp,
) {
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val sliderLeft = Offset(0f, center.y)
    val sliderRight = Offset(size.width, center.y)
    val sliderStart = if (isRtl) sliderRight else sliderLeft
    val sliderEnd = if (isRtl) sliderLeft else sliderRight
    val tickSize = 2.0.dp.toPx()
    val trackStrokeWidth = trackHeight.toPx()
    drawLine(
        inactiveTrackColor,
        sliderStart,
        sliderEnd,
        trackStrokeWidth,
        StrokeCap.Round,
    )
    val sliderValueEnd =
        Offset(
            sliderStart.x +
                (sliderEnd.x - sliderStart.x) * activeRangeEnd,
            center.y,
        )
    val sliderValueStart =
        Offset(
            sliderStart.x +
                (sliderEnd.x - sliderStart.x) * activeRangeStart,
            center.y,
        )
    drawLine(
        activeTrackColor,
        sliderValueStart,
        sliderValueEnd,
        trackStrokeWidth,
        StrokeCap.Round,
    )
    for (tick in tickFractions) {
        val outsideFraction = tick > activeRangeEnd || tick < activeRangeStart
        drawCircle(
            color = if (outsideFraction) inactiveTickColor else activeTickColor,
            center = Offset(lerp(sliderStart, sliderEnd, tick).x, center.y),
            radius = tickSize / 2f,
        )
    }
}

private fun stepsToTickFractions(steps: Int): FloatArray =
    if (steps == 0) {
        floatArrayOf()
    } else {
        FloatArray(steps + 2) {
            it.toFloat() / (steps + 1)
        }
    }

private fun calcFraction(
    a: Float,
    b: Float,
    pos: Float,
) = (if (b - a == 0f) 0f else (pos - a) / (b - a)).coerceIn(0f, 1f)
