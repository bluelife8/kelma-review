plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
        // Compose selects these native artifacts by the build host. Their
        // versions remain pinned by the locked Compose/Skiko graph and SBOM.
        listOf("macos-arm64", "linux-x64", "windows-x64").forEach { host ->
            ignoredDependencies.add("org.jetbrains.compose.desktop:desktop-jvm-$host")
            ignoredDependencies.add("org.jetbrains.skiko:skiko-awt-runtime-$host")
        }
        listOf("base", "controls", "graphics", "media", "swing", "web").forEach { module ->
            ignoredDependencies.add("org.openjfx:javafx-$module")
        }
    }
}