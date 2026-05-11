---
doc_type: context
status: complete
group: code-block-line-height
created: 2026-03-31
---

# Code Block Line-Height Fix — Context

## Problem

The Winze markdown viewer renders `<pre>` / `<code>` blocks using a Browser widget
(WebKit). Code blocks containing Unicode box-drawing characters (U+2500–U+257F) — e.g.
the "Universal Pipeline Architecture" diagram in CLOUD-GPU-GUIDE — show **broken vertical
bars**: the `│` characters on consecutive lines have visible gaps instead of forming
continuous vertical lines.

## Root Cause

CSS inheritance. In `winze-server/src/llm_memory/ui/search.clj`, `page-css` set
`line-height: 1.7` on `.result-body` and `.result-body pre` inherited it. The extra
leading created visible gaps between box-drawing characters designed to connect
edge-to-edge.

## Investigation

### Approach 1: `line-height: 1` — too tight

Set `line-height: 1` on `.result-body pre`. This made code text difficult to read and
caused glyph overlap — the upper-right corner (`┐`) overlapped with the `│` below it
because glyph bounding boxes extend slightly beyond the em-square.

### Approach 2: `line-height: normal` — correct fix

Set `line-height: normal` on `.result-body pre`. This uses the font's designed line
spacing (~1.15–1.2× for most monospace fonts). Code text is readable with no overlap.

### Font stack investigation

The StyledText editor (SWT native) renders box-drawing characters with no gaps, using
`JetBrains Mono` (from `resources.clj` `mono-stack`). The Browser CSS used
`'SF Mono', Menlo, monospace`. We attempted to align the font stacks, but discovered:

- **WebKit in SWT cannot see JetBrains Mono, Fira Code, or SF Mono** — `getComputedStyle`
  resolves all to generic `monospace`. Only `Menlo` is available as a named font.
- All characters (box-drawing, ASCII, space) measure exactly 7.234375px wide — the
  misalignment is not a glyph-width issue.
- All source lines are exactly 68 characters — the source text is correct.
- The HTML rendering is faithful — no whitespace modification by the markdown pipeline.

### Display-dependent rendering

The remaining small gap with `line-height: normal` is **display-dependent**:

- **Retina (2x)**: Box-drawing characters connect correctly. A 14px line-height at 12px
  font maps to exactly 28 device pixels — no subpixel rounding.
- **1080p (1x)**: Small visible gaps between `│` characters. Fractional pixel values in
  glyph metrics get rounded, creating a sub-pixel gap that box-drawing characters can't
  span.

This is a WebKit subpixel rounding behavior, not fixable via CSS. The SWT StyledText
widget uses the OS text rendering pipeline which snaps glyphs to the pixel grid
differently, keeping box-drawing characters connected regardless of display scaling.

### Font embedding rejected

Embedding JetBrains Mono via `@font-face` with `file://` was considered but rejected —
it wouldn't work cross-platform or when the font isn't installed. The `line-height: normal`
fix is the correct CSS approach; the 1x display gap is a minor cosmetic artifact of the
WebKit rendering engine.

## Changes Made

**File**: `winze-server/src/llm_memory/ui/search.clj` — `page-css` function

1. Added `line-height: normal;` to `.result-body pre` rule
2. Updated all three `font-family` declarations (`.file-path`, `.result-body code`,
   `.frontmatter-block`) from `'SF Mono', Menlo, monospace` to
   `'JetBrains Mono', 'Fira Code', 'Noto Sans Mono', Menlo, monospace` — aligning with
   the SWT `mono-stack` in `resources.clj`. While WebKit currently only resolves `Menlo`
   from this stack, the alignment is correct for portability and future WebKit improvements.

## Related Work

- SWT-UI-GUIDE §20 was added during this task: documents the targeted function
  redefinition technique (`in-ns` + `defn`) for REPL-driven development.
