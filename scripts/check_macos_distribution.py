#!/usr/bin/env python3
"""Verify Developer ID signing, hardened runtime, notarization, and stapling."""

from __future__ import annotations

import argparse
import plistlib
import re
import subprocess
import tempfile
from pathlib import Path

EXPECTED_BUNDLE_ID = "tech.kelma.app.KelmaReview.desktop"
EXPECTED_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")


def fail(message: str) -> None:
    raise SystemExit(message)


def run(*arguments: str) -> str:
    result = subprocess.run(arguments, check=True, capture_output=True, text=True)
    return result.stdout + result.stderr


def check_source(root: Path) -> None:
    build = (root / "desktopApp/build.gradle.kts").read_text(encoding="utf-8")
    entitlements_path = root / "desktopApp/src/main/resources/macos-entitlements.plist"
    required = (
        f'bundleID = "{EXPECTED_BUNDLE_ID}"',
        'providers.gradleProperty("kelmaPackageVersion").orElse("1.0.17")',
        'entitlementsFile.set(project.file("src/main/resources/macos-entitlements.plist"))',
        'runtimeEntitlementsFile.set(project.file("src/main/resources/macos-entitlements.plist"))',
        "<key>NSMicrophoneUsageDescription</key>",
    )
    for setting in required:
        if setting not in build:
            fail(f"macOS distribution configuration is missing {setting}")
    entitlements = plistlib.loads(entitlements_path.read_bytes())
    if entitlements.get("com.apple.security.cs.allow-jit") is not True:
        fail("macOS hardened runtime entitlements do not permit the packaged JVM JIT")


def find_app(root: Path) -> Path:
    apps = sorted(root.rglob("*.app"))
    if len(apps) != 1:
        fail(f"expected one app inside mounted DMG, found {len(apps)}")
    return apps[0]


def check_hardened_runtime(app: Path) -> None:
    result = subprocess.run(
        ["codesign", "-d", "--entitlements", ":-", str(app)],
        check=True,
        capture_output=True,
    )
    payload = result.stdout or result.stderr
    start = payload.find(b"<?xml")
    if start < 0:
        fail("macOS application signature has no readable entitlements")
    entitlements = plistlib.loads(payload[start:])
    if entitlements.get("com.apple.security.cs.allow-jit") is not True:
        fail("signed macOS application does not permit the packaged JVM JIT")


def check_app(app: Path, version: str) -> None:
    info = plistlib.loads((app / "Contents/Info.plist").read_bytes())
    if info.get("CFBundleIdentifier") != EXPECTED_BUNDLE_ID:
        fail(f"unexpected macOS bundle ID: {info.get('CFBundleIdentifier')!r}")
    if info.get("CFBundleShortVersionString") != version:
        fail(f"unexpected macOS version: {info.get('CFBundleShortVersionString')!r}")
    if not info.get("NSMicrophoneUsageDescription"):
        fail("macOS application is missing its microphone usage description")
    signature = run("codesign", "--verify", "--deep", "--strict", "--verbose=2", str(app))
    if "valid on disk" not in signature or "satisfies its Designated Requirement" not in signature:
        fail("macOS application signature verification did not succeed")
    details = run("codesign", "-dvv", str(app))
    if "Authority=Developer ID Application:" not in details:
        fail("macOS application was not signed with Developer ID Application")
    if "flags=" not in details or "runtime" not in details:
        fail("macOS application signature does not enable hardened runtime")
    check_hardened_runtime(app)
    run("spctl", "--assess", "--type", "execute", "--verbose=2", str(app))


def check_dmg(path: Path, version: str) -> None:
    run("codesign", "--verify", "--verbose=2", str(path))
    run("xcrun", "stapler", "validate", str(path))
    run("spctl", "--assess", "--type", "open", "--context", "context:primary-signature", "--verbose=2", str(path))
    with tempfile.TemporaryDirectory(prefix="kelma-dmg-") as temporary:
        mount = Path(temporary)
        run("hdiutil", "attach", "-nobrowse", "-readonly", "-mountpoint", str(mount), str(path))
        try:
            check_app(find_app(mount), version)
        finally:
            run("hdiutil", "detach", str(mount))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dmg", type=Path)
    parser.add_argument("--version")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    check_source(root)
    if bool(args.dmg) != bool(args.version):
        fail("--dmg and --version must be supplied together")
    if args.version:
        if not EXPECTED_VERSION.fullmatch(args.version):
            fail("version must be a numeric X.Y.Z value")
        if not args.dmg.is_file():
            fail(f"DMG does not exist: {args.dmg}")
        check_dmg(args.dmg, args.version)
    print("macOS distribution policy passed")


if __name__ == "__main__":
    main()
