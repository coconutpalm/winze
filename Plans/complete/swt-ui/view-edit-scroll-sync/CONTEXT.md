---
created: 2026-03-30
status: complete
related: [STYLEDTEXT-EDITOR-CONTEXT.md, MD-THEME-CONTEXT.md]
tags: [swt, ui, editor, scroll, caret, toggle]
---

# View ↔ Edit Scroll Sync — Context

## Status (2026-03-30)

**Implementation complete and verified.** Both directions of scroll sync work.
Source files fully updated; new JAR built and installed.

## Goal

Synchronize scroll position and cursor/caret between the Browser (view mode)
and StyledText (edit mode) when toggling modes in a file tab. The user should
feel like they're looking at the same region of the document regardless of which
mode they're in.

Three scenarios:

1. **First switch to edit** — derive scroll position from the Browser's current
   viewport. Place the caret on the topmost visible line.
2. **Edit → view** — remember the editor's scroll position and caret. Scroll the
   Browser to the same region.
3. **Subsequent edit returns** — derive scroll from the Browser (which may have
   been scrolled independently). If the old caret position is still visible in
   the new viewport, restore it; otherwise place the caret on the topmost
   visible line.

## Implementation

### Files changed

| File | Change |
|------|--------|
| `winze-server/src/llm_memory/ui/markdown_editor.clj` | Added `scroll-to-line!`, `line-visible?` |
| `winze-server/src/llm_memory/ui/main_window.clj` | Added `:scroll-state` to `open-tab!`; added `browser-top-line`, `scroll-browser-to-line!`; rewrote `toggle-mode!` |
| `winze-server/src/llm_memory/ui/hiccup.clj` | **New** — commonmark-java → Hiccup renderer with `data-line` attrs |
| `winze-server/src/llm_memory/ui/search.clj` | Replaced `nextjournal.markdown` with `llm-memory.ui.hiccup` |
| `winze-server/deps.edn` | Removed `nextjournal/markdown`; added explicit `org.commonmark/*` 0.24.0 |

### Per-tab scroll state

`:scroll-state (atom nil)` added to each entry in `open-files`. When populated:

```clojure
{:line   <int>    ;; 0-based source line at top of editor viewport
 :caret  <int>}   ;; character offset of cursor (edit mode only)
```

Written on edit→view, read on view→edit.

### HTML `data-line` prerequisite

`search/file-page` now uses `llm-memory.ui.hiccup/md->hiccup` (commonmark-java
AST walker) which annotates every block-level element with `{:data-line N}`
(0-based source line). Renders as `data-line="N"` in HTML.

## Scroll primitives

### Browser → line number

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

**WebKit note**: `Browser.evaluate(String)` on macOS requires `return` at the
outermost level. `"(function(){...})()"` returns `null`; `"return (function(){...})()"`
returns the value. Always prefix with `return`.

### Line number → Browser scroll

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

**data-line sparsity**: Only block-starts carry `data-line` attributes (headings,
paragraphs, lists, code blocks, etc). Exact match on a line number will often
miss. Use "last element with data-line ≤ target" to find the enclosing block.

Must be called after `(.setText browser html)` — the `ProgressAdapter.completed`
fires when the page finishes loading.

### Line number → StyledText scroll

```clojure
(defn scroll-to-line! [styled-text line]
  (let [line (min line (dec (.getLineCount styled-text)))]
    (.setTopIndex styled-text line)
    (.setCaretOffset styled-text (.getOffsetAtLine styled-text line))))
```

### Viewport visibility check (caret restoration)

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

## toggle-mode! logic

### view → edit

1. Call `browser-top-line` on the Browser **before** disposing it → `from-line`
2. Dispose Browser, create StyledText
3. `async-exec!` (runs after `.layout wrapper`):
   - `(scroll-to-line! st from-line)`
   - If `@scroll-state` has a `:caret` and it's visible (`line-visible?`), restore it

### edit → view

1. Capture `{:line (.getTopIndex child) :caret (.getCaretOffset child)}` → `scroll-state`
2. Dispose StyledText, create Browser, `(.setText brow html)`
3. `(scroll-browser-to-line! brow (:line @scroll-state))`
   — registers ProgressListener that scrolls on page-load

## Interaction with undo/redo

`:scroll-state` is orthogonal to `:history` — it holds cross-mode state for
toggle transitions, not within-editor undo history. No conflict.

## Interaction with the file watcher

`refresh-open-tabs!` handles scroll preservation within each mode independently.
The cross-mode scroll state is not involved in watcher refreshes.
