---
created: 2026-03-30
status: active
related: [MD-HICCUP-RENDERER-CONTEXT.md, STYLEDTEXT-EDITOR-CONTEXT.md]
tags: [swt, ui, editor, syntax-highlighting, md-theme]
---

# Code Block Line Background Bug — Context

## Problem Statement

In the Winze markdown editor (`markdown-editor.clj`), the dark bedrock background color is
not applied to lines inside a code block that are entirely covered by a multi-line token span
(e.g., a multi-line Clojure string literal). The lines appear in the lighter mine-shaft color
instead.

**Visible symptom**: In the view-edit-scroll-sync plan document, the `browser-top-line` code
block contains a multi-line JavaScript string. The `for` loop lines (82–86 in the document)
appear with a lighter background than the surrounding code block lines.

## Color Values

- **Bedrock** (code block background): `rgb(14, 13, 24)` — very dark near-black
- **Mine-shaft** (editor default background): `rgb(30, 27, 46)` — darker purple, visibly lighter than bedrock

## Root Cause

### Theming Pipeline

1. `md-theme/theme(text)` calls `parse-blocks` → `highlight/tokenize` → `split-around`
2. Code blocks are split into non-overlapping spans: `:code-block` fragments (gaps between
   syntax tokens) and `:token/*` spans (keyword, string, comment, etc.)
3. All spans — both `:code-block` and `:token/*` — include `:bg res/color-bedrock` in their
   `StyleRange`, so character-level backgrounds are correct.

### How Line Backgrounds Are Applied

`apply-code-block-line-backgrounds!` in `markdown-editor.clj`:
1. Resets ALL line backgrounds to `nil` (mine-shaft) with `.setLineBackground(0, n, nil)`
2. Iterates spans with `(= type :code-block)` only
3. Calls `.setLineBackground(start-line, count, bedrock)` for lines containing those spans
   (plus ±1 fence lines to include the opening/closing ` ``` `)

### The Gap

For a multi-line string like:

```clojure
(.evaluate browser
  "return (function() {
     var els = document.querySelectorAll('[data-line]');
     for (var i = els.length - 1; i >= 0; i--) {
       if (els[i].getBoundingClientRect().top <= 0) {
         return parseInt(els[i].getAttribute('data-line'), 10);
       }
     }
     return 0;
   })()"]
```

The Clojure tokenizer produces a **single** `:token/string` span from the opening `"` to the
closing `"`, covering 413 characters across lines 80–88 of the document.

After `theme()` splits the code block:
- `:code-block` fragments exist before the string (lines 78–80) and after it (lines 88–89)
- The `:token/string` span at offsets 3004–3416 covers **lines 80–88** entirely

The last `:code-block` fragment before the string sets bedrock for lines up to ~81.
The first `:code-block` fragment after the string sets bedrock for lines from ~87.

**Lines 82–86 (the `for` loop body) have no `:code-block` fragment** — they are
entirely within the `:token/string` span. `apply-code-block-line-backgrounds!` never
calls `setLineBackground(bedrock)` for those lines. After the initial reset, they retain
mine-shaft background.

### Why Character-Level Styling Is Not Enough

`StyleRange.background` paints the background behind the character glyphs only. The
trailing whitespace to the right of the last character on a line (and line padding) is
governed by the **line background** set via `setLineBackground`. Without it, trailing space
shows mine-shaft instead of bedrock, creating a visible band of lighter color on the right
side of those lines.

## Files Involved

| File | Role |
|------|------|
| `winze-server/src/llm_memory/ui/md_theme.clj` | `parse-blocks` (private), `theme()` |
| `winze-server/src/llm_memory/ui/markdown_editor.clj` | `apply-code-block-line-backgrounds!`, `apply-theme!` |
| `winze-server/src/llm_memory/highlight/clojure.clj` | Clojure tokenizer (multi-line string regex) |

## Confirmed Data Points

- `:token/string` span: offset 3004, length 413, covering **lines 80–88**
- `:code-block` spans near the string set line backgrounds up to line ~81 and from line ~87
- **Gap: lines 82–86** — no `setLineBackground(bedrock)` call

## Fix Approach

Make `parse-blocks` public in `md-theme.clj` and pass the raw block boundaries to
`apply-code-block-line-backgrounds!` instead of the post-themed spans. Raw blocks capture
the full extent of each code block before tokenization splits it into fragments. This
ensures ALL lines in a code block receive `setLineBackground(bedrock)`, regardless of
whether they are covered by token spans.

See `CODE-BLOCK-LINE-BG-PLAN.md` for implementation steps.
