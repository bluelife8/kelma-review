package tech.kelma.app

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.Modifier

internal actual fun Modifier.platformPointerScroll(state: ScrollableState): Modifier = this
