---
created: 2026-03-31
status: complete
tags: [markdown, links, url, hiccup, file-viewer, swt, ui]
related: [complete/swt-ui/md-link-rewrite/CONTEXT.md]
---

# Markdown Relative Link Rewriting — Plan

**Pre-requisite**: Read `winze/Plans/SWT-UI-GUIDE.md` before beginning
implementation. It covers threading rules (the `ui` macro, `async-exec!`),
targeted namespace reload (§12, §21), and screenshot-based visual verification
(§14). §19 covers `Browser.evaluate` requiring explicit `return`.

## Phase 1: Link rewriting in hiccup.clj

### Step 1.1 — Add URL rewriting helpers

Two private functions in `hiccup.clj`:

- `local-md-link?` — predicate: true when `dest` is a relative `.md` path (not
  fragment-only, not absolute, not already-schemed, not non-markdown)
- `rewrite-local-link` — if ctx has `:root-uri` + `:file-dir` and dest is a
  local md link, resolves via `Paths.resolve().normalize()` and constructs
  `winze:open-file?root=...&path=...` URL. Preserves `#fragment` if present.

### Step 1.2 — Thread context through render-link

Modify `render-link` to call `rewrite-local-link`:

```clojure
(defn- render-link
  [ctx node]
  (let [dest  (.getDestination ^Link node)
        title (.getTitle ^Link node)
        href  (rewrite-local-link ctx dest)
        attrs (cond-> {:href href}
                (not (str/blank? title)) (assoc :title title))]
    (into [:a attrs] (walk-children ctx node))))
```

### Step 1.3 — Extend md->hiccup signature

Add a 3-arity overload accepting an options map (`{:root-uri :file-dir}`).
Existing 1-arity and 2-arity callers continue to work unchanged.

### Step 1.4 — Add imports

`[java.net URLEncoder]` and `[java.nio.file Paths]` to `(:import ...)`.

### Step 1.5 — RCF tests

8 tests covering: relative `.md` with/without context, fragment-only, absolute
HTTP, `../` traversal, `.md#fragment`, non-`.md` file, https with context.

### Step 1.6 — REPL verification

Load `hiccup.clj` with `:reload`, confirm all 19 RCF assertions pass.

## Phase 2: Pass context from callers

### Step 2.1 — search.clj: file-page

Add `file-dir` helper (extracts directory from rel-path). Update `file-page`
signature to `[markdown-text file-path & [metadata root-uri]]`. Pass
`{:root-uri root-uri :file-dir (file-dir file-path)}` to `md->hiccup`.

### Step 2.2 — search.clj: home-card

Pass `{:root-uri uri :file-dir (file-dir rel-path)}` to `md->hiccup`.

### Step 2.3 — search.clj: home-page

Pass `uri` as 4th arg to `file-page` in the single-home-file branch.

### Step 2.4 — main_window.clj

Pass `root-uri` as 4th arg to `search/file-page` (1-line change).

### Step 2.5 — result-card (no change)

Search result snippets render chunk excerpts, not full files. Links in snippets
pass through as-is.

## Phase 3: Rebuild and verify

### Step 3.1 — Rebuild server

`make install` from `winze-server/`. This builds the uberjar, copies it to
`~/.local/share/winze/`, and stops the running server. The proxy auto-starts
the new server on next MCP tool call.

**Important**: Kill the old server process if it doesn't terminate. The new jar
must be running for changes to take effect. Monkey-patching `custom-browser`
does NOT work for testing because the LocationListener closure is captured at
Browser construction time — existing browsers keep the old closure.

### Step 3.2 — Open a cross-referenced document

Open `CLOUD-GPU-GUIDE.md` in the file viewer programmatically:

```clojure
(let [root-uri "file:///Users/dorme/code/_finance"
      rel-path "guides/CLOUD-GPU-GUIDE.md"
      abs-path "/Users/dorme/code/_finance/Plans/guides/CLOUD-GPU-GUIDE.md"
      content  (slurp abs-path)
      html     (search/file-page content rel-path nil root-uri)
      title    (search/tab-title "CLOUD-GPU-GUIDE.md" content)]
  (async-exec! #(mw/open-tab! @tab-document-icon title html rel-path abs-path rel-path root-uri))
  :ok)
```

### Step 3.3 — Verify link counts

Use `Browser.evaluate` (with explicit `return`) to confirm:
- 52 `winze:open-file` links (rewritten relative `.md` refs)
- 14 `#fragment` links (Table of Contents, unchanged)
- 66 total links

### Step 3.4 — Click a relative link

Simulate via JavaScript: `document.querySelector('a[href^="winze:open-file"]').click()`

Verify: new tab opens with the target document ("GPU Report Migration").

### Step 3.5 — Verify chained navigation

Check the newly opened document also has rewritten links (confirms
`custom-browser`'s LocationListener passes `root-uri` through to `file-page`).

### Step 3.6 — Screenshot-verify

```clojure
(ui (llm-memory.ui.util/screenshot-widget! (element :main-window) "/tmp/link-rewrite.png"))
```

## Actual results

| Metric | Value |
|--------|-------|
| Total links in CLOUD-GPU-GUIDE.md | 66 |
| Rewritten to `winze:open-file` | 52 |
| Fragment-only (unchanged) | 14 |
| Chained links in opened target | 1 |
| RCF test assertions | 19 (all pass) |
| New lines of code | ~30 |
| Modified lines | ~7 |
| Files changed | 3 |

## Summary of changes

| File | Lines changed | Nature |
|------|--------------|--------|
| `hiccup.clj` | ~30 new | `local-md-link?`, `rewrite-local-link`, modified `render-link`, extended `md->hiccup` 3-arity, imports, 8 RCF tests |
| `search.clj` | ~10 modified | `file-dir` helper, `file-page` accepts `root-uri`, `home-card`/`home-page` pass context |
| `main_window.clj` | 1 modified | Pass `root-uri` to `file-page` |

No new library dependencies.
