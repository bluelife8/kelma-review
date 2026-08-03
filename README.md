# Kelma Review

[Kelma Review](https://github.com/bluelife8/kelma-review) is a progressive, open source, community-driven spaced repitition flashcard application for Linux, Android, macOS, iOS, and Windows. It was developed to compete with [Anki](https://apps.ankiweb.net/) and to bring a serious, permissively licensed application to the ecosystem. Kelma Review is licensed under [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) and can be used in and with proprietary software.

Kelma Review is sponsored by [Kelma Tech LLC](https://kelma.tech/), which also develops [Kelma Immersion](https://kelma.tech/), a science-based language learning platform that was originally intended to be integrated with Anki. Kelma Review development depends on volunteer work and funds from Kelma Immersion profits. Unlike Anki, Kelma Review is completely free to use on any platform. It was developed to be a single free and open source application with opensource licensing that is compatible with [Google Play](https://play.google.com/store) and the [Apple App Store](https://www.apple.com/app-store/).

Kelma Review is designed to be able to use any sync server that uses the [KelmaSync](https://github.com/bluelife8/kelma-sync) protocol. KelmaSync is also fully free and opensource, unlike the [AnkiWeb](https://ankiweb.net/) protocol. It makes serious improvements over AnkiWeb, particularly when it comes to third party application support and rapid, change-based upload and download. The default sync server is the Kelma Immersion server which is free up to 5 GB, but anybody can run their own sync server and Kelma Review will always support collections that connect to difference sync servers. 

Kelma Review currently uses [FSRS-6](https://github.com/open-spaced-repetition/free-spaced-repetition-scheduler) as its default scheduling program. It also ships with a parameter optimizer. Kelma Tech LLC is committed to the development of improved spaced repititon algorithms, and the vision for Kelma Review is to be a first adopter and contributor to progress in this domain. FSRS-6 has been shown scientifically to have superior efficiency to Anki's default scheduling algorithm.

Kelma Review is a completely clean-room implementation of spaced repitition, written in [Kotlin](https://kotlinlang.org/). It achieves greater efficiency than Anki on desktop, using less RAM and CPU threads. Syncing is much faster, the default scheduling system is more efficient, and the GUI has been modernized with more features out of the box. As a progressive application, Kelma Review seeks to be the [Fedora](https://fedoraproject.org/) to Anki's [Ubuntu](https://ubuntu.com/): Kelma Review's philosophy is embrace frontier features that push the boundaries of spaced repition.

## What Kelma Review modernizes

### UI

Kelma Review replaces legacy, dialog-heavy flashcard workflows with a coherent Compose interface. Desktop gets wide deck, editor, browser, options, statistics, and reviewer workspaces with keyboard and pointer affordances; Android and iOS get touch-first navigation, reachable review controls, safe-area-aware layouts, and readable card surfaces. Both presentations share behavior and visual semantics without forcing a stretched phone UI onto desktop or a compressed desktop UI onto phones.

### Code

The application uses a shared Kotlin Multiplatform domain instead of separate desktop and mobile products. Compose remains declarative, SQLDelight provides reviewed and tested migrations, immutable state drives projections, and blocking database, rendering, plugin, and indexing work stays off the UI thread. Transactional services own collection writes, deterministic tests cover scheduler and sync behavior, and narrow platform adapters provide native storage, security, media, browser, and lifecycle capabilities. A versioned Lua plugin system offers cross-platform extension points without exposing the collection database.

### Sync

KelmaSync exchanges durable content and immutable review events rather than treating one device's mutable due-state projection as universal truth. Offline writes enter transactional outboxes, retries are idempotent, conflicts require explicit resolution, and a confirming pull reconciles acknowledged work. Account data is isolated, credentials use each platform's secure vault, and large media collections use resumable disk-backed, bounded-concurrency transfer instead of retaining the whole collection in memory.

### Scheduling

Kelma Review runs the Apache-2.0 `kelma-fsrs-v6` scheduler locally on every supported platform. Immutable review history is replayed into device-local schedule projections, so desktop and mobile can share facts without forcing one client's queue state onto every other client. FSRS optimization is explicit and reviewable, custom parameters are versioned, previewed answer intervals are non-mutating, and each completed rating transactionally stores both the review fact and its immediately usable local projection.

### One open-source desktop and mobile app

Anki's desktop, Android, and iOS experiences are maintained as separate applications with different codebases and licensing models. Kelma Review instead ships the desktop and mobile product from the same Apache-2.0-licensed repository. Shared scheduling, sync, persistence, import/export, rendering, and plugin contracts reduce platform drift, make mobile behavior auditable in the same open project as desktop, and let contributors fix a core behavior once while still designing the interface appropriately for each device.

The current implementation is the selected foundation for Kelma Review while production hardening and plugin services continue.

## Selected stack

- Kotlin Multiplatform 2.4
- Compose Multiplatform 1.11
- Ktor client for KelmaSync v2
- SQLDelight + SQLite persistence
- Independent Apache-2.0 `kelma-fsrs-v6` Kotlin Multiplatform scheduling and local optimization with JVM/Native oracle parity
- Embedded standard Lua 5.4.8 on desktop, Android, and community iOS
- Versioned `.kelmaplugin` packages, dependency-aware `require()`, capability confirmation, runtime limits, diagnostics, commands, events, renderers, and a cross-platform plugin manager
- Optional desktop JAR plugins remain a later escape hatch after the portable API stabilizes

## Run

The generated project uses a Java 21 toolchain. Native plugin builds also use the host C compiler, Android NDK 28.2/CMake 3.22.1, and Xcode's iOS toolchain.

```bash
# Shared and desktop tests
./gradlew :shared:jvmTest

# Desktop development runner (stages an isolated runtime before launch)
./scripts/run-desktop-dev.sh

# Direct Gradle task (do not rebuild other tasks while this process is open)
./gradlew :desktopApp:run

# Android emulator (builds, boots/reuses an AVD, installs, and launches)
./scripts/run-android-emulator.sh

# Build the Android APK without launching an emulator
./gradlew :androidApp:assembleDebug

# iOS simulator, entirely from the CLI
./scripts/run-ios-simulator.sh

# Or open the Xcode project
open iosApp/iosApp.xcodeproj
```

The app opens the locally persisted collection and offers KelmaSync sign-in from the deck toolbar when no account is stored. After sign-in it atomically stores content, immutable review history, notetypes, media, deck records, and tombstones. Card scheduling payloads and legacy daily counters may be retained as opaque interoperability data, but never control Kelma's queues. Ratings run through the local FSRS-6 library and transactionally persist both a derived schedule projection and an undoable review event. Pulled review events are matched by note GUID plus card ordinal and replayed through this device's scheduler; projections rebuild on pulls, restart, Undo, conflicts, and local option changes. KelmaSync uploads review events but never local due dates, FSRS memory state, or queue counters; only per-deck New/day and Reviews/day values synchronize from study Options as deck metadata. Account FSRS-6 profiles use a separate versioned API with a durable idempotent outbox, explicit local/cloud Apply actions, and conflict resolution after acknowledgement plus confirming pull. Local optimization is opt-in and durable: Optimize creates a reviewable candidate with progress and loss metrics, while Apply, Apply & publish, and Discard remain separate explicit actions. This allows Anki and custom-scheduler clients to share history without imposing their schedules on Kelma. Content writes still use account-scoped SQLite outboxes, retry idempotently, reconcile only after acknowledgement and a confirming pull, and hold note/deck conflicts for explicit resolution. Large uploads use bounded 500-record content batches, deduplicated dependencies, concurrent media transfers, and aggregate live progress rather than one request or log row per note/card. The desktop deck list has a real Create Deck action, and desktop and mobile deck lists have per-deck KelmaSync badges that show `+n` locally added cards, `~n` locally changed cards, or a green check only when no card changes remain unsynced; empty and nested decks persist across restart and pulls and are available in Add. Every deck can be renamed or deleted immediately through a persistent overlay; the queued mutation then updates KelmaSync and the overlay is removed only after a confirming pull. Export defaults to versioned native Kelma JSON and also supports Anki-compatible `.apkg` deck packages, `.colpkg` collection packages, self-describing note text, and rendered-card text on desktop, Android, and iOS. Native JSON round-trips immutable history, reusable presets, empty decks, note types, and media; import also accepts legacy v1 Kelma deck JSON plus the Anki package and text formats. Package export can include immutable scheduling history, reusable deck presets, and media, with optional legacy Anki packaging. Package import handles legacy and current Zstandard/Protobuf containers, merges matching GUIDs, retains conflicting notes as copies, validates media, and rebuilds local FSRS projections from imported review history rather than trusting foreign due-state snapshots. A functional Stats destination derives recall, study time, streaks, daily history, and card maturity exclusively from immutable review events and local projections. Options also opens the plugin manager, which installs validated `.kelmaplugin` ZIP packages only after capability confirmation, resolves dependencies, runs bounded standard Lua 5.4 states, attributes failures and logs, and supports disable, reload, safe mode, renderer assignment, and uninstall without exposing the collection SQLite connection. Built-in and Lua commands share a searchable Cmd/Ctrl+K desktop command palette; mobile exposes plugin command actions from Plugin Manager without a global palette modal. Pure Lua HTML/CSS renderers can be assigned by deck or note type and safely fall back to the original network-isolated card. A plain-text Options tab between Browse and Stats provides synchronized per-deck daily limits plus device-local learning/relearning steps, audio autoplay, answer-time caps, FSRS-6 retention/parameters, maximum intervals, sibling burying, shared option presets, new-card gather/sort order, new/review mixing, interday-learning placement, and review sorting. Remaining KelmaDesktop option groups are shown disabled until they have functional Kotlin implementations. Add works without signing in. It offers a note-type selector (Basic and Basic-and-reversed), an existing-or-new deck, a full formatting toolbar (bold, italic, underline, super/subscript, text color, highlight, remove formatting, lists, alignment, and math), Fields and Card-template views, per-field pin and preview toggles, tags, and a Help/Add/History/Close footer. New notes and their generated cards receive an exact immutable creation timestamp, appear immediately in decks, counts, and review, and are stored separately from downloaded data so a refresh never overwrites them. Add and Browse/Edit can attach images or audio through native pickers; attachments enter a durable media outbox, write through an atomic platform cache, upload idempotently, and clear only after a confirming pull. A remote blob missing behind existing metadata is requeued from the durable local copy instead of advancing the sync cursor or losing media. Browse searches the whole collection with an Anki-style query language (plain terms plus `deck:`, `tag:`, `note:`, `created:YYYY-MM-DD`, inclusive `created:start..end`, and `is:new|learning|review|suspended|due|local`), with Created sorting and metadata, a filter sidebar, and card detail panel on desktop and filter chips with an in-place detail view on mobile. Every note can be edited directly in the card pane: Edit sits at the top and replaces the rendered preview with source-text fields for every ordered field and the tags. Edits to downloaded notes persist as local overlays and are reapplied after pulls; locally authored notes can also be deleted.

The KelmaSync bearer token is stored outside SQLite in Android Keystore-encrypted storage, iOS Keychain, macOS Keychain, Windows PasswordVault, or Linux Secret Service; passwords are never stored. Migration 14 permanently removes legacy SQLite token bytes and may require a one-time sign-in after upgrading. Sync cursors, downloaded records, and media bytes remain in SQLite. Cards render downloaded notetype question/answer templates, including field selection, reverse templates, conditionals, cloze deletions, ordered images, and `[sound:...]` media. Review renders one complete question or answer document in a full-viewport browser surface: Android WebView, iOS WKWebView, or desktop JavaFX WebView/WebKit. Template CSS, inline JavaScript, embedded local images, native audio bridges, internal scrolling, and answer-anchor scrolling are preserved. Mobile surfaces bridge touch ratings, while the desktop browser is non-focusable so Compose review shortcuts remain authoritative. Audio playback and autoplay remain device-local.

## Documents

- [`docs/DESIGN.md`](docs/DESIGN.md)
- [`docs/FSRS_DESIGN.md`](docs/FSRS_DESIGN.md)
- [`docs/ANKI_INTERCHANGE.md`](docs/ANKI_INTERCHANGE.md)
- [`docs/PLUGIN_SYSTEM.md`](docs/PLUGIN_SYSTEM.md)
- [`docs/TESTING.md`](docs/TESTING.md)
- [`docs/RELEASE.md`](docs/RELEASE.md)
- [`docs/PROTOTYPE_PLAN.md`](docs/PROTOTYPE_PLAN.md)
- [`docs/EVALUATION.md`](docs/EVALUATION.md)
- [`docs/STYLE.md`](docs/STYLE.md)
- [`docs/VISUAL_PARITY.md`](docs/VISUAL_PARITY.md)

## License

The application is licensed under Apache License 2.0. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE). Dependencies remain subject to their own permissive licenses and are summarized in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
