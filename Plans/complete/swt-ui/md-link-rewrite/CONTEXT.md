---
created: 2026-03-31
status: complete
tags: [markdown, links, url, hiccup, file-viewer, swt, ui]
related: [complete/swt-ui/file-viewer/CONTEXT.md, complete/swt-ui/md-hiccup-renderer/CONTEXT.md]
---

# Markdown Relative Link Rewriting — Context

## Problem

Markdown documents viewed in the Winze file viewer contain relative links to
other planning documents. For example, `CLOUD-GPU-GUIDE.md` has ~50 links like:

```markdown
[OCI GPU Report — Context](../complete/gpu-report/CONTEXT.md)
[AWS GPU Report — Plan](../dev/deferred/AWS-GPU-REPORT-PLAN.md)
```

These links render as clickable `<a>` tags in the Browser widget, but **clicking
them crashes the server** because:

1. File-viewer HTML is loaded via `Browser.setText(html)`, not from a file URL
2. WebKit has no base URL to resolve relative paths against
3. The `LocationListener` in `main_window.clj` only dispatches on `winze:` scheme
   URLs — unrecognized URLs (bare relative paths) fall through and WebKit
   attempts to navigate, which crashes the SWT Browser

The same links work correctly in search results because search results construct
explicit `winze:open-file?root=...&path=...` URLs in the Hiccup (see
`search.clj:252-256`). The file viewer's Markdown renderer did not have the
same translation.

## Solution

Rewrite relative `.md` links to `winze:open-file?` URLs during Markdown → Hiccup
conversion. The `ctx` map already threaded through the AST walker carries
`:root-uri` and `:file-dir` when rendering file-page content. `render-link`
delegates to `rewrite-local-link` which:

1. Checks if the link is a local `.md` reference (not fragment-only, not
   absolute, not already-schemed, not a non-markdown file)
2. Resolves the relative path against the file's directory using
   `java.nio.file.Paths.resolve().normalize()`
3. Constructs the `winze:open-file?root=...&path=...` URL
4. Preserves any `#fragment` suffix

### Rendering pipeline (after)

```
Markdown text
    ↓
hiccup.clj:md->hiccup(text, line-offset, {:root-uri ... :file-dir ...})
    ↓ ctx = {:line-offset N :root-uri "file:///..." :file-dir "guides"}
hiccup.clj:render-link(ctx, node)
    → rewrite-local-link(ctx, dest)
    → [:a {:href "winze:open-file?root=...&path=..."} ...children...]
    ↓
search.clj:file-page(markdown-text, file-path, metadata, root-uri)
    → wraps in [:html [:head ...] [:body ... (md->hiccup body fm-offset opts)]]
    ↓
Browser.setText(html)
    ↓
User clicks link → LocationListener fires (existing handler) → opens new tab
```

### What is NOT rewritten

- Fragment-only links: `#section-heading` (in-page anchors)
- Absolute URLs: `http://...`, `https://...`
- Already-schemed URLs: `winze:...`, `mailto:...`, `file:...`
- Non-markdown files: `.png`, `.jpg`, `.pdf`, etc.

### Chained navigation

Because `custom-browser`'s LocationListener passes `root-uri` through to
`file-page` when opening linked files, the target document also gets its
relative links rewritten. Users can navigate through chains of cross-referenced
documents seamlessly.

## Files Changed

| File | Change |
|------|--------|
| `winze-server/src/llm_memory/ui/hiccup.clj` | Added `local-md-link?` predicate, `rewrite-local-link` helper, modified `render-link` to call it, extended `md->hiccup` with 3-arity accepting `{:root-uri :file-dir}` opts, added `URLEncoder`/`Paths` imports, added 8 RCF tests |
| `winze-server/src/llm_memory/ui/search.clj` | Added `file-dir` helper, updated `file-page` signature to accept optional `root-uri`, updated `home-card` and `home-page` to pass `root-uri` |
| `winze-server/src/llm_memory/ui/main_window.clj` | 1-line change: pass `root-uri` to `search/file-page` call |

## Lessons Learned

- **Clicking unhandled URLs in the SWT Browser crashes the server.** The
  LocationListener's `e/changing` handler only covers `winze:` scheme URLs.
  Bare relative paths fall through to WebKit's default navigation, which crashes
  because the content was loaded via `.setText()` with no base URL. Any future
  link types must be explicitly handled or blocked.

- **`Browser.evaluate` requires an explicit `return` statement.** SWT wraps the
  JavaScript in an anonymous function, so without `return`, the result is always
  `nil`. Added as §19 in SWT-UI-GUIDE.md.

- **Monkey-patching `custom-browser` has limited effect.** The LocationListener
  closure is captured at Browser construction time. Redefining `custom-browser`
  only affects new Browser widgets — the search results browser keeps its old
  closure. For testing, rebuild and restart the server (`make install`) rather
  than trying to hot-patch closures.

- **`file-page` signature uses `& [metadata root-uri]`** (optional positional
  args). All existing callers that passed only `metadata` continue to work
  unchanged — `root-uri` defaults to `nil`, which disables link rewriting.

## Dependencies

- `java.nio.file.Paths` — for `../` resolution and normalization
- `java.net.URLEncoder` — for URL-encoding root-uri and path parameters
- No new library dependencies
