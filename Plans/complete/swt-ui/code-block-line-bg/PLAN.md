---
created: 2026-03-30
status: active
related: [CODE-BLOCK-LINE-BG-CONTEXT.md]
tags: [swt, ui, editor, syntax-highlighting, md-theme]
---

# Code Block Line Background Bug — Plan

## Goal

Fix the mine-shaft background appearing on lines inside code blocks that are entirely
covered by multi-line token spans (e.g., multi-line string literals in Clojure code).

## Steps

### Step 1 — Make `parse-blocks` public in `md-theme.clj`

Change `defn-` to `defn` for `parse-blocks`.

```clojure
;; Before
(defn- parse-blocks ...)

;; After
(defn parse-blocks ...)
```

**Why**: `apply-code-block-line-backgrounds!` in `markdown-editor.clj` needs the raw block
boundaries (before tokenization) to know the full extent of each code block.

### Step 2 — Update `apply-code-block-line-backgrounds!` signature and body

Change the function to accept raw `blocks` (from `parse-blocks`) instead of the post-themed
`spans`. The raw blocks give the complete code block extent regardless of token fragmentation.

```clojure
;; Before — processes only :code-block fragments from themed spans
(defn- apply-code-block-line-backgrounds!
  [styled-text spans]
  ...
  (doseq [{:keys [start length type]} spans
          :when (= type :code-block)]
    ...))

;; After — processes raw blocks, which have full code block extents
(defn- apply-code-block-line-backgrounds!
  [styled-text blocks]
  (let [line-count (.getLineCount styled-text)
        bedrock    @res/color-bedrock]
    (.setLineBackground styled-text 0 line-count nil)
    (doseq [{:keys [start length type]} blocks
            :when (= type :code-block)]
      (let [first-code-line (offset->line styled-text start)
            last-code-line  (offset->line styled-text (+ start (max 0 (dec length))))
            start-line      (max 0 (dec first-code-line))
            end-line        (min (dec line-count) (inc last-code-line))
            num-lines       (inc (- end-line start-line))]
        (.setLineBackground styled-text start-line num-lines bedrock)))))
```

**Note**: The `dec`/`inc` fence-line calculation is preserved — it includes the ` ``` ` fence
lines above and below the code content. This is correct for raw blocks too: `parse-blocks`
sets `:start` to the first code line (after the fence) and `:length` to the code content only
(excluding the closing fence), so `dec first-code-line` adds the opening fence and
`inc last-code-line` adds the closing fence.

### Step 3 — Update `apply-theme!` to call `parse-blocks` and pass to line-bg function

```clojure
(defn apply-theme!
  "Compute the markdown theme for `text` and apply StyleRanges to `styled-text`.
  Also sets full-line backgrounds for code blocks. Must be called on the UI thread."
  [styled-text text]
  (let [blocks (md-theme/parse-blocks text)
        spans  (md-theme/theme text)
        ranges (into-array StyleRange (map span->style-range spans))]
    (.setStyleRanges styled-text ranges)
    (apply-code-block-line-backgrounds! styled-text blocks)))
```

**Note**: `theme()` already calls `parse-blocks` internally, so this adds one extra pass.
This is acceptable — both calls are cheap (linear text scan, no I/O).

### Step 4 — Test in REPL

Reload `md-theme` and `markdown-editor`, then apply theme to the editor and screenshot:

```clojure
(require '[llm-memory.ui.md-theme :as md-theme] :reload)
(require '[llm-memory.ui.markdown-editor :as editor] :reload)
(swt/sync-exec!
  (fn []
    (editor/apply-theme! st (.getText st))
    (llm-memory.ui.util/screenshot-widget! st "/tmp/winze-after-fix.png")))
```

Verify that lines 82–86 (the `for` loop lines inside the multi-line string) now show the
same bedrock background as the surrounding code block lines.

### Step 5 — Run tests

```bash
cd winze/winze-server && make test
```

Verify all RCF tests in `md-theme` and `markdown-editor` still pass.

## Acceptance Criteria

- Lines inside multi-line token spans (strings, comments spanning multiple lines) have
  bedrock background matching the rest of the code block
- The opening and closing ` ``` ` fence lines still have bedrock background
- Non-code-block lines still have mine-shaft (default) background
- All existing RCF tests pass

## Risk: `parse-blocks` Called Twice

`apply-theme!` now calls `parse-blocks` (via `md-theme/parse-blocks`) separately from
`theme()` (which calls it internally). This is a minor performance consideration but
acceptable since `parse-blocks` is a simple linear text scan with no I/O.

**Alternative** if performance becomes a concern: modify `theme()` to return `[blocks spans]`
instead of just `spans`. This avoids the double parse. Defer this refactor unless profiling
shows it matters.
