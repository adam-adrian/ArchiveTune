/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt

private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
private const val ACTION_STREAM_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"
private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
private const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
private const val VOLUME_SETTLE_ANIMATION_MS = 160

@Stable
internal class DeviceMusicVolumeController(
    private val audioManager: AudioManager,
) {
    private var minVolume: Int = readMinVolume()
    private var maxVolume: Int = readMaxVolume(minVolume)
    private var lastAppliedVolume: Int = readCurrentVolume(minVolume, maxVolume)

    var isDragging by mutableStateOf(false)
        private set

    var volumeFraction by mutableFloatStateOf(
        readEffectiveVolumeFraction(minVolume, maxVolume, lastAppliedVolume, checkMute = true),
    )
        private set

    fun refresh(streamVolumeOverride: Int? = null) {
        val newMin = readMinVolume()
        val newMax = readMaxVolume(newMin)
        minVolume = newMin
        maxVolume = newMax
        if (isDragging) return

        val currentVolume =
            streamVolumeOverride?.coerceIn(newMin, newMax)
                ?: readCurrentVolume(newMin, newMax)
        lastAppliedVolume = currentVolume
        volumeFraction =
            readEffectiveVolumeFraction(
                min = newMin,
                max = newMax,
                current = currentVolume,
                checkMute = streamVolumeOverride == null,
            )
    }

    fun onVolumeChange(fraction: Float) {
        val safeFraction = fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: volumeFraction
        isDragging = true
        volumeFraction = safeFraction

        val volumeRange = (maxVolume - minVolume).coerceAtLeast(1)
        val targetVolume =
            (minVolume + (safeFraction * volumeRange).roundToInt())
                .coerceIn(minVolume, maxVolume)
        if (targetVolume != lastAppliedVolume) {
            lastAppliedVolume = targetVolume
            runCatching {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
            }
        }
    }

    fun onVolumeChangeFinished() {
        if (!isDragging) return
        isDragging = false
        refresh()
    }

    @JvmName("setDeviceMusicVolumeFraction")
    fun setVolumeFraction(fraction: Float) {
        val safeFraction = fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: volumeFraction
        val volumeRange = (maxVolume - minVolume).coerceAtLeast(1)
        val targetVolume =
            (minVolume + (safeFraction * volumeRange).roundToInt())
                .coerceIn(minVolume, maxVolume)
        if (targetVolume != lastAppliedVolume) {
            lastAppliedVolume = targetVolume
            runCatching {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
            }
        }
        refresh()
    }

    private fun readEffectiveVolumeFraction(
        min: Int,
        max: Int,
        current: Int,
        checkMute: Boolean,
    ): Float {
        if (checkMute && runCatching { audioManager.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrDefault(false)) {
            return 0f
        }
        val volumeRange = (max - min).coerceAtLeast(1)
        return ((current - min).toFloat() / volumeRange.toFloat()).coerceIn(0f, 1f)
    }

    private fun readCurrentVolume(
        min: Int,
        max: Int,
    ): Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(min, max)

    private fun readMaxVolume(streamMinVolume: Int): Int =
        audioManager
            .getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            .coerceAtLeast(streamMinVolume + 1)

    private fun readMinVolume(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        } else {
            0
        }
}

@Composable
internal fun rememberDeviceMusicVolumeController(): DeviceMusicVolumeController {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val appContext = remember(context) { context.applicationContext }
    val audioManager =
        remember(appContext) {
            appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
    val controller =
        remember(audioManager) {
            DeviceMusicVolumeController(audioManager)
        }

    DisposableEffect(appContext, controller, lifecycleOwner) {
        val volumeReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    val action = intent?.action ?: return
                    val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
                    if (streamType != -1 && streamType != AudioManager.STREAM_MUSIC) return

                    when (action) {
                        ACTION_VOLUME_CHANGED -> {
                            val volumeValue = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)
                            controller.refresh(streamVolumeOverride = volumeValue.takeIf { it >= 0 })
                        }

                        ACTION_STREAM_MUTE_CHANGED -> {
                            controller.refresh()
                        }
                    }
                }
            }
        val receiverFilter =
            IntentFilter().apply {
                addAction(ACTION_VOLUME_CHANGED)
                addAction(ACTION_STREAM_MUTE_CHANGED)
            }
        val receiverRegistered =
            runCatching {
                ContextCompat.registerReceiver(
                    appContext,
                    volumeReceiver,
                    receiverFilter,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                true
            }.getOrDefault(false)

        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    controller.refresh()
                }
            }
        val contentResolver = appContext.contentResolver
        val observerRegistered =
            runCatching {
                contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
                true
            }.getOrDefault(false)

        val lifecycleObserver =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> controller.refresh()
                    Lifecycle.Event.ON_STOP -> controller.onVolumeChangeFinished()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        controller.refresh()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            if (receiverRegistered) {
                runCatching { appContext.unregisterReceiver(volumeReceiver) }
            }
            if (observerRegistered) {
                runCatching { contentResolver.unregisterContentObserver(observer) }
            }
        }
    }

    return controller
}

@Composable
internal fun rememberSmoothVolumeFraction(
    volume: Float,
    isDragging: Boolean,
): Float {
    val animatedVolume by animateFloatAsState(
        targetValue = volume.coerceIn(0f, 1f),
        animationSpec =
            if (isDragging) {
                snap()
            } else {
                tween(
                    durationMillis = VOLUME_SETTLE_ANIMATION_MS,
                    easing = FastOutSlowInEasing,
                )
            },
        label = "deviceMusicVolume",
    )
    return animatedVolume
}
