/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
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
    onBackWhileClosing: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val currentOnBackWhileClosing by rememberUpdatedState(onBackWhileClosing)
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
        val windowOwner =
            LocalView.current.findViewTreeNavigationEventDispatcherOwner()
                ?: checkNotNull(LocalNavigationEventDispatcherOwner.current) {
                    "No NavigationEventDispatcherOwner available for the modal bottom sheet window"
                }
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides windowOwner) {
            // A press must never land on a sheet that is already leaving, for two separate reasons.
            // `targetValue` is the intent: it flips to `Hidden` the moment a close starts. And once
            // this handler is disabled the press does not escape - the sheet is its own dialog
            // window, and its dispatcher's fallback is `ComponentDialog`'s `onBackPressed()`, whose
            // `cancel()` this material3 version overrides to a no-op, so the press dies there.
            //
            // The gate is `currentValue` (the settled value: still `Expanded` until the close
            // lands), not `isVisible` - that one already reads false once the sheet crosses
            // halfway, which is exactly the stretch of the animation a second press arrives in.
            BackHandler(
                enabled = sheetState.targetValue != SheetValue.Hidden ||
                    (sheetState.currentValue != SheetValue.Hidden && currentOnBackWhileClosing != null),
            ) {
                // Read live, not from composition: this runs in the same frame the state changes.
                if (sheetState.targetValue == SheetValue.Hidden) {
                    currentOnBackWhileClosing?.invoke()
                } else {
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) currentOnDismissRequest()
                    }
                }
            }
            content()
        }
    }
}
