# Kelma Review design

The integrated FSRS-6 library, explicit local optimization, profile sync, and
remaining FSRS-5 profile migration are specified in [FSRS_DESIGN.md](FSRS_DESIGN.md).

## Status

Candidate architecture for prototyping. No production commitment has been made.

## Goals

- One repository and mostly shared implementation for Windows, macOS, Linux, Android, and iOS.
- Clean room Apache-2.0 application with no Anki/rslib code.
- Kelma-native collection schema and KelmaSync v2.
- FSRS as the built-in scheduler.
- Powerful third-party Lua plugins.
- Optional compiled JAR plugins on desktop.
- Full plugin community build for desktop, Android, and sideloaded iOS.
- Plugin-free iOS App Store build.
- F-Droid-compatible Android build without proprietary dependencies.

## Non-goals

- Opening or modifying Anki's live SQLite collection.
- Implementing the AnkiWeb protocol.
- Running Anki Desktop Python add-ons.
- Replacing the Kelma Immersion Vue website.
- Reproducing every Anki preference or historical behavior.

Live-client interoperability remains through KelmaSync and the separately distributed Anki Desktop add-on. File interchange is also supported through a clean room Anki package/text codec; it never opens or modifies a live Anki collection.

## Proposed repository

```text
kelma-review/
  app/
    src/commonMain/
    src/androidMain/
    src/iosMain/
    src/desktopMain/
  core/
  database/
  scheduling/
  sync/
  plugin-api/
  plugin-host/
  ui/
  native/lua/
  iosApp/
  docs/
```

## Dependency direction

```text
app -> ui -> core
app -> platform implementations
sync -> core
scheduling -> core
plugin-host -> plugin-api -> core public models
database -> core repository interfaces
```

`core`, `scheduling`, and `plugin-api` must not depend on Compose or platform APIs.

## Shared code

`commonMain` should contain:

- Notes, cards, decks, notetypes, review events, and sync metadata.
- Collection services and transaction boundaries.
- FSRS and queue policy.
- KelmaSync request/response models.
- Plugin API contracts and Lua-facing value models.
- Navigation state and most Compose screens.
- SQLDelight schema and queries.

Top-level collection navigation is a single `CollectionDestination` value (`Decks`, `Add`, or `Browse`), never a set of independent visibility flags. Every toolbar action replaces the current destination; Decks also clears any selected study deck. This prevents Add or Browse from remaining mounted underneath another screen and reappearing during later navigation. Collection-wide Deck projections and paged Browse queries run on a worker dispatcher, so changing destinations never groups, sorts, searches, or renders every card on the UI thread.

## Platform code

Use small `expect`/`actual` boundaries for:

- SQLite drivers.
- Files and document pickers.
- Secure credential storage.
- Notifications and background work.
- WebView/card rendering.
- Audio playback and recording.
- Lua runtime bindings.
- Desktop windows, menus, tray, and processes.

## Collection model

Kelma owns its schema. Stable identities are independent of local SQLite row IDs.

```text
Note: stable GUID, notetype, fields, tags, modified time
Card: note GUID + template ordinal and content placement
ReviewEvent: globally unique timestamp ID, portable card identity, rating, duration, origin metadata
Deck: stable ID/name and content metadata
LocalStudyProfile: scheduler implementation/version, limits, parameters, and display policy
Notetype: fields and card templates
Media: logical filename, checksum, local state
Tombstone: stable entity identity and deletion time
```

Portable plugins are validated `.kelmaplugin` ZIP packages persisted in application-owned SQLite. Standard Lua 5.4.8 runs in one state per plugin through JNI on desktop/Android and C interop on community iOS. The host removes filesystem/process/debug standard libraries and dynamic loaders, replaces `require()` with package/dependency runtime paths, gates registered services by declared capability, and enforces allocator and instruction budgets. These controls are defense in depth for trusted third-party software, not an OS sandbox. The Options plugin manager confirms requested capabilities and provides enable/disable, reload, safe mode, attributed diagnostics, command inspection, and uninstall.

Review events are the durable synchronized scheduling facts. Due times, intervals, learning phases, FSRS memory state, and queue ordering are device-local derived projections and are never authoritative sync records. Daily answer totals remain derived from immutable events, while synchronized per-deck study-day counters summarize how many New and Review cards consumed daily quota; learning and relearning repetitions do not consume Review quota. A card's independent `active`/`suspended` study state is synchronized user intent, not a scheduler phase. Source clients may include interval/factor/card-scheduling fields for Anki round-tripping, but Kelma treats them as opaque origin metadata except that an explicit Anki New-card `due` value is retained solely as its New gather position; it never seeds a phase, due time, or Review schedule.

## Database

Use SQLDelight with hand-reviewed migrations. The schema persists KelmaSync credentials, the sync cursor, every downloaded record class, remote-media filename/version/size metadata, locally created decks, device-local per-deck study profiles, locally authored notes/cards, downloaded-note field/tag overrides, device-local card flags and current-study-day card/note buries, synchronized manual due-date overrides and card-reset projection cutoffs, local schedule projections, and pending review, note, card-state, and deck sync outboxes. Local review rows store note GUID plus template ordinal so they survive collection-local card-ID changes, and persist whether an answer admitted a card from the Review queue so learning/relearning repetitions cannot consume daily Review quota after reload. An initial pull atomically installs the downloaded snapshot and rebuilds schedules from confirmed review history plus unconfirmed local events. Incremental pulls transactionally write only changed or removed rows and replay only affected card identities; account policy/profile changes retain an explicit full-rebuild path. Each rating atomically appends its event and advances the local projection immediately. Local review rows keep sync ordering (`review_id`) separate from wall-clock study time (`reviewed_at_ms`): collision avoidance may advance the former, but FSRS due times, learning steps, projection replay, and optimizer history must use the latter. Confirmed reviews originating on this device retain their local wall-clock time during replay. When the answer is revealed, the four rating controls run non-mutating scheduler projections from the same card state and timestamp, then display their resulting due intervals; previews never append history or change the queue. Reset Card stores a synchronized, monotonic review-history cutoff and rebuilds the card as New without deleting immutable review facts; every client and the server FSRS projection replay only reviews after that cutoff. Set Due Date stores an independently synchronized UTC civil-date override without changing FSRS memory state; projection rebuilds preserve it, newest-write-wins reconciliation keeps clients consistent, and the next review or Reset Card synchronizes its removal. Review note actions persist same-day sibling burying, synchronize Mark/Unmark through the canonical `marked` tag, create independent local copies through the normal note outbox, and hide confirmed deletions immediately while their tombstones synchronize. Undo from either the reviewer or Decks asks for confirmation by default, then removes the pending event and replays the remaining history so concurrent pulled reviews are retained; a device-local per-deck option controls both entry points and may disable that prompt. Startup, option changes, and review-conflict resolution also rebuild projections. Each account uses a separate SQLDelight database and filesystem media-cache namespace selected by a small platform account registry. Sign-in restores that account's retained collection (or atomically installs its initial snapshot). Switching accounts keeps each bearer token in the platform credential vault, opens an empty guest database for the chooser, and never deletes retained collection data. Choosing a saved account reopens its isolated database and vault-backed session without requesting the password; a missing token falls back to one explicit sign-in. As soon as a new login returns a token, the account manager leaves the credential form, opens Sync, and streams the initial pull/upload log instead of blocking behind the sign-in spinner. Mobile and desktop expose this behavior through Switch account. The Sync page also provides a confirmed Redownload collection action that preserves credentials and pending local work, clears only downloaded rows, cursor, schedules, and media cache, then immediately starts a complete sync. Passwords are never stored. Plugins do not receive the application SQLite connection; they use collection APIs and may create plugin-owned databases.

## Scheduling

The current prototype uses the separate Apache-2.0 `kelma-fsrs-v6` Kotlin Multiplatform library with the same 705-case Python parity contract as KelmaSync. Customized 19-parameter FSRS-5 profiles continue through a labeled legacy engine and are never relabeled as FSRS-6, as specified in [FSRS_DESIGN.md](FSRS_DESIGN.md). New, learning, review, and relearning queues continue to use device-local absolute due times cached in SQLite. Pulled reviews are matched by note GUID plus template ordinal, ordered by review timestamp and immutable ID, and replayed with the active local profile; origin intervals, factors, due dates, and memory state remain ignored. A synchronized study-day counter matching the active policy supplies remote New/Review quota consumption when present; immutable review kinds and local pre-review phases provide the deterministic fallback. Optimization is an explicit local Optimize/Apply workflow and never runs automatically.

An active reviewer owns an immutable dynamic session rather than a fixed card list. It keeps the current card, the daily-limit-bounded regular queue, and an absolute-time Learning/Relearning queue as separate values. Committing a rating replaces any timed entry for that card by ID. Next-card selection chooses due timed learning before regular cards, then allows learn-ahead only after the regular queue is empty; it never interrupts a displayed card. Steps crossing the account's synchronized, DST-aware study-day boundary remain in the persisted projection for a later session. Deck projection refreshes may update queued card content and introduce newly visible intraday learning, but never append ordinary cards or own repeats created by the active session.

Custom scheduler plugins persist their implementation ID, version, and opaque projection state locally. Other clients need only exchange review events, so an Anki or custom scheduler can interpret the same history independently instead of forcing its state onto Kelma. The separate server FSRS-6 projection exists only for compatibility and never overrides this local projection.

## Sync

KelmaSync v2 is the only cloud protocol implemented by the app. It never contacts AnkiWeb.

The sync client authenticates through `/v2/auth/login`, exchanges the change manifest, and pulls content, immutable review history, notetypes, deck records, media bytes, and tombstones. Existing card scheduling payloads remain opaque protocol compatibility data and never enter local phase, due-time, Review-queue, or Browse-state decisions; the sole narrow exception is an explicit source New-card `due` value, which supplies only its deterministic New gather position. Portable daily counters affect only New/Review quota consumption for their matching study day; they never seed card schedules, phases, due times, Browse state, totals, or streaks. Uploads include review events, independently versioned card study-state, manual due-date, and schedule-reset changes, authored notes/cards and dependencies, downloaded-note edits, and deck rename/delete plans. Reviews do not trigger card-scheduling or daily-counter writes, and Kelma-native study Options are never inserted into synchronized deck configuration. Explicit Anki `newLimit` and `reviewLimit` values already present in an opaque synchronized deck config act only as daily-limit fallbacks until a local option or preset overrides that deck; other foreign deck settings and legacy `kelma_options` payloads remain ignored. Full-card content operations send an empty scheduling object for authored cards or preserve an existing source payload during content-only moves because the current server schema requires the field—they never serialize Kelma's local projection. Local writes enter SQLite outboxes in the same transaction as their visible change. Sync first completes a preflight pull, atomically snapshots an upload plan against that current server state, sends content through bounded batch requests, uses temporary authenticated TARs of at most 128 MiB for initial or other mass media pulls while keeping loose objects canonical, prepares up to five TARs in the background, immediately streams whichever preparation finishes first while keeping exactly one TAR GET active and reporting bytes as they arrive, and relies on KelmaSync to delete a claimed TAR when its response completes or disconnects (with client acknowledgement as best effort), and otherwise downloads ordinary incremental media through exactly 64 concurrent authenticated KelmaSync proxy requests; both paths use longer timeouts plus retry/backoff and atomically stage size-verified files in the account cache so an interrupted initial pull resumes without retransferring them, retains only filename/version/size references in the in-memory collection so heap use is bounded rather than retaining the full multi-gigabyte pull, and hydrates only the audio/images referenced by a bounded current-and-next review-card window from the account cache, reports transferred GiB against the manifest's total bytes, marks only server-confirmed resources uploaded, and performs a confirming pull from the new `server_time`; confirmation promotes authored rows, clears deck overlays, and updates only affected local projections. Note/deck dependencies are deduplicated before upload, so a large imported deck does not repeatedly send the same dependency. No-change pulls advance only the durable cursor, and content-only pulls preserve unchanged media cache files and SQLite metadata instead of rewriting payloads. Failed HTTP calls leave pending rows untouched and partially accepted batches remain idempotent on retry. Each cycle also appends a bounded persistent activity log containing phase timings, preflight results, aggregate outbox counts, destructive deck/card/note summaries, single-row live resource progress, acknowledgements, confirming pull, conflicts, and failures without credentials, storage URLs, note GUIDs, card IDs, or card contents. The Sync tab displays these records across restarts. Note/deck `409` responses are persisted and shown in an explicit Keep this device / Use KelmaSync dialog; force override occurs only after that choice. Review-ID collisions can only accept the immutable server row.

Authentication metadata and downloaded records restore from SQLite at startup. Bearer tokens are stored separately through Android Keystore-encrypted app storage, iOS Keychain, macOS Keychain, Windows PasswordVault, or Linux Secret Service. Migration 14 rebuilds `sync_auth` without its legacy token column; because a SQL migration cannot write an operating-system vault, upgrading users may need to sign in once. Downloaded remote-media payloads live in the atomic filesystem cache namespaced by account database; SQLite retains only filename/version/size metadata, and startup migrates legacy SQLite media BLOBs into that cache one file at a time before clearing each BLOB. Locally authored media remains durable in its local outbox table until confirmed. Native desktop, Android, and iOS pickers attach image/audio to Add or Browse/Edit; a separate media outbox uploads bytes with idempotent PUT and reconciles only after a confirming pull. If server metadata points to a missing blob and the filesystem cache still has downloaded bytes, preflight records the miss and transactionally requeues that copy for repair. Later phases may add richer conflict previews/merging.

## Creating decks and adding cards

The desktop deck-list utility row exposes Create Deck rather than duplicating the toolbar's Add action. Its focused dialog validates and normalizes names, supports `Parent::Child` hierarchy syntax, rejects case-insensitive duplicates across downloaded and local decks, and persists every hierarchy level in a separate local table. Empty decks survive restarts and sync cycles, appear immediately in the deck list and Add selector, and clear with other account-scoped local content. Each deck row has a persistent Kelma-styled gear menu. Add cards opens Add with that deck selected, and Browse cards opens Browse with a quoted deck filter. The desktop menu always enables Rename, Options, Export, and Delete entries plus Add cards and Browse cards. Local trees are mutated transactionally. Downloaded and mixed trees use account-scoped persistent overlays: Rename remaps the server tree throughout the displayed collection, while Delete hides it and removes local schedule projections without mutating authoritative rows before server acknowledgement. Pending immutable reviews are preserved and uploaded before the content deletion. Corresponding rename/delete plans upload cards, deck records, orphaned notes, and tombstones; overlays survive failures and clear after a confirming pull or explicit account-data deletion; ordinary sign-out preserves them in that account's isolated collection. Import and Export use native document pickers on desktop, Android, and iOS. The default version-2 Kelma JSON format round-trips immutable review history, reusable presets and assignments, empty decks, note types, and media; version-1 deck JSON remains importable but its mutable schedule projections are deliberately ignored. Export also supports Anki-compatible `.apkg` deck packages, `.colpkg` collection packages, self-describing UTF-8 note text, and rendered-card text. Package controls independently include immutable scheduling history, reusable deck presets, and referenced media, and can emit either the current Zstandard/Protobuf container or the larger legacy-compatible container. Import reads both schema-11 and normalized schema-18 Anki databases, validates archive paths, size limits, media sizes and SHA-1 values, and maps arbitrary note types/templates without using Anki implementation code. Matching GUID/content is idempotent; a mismatched GUID is retained as an explicit imported copy instead of overwriting local content. Foreign due dates and memory state remain untrusted: only imported immutable review rows are replayed through the active local scheduler. Rendered-card text import is necessarily lossy and creates Basic notes.

Desktop Options is a plain-text top-level tab between Browse and Stats rather than an Anki-style modal. Each deck gear menu retains Rename, Export, Add cards, Browse cards, and Delete, and adds Options as a direct route to the page with that deck selected. A deck selector edits device-local profiles for downloaded or authored decks. New/review queue limits, learning/relearning delays, autoplay, review-duration caps, current FSRS-6 desired retention and all 21 parameters, maximum interval, new-card gather/sort order, new/review mixing, interday-learning placement, and review sorting are functional and persist across restarts and pull replacement without uploading. Until locally overridden, New/day and Reviews/day inherit explicit `newLimit` and `reviewLimit` values from synchronized Anki deck configuration. Parent rows aggregate their complete descendant subtree, and every card is constrained by both its own deck limits and the remaining shared limits of each ancestor. Review cards receive capacity before New cards; by default New cards also consume Review capacity, while learning and relearning repetitions consume neither daily queue. A separate versioned KelmaSync study-day policy synchronizes one IANA timezone and local rollover hour for Review, Immersion, and future clients; Options can update it, and version 0 is initialized from the first Review device's system timezone with a 04:00 rollover. FSRS-6 introduces 21 versioned parameters, explicit local optimization, and a separate user-approved account-profile upload; arbitrary 19-parameter custom profiles are preserved rather than silently relabeled. Scheduler-affecting edits replay review history immediately. Queue ordering is a separate deterministic policy layer and does not alter FSRS mathematics. FSRS chooses day intervals, while their wall-clock due times are anchored to the synchronized study-day rollover using timezone/DST rules; intraday Learning/Relearning steps retain exact elapsed delays. Random orders use a stable per-study-day key so a device remains consistent within a session while still rotating on later days. Intraday learning remains first; interday learning and new cards are then placed before, after, or proportionally among reviews. Stats derives review totals, time, recall, streaks, a 30-day chart, due state, and maturity from immutable review rows plus local projections; it never reads card contents. Unsupported desktop option groups remain visibly disabled instead of pretending to save; leeches, advanced timers, easy days, evaluation/simulation, history cutoff, and custom scheduling remain later implementation slices. Sibling burying and reusable local presets are functional. Preset edits propagate transactionally to assigned decks, while deleting a preset materializes its last options per deck so scheduling behavior does not change.

Add is deliberately platform-specific rather than a resized copy of one screen. Desktop uses a wide note editor with Type and Deck selectors, a formatting toolbar (bold, italic, underline, super/subscript, text color, highlight, remove formatting, lists, alignment and math), Fields and Card-template views, per-field pin and preview toggles, tags, and a Help/Add/History/Close footer, plus a Ctrl or Cmd + Enter shortcut. Mobile uses a stacked, touch-first version of the same editor with a compact toolbar and a persistent bottom action. Both build the same note draft — including the note type's generated cards — and write it transactionally to local content tables. The toolbar applies standard inline HTML through shared, unit-tested transforms, and the reviewer renders that formatting. Added cards are immediately studyable and survive restart and upload through the transactional note outbox.

## Browsing cards

Browse is a platform-specific card browser over the merged collection (downloaded plus locally authored cards). Migration 29 adds a durable derived SQL index of card IDs, normalized note fields, deck, note type, and tags. Content mutations mark that index dirty transactionally; rebuilding it does not render card templates, and a clean index survives restart. Search, dynamic schedule-state joins, facets, sorting, and total counts run in SQLite on a worker dispatcher and return 50-row pages. Only each loaded page and the selected preview render complete card templates, keeping heap and first-frame work bounded. Query changes are cancellable and briefly debounced instead of blocking navigation or typing. New/learning/review/due state comes from the local schedule projection, while suspended state comes from independently synchronized card study state; opaque scheduling fields downloaded from another client are ignored. A shared query language supports plain-text terms combined with AND plus `deck:`, `tag:`, `note:`, and `is:new|learning|review|suspended|due|local` qualifiers (quote values containing spaces, e.g. `deck:"French verbs"`); its parser is pure and SQL matching has deterministic parity tests. Sidebar and chip filters toggle: clicking an active filter removes it, and contradictory terms replace each other within a group (card states, decks, note types) while tags and `is:due`/`is:local` combine freely. Desktop presents a filter sidebar (states, decks with counts, top tags), a sortable results table, and a deliberately wide detail panel with a fully rendered card preview (styled text, images, and playable audio), metadata, and actions. Mobile uses a search field with filter chips, a card list, and an in-place detail view. Any row can jump to its deck's study screen. Edit sits at the top of the detail pane; activating it replaces the preview surface itself with source-text controls for every ordered field and the tags, including raw inline HTML, rather than opening a modal. Cancel and Save share the `EDIT NOTE` header row above the bordered field surface, so field actions remain visible before scrolling. Locally authored notes update in place; edits to downloaded notes are stored in a separate override table and overlaid after each pull until their optimistic-checksum upload is acknowledged. Locally authored notes can also be deleted, removing their cards, schedules, and pending events transactionally. Browse entry points live in the desktop toolbar and the mobile deck toolbar.

## Card rendering

The shared renderer evaluates downloaded notetype `qfmt`/`afmt` templates, field conditionals, reverse layouts, `FrontSide`, and cloze filters. Production review gives Android WebView, iOS WKWebView, or desktop JavaFX WebView/WebKit the complete bounded card viewport. Deck-list projection builds exact queue counts without rendering review-card bodies; only the selected deck hydrates its review queue. A worker-owned cache partitions projection-relevant card metadata by deck and retains each exact summary. Review deltas identify affected cards, notes, and decks directly; content and sync snapshots compare per-deck source keys, while unfamiliar review snapshots use an exact schedule/bury/daily-state diff. Only affected decks run the canonical queue builder, with due, learn-ahead, dynamic-sort, and study-day boundaries invalidating cached counts before they can become stale. A short idle refresh keeps the cache warm after edits and sync while another destination is visible. The reviewer retains one browser shell for the session and replaces its complete card body and stylesheet in place. Before reveal it displays the rendered `qfmt`; after reveal it installs the complete rendered `afmt` and scrolls to `#answer`, so `FrontSide` is neither appended nor duplicated. The document uses `<body class="card">`, embeds local images, bridges fixed-size sound controls to native audio, and owns vertical scrolling. An explicitly assigned Lua renderer may transform the complete question and answer HTML/CSS before media embedding. The nearest deck or parent-deck assignment overrides note-type assignments; bounded output is cached for unchanged active-queue cards, and any unavailable or failed renderer falls back to the original card without transferring WebView ownership. Mobile surfaces report ordinary tap position to Compose for reveal and Again/Good shortcuts; the desktop browser and its WebView are non-focusable so Compose review keys remain authoritative. Answer autoplay still excludes repeated `FrontSide` media, and advancing or backgrounding stops audio. Native players handle MP3 and other platform-supported formats, including pause and bounded five-second seeking. Record Own Voice requests platform microphone permission and keeps one temporary review-session clip for comparison without persisting or synchronizing it. Auto Advance is session-local and, while enabled, reveals after three seconds and submits Good five seconds later.

## UI

Compose Multiplatform owns application chrome and built-in screens. The current plugin manager has platform-appropriate desktop/mobile shells and displays trust guidance, capabilities, dependencies, runtime status, startup time, registered commands/events/renderers, durable renderer assignments, and durable logs. A global searchable command palette opens with Cmd/Ctrl+K on desktop; mobile keeps review undo directly in the top bar and exposes plugin commands from Plugin Manager instead of opening a command-palette modal. Built-in navigation and sync actions share the same registry as Lua commands, while plugins receive only content-free screen/deck context. Plugins currently register commands, lifecycle/review observers, and pure HTML/CSS renderer transforms. Rich portable host UI trees plus canvas and WebView escape hatches remain a later additive API. Direct Compose UI from JAR plugins is desktop-only and version-sensitive.

## Builds

```text
Desktop community: Lua enabled; optional JAR plugins
Android community/F-Droid: Lua enabled
Android store: decide after store-policy review
iOS App Store: external Lua plugins excluded at compile time
iOS community: Lua enabled, separate bundle ID, user-signed
```

Suggested IDs:

```text
App Store/stable: tech.kelma.app
Community:        tech.kelma.app.community
```

## Licensing controls

- Apache-2.0 project and plugin SDK.
- SPDX headers/metadata.
- Dependency lockfiles.
- Automated license allowlist in CI.
- Generated third-party notices.
- No implementation copied from AGPL Kelma/ForkiCards repositories.
- Protocol schemas intended for both Apache-2.0 clients and the AGPL Anki bridge must live in a separately permissive module.

## Main risks

1. Compose Multiplatform iOS maturity for Kelma's actual UI.
2. Reliable desktop HTML/card rendering.
3. JNI plus Kotlin/Native Lua integration complexity.
4. Plugin API becoming an unstable reflection of internal models.
5. Direct Compose JAR plugins coupling to Kotlin/Compose versions.
6. Mobile lifecycle restrictions for long-running Lua plugins.
7. iOS policy for the separately distributed community build.
