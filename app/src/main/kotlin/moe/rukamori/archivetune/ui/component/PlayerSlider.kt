/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.ceil
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

// Adapted from saket/squiggly-slider (Copyright 2019 Saket Narayan, Apache-2.0)
// for Material 3 SliderState.trackRange.
private const val SQUIGGLY_SEGMENTS_PER_WAVELENGTH = 10
private const val SQUIGGLY_WAVE_DURATION_SECONDS = 4f

@Composable
fun PlayerWavySliderThumb(
    colors: SliderColors,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    strokeWidth: Dp = 6.dp,
    thumbSize: DpSize =
        DpSize(
            width = strokeWidth.coerceAtLeast(4.dp),
            height = (strokeWidth * 4).coerceAtLeast(16.dp),
        ),
    shape: Shape = RoundedCornerShape(4.dp),
) {
    val thumbColor = if (enabled) colors.thumbColor else colors.disabledThumbColor
    Box(
        modifier = modifier.sizeIn(minWidth = 20.dp, minHeight = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            Modifier
                .size(thumbSize)
                .background(thumbColor, shape),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerWavySliderTrack(
    sliderState: SliderState,
    interactionSource: MutableInteractionSource,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    colors: SliderColors = SliderDefaults.colors(),
    enabled: Boolean = true,
    strokeWidth: Dp = 6.dp,
    amplitude: Dp = 2.dp,
    wavelength: Dp = (strokeWidth * 6).coerceAtLeast(16.dp),
) {
    val sliderHeight = (amplitude + strokeWidth) * 2
    val inactiveTrackColor = if (enabled) colors.inactiveTrackColor else colors.disabledInactiveTrackColor
    val activeTrackColor = if (enabled) colors.activeTrackColor else colors.disabledActiveTrackColor
    val valueRange = sliderState.trackRange

    val isDragged by interactionSource.collectIsDraggedAsState()
    val animatedAmplitude by animateDpAsState(
        targetValue = if (isPlaying && !isDragged) amplitude else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "Squiggles amplitude",
    )

    var animationProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying, isDragged) {
        if (!isPlaying || isDragged) return@LaunchedEffect
        var lastFrameNanos = 0L
        while (isActive) {
            val frameNanos = withFrameNanos { it }
            if (lastFrameNanos != 0L) {
                val deltaSeconds = ((frameNanos - lastFrameNanos).coerceAtLeast(0L)) / 1_000_000_000f
                animationProgress = (animationProgress + deltaSeconds / SQUIGGLY_WAVE_DURATION_SECONDS) % 1f
            }
            lastFrameNanos = frameNanos
        }
    }

    Spacer(
        modifier
            .fillMaxWidth()
            .height(sliderHeight)
            .drawWithCache {
                val path = Path()
                val strokeWidthPx = strokeWidth.toPx()
                val wavelengthPx = wavelength.toPx().coerceAtLeast(1f)
                val pathStyle =
                    Stroke(
                        width = strokeWidthPx,
                        join = StrokeJoin.Round,
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.cornerPathEffect(radius = wavelengthPx),
                    )
                onDrawBehind {
                    val isRtl = layoutDirection == LayoutDirection.Rtl
                    val sliderLeft = Offset(0f, center.y)
                    val sliderRight = Offset(size.width, center.y)
                    val sliderStart = if (isRtl) sliderRight else sliderLeft
                    val sliderEnd = if (isRtl) sliderLeft else sliderRight
                    val activeFraction =
                        calcFraction(
                            valueRange.start,
                            valueRange.endInclusive,
                            sliderState.value.coerceIn(valueRange.start, valueRange.endInclusive),
                        )
                    val sliderValueEnd =
                        Offset(
                            x = sliderStart.x + (sliderEnd.x - sliderStart.x) * activeFraction,
                            y = center.y,
                        )
                    val direction = if (isRtl) -1f else 1f
                    val halfStroke = strokeWidthPx / 2f
                    val inactiveStartX =
                        if (isRtl) {
                            sliderValueEnd.x.coerceAtMost(sliderStart.x - halfStroke)
                        } else {
                            sliderValueEnd.x.coerceAtLeast(sliderStart.x + halfStroke)
                        }
                    val inactiveEndX = sliderEnd.x - direction * halfStroke
                    if ((inactiveEndX - inactiveStartX) * direction > 0f) {
                        drawLine(
                            color = inactiveTrackColor,
                            start = Offset(inactiveStartX, center.y),
                            end = Offset(inactiveEndX, center.y),
                            strokeWidth = strokeWidthPx,
                            cap = StrokeCap.Round,
                        )
                    }

                    if (activeFraction <= 0f) return@onDrawBehind

                    val activeLength = (size.width * activeFraction - strokeWidthPx).coerceAtLeast(0f)
                    val waveStartX = sliderStart.x + direction * halfStroke
                    val segmentWidth = wavelengthPx / SQUIGGLY_SEGMENTS_PER_WAVELENGTH
                    val numOfPoints = ceil(activeLength / segmentWidth).toInt().coerceAtLeast(1)
                    val twoPi = (2.0 * PI).toFloat()
                    val amplitudePx = animatedAmplitude.toPx()
                    val transitionWidth = wavelengthPx * 0.75f

                    path.rewind()
                    var distance = 0f
                    for (point in 0..numOfPoints) {
                        val proportionOfWavelength = distance / wavelengthPx
                        val radiansX = proportionOfWavelength * twoPi + (twoPi * animationProgress)
                        val t = ((activeLength - distance) / transitionWidth).coerceIn(0f, 1f)
                        val taper = t * t * (3f - 2f * t)
                        val offsetX = waveStartX + direction * distance
                        val offsetY = center.y + (sin(radiansX) * amplitudePx * taper)
                        if (point == 0) {
                            path.moveTo(offsetX, offsetY)
                        } else {
                            path.lineTo(offsetX, offsetY)
                        }
                        distance = (distance + segmentWidth).coerceAtMost(activeLength)
                    }

                    if (isRtl) {
                        clipRect(left = sliderValueEnd.x) {
                            drawPath(
                                path = path,
                                color = activeTrackColor,
                                style = pathStyle,
                            )
                        }
                    } else {
                        clipRect(right = sliderValueEnd.x) {
                            drawPath(
                                path = path,
                                color = activeTrackColor,
                                style = pathStyle,
                            )
                        }
                    }
                }
            },
    )
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
