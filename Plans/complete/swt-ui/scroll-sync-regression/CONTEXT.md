---
created: 2026-03-30
status: complete
related: [complete/file-viewer-header/CONTEXT.md, complete/view-edit-scroll-sync/CONTEXT.md]
tags: [swt, ui, scroll, regression, frontmatter, data-line]
---

# Scroll Sync Regression — Context

## Problem Statement

After the file-viewer-header changes (which strip YAML frontmatter from the body
before passing to `md->hiccup`), view ↔ edit scroll sync is broken for files with
frontmatter. The editor opens to a position several lines above the browser viewport.

Example: browser shows Step 4 at the top of the viewport. After toggling to edit, Step 4
is the ~9th visible line — the editor scrolled too high.

## Root Cause

The scroll sync system uses `data-line` attributes on HTML block elements as the
coordinate bridge between Browser viewport and StyledText line numbers (see
`complete/view-edit-scroll-sync/CONTEXT.md`).

**Before the header change**: `file-page` passed the full markdown text (including
frontmatter) to `md->hiccup`. The `data-line` values matched the source file line
numbers 1:1. The editor and browser agreed on what "line N" meant.

**After the header change**: `file-page` strips frontmatter via `parse-frontmatter`
before passing the body to `md->hiccup`. The commonmark parser now sees the body
starting at line 0, but in the full source file that content starts at line N
(where N = number of frontmatter lines). The `data-line` attributes are offset
by the frontmatter line count.

### Concrete example

For `FILE-VIEWER-HEADER-PLAN.md`:
- Frontmatter = 5 lines (lines 0–4: `---`, 3 YAML lines, `---`)
- Body starts at source line 5 (a blank line), heading at source line 6
- `md->hiccup` assigns `data-line="1"` to the heading (body-relative)
- Editor line for the same heading = 6 (absolute)
- **Offset = 5 lines**

### Affected code paths

1. **view → edit** (`browser-top-line` → `scroll-to-line!`):
   `browser-top-line` reads `data-line` from HTML (body-relative). That value is passed
   directly to `scroll-to-line!` which calls `.setTopIndex` on the StyledText (expects
   absolute line). Editor scrolls too high by `frontmatter-line-count` lines.

2. **edit → view** (`scroll-state :line` → `scroll-browser-to-line!`):
   `toggle-mode!` captures `.getTopIndex` (absolute line) and passes it to
   `scroll-browser-to-line!` which searches for `data-line ≤ target` in the HTML.
   Since the HTML `data-line` values are smaller (body-relative), it finds a match
   too far down in the document. Browser scrolls too low.

### Files without frontmatter

Files without frontmatter are unaffected — `parse-frontmatter` returns `[{}, text]`
with the original text unchanged, so `data-line` values still match source line numbers.

## Fix Strategy

The offset is a constant for each file: `frontmatter-line-count`. The fix must add
this offset when converting from browser `data-line` → editor line, and subtract it
when converting from editor line → browser `data-line`.

Two approaches:

### Option A: Offset at the toggle boundary (minimal change)

In `toggle-mode!`, compute the frontmatter line count from the file content and
apply the offset:

- **view → edit**: `(scroll-to-line! st (+ from-line fm-offset))`
- **edit → view**: `(scroll-browser-to-line! brow (- editor-line fm-offset))`

Requires `file-page` or `open-files` to store the offset, or re-compute it from
the file content at toggle time.

### Option B: Offset at the `data-line` source (transparent)

Pass the frontmatter line count to `md->hiccup` and add it to every `data-line`
attribute at render time. The HTML `data-line` values become absolute source lines
again, and all existing scroll code works unchanged.

Requires a small change to `hiccup.clj:block-attrs` and threading the offset
through `md->hiccup`.

### Recommendation

**Option B** is safer — it restores the `data-line` = source-line invariant that
the entire scroll sync system was designed around. Option A works but creates a
hidden coupling: any future code reading `data-line` must remember to adjust.
