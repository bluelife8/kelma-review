package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertFalse

class SharedLogicAndroidHostTest {
    @Test
    fun androidUsesMobileChrome() {
        assertFalse(isDesktopApp)
    }
}
