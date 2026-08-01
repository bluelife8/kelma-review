package tech.kelma.app

import java.util.UUID

actual fun randomUuidString(): String = UUID.randomUUID().toString()
