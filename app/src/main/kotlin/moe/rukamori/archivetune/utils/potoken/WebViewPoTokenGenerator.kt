/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * WebView-based PoToken generator using YouTube's BotGuard.
 * Adapted from Metrolist (https://github.com/MetrolistGroup/Metrolist)
 */

package moe.rukamori.archivetune.utils.potoken

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/**
 * Generates valid PoTokens by running YouTube's BotGuard JavaScript in an Android WebView.
 * This produces cryptographically valid tokens that YouTube accepts for playback history sync.
 *
 * Must call [initialize] with an Android Context before use.
 */
object WebViewPoTokenGenerator {
    private const val TAG = "WebViewPoTokenGenerator"
    private const val POTOKEN_TIMEOUT_MS = 8_000L

    private var appContext: android.content.Context? = null

    private val webViewSupported by lazy {
        runCatching { CookieManager.getInstance() }.isSuccess
    }
    private var webViewBadImpl = false

    private val webPoTokenGenLock = Mutex()
    private var webPoTokenSessionId: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenWebView? = null

    /**
     * Initialize with application context. Must be called before [getWebClientPoToken].
     * Call from your Application class or Hilt module.
     */
    fun initialize(context: android.content.Context) {
        appContext = context.applicationContext
        Timber.tag(TAG).d("WebViewPoTokenGenerator initialized")
    }

    /**
     * Generate a PoToken for the given video and session.
     * Returns null if WebView is not available or token generation fails.
     */
    fun getWebClientPoToken(videoId: String, sessionId: String): PoTokenResult? {
        val ctx = appContext
        if (ctx == null) {
            Timber.tag(TAG).w("Not initialized — call initialize() first")
            return null
        }

        Timber.tag(TAG).d("getWebClientPoToken called: videoId=$videoId, sessionId=$sessionId")
        if (!webViewSupported || webViewBadImpl) {
            Timber.tag(TAG).d("WebView not available: supported=$webViewSupported, badImpl=$webViewBadImpl")
            return null
        }

        return try {
            runBlocking {
                withTimeout(POTOKEN_TIMEOUT_MS) {
                    getWebClientPoTokenInternal(ctx, videoId, sessionId, forceRecreate = false)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Timber.tag(TAG).w("poToken generation timed out after ${POTOKEN_TIMEOUT_MS}ms")
            runBlocking {
                webPoTokenGenLock.withLock {
                    try {
                        withContext(Dispatchers.Main) {
                            webPoTokenGenerator?.close()
                        }
                    } catch (closeEx: Exception) {
                        Timber.tag(TAG).e(closeEx, "Exception closing PoTokenWebView during timeout cleanup")
                    }
                    webPoTokenGenerator = null
                    webPoTokenStreamingPot = null
                    webPoTokenSessionId = null
                }
            }
            null
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "poToken generation exception: ${e.javaClass.simpleName}: ${e.message}")
            when (e) {
                is BadWebViewException -> {
                    webViewBadImpl = true
                    null
                }
                else -> throw e
            }
        }
    }

    private suspend fun getWebClientPoTokenInternal(
        context: android.content.Context,
        videoId: String,
        sessionId: String,
        forceRecreate: Boolean,
    ): PoTokenResult {
        val (poTokenGenerator, streamingPot, hasBeenRecreated) =
            webPoTokenGenLock.withLock {
                val shouldRecreate =
                    forceRecreate || webPoTokenGenerator == null || webPoTokenGenerator!!.isExpired || webPoTokenSessionId != sessionId

                if (shouldRecreate) {
                    webPoTokenSessionId = sessionId

                    withContext(Dispatchers.Main) {
                        webPoTokenGenerator?.close()
                    }

                    webPoTokenGenerator = PoTokenWebView.getNewPoTokenGenerator(context)

                    webPoTokenStreamingPot = webPoTokenGenerator!!.generatePoToken(sessionId)
                }

                Triple(webPoTokenGenerator!!, webPoTokenStreamingPot!!, shouldRecreate)
            }

        val playerPot = try {
            poTokenGenerator.generatePoToken(videoId)
        } catch (throwable: Throwable) {
            if (hasBeenRecreated) {
                throw throwable
            } else {
                return getWebClientPoTokenInternal(context, videoId, sessionId, forceRecreate = true)
            }
        }

        // IMPORTANT: PoToken binding (PR #3950 fix)
        // playerRequestPoToken = session-bound token (streamingPot)
        // streamingDataPoToken = video-bound token (playerPot)
        return PoTokenResult(
            playerRequestPoToken = streamingPot,
            streamingDataPoToken = playerPot,
        )
    }
}
