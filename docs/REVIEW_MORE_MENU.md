# Review More menu

## Flag Card

- [x] No Flag / clear flag (`Cmd/Ctrl+0`)
- [x] Red (`Cmd/Ctrl+1`)
- [x] Orange (`Cmd/Ctrl+2`)
- [x] Green (`Cmd/Ctrl+3`)
- [x] Blue (`Cmd/Ctrl+4`)
- [x] Pink (`Cmd/Ctrl+5`)
- [x] Turquoise (`Cmd/Ctrl+6`)
- [x] Purple (`Cmd/Ctrl+7`)
- [x] Persist the selected flag
- [x] Indicate the selected flag in the menu
- [x] Roll back the UI if persistence fails

Card flags are intentionally device-local under the current design.

## Card actions

- [x] **Bury Card** — persisted on this device for the current study day
- [x] **Reset Card…** — keeps immutable review history and synchronizes a New scheduling cutoff across clients
- [x] **Set Due Date…** — synchronized independently until the card is reviewed or reset
- [x] **Suspend Card** — durable and synchronized
- [x] **Options**
- [x] **Card Info**
- [x] **Previous Card Info**

## Note actions

- [x] **Mark/Unmark Note** — toggles the synchronized `marked` tag
- [x] **Bury Note** — persists all sibling cards for the current study day
- [x] **Suspend Note** — durable and synchronized
- [x] **Create Copy…** — confirms, then creates a synchronized independent note with matching fields, tags, cards, and decks
- [x] **Delete Note** — confirms, hides immediately, and synchronizes a note/card tombstone

## Audio actions

- [x] **Replay Audio**
- [x] **Pause Audio** — preserves the current playback position
- [x] **Audio −5s** — seeks the active clip without crossing its bounds
- [x] **Audio +5s** — seeks the active clip without crossing its bounds
- [x] **Record Own Voice** — permission-aware temporary microphone recording
- [x] **Replay Own Voice** — replays the current review-session recording
- [x] **Auto Advance** — toggles a session-local 3-second reveal and 5-second Good rating

## Suggested implementation order

1. ~~Persisted **Bury Note**~~
2. ~~**Mark/Unmark Note**~~
3. ~~**Delete Note** with confirmation and a synchronization tombstone~~
4. ~~**Set Due Date**~~
5. ~~**Create Copy**~~
6. ~~Audio seeking~~
7. ~~Auto Advance~~
8. ~~Voice recording and replay~~

## Platform availability

Desktop exposes the menu from the review footer with keyboard shortcuts. Mobile exposes the same behavior from a touch-sized top-bar overflow menu, with the highest-frequency actions first: Suspend Card, Bury Card, Suspend Note, and Bury Note.
