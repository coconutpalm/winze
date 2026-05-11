---
created: 2026-03-30
status: complete
related: [complete/file-viewer/CONTEXT.md, complete/file-viewer/PLAN.md, complete/tag-search/CONTEXT.md, complete/live-search/CONTEXT.md]
tags: [swt, ui, file-viewer, search, header, yaml, frontmatter]
---

# File Viewer Header — Context

## Problem Statement

The file viewer (opened by clicking a search result's file path link) has a minimal
header — just the plain relative path in `.header` styling. By contrast, the Live Search
result cards show a rich, structured header:

**Search result card header** (`search.clj:result-card`, lines 218–240):
```
[☐/☑ status] clickable-file-path (created-date)              [67% relevance badge]
[#group] [#tag1] [#tag2] [#related-doc]                       ← clickable pills
```

**Current file viewer header** (`search.clj:file-page`, lines 290–301):
```
complete/view-edit-scroll-sync/PLAN.md                        ← plain text, no metadata
```

Additionally, the YAML frontmatter (`---` fenced block at the top of each markdown file)
renders as ordinary body text and wraps sloppily instead of being displayed as a
formatted code block.

## Current Architecture

### Search result card rendering

`result-card` (search.clj:218–240) receives a full result map from `core/search`:

```clojure
{:file/path    "complete/view-edit-scroll-sync/PLAN.md"
 :file/status  "complete"
 :file/created "2026-03-30"
 :file/group   "view-edit-scroll-sync"
 :file/tags    "swt,ui,editor,scroll,caret,toggle"
 :file/related "CONTEXT.md,STYLEDTEXT-EDITOR-CONTEXT.md"
 :root/uri     "file:///Users/dorme/code/_finance"
 :relevance    0.55
 :chunk/text   "..."}
```

It renders:
1. `.result-header` — flex row: `.result-header-left` (status + path link + date) vs relevance badge
2. `metadata-pills` — clickable `#tag` links that trigger `winze:search?q=` navigation
3. `.result-body` — chunk text rendered via `md->hiccup`

### File viewer rendering

`file-page` (search.clj:290–301) receives only `markdown-text` and `file-path`:

```clojure
(defn file-page [markdown-text file-path]
  ;; renders [:div.header file-path] + [:div.result-body (md->hiccup markdown-text)]
  )
```

Called from `main_window.clj:70–74`:

```clojure
(let [abs-path  (search/resolve-file-path root-uri rel-path)
      content   (slurp abs-path)
      html      (search/file-page content rel-path)
      filename  (last (str/split rel-path #"/"))]
  (async-exec! #(open-tab! ... filename html rel-path abs-path rel-path)))
```

No metadata lookup occurs — the store is never queried for the file's indexed attributes.

### Metadata availability

All needed fields exist on the file entity in Datalevin (pulled via `store/pull-entity`):

```clojure
{:file/status   "complete"
 :file/created  "2026-03-30"
 :file/group    "view-edit-scroll-sync"
 :file/tags     "swt,ui,editor,scroll,caret,toggle"
 :file/related  "CONTEXT.md,STYLEDTEXT-EDITOR-CONTEXT.md"
 :file/type     "plan"
 :file/modified 1774890900}
```

Lookup by path: query `[:find [?f ...] :in $ ?path :where [?f :file/path ?path]]`
then `pull-entity` on the result eid.

### YAML frontmatter handling

`md->hiccup` (hiccup.clj) does **not** strip YAML frontmatter — the commonmark-java
parser treats `---` fences as thematic breaks and the key-value lines as plain paragraphs.
The result is unstyled body text that wraps at arbitrary points.

`frontmatter.clj:parse-frontmatter` already exists and returns
`[metadata-map, body-text-without-frontmatter]`. The file viewer can use this to:
1. Extract frontmatter as raw YAML text
2. Render it in a `<pre><code>` block with appropriate styling
3. Pass only the body (sans frontmatter) to `md->hiccup`

### Refresh path

`refresh-open-tabs!` (main_window.clj:179) re-reads the file and re-renders HTML on
external modification. It currently calls `file-page` with the same two args. After
this change, it will also need metadata — either re-queried from the store or cached
in `open-files`.

### CSS

The search result card CSS (`.result-header`, `.file-path`, `.badge`, `.pills`, `.pill`,
`.status-indicator`, `.created-date`) is already defined in `page-css` and shared by both
the results page and the file page (both call `page-css`). New classes added:
- `.frontmatter-block` — monospace pre-formatted YAML code block
- `.status-active` / `.status-complete` — colored status indicators (deep-violet / green)

### Status indicator styling

The original `☑` glyph for completed documents didn't stand out visually. Replaced with
`✓` (checkmark) plus CSS color classes:
- `.status-active` → `☐` in deep-violet (`#7B6FC0`)
- `.status-complete` → `✓` in green (`#66BB6A`)

Both classes are applied alongside `.status-indicator` via a `(str "status-indicator" ...)`
class builder in `file-header`.

## Scope

**In scope:**
- File viewer header matching search result card format (status, path, date, pills)
- No relevance badge (file viewer isn't a search result)
- YAML frontmatter rendered as styled code block
- Frontmatter stripped from markdown body to avoid duplication
- Refresh path updated to include metadata
- Colored status indicators (green ✓ for complete, violet ☐ for active)

**Out of scope:**
- Completion checkbox toggling (read-only display only)
- Edit mode changes (StyledText editor is unaffected)
