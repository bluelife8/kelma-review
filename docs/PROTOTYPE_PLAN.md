# Kotlin implementation plan

Status: Kotlin Multiplatform selected on 2026-07-25; the Flutter comparison is concluded.

## Purpose

Complete the original vertical-slice proofs in the selected Kotlin stack, then harden them into production architecture. The measurements below remain useful engineering baselines rather than framework-selection gates.

## Vertical slice

1. Launch on macOS, Android, and iOS.
2. Create/open a SQLDelight collection.
3. Display a deck list and one review screen from shared Compose code.
4. Fetch a due card, reveal it, answer it, and atomically append review history/update scheduling state.
5. Run validated FSRS behavior.
6. **Complete:** load a validated Lua plugin package from application storage on every community target.
7. **Complete:** let the plugin register a globally discoverable command, review event, and assignable pure renderer.
8. Let the plugin render a small portable host-UI panel; the manager and pure HTML/CSS renderer boundary are complete.
9. Let a Lua worker start a localhost JSON server and create a note through public collection RPC.
10. Package a desktop application and mobile debug builds.

## Required proof plugins

These are conformance fixtures, not promised bundled features.

- `hello-panel`: command, event, settings, and UI tree.
- `review-tag`: tags a note after an `Again` answer.
- `local-api`: localhost server implementing `version`, `deckNames`, and `addNote` without special host code.

## Measurements

Record for the Kotlin implementation:

- Clean checkout-to-running instructions.
- Clean and incremental build times.
- Release binary/package size.
- Cold startup time.
- Idle memory on desktop and mobile.
- Shared versus platform-specific LOC.
- Lua bridge LOC and number of platform implementations.
- Time required to add a new UI screen.
- Time required to add a native file picker.
- Keyboard navigation and screen-reader behavior.
- Plugin crash recovery.
- Debugger and profiler quality.

## Weighted scorecard

| Criterion | Weight |
|---|---:|
| One shared desktop/mobile UI | 20 |
| Lua/plugin implementation | 20 |
| Desktop modifiability | 15 |
| Android/iOS integration | 15 |
| Database/data integrity | 10 |
| Accessibility/input quality | 10 |
| Build/release maintainability | 5 |
| FOSS dependency quality | 5 |

Score each from 1–5 and retain written evidence. Do not score based only on preference for language syntax.

## Blocking criteria

Do not treat the architecture as production-ready if any of these remain unresolved:

- Cannot build and run all three primary targets from one repository.
- Desktop card rendering has no maintainable path.
- Lua cannot run the same plugin on all community targets.
- Review answer cannot be committed atomically.
- Platform-specific UI dominates the vertical slice.
- Required dependencies conflict with the intended permissive/F-Droid distribution.

## Out of scope

- Full sync implementation.
- APKG compatibility.
- Complete editor.
- Plugin registry.
- JAR plugin loader.
- Production visual design.

## Evaluation output

Keep `EVALUATION.md` updated with raw measurements, screenshots referenced by path only, failures, workarounds, and major architectural conclusions.
