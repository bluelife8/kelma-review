# Kotlin prototype evaluation

Status: **selected**, started and decided 2026-07-25.

## Baseline completed

- Generated from the official Kotlin Multiplatform wizard.
- Targets Android, iOS, and JVM desktop from one repository.
- Replaced the template screen with a shared Kelma review interaction.
- Added common review-session domain tests.
- Verified JVM tests and desktop compilation.
- Verified Android debug assembly.
- Verified iOS simulator compilation, tests, full Xcode application build, and a clean install/launch/terminate cycle on a freshly created iOS 26.4 simulator.
- Added an isolated end-to-end sync/review/restart/resync/Undo/sign-out acceptance workflow and shared Compose reviewer interaction tests.
- Added an offline note editor with note-type selection, a full formatting toolbar, tags, and per-field controls, transactionally persisted and presented distinctly on desktop and mobile.
- Added a card browser with an Anki-style query language (`deck:`, `tag:`, `note:`, `is:` qualifiers), sortable desktop table with filter sidebar and detail panel, mobile filter chips with in-place detail, source-field editing for every note (downloaded edits persist as local overlays), and deletion for locally authored notes.

## Baseline environment

- Kotlin 2.4.10
- Compose Multiplatform 1.11.1
- Gradle 9.1
- Java 21 toolchain
- Android SDK 36
- Xcode 26.4

## Initial observations

- First JVM/Android build completed in about 2 minutes after downloading Gradle and dependencies.
- First iOS test build completed in about 1.5 minutes while downloading Kotlin/Native components.
- The generated project has separate thin Android, desktop, and Xcode launchers around a shared Compose module.
- The iOS build currently warns that one bundled object has an 18.5 minimum while the generated app targets 18.2; this needs cleanup before release testing.
- The versioned plugin API now executes embedded standard Lua 5.4.8 through JNI on JVM/Android and C interop on iOS, with validated packages, dependency runtime paths, capability confirmation, runtime limits, safe mode, diagnostics, a shared command palette, durable renderer assignments, and a Compose manager. SQLDelight persistence/migration and the independent `kelma-fsrs-v6` scheduling slice work across targets. The library passes the shared 705-case Python fixture on JVM, Android host, macOS Native, and iOS Simulator Native; legacy custom FSRS-5 profiles retain their pinned `ts-fsrs` coverage.

## Decision

Kotlin Multiplatform with Compose is the selected application stack. The Flutter comparison is discontinued. The decision reflects the preferred feel of the working mobile and desktop UI, concise shared production code, strong common-domain typing, and acceptable Android/iOS/desktop build results.

This selection does not erase the remaining technical risks: production migrations, future FSRS version upgrades, expanded transactional/network plugin services, native plugin release signing, and release performance still require validation. They are implementation milestones now, not framework-selection gates.

## Pending implementation

- [x] SQLDelight credential and complete-download transaction
- [x] Transactional local review state, due queues, and Undo
- [x] Independent MIT `kelma-fsrs-v6` Kotlin Multiplatform scheduler
- [x] Shared 705-case Python FSRS-6 parity fixture on JVM and Native
- [x] Preserved MIT `ts-fsrs` coverage for labeled custom FSRS-5 profiles
- [x] Standard Lua 5.4 bridge on desktop, Android, and community iOS
- [x] Lua commands, events, pure renderers, packages, safe mode, and manager
- Lua-owned localhost API through generic network/worker services
- [x] Desktop JavaFX WebView card renderer
- Release-size/startup/memory measurements
