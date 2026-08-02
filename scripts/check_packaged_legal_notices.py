#!/usr/bin/env python3
"""Verify that a packaged Kelma Review artifact contains exact legal notices."""

from __future__ import annotations

import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "LICENSE.txt": (ROOT / "LICENSE").read_bytes(),
    "NOTICE.txt": (ROOT / "NOTICE").read_bytes(),
    "THIRD_PARTY_NOTICES.md": (ROOT / "THIRD_PARTY_NOTICES.md").read_bytes(),
    "LUA_LICENSE.txt": (ROOT / "native/lua/LICENSE").read_bytes(),
}
ARCHIVE_SUFFIXES = {".apk", ".ipa", ".jar", ".zip"}


def collect_from_archive(path: Path, found: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path) as archive:
        for entry in archive.namelist():
            name = Path(entry).name
            if "/files/legal/" in f"/{entry}" and name in EXPECTED:
                found[name] = archive.read(entry)


def collect(path: Path) -> dict[str, bytes]:
    found: dict[str, bytes] = {}
    candidates = (
        [path]
        if path.is_file()
        else sorted(candidate for candidate in path.rglob("*") if candidate.is_file())
    )
    for candidate in candidates:
        if "files/legal" in candidate.as_posix() and candidate.name in EXPECTED:
            found[candidate.name] = candidate.read_bytes()
        if candidate.suffix.lower() in ARCHIVE_SUFFIXES and zipfile.is_zipfile(candidate):
            collect_from_archive(candidate, found)
    return found


def main(arguments: list[str]) -> int:
    if len(arguments) != 1:
        print("usage: check_packaged_legal_notices.py <artifact-or-app-directory>", file=sys.stderr)
        return 2
    artifact = Path(arguments[0])
    if not artifact.exists():
        print(f"packaged artifact does not exist: {artifact}", file=sys.stderr)
        return 1
    found = collect(artifact)
    failures = [name for name, expected in EXPECTED.items() if found.get(name) != expected]
    if failures:
        print(f"missing or incorrect packaged legal notices: {', '.join(failures)}", file=sys.stderr)
        return 1
    print(f"Packaged legal notices passed: {artifact}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
