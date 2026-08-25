import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

val luaNativeRoot = rootProject.file("native/lua")
val luaNativeSources = fileTree(luaNativeRoot.resolve("src")) {
    include("*.c")
    exclude("liolib.c", "loslib.c", "ldblib.c", "linit.c")
}.files.sortedBy(File::getName)
val luaNativeHeaders = fileTree(luaNativeRoot) { include("*.h", "src/*.h") }
val desktopLuaResources = layout.buildDirectory.dir("generated/lua/jvmMain/resources")
val desktopLuaLibraryName = System.mapLibraryName("kelma_lua")
val appStoreBuild = providers.gradleProperty("kelmaAppStoreBuild")
    .map(String::toBooleanStrict)
    .orElse(false)

val buildDesktopLuaRuntime by tasks.registering(Exec::class) {
    val output = desktopLuaResources.map { it.file("native/$desktopLuaLibraryName") }
    inputs.files(luaNativeSources, luaNativeHeaders)
    inputs.files(
        luaNativeRoot.resolve("kelma_lua_host.c"),
        luaNativeRoot.resolve("kelma_lua_calls.c"),
        luaNativeRoot.resolve("kelma_lua_jni.c"),
    )
    outputs.file(output)
    doFirst { output.get().asFile.parentFile.mkdirs() }
    val javaHome = File(System.getProperty("java.home"))
    val commonArguments = listOf(
        "-std=c99", "-O2", "-Wall", "-Wextra", "-Werror",
        "-I${luaNativeRoot.resolve("src")}", "-I$luaNativeRoot",
        "-I${javaHome.resolve("include")}",
    )
    val sourceArguments = listOf(
        luaNativeRoot.resolve("kelma_lua_host.c").absolutePath,
        luaNativeRoot.resolve("kelma_lua_calls.c").absolutePath,
        luaNativeRoot.resolve("kelma_lua_jni.c").absolutePath,
    ) + luaNativeSources.map(File::getAbsolutePath)
    when {
        System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> commandLine(
            "cc", *commonArguments.toTypedArray(), "-I${javaHome.resolve("include/darwin")}",
            "-DLUA_USE_MACOSX", "-dynamiclib", *sourceArguments.toTypedArray(),
            "-o", output.get().asFile.absolutePath, "-lm",
        )
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> commandLine(
            "cl", "/nologo", "/O2", "/LD", "/D_CRT_SECURE_NO_WARNINGS", "/DLUA_USE_WINDOWS",
            "/I${luaNativeRoot.resolve("src")}", "/I$luaNativeRoot", "/I${javaHome.resolve("include")}",
            "/I${javaHome.resolve("include/win32")}", *sourceArguments.toTypedArray(),
            "/Fe:${output.get().asFile.absolutePath}",
        )
        else -> commandLine(
            "cc", *commonArguments.toTypedArray(), "-I${javaHome.resolve("include/linux")}",
            "-DLUA_USE_LINUX", "-shared", "-fPIC", *sourceArguments.toTypedArray(),
            "-o", output.get().asFile.absolutePath, "-ldl", "-lm",
        )
    }
}

val javafxPlatform = when {
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) ->
        if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "mac-aarch64" else "mac"
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "win"
    System.getProperty("os.arch") in setOf("aarch64", "arm64") -> "linux-aarch64"
    else -> "linux"
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        val targetLabel = iosTarget.name.replaceFirstChar(Char::uppercase)
        val sdk = if (iosTarget.name == "iosArm64") "iphoneos" else "iphonesimulator"
        val minimumFlag = if (iosTarget.name == "iosArm64") {
            "-miphoneos-version-min=15.0"
        } else {
            "-mios-simulator-version-min=15.0"
        }
        val outputDirectory = layout.buildDirectory.dir("native/lua/${iosTarget.name}")
        val luaLibrary = outputDirectory.map { it.file("libkelma_lua.a") }
        val buildLuaRuntime = tasks.register<Exec>("build${targetLabel}LuaRuntime") {
            inputs.files(luaNativeSources, luaNativeHeaders)
            inputs.files(
                luaNativeRoot.resolve("kelma_lua_host.c"),
                luaNativeRoot.resolve("kelma_lua_calls.c"),
            )
            outputs.file(luaLibrary)
            val sources = (listOf(
                luaNativeRoot.resolve("kelma_lua_host.c"),
                luaNativeRoot.resolve("kelma_lua_calls.c"),
            ) + luaNativeSources)
                .joinToString(" ") { "'${it.absolutePath}'" }
            val outputPath = luaLibrary.get().asFile.absolutePath
            val objectDirectory = outputDirectory.get().dir("objects").asFile.absolutePath
            commandLine(
                "bash",
                "-c",
                """
                set -euo pipefail
                rm -rf '$objectDirectory'
                mkdir -p '$objectDirectory'
                sdk_path="${'$'}(xcrun --sdk $sdk --show-sdk-path)"
                index=0
                for source in $sources; do
                  xcrun --sdk $sdk clang -std=c99 -O2 -Wall -Wextra -Werror -fPIC -arch arm64 $minimumFlag \
                    -isysroot "${'$'}sdk_path" -DLUA_USE_POSIX -I'${luaNativeRoot.resolve("src")}' \
                    -I'$luaNativeRoot' -c "${'$'}source" -o "$objectDirectory/${'$'}index.o"
                  index=${'$'}((index + 1))
                done
                xcrun --sdk $sdk ar rcs '$outputPath' "$objectDirectory"/*.o
                """.trimIndent(),
            )
        }
        if (!appStoreBuild.get()) {
            iosTarget.compilations.getByName("main").cinterops.create("kelmaLua") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/kelmaLua.def"))
                includeDirs(luaNativeRoot)
                extraOpts(
                    "-libraryPath", outputDirectory.get().asFile.absolutePath,
                    "-staticLibrary", luaLibrary.get().asFile.name,
                )
            }
            tasks.named("cinteropKelmaLua$targetLabel").configure { dependsOn(buildLuaRuntime) }
        }
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
            binaryOption("bundleId", "tech.kelma.app.shared")
        }
    }
    
    jvm()
    
    android {
       namespace = "tech.kelma.app.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }
    
    sourceSets {
        if (appStoreBuild.get()) {
            iosMain {
                kotlin.exclude("**/PluginLuaRuntime.ios.kt")
                kotlin.srcDir("src/iosAppStoreMain/kotlin")
            }
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.androidDriver)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.testExt.junit)
            implementation(libs.kotlin.test)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.nativeDriver)
        }
        getByName("jvmMain").resources.srcDir(desktopLuaResources)
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("org.openjfx:javafx-base:${libs.versions.javafx.get()}:$javafxPlatform")
            implementation("org.openjfx:javafx-controls:${libs.versions.javafx.get()}:$javafxPlatform")
            implementation("org.openjfx:javafx-graphics:${libs.versions.javafx.get()}:$javafxPlatform")
            implementation("org.openjfx:javafx-media:${libs.versions.javafx.get()}:$javafxPlatform")
            implementation("org.openjfx:javafx-swing:${libs.versions.javafx.get()}:$javafxPlatform")
            implementation("org.openjfx:javafx-web:${libs.versions.javafx.get()}:$javafxPlatform")
            implementation(libs.ktor.client.cio)
            implementation(libs.sqldelight.sqliteDriver)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(libs.compose.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
        commonMain.dependencies {
            implementation("tech.kelma:kelma-fsrs-v6:0.2.0-SNAPSHOT")
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutines.core)
            implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.4.0")
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
            implementation(libs.zstd.kmp.okio)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.kotlinxJson)
            implementation(libs.sqldelight.runtime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

sqldelight {
    databases {
        create("KelmaDatabase") {
            packageName.set("tech.kelma.db")
        }
    }
}

tasks.named("jvmProcessResources").configure { dependsOn(buildDesktopLuaRuntime) }

tasks.withType<Test>().configureEach {
    systemProperty("tech.kelma.disable-native-card-renderer", "true")
}
