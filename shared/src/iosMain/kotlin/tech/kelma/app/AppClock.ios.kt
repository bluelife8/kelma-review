package tech.kelma.app

import platform.CoreFoundation.CFAbsoluteTimeGetCurrent
import platform.CoreFoundation.kCFAbsoluteTimeIntervalSince1970

actual fun currentEpochMillis(): Long =
    ((CFAbsoluteTimeGetCurrent() + kCFAbsoluteTimeIntervalSince1970) * 1_000.0).toLong()
