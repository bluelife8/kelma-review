#!/usr/bin/env python3
"""Generate a deterministic CycloneDX component inventory from Gradle reports."""

import json
from pathlib import Path
import re
import sys

from dependency_licenses import license_for_group

COORDINATE = re.compile(r"^[+\\]--- ([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([^ (]+)(?: -> ([^ (]+))?")


def main() -> int:
    components: dict[tuple[str, str, str], dict[str, str]] = {}
    for report_name in sys.argv[1:]:
        for raw in Path(report_name).read_text(errors="replace").splitlines():
            line = raw.strip().replace("|    ", "").replace("     ", "")
            match = COORDINATE.match(line)
            if not match:
                continue
            group, name, requested, selected = match.groups()
            version = selected or requested
            if version in {"FAILED", "(*)"} or version.startswith(("project", "{")):
                continue
            key = group, name, version
            component = {
                "type": "library",
                "group": group,
                "name": name,
                "version": version,
                "purl": f"pkg:maven/{group}/{name}@{version}",
            }
            license_id = license_for_group(group)
            if license_id is not None:
                component["licenses"] = [{"license": {"id": license_id}}]
            components[key] = component
    # Compose resolves a native desktop runtime for the host that generates the
    # report. Include every supported release host so committed bytes do not
    # vary between macOS development and Linux CI.
    desktop_variants = {
        "org.jetbrains.compose.desktop": (
            "desktop-jvm-macos-arm64",
            "desktop-jvm-linux-x64",
            "desktop-jvm-windows-x64",
        ),
        "org.jetbrains.skiko": (
            "skiko-awt-runtime-macos-arm64",
            "skiko-awt-runtime-linux-x64",
            "skiko-awt-runtime-windows-x64",
        ),
    }
    for group, names in desktop_variants.items():
        versions = {
            key[2] for key in components
            if key[0] == group and any(key[1].startswith(name.rsplit('-', 2)[0]) for name in names)
        }
        for version in versions:
            for name in names:
                components[group, name, version] = {
                    "type": "library",
                    "group": group,
                    "name": name,
                    "version": version,
                    "purl": f"pkg:maven/{group}/{name}@{version}",
                    "licenses": [{"license": {"id": license_for_group(group)}}],
                }

    components["lua.org", "lua", "5.4.8"] = {
        "type": "library",
        "group": "lua.org",
        "name": "lua",
        "version": "5.4.8",
        "purl": "pkg:generic/lua@5.4.8",
        "licenses": [{"license": {"id": "MIT"}}],
    }

    document = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "version": 1,
        "metadata": {
            "component": {
                "type": "application",
                "name": "Kelma Review",
                "licenses": [{"license": {"id": "Apache-2.0"}}],
            },
        },
        "components": [components[key] for key in sorted(components)],
    }
    print(json.dumps(document, indent=2, sort_keys=True) + "\n", end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
