# Clickable File Viewer Tabs — Context

## Goal

Make the file path in each search result card a clickable link that opens the full markdown file in a new closable tab, rendered as HTML.

## Current State

### Search Result Cards

`winze-server/src/llm_memory/ui/search.clj:result-card` renders each result as a `.result-card` div. The header row contains:

```clojure
[:div.result-header
 [:span.file-path (or path "—")]          ;; ← plain text today
 [:span {:class (relevance-class relevance)}
  (format "%.0f%%" (* 100.0 relevance))]]
```

`:file/path` is a relative path within the Plans directory (e.g. `"dev/WORD-CLOUD-PLAN.md"`, `"complete/gpu-report/CONTEXT.md"`).

### Search Result Data

`core/search` enriches each result with:
- `:file/path` — relative path within the Plans directory
- `:file/id` — unique identifier (`"{root-name}::{rel-path}"`)
- `:root/uri` — project root as a `file:///` URI (e.g. `"file:///Users/dorme/code/_finance"`)
- `:root/name` — display name of the root

The root entity also stores `:root/plans-dir` (typically `"Plans"`), accessible via `core/list-roots` or a Datalog query.

### Absolute Path Resolution

The absolute filesystem path for any indexed file is:

```
{root/uri path component} / {root/plans-dir} / {file/path}
```

Example:
- `root/uri` = `"file:///Users/dorme/code/_finance"`
- `root/plans-dir` = `"Plans"`
- `file/path` = `"dev/WORD-CLOUD-PLAN.md"`
- → `/Users/dorme/code/_finance/Plans/dev/WORD-CLOUD-PLAN.md`

### Tab Infrastructure

`main_window.clj:body` creates a `CTabFolder` (`:ui/main-folder`) with one permanent tab ("Live search") containing the Browser widget. The folder is configured with:

```clojure
(ctab-folder (id! :ui/main-folder)
             :simple                    false
             :unselected-image-visible  false
             :unselected-close-visible  false
             ...)
```

New `CTabItem` instances can be created programmatically against this folder. SWT `CTabItem` supports `SWT.CLOSE` style for a close button. The `CTabFolder` fires `CTabFolder2Adapter.close(CTabFolderEvent)` when the close button is clicked.

### CDT Widget Construction

CDT init functions (`browser`, `ctab-item`, etc.) expect to be composed declaratively inside a parent. For dynamic tab creation (after initial construction), we need to use SWT directly:

```clojure
;; Create a new Browser widget parented to the CTabFolder
(let [folder  (element :main-folder)
      browser (Browser. folder SWT/WEBKIT)]
  (.setJavascriptEnabled browser true)
  (.setText browser html-content)
  ;; Create a CTabItem with close button
  (let [tab (CTabItem. folder SWT/CLOSE)]
    (.setText tab "file-name.md")
    (.setControl tab browser)
    (.setSelection folder tab)))
```

### Markdown Rendering

`search.clj` already has:
- `md->hiccup` — converts markdown text to Hiccup via `nextjournal.markdown`
- `page-css` — the full brand-palette CSS stylesheet
- `hiccup2.core/html` — renders Hiccup to HTML strings

These can be reused directly for rendering full file content.

## Design: LocationListener Approach

Same pattern as the word cloud click-to-search: the file path becomes an `<a>` tag with a pseudo-URL scheme. The `LocationListener` on the Browser widget intercepts navigation and opens a tab instead.

### URL Scheme

```
winze:open-file?root={root-uri}&path={file/path}
```

Example:
```
winze:open-file?root=file%3A%2F%2F%2FUsers%2Fdorme%2Fcode%2F_finance&path=dev%2FWORD-CLOUD-PLAN.md
```

Both parameters are URL-encoded. The handler:
1. Resolves the root's `plans-dir` via Datalog query
2. Constructs the absolute path
3. Reads the file from disk (`slurp`)
4. Renders markdown → Hiccup → HTML
5. Creates a new Browser + CTabItem in the main folder

### Click Flow

```
User clicks file path in search result
  → Browser navigates to "winze:open-file?root=...&path=..."
  → LocationListener fires (changing event)
  → Clojure handler:
      1. event.doit = false
      2. Resolve absolute path from root-uri + plans-dir + file/path
      3. Read file content (slurp)
      4. Render markdown → HTML (reusing page-css + md->hiccup)
      5. Create new Browser widget in CTabFolder
      6. Create CTabItem with SWT/CLOSE, set text to filename
      7. Set selection to the new tab
```

### Tab Lifecycle

- Each file-viewer tab gets its own `Browser` widget
- `CTabItem` created with `SWT.CLOSE` style — shows the close button
- On close, the `Browser` widget is disposed automatically (SWT disposes child controls when the `CTabItem` is disposed)
- No need for a `CTabFolder2Adapter` unless we want to intercept close events

### Shared Code with Word Cloud

Both this feature and the word cloud use the same `LocationListener` on the search Browser widget. The listener dispatches on the pseudo-URL scheme:

- `winze:search?q=...` → populate search field, trigger search (word cloud)
- `winze:open-file?root=...&path=...` → open file in new tab (this feature)

This means the `LocationListener` should be a single handler with a `cond` dispatch, not two separate listeners.

## Files to Modify

| File | Change |
|------|--------|
| `winze-server/src/llm_memory/ui/search.clj` | Change `.file-path` span to an `<a>` tag with `winze:open-file` URL; add `file-page` rendering function |
| `winze-server/src/llm_memory/ui/main_window.clj` | Add `open-file-tab!` function; extend `LocationListener` to handle `winze:open-file` URLs; import `Browser`, `CTabItem` |

## Dependencies

- No new dependencies required
- Reuses existing: `hiccup2.core`, `nextjournal.markdown`, brand palette CSS
- File I/O: `slurp` on the resolved absolute path (the file is local)
