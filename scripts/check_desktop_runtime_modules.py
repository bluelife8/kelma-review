#!/usr/bin/env python3
"""Verify that a packaged Compose Desktop runtime contains dynamic modules."""

from __future__ import annotations

import re
import sys
import zipfile
from pathlib import Path

REQUIRED_MODULES = {"java.sql", "jdk.jsobject", "jdk.unsupported.desktop"}
RENDERER_CLASSES = {
    "tech/kelma/app/DesktopBrowserCardPanel$WhenMappings.class",
    "tech/kelma/app/DesktopBrowserCardPanel.class",
    "tech/kelma/app/DesktopCardBridge.class",
    "tech/kelma/app/RichCardRenderingKt.class",
    "tech/kelma/app/RichCardRendering_jvmKt.class",
}


def packaged_modules(root: Path) -> set[str]:
    release_files = [
        path
        for path in root.rglob("release")
        if "runtime" in path.parts and path.is_file()
    ]
    if len(release_files) != 1:
        raise RuntimeError(
            f"expected one packaged runtime release file, found {len(release_files)}"
        )
    text = release_files[0].read_text(encoding="utf-8")
    match = re.search(r'^MODULES="([^"]*)"$', text, re.MULTILINE)
    if not match:
        raise RuntimeError(f"MODULES entry missing from {release_files[0]}")
    return set(match.group(1).split())


def renderer_classes(jar: Path) -> dict[str, bytes]:
    with zipfile.ZipFile(jar) as archive:
        return {name: archive.read(name) for name in RENDERER_CLASSES}


def verify_packaged_renderer(root: Path) -> None:
    packaged_jars = list(root.rglob("shared-jvm-*.jar"))
    source_jar = Path("shared/build/libs/shared-jvm.jar")
    if len(packaged_jars) != 1:
        raise RuntimeError(f"expected one packaged shared jar, found {len(packaged_jars)}")
    if renderer_classes(packaged_jars[0]) != renderer_classes(source_jar):
        raise RuntimeError("packaged desktop renderer differs from the verified development build")


def main() -> int:
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(
        "desktopApp/build/compose/binaries/main/app"
    )
    missing = REQUIRED_MODULES - packaged_modules(root)
    if missing:
        print(f"packaged desktop runtime is missing: {', '.join(sorted(missing))}")
        return 1
    verify_packaged_renderer(root)
    print(f"packaged desktop runtime contains: {', '.join(sorted(REQUIRED_MODULES))}")
    print("packaged desktop renderer matches the verified development classes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
