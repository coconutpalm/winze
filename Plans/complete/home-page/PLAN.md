# Live Search Home Page — Plan

## Step 1 — Promote live search tab to a file browser tab

The live search tab is currently a bare `custom-browser` widget. Change it to use the same Composite wrapper as file tabs (`open-tab!` pattern): Browser for view mode + StyledText for edit mode, with Cmd+E toggle.

Add a concept of **content mode** to the file browser tab — a flag (e.g., an atom or entry in the tab's props map) indicating whether the tab currently holds:
- `:file` — backed by a real file on disk (edit mode allowed)
- `:synthetic` — generated HTML with no backing file (edit mode suppressed)

When in `:synthetic` mode, the Cmd+E handler is a no-op (or shows a brief status message). When switching to `:file` mode (e.g., single home file loaded), the tab tracks the file path and enables the full edit/save/watch pipeline.

## Step 2 — Discover home files across roots

Add a function `home-files` in `search.clj` that:

1. Calls `(core/list-roots (server/store))` to get all registered roots.
2. For each root, resolves the absolute path to `Plans/home.md` (using root URI + `:root/plans-dir`).
3. Checks which files exist on disk (`.exists` / `slurp`-safe).
4. Returns a vector of `{:root/uri :root/name :rel-path :abs-path}` maps — one per root that has a `home.md`.

## Step 3 — Extract H1 heading

Add a helper `extract-h1` that takes markdown text and returns the first `# ...` heading as plain text, or `nil`. Used for:
- Alphabetizing cards in the multi-root home page case
- Generating descriptive file tab titles (Step 3b)

Implementation: simple regex `#"^# +(.+)"` on the first matching line — no need for a full parse.

## Step 3b — Descriptive file tab titles

Simplify `tab-title` to take a filename and markdown text:

1. Extract H1 via `extract-h1`. If present, truncate at first punctuation (`.`, `,`, `:`, `;`, `—`, `–`, `-`, `(`, etc.), trim, and return just the truncated H1 (no filename prefix). The filename is available in the tooltip.
2. If no H1, return the basename alone.

Update the `winze:open-file` handler to call `tab-title`. The "Live search" tab is unaffected — its title is hardcoded.

**Live tab title updates**: In edit mode, the `modifyListener` already fires on every keystroke. After restyling, re-extract the H1 from the editor text and update the `CTabItem` title if it changed. On file-watcher refresh (external edit in view mode), also update the tab title after re-rendering the HTML.

## Step 3c — Live search refresh on file watcher events

Track the last search state in an atom: `{:query "..." :filters {...}}` (set when a search completes, cleared when returning to home/empty page).

Extend `on-file-changed` in `main_window.clj`: after handling open-file-tab refresh, check if the live search tab is in synthetic mode. If so:
- **Search results visible** (query atom is non-nil): re-run the search with the saved query/filters and update the browser HTML.
- **Multi-root home cards visible** (no query, but synthetic mode with multiple home files): re-call `home-page` and update via `set-live-search-content!`.

This reuses the existing search pipeline — no partial DOM updates needed.

## Step 4 — Render home page content

Add a function `home-page` in `search.clj` that returns `{:mode :file/:synthetic, :html "...", :abs-path "..." :root-uri "..." :rel-path "..."}`:

- **Single home file**: Read the markdown, call `file-page` with metadata from `file-metadata-by-path` (if indexed). Return `{:mode :file, :html ..., :abs-path ..., :root-uri ..., :rel-path ...}` so the tab can wire up edit mode and file watching.
- **Multiple home files**: For each file, read the markdown, extract H1 for sorting. Render as result cards (reuse `result-card` or a similar card layout with `winze:open-file` links). Alphabetize by H1 text (falling back to full file path). Return `{:mode :synthetic, :html ...}`.
- **No home files**: Return `nil` (caller falls back to `empty-page`).

## Step 5 — Update empty-page with home file hint

Modify `empty-page` to accept the list of registered roots and include a subtle note below "Type to search…" listing the full path where `home.md` could be created for each root. For example:

> Create a home page at:
> `/Users/me/project-a/Plans/home.md`
> `/Users/me/project-b/Plans/home.md`

Style as muted secondary text so it doesn't dominate the page but is discoverable. Use `list-roots` to resolve each root's plans directory into an absolute path.

## Step 6 — Wire home page into the tab lifecycle

Modify the two call sites that currently show `empty-page`:

1. **Tab init** (`main_window.clj:525-526`): After creating the file browser tab, call `home-page`. If it returns a `:file` result, set the tab to file mode with the home file's path and HTML. If `:synthetic`, set HTML and mark synthetic. If `nil`, show `empty-page` (synthetic).
2. **Search clear** (`search.clj:395-397`): When the query drops below 3 chars, re-evaluate `home-page` and update the tab's mode and content accordingly. This ensures newly added/removed home files are picked up.

In both cases, transitioning from `:file` → `:synthetic` (or vice versa) must update the content-mode flag so Cmd+E behaves correctly.

**File watcher participation**: When the tab enters `:file` mode (single `home.md`), it registers with the existing file watcher infrastructure automatically — just like any file tab opened via `winze:open-file`. External edits to `home.md` trigger a re-render. When transitioning back to `:synthetic` mode (search results, or `home.md` deleted), the watcher is deregistered. No custom refresh logic is needed for the `:file` case.

**Force view mode on search text change**: In the `modify-text` handler, before evaluating the query or showing results, check whether the live search tab is currently in edit mode and switch it back to view mode if so. This applies to all search text changes — typing, clearing, pasting — so the tab is always in view mode when displaying new content.

## Step 7 — Visual verification

1. Start with zero roots having `home.md` → verify "Type to search…" appears with per-root path hints.
2. Create `Plans/home.md` in one root → verify it renders as a full file page, Cmd+E opens edit mode.
3. Create `Plans/home.md` in a second root → verify both appear as alphabetized result cards, Cmd+E is disabled.
4. Remove one → verify it reverts to single-file mode with edit support.
5. Clear search after typing a query → verify home page reappears correctly.
6. Click a card title in multi-root mode → verify it opens a file tab.
7. Open a file with an H1 → verify tab title shows just the H1 prefix (no filename), tooltip shows filename.
8. Open a file with no H1 → verify tab title is the basename.
9. Enter edit mode, change the H1 → verify tab title updates on each keystroke.
10. Edit a file externally while its tab is open in view mode → verify tab title updates on watcher refresh.
11. Modify a file while search results are visible → verify results refresh automatically.
12. Rename a file while multi-root home cards are visible → verify cards refresh.
13. Screenshot each state via `screenshot-widget!`.

## Step 8 — Edge cases

- Root registered but plans directory doesn't exist yet → skip gracefully.
- `home.md` exists but is empty → render empty file page (header only), edit mode still available.
- H1 missing from a file → sort by full file path instead.
- File deleted while app is running → next home-page call won't find it (reads fresh each time).
- Search results displayed → tab is in `:synthetic` mode, Cmd+E suppressed.
- User edits `home.md` via edit mode or externally → file watcher refreshes the view automatically (standard file browser tab behavior).
