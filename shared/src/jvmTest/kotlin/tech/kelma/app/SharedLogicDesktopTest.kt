package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertTrue

class SharedLogicDesktopTest {
    @Test
    fun desktopUsesDesktopChrome() {
        assertTrue(isDesktopApp)
    }
}
