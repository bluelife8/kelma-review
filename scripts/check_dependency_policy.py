#!/usr/bin/env python3
"""Fail on unreviewed groups, forbidden coordinates, or non-allowlisted licenses."""

import hashlib
import json
from pathlib import Path
import sys
import tomllib

from dependency_licenses import APPROVED_LICENSES, license_for_group

ROOT = Path(__file__).resolve().parents[1]
APPROVED_GROUPS = {
    "androidx.activity",
    "androidx.appcompat",
    "androidx.core",
    "androidx.espresso",
    "androidx.test",
    "androidx.test.espresso",
    "androidx.test.ext",
    "app.cash.sqldelight",
    "com.squareup.okio",
    "com.squareup.zstd",
    "io.ktor",
    "junit",
    "org.jetbrains.androidx.lifecycle",
    "org.jetbrains.compose.components",
    "org.jetbrains.compose.foundation",
    "org.jetbrains.compose.material",
    "org.jetbrains.compose.material3",
    "org.jetbrains.compose.runtime",
    "org.jetbrains.compose.ui",
    "org.jetbrains.kotlin",
    "org.jetbrains.kotlinx",
    "org.openjfx",
}
FORBIDDEN_MARKERS = ("anki", "rslib", "copyleft", "agpl", "gpl")


def main() -> int:
    catalog = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())
    failures: list[str] = []
    for alias, declaration in catalog.get("libraries", {}).items():
        module = declaration.get("module", "")
        group = module.partition(":")[0]
        lowered = module.lower()
        if group not in APPROVED_GROUPS:
            failures.append(f"{alias}: unreviewed dependency group {group}")
        license_id = license_for_group(group)
        if license_id not in APPROVED_LICENSES:
            failures.append(f"{alias}: unapproved or unknown license for {module}")
        if any(marker in lowered for marker in FORBIDDEN_MARKERS):
            failures.append(f"{alias}: forbidden dependency coordinate {module}")
    for required in (
        "LICENSE",
        "THIRD_PARTY_NOTICES.md",
        "sbom.cdx.json",
        "native/lua/LICENSE",
        "native/lua/UPSTREAM.md",
        "native/lua/SHA256SUMS",
    ):
        if not (ROOT / required).is_file():
            failures.append(f"missing {required}")
    lua_root = ROOT / "native/lua"
    checksum_file = lua_root / "SHA256SUMS"
    if checksum_file.is_file():
        listed_sources: set[str] = set()
        for line in checksum_file.read_text().splitlines():
            expected, relative = line.split(maxsplit=1)
            listed_sources.add(relative)
            source = lua_root / relative
            if not source.is_file():
                failures.append(f"missing vendored Lua source {relative}")
            elif hashlib.sha256(source.read_bytes()).hexdigest() != expected:
                failures.append(f"vendored Lua source checksum changed: {relative}")
        actual_sources = {
            str(path.relative_to(lua_root))
            for path in (lua_root / "src").iterdir()
            if path.is_file()
        }
        if listed_sources != actual_sources:
            failures.append("vendored Lua source inventory does not match SHA256SUMS")
    sbom_path = ROOT / "sbom.cdx.json"
    if sbom_path.is_file():
        sbom_components = json.loads(sbom_path.read_text()).get("components", [])
        for component in sbom_components:
            identities = {
                entry.get("license", {}).get("id")
                for entry in component.get("licenses", [])
            }
            if not identities or not identities.issubset(APPROVED_LICENSES):
                coordinate = f"{component.get('group')}:{component.get('name')}:{component.get('version')}"
                failures.append(f"SBOM component has unapproved or unknown license: {coordinate}")
        if not any(
            component.get("group") == "lua.org"
            and component.get("name") == "lua"
            and component.get("version") == "5.4.8"
            for component in sbom_components
        ):
            failures.append("SBOM is missing vendored Lua 5.4.8")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print("Dependency policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
