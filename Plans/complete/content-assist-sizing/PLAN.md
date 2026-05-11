# Content Assist & Link Preview — Sizing and Trigger Fixes Plan

**Context**: [_CONTENT-ASSIST-SIZING-CONTEXT.md](_CONTENT-ASSIST-SIZING-CONTEXT.md)

---

## Step 1 — Fix `[text](` trigger timing

**File**: `winze-server/src/llm_memory/ui/markdown_editor.clj`

In `handle-paren-trigger!` (line 556-575), the `async-exec!` callback runs
after the `(` has been inserted. The caret is after `(`, not after `]`.

Replace:

```clojure
(let [text  (.getText st)
      caret (.getCaretOffset st)]
  (when-let [link-text (extract-bracket-text text caret)]
    ...))
```

With:

```clojure
(let [text  (.getText st)
      caret (.getCaretOffset st)]
  ;; After async-exec!, ( is already inserted and caret is after it.
  ;; Verify the ( is there, then look before it for ]...[.
  (when (and (pos? caret) (= \( (.charAt text (dec caret))))
    (when-let [link-text (extract-bracket-text text (dec caret))]
      ...)))
```

**Verify**: Type `[some text](` in the editor. Content assist popup should
open with "some text" as the search seed.

---

## Step 2 — Fix card CSS bottom margin

**File**: `winze-server/src/llm_memory/ui/search.clj`

Add to `page-css` after the `.result-card` rule:

```css
.result-card:last-child { margin-bottom: 0; }
```

This eliminates the 12px dead space at the bottom of standalone cards (link
preview, content assist screenshots) while preserving inter-card spacing in
multi-card results lists.

**Verify**: Link preview popup should have symmetric top/bottom padding.

---

## Step 3 — Fix link preview sizing

**File**: `winze-server/src/llm_memory/ui/link_preview.clj`

**3a** — Remove the `+ 10` fudge from the height calculation (line 189-192):

```clojure
;; OLD:
height (int (min preview-max-height
                 (+ 10 (if (number? height-d) ...))))
;; NEW:
height (int (min preview-max-height
                 (if (number? height-d)
                   (long height-d)
                   preview-initial-height)))
```

**3b** — Widen `preview-width` from 380 to 500 (line 46).

**Verify**: Link preview auto-sizes tightly around the card. No extra bottom
space. Wider card shows full file paths without wrapping.

---

## Step 4 — Widen the content assist popup

**File**: `winze-server/src/llm_memory/ui/content_assist.clj`

Change `popup-width` from 450 to 600 (line 40).

Update the offscreen Shell size accordingly — `ensure-offscreen-browser!`
sets the Shell width to `popup-width + 20` (line 106) and
`measure-scrollbar-gutter!` resizes to `popup-width + gutter` (line 138).
These automatically follow `popup-width`, so no additional changes needed.

Update the Table column width: `(.setWidth col (- popup-width 8))` (line 489)
— also follows `popup-width` automatically.

**Verify**: Content assist popup is wider. Card headers have more room.

---

## Step 5 — Auto-size screenshot height per card

**File**: `winze-server/src/llm_memory/ui/content_assist.clj`

Modify the render loop (`render-row-images!`) so that each card is
screenshotted at its natural content height instead of the fixed `row-height`.

In the `ProgressListener.completed` callback, after measuring the scrollbar
gutter (first iteration) and before calling `screenshot-browser`:

1. Measure the content height:
   ```clojure
   (let [h-d (.evaluate browser "return document.body.scrollHeight;")
         content-h (int (min max-row-height
                             (if (number? h-d) (long h-d) row-height)))]
     ...)
   ```

2. Resize the offscreen Browser (and its Shell) to the measured height:
   ```clojure
   (when-let [{:keys [^Shell shell]} @offscreen-state]
     (when-not (.isDisposed shell)
       (.setSize shell
                 (+ popup-width (or @scrollbar-gutter 0))
                 content-h)))
   ```

3. Take the screenshot at the new height. Since `.setSize` is synchronous on
   the UI thread and `screenshot-browser` reads `.getBounds`, the screenshot
   captures the full card.

Add a constant for the maximum row height:
```clojure
(def ^:private max-row-height 200)
```

Keep the existing `row-height` (80) as the initial offscreen Browser height
and as a fallback.

**Verify**: Screenshot images have varying heights matching their card content.
Short cards produce short images; cards with long headings produce taller ones.

---

## Step 6 — Owner-draw Table for variable-height rows

**File**: `winze-server/src/llm_memory/ui/content_assist.clj`

### 6a — Change image storage from `.setImage` to `.setData`

In `render-row-images!`, replace:
```clojure
(.setImage item img)
```
With:
```clojure
(.setData item "image" img)
```

### 6b — Update `dispose-table-images!`

Replace `.getImage` with `.getData "image"`:
```clojure
(defn- dispose-table-images! [^Table table]
  (doseq [item (.getItems table)]
    (when-let [img (.getData item "image")]
      (when (instance? Image img)
        (when-not (.isDisposed ^Image img)
          (.dispose ^Image img))))))
```

### 6c — Add owner-draw listeners to the Table

In `open-content-assist!`, after creating the Table and column, add three
listeners:

**MeasureItem** — sets per-row height from the image:
```clojure
(.addListener tbl SWT/MeasureItem
  (reify org.eclipse.swt.widgets.Listener
    (handleEvent [_ event]
      (when-let [item (.item event)]
        (when-let [img (.getData item "image")]
          (when (instance? Image img)
            (let [bounds (.getBounds ^Image img)]
              (set! (.-width event) (.width bounds))
              (set! (.-height event) (.height bounds)))))))))
```

**EraseItem** — suppress default drawing, paint selection background:
```clojure
(.addListener tbl SWT/EraseItem
  (reify org.eclipse.swt.widgets.Listener
    (handleEvent [_ event]
      (set! (.-detail event)
            (bit-and (.-detail event) (bit-not SWT/FOREGROUND)))
      ;; Paint selection highlight
      (when (not= 0 (bit-and (.-detail event) SWT/SELECTED))
        (let [gc     (.-gc event)
              bounds (.getBounds (.item event))]
          (.setBackground gc @resources/color-deep-amethyst)
          (.fillRectangle gc (.x bounds) (.y bounds)
                          (.width bounds) (.height bounds)))))))
```

**PaintItem** — draw the image:
```clojure
(.addListener tbl SWT/PaintItem
  (reify org.eclipse.swt.widgets.Listener
    (handleEvent [_ event]
      (when-let [item (.item event)]
        (when-let [img (.getData item "image")]
          (when (and (instance? Image img)
                     (not (.isDisposed ^Image img)))
            (.drawImage (.-gc event) ^Image img
                        (.-x event) (.-y event))))))))
```

### 6d — Remove `.setImage` call from Table column setup

The `TableColumn` width and the `.setHeaderVisible false` / `.setLinesVisible
false` settings remain. Remove any `(.setImage ...)` calls — images are now
owner-drawn.

**Verify**: Open content assist. Rows have different heights matching their
card content. Selection highlight works on click and arrow keys.

---

## Step 7 — Auto-size popup Shell height

**File**: `winze-server/src/llm_memory/ui/content_assist.clj`

Replace the static popup height calculation in `open-content-assist!` with
a dynamic resize after each row renders.

Add a helper:
```clojure
(defn- resize-popup!
  "Resize the popup Shell to fit the filter field + current Table content.
  Capped at popup-max-height."
  []
  (when-let [{:keys [^Shell shell ^Table table ^Text filter-text]} @popup-state]
    (when (and shell (not (.isDisposed shell)))
      (let [filter-h (+ (.y (.computeSize filter-text SWT/DEFAULT SWT/DEFAULT)) 8)
            table-h  (reduce (fn [h item]
                               (if-let [img (.getData item "image")]
                                 (+ h (.height (.getBounds ^Image img)))
                                 h))
                             0 (.getItems table))
            total    (min popup-max-height (+ filter-h table-h 20))]
        (.setSize shell popup-width (int total))))))
```

Call `resize-popup!` after each `TableItem` is created in the render loop
(inside the `completed` callback, after `.setData item "image" img`).

For the initial open, use a minimal height (filter field + margin) and let it
grow as rows render. This avoids showing a large empty popup.

**Verify**: Popup starts small and grows as images render. Final height matches
content. Never exceeds `popup-max-height`.

---

## Implementation Order

**Sequential** (single developer, REPL verification after each step):

1. Step 2 (CSS fix) — shared, benefits both popups immediately
2. Step 3 (link preview sizing) — quick win, isolated file
3. Step 1 (paren trigger) — isolated to markdown_editor.clj
4. Step 4 (widen popup) — constant change, immediate visual effect
5. Step 5 (auto-size screenshots) — render loop modification
6. Step 6 (owner-draw Table) — most complex, builds on Step 5
7. Step 7 (auto-size popup Shell) — final polish, builds on Step 6

**Parallel** (subagent team, three file-writing agents):

| Agent | Files | Steps |
|-------|-------|-------|
| A | `search.clj`, `link_preview.clj` | Steps 2, 3 |
| B | `markdown_editor.clj` | Step 1 |
| C | `content_assist.clj` | Steps 4, 5, 6, 7 |

Phase 2: single REPL owner verifies all changes after `make install`.

---

## Verification Checklist

| Test | Expected |
|------|----------|
| Type `[text](` | Content assist opens with "text" as search seed |
| Link preview hover | Symmetric padding, no extra bottom space, wider card |
| Content assist rows | Variable heights, full card content visible |
| Content assist selection | Highlight visible on arrow keys and click |
| Content assist dismiss | No image leaks (Table DisposeListener fires) |
| New search while rendering | Old images disposed, new render starts cleanly |
| Long card content | Capped at max-row-height (~200px), not infinite |
| Popup height | Grows with content, capped at popup-max-height |
