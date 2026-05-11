---
doc_type: plan
status: complete
group: code-block-line-height
created: 2026-03-31
related: [CODE-BLOCK-LINE-HEIGHT-CONTEXT.md]
---

# Code Block Line-Height Fix — Plan

## Goal

Fix broken vertical bars in code block box-drawing characters by overriding the
inherited `line-height: 1.7` on `.result-body pre`.

## Steps (all completed)

### 1. Add `line-height: normal` to `.result-body pre` ✓

File: `winze-server/src/llm_memory/ui/search.clj`, `page-css` function.

Initial attempt used `line-height: 1` — caused overlap and cramped text. Changed to
`line-height: normal` which uses the font's designed spacing.

### 2. Align mono font stack with SWT editor ✓

Updated all `font-family` declarations for monospace elements from
`'SF Mono', Menlo, monospace` to `'JetBrains Mono', 'Fira Code', 'Noto Sans Mono', Menlo, monospace`
matching the `mono-stack` in `resources.clj`.

### 3. Verified via REPL monkey-patching ✓

Used targeted function redefinition (`in-ns` + `defn-`) to redefine `page-css` in the
running server, then closed/reopened the viewer tab to pick up the new CSS. No full
namespace reload or server restart needed.

### 4. Visual verification ✓

- Retina display: box-drawing characters connect perfectly.
- 1080p display: minor sub-pixel gap remains — WebKit subpixel rounding, not fixable
  via CSS.

## Outcome

- Prose body text unchanged (`line-height: 1.7`).
- Code blocks use `line-height: normal` — readable and box-drawing chars connect on
  Retina displays.
- Font stack aligned between Browser CSS and SWT StyledText editor.
- Remaining 1x display gap accepted as a WebKit rendering artifact.
