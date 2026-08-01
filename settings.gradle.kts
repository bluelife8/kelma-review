rootProject.name = "KelmaReview"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

val kelmaFsrsPath = providers.gradleProperty("kelmaFsrsPath").orNull
    ?: "../kelma-fsrs-v6"
check(file(kelmaFsrsPath).resolve("settings.gradle.kts").isFile) {
    "kelma-fsrs-v6 checkout not found at $kelmaFsrsPath; set -PkelmaFsrsPath=<path>"
}
includeBuild(kelmaFsrsPath) {
    dependencySubstitution {
        substitute(module("tech.kelma:kelma-fsrs-v6")).using(project(":"))
    }
}

include(":androidApp")
include(":desktopApp")
include(":shared")