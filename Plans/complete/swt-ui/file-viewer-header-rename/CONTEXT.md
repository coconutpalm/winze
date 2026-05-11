---
created: 2026-03-27
related: [file-refresh]
tags: [swt, beholder, file-watcher, tabs]
---

# File Viewer Header Doesn't Update on Rename — Context

## Problem

When a file open in a tab is renamed on disk, `rename-open-tabs!` correctly
updates the CTabItem title and tooltip, but the HTML page displayed in the
Browser still shows the old relative path in its header bar.

Before rename: tab title = `RENAME-VERIFY-BEFORE.md`, header = `dev/RENAME-VERIFY-BEFORE.md`
After rename:  tab title = `RENAME-VERIFY-AFTER.md` ✓, header = `dev/RENAME-VERIFY-BEFORE.md` ✗

## Root Cause

`search/file-page` renders the relative path into the HTML as a static
`<div class="header">dev/path.md</div>`:

```clojure
;; winze-server/src/llm_memory/ui/search.clj
(defn file-page [markdown-text file-path]
  (str (h/html
        [:html ...
         [:body
          [:div.header file-path]   ; <-- baked in at render time
          [:div.result-body ...]]])))
```

`rename-open-tabs!` (in `main_window.clj`) currently only calls
`.setText` and `.setToolTipText` on the CTabItem, leaving the already-loaded
page unchanged.

## Options

### Option A — JavaScript DOM update (preferred)

After updating the tab title/tooltip, call `Browser.execute()` to patch the
header text in the live DOM, without reloading the page:

```javascript
var h = document.querySelector('.header');
if (h) h.textContent = 'dev/RENAME-VERIFY-AFTER.md';
```

**Pros**: No page reload, no scroll position loss, trivial to implement — one
`(.execute ctrl js)` call added to the existing `async-exec!` block.
**Cons**: Requires escaping the path for JavaScript string safety.

### Option B — Full page re-render

Re-slurp and re-render via `search/file-page` with the new rel-path, then
call `refresh-browser-with-scroll!` (which already handles scroll preservation).

**Pros**: Guaranteed consistency — page reflects current disk state.
**Cons**: More code path, slightly slower, re-reads file unnecessarily when
only the path changed (content is unchanged on rename).

## Decision

**Option A.** The content hasn't changed — only the path label needs updating.
A targeted DOM patch is simpler, faster, and consistent with the design
principle of not re-rendering when unnecessary.

## Path escaping

File paths on macOS/Linux should not contain single quotes in practice, but
`clojure.string/replace` should be used to escape any `'` → `\'` before
interpolating into the JS string literal. Alternatively, build the JS with
`clojure.string/escape` for the common unsafe JS characters.

## Affected file

`winze-server/src/llm_memory/ui/main_window.clj` — `rename-open-tabs!` only.
One line added inside the `async-exec!` doseq, after `.setToolTipText`.
