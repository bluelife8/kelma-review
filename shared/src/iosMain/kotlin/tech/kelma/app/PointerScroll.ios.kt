package tech.kelma.app

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

/** Compose iOS touch scrolling works natively, but Simulator trackpad wheels need explicit routing. */
@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.platformPointerScroll(state: ScrollableState): Modifier =
    onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
        val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
        if (delta != 0f) {
            state.dispatchRawDelta(-delta)
            event.changes.forEach { it.consume() }
        }
    }
