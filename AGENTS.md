# Contributor instructions

Read `docs/DESIGN.md`, `docs/PLUGIN_SYSTEM.md`, `docs/STYLE.md`, and `docs/VISUAL_PARITY.md` before changing architecture, public APIs, or UI styling. Read `docs/RELEASE.md` completely before changing versions, packaging, release CI, or public artifacts.

`docs/STYLE.md` is binding for hand-written code. In particular, keep files under approximately 500 lines, preserve multiplatform boundaries, keep Compose declarative, and add deterministic tests for behavior changes.

This is a permissively licensed clean room prototype. Do not copy implementation code from Anki, ForkiCards, or other AGPL repositories.
