# Anki file interchange

Kelma implements Anki file interchange independently in Kotlin Multiplatform. It does not embed, call, or translate Anki/rslib implementation code. Python and an installed Anki build are used only as optional development oracles.

## Supported formats

| Format | Import | Export | Notes |
| --- | --- | --- | --- |
| `.apkg` | Yes | Yes | A selected deck tree or the whole collection |
| `.colpkg` | Yes | Yes | Always exports the whole collection; import merges safely rather than replacing the local database |
| Anki notes text | Yes | Yes | UTF-8, self-describing directives, configurable delimiter on import |
| Rendered cards text | Yes | Yes | Question/answer rows; import is intentionally lossy and creates Basic notes |

Package export offers independent controls for media, immutable scheduling history, and reusable deck configurations. **Support older Anki versions** emits the legacy package layout; otherwise Kelma emits the current Zstandard/Protobuf layout.

## Package contract

A current package is a ZIP32 archive containing:

- `meta`: a Protobuf package-version record.
- `collection.anki21b`: a Zstandard-compressed SQLite collection.
- `collection.anki2`: a small upgrade-message fallback collection.
- `media`: a Zstandard-compressed Protobuf manifest.
- Numeric media entries (`0`, `1`, …), each Zstandard-compressed and validated by size and SHA-1.

A legacy-compatible package uses `collection.anki21`, a JSON `media` map, and uncompressed numeric media entries. Import also accepts packages whose primary database is `collection.anki2`.

Kelma writes a deterministic schema-11 SQLite collection. Import reads schema-11 metadata and the normalized schema-18 `notetypes`, `fields`, `templates`, `decks`, and `deck_config` tables. ZIP paths, entry sizes, decompressed sizes, media names, media checksums, and aggregate media size are validated before persistence. Packages are limited to 512 MiB and 65,535 ZIP entries.

The implementation is based on the public file-format contract and interoperability observations, including the Anki manual's [export](https://docs.ankiweb.net/exporting.html) and [text import](https://docs.ankiweb.net/importing/text-files.html) documentation and the published Anki Protobuf/SQLite schemas. Compatibility acceptance uses isolated, content-minimal collections in both directions.

## Scheduling policy

Kelma's immutable review ledger remains authoritative:

- Export can include rating, review timestamp, duration, and Anki-compatible interval projections.
- Import accepts valid review-log ratings and timestamps.
- Imported card queue, due date, ease, memory state, and counters are never trusted as local scheduling truth.
- Imported reviews are deduplicated and replayed through the active local scheduler in timestamp order.
- A review-ID collision with different card/rating identity is reported and skipped rather than overwritten.

This keeps Anki transport compatible without allowing foreign mutable projections to replace Kelma's local FSRS state.

## Conflict and media policy

Import is transactional and additive:

- A matching note GUID is reused only when note type, ordered fields, and tags match.
- Different content with the same GUID receives a deterministic copy GUID.
- Cards are matched by the portable note-GUID/card-ordinal identity.
- Note types and deck presets are reused only when their definitions/options match.
- Media names are normalized; identical existing media is reused and differing collisions are renamed. References in fields and templates are rewritten to the final name.
- Imported local content, note types, cards, media, and reviews enter the existing durable sync/outbox model.

`.colpkg` import deliberately does **not** destructively replace the current Kelma collection.

## Text contract

Notes export includes Anki directives for separator, HTML, GUID, note type, deck, and tags. The parser supports BOMs, CRLF/LF, quoted multiline fields, escaped quotes, and tab/comma/semicolon/space/pipe/colon separators.

Rendered-card text contains the completed question and answer HTML. Because it has no note-type or template structure, import creates one Basic note per row and displays an explicit lossy-import warning.

## Deliberate boundaries

- Password-protected/encrypted packages and ZIP64 output are not supported.
- Filtered-deck behavior, add-on metadata, and foreign scheduler projections are not preserved.
- Anki card suspension/queue flags do not override Kelma's local queue policy.
- Exported packages use standard Anki extensions only because they implement the corresponding Anki container and database contracts; Kelma-native JSON is not relabeled as `.apkg` or `.colpkg`.
