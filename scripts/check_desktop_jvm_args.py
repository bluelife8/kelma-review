#!/usr/bin/env python3
"""Verify the desktop JVM argument configuration.

``desktopApp/jvm-args.txt`` is the single source of truth for the JVM flags the
desktop application runs with. The packaged distribution and the development
launcher must both read it, and a packaged application image must actually
contain every flag. A packaged app that runs with different JVM settings than
the development build has previously produced different rendering behaviour, and
untuned defaults size the heap, GC threads, and JIT threads from host RAM and
CPU count rather than from what the application needs.

Usage:
    python scripts/check_desktop_jvm_args.py [packaged-app-root]
"""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ARGS_FILE = ROOT / "desktopApp" / "jvm-args.txt"
BUILD_FILE = ROOT / "desktopApp" / "build.gradle.kts"
DEV_SCRIPT = ROOT / "scripts" / "run-desktop-dev.sh"

# Flags whose absence silently reintroduces host-derived sizing.
REQUIRED_PREFIXES = (
    "-Xmx",
    "-Xms",
    "-XX:ParallelGCThreads=",
    "-XX:ConcGCThreads=",
    "-XX:CICompilerCount=",
    "-XX:MaxMetaspaceSize=",
    "-XX:ReservedCodeCacheSize=",
)


def parse_jvm_args(path: Path) -> list[str]:
    """Parse the shared argument file using the same rules as its consumers."""
    arguments: list[str] = []
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        # A single argument may contain spaces (-Dapple.awt.application.name=Kelma
        # Review), but two arguments sharing a line would be passed as one.
        if any(token.startswith("-") for token in line.split()[1:]):
            raise SystemExit(f"{path.name}:{number}: put one argument per line: {line!r}")
        if not line.startswith("-"):
            raise SystemExit(f"{path.name}:{number}: expected a JVM argument, found {line!r}")
        arguments.append(line)
    return arguments


def packaged_java_options(root: Path) -> list[str]:
    """Read the ``java-options`` entries from a packaged application image."""
    configurations = sorted(root.glob("**/Contents/app/*.cfg")) or sorted(root.glob("**/app/*.cfg"))
    if not configurations:
        raise SystemExit(f"no packaged application configuration found under {root}")
    options: list[str] = []
    for line in configurations[0].read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() == "java-options":
            options.append(value.strip())
    return options


def main() -> int:
    arguments = parse_jvm_args(ARGS_FILE)

    missing_required = [
        prefix for prefix in REQUIRED_PREFIXES if not any(argument.startswith(prefix) for argument in arguments)
    ]
    if missing_required:
        print(f"{ARGS_FILE.name} is missing required flags: {', '.join(missing_required)}", file=sys.stderr)
        return 1

    duplicates = sorted({argument for argument in arguments if arguments.count(argument) > 1})
    if duplicates:
        print(f"{ARGS_FILE.name} repeats arguments: {', '.join(duplicates)}", file=sys.stderr)
        return 1

    for consumer in (BUILD_FILE, DEV_SCRIPT):
        if "jvm-args.txt" not in consumer.read_text(encoding="utf-8"):
            print(f"{consumer.relative_to(ROOT)} no longer reads desktopApp/jvm-args.txt", file=sys.stderr)
            return 1

    if len(sys.argv) > 1:
        packaged = packaged_java_options(Path(sys.argv[1]))
        missing_packaged = [argument for argument in arguments if argument not in packaged]
        if missing_packaged:
            print(f"packaged image is missing JVM arguments: {', '.join(missing_packaged)}", file=sys.stderr)
            return 1

    print(f"desktop JVM arguments verified ({len(arguments)} flags)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
