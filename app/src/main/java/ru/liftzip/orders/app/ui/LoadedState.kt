package ru.liftzip.orders.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/** Cancels an obsolete load when the item or its revision changes. */
@Composable
internal fun <T> rememberLoaded(initial: T, vararg keys: Any?, load: suspend () -> T): State<T> {
    val state = remember(*keys) { mutableStateOf(initial) }
    LaunchedEffect(state) { state.value = load() }
    return state
}
