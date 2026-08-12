import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val signingProperties = providers.environmentVariablesPrefixedBy("KELMA_ANDROID_")
val playSigningEnabled = signingProperties.map { values ->
    setOf("KEYSTORE_PATH", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").all(values::containsKey)
}

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)

}

android {
    namespace = "tech.kelma.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "tech.kelma.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 17
        versionName = "1.0.17"
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("../native/lua/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        create("playUpload") {
            if (playSigningEnabled.get()) {
                val values = signingProperties.get()
                storeFile = rootProject.file(values.getValue("KEYSTORE_PATH"))
                storePassword = values.getValue("KEYSTORE_PASSWORD")
                keyAlias = values.getValue("KEY_ALIAS")
                keyPassword = values.getValue("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("play") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            if (playSigningEnabled.get()) {
                signingConfig = signingConfigs.getByName("playUpload")
            }
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}