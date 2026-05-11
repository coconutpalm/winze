# Content Assist & Link Preview — Sizing V2 (Remaining Fixes)

## Background

The first pass (archived in `Plans/complete/content-assist-sizing/`) implemented:
- Owner-draw Table with `MeasureItem`/`EraseItem`/`PaintItem`
- Per-card `scrollHeight` measurement + offscreen Shell resize
- `.setData "image"` instead of `.setImage`
- `resize-popup!` for dynamic popup Shell height
- Wider popups (600px content assist, 500px link preview)
- Removed `+10` fudge factor from link preview height
- `.result-card:last-child { margin-bottom: 0 }` CSS fix
- Fixed `[text](` paren trigger timing

User verification confirmed several fixes (paren trigger, symmetric padding,
arrow keys, Enter accepts) but identified five remaining issues.

## Issue 1 — Link preview not sized to entire card

**File**: `link_preview.clj`, `show-preview-at!` (line 167)

The link preview uses `document.body.scrollHeight` to auto-size the popup
Shell. After removing the `+10` fudge factor, the popup is now slightly
**shorter** than the card content — the bottom of the card is clipped.

**Root cause**: macOS WebKit's `scrollHeight` under-reports by a few pixels
due to sub-pixel rendering. The original `+10` was too much (created
visible dead space), but `+0` is too little.

**Fix**: Add a small fudge factor of `+4` — large enough to cover sub-pixel
under-reporting, small enough to be visually imperceptible.

## Issue 2 — Content assist screenshots all same height

**File**: `content_assist.clj`, `render-row-images!` (line 179)

All card screenshots are the same height despite the `scrollHeight`
measurement and offscreen Shell resize. This is the **critical bug** in
the V1 implementation.

**Root cause**: After resizing the offscreen Shell with `.setSize`, the
Browser inside it does **not** change size until the Shell's layout is
recalculated. SWT's `FillLayout` caches child bounds and only propagates
size changes on an explicit `.layout` call.

The render loop does:

```clojure
;; 1. Measure content height
(let [h-d (.evaluate browser "return document.body.scrollHeight;")]
  ;; 2. Resize Shell
  (.setSize shell (+ popup-width gutter) content-h)
  ;; 3. Screenshot — BUT browser still has OLD bounds!
  (let [img (screenshot-browser browser)] ...))
```

`screenshot-browser` reads `(.getBounds browser)` which returns the
Browser's own bounds — still the previous size because `.layout` was never
called. The screenshot captures at the stale height.

**Fix**: Add `(.layout shell true)` between `.setSize` and `screenshot-browser`.

## Issue 3 — Popup too small / only shows 2 cards

**File**: `content_assist.clj`

Two contributing factors:

### 3a — `popup-max-height` too low

With properly-sized card screenshots (once Issue 2 is fixed), each card
will be 100–200px tall. `popup-max-height` at 420px only fits 2–3 cards.
A larger value (e.g. 600) gives room for 4–5 cards while staying within
typical screen bounds.

### 3b — Initial popup height too small

The initial height is `(+ 30 24 12)` = 66px. This is just the filter
field. The popup starts tiny and grows as rows render, but the growth
happens so quickly the user perceives it as "too small" because:
- The resize only happens after each card's offscreen render completes
- On fast machines all renders complete within a few frames

A reasonable initial height estimate (e.g. filter + 3 rows × estimated
row height) avoids the visual "popping" effect and gives the popup a
stable starting size.

## Issue 4 — Selection highlight not visible

**File**: `content_assist.clj`, `EraseItem` listener (line 535)

The current `EraseItem` handler suppresses default foreground drawing but
**does not paint a selection highlight**:

```clojure
(.addListener tbl SWT/EraseItem
  (reify org.eclipse.swt.widgets.Listener
    (handleEvent [_ event]
      (set! (.-detail event)
            (bit-and (.-detail event) (bit-not SWT/FOREGROUND))))))
```

The original plan included selection highlight code but it was omitted.
With owner-draw, SWT's default selection painting is suppressed — we must
paint it ourselves or the user sees no visual feedback when navigating.

**Fix**: In `EraseItem`, check if `SWT/SELECTED` is set in `event.detail`.
If so, paint a semi-visible highlight rectangle using a brand color
(e.g. `color-royal-purple` — already available in `resources.clj`).

The `SWT/SELECTED` check must happen **before** clearing `SWT/FOREGROUND`,
because clearing detail bits can also clear SELECTED on some platforms.

## Issue 6 — Single-click does not accept selection

**File**: `content_assist.clj`, `SWT/Selection` listener (line 595) and
`SWT/DefaultSelection` listener (line 604)

Single-clicking a content assist row only updates the `:selected` index in
`popup-state` — it does **not** call `select-result!` to accept the selection
and close the popup. The user must **double-click** (which fires
`SWT/DefaultSelection`) to actually accept. This contradicts both typical
IDE content-assist behavior and the command palette's own single-click UX.

**Root cause**: The `SWT/Selection` and `SWT/DefaultSelection` listeners are
wired to different actions. `SWT/Selection` (single-click) only updates state:

```clojure
(.addListener tbl SWT/Selection
              (reify org.eclipse.swt.widgets.Listener
                (handleEvent [_ _e]
                  (let [items (.getSelection tbl)]
                    (when (seq items)
                      (when-let [idx (.getData (first items) "result-idx")]
                        (swap! popup-state assoc :selected (int idx))))))))
```

While `SWT/DefaultSelection` (double-click / Enter) actually accepts:

```clojure
(.addListener tbl SWT/DefaultSelection
              (reify org.eclipse.swt.widgets.Listener
                (handleEvent [_ _e]
                  (let [items (.getSelection tbl)]
                    (when (seq items)
                      (when-let [idx (.getData (first items) "result-idx")]
                        (swap! popup-state assoc :selected (int idx))
                        (select-result!)))))))
```

The command palette (`command_palette.clj`, line 222) handles this correctly —
it calls `execute-and-close!` directly from `SWT/Selection`:

```clojure
(.addListener tbl SWT/Selection
              (reify org.eclipse.swt.widgets.Listener
                (handleEvent [_ _event]
                  (execute-and-close! tbl sh))))
```

**Fix**: Move the `select-result!` call into the `SWT/Selection` listener so
single-click accepts. Remove the `SWT/DefaultSelection` listener (now
redundant, since `SWT/DefaultSelection` also fires `SWT/Selection` first on
most platforms — but keep it as a safety net if preferred).

## Issue 5 — Filter text widget low contrast

**File**: `content_assist.clj`, `open-content-assist!` (line 496)

The filter `Text` widget uses `color-mine-shaft` (#1E1B2E) background
and `color-crystal-white` (#E8E0FF) foreground. But its **selection
colors** use the OS defaults (blue highlight with white text), which
clashes with the dark theme.

**Fix**: Set `.setSelectionBackground` and `.setSelectionForeground` on
the Text widget to match the popup's palette. Use `color-royal-purple`
for selection background and `color-pure-white` for selection foreground
— the same combination used for the StyledText editor selection in
`markdown_editor.clj`.

## Files Affected

| File | Issues |
|------|--------|
| `link_preview.clj` | Issue 1 (fudge factor) |
| `content_assist.clj` | Issues 2, 3, 4, 5, 6 |

## Risks

- **Issue 2 fix** (`.layout shell true`): Adding a synchronous layout
  call inside the render loop means each screenshot takes slightly longer
  (~1ms for layout). With 8 rows this adds ~8ms total — imperceptible.
- **Issue 3a** (`popup-max-height` increase): A taller popup could
  extend below the screen on small displays. The position calculation
  in `position-below-caret` doesn't clamp to screen bounds. Consider
  adding screen-edge clamping if this causes issues.
- **Issue 4** (selection highlight): The highlight is painted via GC
  onto the Table row background. If the image has an opaque background
  (which it does — mine-shaft), the highlight won't show through the
  image. The highlight must be painted **before** the image in PaintItem,
  or we must use a semi-transparent overlay technique. However, since
  EraseItem runs before PaintItem, painting the highlight in EraseItem
  (behind the image) won't work either. **Alternative**: paint a colored
  **border** around the selected row instead of a fill, or draw a
  vertical accent bar on the left edge of the selected row in PaintItem.
  This avoids the opacity problem entirely.

  A second alternative: tint the screenshot image itself at render time.
  But this requires creating a new Image for the selected state, doubling
  memory. Not worth it for a transient highlight.

  **Recommended approach**: Left-edge accent bar in `PaintItem` — a 3-4px
  wide filled rectangle at `event.x, event.y` using `color-lavender`,
  drawn before the image. The bar sits in the Table's left padding area
  (the column is `popup-width - 8`, so there's 8px of space). This is
  visually distinct and works regardless of image opacity.
