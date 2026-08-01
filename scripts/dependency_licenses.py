"""Reviewed SPDX identities for dependency groups used by Kelma release artifacts."""

APPROVED_LICENSES = {
    "Apache-2.0",
    "MIT",
    "BSD-3-Clause",
    "EPL-1.0",
    "GPL-2.0-with-classpath-exception",
}


def license_for_group(group: str) -> str | None:
    if group == "junit":
        return "EPL-1.0"
    if group == "org.slf4j":
        return "MIT"
    if group == "org.openjfx":
        return "GPL-2.0-with-classpath-exception"
    apache_prefixes = (
        "androidx.",
        "app.cash.sqldelight",
        "com.google.guava",
        "com.squareup.",
        "co.touchlab",
        "io.ktor",
        "org.jetbrains",
        "org.jspecify",
        "org.xerial",
    )
    return "Apache-2.0" if group.startswith(apache_prefixes) else None
