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
import android.net.Uri
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
import androidx.compose.runtime.rememberUpdatedState
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
private const val VOLUME_SETTLE_ANIMATION_MS = 160
private const val UNAPPLIED_VOLUME_STEP = -1

@Stable
internal class DeviceMusicVolumeController(
    private val audioManager: AudioManager,
) {
    private var minVolume: Int = readMinVolume()
    private var maxVolume: Int = readMaxVolume(minVolume)
    private var lastAppliedVolume: Int = UNAPPLIED_VOLUME_STEP

    var isDragging by mutableStateOf(false)
        private set

    var volumeFraction by mutableFloatStateOf(0f)
        private set

    init {
        refresh()
    }

    fun refresh() {
        refreshBounds()
        if (isDragging) return

        val isMuted = runCatching { audioManager.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrDefault(false)
        if (isMuted) {
            lastAppliedVolume = UNAPPLIED_VOLUME_STEP
            volumeFraction = 0f
            return
        }

        val currentVolume = readCurrentVolume(minVolume, maxVolume)
        lastAppliedVolume = currentVolume
        val volumeRange = (maxVolume - minVolume).coerceAtLeast(1)
        volumeFraction = ((currentVolume - minVolume).toFloat() / volumeRange.toFloat()).coerceIn(0f, 1f)
    }

    fun onVolumeChange(fraction: Float) {
        val safeFraction = fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: volumeFraction
        val wasDragging = isDragging
        if (!wasDragging) {
            refreshBounds()
            isDragging = true
        }
        volumeFraction = safeFraction
        applyFractionStep(safeFraction)
    }

    fun onVolumeChangeFinished() {
        if (!isDragging) return
        isDragging = false
        refresh()
    }

    @JvmName("setDeviceMusicVolumeFraction")
    fun setVolumeFraction(fraction: Float) {
        val safeFraction = fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: volumeFraction
        refreshBounds()
        isDragging = false
        applyFractionStep(safeFraction)
        refresh()
    }

    private fun applyFractionStep(safeFraction: Float) {
        val volumeRange = (maxVolume - minVolume).coerceAtLeast(1)
        val targetVolume =
            (minVolume + (safeFraction * volumeRange).roundToInt())
                .coerceIn(minVolume, maxVolume)
        if (targetVolume != lastAppliedVolume) {
            runCatching {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
            }.onSuccess {
                lastAppliedVolume = targetVolume
            }
        }
    }

    private fun refreshBounds() {
        val newMin = readMinVolume()
        val newMax = readMaxVolume(newMin)
        minVolume = newMin
        maxVolume = newMax
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
                    if (action != ACTION_VOLUME_CHANGED && action != ACTION_STREAM_MUTE_CHANGED) return
                    val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
                    if (streamType != -1 && streamType != AudioManager.STREAM_MUSIC) return
                    controller.refresh()
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
                    onChange(selfChange, null)
                }

                override fun onChange(
                    selfChange: Boolean,
                    uri: Uri?,
                ) {
                    if (
                        uri != null &&
                        uri != Settings.System.CONTENT_URI &&
                        !uri.lastPathSegment.orEmpty().contains("volume", ignoreCase = true)
                    ) {
                        return
                    }
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
    onDispose: (() -> Unit)? = null,
): Float {
    val safeVolume = volume.coerceIn(0f, 1f)
    val animatedVolume by animateFloatAsState(
        targetValue = safeVolume,
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
    if (onDispose != null) {
        val currentOnDispose by rememberUpdatedState(onDispose)
        DisposableEffect(Unit) {
            onDispose { currentOnDispose() }
        }
    }
    return if (isDragging) safeVolume else animatedVolume
}
