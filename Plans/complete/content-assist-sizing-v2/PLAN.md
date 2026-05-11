# Content Assist & Link Preview — Sizing V2 Plan

**Context**: [_CONTENT-ASSIST-SIZING-V2-CONTEXT.md](_CONTENT-ASSIST-SIZING-V2-CONTEXT.md)

---

## Step 1 — Fix link preview under-sizing (+4 fudge)

**File**: `winze-server/src/llm_memory/ui/link_preview.clj`

In `show-preview-at!` (line 189), add `+4` to the measured `scrollHeight`:

```clojure
;; CURRENT:
height   (int (min preview-max-height
                   (if (number? height-d)
                     (long height-d)
                     preview-initial-height)))

;; NEW:
height   (int (min preview-max-height
                   (+ 4 (if (number? height-d)
                           (long height-d)
                           preview-initial-height))))
```

**Verify**: Hover a wiki link. Card content should not clip at the bottom.

---

## Step 2 — Add `.layout` after offscreen Shell resize

**File**: `winze-server/src/llm_memory/ui/content_assist.clj`

In `render-row-images!` (line 209-213), add `.layout` after `.setSize`:

```clojure
;; CURRENT:
(when-let [{:keys [^Shell shell]} @offscreen-state]
  (when-not (.isDisposed shell)
    (.setSize shell
              (+ popup-width (or @scrollbar-gutter 0))
              content-h)))

;; NEW:
(when-let [{:keys [^Shell shell]} @offscreen-state]
  (when-not (.isDisposed shell)
    (.setSize shell
              (+ popup-width (or @scrollbar-gutter 0))
              content-h)
    (.layout shell true)))
```

This forces the FillLayout to propagate the new Shell size to the
Browser before `screenshot-browser` reads `(.getBounds browser)`.

**Verify**: Open content assist. Card screenshots should have varying
heights matching their content. Short cards produce short rows; cards
with long headings produce taller rows.

---

## Step 3 — Increase `popup-max-height` and initial height

**File**: `winze-server/src/llm_memory/ui/content_assist.clj`

### 3a — Increase max height

Line 41: Change `popup-max-height` from 420 to 600:

```clojure
(def ^:private popup-max-height 600)
```

### 3b — Use a reasonable initial height

In `open-content-assist!` (line 636), replace the minimal initial
height with a larger estimate:

```clojure
;; CURRENT:
(let [initial-h (+ 30 24 12)] ;; margins + filter field + padding

;; NEW:
(let [initial-h (min popup-max-height (+ 30 24 (* 3 row-height) 12))]
    ;; margins + filter field + 3 estimated rows + padding
```

This opens the popup at ~300px (filter + 3×80px rows + chrome), which
is big enough to feel intentional. `resize-popup!` will adjust to actual
content as screenshots arrive.

**Verify**: Content assist popup opens at a comfortable size. After
rendering completes, it should show 4–5 cards instead of 2.

---

## Step 4 — Selection highlight (left-edge accent bar)

**File**: `winze-server/src/llm_memory/ui/content_assist.clj`

The owner-draw image is opaque, so a background fill highlight won't be
visible. Instead, draw a left-edge accent bar in `PaintItem`.

Replace the `PaintItem` listener (line 540-548):

```clojure
;; CURRENT:
_          (.addListener tbl SWT/PaintItem
                         (reify org.eclipse.swt.widgets.Listener
                           (handleEvent [_ event]
                             (when-let [item (.item event)]
                               (when-let [img (.getData item "image")]
                                 (when (and (instance? Image img)
                                            (not (.isDisposed ^Image img)))
                                   (.drawImage (.-gc event) ^Image img
                                               (.-x event) (.-y event))))))))

;; NEW:
_          (.addListener tbl SWT/PaintItem
                         (reify org.eclipse.swt.widgets.Listener
                           (handleEvent [_ event]
                             (when-let [item (.item event)]
                               ;; Draw the card image
                               (when-let [img (.getData item "image")]
                                 (when (and (instance? Image img)
                                            (not (.isDisposed ^Image img)))
                                   (.drawImage (.-gc event) ^Image img
                                               (.-x event) (.-y event))))
                               ;; Draw selection accent bar on the left edge
                               (when (not= 0 (bit-and (.-detail event) SWT/SELECTED))
                                 (let [gc     (.-gc event)
                                       bounds (.getBounds item)
                                       old-bg (.getBackground gc)]
                                   (.setBackground gc ^Color @resources/color-lavender)
                                   (.fillRectangle gc
                                                   (.x bounds) (.y bounds)
                                                   4 (.height bounds))
                                   (.setBackground gc old-bg)))))))
```

The 4px lavender bar on the left edge of the selected row provides clear
visual feedback without obscuring the card image.

**Verify**: Arrow up/down in content assist. Selected row shows a
lavender accent bar on the left edge.

---

## Step 5 — Filter text selection colors

**File**: `winze-server/src/llm_memory/ui/content_assist.clj`

In `open-content-assist!`, after setting the Text widget properties
(line 496-502), add selection colors:

```clojure
;; CURRENT:
filter-txt (doto (Text. sh SWT/SINGLE)
             (.setBackground bg)
             (.setForeground fg)
             (.setFont font)
             (.setMessage "Search pages...")
             (.setLayoutData filter-gd)
             (.setText (or seed-text "")))

;; NEW:
filter-txt (doto (Text. sh SWT/SINGLE)
             (.setBackground bg)
             (.setForeground fg)
             (.setFont font)
             (.setMessage "Search pages...")
             (.setLayoutData filter-gd)
             (.setText (or seed-text ""))
             (.setSelectionBackground ^Color @resources/color-royal-purple)
             (.setSelectionForeground ^Color @resources/color-pure-white))
```

**Note**: `SWT.Text` supports `.setSelectionBackground` /
`.setSelectionForeground` only on some platforms. On macOS Cocoa, these
methods exist but may be no-ops — the OS controls selection appearance
for native text fields. If this doesn't work, the fallback is to replace
the `Text` widget with a `StyledText` (which always honors selection
colors), or accept the OS default.

**Verify**: Select text in the filter field. Selection should use
royal-purple background with white text. If the OS overrides, accept
the default and remove the calls.

---

## Step 6 — Single-click accepts selection

**File**: `winze-server/src/llm_memory/ui/content_assist.clj`

Move `select-result!` into the `SWT/Selection` listener (line 595) so that
a single click accepts the selection and closes the popup — matching the
command palette's behavior (`command_palette.clj`, line 222).

Replace the two listeners (lines 595-611):

```clojure
;; CURRENT — two separate listeners:
;; Single-click: only updates state
(.addListener tbl SWT/Selection
              (reify org.eclipse.swt.widgets.Listener
                (handleEvent [_ _e]
                  (let [items (.getSelection tbl)]
                    (when (seq items)
                      (when-let [idx (.getData (first items) "result-idx")]
                        (swap! popup-state assoc :selected (int idx))))))))

;; Double-click / Enter: accepts
(.addListener tbl SWT/DefaultSelection
              (reify org.eclipse.swt.widgets.Listener
                (handleEvent [_ _e]
                  (let [items (.getSelection tbl)]
                    (when (seq items)
                      (when-let [idx (.getData (first items) "result-idx")]
                        (swap! popup-state assoc :selected (int idx))
                        (select-result!)))))))

;; NEW — single listener, single-click accepts:
(.addListener tbl SWT/Selection
              (reify org.eclipse.swt.widgets.Listener
                (handleEvent [_ _e]
                  (let [items (.getSelection tbl)]
                    (when (seq items)
                      (when-let [idx (.getData (first items) "result-idx")]
                        (swap! popup-state assoc :selected (int idx))
                        (select-result!)))))))
```

The `SWT/DefaultSelection` listener is removed entirely — it's redundant
because `SWT/Selection` fires before `SWT/DefaultSelection` for both
double-click and Enter, so the single-click listener already covers those
cases. (Enter key acceptance from the filter field is handled separately
via the KeyDown filter, not via Table `DefaultSelection`.)

**Verify**: Open content assist, single-click a row. The link should be
inserted and the popup should close immediately — no double-click needed.
Also verify Enter still works from both the filter field and the Table.

---

## Implementation Order

Sequential (single developer, REPL verification after each step):

1. Step 2 (`.layout` fix) — critical bug, enables all other fixes
2. Step 1 (link preview fudge) — quick constant change
3. Step 3 (height constants) — depends on Step 2 being correct
4. Step 6 (single-click accepts) — interaction fix, pairs with Step 4
5. Step 4 (selection accent bar) — visual polish
6. Step 5 (text selection colors) — visual polish

Steps 1, 5, and 6 are independent of everything else. Steps 2→3 are
sequential. Step 4 is independent. Step 6 is placed before Step 4
because the accent bar is visual feedback for keyboard navigation —
once single-click accepts immediately, the accent bar matters most
for arrow-key browsing.

---

## Verification Checklist

| Test | Expected |
|------|----------|
| Link preview hover | Card fully visible, no bottom clipping |
| Content assist card heights | Vary per card (short cards shorter, long cards taller) |
| Content assist row count | 4–5 visible cards, not 2 |
| Content assist initial size | Opens at comfortable ~300px, not 66px flash |
| Single-click row | Link inserted, popup closes immediately |
| Double-click row | Same as single-click (no regression) |
| Enter from filter field | Accepts selected row |
| Arrow key selection | Lavender accent bar on left edge of selected row |
| Filter text selection | Royal-purple highlight (or acceptable OS default) |
| Dismiss / new search | No image leaks, popup resizes correctly |
| Long card content | Capped at max-row-height (200px), not infinite |
