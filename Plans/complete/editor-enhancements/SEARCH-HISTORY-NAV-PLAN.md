---
created: 2026-03-31
doc_type: plan
group: search-history-nav
tags: [swt, ui, search, navigation, keybindings]
related: [search-keybindings, global-esc]
---

# Search History Navigation — Plan

## Overview

Add back/forward search history navigation to the winze live search page with toolbar arrows and keyboard shortcuts. See [SEARCH-HISTORY-NAV-CONTEXT.md](SEARCH-HISTORY-NAV-CONTEXT.md) for architecture details.

## Steps

### 1. Add history state and manipulation functions in `resources.clj`

Add to `resources.clj`:

```clojure
;; Search history: {:entries ["query1" "query2" ...], :position 0}
(defonce search-history (atom {:entries [] :position -1}))

;; Flag: true while programmatically restoring a history entry (suppresses push)
(defonce restoring-history? (atom false))
```

Add pure functions for history manipulation (testable with RCF):

```clojure
(defn history-push
  "Push a query onto the history stack, truncating any forward entries.
   Returns the new history state."
  [{:keys [entries position]} query]
  (let [truncated (subvec entries 0 (inc position))
        new-entries (conj truncated query)]
    {:entries new-entries :position (dec (count new-entries))}))

(defn history-back
  "Move back in history. Returns [new-state query-at-new-position] or nil if at start."
  [{:keys [entries position] :as state}]
  (when (pos? position)
    (let [new-pos (dec position)]
      [(assoc state :position new-pos) (entries new-pos)])))

(defn history-forward
  "Move forward in history. Returns [new-state query-at-new-position] or nil if at end."
  [{:keys [entries position] :as state}]
  (when (< position (dec (count entries)))
    (let [new-pos (inc position)]
      [(assoc state :position new-pos) (entries new-pos)])))

(defn can-go-back? [{:keys [position]}] (pos? position))
(defn can-go-forward? [{:keys [entries position]}]
  (< position (dec (count entries))))
```

Include `(tests ... :rcf)` blocks exercising push, back, forward, truncation on forward-push, and edge cases (empty history, single entry).

### 2. Create arrow icon resources in `resources.clj`

Add a function to programmatically draw chevron arrow icons using SWT GC, and define `defonce` delays for the back and forward icons (matching the `edit-icon` / `tab-document-icon` pattern):

```clojure
(defn- draw-chevron-image
  "Draw a chevron arrow icon at the given size.
  `direction` is :left or :right. Returns an Image (caller owns lifecycle)."
  [size direction]
  (ui
   (let [image (Image. @display size size)
         color (Color. @display 0x9B 0x8F 0xE0)]  ; Amethyst #9B8FE0
     (with-gc-on image
       (fn [gc]
         (.setAntialias gc SWT/ON)
         (.setLineCap gc SWT/CAP_ROUND)
         (.setLineJoin gc SWT/JOIN_ROUND)
         (.setForeground gc color)
         (.setLineWidth gc (if (>= size 32) 4 2))
         ;; Chevron points: < for left, > for right
         ;; Centered in the image with ~25% margin
         (let [margin (int (* size 0.3))
               mid    (int (/ size 2))
               tip-x  (if (= direction :left) margin (- size margin))
               top-x  (if (= direction :left) (- size margin) margin)
               bot-x  top-x]
           (.drawLine gc top-x margin tip-x mid)
           (.drawLine gc tip-x mid bot-x (- size margin)))))
     (.dispose color)
     image)))

(defn- chevron-hidpi-image
  "Create a HiDPI-aware chevron arrow image using ImageDataProvider."
  [direction]
  (ui (let [img-1x (draw-chevron-image 16 direction)
            img-2x (draw-chevron-image 32 direction)
            data-1x (.getImageData img-1x)
            data-2x (.getImageData img-2x)]
        (.dispose img-1x)
        (.dispose img-2x)
        (Image. @display
                (reify ImageDataProvider
                  (getImageData [_ zoom]
                    (if (>= zoom 200) data-2x data-1x)))))))

(defonce back-icon    (delay (chevron-hidpi-image :left)))
(defonce forward-icon (delay (chevron-hidpi-image :right)))
```

Key details:
- Uses `with-gc-on` (CDT's safe GC wrapper) to draw on the Image
- Amethyst `#9B8FE0` matches the brand palette primary accent (same family as the edit icon)
- `SWT/CAP_ROUND` + `SWT/JOIN_ROUND` for clean chevron endpoints
- 2px stroke at 16×16, 4px at 32×32 for consistent visual weight
- Temporary drawing Images are disposed; final HiDPI Image wraps captured `ImageData`
- SWT auto-generates greyed-out disabled appearance from the image

### 3. Add back/forward ToolItems to the existing toolbar

In `main_window.clj`, modify `setup-edit-toolbar!` to add navigation items with the new icons. The toolbar uses `SWT/FLAT` style (already the case):

```clojure
(defn- setup-edit-toolbar!
  []
  (let [folder  (element :main-folder)
        toolbar (ToolBar. ^Composite folder SWT/FLAT)
        back-btn    (ToolItem. toolbar SWT/PUSH)
        forward-btn (ToolItem. toolbar SWT/PUSH)
        _separator  (ToolItem. toolbar SWT/SEPARATOR)
        edit-btn    (ToolItem. toolbar SWT/PUSH)]
    ;; Back button
    (.setImage back-btn @back-icon)
    (.setToolTipText back-btn (if mac? "Back (⌘[)" "Back (Alt+Left)"))
    (.setEnabled back-btn false)
    (.addSelectionListener back-btn
      (proxy [SelectionAdapter] []
        (widgetSelected [_e] (navigate-back!))))

    ;; Forward button
    (.setImage forward-btn @forward-icon)
    (.setToolTipText forward-btn (if mac? "Forward (⌘])" "Forward (Alt+Right)"))
    (.setEnabled forward-btn false)
    (.addSelectionListener forward-btn
      (proxy [SelectionAdapter] []
        (widgetSelected [_e] (navigate-forward!))))

    ;; Edit button (unchanged)
    (.setImage edit-btn @edit-icon)
    (.setToolTipText edit-btn (accel-label "Edit" "E"))
    (.setEnabled edit-btn false)

    (.setTopRight folder toolbar)
    (swap! app-props assoc
           :ui/back-button back-btn
           :ui/forward-button forward-btn
           :ui/edit-button edit-btn)
    ;; ... existing tab selection listener and edit click handler
    ))
```

### 4. Implement navigation functions in `main_window.clj`

```clojure
(defn- update-nav-buttons!
  "Enable/disable back and forward buttons based on history state."
  []
  (let [state @resources/search-history]
    (when-let [back (element :back-button)]
      (.setEnabled back (resources/can-go-back? state)))
    (when-let [fwd (element :forward-button)]
      (.setEnabled fwd (resources/can-go-forward? state)))))

(defn- navigate-back! []
  (when-let [[new-state query] (resources/history-back @resources/search-history)]
    (reset! resources/search-history new-state)
    (reset! resources/restoring-history? true)
    (.setSelection (element :main-folder) 0)
    (.setText (element :search) query)
    ;; modify-text fires synchronously from setText, so reset flag after
    (reset! resources/restoring-history? false)
    (update-nav-buttons!)))

(defn- navigate-forward! []
  (when-let [[new-state query] (resources/history-forward @resources/search-history)]
    (reset! resources/search-history new-state)
    (reset! resources/restoring-history? true)
    (.setSelection (element :main-folder) 0)
    (.setText (element :search) query)
    (reset! resources/restoring-history? false)
    (update-nav-buttons!)))
```

**Important**: `.setText` fires `modify-text` synchronously on the UI thread (SWT behavior), so the `restoring-history?` flag is correctly scoped — set before, cleared after, no race condition.

### 5. Wire history-push into the modify-text handler

In the `header` function's `modify-text` handler, add history recording after the search is dispatched:

```clojure
(on e/modify-text [props parent event]
    ;; ... existing force-live-search-to-view and tab-switch logic ...
    (let [q (str/trim (.getText (element :search)))]
      (search/results q (element :live-search-browser) set-live-search-content!)
      ;; Record in history (unless restoring from back/forward)
      (when (and (not @resources/restoring-history?)
                 (>= (count q) 3))
        (swap! resources/search-history resources/history-push q)
        (update-nav-buttons!))))
```

**Debounce consideration**: History records on every qualifying keystroke, not on debounced search completion. This matches the "what the user typed" mental model. Adjacent duplicates could be filtered in `history-push` if desired:

```clojure
(defn history-push [{:keys [entries position] :as state} query]
  (if (and (>= position 0) (= query (entries position)))
    state  ; don't push duplicate of current entry
    (let [truncated (subvec entries 0 (inc (max 0 position)))
          ...])))
```

### 6. Register keyboard shortcuts in the Display key filter

Add to the `cond` in the Display-level `SWT/KeyDown` filter:

```clojure
;; Cmd+[ or Alt+Left — navigate back
(or (and cmd? (= (.character event) \[))
    (and alt? (= (.keyCode event) SWT/ARROW_LEFT)))
(do (set! (.-doit event) false)
    (async-exec! navigate-back!))

;; Cmd+] or Alt+Right — navigate forward
(or (and cmd? (= (.character event) \]))
    (and alt? (= (.keyCode event) SWT/ARROW_RIGHT)))
(do (set! (.-doit event) false)
    (async-exec! navigate-forward!))
```

Add `alt?` binding at the top of the `handleEvent`:
```clojure
alt? (not= 0 (bit-and mod SWT/ALT))
```

### 7. Clear history on Esc

In the existing Esc handler, reset history state so the user starts fresh:

```clojure
;; Esc — focus search, clear selection, reset history
(= (.keyCode event) (int SWT/ESC))
(do (set! (.-doit event) false)
    (async-exec!
     (fn []
       (.setSelection (element :main-folder) 0)
       (.setText (element :search) "")
       (.setFocus (element :search))
       (reset! resources/search-history {:entries [] :position -1})
       (update-nav-buttons!))))
```

### 8. Test via REPL

1. Start the winze server nREPL
2. Load modified namespaces with `:reload`
3. Verify RCF tests pass for `history-push`, `history-back`, `history-forward`
4. Use synthetic key events (`Display.post`) to test:
   - Type several searches, verify back button enables
   - Click/shortcut back, verify search field restores and forward enables
   - Click/shortcut forward, verify search field restores
   - Type new search while mid-history, verify forward history is truncated
   - Press Esc, verify both buttons disable
5. Screenshot-verify toolbar appearance:
   - Both nav buttons disabled (initial state)
   - Back enabled, forward disabled (after typing several searches)
   - Both enabled (after navigating back)
   - Arrow icons render cleanly in amethyst on the dark background, matching the edit icon style

## Refinements (defer unless needed)

- **History size cap**: Add a max-entries (e.g. 100) to `history-push` that drops oldest entries
- **Search-scope interaction**: When scope filtering is implemented, decide whether scope changes are separate history entries
- **Debounced history push**: Only record after the search actually executes (more complex wiring, probably not worth it)
