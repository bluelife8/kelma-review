package tech.kelma.app

import platform.Foundation.NSUUID

actual fun randomUuidString(): String = NSUUID().UUIDString()
