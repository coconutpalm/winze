---
group: live-search
type: context
related: winze-ctabfolder-ui
---

# Live Search Implementation — Context

## Goal

Implement live search in the Winze SWT desktop UI. As the user types in the search box, results from the Plans vector store are rendered as HTML in an embedded WebKit browser widget.

## Current State

### Implementation (complete)

`winze-server/src/llm_memory/ui/search.clj` — full implementation with debounced search,
Hiccup page template using brand palette, and nextjournal/markdown for chunk body rendering.

### Integration Point

In `llm-memory.ui.main-window`, the search text widget fires on every keystroke:

```clojure
(on e/modify-text [props parent event]
  (async-exec! #(search/results
                 (.getText (element :search))
                 (element :live-search-results))))
```

- `async-exec!` queues the call on the SWT UI thread (non-blocking).
- `:ui/live-search-results` is an `SWT.WEBKIT` Browser widget with JavaScript enabled.

### Store Access

`llm-memory.server.main/store` returns the active `PlanStore` — a Datalevin-backed store with inference4j embeddings. Available from any thread in the server JVM.

## Key APIs

### `llm-memory.core/search` (structured data — preferred)

Returns a vector of result maps:

```clojure
{:eid          N
 :distance     F                  ; cosine distance (0–1, lower = better)
 :relevance    F                  ; 1.0 - distance
 :chunk/id     "root::file::slug"
 :chunk/text   "..."              ; **markdown** content of the chunk
 :chunk/slug   "section-name"
 :file/path    "dev/CONTEXT.md"
 :file/status  "active"           ; optional
 :file/type    "context"          ; optional
 :file/group   "cache-gap-detect" ; optional
 :file/jira    "AAO-30"           ; optional
 :root/uri     "file:///..."      ; optional
 :root/name    "project-name"}    ; optional
```

### `llm-memory.tools/search-plans` (pre-formatted markdown string)

Returns a single markdown string with `---` separators between results, including relevance badges and annotations. Useful for MCP tool output, but less flexible for HTML rendering.

**Decision**: Use `core/search` directly for structured data — gives full control over HTML layout and metadata display.

## Design Constraints

### Performance / Debouncing

`modify-text` fires on **every keystroke**. Embedding + vector search takes ~50–200ms per query. Without debouncing, rapid typing would queue dozens of redundant searches.

**Approach**: Debounce in the search namespace — only execute the search after the user pauses typing (e.g., 300ms). Use a scheduled executor or `java.util.Timer` to delay execution and cancel pending searches when new keystrokes arrive.

### HTML Rendering — Pure Hiccup Pipeline

The entire rendering pipeline stays in Clojure data until the final `str` call:

1. **Page structure** (result cards, badges, metadata pills, CSS) — hand-written Hiccup vectors
2. **Chunk body content** (`:chunk/text` markdown) — parsed to hiccup via `nextjournal.markdown`
3. **Final render** — `(str (h/html page-hiccup))` produces the HTML string for `.setText`

**nextjournal/markdown** (`io.github.nextjournal/markdown`) provides a two-step API:
- `md/parse` — markdown string → Pandoc-style AST (nested maps with `:type` and `:content`)
- `md/->hiccup` — AST → hiccup vectors (e.g. `[:div [:p [:strong "bold"] " text"]]`)

Since the output is plain hiccup data, chunk bodies compose directly into the page template — no `h/raw` injection of pre-rendered HTML strings. Custom renderers can be supplied via `md/default-hiccup-renderers` to control how specific node types render (e.g. code blocks, links).

Under the hood, nextjournal/markdown uses commonmark-java on the JVM, so we get full CommonMark + GFM compliance (tables, task lists, footnotes) without a direct dependency.

### SWT Browser Widget

- `.setText(String html)` — sets the browser's content to an HTML string. This is the primary API.
- `.setUrl(String url)` — navigates to a URL (used for initial DuckDuckGo placeholder).
- Must be called on the SWT UI thread — guaranteed here since `async-exec!` is used by the caller.

### Threading Model

The call chain is:
1. Keystroke → `modify-text` event (UI thread)
2. `async-exec!` queues `search/results` (still UI thread when executed)
3. Search involves embedding (CPU-bound, ~50ms) and vector lookup (I/O-bound, ~10ms)

**Problem**: Running search synchronously on the UI thread blocks the event loop. Long searches would freeze the UI.

**Solution**: Run the search on a background thread (future or executor), then use `async-exec!` to push the HTML result back to the Browser widget on the UI thread. The debounce timer naturally runs off the UI thread already.

### Empty / Short Queries

- Empty string → show a welcome/placeholder page
- Very short queries (1–2 chars) → skip search, too noisy

## Dependencies to Add

In `winze-server/deps.edn`:

```clojure
hiccup/hiccup                          {:mvn/version "2.0.0"}
io.github.nextjournal/markdown         {:mvn/version "0.7.222"}
```

- **Hiccup 2.x** — page structure templating. `hiccup2.core/html` returns a `RawString`; call `str` to get the HTML string.
- **nextjournal/markdown** — parses chunk body markdown into hiccup vectors that compose directly into the page template. Brings commonmark-java transitively.

## Operational Findings (from implementation)

### Hiccup version: use 2.0.0 (not RC3)

`hiccup/hiccup 2.0.0-RC3` does not exist on Maven Central or Clojars. The correct release is **2.0.0** (final). Version 2.0.0-RC5 also exists locally but 2.0.0 is preferred since it's the GA release.

### Dynamic dep loading into the running uberjar

The winze uberjar supports `clojure.repl.deps/add-libs` because `-main` wraps the context classloader in a `DynamicClassLoader` and binds `*repl* true` (see SWT-UI-GUIDE §10). This means new Maven dependencies can be injected at runtime without restarting:

```clojure
(clojure.repl.deps/add-libs '{hiccup/hiccup {:mvn/version "2.0.0"}})
(clojure.repl.deps/add-libs '{io.github.nextjournal/markdown {:mvn/version "0.7.222"}})
```

### `require :reload` vs `load-file` for AOT-compiled namespaces

When the winze server runs from an uberjar, namespaces are loaded from AOT-compiled `.class` files. `(require 'ns :reload)` **does not override** these with source `.clj` files — it reloads the compiled version. To pick up source changes without restarting, use `load-file`:

```clojure
(load-file "/Users/dorme/code/_finance/winze/winze-server/src/llm_memory/ui/search.clj")
```

This is essential for the dev loop: edit source → `load-file` → test via REPL.

### `refer` fails on vars not in AOT-compiled namespace

`(require '[ns :refer [var-name]])` throws `IllegalAccessError: var-name does not exist` when the namespace was loaded from AOT classes and the var wasn't compiled into that version. Use `:as` aliases instead of `:refer` when working against the uberjar:

```clojure
;; Fails if `screenshot-widget!` wasn't in the compiled version:
(require '[llm-memory.ui.util :refer [screenshot-widget!]])  ; IllegalAccessError

;; Works — qualified access is always safe:
(require '[llm-memory.ui.util :as ui-util])
(ui (ui-util/screenshot-widget! widget "/tmp/test.png"))
```

### `.setText` on Text widgets fires `modify-text` events

Calling `.setText` programmatically on an SWT `Text` widget from the UI thread **does** fire `modify-text` listener events, just like real keyboard input. This makes it a reliable way to simulate typing in REPL tests:

```clojure
(ui (.setText (mw/element :search) "gpu report cost"))  ;; triggers the search pipeline
```

### Thread safety: both code paths need `async-exec!`

The `results` function can be called from the UI thread (event handler), nREPL thread, or the scheduled executor thread. Both the short-query path (show placeholder) and the search-complete path (show results) must use `async-exec!` — see SWT-UI-GUIDE §13.

### `tab-title` H1 truncation regex — hyphenated words

`tab-title` in `search.clj` truncates an H1 heading at the first "title-ending" punctuation character to derive a short tab label. The regex `h1-punctuation-re` must not match a bare `-` because hyphenated words (e.g. "Multi-Cloud GPU Cost Report") are part of the title, not separators.

The original regex `#"[.,:;\(\)\u2014\u2013\-]"` matched `-` unconditionally, causing "Multi-Cloud GPU Cost Report - Cross-Provider Guide" to truncate at "Multi" (the first `-` in "Multi-Cloud").

**Fix (2026-03-31)**: Use a two-group pattern that only matches ` -` (space + hyphen, i.e. an em-dash surrogate used as a section separator) while leaving standalone hyphens in compound words intact:

```clojure
(def ^:private h1-punctuation-re
  #"( [\-])|([.,:;\(\)\u2014\u2013])")
```

Result: "Multi-Cloud GPU Cost Report - Cross-Provider Guide" → "Multi-Cloud GPU Cost Report".
