---
doc_type: context
status: active
group: palette-cdt-refactor
---

# Command Palette CDT Refactor — Context

## What and Why

`llm-memory.ui.command_palette` was written with raw Java interop throughout:
raw `Shell.`, `Text.`, `Table.`, `TableColumn.`, `GridData.`, `GridLayout.`,
and SWT listener adapter classes.  The inline comments acknowledge this and
attribute it to CDT's `shell` not supporting parent-shell creation — but that
attribution is incorrect.  `content_assist.clj` already demonstrates the correct
pattern: `(child-of (element :main-window) props (shell (| SWT/TOOL ...) ...))`.

This work brings the command palette into line with the rest of the UI codebase,
replacing raw interop with CDT init functions, switching the filter field from
`Text` to `StyledText`, and fixing an unreadable selection highlight.

## Affected File

`winze-server/src/llm_memory/ui/command_palette.clj`

## Current Implementation

`open-palette!` builds the entire widget tree imperatively:

```
Shell. parent-sh  (| SWT/TOOL SWT/ON_TOP SWT/NO_TRIM)
  Text. sh SWT/SINGLE              — filter field
  Table. sh (| SWT/SINGLE SWT/FULL_SELECTION)
    TableColumn. tbl SWT/LEFT      — label column (310px)
    TableColumn. tbl SWT/LEFT      — scope column (70px)
    TableColumn. tbl SWT/RIGHT     — keybinding hint column (88px)
```

All listeners are attached via raw Java adapter classes:
`ModifyListener`, `KeyAdapter`, `ShellAdapter`, `DisposeListener`, and a raw
`Listener` via `.addListener tbl SWT/Selection`.

`populate-table!` creates `TableItem.` instances directly inside the table.
This is dynamic row creation, not widget construction — it stays raw.

## CDT Pattern to Follow

`content_assist.clj` (`open-content-assist!`) is the canonical reference.
It uses:

```clojure
(child-of (element :main-window) props
          (shell (| SWT/TOOL SWT/ON_TOP SWT/NO_TRIM)
                 (id! :ca/shell)
                 :background bg
                 (grid-layout ...)
                 ...children...))
sh (:ca/shell @props)
```

## StyledText vs Text — macOS Platform Limitation

`Text.setSelectionBackground(Color)` and `Text.setSelectionForeground(Color)`
are **no-ops on macOS Cocoa**.  SWT's Cocoa backend does not honour these
calls for the native `Text` widget — the platform renders selection in the
system highlight colour regardless.  This means the `:selection-background` /
`:selection-foreground` properties used in `find_replace.clj` on regular CDT
`text` widgets have no effect on macOS.

`StyledText` is a pure-Java widget that manages its own painting.  Its
`.setSelectionBackground(Color)` and `.setSelectionForeground(Color)` methods
work correctly on all platforms including macOS Cocoa, because SWT hands the
entire paint cycle to the widget rather than delegating to the native control.

This is the reason the filter field must be `StyledText`, not `Text`.

CDT confirms `styled-text` as a generated init function (SWT-UI-GUIDE §6),
so the switch is a drop-in at the CDT level:
replace `(text SWT/SINGLE ...)` with `(styled-text SWT/SINGLE ...)`.
CDT maps `:selection-background` / `:selection-foreground` to the underlying
`.setSelectionBackground` / `.setSelectionForeground` calls.

The scope data tag (`.setData parent "scope" :command-palette`) is set via a
raw init fn `(fn [_props parent] (.setData parent "scope" :command-palette))`
— same pattern used in `content_assist.clj` and `find_replace.clj`.

## Selection Highlight Color

The current `Text` widget shows text selection in the system highlight colour
on macOS (blue by default), which clashes with crystal-white foreground on the
mine-shaft background — and cannot be overridden via SWT on Cocoa.

**Mine-shaft** (`#1E1B2E`) is the widget background.  
**Crystal-white** (`#E8E0FF`) is the text foreground.

`color-royal-purple` (`#5548A0`) is the right selection background:

- Substantially lighter than mine-shaft — unambiguous visual signal that text is selected
- Contrast with crystal-white ≈ 6.2:1 (passes WCAG AA, threshold 4.5:1)
- Already in the palette; no new resource var needed
- Selection foreground: crystal-white can stay (same as normal foreground)

## Singleton Lifecycle

`@palette-shell` currently tracks the raw `Shell.` object.  After CDT
conversion the shell is stored in props as `(:palette/shell @props)`.
`palette-open?` and `close-palette!` must be updated to retrieve the shell
from `@palette-shell` — which is reset to the CDT-built shell after
`child-of` completes, just as `content_assist.clj` resets `popup-state`
after building its tree.

## Remaining Raw Interop (by design)

The following raw Java calls stay after the refactor:

| Location | Raw Java | Reason |
|---|---|---|
| `populate-table!` | `TableItem. table SWT/NONE` | Dynamic row creation — not widget construction |
| `resize-to-fit!` | `.getLayout sh`, `.getBounds sh`, `.setSize sh` | Post-open geometry, no CDT equivalent |
| `position-palette!` | `.getBounds parent-sh`, `.setBounds palette-sh` | Pixel positioning, no CDT equivalent |
| `execute-and-close!` | `.getSelectionIndex`, `.getItem`, `.getData`, `.close` | Widget queries and imperative close |

## Key Invariants

- The `populate-table!` function signature does not change.  It receives the
  CDT-constructed `Table` widget (retrieved from `@props` in `open-palette!`)
  and operates on it identically to today.
- `resize-to-fit!` and `position-palette!` receive widget refs extracted from
  `@props` — their bodies are unchanged.
- `keybindings/set-active-popup!` and `keybindings/clear-active-popup!` calls
  stay in the same positions relative to `.open` and `.close`.
- `palette-open?` / `close-palette!` remain public API — they check
  `@palette-shell` as today, but `@palette-shell` is populated from CDT props.
