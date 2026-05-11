# Markdown Wiki Links — Context

## Goal

Add wiki-style cross-linking to the Winze editor. `[[...]]` is a **creation
trigger** — typing it opens the content assist popup to select or create a
target, then rewrites the `[[...]]` into a standard `[title](wiki:uuid)` link.
The `[[...]]` form is never persisted; it's a transient editing affordance.

Two capabilities:

1. **`[[...]]` as a link creation trigger** — when typed in the editor, opens
   the content assist popup (same one used by `[text](` and Cmd+K). The user
   selects an existing page/heading or creates a new file. The `[[...]]` is
   then rewritten to `[page title](wiki:uuid)`.
2. **MOD1-click navigation** — MOD1-click on any link (standard markdown
   `[text](url)` or `wiki:uuid`) opens the target in a separate tab.

## How `[[...]]` Works

### Typing flow

```
User types: [[design deci
                         ↑ popup opens, seeded with "design deci"
                         ↓ semantic search shows matching pages/headings

User selects: "Editor Commands — Context"
                         ↓
[[design deci]] is replaced with:
[Editor Commands — Context](wiki:a1b2c3d4-e5f6-...)
```

### Trigger detection

The editor monitors for `]]` — when the closing brackets are typed, the
content between `[[` and `]]` becomes the seed text for the content assist
popup. If the user types `[[` and starts typing, the popup appears
immediately (same as the `[text](` trigger).

Alternatively, the popup opens on `[[` (not waiting for `]]`), and `]]` or
Enter confirms the selection. The user types freely to filter, and the popup
updates live. When they select a result, the entire `[[...]]` span (including
brackets) is replaced with the resolved link.

### What gets saved

The `[[...]]` form is **never saved to disk**. It is always rewritten before
the auto-save fires. The persisted form is standard markdown:

```markdown
[Editor Commands — Context](wiki:a1b2c3d4-e5f6-7890-abcd-ef1234567890)
```

This means:
- Every markdown viewer can display the link text
- The `wiki:uuid` resolves within Winze via Datalevin
- No custom syntax in the saved file

### Visual styling of `[[...]]` while typing

While the `[[...]]` is still in the editor (before resolution), it should be
visually distinct — styled with a **dotted underline** to signal "this is a
draft link that hasn't been resolved yet." This uses a different style from
resolved links (which use solid amethyst color):

```clojure
;; In type->style:
:inline/wiki-draft {:fg res/color-amethyst
                    :underline true
                    :underline-style SWT/UNDERLINE_DOT}
```

After the popup resolves the link, the `[[...]]` is replaced with a standard
`[text](wiki:uuid)` link and the styling switches to normal `:inline/link`.

### Creating a new file from `[[...]]`

If the content assist popup shows no matching results (or the user explicitly
chooses "Create new page"), the system:

1. **Derives a filename** from the typed text:
   - Slugify: lowercase, replace spaces with hyphens, remove special chars
   - Add `.md` extension
   - Place in the same directory as the current file (or a configurable default)
   - Example: `[[Design Decisions]]` → `design-decisions.md`

2. **Creates the file** with a populated H1 heading:
   ```markdown
   # Design Decisions
   ```

3. **Indexes the file** — the filesystem watcher picks up the new file and
   indexes it, creating `:file/*` and `:wiki/*` entities with a UUID.

4. **Rewrites the `[[...]]`** into `[Design Decisions](wiki:<new-uuid>)` using
   the newly generated UUID.

5. **Optionally opens the new file** in a tab so the user can start editing it
   immediately.

### Content assist popup behavior for `[[...]]`

The content assist popup (from the editor-commands plan) handles `[[...]]` the
same way it handles `[text](` and Cmd+K, with one addition: a "Create new page"
option at the bottom of the results when no exact match exists.

Search tiers (same as the `[text](` trigger):
1. **Semantic search** — embed the typed text, find similar chunks
2. **Page title search** — substring match against `:file/title` (first H1)
3. **Filename search** — fallback for files without H1

The typed text between `[[` and `]]` (or `[[` and the cursor position if `]]`
hasn't been typed yet) is the search seed.

## Current State

### Browser (view mode) — links already work

| Feature | Status |
|---------|--------|
| Standard `.md` relative links → `winze:open-file?` | Done (`hiccup.clj:rewrite-local-link`) |
| Anchor/fragment links (`#heading`) | Done (`hiccup.clj:render-heading` adds `id`) |
| `winze:search?` tag links | Done (`main_window.clj:custom-browser`) |
| External `https://` links | WebKit handles natively |
| `wiki:uuid` link resolution | **Not implemented** (editor-commands plan) |

### StyledText editor — no link interaction

| Feature | Status |
|---------|--------|
| Link spans styled (amethyst color) | Done (`md_theme.clj:find-inline-spans`, type `:inline/link`) |
| Link destination stored per span | **Not implemented** — regex captures text only |
| Mouse click on link → navigation | **Not implemented** — no `MouseListener` |
| Cursor change on link hover | **Not implemented** |
| MOD1 modifier detection | **Not implemented** |
| `[[...]]` trigger for content assist | **Not implemented** |
| Dotted underline for draft wiki links | **Not implemented** |

## Files to Modify

| File | Change |
|------|--------|
| `winze-server/src/llm_memory/ui/md_theme.clj` | Add `:inline/wiki-draft` pattern + dotted underline style; extract + store link destinations for all link types |
| `winze-server/src/llm_memory/ui/markdown_editor.clj` | Add MOD1-click handler; add `[[` trigger for content assist; add link rewrite logic |
| `winze-server/src/llm_memory/ui/main_window.clj` | Extract `open-file-in-tab!` helper; add `wiki:` URL handling in `custom-browser` |
| `winze-server/src/llm_memory/ui/content_assist.clj` | Add "Create new page" option to results (editor-commands plan owns this file) |
| `clj-llm-memory/src/llm_memory/tools.clj` | Possibly: expose file-lookup-by-stem for wiki-link resolution |

## Dependencies

- **Content assist popup** — from the editor-commands plan
  ([EDITOR-COMMANDS-CONTEXT.md](EDITOR-COMMANDS-CONTEXT.md)). The `[[...]]`
  trigger opens the same popup used by `[text](` and Mod1+K.
- **Wiki link registry** — `:wiki/*` entities in Datalevin, also from the
  editor-commands plan. Needed for UUID generation and resolution.
- **SWT MouseListener** — for MOD1-click detection on `StyledText`.
- **SWT Cursor** — `SWT/CURSOR_HAND` for hover feedback.
- **SWT `UNDERLINE_DOT`** — for the dotted underline on draft `[[...]]` links.
- **`java.awt.Desktop`** — for opening external URLs in the system browser.

## Design Decisions

### Why `[[...]]` is a creation trigger, not persisted syntax

Persisting `[[...]]` in files has problems:
- **Non-standard**: most markdown renderers don't understand `[[...]]`
- **Fragile**: stem-based resolution breaks on file renames
- **Duplicative**: we already have `wiki:uuid` for stable links

Making `[[...]]` a transient trigger gives us:
- **Standard markdown on disk**: every file contains only `[text](url)` links
- **Stable links**: `wiki:uuid` survives file and heading renames
- **Clean separation**: `[[...]]` = creation affordance, `wiki:uuid` = persisted
  link format

### Why dotted underline for draft links

The dotted underline signals "this isn't a real link yet" — it's visually
distinct from:
- Solid amethyst styling for resolved `[text](wiki:uuid)` links
- Strikethrough for broken links

The dotted underline is a common UI convention for "pending" or "unresolved"
state (e.g., spelling suggestions, unresolved references in IDEs).

### Why create new files from `[[...]]`

This is the Obsidian/Logseq workflow: type `[[New Topic]]`, and if the page
doesn't exist, it's created on the spot. This makes wiki linking a zero-
friction way to create new documents. The alternative (requiring the user to
create the file first, then link to it) adds friction that discourages linking.

### Why derive filename from typed text, not use the UUID

Filenames should be human-readable for git, file explorers, and command-line
use. `design-decisions.md` is better than `a1b2c3d4.md`. The UUID is the
internal link target; the filename is for humans.

### MOD1-click scope

MOD1-click navigates. Plain click edits as normal. This matches VS Code / IDE
convention and avoids accidental navigation while editing. See
[COMMAND-PALETTE-CONTEXT.md](COMMAND-PALETTE-CONTEXT.md) for the MOD1
convention.

### External URL handling

For `https://`, `http://`, and `mailto:` links, use `java.awt.Desktop/browse`
to open the system browser/mail client. Do not open external URLs inside the
SWT Browser widget.

## Related Work

### Active
- **editor-commands** — introduces the `wiki:uuid` protocol, content assist
  popup, link preview, and wiki link registry. See
  [EDITOR-COMMANDS-CONTEXT.md](EDITOR-COMMANDS-CONTEXT.md). This plan depends
  on the content assist popup and wiki registry from that plan.
- **command-palette** — the keybinding system. MOD1-click uses `SWT/MOD1`.
  See [COMMAND-PALETTE-CONTEXT.md](COMMAND-PALETTE-CONTEXT.md).
- **edn-tokenizers** — externalized syntax highlighting. Orthogonal. See
  [EDN-TOKENIZERS-CONTEXT.md](EDN-TOKENIZERS-CONTEXT.md).

### Completed
- `complete/swt-ui/md-link-rewrite/` — relative `.md` link → `winze:open-file?` rewriting
- `complete/anchor-nav/` — heading `id` attributes for fragment navigation
- `complete/swt-ui/styledtext-editor/` — StyledText widget creation + theme wiring
- `complete/swt-ui/md-theme/` — inline span detection (link regex)
- `complete/swt-ui/file-viewer/` — `open-tab!` and file-viewer tab architecture
- `complete/search/tag-search/` — `winze:search?` URL scheme in `custom-browser`

## Risks

- **Content assist dependency**: The `[[...]]` trigger requires the content
  assist popup from the editor-commands plan. If that plan isn't complete,
  the `[[...]]` trigger can't be fully implemented. Mitigation: implement
  the MOD1-click navigation and link span tracking first (no content assist
  dependency), add the `[[...]]` trigger when the popup is available.
- **Auto-save timing**: The `[[...]]` must be rewritten before auto-save fires
  (1.5s debounce). If the user types `[[text]]` and walks away, the content
  assist popup should resolve or cancel, and the `[[...]]` should either be
  rewritten or removed before save. Mitigation: the auto-save callback checks
  for unresolved `[[...]]` patterns and either strips them or leaves them as
  plain text (not as broken wiki syntax).
- **Filename collisions**: `[[Design Decisions]]` → `design-decisions.md` might
  already exist. The creation flow should detect this and offer to link to the
  existing file instead.
- **Thread safety** — `link-spans` atom is written by `apply-theme!` (UI thread)
  and read by the `MouseListener` (also UI thread) — no concurrency issue.
