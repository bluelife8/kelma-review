# Kotlin FSRS-6 and local optimization design

Status: scheduler, cloud-profile sync, durable local optimization, and candidate Apply/Discard integrated

This document defines the Kotlin-client side of the Kelma FSRS-6 architecture.
The corresponding server contract is in
`kelma_sync_2/FSRS_DESIGN.md`.

## Goals

- Use the independent Apache-2.0 `kelma-fsrs-v6` Kotlin Multiplatform library instead
  of application-owned scheduling equations.
- Keep review history authoritative and scheduling projections device-local.
- Match the pinned Python reference and the KelmaSync Go compatibility
  scheduler for identical inputs.
- Let users explicitly optimize on their own device without a production
  server optimizer.
- Never change active parameters or retention merely because optimization was
  started.
- Upload an optimized or manual account profile only after explicit user
  action, with durable conflict-safe sync.

## Non-goals

- Accepting server due dates as local scheduling truth.
- Automatically or periodically running optimization.
- Running optimization after every sync or review.
- Requiring Python, Go, PyTorch, CUDA, or a network connection to schedule
  cards locally.
- Synchronizing queue ordering, burying, or local learning steps as
  account-wide truth. Per-deck New/day and Reviews/day limits synchronize as
  deck metadata instead.
- Defining scheduling policy for the maintained Anki plugin or ForkiCards. The
  Anki plugin remains supported for Anki users; ForkiCards may later choose
  local rslib or server scheduling independently.

## Library boundary

`kelma-fsrs-v6` is a separate Apache-2.0 Kotlin Multiplatform library. The application
uses its public models rather than retaining FSRS equations in application UI
or persistence code.

The library is organized around:

```text
Scheduler configuration
Card memory/scheduling state
Review event
Review/preview/replay operations
Retrievability and interval calculations
Parameter validation
Optimizer input, result, progress, and cancellation
```

Scheduling and optimizer mathematics live in `commonMain` and have no Compose,
SQLDelight, Ktor, Android, or Apple-framework dependency. Platform applications
provide persistence, clocks, dispatchers, lifecycle handling, and UI.

The library targets JVM, Android, iOS, macOS, and Linux. Its scheduler and
optimizer are implemented independently in `commonMain`. The app exposes
Optimize only through the durable, explicit candidate workflow described here.

## Independent validation

Python is a development oracle only:

- `py-fsrs==6.3.1` generates scheduler trajectories.
- Pinned `fsrs-optimizer==6.5.0` generates committed optimizer inputs,
  initialized/optimized parameters, training/validation losses, eligibility
  failures, bounds, determinism, and cancellation-contract fixtures.
- Fixture generators run in pinned containers.
- Generated JSON fixtures record package versions, source commits, container
  digest, configuration, and SHA-256.
- Committed fixtures run without Python or network access in Kotlin CI.
- Private read-only acceptance over 127,011 active-card reviews compares Kotlin
  and the pinned Python optimizer without committing source history. Dataset
  hashes, rounded candidates, split counts, and six-decimal losses match; scalar
  initialization remains within the documented minimizer tolerance.

The same scheduling fixtures validate the internal KelmaSync Go scheduler.
Kotlin and Go therefore share a behavioral contract rather than validating only
against one another.

Fixtures cover all ratings, first reviews, learning/relearning, same-day and
fractional-day reviews, UTC boundaries, rounding edges, custom parameters,
custom retention, maximum intervals, empty steps, out-of-order replay, duplicate
IDs, and long trajectories. Optimizer fixtures cover dataset formatting,
initialization, loss before/after training, parameter bounds, deterministic
runs, insufficient data, timezone/day-start behavior, and cancellation.

A package or algorithm upgrade creates a new fixture set and explicit migration.
Expected values are never edited merely to make a mismatch pass.

## Local scheduling authority

The local database stores immutable reviews and a derived schedule projection.
For each card, replay identity uses:

```text
note GUID + card template ordinal
```

Reviews are sorted by review timestamp and then immutable review ID. Confirmed
server reviews and pending local reviews both participate in local replay.
Downloaded `card.scheduling`, interval, factor, memory state, and daily counters
remain opaque interoperability data and never seed or override the Kotlin
projection.

A rating transaction:

1. appends a globally unique immutable review event;
2. writes the review outbox entry;
3. advances the local FSRS projection;
4. records Undo identity;
5. commits all changes atomically.

Pull, restart, Undo, conflict resolution, portable card-ID remapping, and every
scheduler-affecting local option change rebuild the projection from history.
Queue ordering remains a separate policy layer.

## Default FSRS-6 configuration

New local profiles use:

- parameters:
  `[0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542]`;
- desired retention: `0.90`;
- maximum interval: `36500` days;
- learning steps: `1m, 10m`;
- relearning steps: `10m`;
- short-term scheduling enabled;
- fuzzing disabled.

Local users may continue selecting learning/relearning steps, maximum interval,
and desired retention per deck. Desired retention is constrained to
`0.70..0.99`. The server compatibility profile is account-wide initially and is
not silently imported into these local per-deck settings.

## Profile sources and ownership

Parameter and retention ownership are independent:

```text
parameterSource: Default | ClientOptimized | Manual
retentionSource: Default | ClientOptimized | Manual
```

Editing any of the 21 parameters sets `parameterSource=Manual`. Editing desired
retention sets `retentionSource=Manual`. A manual retention does not disable
parameter optimization, and manual parameters do not prevent requesting an
optimized-retention recommendation.

Because optimization is never automatic, “disabled” means that no prior or
delayed result may overwrite the active manual value. The user must explicitly
press Optimize and later Apply to replace it.

The Options UI always identifies the active source, for example:

```text
Parameters: optimized on this device
Desired retention: manually set to 95%
Cloud profile: upload pending
```

Reset to defaults, Optimize, and Apply optimized settings are separate actions.

## Explicit optimization workflow

Optimization begins only when the user presses **Optimize**.

### Preconditions

The client freezes an immutable local snapshot from confirmed and pending
content-free review rows and records:

- account-scoped job identity;
- local optimizer history and dataset hashes;
- through-review ID;
- current parameter vector for candidate comparison;
- optimizer/library version;
- timezone and day-start policy used for dataset formatting.

Local Apply does not require sync. Apply & publish additionally computes the
KelmaSync checksum-history hash and requires every pending review to be
uploadable; otherwise the UI asks the user to sync first.

Reviews created after the snapshot do not mutate the running job. They remain
valid local history and may be included by a later optimization.

Initial product guidance requires at least 1,000 qualifying cross-day reviews
across at least 100 cards, including both recalled and forgotten outcomes. If
history is insufficient, the UI reports current and required counts. It does
not return defaults and call that optimization.

### Execution

Optimization runs as explicit cancellable work with progress reporting. It does
not hold a database transaction or block normal review persistence. Desktop is
the preferred environment for large histories. Mobile runs only while platform lifecycle guarantees permit and records process
death as an interrupted job with no candidate.

Partial checkpoints are not eligible profiles. Cancellation, process death,
math failure, or invalid output leaves the active local and cloud profiles
unchanged.

### Result review

A completed candidate displays:

- current and candidate 21 parameters;
- the unchanged independently owned desired retention;
- qualifying review/card counts;
- training and validation loss before/after;
- optimizer and algorithm version;
- history-through identity;
- warnings or insufficient outcome diversity.

Pressing Optimize alone never changes scheduling. The user may discard the
candidate or press **Apply optimized settings**. Apply changes parameter
ownership to `client_optimized` while preserving retention and its ownership.

### Applying locally

Apply validates the candidate again, writes a new local profile version, and
rebuilds affected local projections transactionally from immutable history.
Decks with explicit manual overrides remain manual unless the user chooses to
replace them. The initial optimizer targets the account/default local profile,
not separate per-deck models.

If signed out, local application succeeds without a cloud upload. If signed in,
the same transaction creates a durable scheduler-profile outbox entry when the
user elects to publish the result as the account cloud profile.

## Cloud profile sync

A profile upload contains no review content. It contains:

- FSRS and profile schema versions;
- exactly 21 parameters and source;
- desired retention and source;
- maximum interval and deterministic flags required by the cloud profile;
- optimizer/library version when applicable;
- canonical history hash and through-review ID when applicable;
- quality metrics;
- `baseProfileVersion` for optimistic ownership;
- stable idempotency ID.

KelmaSync validates bounds, provenance structure, history-prefix identity, and
base ownership. The upload reconciles only after acknowledgement and a
confirming pull, like other outbox writes.

The local profile does not roll back merely because cloud upload is offline or
conflicted. The UI shows local and cloud states separately.

### Profile conflicts

If another device changes the cloud profile after the local candidate's base,
KelmaSync returns `409`. The client persists the conflict and offers:

- Keep KelmaSync profile;
- Replace with this device's profile;
- Rerun optimization from current history/profile.

No timestamp silently picks a winner. A delayed optimization result cannot
overwrite a manual profile selected on another device without explicit force.
Accepting the server cloud profile does not silently replace per-deck local
study settings; applying it locally is a separate choice.

## Server projection observability

The client may display server compatibility projection health from KelmaSync:

```text
current | pending | running | stale/failed
```

It also displays applied and desired through-review/profile identities when
available. A failed projection means the server retained an older compatibility
schedule; it does not mean review history or the local Kotlin projection failed.

Kotlin review remains available when server reprojection is pending or failed.
Sync logs record profile upload, acknowledgement, conflict, and projection
status without card contents or credentials.

## FSRS-5 migration

Persisted profiles gain an explicit algorithm/version field and parameter
count. There is no mathematically valid automatic conversion of arbitrary 19
FSRS-5 parameters into 21 FSRS-6 parameters.

Migration behavior is:

- An untouched FSRS-5 default profile upgrades to the FSRS-6 defaults and
  replays history.
- A customized FSRS-5 profile is preserved as a labeled legacy profile and is
  not uploaded as FSRS-6.
- The user is offered **Upgrade to FSRS-6 defaults** or, when available,
  **Optimize FSRS-6 from my history**.
- The legacy profile remains available until the user explicitly migrates;
  removal requires a later announced migration policy.

Database migration preserves review history, pending events, Undo state, and the
old parameter payload. It never relabels 19 parameters as FSRS-6.

New installations create only FSRS-6 profiles and contain no sample decks.

## Persistence additions

The app has account-scoped records for local scheduler profile versions and
active assignment, legacy FSRS-5 preservation, the scheduler-profile outbox,
cloud snapshot/version, persisted conflicts, and cloud projection status.
Migration 13 persists optimizer jobs, progress, cancellation requests, compact
result provenance, and unapplied candidates. Review examples are assembled from
existing immutable rows and are never duplicated into permanent optimizer
storage.

Large optimizer datasets are assembled from existing review rows rather than
stored as a duplicate permanent history. Temporary data is deleted on success,
cancellation, sign-out, and account change. Tests use isolated temporary or
in-memory databases.

## UI placement

Desktop Options gains an FSRS-6 section with:

- active source/version;
- retention and 21 parameters;
- Optimize;
- Reset to defaults;
- candidate comparison and Apply/Discard;
- local/cloud status and profile conflict action.

Mobile Options provides the same behavior in touch-first sections. Optimize is
never hidden, but insufficient history disables execution with an explanation.
The optimization surface does not claim background execution unsupported by the
platform.

Optimize, cancellation, candidate review, Apply, Apply & publish, and Discard
are functional. Evaluate/simulator controls remain disabled until separately
implemented and validated.

## Security and privacy

Optimization reads local immutable review metadata: portable card identity,
rating, timestamp, and optional duration. It does not inspect card text,
templates, tags, media, passwords, or tokens.

Profile sync excludes note content and raw review history. Passwords remain
unstored. Tokens must move to platform secure storage as already planned.
Optimizer failures and sync logs exclude card contents and credentials.

## Licensing and provenance

The application and `kelma-fsrs-v6` are licensed under Apache License 2.0. The library documents the
formulas/reference behavior used, pinned oracle versions, fixture provenance,
and all copied or adapted permissive sources. It contains no Anki/rslib or AGPL
implementation code.

Python packages are fixture-generation tools, not application dependencies.
Their MIT and BSD-3-Clause notices are retained in generation documentation.

## Rollout

1. Create and license `kelma-fsrs-v6`.
2. Generate pinned Python scheduling fixtures.
3. Implement the Kotlin scheduler and make JVM/Native parity pass.
4. Add versioned profile persistence and FSRS-5 migration.
5. Replace application `FsrsScheduler` use with the library adapter.
6. Rebuild all migrated local projections and run existing acceptance tests.
7. Add cloud profile models/outbox/conflict UI after the KelmaSync API exists.
8. Implement optimizer dataset/loss behavior and validate against the official
   optimizer.
9. Add explicit Optimize/Apply UI only after cancellation, interruption, and
   persistence tests pass.
10. Run desktop, Android, iOS, and multi-device acceptance before enabling
    profile publication by default.

Steps 1–9 and private real-history optimizer acceptance are implemented.
Custom 19-parameter profiles remain explicitly FSRS-5. Physical-device and
multi-device release acceptance in step 10 remains.

## Acceptance criteria

- Kotlin scheduling matches pinned Python fixtures on JVM and Native.
- Go and Kotlin consume the same canonical history/configuration contract.
- Foreign scheduling payloads never change local queues.
- FSRS-5 custom parameters are preserved and never mislabeled as FSRS-6.
- Optimize never starts automatically.
- Optimize without Apply changes no active profile or schedule.
- Cancellation/interruption changes no active profile.
- Apply atomically changes the local profile and rebuilds projections.
- Profile uploads survive restart and reconcile only after confirming pull.
- Conflicting cloud profiles require explicit ownership resolution.
- Manual parameters/retention cannot be overwritten by delayed results.
- Local study continues while server compatibility projection is stale or
  failed.
