package ru.liftzip.orders.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun Modifier.keepVisibleWithKeyboard(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val ime = WindowInsets.ime
    val target = WindowInsets.imeAnimationTarget
    val density = LocalDensity.current

    LaunchedEffect(focused, ime, target, density) {
        if (!focused) return@LaunchedEffect
        snapshotFlow {
            val bottom = ime.getBottom(density)
            bottom.takeIf { it > 0 && it == target.getBottom(density) }
        }.collectLatest { settledHeight ->
            if (settledHeight != null) {
                // Let the scroll container lay out with the final keyboard inset.
                withFrameNanos { }
                requester.bringIntoView()
            }
        }
    }

    return bringIntoViewRequester(requester).onFocusChanged { focused = it.isFocused }
}
