---
created: 2026-03-31
doc_type: context
group: search-history-nav
tags: [swt, ui, search, navigation, keybindings]
related: [search-keybindings, global-esc, search-scope]
---

# Search History Navigation — Context

## Goal

Add back/forward navigation through search history on the winze live search page. Users should be able to revisit previous search queries using toolbar arrows and keyboard shortcuts, similar to browser back/forward behavior.

## Current State

### Search Text Widget & Live Search Flow

The search field is an `SWT/SEARCH | SWT/ICON_CANCEL` Text widget in the header (`main_window.clj:535`). Every keystroke fires `modify-text`, which calls `search/results` with the raw query string. That function:

1. Parses the query via `parse-query` (extracts semantic text + `#tag` filters)
2. Debounces (300ms) on a single-thread executor
3. Runs vector search off the UI thread
4. Pushes HTML to the Browser widget via `async-exec!`

### Existing Query State

- **`resources/last-search-query`** — atom holding `{:query "raw" :text "semantic" :filters {...}}` or `nil`. Used *only* by `refresh-last-search` (re-runs the query when indexed files change on disk). It stores a single query, not a list.
- There is **no history stack** anywhere in the codebase.

### Existing Toolbar

`setup-edit-toolbar!` (`main_window.clj:501`) creates a `ToolBar` with a single edit toggle `ToolItem`, placed via `.setTopRight` on the `CTabFolder`. This toolbar is positioned in the **tab folder's top-right corner** area.

### Existing Global Key Handler

The Display-level `SWT/KeyDown` filter (`main_window.clj:750`) currently handles:
- **Esc** → switch to live search tab, clear search, focus search field
- **Cmd+E** → toggle view/edit mode on active file tab

### Related Completed Work

- **`search-keybindings`** — Enter snapshots results into a new tab; Escape/X clears search
- **`global-esc`** — Display-level Esc handler (switch to search, clear, focus)
- **`styledtext-editor`** — Cmd+E edit toggle, Cmd+Z/Cmd+Shift+Z undo/redo (per-editor history stack — different from navigation history)

### Related Deferred Work

- **`search-scope`** — scope selector (filter search by root). Not yet implemented. The header area may gain additional widgets — coordinate layout.

## Architecture Constraints

### What Constitutes a History Entry

A history entry is a **search query string** — the raw text the user typed. Navigation means restoring that text into the search field and re-running the search. This is simpler and more correct than caching HTML results, because:

- Results change as documents are indexed/updated
- The search field text should reflect what the user is "looking at"
- Re-running is cheap (300ms debounce + ~50ms vector search)

### Entries to Track

Only queries that produce search results (≥3 chars after parsing, or non-empty filters). Empty/cleared states are not history entries — Esc already handles "go home."

### History Stack Model

Standard browser-style navigation:
- **`history`**: vector of query strings, oldest first
- **`position`**: index into the vector (0-based, points at current)
- When the user types a new query: truncate everything after `position`, append the new query, advance `position`
- **Back**: decrement `position`, restore query at new position
- **Forward**: increment `position`, restore query at new position
- Back at position 0 → disabled (no earlier history)
- Forward at last position → disabled (no later history)

### Programmatic Text Restoration

Setting the search field text via `(.setText (element :search) q)` fires `modify-text`, which triggers a new live search. This is the desired behavior — the search re-runs with current index state. However, we must **suppress history recording** during programmatic restoration to avoid polluting the stack. Use a flag atom (e.g., `restoring-history?`) checked in the `modify-text` handler or in the history-push logic.

### Toolbar Placement

The existing edit toolbar uses `.setTopRight` on the CTabFolder. SWT only allows **one** top-right control. Options:

1. **Composite toolbar**: Replace the single `ToolBar` with a `Composite` containing both the nav toolbar and the edit toolbar. Set this composite as `.setTopRight`.
2. **Single toolbar**: Add back/forward items to the existing `ToolBar` before the edit button, with a separator.
3. **Header placement**: Place nav arrows in the header composite, next to the search field.

Option 2 (single toolbar with separator) is simplest and keeps all toolbar items together. The edit button is already there; prepend `← → |` before it.

### Arrow Icons

The existing edit icon (`winze-edit-16.png` / `winze-edit-32.png`) is a lavender pencil on transparent background, loaded as a HiDPI `ImageDataProvider` pair. The nav arrows should match this style.

**Approach: Programmatic GC-drawn icons** — draw chevron arrows using SWT `GC` at startup. This avoids maintaining static PNGs and gives exact color control:

- **Color**: Amethyst `#9B8FE0` (same palette role as the edit icon — primary accent, used for links and active states per the brand guide)
- **Size**: 16×16 (1x) and 32×32 (2x) via `ImageDataProvider` for HiDPI, matching the `hidpi-image` pattern used by the edit icon
- **Shape**: Simple chevron arrow (`<` / `>`) — 2-pixel stroke at 1x, 4-pixel stroke at 2x. Clean and legible at toolbar size.
- **Antialiasing**: `SWT/ON` for smooth edges
- **Style**: `SWT/FLAT` toolbar — the buttons render with no border, matching the existing edit button. Hover/press states are handled by SWT's flat toolbar rendering.
- **Disabled state**: SWT automatically generates a greyed-out version of the image when `setEnabled(false)` is called on a `ToolItem`. No separate disabled icon needed.
- **Lifecycle**: The generated `Image` objects are long-lived (defonce delays, like the edit icon). Dispose in the shell-closed handler if needed, though SWT disposes all resources when the Display is disposed.

The drawing function belongs in `resources.clj` alongside other image resources (`edit-icon`, `tab-document-icon`, etc.).

### Key Bindings

- **Mod1+[** / **Mod1+]** (Cmd on macOS, Ctrl on others): Standard browser
  back/forward. Cmd+[/] is the macOS convention used by Safari, Xcode, etc.
- **Alt+Left/Right** as a secondary binding — VS Code, IntelliJ convention.
- **SWT key codes**: `SWT/ARROW_LEFT`, `SWT/ARROW_RIGHT`. Modifier: `SWT/MOD1`.
- **Note**: If the command palette keybinding system
  ([COMMAND-PALETTE-PLAN.md](COMMAND-PALETTE-PLAN.md)) is implemented first,
  these keybindings should be externalized into `default.keybinding` instead of
  registered in the Display filter. If search history nav is implemented first,
  inline Display filter wiring is fine and will be migrated to `.keybinding`
  files when the command palette plan lands.

### Button Enable/Disable State

Back and forward arrows should be visually disabled (greyed out) when navigation is not possible in that direction. Update after every history change (new search, back, forward, clear).

## Files to Modify

| File | Change |
|------|--------|
| `resources.clj` | Add `search-history` atom (vector + position), `restoring-history?` atom, history manipulation functions. Add `draw-chevron-image`, `chevron-hidpi-image`, `back-icon`, `forward-icon` (programmatic GC-drawn arrow icons in amethyst `#9B8FE0`). |
| `main_window.clj` | Add back/forward `ToolItem`s (with arrow icons, `SWT/FLAT` toolbar) to `setup-edit-toolbar!`, register Mod1+[/]/Alt+Left/Right (in Display key filter or `.keybinding` file — see Key Bindings note), wire `modify-text` to push history, add `navigate-back!`/`navigate-forward!`/`update-nav-buttons!` |
| `search.clj` | No changes expected — `results` is called the same way regardless of source |

## Risks / Open Questions

1. **Deduplication**: If the user types "foo", then "foobar", then backspaces to "foo" — should the second "foo" create a duplicate entry or reuse the existing one? Simplest: always push (duplicates are harmless and match browser behavior). Could deduplicate adjacent identical entries as a refinement.
2. **History size cap**: Unbounded vectors are fine for a desktop app session. Could cap at ~100 entries if desired, but unlikely to matter in practice.
3. **Interaction with search-scope (deferred)**: When scope filtering is added, should changing scope be a separate history entry or modify the current one? Defer this decision — scope is not yet implemented.
