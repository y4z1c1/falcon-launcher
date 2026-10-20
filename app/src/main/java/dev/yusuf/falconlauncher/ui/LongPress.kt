package dev.yusuf.falconlauncher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LONG_PRESS_MS = 550L

fun KeyEvent.isConfirmKey() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/**
 * Opens a menu when OK is held, or when the remote's Menu key is pressed.
 *
 * A timer decides the long press, so it works the same whether or not the remote sends key
 * repeats. Once the menu has fired, the rest of that press (repeats and release) is swallowed,
 * so it can't click anything.
 */
@Composable
fun Modifier.longPressMenu(onMenu: (() -> Unit)?): Modifier {
    if (onMenu == null) return this
    val scope = rememberCoroutineScope()
    val currentOnMenu by rememberUpdatedState(onMenu)
    val press = remember { PressState() }
    return onPreviewKeyEvent { event ->
        if (event.key == Key.Menu) {
            if (event.type == KeyEventType.KeyUp) currentOnMenu()
            return@onPreviewKeyEvent true
        }
        if (!event.isConfirmKey()) return@onPreviewKeyEvent false
        when (event.type) {
            KeyEventType.KeyDown -> if (event.nativeKeyEvent.repeatCount == 0) {
                press.fired = false
                press.timer?.cancel()
                press.timer = scope.launch {
                    delay(LONG_PRESS_MS)
                    press.fired = true
                    currentOnMenu()
                }
                false
            } else {
                // Don't let the clickable see repeats; it would treat them as its own long press.
                true
            }
            KeyEventType.KeyUp -> {
                press.timer?.cancel()
                press.fired.also { press.fired = false }
            }
            else -> false
        }
    }
}

private class PressState {
    var timer: Job? = null
    var fired = false
}

/**
 * For dialogs opened by a long press: ignores the OK press that is still held down when the
 * dialog appears (its repeats and release) until a fresh press starts.
 */
@Composable
fun Modifier.ignoreHeldConfirm(): Modifier {
    val state = remember { object { var armed = false } }
    return onPreviewKeyEvent { event ->
        if (state.armed || !event.isConfirmKey()) return@onPreviewKeyEvent false
        when {
            event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 -> {
                state.armed = true
                false
            }
            event.type == KeyEventType.KeyUp -> {
                state.armed = true
                true
            }
            else -> true
        }
    }
}
