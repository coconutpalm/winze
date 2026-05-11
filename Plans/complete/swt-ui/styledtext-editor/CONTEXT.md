---
created: 2026-03-27
related: [MD-THEME-CONTEXT.md, MD-THEME-PLAN.md]
tags: [swt, ui, editor, markdown, styledtext]
---

# StyledText Markdown Editor — Context

## Goal

Create a new CDT-compatible widget type that provides a `StyledText`-based
markdown editor for `.md` files. The editor lives in tabs alongside the existing
`Browser`-based read-only file viewer. It applies md-theme styling in real-time
as the user types, and auto-saves after a debounce period.

## How it fits — swappable content area

Currently, clicking a file link opens a read-only `Browser` tab via
`open-tab!`. Rather than creating separate tab types for viewing and editing,
each file tab has a **wrapper Composite** as its `CTabItem` control. The
Composite holds either a `Browser` (view mode) or a `StyledText` (edit mode).
Toggling disposes the current child and instantiates the other.

```
CTabFolder
  └── CTabItem (control = wrapper Composite)
        └── Browser        ← view mode (default)
            — or —
        └── StyledText     ← edit mode (toggled)
```

The toggle is triggered by:
- A **toolbar button** in the `CTabFolder`'s top-right area (via `setTopRight`)
- A **global accelerator**: `SWT/MOD1`+E (Cmd+E on macOS, Ctrl+E elsewhere)

### Toolbar button

A `ToolBar` with a single `ToolItem` placed via `setTopRight`. The icon uses
brand colors (see "Edit icon" below). The button is **disabled** when the
active tab is:
- The live search tab (index 0)
- A search result tab (opened via `open-tab!` without an `abs-path`)

A `CTabFolder` selection listener updates the button's enabled state whenever
the active tab changes.

### Edit icon

A small (16×16, with 32×32 @2x for Retina) icon representing "edit the current
document". Design: a pencil/stylus silhouette in Amethyst (`#9B8FE0`) on a
transparent background. Use the same `hidpi-image` / `new-image-resource`
pattern as the existing icons in `resources.clj`. The icon SVG source goes in
`resources/branding/ui/svg/`, rasterized PNGs in `resources/branding/ui/png/`.

### Per-tab state

Each file tab needs to track its current mode and the data needed to swap:

```clojure
;; Stored in open-files alongside :tab-ids and :rel-path
{:mode      :view          ; or :edit
 :abs-path  "/path/to/file.md"
 :rel-path  "dev/PLAN.md"
 :wrapper   <Composite>    ; the CTabItem's control
 :history   <atom>         ; undo/redo state (persists across toggles)
 :save-future <atom>}      ; pending auto-save
```

Undo/redo history and save state **persist across view↔edit toggles** within
the same tab. Switching to view mode flushes any pending save first.

### Toggle flow: view → edit

1. Flush is a no-op (nothing to save in view mode).
2. Dispose the `Browser` child of the wrapper Composite.
3. Create a `StyledText` inside the wrapper, loading current file content.
4. Apply the md-theme.
5. Set tab `:mode` to `:edit`.
6. Update toolbar button icon/tooltip to "View" state.
7. `.layout` the wrapper Composite.

### Toggle flow: edit → view

1. Flush any pending auto-save.
2. Capture an undo snapshot.
3. Dispose the `StyledText` child.
4. Create a `Browser` inside the wrapper, rendering the file as HTML.
5. Set tab `:mode` to `:view`.
6. Update toolbar button icon/tooltip to "Edit" state.
7. `.layout` the wrapper Composite.

## CDT integration

CDT generates a `styled-text` init function (confirmed in SWT-UI-GUIDE §6).
The editor wraps it as a custom control factory:

```clojure
(defn markdown-editor
  "CDT init function: StyledText configured for markdown editing."
  [abs-path & extra-inits]
  (apply styled-text (| SWT/MULTI SWT/V_SCROLL SWT/WRAP)
         :font       @res/body-font
         :background @res/color-mine-shaft
         :foreground @res/color-crystal-white
         :word-wrap  true
         :text       (slurp abs-path)
         ;; ... ModifyListener, extra-inits
         extra-inits))
```

## Real-time restyling

When text changes, the editor recomputes styling for the full document and
applies it. The flow:

```
User types  →  ModifyListener fires  →  (md-theme/theme text) → span maps
            →  (md-theme/apply-theme! styled-text spans) → StyleRange[]
            →  also: reset debounce save timer
```

### Why full-document restyle is acceptable

- Markdown planning documents are typically < 500 lines.
- `md-theme/theme` is pure-data: string → vector of maps. No SWT calls.
- `setStyleRanges` replaces all ranges in a single native call.
- `setStyleRanges` does **not** fire `ModifyListener` — no infinite loop risk.

If performance becomes an issue later, we can optimize by restyling only visible
lines (using `getTopIndex` / `getLineCount` + viewport height). But do not
prematurely optimize.

### Typing triggers

The restyling should feel immediate. Key scenarios:

| User action                   | Expected result                       |
|-------------------------------|---------------------------------------|
| Type `# ` at line start       | Line becomes H1 (24pt bold, lavender) |
| Type ` ``` clojure` + Enter   | Subsequent lines get code-block bg    |
| Close fence with ` ``` `      | Code block styling completes          |
| Type `**` around a word       | Word becomes bold on closing `**`     |
| Delete `#` from heading       | Line reverts to body style            |

All of these fall out naturally from "retheme the whole document on every
change" — no special-case keystroke detection needed.

## Auto-save with debounce

After each text change, a debounce timer is reset. When it expires, the
content is saved to disk on a background thread. The debounce prevents
writing on every keystroke.

### Design

```clojure
(def save-delay-ms 1500)
```

Use `ScheduledExecutorService` (one shared instance in `resources.clj`) rather
than `Display.timerExec` — the actual file I/O must happen off the UI thread.
The timer schedules `(spit abs-path content)`.

On dispose (tab close), flush immediately if there are unsaved changes — cancel
any pending timer and save synchronously.

### Interaction with the file watcher

The file watcher (`watcher.clj`) monitors the plans directories and fires
`on-file-changed` callbacks. When the editor saves, the watcher will see a
`:modify` event. The editor must suppress refresh for its own saves to avoid:

1. Editor saves → watcher fires → refresh callback replaces content → cursor jumps

Approach: `refresh-open-tabs!` compares the new file content against what the
editor already has; if identical, skip. This is simpler and more robust than a
suppress-flag (no race window, no state to manage).

## Tab title indicators

The tab title should reflect save state:

| State    | Title display        |
|----------|----------------------|
| Clean    | `filename.md`        |
| Modified | `filename.md *`      |
| Saving   | `filename.md ...`    |

Update via `.setText` on the `CTabItem` from within the debounce lifecycle.

## Undo / Redo

Snapshot-based undo/redo. Each snapshot is the full editor text plus
scroll position and cursor offset — no diffing.

### Snapshot capture points

- **On load**: initial file content (scroll 0, cursor 0)
- **On save**: the content at the moment it is written to disk

These are the only times a snapshot is pushed to the undo stack.

### Data structure

```clojure
{:text       "..."   ; full editor text
 :top-pixel  0       ; StyledText.getTopPixel
 :caret      0}      ; StyledText.getCaretOffset
```

Per-editor state (stored in a local atom, not global):

```clojure
{:undo []   ; stack of snapshots (most recent last)
 :redo []}  ; stack of snapshots (most recent last)
```

### Undo (MOD1+Z)

1. Capture the editor's current state as a snapshot.
2. Push it onto the redo stack.
3. Pop the most recent snapshot from the undo stack.
4. Replace the editor text, restore scroll position and cursor offset.
5. Restyle the document.

### Redo (MOD1+Shift+Z)

1. Capture the editor's current state as a snapshot.
2. Push it onto the undo stack.
3. Pop the most recent snapshot from the redo stack.
4. Replace the editor text, restore scroll position and cursor offset.
5. Restyle the document.

### Edge cases

- Undo with empty undo stack → no-op.
- Redo with empty redo stack → no-op.
- Any user edit (text change via typing) clears the redo stack, as is standard.
- The undo stack is unbounded for the lifetime of the tab — markdown planning
  docs are small enough that storing full snapshots is negligible.

## Namespace

```
llm-memory.ui.markdown-editor   — CDT init function, auto-save, restyle wiring,
                                  toggle-mode!, toolbar setup
```

Dependencies:
- `llm-memory.ui.resources` — fonts, colors, app-props, element, open-files, etc.
- `llm-memory.ui.md-theme` — theme computation (pure) + apply-theme! (SWT)
- `llm-memory.highlight.core` — syntax highlighting (via md-theme)
- `ui.SWT` — `styled-text`, `composite`, `on`, `id!`, `async-exec!`, etc.

## Refactoring `open-tab!`

The existing `open-tab!` creates a `Browser` directly as the `CTabItem`'s
control. This must change so that file tabs (those with an `abs-path`) use a
wrapper Composite instead:

```clojure
;; Before: Browser is the CTabItem control
(custom-browser (id! tab-id) :text html ...)
(ctab-item SWT/CLOSE title (control tab-id))

;; After: Composite wrapper is the CTabItem control
(composite (id! tab-id) :layout (FillLayout.)
  (custom-browser (id! browser-id) :text html ...))
(ctab-item SWT/CLOSE title (control tab-id))
```

Tabs without an `abs-path` (live search, standalone search results) continue
to use a `Browser` directly — no wrapper needed, and the edit button is
disabled for these tabs.

## Toolbar lifecycle

The `ToolBar` with the edit toggle is created once during `main-window` setup
via `setTopRight` on the `CTabFolder`. The selection listener on the folder
updates the button state:

```clojure
;; Pseudo-code in the selection listener:
(let [tab   (.getSelection folder)
      ctrl  (.getControl tab)
      entry (tab-entry-for-ctrl ctrl)]  ; look up in open-files
  (if (and entry (:abs-path entry))
    (do (.setEnabled edit-button true)
        ;; Update icon/tooltip based on current mode
        ...)
    (.setEnabled edit-button false)))
```
