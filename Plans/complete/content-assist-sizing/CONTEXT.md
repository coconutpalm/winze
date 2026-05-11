# Content Assist & Link Preview — Sizing and Trigger Fixes

## Background

The content assist popup and link preview popup share a common card-rendering
pipeline (`search/card-html`). Both currently clip or mis-size the rendered
cards. Additionally, the `[text](` paren trigger for content assist has a
timing bug analogous to the `[[` trigger bug fixed in the editor-cleanup work.

**Prior work**: The editor-cleanup crash fix (archived in
`Plans/complete/editor-cleanup/`) resolved the "Widget is disposed" crash by:
1. Replacing `.open` with `.setVisible true` on the offscreen Shell
2. Adding disposal guards in the render loop
3. Incrementing `render-generation` on popup close

The offscreen screenshot pipeline now works — images display correctly. This
follow-on work addresses sizing and layout.

## Problem 1 — `[Link text here](` trigger not firing

**File**: `markdown_editor.clj`, `handle-paren-trigger!` (line 556)

Same class of timing bug as the `[[` trigger. The `(` key handler uses
`async-exec!` to defer `handle-paren-trigger!`:

```clojure
(and (not cmd?) (= ch \())
(async-exec! #(handle-paren-trigger! st abs-path))
```

By the time `handle-paren-trigger!` runs, the `(` has been inserted. The text
is `[Link text here](` with the caret **after** the `(`. But
`extract-bracket-text` checks `(.charAt text (dec caret))` expecting `]` —
it finds `(` instead, returning nil.

The RCF tests (line 521-527) verify the pre-insertion caret position (after
`]`) but the actual runtime call happens at the post-insertion position (after
`(`).

## Problem 2 — Content assist card clipping

**File**: `content_assist.clj`

**Current constants**: `popup-width` = 450px, `row-height` = 80px.

The card HTML has `body { padding: 16px; }` and `.result-card { padding: 12px
16px; }`. At 80px total height, only the file-path header and the first line
of the heading text are visible — the heading wraps and is clipped.

**Root causes**:
1. **Fixed row height** (80px) — too short for cards with wrapped headings
2. **Uniform Table row height** — SWT Table with `.setImage` uses the tallest
   image for ALL rows, wasting space for shorter cards
3. **No per-card height measurement** — the offscreen Browser is always sized
   to `row-height`, so screenshots are clipped at 80px regardless of content
4. **Popup too narrow** (450px) — forces header text to wrap

## Problem 3 — Link preview sizing issues

**File**: `link_preview.clj`

The link preview uses the Snippet 372 auto-sizing technique
(`document.body.scrollHeight`), but has two bugs:

### Bottom border bigger than top

The card CSS has:
- `body { padding: 16px; }` — 16px on all four sides
- `.result-card { margin-bottom: 12px; }` — for spacing between cards in lists

For a standalone card (the only use case for link preview and content assist),
the bottom space is 16px (body padding) + 12px (card margin-bottom) = 28px,
versus 16px at the top. This creates visible asymmetry.

### Height fudge factor adds extra space

The height calculation at line 187-192 adds `+ 10`:

```clojure
height (int (min preview-max-height
                 (+ 10 (if (number? height-d)
                          (long height-d)
                          preview-initial-height))))
```

Since `scrollHeight` already includes all body padding and child margins, the
`+ 10` adds unnecessary extra space at the bottom.

### Preview too narrow

`preview-width` = 380px, which forces long file paths and headings to wrap.

## Solution: Owner-Draw Table with Auto-Sized Screenshots

### Content assist popup

Replace the fixed-height Image-based Table with an owner-draw Table that
supports per-row variable heights.

**Owner-draw architecture** (three SWT events on the Table):

1. **`SWT.MeasureItem`** — SWT calls this to determine each cell's size.
   Read the Image from `item.getData("image")`, return its dimensions as
   `event.width` / `event.height`. Each row gets its own height.

2. **`SWT.EraseItem`** — suppress default foreground drawing
   (`event.detail &= ~SWT.FOREGROUND`). Optionally paint a selection
   highlight background.

3. **`SWT.PaintItem`** — draw the Image:
   `event.gc.drawImage(img, event.x, event.y)`.

**Image storage**: Use `.setData "image" img` instead of `.setImage img`.
This prevents the Table from imposing uniform heights.

**Screenshot pipeline change**: After the offscreen Browser renders each card,
measure `document.body.scrollHeight` via `.evaluate`, resize the Browser to
that height (capped at ~200px), then screenshot. Each Image is the natural
height of its card content.

**Popup auto-sizing**: After each row renders, recompute total height = sum of
all image heights + filter field height + margins. Resize the popup Shell
(capped at `popup-max-height`). This replaces the static height calculation.

**Disposal**: `dispose-table-images!` must read from `.getData "image"` instead
of `.getImage`. The three disposal paths are unchanged:
1. Table DisposeListener → `dispose-table-images!` (popup close)
2. Re-render clear → `dispose-table-images!` + `.removeAll` (new search)
3. Generation counter / disposal guard (render loop cancellation — no image
   created, no disposal needed)

No new disposal paths are needed. The image lifecycle is: created in
`screenshot-browser`, attached to TableItem via `.setData` in the same
synchronous UI-thread block, disposed via `dispose-table-images!`.

### Link preview popup

1. Remove the `+ 10` fudge factor from the height calculation
2. Eliminate card `margin-bottom` for standalone cards via CSS
   `.result-card:last-child { margin-bottom: 0; }` — preserves spacing
   in multi-card results lists, removes it for single-card popups
3. Widen `preview-width` from 380 to ~500px

### Card CSS fix (shared)

Add `.result-card:last-child { margin-bottom: 0; }` to `page-css` in
`search.clj`. This benefits both the link preview and the content assist
screenshots — a standalone card no longer has dead space at the bottom.

## Files Affected

| File | Changes |
|------|---------|
| `markdown_editor.clj` | Fix paren trigger timing (pass `(dec caret)`) |
| `content_assist.clj` | Owner-draw Table, auto-sized screenshots, wider popup |
| `link_preview.clj` | Remove `+ 10` fudge, widen preview |
| `search.clj` | Add `.result-card:last-child { margin-bottom: 0; }` CSS |

## Risks

- **Owner-draw on macOS** — `SWT.MeasureItem` maps to
  `NSTableView.heightOfRow:` on Cocoa. This should support per-row variable
  heights, but needs REPL verification. Fallback: use uniform height =
  tallest image (current behavior but with auto-measured height instead of
  fixed 80px).
- **`scrollHeight` accuracy** — WebKit's `scrollHeight` occasionally
  under-reports by a few pixels due to sub-pixel rendering. A 2-4px fudge
  may be needed (much less than the current 10px).
- **Popup auto-resize flicker** — resizing the Shell after each row renders
  could cause visual flicker. Mitigation: batch the resize — only resize
  after the first row and after the last row, not on every intermediate row.
