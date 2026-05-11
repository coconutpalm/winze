---
created: 2026-03-30
status: complete
related: [CONTEXT.md, complete/file-viewer-header/CONTEXT.md]
tags: [swt, ui, scroll, regression, frontmatter, data-line]
---

# Scroll Sync Regression — Plan

## Step 1 — Add `line-offset` parameter to `md->hiccup` ✓

**File**: `winze-server/src/llm_memory/ui/hiccup.clj`

Added `:line-offset` to the `ctx` map that is already threaded through all
rendering functions. `block-attrs` now reads `(:line-offset ctx)` and adds it
to every `data-line` value. Default 0 via `(or (:line-offset ctx) 0)`.

`md->hiccup` gained a 2-arity overload:

```clojure
(defn md->hiccup
  ([markdown-text] (md->hiccup markdown-text 0))
  ([markdown-text line-offset]
   (let [doc (.parse parser (or markdown-text ""))]
     (node->hiccup {:line-offset line-offset} doc))))
```

All internal callers of `block-attrs` (`render-heading`, `render-paragraph`,
`render-code-block`, `render-list`, and inline sites in `node->hiccup`) updated
to pass `ctx` as the first argument.

## Step 2 — Compute frontmatter line count in `file-page` ✓

**File**: `winze-server/src/llm_memory/ui/search.clj`

In `file-page`, computed `fm-offset` from the difference in line counts between
the full text and the body after frontmatter stripping:

```clojure
(let [[_fm body] (frontmatter/parse-frontmatter markdown-text)
      raw-yaml   (extract-raw-yaml markdown-text)
      fm-offset  (- (count (str/split-lines markdown-text))
                    (count (str/split-lines body)))]
  ;; ...
  [:div.result-body (hiccup/md->hiccup body fm-offset)])
```

Verified: for a file with 5-line frontmatter, `data-line` on the `# heading`
was 6 (matching source line 6 in the full file).

Search result cards continue to call `(hiccup/md->hiccup text)` with default
offset 0 — chunk text has no frontmatter.

## Step 3 — Verify with screenshots ✓

Monkey-patched the running server via `load-file` (no `:reload-all`).

1. Opened `FILE-VIEWER-HEADER-PLAN.md`, scrolled browser to Step 4 ✓
2. Toggled view → edit — Step 4 heading at top of editor ✓
3. Toggled edit → view — Step 4 heading at top of browser ✓

Screenshots: `/tmp/sync-fix-1-view.png`, `/tmp/sync-fix-2-edit.png`,
`/tmp/sync-fix-3-view-back.png`
