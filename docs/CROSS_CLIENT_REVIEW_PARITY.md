# Cross-client review parity

## Status and scope

This is a living planning note, not an implementation or production-promotion approval. It tracks behavioral and data convergence among:

- Kelma Review on desktop, Android, and iOS;
- the rolling Kelma web reviewer;
- KelmaSync v2 and the account mirror used for Anki interoperability.

Parity means that durable user intent and immutable review facts converge predictably. It does **not** mean identical chrome, identical local FSRS projections, or synchronizing temporary UI/session state. Native clients must remain useful offline, while the browser reviewer remains server-authoritative.

The first priority is the flags/marked-note slice currently under review in
[KelmaSync PR 19](https://github.com/jeretmccoy/kelma_sync_2/pull/19),
[Fastify PR 42](https://github.com/jeretmccoy/anki_ai_fastify/pull/42), and
[frontend PR 77](https://github.com/jeretmccoy/anki_ai_frontend/pull/77). That rolling implementation is useful
for validating browser behavior, but its use of `cards.scheduling.flags` is not yet the final native
convergence contract.

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
| Card flags 0–7 | `local_card_flags`, keyed by local card ID and intentionally not uploaded | Receipt-backed operation currently changes `cards.scheduling.flags` | **Not convergent** |
| Mark/Unmark Note | Rewrites the case-insensitive `marked` tag through the normal note outbox | Receipt-backed operation rewrites the same canonical tag and checksum | Same representation; concurrent-edit and pull behavior needs validation |
| Suspend Card/Note | Synchronized `active`/`suspended` card study state | Not implemented | Web capability missing |
| Bury Card/Note | Device-local for the current synchronized study day | Not implemented | Semantics need matching without turning a temporary bury into permanent synchronized state |
| Set Due Date | Independent synchronized override | Not implemented | Web capability missing |
| Reset Card | Synchronized review-history cutoff; immutable history retained | Not implemented | Web capability missing |
| Edit/Delete/Create Copy | Native transactional note/card outboxes and tombstones | Not implemented in the reviewer | Later typed-operation slices |
| Card Info and previous history | Available natively | Presentation-scoped, bounded context | Substantially aligned |
| Automatic and inline audio | Native hydrated media with lifecycle cancellation | Hydrated account-owned data only with lifecycle cancellation | Behaviorally aligned; platform playback details may differ |
| New/Learn/Due eligibility and counts | Local projection from pulled facts and synchronized policy | Authoritative server queue/projection | Requires repeatable oracle tests; neither side may borrow the other side's mutable projection |

## P0: define a synchronized card-flag contract

The native app must not begin trusting the entire opaque `scheduling` object merely to read a flag. Scheduling payloads contain foreign mutable projections that Kelma deliberately ignores. Promote the flag to an independently versioned piece of user intent.

Recommended wire model:

```text
CardFlagState
  account scope (implicit from authentication)
  note GUID + card ordinal (portable identity)
  current source card ID (lookup/compatibility only)
  flag 0..7 (0 is an explicit clear)
  client operation ID
  client modified time
  server revision/modified time
```

The exact table and endpoint shape still require design review. Reusing the existing card metadata envelope is acceptable if the flag has its own version and conflict fields; hiding the final contract only inside `scheduling` is not.

Required work:

1. **KelmaSync protocol and storage**
   - Add independently versioned flag state to manifest/pull responses.
   - Accept an idempotent typed native flag mutation using portable card identity resolved inside the authenticated account.
   - Keep browser receipt creation and flag mutation in one transaction.
   - Materialize the compatibility value into `cards.scheduling.flags` only where the Anki mirror requires it.
   - Ensure FSRS projection publication cannot reset the flag.
   - Represent clear (`0`) durably so an older nonzero value cannot reappear.
2. **Kelma Review persistence**
   - Migrate `local_card_flags` from local card-ID-only storage to portable note-GUID/card-ordinal state.
   - Preserve every existing local flag during migration when its card can be resolved.
   - Add pending/uploaded state, operation ID, and conflict metadata.
   - Apply pulled state without replacing a newer pending local action.
   - Keep the local visual update and outbox write atomic; roll back only on a definitive local persistence failure.
3. **Account mirror compatibility**
   - Translate Anki's card flag to/from the independent flag state without treating other scheduling fields as Kelma schedule truth.
   - Prevent feedback loops where materialized compatibility data appears as a new user mutation.
   - Define deterministic precedence when Anki, an offline native device, and the browser change the same flag concurrently.
4. **Browser reviewer**
   - Continue deriving identity from the active presentation.
   - Return accepted state from the receipt and refresh optional context.
   - Keep grading locked only while an operation outcome is ambiguous.
   - Advertise the capability only when the required schema and containment checks pass.

Preferred conflict behavior is deterministic last accepted user intent by independently versioned flag state, not replacement of a whole card scheduling payload. Client wall clocks cannot be the sole ordering authority.

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
   - Reuse the existing synchronized card study-state model.
   - Resolve sibling cards server-side for note suspension.
   - Exclude suspended cards from authoritative and native queues without changing review history.
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
5. Roll out in dependency order: KelmaSync, account mirror if needed, Fastify, web frontend, then a version-aligned Kelma Review rolling build.
6. Capture bidirectional convergence evidence across at least two mirror cycles and a native restart.
7. Keep production promotion separate and explicitly approved.
8. Roll back application images only; never automatically restore a database or rewrite accepted operations/reviews.

## Definition of parity for a durable action

An action is at parity only when:

- all clients display the same accepted intent after bounded synchronization;
- offline changes survive restart and eventually converge;
- concurrent changes have a documented deterministic outcome;
- retries are idempotent and ambiguous outcomes are recoverable;
- identity is portable and account-scoped;
- review history, quota, and scheduling ownership remain unchanged unless the action explicitly owns one of them;
- clear/reversal actions are first-class and cannot be resurrected by stale state;
- old clients fail safely and do not erase the state;
- automated tests and rolling evidence cover every supported direction.
