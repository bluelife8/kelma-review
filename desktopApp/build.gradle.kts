import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

val jvmArgsFile = project.file("jvm-args.txt")

/**
 * Reads [desktopApp/jvm-args.txt], the single source of truth shared with
 * scripts/run-desktop-dev.sh. The packaged app otherwise runs with a different
 * JVM configuration than the development build that renders cards correctly.
 */
fun readDesktopJvmArgs(): List<String> =
    jvmArgsFile.readLines()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }

abstract class StageDevRuntime : DefaultTask() {
    @get:Classpath
    abstract val runtimeFiles: ConfigurableFileCollection

    @get:InputFile
    abstract val applicationJar: RegularFileProperty

    @get:Input
    abstract val destinationPath: Property<String>

    @get:OutputDirectory
    val destinationDirectory: File
        get() = File(destinationPath.get())

    @TaskAction
    fun stage() {
        destinationDirectory.deleteRecursively()
        val lib = destinationDirectory.resolve("lib").apply(File::mkdirs)
        applicationJar.get().asFile.copyTo(lib.resolve("desktopApp.jar"))
        runtimeFiles.files.sortedBy(File::getAbsolutePath).forEachIndexed { index, dependency ->
            dependency.copyTo(lib.resolve("runtime-${index.toString().padStart(3, '0')}-${dependency.name}"))
        }
        destinationDirectory.resolve("resources").mkdirs()
    }
}

val stageDevRuntime by tasks.registering(StageDevRuntime::class) {
    dependsOn(tasks.jar)
    runtimeFiles.from(configurations.runtimeClasspath)
    applicationJar.set(tasks.jar.flatMap { it.archiveFile })
    destinationPath.set(
        providers.gradleProperty("kelmaDevRuntimeDir")
            .orElse(layout.buildDirectory.dir("dev-runtime").map { it.asFile.absolutePath }),
    )
}

compose.desktop {
    application {
        mainClass = "tech.kelma.app.MainKt"
        // desktopApp/jvm-args.txt is shared with the development launcher
        // (scripts/run-desktop-dev.sh) so the packaged app and the development
        // build can never run with different JVM configurations.
        jvmArgs += readDesktopJvmArgs()

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // JavaFX WebView's WebKit renderer loads JDK modules at render time
            // (jdk.xml.dom, java.scripting, jdk.charsets, jdk.unsupported, ...)
            // that jlink cannot infer from application bytecode. A stripped
            // runtime inits the engine but then paints stale/partial frames, so
            // ship the full module set to match the development JDK. The
            // explicit list documents the modules the renderer-module check
            // guards even though includeAllModules already covers them.
            includeAllModules = true
            modules("java.sql", "jdk.jsobject", "jdk.unsupported.desktop")
            packageName = "KelmaReview"
            packageVersion = "1.0.17"
            macOS {
                iconFile.set(project.file("src/main/resources/icon.icns"))
                // packageName stays "KelmaReview" so the bundle path, the DMG
                // file name, and the published quarantine instructions keep
                // working. Only the user-visible name changes.
                //
                // AppKit titles the application menu from CFBundleName and
                // ignores both CFBundleDisplayName and -Dapple.awt.application.name
                // for a bundled app, so CFBundleName is the key that matters.
                // Compose appends these keys after the ones jpackage generates
                // and the plist parser keeps the last value for a repeated key.
                dockName = "Kelma Review"
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleName</key><string>Kelma Review</string>
                        <key>CFBundleDisplayName</key><string>Kelma Review</string>
                    """.trimIndent()
                }
            }
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
        }
    }
}