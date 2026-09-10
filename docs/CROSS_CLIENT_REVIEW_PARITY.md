# Cross-client review parity

## Status and scope

This is a living planning note, not an implementation or production-promotion approval. It tracks behavioral and data convergence among:

- Kelma Review on desktop, Android, and iOS;
- the rolling Kelma web reviewer;
- KelmaSync v2 and the account mirror used for Anki interoperability.

Parity means that durable user intent and immutable review facts converge predictably. It does **not** mean identical chrome, identical local FSRS projections, or synchronizing temporary UI/session state. Native clients must remain useful offline, while the browser reviewer remains server-authoritative.

The flags/marked-note slice has merged to rolling through
[KelmaSync PR 19](https://github.com/jeretmccoy/kelma_sync_2/pull/19),
[Fastify PR 42](https://github.com/jeretmccoy/anki_ai_fastify/pull/42), and
[frontend PR 77](https://github.com/jeretmccoy/anki_ai_frontend/pull/77). The approved rolling upgrade applied
migration 017 exactly once, validated immutable receipt containment/grants, and deployed the Sync image, so
flag/mark operations are active on rolling. By current product decision, Kelma Review flags stay device-local;
cross-client flag convergence is not part of the active parity target.

Suspension remains staged. Recovery-only
[frontend PR 78](https://github.com/jeretmccoy/anki_ai_frontend/pull/78) remains the verified rollback image.
[KelmaSync PR 20](https://github.com/jeretmccoy/kelma_sync_2/pull/20), Fastify PR 43, and frontend PR 79 are now
deployed in order. Card/Note suspension is visible only through advertised capabilities and focused
confirmation; no real operation was submitted solely to verify rollout.

## Non-negotiable ownership boundaries

- Immutable review events remain the only review-history truth.
- Local FSRS phase, due time, memory state, and queue order remain derived client projections.
- The browser never supplies authoritative account, card, note, deck, or schedule identity.
- KelmaSync owns authenticated identity resolution, account isolation, serialized mutation, and operation receipts.
- Fastify only authenticates, validates a closed request schema, and forwards account-scoped requests.
- Native local writes and their durable outboxes must commit in one SQLite transaction.
- An administrative action must not append a review, consume New/Review quota, or create a browser `study_days` row.
- An ambiguous request keeps its original operation ID and exact frozen intent.
- A browser action must not make the current presentation unanswerable.
- Older clients must not erase metadata they do not understand.

## Current baseline

| Behavior | Kelma Review today | Rolling web work | Parity status |
| --- | --- | --- | --- |
| Rate a card | Appends a local immutable event and advances the local projection; durable outbox uploads later | Sync commits an immutable event and exactly-once answer receipt | Same facts, different online/offline transaction boundaries; end-to-end convergence still needs a shared fixture test |
| Rating previews | Uses non-mutating local scheduler projections | Uses non-mutating authoritative Sync previews | Behaviorally aligned; exact intervals may differ when local profiles intentionally differ |
| Card flags 0–7 | `local_card_flags`, keyed by local card ID and intentionally not uploaded | Receipt-backed operation changes `cards.scheduling.flags` | Intentional local/server difference for now; no native convergence work planned |
| Mark/Unmark Note | Rewrites the case-insensitive `marked` tag through the normal note outbox | Receipt-backed operation rewrites the same canonical tag and checksum | Same representation; concurrent-edit and pull behavior needs validation |
| Suspend Card/Note | Synchronized `active`/`suspended` card study state | Receipt-backed capability and confirmed UI are live on rolling | Expected to converge through the existing independent study-state model; no synthetic live mutation was used |
| Bury Card/Note | Device-local for the current synchronized study day | Not implemented | Semantics need matching without turning a temporary bury into permanent synchronized state |
| Set Due Date | Independent synchronized override | Not implemented | Web capability missing |
| Reset Card | Synchronized review-history cutoff; immutable history retained | Not implemented | Web capability missing |
| Edit/Delete/Create Copy | Native transactional note/card outboxes and tombstones | Not implemented in the reviewer | Later typed-operation slices |
| Card Info and previous history | Available natively | Presentation-scoped, bounded context | Substantially aligned |
| Automatic and inline audio | Native hydrated media with lifecycle cancellation | Hydrated account-owned data only with lifecycle cancellation | Behaviorally aligned; platform playback details may differ |
| New/Learn/Due eligibility and counts | Local projection from pulled facts and synchronized policy | Authoritative server queue/projection | Requires repeatable oracle tests; neither side may borrow the other side's mutable projection |

## Current decision: keep Kelma Review flags local

Kelma Review continues to store flags only in `local_card_flags`. Do not add a native flag outbox, consume
`cards.scheduling.flags` as native state, or migrate flags to portable identity in the active roadmap. Browser
and mirrored Anki flags may persist on the server, but Kelma Review makes no convergence promise for them.
Visual labels and values should remain familiar across clients without implying synchronized state.

If this decision is revisited, flags still need an independently versioned user-intent model keyed by note GUID
plus card ordinal. Clear (`0`) must be durable, conflict order cannot rely only on client clocks, mirror
materialization must not create feedback loops, and opaque scheduling fields must remain non-authoritative for
native FSRS. These are deferred design constraints, not approved work.

## P0: validate and harden Mark/Unmark convergence

Both clients use the canonical case-insensitive `marked` tag, but the operation can race with a general note edit. A native edit based on an older checksum may contain an older marked state even when all ordinary fields and tags are still useful.

Before declaring parity:

- Add native-to-Sync-to-browser and browser-to-Sync-to-native fixture tests.
- Verify a pull updates the menu immediately and does not duplicate `marked` with different casing.
- Verify marking changes the note checksum and re-renders the current card without invalidating its answer.
- Exercise a pending native field/tag edit racing with a browser mark.
- Decide whether the normal note conflict dialog is sufficient or whether marked intent needs independent version metadata that is materialized into the canonical tag.
- Ensure Keep this device / Use KelmaSync cannot silently lose a newer explicit mark action.
- Verify the account mirror imports and exports exactly one canonical `marked` token.

The likely deep seam is a typed note-metadata operation that owns only marked intent while the note store remains responsible for canonical tag materialization and checksum calculation.

## P1: align remaining review actions

Implement one closed operation kind at a time; do not expose a generic mutation endpoint.

1. **Suspend Card and Suspend Note**
   - The current browser slice reuses the existing synchronized card study-state model.
   - The recovery-only Vue revision is live. It recognizes frozen suspension intents and receipts but exposes no Suspend control.
   - Resolve sibling cards server-side for note suspension.
   - Require confirmation because browser Unsuspend is not yet available; direct users to Kelma Review.
   - Expire the browser presentation and exclude suspended cards from authoritative and native queues without changing review history.
   - Expose the Vue controls only after the recovery-only build is the rollback image.
2. **Bury Card and Bury Note**
   - Match the synchronized timezone/rollover policy.
   - Keep burial temporary and day-scoped.
   - Decide explicitly whether browser buries are session/server-device state or account-wide same-day intent. Do not accidentally turn burial into permanent sync metadata.
3. **Set Due Date and Reset Card**
   - Reuse their independent synchronized state instead of editing FSRS projections.
   - Reset must advance a monotonic history cutoff and retain immutable reviews.
   - A later answer must clear the due-date override exactly once.
4. **Edit, Delete, and Create Copy**
   - Use presentation-derived identity and typed payloads.
   - Preserve optimistic note checksums, tombstones, media ownership, and exactly-once recovery.
   - Never grant the browser a generic card/note batch authority.
5. **Undo**
   - Specify separately. Native undo can remove a pending local event, but a browser review may already be accepted immutable history.
   - Do not implement undo by deleting or rewriting an accepted server review without an explicit immutable compensating-event design.

## Shared parity test matrix

Use disposable fixture accounts and databases only; never use a live account or submit a real review solely for testing.

For every durable operation, test:

- native action -> upload -> Sync state -> browser context;
- browser action -> receipt -> native pull -> native UI;
- Anki/account-mirror action -> Sync -> both first-party clients;
- duplicate submission and receipt recovery with the same operation ID;
- changed intent under the same operation ID;
- offline native mutation racing with a browser mutation;
- account switch while a request or sync is in flight;
- exact-account row containment and cross-account denial;
- app restart between local commit, upload, acknowledgement, and confirming pull;
- clear/unmark/unsuspend states, not only positive states;
- current browser presentation remains answerable;
- no added review, quota consumption, attempt, or browser study-day write;
- projection rebuild and mirror cycles preserve accepted metadata;
- older-client pull/push cycles do not erase the new state.

Queue parity fixtures should cover New, Learning, Review, Relearning, intraday steps, learn-ahead, daily limits, parent limits, rollover/DST boundaries, suspended cards, and same-day buries. Compare eligibility and immutable inputs first; do not require identical local and server projection storage.

## Rollout gates

1. Record the final ownership and conflict contract in KelmaSync and Kelma Review protocol/design docs.
2. Add database migrations without modifying or rerunning previously applied migration files.
3. Pass disposable-database integration, scheduler/oracle, mirror compatibility, and all native common tests.
4. Upgrade the rolling schema only with the required private backup, stopped services, owner validation, account containment, and least-privilege grants.
5. For a new browser operation kind, deploy a recovery-only Vue build before capability exposure so that build can become the safe rollback image for the later visible UI. Then roll out KelmaSync, account mirror if needed, Fastify, and the visible Vue controls; a version-aligned Kelma Review rolling build follows only when native code changes.
6. Capture bidirectional convergence evidence across at least two mirror cycles and a native restart.
7. Keep production promotion separate and explicitly approved.
8. Roll back application images only; never automatically restore a database or rewrite accepted operations/reviews.

## Definition of parity for a durable action

For actions designated as synchronized, parity is reached only when:

- all clients display the same accepted intent after bounded synchronization;
- offline changes survive restart and eventually converge;
- concurrent changes have a documented deterministic outcome;
- retries are idempotent and ambiguous outcomes are recoverable;
- identity is portable and account-scoped;
- review history, quota, and scheduling ownership remain unchanged unless the action explicitly owns one of them;
- clear/reversal actions are first-class and cannot be resurrected by stale state;
- old clients fail safely and do not erase the state;
- automated tests and rolling evidence cover every supported direction.
