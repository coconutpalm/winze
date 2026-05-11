# Editor Commands — Plan

**Prerequisites**:
- Command palette implemented — see
  [COMMAND-PALETTE-PLAN.md](COMMAND-PALETTE-PLAN.md). Provides the command
  registry (`commands.clj`), scoped keybinding dispatch, and `.keybinding`
  files. Editor commands register into that system.
- Read `winze/Plans/SWT-UI-GUIDE.md` before implementation
- See [EDITOR-COMMANDS-CONTEXT.md](EDITOR-COMMANDS-CONTEXT.md) for architecture
  and design decisions

---

## Phase 1 — Text Manipulation Primitives + Formatting Commands

### Step 1 — Text manipulation primitives (`commands.clj`)

Add pure helper functions to `commands.clj` (created by the command palette
plan). These are the building blocks for all formatting commands.

1. **`toggle-inline-wrap`** — the core toggle primitive:
   ```clojure
   (defn toggle-inline-wrap
     "If selection is wrapped in `delim`, unwrap it. Otherwise, wrap it.
     Handles empty selection (inserts delimiters with cursor between them)."
     [styled-text delim]
     ...)
   ```
   Logic:
   - Get selection range via `.getSelectionRange` → `Point` (x=offset, y=length)
   - Get selected text via `.getSelectionText`
   - If selected text starts and ends with `delim`, replace with inner text
   - Else, replace with `(str delim selected-text delim)`
   - Set caret: after wrap → between delimiters if empty, after closing delim
     if non-empty; after unwrap → end of unwrapped text

2. **`toggle-line-prefix`** — for headings, lists, quotes:
   ```clojure
   (defn toggle-line-prefix
     "Toggle a prefix string on the current line (or all lines in selection).
     If every affected line already has `prefix`, remove it. Otherwise, add it."
     [styled-text prefix]
     ...)
   ```
   Logic:
   - Determine affected lines from selection range
   - For each line: `.getLine` → check if it starts with `prefix`
   - If ALL lines have the prefix → strip it from all
   - Else → add the prefix to lines that don't have it
   - Use `.replaceTextRange` for each line, adjusting offsets as lines change
     length

3. **`set-heading-level`** — specialized heading setter:
   ```clojure
   (defn set-heading-level
     "Set the current line to heading level N (1-6). If already at level N,
     remove the heading prefix (toggle behavior)."
     [styled-text level]
     ...)
   ```
   Logic:
   - Get current line text
   - Strip any existing `^#{1,6}\s` prefix
   - If the stripped heading was level N, set line to plain text
   - Else, prepend `(str (apply str (repeat level "#")) " ")`

4. **`insert-at-cursor`** — insert text at cursor position:
   ```clojure
   (defn insert-at-cursor
     "Insert `text` at the current caret position. Optionally place cursor
     at `cursor-offset` relative to the insertion start."
     [styled-text text & {:keys [cursor-offset]}]
     ...)
   ```

5. **`selected-lines-range`** — helper for multi-line operations:
   ```clojure
   (defn selected-lines-range
     "Return [first-line-idx last-line-idx] covering the selection."
     [styled-text]
     ...)
   ```

6. RCF tests for each primitive (test the logic, not the SWT calls — extract
   pure string functions where possible and test those).

**Verify**: REPL — open editor, select text, call `toggle-inline-wrap` with
`"**"`, confirm bold wrapping/unwrapping.

---

### Step 2 — Register inline formatting commands

Register the Tier 1 inline formatting commands using the primitives from Step 1.

| Command ID | Label | Hotkey | Delimiter |
|-----------|-------|--------|-----------|
| `:editor/toggle-bold` | Toggle Bold | Mod1+B | `**` |
| `:editor/toggle-italic` | Toggle Italic | Mod1+I | `*` |
| `:editor/toggle-strikethrough` | Toggle Strikethrough | Mod1+Shift+S | `~~` |
| `:editor/toggle-inline-code` | Toggle Inline Code | Mod1+Shift+E | `` ` `` |
| `:editor/toggle-highlight` | Toggle Highlight | Mod1+Shift+H | `==` |

Note: Mod1+E is taken (view/edit toggle). Use Mod1+Shift+E for inline code, or
Mod1+` (backtick). Decide during implementation based on what feels natural.

Register each command via `commands/register!`. Add corresponding keybindings
to `resources/keybindings/editor.keybinding`:
```clojure
{:key \b :mod #{:mod1} :when {:in :editor}
 :command :editor/toggle-bold}
;; etc.
```

**Verify**: REPL — select text, invoke command via hotkey, confirm formatting
toggles. Also verify commands appear in the command palette.

---

## Phase 2 — Heading/List/Line Commands + Insert Commands

### Step 3 — Register heading and list commands

| Command ID | Label | Hotkey | Prefix |
|-----------|-------|--------|--------|
| `:editor/heading-1` | Heading 1 | Mod1+1 | `# ` |
| `:editor/heading-2` | Heading 2 | Mod1+2 | `## ` |
| `:editor/heading-3` | Heading 3 | Mod1+3 | `### ` |
| `:editor/heading-4` | Heading 4 | Mod1+4 | `#### ` |
| `:editor/heading-5` | Heading 5 | Mod1+5 | `##### ` |
| `:editor/heading-6` | Heading 6 | Mod1+6 | `###### ` |
| `:editor/toggle-bullet` | Toggle Bullet List | Mod1+Shift+8 | `- ` |
| `:editor/toggle-numbered` | Toggle Numbered List | Mod1+Shift+7 | `1. ` |
| `:editor/toggle-checkbox` | Toggle Checkbox | Mod1+Shift+4 | `- [ ] ` |
| `:editor/toggle-blockquote` | Toggle Blockquote | Mod1+Shift+. | `> ` |
| `:editor/toggle-comment` | Toggle Comment | Mod1+/ | `<!-- ` / ` -->` |
| `:editor/check-uncheck` | Check/Uncheck Checkbox | Mod1+Enter | (toggle `[ ]` ↔ `[x]`) |

Heading commands use `set-heading-level`. List/quote commands use
`toggle-line-prefix`. Comment toggle is special (wraps in `<!-- -->`, not a
line prefix) — add a `toggle-block-wrap` primitive.

**Verify**: REPL — test each command on sample text.

---

### Step 4 — Line operations

Add commands for line-level manipulation:

| Command ID | Label | Hotkey | Action |
|-----------|-------|--------|--------|
| `:editor/indent` | Indent | Tab | Prepend 2 spaces to each line in selection |
| `:editor/outdent` | Outdent | Shift+Tab | Remove up to 2 leading spaces from each line |
| `:editor/move-line-up` | Move Line Up | Alt+Up | Swap current line with line above |
| `:editor/move-line-down` | Move Line Down | Alt+Down | Swap current line with line below |
| `:editor/delete-line` | Delete Line | Mod1+Shift+K | Delete current line |
| `:editor/duplicate-line` | Duplicate Line | Mod1+Shift+D | Duplicate current line below |
| `:editor/select-line` | Select Line | Mod1+L | Select entire current line |

Implementation notes:
- **Tab/Shift+Tab**: Keybinding uses `:when {:in :editor}` so it only fires
  when the editor has focus, not during tab switching.
- **Move line**: Swap the line text, preserve caret column position.
- **Delete/Duplicate**: Use `.replaceTextRange` for delete, calculate insertion
  point for duplicate.

**Verify**: REPL — test each operation, verify caret ends up in the right place.

---

### Step 5 — Simple insert commands

| Command ID | Label | Hotkey | Inserted text |
|-----------|-------|--------|---------------|
| `:editor/insert-hr` | Insert Horizontal Rule | — | `\n---\n` |
| `:editor/insert-code-block` | Insert Code Block | — | `` \n```\n\n```\n `` (cursor on empty line) |
| `:editor/insert-table` | Insert Table | — | 2×2 markdown table skeleton |
| `:editor/insert-callout` | Insert Callout | — | `> [!note]\n> ` |
| `:editor/insert-math` | Insert Math Block | — | `$$\n\n$$` |
| `:editor/insert-date` | Insert Current Date | — | `2026-04-09` (ISO format) |
| `:editor/insert-time` | Insert Current Time | — | `14:30` (HH:mm) |

All use `insert-at-cursor` with appropriate `cursor-offset`. These commands
have no default hotkey — accessible via the command palette only.

**Verify**: REPL — invoke each via command palette, verify inserted text and
cursor position.

---

## Phase 3 — Wiki Link Schema + Content Assist Popup

### Step 6 — Wiki link schema + heading extraction

Before building the content assist popup, establish the data layer it queries.

**6a — Schema additions** (`clj-llm-memory/src/llm_memory/store/datalevin.clj`):

```clojure
;; Wiki link entities — heading anchors with stable UUIDs
:wiki/id    {:db/valueType :db.type/string  :db/unique :db.unique/identity}
:wiki/file  {:db/valueType :db.type/ref}
:wiki/slug  {:db/valueType :db.type/string}
:wiki/text  {:db/valueType :db.type/string}
:wiki/line  {:db/valueType :db.type/long}
:wiki/level {:db/valueType :db.type/long}

;; Page title — first H1 content, full text (not truncated)
:file/title {:db/valueType :db.type/string}
```

**6b — Heading extraction** (`clj-llm-memory/src/llm_memory/chunk.clj`):

```clojure
(defn extract-headings
  "Parse headings from markdown text. Returns a vector of
  {:text \"Design Decisions\" :level 2 :slug \"design-decisions\" :line 5}."
  [text]
  ...)
```

Logic: split on newlines, match `^(#{1,6})\s+(.*)$`, compute slug via
`slugify`, capture 0-based line number. The first H1 found (if any) is
the page title.

Also: update `hiccup.clj:heading-slug` to delegate to `chunk.clj:slugify`
so that wiki slugs match rendered HTML `id` attributes.

**6c — UUID generation** (`clj-llm-memory/src/llm_memory/index.clj`):

```clojure
(defn- wiki-uuid
  "Generate a deterministic UUID for a heading within a file."
  [file-id slug]
  (str (java.util.UUID/nameUUIDFromBytes
         (.getBytes (str file-id "#" slug) "UTF-8"))))
```

**6d — Index headings in `index-file!`**:

After building chunk entities (line ~280), also build wiki entities and
extract `:file/title`. Retract old `:wiki/*` entities before transacting.

RCF tests: index a file, query `:wiki/*` entities, confirm UUIDs and headings.

**6e — Sidecar backup** (`clj-llm-memory/src/llm_memory/index.clj`):

After `index-file!` transacts wiki entities, write the full UUID→slug map for
that root to `<plans-dir>/.wiki-registry.edn`. This is the authoritative backup
that survives catastrophic store loss.

```clojure
(defn- save-wiki-registry!
  "Write the full wiki UUID→slug map for a root to the sidecar EDN file."
  [store root-uri]
  ;; Query all wiki entities for this root, write as EDN map
  ;; {uuid-str {:file-id str :slug str :text str :level long}}
  ...)
```

On store recovery (rebuild from scratch), if `.wiki-registry.edn` exists, load
it first and use those UUIDs instead of generating new deterministic ones.

See [EDITOR-COMMANDS-CONTEXT.md](EDITOR-COMMANDS-CONTEXT.md) §Sidecar backup
for rationale.

**Verify**: REPL — index a file, confirm `.wiki-registry.edn` exists with
correct data. Delete the store, rebuild, confirm UUIDs match original.

---

### Step 7 — Make `result-card` and `page-css` public (`search.clj`)

The content assist popup and link preview both need to render search result
cards:

1. Change `defn- result-card` → `defn result-card` (line 271)
2. Change `defn- page-css` → `defn page-css` (line 38)
3. Add a `card-html` convenience function that wraps a single result card in
   a minimal HTML page for a mini Browser widget.

**Verify**: REPL — call `card-html` with a search result, confirm it renders
as a self-contained HTML page.

---

### Step 8 — Content assist popup (`content_assist.clj`)

Create `winze-server/src/llm_memory/ui/content_assist.clj` — a floating popup
for link target selection with HTML-rendered result cards.

**8a — Widget structure**:

```
Shell (SWT.TOOL | SWT.ON_TOP | SWT.NO_TRIM)
├── Text (search field, scope :content-assist)
└── ScrolledComposite
    └── Composite (results container)
        ├── Browser (mini, ~80px tall — result card 1) [selected]
        ├── Browser (mini — result card 2)
        └── ... (up to ~8 rows)
```

Position below the caret. Pre-create a pool of ~8 mini `Browser` widgets;
reuse on search updates.

**8b — Modes: Wiki vs. Google**:

**Wiki mode** (default):
- On open with seed text: embed and run vector similarity search
- When user types: page title search (`:file/title`) → filename fallback
- Results rendered as `card-html`

**Google mode** (activated by `http://` or `https://`):
- Switch data source to web search
- Simplified cards: title, URL, snippet
- Backspacing past protocol prefix switches back to wiki mode

**8c — Behavior**:
- Typing filters (debounced ~150ms wiki, ~500ms Google)
- Up/Down navigate, Enter selects, Esc closes
- Clicking outside closes
- Popup registers with `keybindings/set-active-popup!` on open and
  `keybindings/clear-active-popup!` on close

**8d — Output**: Returns a map:
- Wiki: `{:type :wiki :uuid "..." :file-path "..." :slug "..." :title "..."}`
- Google: `{:type :external :url "https://..." :title "..."}`
- Dismissed: `nil`

**Verify**: REPL — open popup, verify semantic prepopulation, type to filter,
type `https://` to switch to Google mode, select a result.

---

### Step 9 — `(` trigger for content assist

In the editor's key handler, detect when the user types `(` immediately
after `]`:

```clojure
(when (and (= (.character event) \()
           (> caret 0)
           (= \] (.charAt text (dec caret))))
  (let [link-text (extract-bracket-text text caret)]
    (open-content-assist! styled-text caret :seed-text link-text)))
```

When the popup returns a wiki selection: insert `wiki:<uuid>)`.
When external: insert `<url>)`.
On Esc: let the `(` pass through as normal text.

**Verify**: REPL — type `[design decisions](`, confirm popup with semantic
results, select, confirm `[design decisions](wiki:uuid)` completed.

---

### Step 10 — Insert Link command (Mod1+K)

Register `:editor/insert-link` with keybinding `{:key \k :mod #{:mod1}
:when {:in :editor}}` in `editor.keybinding`.

Behavior:
1. If text selected → seed popup with selection
2. If no selection → open popup empty
3. Wiki target → insert `[title](wiki:uuid)`
4. External → insert `[title](https://url)`

**Verify**: REPL — Mod1+K with and without selection, verify link inserted.

---

### Step 11 — `wiki:` URL resolution in navigation

Add `wiki:` URL handling to the Browser's `custom-browser` `on-changing`
handler and to the editor's `navigate-link!` dispatch:

```clojure
(when (str/starts-with? url "wiki:")
  (let [uuid (subs url 5)]
    ;; Look up :wiki/id → file path + slug → navigate
    ;; Fallback: look up as :file/id → navigate to file
    ;; Fallback: broken link warning
    ))
```

**Verify**: REPL — insert a `wiki:uuid` link, MOD1-click it, confirm
navigation.

---

## Phase 4 — Link Preview + Find & Replace

### Step 12 — Link preview popup (`link_preview.clj`)

Create `winze-server/src/llm_memory/ui/link_preview.clj` — a floating popup
that shows a preview of the linked content.

**12a — Widget**: `Shell (NO_FOCUS)` with a single `Browser` (~300x200px).
Positioned near the link.

**12b — Data source**: Look up `wiki:uuid` → chunk text + file metadata →
render via `search/card-html`.

**12c — Trigger: mouse hover**: 300ms delay over a link span (without MOD1).
Dismiss on mouse-away.

**12d — Trigger: cursor position**: `CaretListener`, 200ms delay when caret
enters a link span. Dismiss when caret leaves.

**12e — Dismiss**: Mouse away, cursor away, Esc (via scoped keybinding with
`:when {:in :editor :active-popup :link-preview}`), any edit action, scroll.

**12f — Lifecycle**: Create Shell once per editor instance, reuse, dispose on
editor dispose.

**Verify**: REPL — hover over `wiki:uuid` link, confirm preview card. Move
cursor into link, confirm preview. Move away, confirm dismiss.

---

### Step 13 — Find/replace bar (`find_replace.clj`)

Create `winze-server/src/llm_memory/ui/find_replace.clj` — a bar that overlays
the top of the editor area.

**UI structure**:
```
┌──────────────────────────────────────────────────┐
│ Find:    [search field      ] [↑] [↓] [×]  3/17 │
│ Replace: [replace field     ] [Replace] [All]    │
└──────────────────────────────────────────────────┘
```

1. **Widget**: SWT `Composite` with scope `:find-bar`. Show/hide via layout.
2. **Find**: Highlight matches, navigate with Enter/Shift+Enter, show count.
   Case-sensitive toggle.
3. **Replace**: Replace current, Replace All (iterate end-to-start).
4. **Keybindings** (in `editor.keybinding`):
   - `{:key \f :mod #{:mod1} :when {:in :editor} :command :editor/find}`
   - `{:key \h :mod #{:mod1} :when {:in :editor} :command :editor/find-replace}`
   - Esc via `{:key :esc :when {:in :editor :active-popup :find-bar}
     :command :dismiss-find-bar}`
5. **Integration with theme**: Find highlights as an additional `StyleRange`
   pass after `apply-theme!`.

**Verify**: REPL — Mod1+F opens bar, type query, matches highlight, navigate,
replace, Esc closes.

---

## Phase 5 — Wiki Link Rename Tracking

### Step 14 — Snapshot old state before re-indexing

Modify `index-file!` (or wrap it) to snapshot the old heading and chunk state
before retraction:

```clojure
(defn- snapshot-wiki-state
  "Query existing wiki entities and chunk vectors for a file, before re-indexing."
  [store file-id]
  {:wikis  (store/query store
             '[:find ?id ?slug ?text ?line ?level
               :in $ ?fid
               :where
               [?f :file/id ?fid]
               [?w :wiki/file ?f]
               [?w :wiki/id ?id]
               [?w :wiki/slug ?slug]
               [?w :wiki/text ?text]
               [?w :wiki/line ?line]
               [?w :wiki/level ?level]]
             {:fid file-id})
   :chunks (store/query store
             '[:find ?slug ?vec
               :in $ ?fid
               :where
               [?f :file/id ?fid]
               [?c :chunk/file ?f]
               [?c :chunk/slug ?slug]
               [?c :chunk/vec ?vec]]
             {:fid file-id})})
```

**Verify**: REPL — call snapshot on an indexed file, confirm UUIDs, slugs,
and vectors returned.

---

### Step 15 — Chunk-level similarity matching for heading renames

After `index-file!` completes, compare old and new state:

```clojure
(defn- match-heading-renames
  "Given old and new wiki state for a file, use chunk embedding similarity
   to detect heading renames. Returns {:unchanged [...] :renamed [...]
   :added [...] :removed [...]}.

   Each :renamed entry is {:old-uuid str :old-slug str :new-slug str
   :similarity double}. The UUID is preserved for renamed headings."
  [old-snapshot new-snapshot]
  ...)
```

Algorithm (mirrors `match-fuzzy-renames` at lines 132-180 of `index.clj`):

1. Build maps: `{slug → chunk-vector}` for old and new state
2. Slugs in both → unchanged (keep UUID)
3. Slugs only in old → gone candidates
4. Slugs only in new → new candidates
5. Greedy best-match by cosine similarity
6. Similarity ≥ 0.6 → rename (preserve old UUID, update slug + text)
7. Unmatched old → removed (UUID removed)
8. Unmatched new → added (generate new UUID)

**Verify**: RCF tests — snapshots with a renamed slug, confirm UUID preserved.

---

### Step 16 — Wire into `index-file!` lifecycle

```clojure
(let [old-state (snapshot-wiki-state store file-id)
      result    (index-file! store root-uri abs-path)
      new-state (snapshot-wiki-state store file-id)
      changes   (match-heading-renames old-state new-state)]
  ;; Renamed: update :wiki/slug and :wiki/text (UUID preserved)
  ;; Removed: retract :wiki/* entity
  ;; Added: transact new :wiki/* with fresh UUID
  ;; Unchanged: no-op
  )
```

**No cross-file propagation needed** — the UUID in markdown files is stable.

**Verify**: End-to-end — edit a heading, confirm UUID preserved. Confirm
`wiki:uuid` links still resolve.

---

Heading-level folding is a separate work item — see
[HEADING-FOLDING-PLAN.md](HEADING-FOLDING-PLAN.md).

---

## Summary of Changes

| Phase | Files | New lines (est.) | Nature |
|-------|-------|----------------:|--------|
| 1 | `commands.clj` (extend), `editor.keybinding` (extend) | ~200 | Primitives + inline formatting commands |
| 2 | `commands.clj` (extend), `editor.keybinding` (extend) | ~200 | Heading/list/line/insert commands |
| 3 | `content_assist.clj` (new), `search.clj`, `main_window.clj`, `datalevin.clj`, `index.clj`, `chunk.clj`, `markdown_editor.clj` | ~500 | Wiki schema + content assist + URL resolution |
| 4 | `link_preview.clj` (new), `find_replace.clj` (new), `markdown_editor.clj` | ~350 | Link preview + find/replace |
| 5 | `index.clj` | ~150 | Chunk-level rename detection (UUID preservation) |

## Verification Checkpoints

| Phase | How |
|-------|-----|
| 1 | Mod1+B toggles bold, Mod1+I italic, commands appear in palette |
| 2 | Mod1+1 sets H1, Mod1+Shift+8 toggles bullet, Alt+Up moves line |
| 3 | Mod1+K opens content assist with result cards, `[text](` triggers autocomplete, `https://` switches to Google, `wiki:uuid` links resolve |
| 4 | Hover over link shows preview card, Mod1+F opens find bar, replace works |
| 5 | Rename heading → UUID preserved → links still resolve; delete heading → UUID removed → links degrade to file-level |

## Implementation Order Rationale

Phases 1–2 deliver the most value with the least risk — they're pure text
manipulation on the existing widget. Phase 3 (content assist + wiki schema) is
the most architecturally significant: it introduces the `wiki:uuid` protocol,
the content assist popup with HTML-rendered result cards, the wiki/Google mode
switch, and URL resolution. Phase 4 adds polish (link preview) and a standard
feature (find/replace). Phase 5 (rename tracking) makes the `wiki:uuid`
approach robust — without it, heading renames generate new UUIDs and old links
degrade to file-level. Heading-level folding has been extracted into a separate
work item ([HEADING-FOLDING-PLAN.md](HEADING-FOLDING-PLAN.md)).
