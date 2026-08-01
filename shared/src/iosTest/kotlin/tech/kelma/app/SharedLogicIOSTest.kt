package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertFalse

class SharedLogicIOSTest {
    @Test
    fun iosUsesMobileChrome() {
        assertFalse(isDesktopApp)
    }
}
