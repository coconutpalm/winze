# Editor Commands — Context

## Goal

Add a comprehensive set of editor commands to the Winze StyledText markdown
editor, bringing it to feature parity with the core editing capabilities of
Obsidian, Notion, and Logseq. This turns the editor from a plain text field with
syntax highlighting into a proper markdown editing environment.

The command palette (see [COMMAND-PALETTE-CONTEXT.md](COMMAND-PALETTE-CONTEXT.md))
is the primary discovery mechanism and provides the scoped keybinding system.
Frequently-used commands also get direct keyboard shortcuts via externalized
keybinding EDN files.

## Current State

### What exists

| Feature | Location | Status |
|---------|----------|--------|
| Syntax highlighting (headings, bold, italic, code, links, blockquotes, code-block tokens) | `md_theme.clj` | Done |
| Undo / Redo (Mod1+Z / Mod1+Shift+Z) | `markdown_editor.clj:161-185` | Done |
| Debounced auto-save (1.5s) | `markdown_editor.clj:99-122` | Done |
| View / Edit toggle (Mod1+E) | `main_window.clj` | Done |
| Scroll sync between view ↔ edit | `main_window.clj` | Done |
| Search result cards (HTML rendering with file header + markdown body) | `search.clj:271-276` | Done |
| Wiki-link parsing + Ctrl-click navigation | (in progress, wiki-links plan) | Active |
| Search history navigation (Cmd+[/]) | (in progress) | Active |

### What does not exist

- No formatting commands (bold, italic, heading, list, etc.)
- No insert commands (link, code block, table, horizontal rule, etc.)
- No find/replace
- No line operations (move, delete, duplicate, indent/outdent)
- No command registry — keybindings are inline `condp` in `key-down` listener
- No link-insertion assistance (autocomplete for local files/anchors)
- No hover/cursor preview for links
- No content assist popup

## Cross-Editor Consensus

Obsidian, Notion, and Logseq all implement these core categories. Obsidian is
the closest analogue (file-based markdown, command palette, no block-level
database). Notion's block model and Logseq's outliner model add features we
don't need (block references, synced blocks, database views), but their text
editing commands are identical in spirit.

### Universal features (all three apps)

- Toggle inline formatting: bold, italic, strikethrough, inline code, highlight
- Set heading level (H1–H6)
- Toggle line-prefix blocks: bullet list, numbered list, checkbox, blockquote
- Insert link (with search/autocomplete for local targets)
- Find in file, find & replace
- Indent / outdent
- Move line up / down

### Expected features (two of three apps)

- Insert: horizontal rule, code block, table skeleton, callout, math block
- Delete line, duplicate line, select line
- Toggle HTML comment (`<!-- ... -->`)
- Link preview on hover (Obsidian's "Page Preview" plugin, Logseq's block
  preview)

### Skipped features (app-specific or out of scope)

- Block/page embedding (Logseq `![[...]]`) — we use tabs + links instead
- Block references (Logseq `((block-ref))`) — outliner-specific
- Database views (Notion tables/boards) — not a markdown editor feature
- Synced blocks (Notion) — requires cross-file sync infrastructure
- Slash commands as a UI trigger — we use the command palette instead
- Per-block color (Notion) — not standard markdown
- Multi-cursor editing — complex in SWT StyledText, low ROI for markdown
- Templates — separate feature, not core editing

## The `wiki:` URL Protocol

### Design

Wiki links use a custom URL protocol: `wiki:<uuid>`. The UUID is a stable
identifier for a heading (anchor) or file in the Datalevin store. Links in
markdown look like standard links with a `wiki:` URL:

```markdown
[Design Decisions](wiki:a1b2c3d4-e5f6-7890-abcd-ef1234567890)
```

### Why a custom protocol instead of `file#anchor` paths

| Aspect | `file.md#slug` | `wiki:uuid` |
|--------|---------------|-------------|
| Survives heading renames | Only with propagation | Yes — UUID is stable |
| Survives file renames | No — path breaks | Yes — UUID is stable |
| Human-readable in raw markdown | Yes | No (but display text is) |
| Works in GitHub/VS Code | Yes | No (but standard `[text](url)` syntax) |
| Needs a database to resolve | No | Yes |
| Rename propagation needed | Yes (expensive cross-file writes) | No |

The `wiki:uuid` approach **eliminates rename propagation entirely**. When a
heading or file is renamed, only the database entry is updated — the UUID
remains stable, and all links continue to resolve. This is the same principle
as Logseq's block UUIDs, but we use `wiki:` URLs within standard markdown link
syntax so the *display text* remains human-readable.

### Trade-off: portability

Raw `wiki:uuid` URLs are opaque outside Winze. However:
- The **display text** (`[Design Decisions]`) is always human-readable
- When exporting or rendering in other tools, `wiki:` URLs can be
  post-processed into relative paths using the database
- View mode (Browser) already resolves all links through `custom-browser`'s
  `on-changing` handler — it can resolve `wiki:` just as it resolves `winze:`

### Resolution flow

```
User clicks or Ctrl-clicks a wiki:uuid link
    ↓
Look up UUID in Datalevin:
  - :wiki/id = uuid → found a heading anchor
    - Get :wiki/file → :file/id → :file/path, :root/uri
    - Get :wiki/slug → heading anchor within the file
    - Navigate to file#slug
  - Not found as :wiki/id → try :file/id = uuid
    - Navigate to file (no anchor)
  - Not found at all → link is broken
    - Show warning, do nothing (or offer to search for similar content)
```

### Graceful degradation

When a heading is deleted (no similarity match), the `:wiki/*` entity is
removed but the `:file/*` entity remains. The UUID lookup falls through from
`:wiki/id` to `:file/id`:
- If the UUID was originally a heading → the heading entity is gone, but the
  *file* that contained it still exists. Navigation lands on the file.
- If the file itself is deleted → the link is broken. The display text still
  tells the user what was being referenced.

This two-tier lookup (heading → file fallback) means links **never silently
break** — they degrade from heading-level to file-level to broken, each with
a clear visual indicator.

### UUID generation

Each heading gets a deterministic UUID based on `file-id + slug` at first
indexing. When a heading is renamed but semantically matched (via chunk
similarity), the original UUID is preserved — only the `:wiki/slug` and
`:wiki/text` are updated. This is what makes the link stable.

When a heading is deleted and a new one appears with no match, a new UUID is
generated. The old UUID's entity is removed; links to it degrade to file-level.

### Sidecar backup — catastrophic store recovery

The Datalevin store can be lost (corruption, accidental deletion, LMDB lock
failure). Without a backup, rebuilding the store regenerates *new* deterministic
UUIDs from current `file-id + slug`, but if any file was renamed since the
original UUIDs were created, the new UUIDs won't match the old ones already
embedded in `wiki:uuid` links across markdown files. This would silently break
all cross-links.

**Solution**: A sidecar EDN file (`wiki-registry.edn`) at each root's plans
directory stores the authoritative UUID→slug→file mapping:

```clojure
;; Plans/.wiki-registry.edn
{"a1b2c3d4-..." {:file-id "root::path.md" :slug "design-decisions"
                  :text "Design Decisions" :level 2}
 "e5f6a7b8-..." {:file-id "root::path.md" :slug "overview"
                  :text "Overview" :level 2}
 ...}
```

**Lifecycle**:
- **Write**: After `index-file!` transacts wiki entities, the full
  UUID→slug map for that root is written to `wiki-registry.edn` as a
  single `spit` (atomic from the reader's perspective — partial reads
  are not a concern since the file is only read during recovery).
- **Read**: On store recovery (rebuild from scratch), if `wiki-registry.edn`
  exists, it is loaded first and its UUIDs are used instead of generating new
  deterministic ones. This preserves link stability across store rebuilds.
- **Git**: The file is dotfile-prefixed (`.wiki-registry.edn`) so it doesn't
  clutter the Plans directory. It should be committed to git alongside the
  markdown files — the registry travels with the content.

**Why per-root, not global**: Each root has its own Plans directory and its own
set of UUIDs. A global file would require tracking which root owns which UUID.

## Wiki Link Registry

### Schema

```clojure
;; Wiki link entities — heading anchors within files
:wiki/id    {:db/valueType :db.type/string  :db/unique :db.unique/identity}
  ;; UUID string (e.g. "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
:wiki/file  {:db/valueType :db.type/ref}
  ;; ref to :file/id entity
:wiki/slug  {:db/valueType :db.type/string}
  ;; current heading slug (e.g. "design-decisions") — updated on rename
:wiki/text  {:db/valueType :db.type/string}
  ;; current heading text (e.g. "Design Decisions") — updated on rename
:wiki/line  {:db/valueType :db.type/long}
  ;; 0-based line number in the source file
:wiki/level {:db/valueType :db.type/long}
  ;; heading level (1-6)

;; Page title — first H1 content, full text (not truncated)
:file/title {:db/valueType :db.type/string}
```

### Rename detection: chunk-level embedding similarity

When a file is re-indexed, compare old and new headings using the same
embedding similarity approach as `index.clj:match-fuzzy-renames` (lines
132-180), operating on chunks within a file instead of files within a root.

```
File B modified (watcher triggers re-index)
    ↓
1. Snapshot existing state:
   - Query :wiki/* entities for file B → old headings (with UUIDs + slugs)
   - Query :chunk/vec vectors for file B → old chunk embeddings
    ↓
2. Re-index file B (index-file! already does this):
   - Parse new headings → extract new slugs
   - Split into new chunks → embed → get new chunk vectors
    ↓
3. Match old chunks to new chunks by embedding similarity:
   - For each new chunk, compute cosine similarity to each old chunk
   - Greedy best-match (same algorithm as match-fuzzy-renames)
   - Threshold: same rename-similarity-threshold (0.6)
    ↓
4. Classify heading changes:
   - Chunk matched with same slug → heading unchanged, keep UUID
   - Chunk matched with different slug → heading RENAMED
     → Keep the same UUID, update :wiki/slug and :wiki/text
   - Old chunk not matched → heading DELETED
     → Remove :wiki/* entity; links degrade to file-level
   - New chunk not matched → heading ADDED
     → Generate new UUID, create :wiki/* entity
    ↓
5. Transact updated :wiki/* entities
   (NO cross-file propagation needed — UUIDs are stable)
```

### Why this is dramatically simpler than path-based propagation

With `file#slug` links, heading renames required:
1. Detecting the rename
2. Scanning all files for links containing the old slug
3. Rewriting those files
4. Re-indexing the modified files
5. Guarding against infinite loops

With `wiki:uuid` links, heading renames require:
1. Detecting the rename
2. Updating the `:wiki/slug` and `:wiki/text` in Datalevin

That's it. No cross-file writes, no loop prevention, no propagation failures.
The UUID in the markdown never changes.

### Graceful degradation detail

| Scenario | UUID resolves to | Navigation result |
|----------|-----------------|-------------------|
| Heading exists | `:wiki/*` entity | Open file, scroll to heading |
| Heading deleted, file exists | `:wiki/*` gone, `:file/*` exists | Open file (no scroll) |
| File deleted | Neither found | Show "broken link" indicator |
| Heading renamed (matched) | Same `:wiki/*` UUID, updated slug | Open file, scroll to new heading |
| Heading renamed (unmatched) | Old `:wiki/*` removed | Falls to file-level |

## Link Insertion Design

### Content assist popup architecture

The content assist popup is a **custom SWT `Composite` that renders HTML
snippets** in mini `Browser` widgets for each result row. This is necessary
because search result cards contain rich content (file headers with status
indicators, metadata pills, markdown body preview) that cannot be rendered
with plain `Label` or `Table` widgets.

**Why HTML rendering in the popup**: The existing `result-card` function
(`search.clj:271-276`) already generates Hiccup for search results with
file path, status, relevance, pills, and a markdown body preview. Reusing
this rendering in the popup means:
- Consistent visual design between search results and link assist
- No duplicate rendering logic
- Rich preview content (formatted markdown, metadata) in each row

**Widget structure**:
```
Shell (SWT.TOOL | SWT.ON_TOP | SWT.NO_TRIM)
└── Composite (vertical FillLayout)
    ├── Text (search field)
    └── ScrolledComposite
        └── Composite (results container)
            ├── Browser (mini, ~80px tall — result card 1) [selected]
            ├── Browser (mini — result card 2)
            ├── Browser (mini — result card 3)
            └── ...
```

Each result row is a small `Browser` widget rendering a single result card's
HTML. The selected row gets a highlight border/background. This approach is
heavier than a plain `List` but avoids reimplementing the rich card layout in
native SWT widgets.

**Performance note**: SWT `Browser` widgets are heavyweight. Limit the visible
results to ~8-10 rows and reuse/recycle `Browser` instances when scrolling.
On macOS, WebKit is native and lightweight enough for this use case.

### Interactive link autocomplete on `(`

When the user types the opening `(` of a markdown link `[text](`, the editor
pops up the content assist popup. The popup is **prepopulated using semantic
search from the link text itself** (the text between the square brackets). For
example, typing `[design decisions](` would immediately show semantically
similar chunks rendered as result cards.

When the user starts typing in the popup search field, it switches to:
1. **Page title search** — match against the first full H1 line of each file
   (the page title, not the truncated tab title)
2. **Filename search** — fallback when a file has no H1 page title

### HTTP/HTTPS mode switch

If the user types `http` or `https` between the parentheses (i.e., they're
linking to an external URL rather than a local wiki target), the popup
**switches to Google search mode**:

- The search field becomes a Google search query
- Results are fetched from Google (via web search) and displayed as simplified
  cards (title + URL + snippet)
- Selecting a result inserts the URL into the parentheses
- This helps the user find the exact URL they want without leaving the editor

**Detection**: Watch for the character sequence. When the text after `(` starts
with `http://` or `https://`, switch modes. When the user backspaces past the
protocol prefix, switch back to wiki mode.

**Implementation**: Use the existing `WebSearch` tool infrastructure or a
direct HTTP request to a search API. Display results as simplified HTML cards
(same popup widget, different content).

### Dedicated "Insert Link" command (Cmd+K)

Opens the same content assist popup without requiring the user to type
`[text](`. If text is selected, it becomes the link text and seeds the
semantic search. If no selection, the popup opens with empty search.

When the user selects a local target:
- The editor inserts `[display](wiki:uuid)` 
- Display text = selected text, or the target's page title if no selection

When the user selects an external (Google) result:
- The editor inserts `[title](https://url)`

## Link Preview on Hover / Cursor

### Behavior

When the mouse hovers over a `wiki:uuid` link (or the text cursor moves into
one), show a **preview popup** displaying the linked chunk's content — rendered
as the same search result card used in the main search view.

This is similar to:
- Obsidian's "Page Preview" core plugin (hover to see page content)
- Logseq's block reference preview
- VS Code's hover provider for documentation

### Preview popup design

```
┌─────────────────────────────────────────────┐
│ ✓ complete/anchor-nav/CONTEXT.md  (85%)     │  ← file header
│ #anchor-nav                                 │  ← metadata pills
│                                             │
│ ## Goal                                     │  ← rendered markdown body
│ Add heading `id` attributes for fragment    │
│ navigation in the Browser file viewer...    │
└─────────────────────────────────────────────┘
```

**Widget**: Same `Shell` approach as the content assist popup, but with a
single `Browser` widget rendering one result card. Positioned near the link
(below the line if space permits, above if near the bottom).

**Data source**: Look up the `wiki:uuid` in Datalevin:
1. Get the `:wiki/*` entity → file path + chunk slug
2. Query the `:chunk/text` for that slug
3. Query file metadata (status, group, tags, etc.)
4. Render using `result-card` (same as search results)

### Trigger and dismiss

- **Mouse hover**: Show preview after a short delay (~300ms) when the mouse is
  over a link span. Dismiss when the mouse moves away from both the link and
  the preview popup.
- **Cursor position**: Show preview when the text cursor (caret) is inside a
  link span. Dismiss when the cursor moves outside the link. This supports
  keyboard-only navigation.
- **Escape**: Always dismisses the preview.
- **Click**: Clicking inside the preview could navigate to the target (optional
  enhancement).

### Interaction with Ctrl-click

Ctrl-click navigates to the link target (existing wiki-links plan behavior).
The preview popup is for *reading* without navigating. Both can coexist:
- Hover/cursor → preview (read in place)
- Ctrl-click → navigate (open in tab)

## Command Registry and Keybindings

The command registry, scoped keybinding system, and command palette UI are
defined in [COMMAND-PALETTE-CONTEXT.md](COMMAND-PALETTE-CONTEXT.md). Editor
commands register into that system and their keybindings are externalized
in `resources/keybindings/editor.keybinding`.

### Editor command categories

| Category | Commands |
|----------|----------|
| `:formatting` | Bold, italic, strikethrough, code, highlight |
| `:heading` | H1–H6, toggle heading cycle |
| `:list` | Bullet, numbered, checkbox, indent, outdent |
| `:block` | Blockquote, toggle comment |
| `:insert` | Link, horizontal rule, code block, table, callout, math block, date, time |
| `:line` | Move up/down, delete, duplicate, select |
| `:find` | Find, find & replace |

Heading-level folding is a separate work item — see
[HEADING-FOLDING-CONTEXT.md](HEADING-FOLDING-CONTEXT.md).

All editor commands use `:when {:in :editor}` in their keybindings and are
scoped to the `:editor` focus context.

## Files to Modify

| File | Change |
|------|--------|
| `winze-server/src/llm_memory/ui/commands.clj` | **Extend** — add text manipulation primitives and editor command registrations (file created by command palette plan) |
| `winze-server/src/llm_memory/ui/markdown_editor.clj` | Replace inline keybinding dispatch with registry lookup; add `(` trigger for content assist; add hover/cursor preview |
| `winze-server/src/llm_memory/ui/find_replace.clj` | **New** — find/replace overlay composite widget |
| `winze-server/src/llm_memory/ui/content_assist.clj` | **New** — content assist popup: custom Composite with HTML-rendering Browser rows, search field, mode switching (wiki/Google) |
| `winze-server/src/llm_memory/ui/link_preview.clj` | **New** — hover/cursor preview popup (single Browser widget rendering a result card) |
| `winze-server/src/llm_memory/ui/main_window.clj` | Wire command palette to registry; add `wiki:` URL handling in `custom-browser` |
| `winze-server/src/llm_memory/ui/search.clj` | Extract `result-card` and `page-css` as public functions for reuse by content assist and link preview |
| `clj-llm-memory/src/llm_memory/store/datalevin.clj` | Add `:wiki/*` schema + `:file/title` |
| `clj-llm-memory/src/llm_memory/index.clj` | Extract headings during indexing; UUID generation; chunk-level similarity for rename detection |
| `clj-llm-memory/src/llm_memory/chunk.clj` | Expose heading extraction as a reusable function (already has `slugify`) |

## Dependencies

- **SWT `StyledText`** — `replaceTextRange`, `getSelectionRange`, `setSelection`,
  `getLineAtOffset`, `getOffsetAtLine`, `getLine`, `getLineCount` — all the
  primitives for text manipulation. Already available.
- **SWT `Browser`** — for rendering HTML result cards in the content assist
  popup and link preview. Already used extensively in search results and file
  viewer.
- **SWT `Composite`** — for the find/replace bar and content assist popup
  container.
- **SWT `Shell`** — for floating popup windows (content assist, link preview).
- **Datalevin** — schema migration for `:wiki/*` entities and `:file/title`.
  Datalevin supports additive schema changes without migration scripts.
- **`chunk.clj:slugify`** — reuse for wiki slug generation (already exists).
- **`hiccup.clj:heading-slug`** — similar to `slugify` but GFM-flavored. Need
  to align on one canonical slugification algorithm.
- **`index.clj:cosine-similarity`** — already exists, used for file rename
  detection. Reuse for chunk-level similarity in heading rename detection.
- **`index.clj:match-fuzzy-renames`** — the pattern to follow. The heading
  rename matcher is the same algorithm applied at chunk granularity.
- **`search.clj:result-card`** — reuse for content assist rows and link preview.
  Currently private (`defn-`); make public.
- **`search.clj:page-css`** — reuse for styling HTML in popup Browser widgets.
  Currently private; make public.
- **Embedder** — already loaded as part of the store. Heading rename detection
  uses existing chunk embeddings (no extra embedding calls needed).
- **`java.util.UUID`** — for generating deterministic UUIDs (e.g.,
  `UUID/nameUUIDFromBytes` on `(str file-id "#" slug)`).

## Design Decisions

### Why `wiki:uuid` instead of `file#anchor` paths

The `wiki:uuid` protocol eliminates cross-file rename propagation entirely.
When a heading or file is renamed, only the Datalevin entity is updated.
Every link containing that UUID continues to resolve. This is the single
biggest simplification over the previous design.

The trade-off is portability: `wiki:uuid` URLs don't resolve outside Winze.
But the display text is always human-readable (`[Design Decisions]`), and
export tooling can post-process `wiki:` URLs into relative paths.

### Why a custom Composite with Browser widgets for content assist

The content assist popup needs to display rich search result cards — the same
cards shown in the main search view. These include:
- Status indicators (colored dots)
- File paths as clickable links
- Metadata pills (group, tags, related)
- Rendered markdown body preview

SWT `Table` or `List` widgets can only display plain text (or with custom
draw, simple icons). Reimplementing the card layout in native SWT would be
substantial work and produce an inconsistent visual design.

Using mini `Browser` widgets per row reuses the existing Hiccup → HTML
rendering pipeline (`result-card` + `page-css` from `search.clj`), giving
pixel-identical results with no new rendering code. The cost is heavier
widgets, mitigated by limiting visible rows to ~8-10 and recycling instances.

### Why Google search for http/https URLs

When the user is inserting an external link, they know the *content* they want
to link to but may not know the exact URL. Switching the content assist popup
to Google search mode lets them find the URL without leaving the editor — a
workflow that no mainstream markdown editor offers.

Detection is simple: watch for `http://` or `https://` at the start of the
text after `(`. The same popup widget handles both modes; only the data source
changes.

### Why a command registry instead of hardcoded keybindings

The current approach (inline `condp` in the `key-down` listener) doesn't scale.
A registry provides: (1) command palette integration for free, (2) user-
customizable keybindings in the future, (3) a single source of truth for
documentation and help screens.

### Why semantic search prepopulation

When the user types `[design decisions](`, the link text "design decisions"
is a strong signal about what they want to link to. Running a semantic search
using that text as the query (against the existing chunk embeddings) will
surface the most relevant targets before the user types anything else.

### Why page title → filename fallback in the search box

Once the user starts typing in the popup's search field, they're explicitly
searching — they may not want semantic results anymore. At that point:
- **Page title search** is the primary mode: match against the full H1 line
  of each file (stored as `:file/title`).
- **Filename search** is the fallback: for files without an H1, match against
  the filename stem.

### Why chunk-level similarity reuses the file-level pattern

`match-fuzzy-renames` in `index.clj` (lines 132-180) already implements:
1. Compute centroids for "gone" items (from stored vectors)
2. Compute centroids for "new" items (from fresh embeddings)
3. Greedy best-match by cosine similarity
4. Threshold at 0.6

For heading rename detection, the same algorithm operates on chunks within a
single file. The key difference: we don't need to re-embed anything —
`index-file!` already embeds the new chunks, and we snapshot the old chunk
vectors before retraction.

### Slugification: align on one algorithm

`chunk.clj:slugify` and `hiccup.clj:heading-slug` are slightly different
implementations of the same concept. Recommendation: use `chunk.clj:slugify`
(already reusable, has max-len parameter) and update `hiccup.clj:heading-slug`
to delegate to it. This ensures wiki slugs match rendered HTML `id` attributes.

## Risks

- **Browser widget weight in content assist**: Each mini `Browser` is a
  WebKit instance. On macOS this is native and relatively cheap, but showing
  10+ simultaneously may cause layout jank. Mitigate by recycling instances
  and limiting visible rows. Measure during implementation; fall back to a
  single `Browser` with all results in one HTML document if per-row is too
  heavy.
- **Link preview latency** — resolving a `wiki:uuid`, querying chunk text,
  rendering HTML, and loading into a `Browser` needs to be fast (<100ms).
  The Datalevin lookup is ~1ms; HTML generation is ~5ms; Browser render is
  the bottleneck. Pre-create the preview Shell on editor creation and reuse it.
- **Google search rate limiting** — if using a direct API, consider caching
  and debouncing. If using the `WebSearch` tool, it handles this internally.
- **UUID determinism** — `UUID/nameUUIDFromBytes` on `(str file-id "#" slug)`
  gives a deterministic UUID. If `file-id` changes (file renamed), the UUID
  changes too. This is correct: file renames are handled by the existing
  `match-fuzzy-renames` at the file level; heading UUIDs within the renamed
  file are regenerated. Links to the old file's headings degrade to file-level
  (the file's UUID was updated by the rename handler).
- **Schema migration** — adding `:wiki/*` and `:file/title` attributes to an
  existing Datalevin store. Datalevin supports additive schema changes.

## Related Work

- `edn-tokenizers` (active) — externalize syntax highlighting into EDN files.
  Separate work item, see [EDN-TOKENIZERS-CONTEXT.md](EDN-TOKENIZERS-CONTEXT.md).
  Part of the broader editor improvement but independently deliverable.
- `wiki-links` (active) — Ctrl-click navigation, wiki-link syntax. The content
  assist popup, link preview, and `wiki:` protocol complement this work.
- `search-history-nav` (active) — Cmd+[/] navigation. Orthogonal.
- `complete/swt-ui/styledtext-editor/` — editor widget creation.
- `complete/swt-ui/md-theme/` — syntax highlighting and span detection.
- `complete/anchor-nav/` — heading `id` attributes in Browser view.
- `search.clj:result-card` (line 271) — reused for content assist and preview.
- `search.clj:page-css` (line 38) — reused for popup styling.
- `index.clj:match-fuzzy-renames` (lines 132-180) — the pattern for chunk-level
  heading rename detection.
- `index.clj:cosine-similarity` (lines 80-93) — reused for chunk matching.
- `chunk.clj:slugify` (lines 12-28) — canonical slugification.
