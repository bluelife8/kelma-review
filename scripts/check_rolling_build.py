#!/usr/bin/env python3
"""Protect the distinct, non-production rolling iOS preview channel."""

from __future__ import annotations

import argparse
import json
import plistlib
import re
import struct
import zipfile
from pathlib import Path

ROLLING_BUNDLE_ID = "tech.kelma.app.KelmaReview.Rolling"
ROLLING_DISPLAY_NAME = "Kelma Rolling"
STABLE_BUNDLE_ID = "tech.kelma.app.KelmaReview"
ICON_FILENAME = "app-icon-rolling-1024.png"


def fail(message: str) -> None:
    raise SystemExit(message)


def check_png(path: Path) -> None:
    payload = path.read_bytes()
    if payload[:8] != b"\x89PNG\r\n\x1a\n" or payload[12:16] != b"IHDR":
        fail(f"rolling icon is not a valid PNG: {path}")
    width, height, bit_depth, color_type = struct.unpack(">IIBB", payload[16:26])
    if (width, height) != (1024, 1024):
        fail(f"rolling icon must be 1024x1024, found {width}x{height}")
    if bit_depth != 8 or color_type != 2:
        fail("rolling icon must be an opaque 8-bit RGB PNG with no alpha channel")


def check_source(root: Path) -> None:
    stable_config = (root / "iosApp/Configuration/Config.xcconfig").read_text(encoding="utf-8")
    if f"PRODUCT_BUNDLE_IDENTIFIER={STABLE_BUNDLE_ID}" not in stable_config:
        fail("stable iOS bundle identifier changed while configuring rolling builds")

    icon_dir = root / "iosApp/iosApp/Assets.xcassets/AppIcon-Rolling.appiconset"
    manifest = json.loads((icon_dir / "Contents.json").read_text(encoding="utf-8"))
    filenames = {image.get("filename") for image in manifest.get("images", [])}
    if ICON_FILENAME not in filenames:
        fail("rolling app icon manifest does not reference its distinct artwork")
    check_png(icon_dir / ICON_FILENAME)

    workflow = (root / ".github/workflows/rolling-ios.yml").read_text(encoding="utf-8")
    required = (
        "branches:\n      - rolling",
        f"PRODUCT_BUNDLE_IDENTIFIER='{ROLLING_BUNDLE_ID}'",
        f"INFOPLIST_KEY_CFBundleDisplayName='{ROLLING_DISPLAY_NAME}'",
        "ASSETCATALOG_COMPILER_APPICON_NAME='AppIcon-Rolling'",
        "CODE_SIGNING_ALLOWED=NO",
        "KelmaReview-Rolling.ipa",
        "--prerelease",
    )
    for value in required:
        if value not in workflow:
            fail(f"rolling workflow is missing required policy: {value}")
    forbidden = (
        "public/altstore/source.json",
        "tech.kelma.altstore.rolling",
        "refs/heads/main",
    )
    for value in forbidden:
        if value in workflow:
            fail(f"rolling workflow must not alter canonical distribution state: {value}")


def check_ipa(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        info_paths = [
            name for name in names
            if re.fullmatch(r"Payload/[^/]+\.app/Info\.plist", name)
        ]
        if len(info_paths) != 1:
            fail(f"rolling IPA must contain one app Info.plist, found {len(info_paths)}")
        info = plistlib.loads(archive.read(info_paths[0]))
    if info.get("CFBundleIdentifier") != ROLLING_BUNDLE_ID:
        fail(f"unexpected rolling IPA bundle identifier: {info.get('CFBundleIdentifier')!r}")
    if info.get("CFBundleDisplayName") != ROLLING_DISPLAY_NAME:
        fail(f"unexpected rolling IPA display name: {info.get('CFBundleDisplayName')!r}")
    version = str(info.get("CFBundleShortVersionString", ""))
    build = str(info.get("CFBundleVersion", ""))
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        fail(f"rolling IPA has invalid marketing version: {version!r}")
    if not build.isdigit():
        fail(f"rolling IPA has nonnumeric build version: {build!r}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ipa", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    check_source(root)
    if args.ipa:
        if not args.ipa.is_file():
            fail(f"rolling IPA does not exist: {args.ipa}")
        check_ipa(args.ipa)
    print("rolling build policy passed")


if __name__ == "__main__":
    main()
