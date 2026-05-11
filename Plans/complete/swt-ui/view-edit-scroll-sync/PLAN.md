---
created: 2026-03-30
status: complete
related: [CONTEXT.md, STYLEDTEXT-EDITOR-CONTEXT.md]
tags: [swt, ui, editor, scroll, caret, toggle]
---

# View ↔ Edit Scroll Sync — Plan

## Status (2026-03-30)

**All steps implemented and verified.** Both directions of scroll sync work.
New JAR built (`make install`) and server restarted. Screenshots confirm correct
behavior.

### Bugs found and fixed during implementation

1. **`Browser.evaluate()` returns null on macOS WebKit** — The JS `browser-top-line`
   used a bare IIFE `"(function(){...})()"` which SWT WebKit on macOS returns as null.
   Fixed by prefixing with `return`: `"return (function(){...})()"`. Rule: always
   start JS strings passed to `.evaluate` with `return`.

2. **`scroll-browser-to-line!` exact match fails** — `data-line` attributes are sparse
   (only block-start lines carry them). `document.querySelector('[data-line="N"]')`
   returns null when no element starts exactly on line N. Fixed to iterate all elements
   and find the last one with `data-line ≤ target` (enclosing block).

3. **Old JAR running without `hiccup.clj`** — Server was launched from a pre-`hiccup.clj`
   uberjar. `:reload` in nREPL finds compiled classes in the JAR, not source files.
   Fixed by `make install` + full server restart. Symptom: HTML lacking `data-line` attrs;
   confirmed via `document.querySelectorAll('[data-line]').length` → 0 before fix.

4. **`reset! nil` NPE for pre-reload open-files entries** — Tabs opened before the
   `main_window.clj` reload lacked `:scroll-state`. Fixed by closing and reopening
   tabs after the rebuild (new `open-tab!` always creates the atom).

5. **`wrapper-child` requires UI thread** — `.getChildren` is a UI-thread-only SWT
   call. Fixed by wrapping with `(ui ...)` when called from the nREPL thread.

---

## Prerequisite — Custom markdown→hiccup renderer

**Done.** `winze-server/src/llm_memory/ui/hiccup.clj` created; `search.clj` updated
to use `llm-memory.ui.hiccup/md->hiccup`; `deps.edn` updated with explicit
`org.commonmark/*` 0.24.0; `nextjournal/markdown` removed. All 12 RCF tests pass.

The `data-line` attributes (0-based source line on every block-level element) are
the coordinate bridge between Browser viewport and StyledText line numbers.

---

## Step 1 — Add `:scroll-state` to `open-files` entries ✓

**File**: `main_window.clj` — `open-tab!`

Added `:scroll-state (atom nil)` to the map stored in `open-files`. Schema when
populated:

```clojure
{:line   <int>   ;; 0-based source line at top of editor viewport
 :caret  <int>}  ;; character offset of cursor (edit mode only)
```

Written on edit→view, read on view→edit.

---

## Step 2 — JS helpers for Browser line position ✓

**File**: `main_window.clj`

### `browser-top-line`

Returns the 0-based source line of the topmost visible block in the Browser.
**Critical**: prefix JS with `return` or SWT WebKit returns null.

```clojure
(defn- browser-top-line [browser]
  (let [result (.evaluate browser
                  "return (function() {
                     var els = document.querySelectorAll('[data-line]');
                     for (var i = els.length - 1; i >= 0; i--) {
                       if (els[i].getBoundingClientRect().top <= 0) {
                         return parseInt(els[i].getAttribute('data-line'), 10);
                       }
                     }
                     return 0;
                   })()")]
    (if (number? result) (long result) 0)))
```

### `scroll-browser-to-line!`

Scrolls the Browser to the nearest `data-line` element at or before `line`.
Must be called via `ProgressAdapter.completed` (fires after `.setText` page load).

```clojure
(defn- scroll-browser-to-line! [browser line]
  (.addProgressListener browser
    (proxy [ProgressAdapter] []
      (completed [_event]
        (.removeProgressListener browser this)
        (.execute browser
          (str "var els = document.querySelectorAll('[data-line]'),"
               "    best = null, target = " line ";"
               "for (var i = 0; i < els.length; i++) {"
               "  if (parseInt(els[i].getAttribute('data-line'),10) <= target)"
               "    best = els[i];"
               "}"
               "if (best) best.scrollIntoView({block:'start'});"))))))
```

---

## Step 3 — StyledText scroll/caret helpers ✓

**File**: `markdown_editor.clj`

### `scroll-to-line!`

```clojure
(defn scroll-to-line! [styled-text line]
  (let [line (min line (dec (.getLineCount styled-text)))]
    (.setTopIndex styled-text line)
    (.setCaretOffset styled-text (.getOffsetAtLine styled-text line))))
```

### `line-visible?`

```clojure
(defn line-visible? [styled-text caret-offset]
  (let [text-len  (count (.getText styled-text))
        safe-off  (min caret-offset (max 0 (dec text-len)))
        line      (.getLineAtOffset styled-text safe-off)
        top       (.getTopIndex styled-text)
        client-h  (.height (.getClientArea styled-text))
        line-h    (.getLineHeight styled-text)
        vis-lines (if (pos? line-h) (quot client-h line-h) 30)]
    (<= top line (+ top vis-lines))))
```

---

## Step 4 — Update `toggle-mode!` to sync scroll ✓

**File**: `main_window.clj`

### view → edit

1. Read `browser-top-line` **before** disposing Browser → `from-line`
2. Dispose Browser, create StyledText, setText, apply-theme!
3. `async-exec!` (after `.layout wrapper`):
   - `(scroll-to-line! st from-line)`
   - If `@scroll-state` has `:caret` and `(line-visible? st caret)` → restore caret

### edit → view

1. Capture `{:line (.getTopIndex child) :caret (.getCaretOffset child)}` → `reset! scroll-state`
2. Dispose StyledText, create Browser, `(.setText brow html)`
3. `(scroll-browser-to-line! brow (:line @scroll-state))` — ProgressListener scrolls on load

---

## Step 5 — Verify ✓

### Verified scenarios

1. **view → edit** (first switch): browser at mid-doc → editor opens at same region.
   Caret on topmost visible line. Screenshot: `/tmp/sync2-edit.png`.

2. **edit → view**: editor at line 80 → browser scrolls to "CDT integration" region.
   Screenshot: `/tmp/sync4-view-back.png`.

### Remaining edge cases (not explicitly tested, handled by guards)

- **Short file / empty file**: `scroll-to-line!` clamps to `(dec (.getLineCount st))`;
  `line-visible?` uses `max 0 (dec text-len)` guard. No errors expected.
- **File modified externally**: caret offset clamped by `min` guard in toggle-mode!.
- **Browser not yet loaded**: `.evaluate` returns nil → fallback to line 0.

---

## Files changed

| File | Change |
|------|--------|
| `winze-server/src/llm_memory/ui/markdown_editor.clj` | Added `scroll-to-line!`, `line-visible?` |
| `winze-server/src/llm_memory/ui/main_window.clj` | Added `:scroll-state` to `open-tab!`; added `browser-top-line`, `scroll-browser-to-line!`; rewrote `toggle-mode!` |
| `winze-server/src/llm_memory/ui/hiccup.clj` | **New** — commonmark-java → Hiccup with `data-line` attrs |
| `winze-server/src/llm_memory/ui/search.clj` | Replaced `nextjournal.markdown` with `llm-memory.ui.hiccup` |
| `winze-server/deps.edn` | Removed `nextjournal/markdown`; added explicit `org.commonmark/*` 0.24.0 |
