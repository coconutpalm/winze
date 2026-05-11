# Live Search Home Page — Context

## Goal

Replace the static "Type to search…" placeholder on the live search tab with a useful home page that renders content from a project's `Plans/home.md` file.

## Current State

The live search tab (`main_window.clj:525-529`) initializes with `search/empty-page` — a minimal HTML page showing "Type to search…" in centered text. This page appears on startup and whenever the search query is cleared or fewer than 3 characters.

### Relevant Code Locations

- **Empty page**: `search.clj:289-299` — `empty-page` function returns static HTML placeholder.
- **Results pipeline**: `search.clj:386-421` — `results` function calls `empty-page` when query is too short (line 397).
- **Tab init**: `main_window.clj:525-526` — `custom-browser` widget initialized with `:text (search/empty-page)`.
- **File viewer**: `search.clj:348-370` — `file-page` renders markdown with metadata header and frontmatter.
- **File tabs**: `main_window.clj:118-168` — `open-tab!` creates closable tabs with Browser + StyledText (view/edit toggle via Cmd+E).
- **URL dispatch**: `main_window.clj:69-103` — `LocationListener` handles `winze:open-file?root=...&path=...` and `winze:search?q=...` pseudo-URLs.
- **Result cards**: `search.clj:260-265` — `result-card` renders clickable cards with file header + markdown body.
- **Roots API**: `core.clj:93-101` — `list-roots` returns `[{:root/uri :root/name :root/plans-dir :eid}]`.
- **Path resolution**: `search.clj:305-313` — `resolve-file-path` builds absolute path from root URI + relative path.

### File Viewer Capabilities

File tabs already support:
- Markdown rendering via `file-page` (commonmark-java → Hiccup → HTML)
- Metadata header with status indicator, clickable path, created date, metadata pills
- View/edit mode toggle (Browser ↔ StyledText, Cmd+E)
- Auto-scroll sync between modes
- File watcher auto-refresh on external changes
- Undo/redo and auto-save (1500ms debounce)

## Behavior Specification

### Live Search Tab as a File Browser Tab

The live search tab must be a full file browser tab — the same Composite wrapper that file tabs use (Browser + StyledText swapping). This means the live search tab has the same widget infrastructure as any file opened via `winze:open-file`, but with one key addition: the tab needs to know whether it currently holds an actual file or synthetic content (home page stub, search results). When holding synthetic content, edit mode (Cmd+E) must be disabled/suppressed.

### Single Root with Home File

When exactly one registered root has a `Plans/home.md` file, the home page renders that file in the live search tab's file browser — identical to opening a file (markdown rendering, metadata header, edit mode via Cmd+E). The tab tracks the file path so edit mode works normally.

### Multiple Roots with Home Files

When two or more roots have a `home.md` file, display them as search result cards (same styling as `result-card`), alphabetized by their H1 heading text. Clicking a card's title link opens the file in a new tab via the existing `winze:open-file` URL scheme. The live search tab is in synthetic-content mode — edit mode disabled.

### No Home Files

Fall back to a "Type to search…" placeholder with an explanatory note listing the full path where `home.md` could be created for each registered root. This helps discoverability — users may not know the feature exists. The tab is in synthetic-content mode — edit mode disabled.

### Refresh Behavior

**File mode (single `home.md`)**: The live search tab is a real file browser tab, so it participates in the existing file watcher refresh mechanism automatically — edits to `home.md` (external or via edit mode) trigger a re-render with no special logic.

**Re-evaluation of home state**: The home page should re-check for `home.md` files when:
- The search field is cleared (returning to home state)
- A root is registered or unregistered

This ensures the tab transitions correctly between `:file` and `:synthetic` modes as `home.md` files are created or removed. When a single `home.md` exists, the tab enters `:file` mode and the file watcher takes over from there.

**Edit mode on search text change**: Any activity that clears or changes the search text should automatically switch the live search tab back to view mode if it is currently in edit mode. The user is shifting focus to search — leaving edit mode active on stale content would be confusing and could cause accidental edits.

### Descriptive Tab Titles for File Tabs

File tabs use the first H1 heading (up to but not including the first punctuation character, trimmed) as the tab title. For example, `# Live Search Home Page — Context` becomes `Live Search Home Page`. The filename is still visible in the tab tooltip.

If the file has no H1, the tab title falls back to the basename.

The "Live search" tab keeps its fixed title — it is not a file tab in the normal sense.

**Live updates**: When the user is editing a file and modifies the H1 heading, the tab title updates on every keystroke. When a file is refreshed by the watcher (external edit), the tab title also updates.

### Live Search Refresh on File Changes

When the filesystem watcher fires a modify/rename event, and the live search tab is currently displaying search results or multi-root home cards (synthetic mode with visible file content), the results should refresh automatically if any displayed file was affected.

Approach: track the last query string and filters used for the current search results. When `on-file-changed` fires, if the live search tab is in synthetic mode and has an active query, re-run the search to pick up updated content. This reuses the existing search pipeline and avoids partial DOM manipulation.

For the multi-root home page case, re-call `home-page` and update the live search tab.

## Constraints

- The live search tab uses the same file browser tab infrastructure as file tabs (Composite with Browser/StyledText swapping).
- The file browser tab must track whether it holds an actual file or synthetic content. Edit mode (Cmd+E) is only available when backed by a real file. This distinction applies to the home page stub, search results, and the multi-root card view.
- Must not break the existing search flow: typing ≥3 chars should immediately show search results (synthetic content, no edit), replacing the home page.
- The H1 extraction for alphabetization should handle missing H1 gracefully (fall back to full file path).

## Related Work

- **Word cloud**: `Plans/dev/deferred/WORD-CLOUD-CONTEXT.md` — alternative empty-page enhancement. The home page feature takes precedence when index files exist; word cloud would be the fallback when no index files are found.
- **File viewer**: `Plans/complete/swt-ui/file-viewer/` — completed work establishing the view/edit toggle pattern.
- **File viewer header**: `Plans/complete/swt-ui/file-viewer-header/` — metadata header rendering in file tabs.
