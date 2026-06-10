/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Initialization helper for WebViewPoTokenGenerator.
 * Call from your Application.onCreate() or Hilt module.
 *
 * Example usage in App.kt:
 *   import moe.rukamori.archivetune.utils.potoken.WebViewPoTokenGenerator
 *   // In onCreate():
 *   WebViewPoTokenGenerator.initialize(this)
 */

package moe.rukamori.archivetune.utils.potoken

import android.content.Context
import timber.log.Timber

/**
 * Initializes the WebViewPoTokenGenerator with the application context.
 * This must be called before any player requests that need PoTokens.
 *
 * Add this to your Application.onCreate():
 * ```
 * WebViewPoTokenGenerator.initialize(this)
 * ```
 */
object PoTokenInitializer {
    private const val TAG = "PoTokenInitializer"

    fun init(context: Context) {
        Timber.tag(TAG).d("Initializing WebViewPoTokenGenerator")
        WebViewPoTokenGenerator.initialize(context)
        Timber.tag(TAG).d("WebViewPoTokenGenerator initialized successfully")
    }
}
