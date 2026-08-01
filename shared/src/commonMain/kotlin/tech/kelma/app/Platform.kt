package tech.kelma.app

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform