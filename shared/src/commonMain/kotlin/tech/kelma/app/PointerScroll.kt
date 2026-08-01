package tech.kelma.app

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.Modifier

/** Adds platform pointer-wheel handling without changing native touch drag behavior. */
internal expect fun Modifier.platformPointerScroll(state: ScrollableState): Modifier
