# Kotlin style and quality guidelines

These rules apply to hand-written production and test code. Generated files, vendored code, Gradle wrappers, and platform project metadata are exempt.

## File and function size

- Keep source files under approximately **500 lines**. At 400 lines, consider extracting a cohesive type or feature; over 500 requires a documented reason in review.
- Prefer functions under 50 lines and composables under 100 lines. Split by responsibility rather than creating arbitrary helper fragments.
- Avoid generic dumping-ground files such as `Utils.kt`, `Helpers.kt`, or giant `Models.kt` files.
- Keep one principal public type per file when practical. Small private supporting types may remain beside it.

## Formatting and linting

- Use `ktlint` formatting and `detekt` static analysis in CI.
- Use four-space indentation, trailing commas in multiline declarations/calls, and a 120-character line limit.
- Avoid wildcard imports and hand-aligned whitespace.
- Treat compiler, detekt, and lint warnings as errors in CI. Suppress a rule only at the narrowest scope and explain why.
- Public plugin API declarations require KDoc and compatibility review.

## Kotlin safety

- Do not use `!!` in production code. Convert uncertainty into validation, a nullable result, or a meaningful error.
- Prefer immutable `val`, read-only collections, data classes, and sealed hierarchies.
- Do not expose mutable collections or internal persistence models from public APIs.
- Avoid unchecked casts, reflection, and `Any` outside serialization/plugin boundaries.
- Never swallow exceptions. Catch specific failures, preserve causes, and attach actionable context.
- Do not log credentials, sync tokens, note contents, or media bytes.

## Coroutines and concurrency

- Use structured concurrency; never use `GlobalScope`.
- Inject dispatchers and clocks into testable services.
- Do not call blocking filesystem, database, network, or plugin work on the UI thread.
- Avoid `runBlocking` in production application paths.
- Give every long-lived job an explicit owner and cancellation lifecycle.
- Keep SQLite mutations transactional and on the designated database dispatcher.

## Compose

- Keep composables declarative: no direct database, network, filesystem, or plugin-runtime calls during composition.
- Hoist state and pass immutable UI state plus event callbacks.
- Provide stable keys for dynamic lists and accessibility labels for interactive controls.
- Do not place business rules in composables or platform launchers.
- Extract a component when it has an independent purpose, state contract, or meaningful test—not merely to reduce line count.

## Multiplatform boundaries

- Put code in `commonMain` unless it genuinely requires a platform API.
- Keep `expect`/`actual` interfaces narrow and capability-oriented.
- Platform launchers configure dependencies; they do not implement domain behavior.
- Do not add a JVM-only dependency to shared code because it happens to work on Android/desktop.

## Visual consistency

- Follow `docs/VISUAL_PARITY.md`; use semantic theme tokens instead of one-off colors.
- **Platform-appropriate presentation is the default.** Desktop and mobile should generally use different layouts, navigation, control density, spacing, and interaction patterns whenever that better suits each platform. Do not ship an enlarged mobile screen on desktop or a compressed desktop screen on mobile merely to maximize shared UI code.
- Share domain state, behavior, content, accessibility meaning, and semantic color roles. Platform-specific composables may present that shared contract differently.
- Desktop should take advantage of wider canvases, keyboard shortcuts, precise pointer input, hover feedback, and persistent actions. Mobile should prioritize touch targets, simple vertical flow, safe areas, software keyboards, and controls reachable on a small screen.
- Select the presentation by application target (`isDesktopApp`), not by an arbitrary window-width breakpoint. Tablets remain in the mobile family, although layouts may adapt within that family.
- Preserve each Kelma Review platform family: desktop uses desktop chrome; Android and iOS use the touch-first mobile presentation. Visual similarity must not override native usability.

## Architecture

- Core domain modules must not depend on Compose, SQLDelight-generated records, or platform APIs.
- All collection writes go through explicit services and transactions.
- Built-in schedulers, renderers, importers, and sync providers use the same registries exposed to plugins.
- Public plugin DTOs are stable contracts and must not alias internal Kotlin classes.
- Avoid cyclic module dependencies and service locators hidden behind globals.

## Tests

- Test domain behavior in `commonTest` first.
- Every migration, sync conflict rule, scheduler transition, and plugin API change requires a regression test.
- Tests must be deterministic: inject time, randomness, filesystem roots, and network clients.
- Prefer behavior assertions over implementation details and snapshots of unstable UI structure.

## Dependency discipline

- Add dependencies only with a clear owner and cross-platform/license review.
- Pin versions and commit lock/checksum metadata where supported.
- Allow only permissive licenses approved for the Apache-2.0 application; generate third-party notices.
- Do not copy code from the AGPL ForkiCards or Anki repositories.
