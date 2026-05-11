# Editor Cleanup — Plan

**Prerequisites**:
- Read `winze/Plans/SWT-UI-GUIDE.md` before implementation
- See [_EDITOR-CLEANUP-CONTEXT.md](_EDITOR-CLEANUP-CONTEXT.md) for architecture
  and root cause analysis

---

## Fix 1 — Content Assist: Screenshot-Based Row Rendering

Replace the 8 heavyweight Browser rows with a Table widget displaying
screenshot Images rendered from a single offscreen Browser.

### Step 1 — Add offscreen Browser infrastructure

**File**: `winze-server/src/llm_memory/ui/content_assist.clj`

1. Add a `defonce` for the offscreen Shell and Browser (shared across popup
   openings, like the link preview shell):

   ```clojure
   (defonce ^:private offscreen-state (atom nil))
   ;; {:shell Shell :browser Browser}
   ```

2. Add `offscreen-position` — computes coordinates guaranteed to be beyond
   all connected monitors:

   ```clojure
   (defn- offscreen-position
     "Return [x y] beyond the right edge of all monitors."
     []
     (let [monitors (.getMonitors (Display/getDefault))
           max-x    (reduce (fn [mx m]
                              (let [b (.getBounds m)]
                                (max mx (+ (.x b) (.width b)))))
                            0 monitors)]
       [(+ max-x 100) 0]))
   ```

3. Add `ensure-offscreen-browser!` — creates the offscreen Shell at the
   computed offscreen position. The Shell contains a **disabled Composite**
   wrapping the Browser:

   ```
   Shell (FillLayout)
   └── Composite (FillLayout, .setEnabled false)
       └── Browser (SWT/WEBKIT)
   ```

   The disabled Composite prevents the Shell from receiving focus via
   alt-tab — `setEnabled(false)` propagates to all children, blocking
   input and activation. Opens the Shell so the Browser can render.
   Returns the Browser.

4. Add `dispose-offscreen!` — closes and disposes the offscreen Shell. Called
   on application shutdown.

**Verify**: REPL — call `ensure-offscreen-browser!`, confirm Shell is open,
confirm Browser is not disposed, confirm Shell bounds are beyond all
monitors. Alt-tab through windows and confirm the offscreen Shell never
appears in the window list.

---

### Step 2 — Add screenshot function

**File**: `winze-server/src/llm_memory/ui/content_assist.clj`

1. Add `measure-scrollbar-gutter!` — after the first page renders in the
   offscreen Browser, measure the scrollbar gutter width dynamically and
   cache it. This runs once per application session:

   ```clojure
   (defonce ^:private scrollbar-gutter (atom nil))

   (defn- measure-scrollbar-gutter!
     "Measure the scrollbar gutter width by comparing the Browser widget
     width to the HTML body's rendered width. Caches the result."
     [^Browser browser]
     (when-not @scrollbar-gutter
       (let [widget-w  (.width (.getBounds browser))
             body-w-d  (.evaluate browser "return document.body.scrollWidth;")
             body-w    (if (number? body-w-d) (long body-w-d) widget-w)
             gutter    (max 0 (- widget-w body-w))]
         (reset! scrollbar-gutter gutter)))
     @scrollbar-gutter)
   ```

   Once the gutter is known, resize the offscreen Shell so the Browser is
   `popup-width + gutter` wide — this makes the body render at exactly
   `popup-width`, and the screenshot trims the gutter cleanly.

2. Add `screenshot-browser` — captures the Browser widget as an Image,
   trimming the measured scrollbar gutter:

   ```clojure
   (defn- screenshot-browser
     "Take a screenshot of a Browser widget, trimming the scrollbar gutter.
     Returns a new Image (caller must dispose)."
     [^Browser browser]
     (let [bounds  (.getBounds browser)
           gutter  (or @scrollbar-gutter 0)
           w       (- (.width bounds) gutter)
           h       (.height bounds)
           src-img (Image. (.getDisplay browser) (.width bounds) h)
           dst-img (Image. (.getDisplay browser) w h)]
       (try
         (let [gc (GC. src-img)]
           (try (.print browser gc) (finally (.dispose gc))))
         (let [gc (GC. dst-img)]
           (try
             (.drawImage gc src-img 0 0 w h 0 0 w h)
             (finally (.dispose gc))))
         dst-img
         (finally (.dispose src-img)))))
   ```

   Note: `.print(GC)` on Browser renders the current page content into the
   GC. Verify this works on macOS WebKit via REPL.

3. Wire the measurement into the render loop: the first iteration calls
   `measure-scrollbar-gutter!` inside its `ProgressListener.completed`
   callback (the page is fully rendered at that point). Subsequent
   iterations reuse the cached value.

**Verify**: REPL — render HTML in the offscreen Browser, confirm
`@scrollbar-gutter` is a reasonable value (e.g. 15-17 on macOS, 0 if no
scrollbar). Call `screenshot-browser`, save the Image to a file, confirm
no scrollbar gutter is visible.

---

### Step 3 — Add continuation-based render loop

**File**: `winze-server/src/llm_memory/ui/content_assist.clj`

1. Add a **generation counter** for render loop cancellation:

   ```clojure
   (defonce ^:private render-generation (atom 0))
   ```

   Each new search increments this. The render loop checks it before each
   iteration and aborts if stale.

2. Add `render-row-images!` — the trampoline loop that renders each result
   as an Image and **appends it to the Table progressively**:

   ```clojure
   (defn- render-row-images!
     "Render results as Images via the offscreen Browser.
     Appends each Image to the Table as soon as it's ready.
     Aborts if `render-generation` changes (newer search superseded this one)."
     [^Browser browser results ^Table table gen]
     (let [idx   (atom 0)
           total (count results)]
       (letfn [(render-next []
                 (cond
                   (not= gen @render-generation) nil  ;; stale — abort
                   (>= @idx total) nil                ;; done
                   :else
                   (let [html (search/card-html (nth results @idx))]
                     (.addProgressListener browser
                       (proxy [ProgressAdapter] []
                         (completed [_event]
                           (.removeProgressListener browser this)
                           (when (= gen @render-generation)
                             (let [img (screenshot-browser browser)
                                   i   @idx]
                               (let [item (TableItem. table SWT/NONE)]
                                 (.setImage item img)
                                 (.setData item "result-idx" (int i)))
                               (when (zero? i) (.select table 0))
                               (swap! idx inc)
                               (async-exec! render-next))))))
                     (.setText browser html))))]
         (render-next))))
   ```

   Each image appears in the Table immediately — the user sees results
   fill in one by one rather than waiting for all to render.

3. Add `dispose-table-images!` — disposes all Images on a Table's items:

   ```clojure
   (defn- dispose-table-images! [^Table table]
     (doseq [item (.getItems table)]
       (when-let [img (.getImage item)]
         (when-not (.isDisposed img)
           (.dispose img)))))
   ```

**Verify**: REPL — call `render-row-images!` with 3 results, confirm
items appear in the Table progressively. Type again before rendering
completes, confirm the old loop aborts and new results replace old ones.

---

### Step 4 — Replace Browser rows with Table in the popup

**File**: `winze-server/src/llm_memory/ui/content_assist.clj`

Rewrite `open-content-assist!` to use a Table instead of Browser rows:

1. Remove the Browser row creation loop (lines 340-353).
2. Replace with a `Table (SWT.SINGLE | SWT.FULL_SELECTION)` — single column,
   no header, image-bearing rows (same styling as command palette).
3. Update `popup-state` atom: replace `:rows [Browser...]` with
   `:table Table`. Images are tracked on the `TableItem`s themselves (via
   `.getImage`), not in a separate atom.
4. Add an image column: `(TableColumn. table SWT/LEFT)` with width =
   `popup-width - 8` (margins).
5. Set `(.setItemHeight table row-height)` or let it auto-size from Images.
6. **No `populate-table!` function** — the render loop (Step 3) appends
   `TableItem`s progressively. To clear and re-render, call
   `dispose-table-images!` then `.removeAll` on the Table, then start a
   new render loop.
7. Move the keyboard handling (arrow keys, Enter, Esc) to the filter Text
   (unchanged — same pattern as command palette).
8. Single-click on Table row → select (via `SWT/Selection` listener, same
   as command palette).
9. Remove `highlight-selection!` — Table handles selection natively.
10. Add a `DisposeListener` on the **Table widget** that calls
   `dispose-table-images!`. This is the single authoritative disposal
   point — it fires regardless of how the popup closes (Esc,
   click-outside, Shell dispose cascade, platform close).

**Verify**: REPL — open content assist, confirm Table appears with image
rows. Arrow keys navigate, Enter selects, Esc dismisses.

---

### Step 5 — Wire up re-rendering on keystroke

**File**: `winze-server/src/llm_memory/ui/content_assist.clj`

1. In `schedule-search!`, after results arrive on the UI thread:
   - Increment `render-generation` (cancels any in-progress render loop)
   - Dispose old Images via `dispose-table-images!`
   - Clear the Table (`.removeAll`)
   - Start `render-row-images!` with the new results, the Table, and the
     current generation counter
2. The existing search debounce (150ms wiki / 500ms Google) prevents
   triggering on every keystroke. The generation counter handles the case
   where the user types *during* a render loop — the stale loop checks
   the counter at its next iteration and aborts, freeing the offscreen
   Browser for the new loop.
3. The Table's `DisposeListener` (added in Step 4) handles final Image
   disposal. The Shell's existing `DisposeListener` should still clean up
   the executor and popup state, but does NOT need to dispose Images — the
   Table's listener fires first during the dispose cascade.
   Do NOT dispose the offscreen Shell (it persists for reuse).
4. When the Table is empty (between clear and first image arriving), the
   popup shows an empty list. This is fine — the first image typically
   appears within ~50ms. No placeholder needed.

**Verify**: REPL — type in the search field, confirm Images update after
debounce. Confirm no Image disposal warnings in logs.

---

### Step 6 — Clean up old code

1. Remove `update-row!` function (no longer needed).
2. Remove `highlight-selection!` function (Table handles this).
3. Remove unused Browser-related imports.
4. Update `select-result!` to read the selected index from the Table widget.
5. Ensure the executor is shut down on popup dispose (unchanged).

**Verify**: `make test` passes. REPL — full end-to-end: type `[text](`,
confirm popup with images, select, confirm link inserted.

---

## Fix 2 — Wiki-Link `[[` Trigger

### Step 7 — Fix trigger detection timing

**File**: `winze-server/src/llm_memory/ui/markdown_editor.clj`

The current `keyPressed` handler reads `.getText` and `.getCaretOffset`
before the character is inserted. The fix: move the detection into the
`async-exec!` block (which runs after the character is inserted) so the
text and caret reflect the post-insertion state.

Replace lines 622-629:

```clojure
;; Current (broken timing):
(and (not cmd?) (= ch \[))
(let [text  (.getText st)
      caret (.getCaretOffset st)]
  (when (and (> caret 0)
             (= \[ (.charAt text (dec caret))))
    (async-exec!
     #(handle-wiki-draft-trigger! st abs-path (dec caret)))))
```

With:

```clojure
;; Fixed (check text after insertion):
(and (not cmd?) (= ch \[))
(async-exec!
 (fn []
   (let [text  (.getText st)
         caret (.getCaretOffset st)]
     ;; After insertion, caret is after the second [.
     ;; Check that the two characters before the caret are both [
     (when (and (>= caret 2)
                (= \[ (.charAt text (- caret 1)))
                (= \[ (.charAt text (- caret 2))))
       (handle-wiki-draft-trigger! st abs-path (- caret 2))))))
```

This ensures:
- The text includes the just-typed `[`
- The caret is at the correct post-insertion position
- `bracket-start` points to the first `[` in the `[[` pair

**Verify**: REPL — type `[[` in the editor, confirm popup opens only after
the second `[`. Type a single `[` elsewhere, confirm no popup.

---

### Step 8 — Fix UUID enrichment query (Datalevin ref-join workaround)

**File**: `winze-server/src/llm_memory/ui/content_assist.clj`

**Root cause**: REPL investigation confirmed a Datalevin query engine bug
where Datalog variable unification across `:db.type/ref` joins fails. The
enrichment query on lines 91-101 uses the broken pattern
`[?f :file/id ?fid] [?w :wiki/file ?f]`. All 181 wiki entities in the
store have valid UUIDs but the enrichment always falls back to `file-id`.

**Fix**: Split the enrichment into two queries — first find the file entity
ID, then use it as an explicit `:in` parameter:

Replace the enrichment in `wiki-search` (lines 88-102):

```clojure
(mapv (fn [r]
        (let [file-id (:file/id r)
              ;; Step 1: find the file entity ID
              file-eid (when file-id
                         (first
                          (query-store s
                            '[:find [?f ...]
                              :in $ ?fid
                              :where [?f :file/id ?fid]]
                            {:fid file-id})))
              ;; Step 2: find wiki UUID for the file's first heading
              wiki-id (when file-eid
                        (first
                         (query-store s
                           '[:find [?wid ...]
                             :in $ ?f
                             :where
                             [?w :wiki/file ?f]
                             [?w :wiki/id ?wid]
                             [?w :wiki/level 1]]
                           {:f file-eid})))]
          (assoc r :wiki/id (or wiki-id file-id))))
      results)
```

Also apply the same two-step pattern to `title-search` (line 128) which
currently uses `file-id` directly as `:wiki/id`.

**Verify**: REPL — call `wiki-search` with "editor commands", confirm
`:wiki/id` contains a UUID string (not a `winze::...` file-id).

---

## Fix 3 — Link Preview in View Mode

### Step 9 — Replace `window.status` with `BrowserFunction`

**File**: `winze-server/src/llm_memory/ui/link_preview.clj`

Replace the `StatusTextListener` + `window.status` mechanism with
`BrowserFunction` — the official SWT JavaScript→Java callback API.

**9a — Update the injected JavaScript** (replace `hover-js`, line 332-346):

```javascript
document.addEventListener('mouseover', function(e) {
  var a = e.target.closest('a[href^="wiki:"]');
  if (a) {
    var r = a.getBoundingClientRect();
    wpreviewHover(a.getAttribute('href').substring(5),
                  Math.round(r.left), Math.round(r.bottom));
  }
});
document.addEventListener('mouseout', function(e) {
  var a = e.target.closest('a[href^="wiki:"]');
  if (a) { wpreviewLeave(); }
});
```

Instead of `window.status = ...`, the JavaScript calls `wpreviewHover()`
and `wpreviewLeave()` — Java functions exposed via `BrowserFunction`.

**9b — Register BrowserFunctions** (replace `StatusTextListener`):

```clojure
(defn install-browser-link-preview!
  [^Browser view-browser]
  (ensure-preview-shell!)
  (let [mouse-in (:mouse-in-preview? @preview-state)

        ;; BrowserFunction: called from JS on hover enter
        hover-fn
        (proxy [BrowserFunction] [view-browser "wpreviewHover"]
          (function [args]
            (let [uuid (str (aget args 0))
                  x    (int (double (aget args 1)))
                  y    (int (double (aget args 2)))]
              (when (not= uuid (:current-uuid @preview-state))
                (cancel-pending-preview!)
                (let [{:keys [^ScheduledExecutorService executor]}
                      (ensure-preview-shell!)
                      new-future
                      (.schedule executor
                        ^Runnable
                        (fn []
                          (try
                            (when-let [result (resolve-wiki-preview uuid)]
                              (async-exec!
                               #(when (not (.isDisposed view-browser))
                                  (let [pt (.toDisplay view-browser x y)]
                                    (show-preview-at! uuid result
                                      (.x pt) (.y pt)
                                      (.getShell view-browser))))))
                            (catch Throwable t
                              (log/warn t "Browser link preview failed"))))
                        (long hover-delay-ms)
                        TimeUnit/MILLISECONDS)]
                  (swap! preview-state assoc :hover-future new-future))))
            nil))

        ;; BrowserFunction: called from JS on hover leave
        leave-fn
        (proxy [BrowserFunction] [view-browser "wpreviewLeave"]
          (function [_args]
            (cancel-pending-preview!)
            (when (and (preview-open?) (not @mouse-in))
              (async-exec! hide-preview!))
            nil))]

    ;; Inject hover JS after every page load
    (.addProgressListener view-browser
      (proxy [ProgressAdapter] []
        (completed [_event]
          (try (.execute view-browser hover-js)
               (catch Throwable t
                 (log/debug t "Failed to inject hover JS"))))))

    ;; Dismiss on navigation
    (.addLocationListener view-browser
      (proxy [LocationAdapter] []
        (changing [_event]
          (cancel-pending-preview!)
          (when (preview-open?) (hide-preview!)))))

    ;; Dispose BrowserFunctions when Browser is disposed
    (.addDisposeListener view-browser
      (reify DisposeListener
        (widgetDisposed [_ _e]
          (.dispose hover-fn)
          (.dispose leave-fn))))))
```

**9c — Remove old code:**
- Remove `parse-preview-status` function
- Remove `StatusTextListener` import
- Remove `StatusTextEvent` import
- Add `BrowserFunction` import

**Verify**: REPL — open a file with `wiki:uuid` links in view mode. Hover
over a link. Confirm the preview popup appears after 300ms. Move away,
confirm it dismisses.

---

### Step 10 — Verify coordinate system

After replacing the communication channel, verify that coordinates are
correct:

1. `getBoundingClientRect()` returns viewport-relative coordinates.
2. `.toDisplay(view-browser, x, y)` converts widget-relative coordinates
   to screen coordinates.
3. For a non-scrolled Browser, viewport coords ≈ widget client area coords.
4. **For a scrolled Browser**: viewport `y` is offset by the scroll
   position. The preview may appear at the wrong vertical position.

If coordinates are wrong, add scroll offset correction in the JavaScript:

```javascript
var scrollY = window.scrollY || document.documentElement.scrollTop;
wpreviewHover(uuid, Math.round(r.left), Math.round(r.bottom - scrollY));
```

**Verify**: REPL — scroll the view-mode Browser to the bottom of a long
file. Hover over a wiki link. Confirm the preview appears near the link,
not offset by the scroll distance.

---

## Verification Checkpoints

| Fix | Test |
|-----|------|
| 1 (Content assist) | Popup shows Table with image rows; typing updates images; selection works; no Image leaks on close |
| 2 (Wiki trigger) | `[[` triggers popup; single `[` does not; selecting a result writes `[title](wiki:uuid)` with a valid UUID |
| 3 (Link preview) | Hover over wiki link in view mode shows preview card; dismiss on mouse-away; edit mode preview still works |

## Implementation Order

### Sequential (single developer)

1. **Fix 3 first** (link preview) — smallest change, isolated to one file,
   highest confidence. Proves `BrowserFunction` works on the target platform.
2. **Fix 2 second** (wiki trigger) — small change, isolated, but needs REPL
   verification of UUID persistence in the store.
3. **Fix 1 last** (content assist) — largest change, but the offscreen
   screenshot technique benefits from having `BrowserFunction` proven (both
   involve Browser rendering interactions).

### Parallel (subagent team)

The three fixes touch three distinct files with no cross-file write
dependencies. Use a two-phase approach:

**Phase 1 — three parallel file-writing agents (no REPL):**

| Agent | File | Steps |
|-------|------|-------|
| Agent A | `link_preview.clj` | Steps 9–10 (Fix 3) |
| Agent B | `markdown_editor.clj` | Step 7 (Fix 2a — timing) |
| Agent C | `content_assist.clj` | Steps 1–6 (Fix 1) + Step 8 (Fix 2b — UUID) |

Agent C is the critical path. Read `content_assist.clj` before launching
to verify step line numbers are still accurate.

**Phase 2 — single REPL owner (sequential verification):**

After all Phase 1 agents complete:
1. `make install` + server restart
2. Verify Fix 3 (Steps 9–10): `BrowserFunction` hover in view mode
3. Verify Fix 2 (Steps 7–8): `[[` trigger timing + UUID enrichment
4. Verify Fix 1 (Steps 1–6): offscreen Browser, progressive Table fill,
   image disposal, keyboard navigation

Each failed checkpoint is an iterative fix-and-reload cycle in the REPL.
Only one agent may own the REPL at a time — concurrent nREPL access
deadlocks SWT's single UI thread (see `winze/CLAUDE.md`).
