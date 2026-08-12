#!/usr/bin/env python3
"""Validate store/community configuration without requiring signing credentials."""

from __future__ import annotations

import argparse
import plistlib
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
IOS_BUNDLE_ID = "tech.kelma.app.KelmaReview"
ANDROID_BUNDLE_ID = "tech.kelma.app"


def fail(message: str) -> None:
    raise SystemExit(message)


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def xcode_settings(configuration: str) -> dict[str, str]:
    result = subprocess.run(
        [
            "xcodebuild",
            "-showBuildSettings",
            "-project",
            "iosApp/iosApp.xcodeproj",
            "-target",
            "iosApp",
            "-configuration",
            configuration,
        ],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    settings: dict[str, str] = {}
    for line in result.stdout.splitlines():
        stripped = line.strip()
        if " = " in stripped:
            key, value = stripped.split(" = ", 1)
            settings[key] = value
    return settings


def check_source(check_xcode: bool) -> None:
    config = read("iosApp/Configuration/Config.xcconfig")
    app_store_config = read("iosApp/Configuration/AppStore.xcconfig")
    project = read("iosApp/iosApp.xcodeproj/project.pbxproj")
    shared_build = read("shared/build.gradle.kts")
    content_view = read("iosApp/iosApp/ContentView.swift")
    manifest = plistlib.loads((ROOT / "iosApp/iosApp/PrivacyInfo.xcprivacy").read_bytes())

    if f"PRODUCT_BUNDLE_IDENTIFIER={IOS_BUNDLE_ID}" not in config:
        fail("iOS bundle identifier is not the canonical Kelma Review identifier")
    if "PRODUCT_BUNDLE_IDENTIFIER=tech.kelma.app.KelmaReview$(TEAM_ID)" in config:
        fail("TEAM_ID must not be appended to the iOS bundle identifier")
    required_app_store_settings = (
        "KELMA_APP_STORE_BUILD=YES",
        "KOTLIN_FRAMEWORK_BUILD_TYPE=Release",
        "CODE_SIGN_STYLE=Automatic",
    )
    for setting in required_app_store_settings:
        if setting not in app_store_config:
            fail(f"App Store xcconfig is missing {setting}")
    if "baseConfigurationReferenceRelativePath = AppStore.xcconfig;" not in project:
        fail("Xcode AppStore configuration does not use AppStore.xcconfig")
    if "name = AppStore;" not in project:
        fail("Xcode AppStore build configuration is missing")
    if "-PkelmaAppStoreBuild=true" not in project:
        fail("Xcode does not request the plugin-free Kotlin framework")
    if "if (!appStoreBuild.get())" not in shared_build:
        fail("App Store builds do not exclude the iOS Lua cinterop")
    if 'kotlin.exclude("**/PluginLuaRuntime.ios.kt")' not in shared_build:
        fail("App Store builds do not exclude the community iOS Lua runtime")
    if "externalPluginsEnabled: !BuildChannel.isAppStore" not in content_view:
        fail("The App Store app does not disable external plugin behavior")
    if check_xcode:
        settings = xcode_settings("AppStore")
        expected = {
            "PRODUCT_BUNDLE_IDENTIFIER": IOS_BUNDLE_ID,
            "KELMA_APP_STORE_BUILD": "YES",
            "KOTLIN_FRAMEWORK_BUILD_TYPE": "Release",
            "IPHONEOS_DEPLOYMENT_TARGET": "15.0",
        }
        for key, value in expected.items():
            if settings.get(key) != value:
                fail(f"App Store Xcode setting {key} is {settings.get(key)!r}, expected {value!r}")
        if "KELMA_APP_STORE" not in settings.get("SWIFT_ACTIVE_COMPILATION_CONDITIONS", "").split():
            fail("App Store Xcode build does not define KELMA_APP_STORE")

    if manifest.get("NSPrivacyTracking") is not False:
        fail("Privacy manifest must declare tracking disabled")
    collected_types = {
        item.get("NSPrivacyCollectedDataType")
        for item in manifest.get("NSPrivacyCollectedDataTypes", [])
    }
    required_types = {
        "NSPrivacyCollectedDataTypeEmailAddress",
        "NSPrivacyCollectedDataTypeUserID",
        "NSPrivacyCollectedDataTypeOtherUserContent",
        "NSPrivacyCollectedDataTypeProductInteraction",
    }
    if not required_types.issubset(collected_types):
        fail("Privacy manifest does not cover account, content, and study data")
    reasons = {
        reason
        for api in manifest.get("NSPrivacyAccessedAPITypes", [])
        if api.get("NSPrivacyAccessedAPIType") == "NSPrivacyAccessedAPICategoryUserDefaults"
        for reason in api.get("NSPrivacyAccessedAPITypeReasons", [])
    }
    if "CA92.1" not in reasons:
        fail("Privacy manifest is missing the app-specific UserDefaults reason")

    android_build = read("androidApp/build.gradle.kts")
    android_manifest = read("androidApp/src/main/AndroidManifest.xml")
    if f'applicationId = "{ANDROID_BUNDLE_ID}"' not in android_build:
        fail("Android application ID changed unexpectedly")
    required_android_settings = (
        'create("play")',
        'debugSymbolLevel = "SYMBOL_TABLE"',
        'android:dataExtractionRules="@xml/data_extraction_rules"',
        'android.hardware.microphone',
        'android:required="false"',
    )
    android_policy = android_build + android_manifest
    for setting in required_android_settings:
        if setting not in android_policy:
            fail(f"Android store policy is missing {setting}")
    if "implementation(libs.compose.uiTooling)" in read("shared/build.gradle.kts"):
        fail("Compose UI tooling remains in shared production dependencies")
    if "implementation(libs.compose.uiTooling)" in android_build:
        fail("Compose UI tooling remains in the Android application")
    if "implementation(libs.compose.uiToolingPreview)" in android_build:
        fail("Compose preview tooling remains in the Android application")
    main_activity = read("androidApp/src/main/kotlin/tech/kelma/app/MainActivity.kt")
    if "@Preview" in main_activity or "AppAndroidPreview" in main_activity:
        fail("Android production source still contains the app preview entry point")


def archive_names(path: Path) -> set[str]:
    with zipfile.ZipFile(path) as archive:
        return set(archive.namelist())


def check_artifact(path: Path, kind: str) -> None:
    if not path.is_file():
        fail(f"Artifact does not exist: {path}")
    names = archive_names(path)
    lua_patterns = ("libkelma_lua", "PluginLuaRuntime", ".kelmaplugin")
    if kind == "ios-app-store":
        forbidden = [name for name in names if any(pattern in name for pattern in lua_patterns)]
        if forbidden:
            fail(f"App Store artifact contains external plugin runtime content: {forbidden[0]}")
        privacy = [name for name in names if name.endswith("PrivacyInfo.xcprivacy")]
        if not privacy:
            fail("App Store artifact does not contain PrivacyInfo.xcprivacy")
    elif kind == "android-play":
        if "base/manifest/AndroidManifest.xml" not in names:
            fail("Android App Bundle is missing the base manifest")
        required_abis = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
        packaged_abis = {
            name.split("/")[2]
            for name in names
            if name.startswith("base/lib/") and name.endswith("/libkelma_lua.so")
        }
        if packaged_abis != required_abis:
            fail(f"Android App Bundle ABIs are incomplete: {sorted(packaged_abis)}")
        symbol_abis = {
            name.split("/")[-2]
            for name in names
            if name.startswith("BUNDLE-METADATA/com.android.tools.build.debugsymbols/")
            and name.endswith("/libkelma_lua.so.sym")
        }
        if symbol_abis != required_abis:
            fail(f"Android native debug symbols are incomplete: {sorted(symbol_abis)}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifact", type=Path)
    parser.add_argument("--check-xcode", action="store_true")
    parser.add_argument("--kind", choices=("ios-app-store", "android-play"))
    args = parser.parse_args()
    check_source(args.check_xcode)
    if bool(args.artifact) != bool(args.kind):
        fail("--artifact and --kind must be supplied together")
    if args.artifact:
        check_artifact(args.artifact, args.kind)
    print("Store build policy passed")


if __name__ == "__main__":
    main()
