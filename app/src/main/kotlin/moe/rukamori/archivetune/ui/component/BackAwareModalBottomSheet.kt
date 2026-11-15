/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackAwareModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.modalWindowInsets },
    content: @Composable ColumnScope.() -> Unit,
) {
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        sheetMaxWidth = sheetMaxWidth,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        dragHandle = dragHandle,
        contentWindowInsets = contentWindowInsets,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        val scope = rememberCoroutineScope()
        val hostOwner = checkNotNull(LocalNavigationEventDispatcherOwner.current) {
            "No NavigationEventDispatcherOwner available for the modal bottom sheet window"
        }
        val windowOwner = rememberWindowNavigationEventDispatcherOwner(hostOwner)
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides (windowOwner ?: hostOwner)) {
            BackHandler(enabled = windowOwner != null && sheetState.isVisible) {
                scope.launch { sheetState.hide() }.invokeOnCompletion {
                    if (!sheetState.isVisible) currentOnDismissRequest()
                }
            }
            content()
        }
    }
}

/**
 * Resolves the back dispatcher of the window this sheet actually lives in.
 *
 * `ModalBottomSheet` composes its content before the dialog is shown, so the window view tree does
 * not carry `NavigationEventDispatcherOwner` yet and `findViewTreeNavigationEventDispatcherOwner`
 * returns null. Falling back to the composition's owner registers the sheet's back handler on the
 * activity, where presses from the sheet window never arrive, and nothing recomposes afterwards to
 * correct it. Wait for the attach that follows `Dialog.show()` instead.
 */
@Composable
private fun rememberWindowNavigationEventDispatcherOwner(
    fallback: NavigationEventDispatcherOwner,
): NavigationEventDispatcherOwner? {
    val view = LocalView.current
    var owner by remember(view) { mutableStateOf<NavigationEventDispatcherOwner?>(null) }

    DisposableEffect(view) {
        fun resolve(candidate: View) {
            owner = candidate.findViewTreeNavigationEventDispatcherOwner() ?: fallback
        }

        val listener =
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(attached: View) = resolve(attached)

                override fun onViewDetachedFromWindow(detached: View) = Unit
            }

        view.addOnAttachStateChangeListener(listener)
        if (view.isAttachedToWindow) resolve(view)

        onDispose { view.removeOnAttachStateChangeListener(listener) }
    }

    return owner
}
