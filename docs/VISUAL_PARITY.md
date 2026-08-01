# Platform visual language

The application deliberately has separate desktop and mobile presentations. Shared domain behavior does not require identical chrome.

## Brand marks

The plain green star is the general Kelma company mark. Product icons add one clear object to that star: Kelma Immersion uses an open book, while Kelma Review uses a single review card. Kelma Review packages and launchers must use the star-and-card icon on desktop, Android, and iOS; they must not use the plain company star, the Immersion book, or platform-template artwork.

## Kelma Review on mobile

Android and iOS retain the current Kelma Review mobile look: near-black olive canvases, layered warm surfaces, gold accents, off-white text, rounded controls, and compact top navigation. This remains true on tablets and in landscape; screen width alone must not switch a mobile device into desktop chrome.

| Token | Color |
|---|---|
| Background | `#0F100A` |
| Background alternate | `#141610` |
| Surface | `#1B1D16` |
| Surface elevated | `#24271D` |
| Surface high | `#2D3024` |
| Surface border | `#3A3D31` |
| Hairline | `#2A2C22` |
| Gold | `#C9AC6B` |
| Gold soft | `#DCC48F` |
| Gold bright | `#ECD49A` |
| Text primary | `#F4F1E7` |
| Text secondary | `#ADAEA1` |
| Text muted | `#7B7D70` |

Mobile uses a native five-item bottom navigation bar for Decks, Browse, Add, Options, and Sync, with safe-area-aware compact top app bars. The Add tab is the sole collection-level add affordance; there is no redundant floating Add Card button. Sync-now remains a separate icon action instead of competing with the Sync activity destination; account actions live in overflow rather than a row of wrapping text buttons. Mobile Options provides the same functional persisted scheduling controls—including five display-order selectors—in a touch-first stacked editor while unavailable groups remain muted. iOS keeps native touch dragging and explicitly routes Simulator trackpad-wheel events into each mobile scroll state. Headers use a four-pixel gold accent bar, 34/40 sp screen titles, and deliberate 20 dp horizontal gutters. Corner radii follow 10, 14, 18, and 22 dp steps. Add is a touch-first stacked editor: Type and Deck selectors, a horizontally scrolling formatting toolbar, large stacked field cards with pin and preview toggles, tag chips, and a persistent bottom action. It does not inherit the desktop footer or columns. Browse is a search field with horizontal filter chips, a stacked result list, and an in-place detail view rather than the desktop's sidebar and table. Review follows the current Kelma Review mobile presentation: a compact back/count header with Undo and a touch-sized card-options overflow in the top action area (Suspend and Bury actions first), the same default Undo confirmation used by Decks, edge-to-edge bounded card surface, independently scrollable 22sp content, tap-to-reveal behavior, revealed-card left-half Again and right-half Good shortcuts, and fixed bottom Show answer or four-rating controls that remain reachable regardless of card length or media size.

## Kelma Review on desktop

The JVM desktop application follows the supplied Kelma Review desktop references rather than scaling up mobile UI:

- A near-black olive canvas with a centered, bordered Decks/Add/Browse/Options/Stats/Sync pill. Top-level items are text-only; settings icons remain on deck rows. Sync opens a detailed persistent activity tab with a visible Sync now button; the deck screen's bottom utility row also exposes a sync icon, Sync action, and Ctrl/Cmd+S hint, and the shortcut runs sync without navigating.
- A narrow rounded deck panel with checkbox marks, bright count columns, hover rows, and a gear affordance.
- KelmaSync card/media totals centered above the deck panel.
- A distinct deck-overview screen with counts, a gold Study Now action, and bottom utility controls.
- The deck list's Create Deck utility opens a focused naming dialog; it does not duplicate the toolbar's Add action.
- Every desktop deck row keeps a visible gear. Its rounded olive menu always displays Rename, Options, Export, Delete, Add cards, and Browse cards. All six actions are enabled; destructive actions use styled confirmation dialogs.
- Options uses a wide two-column desktop workspace of rounded olive setting cards. Daily limits, learning, FSRS, timer/audio, undo confirmation, maximum interval, and all five display-order selectors are functional and use normal contrast; intentionally unavailable KelmaDesktop fields remain visibly grey and non-interactive.
- A flat reviewer canvas with centered card content, circular gold audio control, and no mobile card shell.
- Reviewer counts and actions live against the bottom edge, with Edit and More flanking the gold answer action.
- A native desktop title bar reading `User 1 - Kelma`.
- Add uses a purpose-built wide note editor: a Type and Deck row, a full formatting toolbar, per-field pin and preview toggles, tag chips, and a Help/Add/History/Close footer with a visible keyboard shortcut. It matches Anki's editor capabilities without copying its legacy dialog styling.
- Browse uses a three-part workspace: a narrow filter sidebar, a sortable results table with a search bar, and a wider card detail panel with preview, metadata, and actions. Edit lives at the top of the detail panel and changes the preview surface into an inline source editor; it is not a popup. Save and Cancel share the `EDIT NOTE` row above the bordered field surface. The Browse toolbar pill shows the active state.

| Desktop token | Color |
|---|---|
| Background | `#0F100B` |
| Toolbar | `#1B1A12` |
| Panel | `#1C1B13` |
| Hover row | `#393629` |
| Border | `#403821` |
| Text primary | `#F4F1E7` |
| Text secondary | `#C7C2B4` |
| Text muted | `#958B70` |
| KelmaSync accent | `#08A64B` |
| Gold action | `#E8CF91` |
| New | `#86BEF4` |
| Learning | `#FF6B73` |
| Due | `#18C45A` |

## Shared semantics

New, learning/again, good/due, hard, and easy retain their semantic blue/red/green/amber colors across platforms. Media, card content, keyboard behavior, and accessibility semantics remain shared even when their containers differ.
