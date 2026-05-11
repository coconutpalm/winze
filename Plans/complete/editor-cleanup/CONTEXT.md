# Editor Cleanup — Context

## Goal

Fix three bugs/issues in the partially-implemented editor enhancement features:

1. **Content assist popup uses heavyweight mini-Browser widgets per row** —
   the current architecture creates 8 `Browser` (WebKit) instances inside the
   popup, one per result. This is slow, resource-heavy, and visually broken.
   Replace with a **screenshot-based approach**: render each row in a single
   offscreen Browser, screenshot it, and display the resulting `Image` in a
   lightweight `Table` (same widget the command palette uses).

2. **Wiki-link `[[` trigger fires on the first `[`** — the `keyPressed`
   handler reads `(.getText st)` and `(.getCaretOffset st)` before the
   character has been inserted by SWT, so the check `(= \[ (.charAt text
   (dec caret)))` tests against the character *before* the cursor, which is
   the preceding character in the *old* text — not the `[` the user just
   typed. Additionally, on selection from the content assist popup, the UUID
   is not always written into the link.

3. **Link preview popup doesn't display on mouse hover in view mode** — the
   view-mode preview relies on injecting JavaScript that writes to
   `window.status` and a `StatusTextListener` to receive the event. This
   mechanism is unreliable: modern WebKit implementations may not fire
   `StatusTextEvent` for `window.status` assignments, or may fire it
   inconsistently.

## Issue 1 — Content Assist: Screenshot-Based Row Rendering

### Current architecture (broken)

```
Shell (SWT.TOOL | ON_TOP | NO_TRIM)
├── Text (search field)
└── Composite (results container)
    ├── Composite (row 0) → Browser (WebKit) [80px]   ← heavyweight
    ├── Composite (row 1) → Browser (WebKit) [80px]
    ├── ...
    └── Composite (row 7) → Browser (WebKit) [80px]
```

**Problems:**
- 8 concurrent WebKit instances per popup opening — high memory, high latency
- Each `populate-rows!` call does `.setText(html)` on all 8 Browsers
- Selection highlighting is a parent-Composite background color change (hacky)
- Mouse click listeners are on parent Composites, not on the result items
- Browsers inside Composites don't propagate mouse events reliably

### New architecture (screenshot-based)

**Phase 1 — Offscreen rendering:**

Create a Shell positioned offscreen using coordinates derived from the
Display/Monitor API — place it beyond the bounds of all connected monitors
so it's invisible regardless of multi-monitor layout. The Shell contains a
disabled Composite (`.setEnabled false`) wrapping the Browser widget. The
disabled Composite prevents the offscreen Shell from receiving focus via
alt-tab or any other mechanism — the user can never accidentally interact
with it. Layout: `Shell (FillLayout) → Composite (FillLayout, disabled) →
Browser`. The Shell stays alive for the lifetime of the content assist popup.

**Phase 2 — Screenshot loop (Java↔JavaScript trampolining):**

Rendering each row requires a multi-step asynchronous pipeline because
`Browser.setText()` is asynchronous — the page is not rendered until the
`ProgressListener.completed` event fires, and we need to wait for that before
taking the screenshot.

```
For each result in results:
  1. Call browser.setText(html) on the offscreen Browser
  2. Wait for ProgressListener.completed to fire
  3. Take screenshot: browser → Image (via GC draw)
  4. Trim the rightmost ~16px (scrollbar gutter) from the Image
  5. Store the Image in a vector
  6. Continue to the next result (loop via continuation)
```

This is a **continuation-based trampoline**: each step schedules the next
via a callback (the `completed` listener), bouncing between Java (screenshot
capture) and the Browser engine (HTML rendering). The loop cannot be written
as a simple `doseq` because each iteration must yield to the SWT event loop.

**Implementation pattern:**

The render loop uses a **generation counter** for cancellation. Each search
increments the generation; a render loop checks the counter before each
iteration and aborts if it's stale. This handles the case where the user
types faster than the render loop completes — the old loop self-cancels
and the new one takes over the offscreen Browser.

```clojure
(defonce ^:private render-generation (atom 0))

(defn- render-row-images!
  "Render result cards as Images via an offscreen Browser.
  Updates the Table progressively as each image completes.
  Aborts if `render-generation` changes (a newer search superseded this one)."
  [^Browser offscreen-browser results ^Table table gen]
  (let [idx (atom 0)
        total (count results)]
    (letfn [(render-next []
              (cond
                ;; Stale — a newer search has started; abandon this loop.
                ;; Do NOT dispose images already in the Table — the next
                ;; loop or the Table's DisposeListener handles that.
                (not= gen @render-generation)
                nil

                ;; All done
                (>= @idx total)
                nil

                ;; Render next row
                :else
                (let [html (search/card-html (nth results @idx))]
                  (.addProgressListener offscreen-browser
                    (proxy [ProgressAdapter] []
                      (completed [_event]
                        (.removeProgressListener offscreen-browser this)
                        (when (= gen @render-generation)
                          (let [img (screenshot-browser offscreen-browser)
                                i   @idx]
                            ;; Append a new TableItem with the image
                            (let [item (TableItem. table SWT/NONE)]
                              (.setImage item img)
                              (.setData item "result-idx" (int i)))
                            ;; Select the first row once available
                            (when (zero? i)
                              (.select table 0))
                            (swap! idx inc)
                            (async-exec! render-next))))))
                  (.setText offscreen-browser html))))]
      (render-next))))
```

Each image appears in the Table as soon as it's screenshotted — the user
sees results fill in progressively rather than waiting for all 8 to render.

**Phase 3 — Display in the popup:**

The popup uses a `Table` widget (like the command palette) instead of
Browser rows. `TableItem`s are appended progressively by the render loop
as each screenshot completes. Selection, keyboard navigation, and click
handling all work natively with `Table`.

```
Shell (SWT.TOOL | ON_TOP | NO_TRIM)
├── Text (search field)
└── Table (SWT.SINGLE | SWT.FULL_SELECTION)
    ├── TableItem [Image: screenshot of result card 0]  ← appears first
    ├── TableItem [Image: screenshot of result card 1]  ← appears ~50ms later
    └── ...                                             ← progressive fill
```

**Phase 4 — Re-rendering on keystroke:**

When the user types and a new search fires:
1. Increment `render-generation` — the in-progress render loop sees the
   counter change and self-cancels at its next iteration.
2. Dispose all Images currently on the Table's items (the old results).
3. Clear the Table (`.removeAll`).
4. Start a new `render-row-images!` loop with the new results and the
   new generation counter.

The debounce on the search itself (150ms wiki / 500ms Google) prevents
the render loop from being triggered on every keystroke. The generation
counter handles the case where the user types during the render loop —
the stale loop aborts and the new one takes over the offscreen Browser
without conflict.

### Key constraints

- **Image disposal is critical** — every `Image` created from a screenshot
  must be disposed when replaced or when the popup closes. Track all live
  Images in an atom and dispose them in a `DisposeListener` on the **Table
  widget** (not the Shell). This guarantees disposal even if the Shell is
  closed by the platform or a parent dispose cascade.
- **Position the offscreen Shell beyond all monitors** — use
  `Display.getMonitors()` to find the bounding rectangle of all connected
  monitors, then place the Shell beyond the right edge (e.g.,
  `max-x + 100`). This is safe for any multi-monitor configuration.
- **The offscreen Shell must be large enough** for the Browser to render
  at the target width. Size: `[popup-width × row-height]` (adjusted to
  `popup-width + gutter` after the first measurement).
- **Prevent focus on the offscreen Shell** — wrap the Browser in a
  Composite with `.setEnabled(false)`. This disables all input on the
  Composite and its children, preventing the Shell from being activated
  via alt-tab or any other focus mechanism.
- **Measure the scrollbar gutter dynamically** — after the first page
  renders, query `document.body.scrollWidth` via `Browser.evaluate` to get
  the HTML body's actual rendered width. The scrollbar gutter width is
  `browser-widget-width - body-scroll-width`. Cache this value (it won't
  change across renders on the same platform). Use it to trim screenshots
  and to size the offscreen Shell so the body renders at exactly
  `popup-width` (i.e., set the Shell width to `popup-width + gutter`).
  This avoids hardcoding a platform-specific scrollbar width.
- **The offscreen Shell should be opened (`.open`) but remain offscreen** —
  SWT Browsers don't render until the Shell is open and the Browser is
  visible.

### Reference: command palette architecture

The command palette (`command_palette.clj`) demonstrates the target pattern:

- `Shell (SWT.TOOL | ON_TOP | NO_TRIM)`
- `Text` filter field with `KeyAdapter` for arrow keys, Enter, Esc
- `Table (SWT.SINGLE | SWT.FULL_SELECTION)` with columns
- `ModifyListener` on Text → repopulate Table
- `ShellAdapter.shellDeactivated` → close
- `resize-to-fit!` adjusts height to content
- Single-click on row → execute

The content assist popup mirrors this pattern but replaces plain-text
`TableItem`s with image-bearing `TableItem`s.

## Issue 2 — Wiki-Link `[[` Trigger Fires on First `[`

### Root cause

In `markdown_editor.clj:install-content-assist-triggers!` (line 622-629):

```clojure
(and (not cmd?) (= ch \[))
(let [text  (.getText st)
      caret (.getCaretOffset st)]
  (when (and (> caret 0)
             (= \[ (.charAt text (dec caret))))
    ...))
```

The `keyPressed` event fires **before** SWT inserts the character into the
StyledText widget. So when the user types the second `[`:

- `(.character event)` = `\[` (the key being pressed)
- `(.getText st)` = text **without** the second `[` yet
- `(.getCaretOffset st)` = position **before** the second `[` is inserted
- `(.charAt text (dec caret))` checks the character before the caret in
  the old text

This means the trigger actually fires when the character *before the cursor*
happens to be `[` — which could be the first `[` the user just typed (correct)
or any other `[` in the document (incorrect). The `async-exec!` wrapper defers
the handler until after the character is inserted, but the detection logic
runs in the synchronous `keyPressed` callback with stale text.

**Practical effect:** The trigger fires correctly on `[[` because by the time
`keyPressed` runs for the second `[`, the first `[` has already been inserted
and is at `(dec caret)`. The `async-exec!` wrapper then runs
`handle-wiki-draft-trigger!` after the second `[` is inserted. However, the
`bracket-start` is `(dec caret)` from the pre-insertion state — which may be
off by one depending on timing.

### Additional issues

1. **Datalevin ref-join bug breaks UUID enrichment** — REPL investigation
   confirmed that the Datalevin query engine fails on compound joins across
   `:db.type/ref` attributes when the ref entity ID is unified through
   variable binding. Specifically:

   ```clojure
   ;; This FAILS (returns empty):
   [:find ?wid :where
    [?f :file/id ?fid] [?w :wiki/file ?f] [?w :wiki/id ?wid]]

   ;; This WORKS (returns results):
   [:find [?wid ...] :in $ ?f :where
    [?w :wiki/file ?f] [?w :wiki/id ?wid]]
   ;; ...when ?f is provided as the raw entity ID (e.g., 4001)
   ```

   The store has **181 wiki entities** with valid UUIDs, slugs, and file
   refs. Entity navigation (`:wiki/file` → file entity → `:file/path`)
   works correctly. Only the Datalog variable unification across the ref
   join fails.

   **Impact on `wiki-search`**: The enrichment query (line 91-101) uses
   the broken join pattern `[?f :file/id ?fid] [?w :wiki/file ?f]`, so it
   always returns `nil`. The fallback `(or wiki-id file-id)` on line 102
   uses the file-id string instead. This means **every wiki link inserted
   by content assist uses `wiki:<file-id>` instead of `wiki:<uuid>`**.

   **Workaround**: Split the query into two steps — first find the file
   entity ID, then use it as an `:in` parameter for the wiki lookup.

2. **`title-search` results use `file-id` as UUID** — line 128:
   `{:wiki/id fid}`. These produce `wiki:<file-id>` links, not
   `wiki:<uuid>` links. The `resolve-wiki-uuid` function does fall back to
   file-id lookup, so these links work but bypass the heading-level
   resolution.

3. **The popup opens with empty seed text** — `handle-wiki-draft-trigger!`
   passes `:seed-text ""`. Since `schedule-search!` returns `[]` for blank
   queries (line 198-199), the popup opens empty. The user must type to get
   results. This is correct behavior but worth noting.

## Issue 3 — Link Preview Not Displaying in View Mode

### Architecture (current)

The view-mode link preview relies on a JavaScript→Java communication channel:

1. After page load, `ProgressListener.completed` injects `hover-js` into
   the Browser
2. The JavaScript attaches `mouseover`/`mouseout` listeners to
   `a[href^="wiki:"]` elements
3. On hover, JavaScript writes to `window.status` with a formatted string:
   `wpreview:wiki:<uuid>:<x>:<y>`
4. A `StatusTextListener` on the Browser parses this string and schedules
   the preview

### Likely failure points

1. **`window.status` is unreliable in SWT WebKit** — modern WebKit
   (used by SWT on macOS) may not fire `StatusTextEvent` for
   `window.status` assignments. The status bar is a legacy browser chrome
   feature that WebKit may have deprecated or restricted. This is the most
   likely root cause.

2. **JavaScript injection timing** — `ProgressListener.completed` fires
   after the main document loads, but if sub-resources (CSS, images) load
   asynchronously, the listener may fire before `wiki:` links exist in the
   DOM. The `mouseover` handlers would be attached to nothing.

3. **Coordinate system** — the injected JavaScript uses
   `getBoundingClientRect()` which returns coordinates relative to the
   Browser's viewport. These are then transformed via
   `.toDisplay(view-browser, x, y)` which expects widget-relative
   coordinates. If the Browser's viewport origin doesn't match the widget's
   client area origin (e.g., due to scrolling or padding), the preview
   appears at the wrong position.

4. **Edit mode works** — `install-link-preview!` for StyledText (edit mode)
   uses native SWT `MouseMoveListener` and `CaretListener`, not JavaScript.
   If edit-mode preview works but view-mode doesn't, the problem is
   specifically in the JavaScript↔StatusTextListener channel.

### Alternative approach

Replace the `window.status` channel with SWT's `BrowserFunction` — a
mechanism that exposes a Java method as a JavaScript function:

```clojure
(BrowserFunction. browser "wpreviewHover"
  ;; Called from JavaScript: wpreviewHover(uuid, x, y)
  (fn [args]
    (let [uuid (aget args 0)
          x    (int (aget args 1))
          y    (int (aget args 2))]
      (schedule-preview! uuid x y))
    nil))
```

The injected JavaScript then calls `wpreviewHover(uuid, x, y)` instead of
setting `window.status`. `BrowserFunction` is the official SWT mechanism
for JavaScript→Java callbacks and is well-supported across all SWT Browser
backends.

## Files Affected

| File | Changes |
|------|---------|
| `content_assist.clj` | Replace Browser rows with Table + screenshot Images; add offscreen rendering pipeline |
| `markdown_editor.clj` | Fix `[[` trigger detection; verify bracket-start calculation |
| `link_preview.clj` | Replace `window.status` + `StatusTextListener` with `BrowserFunction` for view-mode preview |
| `search.clj` | May need to expose additional rendering helpers for the screenshot pipeline |

## Dependencies

- **SWT `BrowserFunction`** — for JavaScript→Java callbacks in the link
  preview fix. Import: `org.eclipse.swt.browser.BrowserFunction`.
- **SWT `GC` + `Image`** — for screenshotting the offscreen Browser.
  Already used in `resources.clj` for icon drawing.
- **SWT `Table` + `TableItem`** — for the content assist result list.
  Already used in `command_palette.clj`.
- **`search/card-html`** — still used for HTML rendering, but now
  rendered offscreen instead of inline.

## Parallel Implementation Strategy

The three fixes touch **three distinct files** with no cross-file write
dependencies, enabling a two-phase parallel+sequential approach.

### Phase 1 — Parallel file writers (no REPL)

| Agent | File | Work |
|-------|------|------|
| Agent A | `link_preview.clj` | Fix 3: replace `StatusTextListener`/`hover-js` with `BrowserFunction` (Steps 9–10) |
| Agent B | `markdown_editor.clj` | Fix 2a: move `[[` detection inside `async-exec!` (Step 7) |
| Agent C | `content_assist.clj` | Fix 1 (offscreen Browser + Table rewrite, Steps 1–6) + Fix 2b (Datalevin two-step UUID query, Step 8) |

Fix 1 and Fix 2b are both in `content_assist.clj` — Agent C owns both.
Agents A and B are small targeted changes; Agent C has the most work and
is the critical path.

**No agent touches the REPL during Phase 1.** Each agent reads the current
file, writes changes, and stops.

### Phase 2 — Single REPL owner (sequential verification)

After all three Phase 1 agents complete, one agent (or the human) owns the
REPL and runs verification checkpoints in Fix 3 → Fix 2 → Fix 1 order (as
recommended in the plan). The agent also runs `make install` + server restart
before the first checkpoint.

**Rationale**: `winze/CLAUDE.md` prohibits concurrent REPL access — multiple
subagents `load-file`-ing the same nREPL deadlocks SWT's single UI thread
against Datalevin read locks. The safe pattern is: subagents write files
only; one agent does all REPL interaction after they complete.

### Pre-flight check

Before launching Phase 1, read the current `content_assist.clj` to verify
line numbers referenced in the plan (e.g. Browser row loop, enrichment query)
still match. Agent C's rewrite depends on accurate line number context.

## Related Work

- **command-palette** — `command_palette.clj` is the reference
  implementation for the Table-based popup pattern.
- **editor-commands** — the parent plan for content assist
  ([EDITOR-COMMANDS-PLAN.md](../complete/editor-enhancements/EDITOR-COMMANDS-PLAN.md), Phase 3).
- **wiki-links** — the parent plan for `[[` trigger and link preview
  ([WIKI-LINKS-PLAN.md](../complete/editor-enhancements/WIKI-LINKS-PLAN.md)).
- **SWT-UI-GUIDE** — threading rules (§2, §19), resource disposal (§11),
  screenshot API (§15).

## Risks

- **Screenshot rendering speed** — each row requires a full
  `setText → completed → screenshot` cycle. With 8 rows at ~50ms each,
  the initial render could take ~400ms. Mitigate by showing the popup
  immediately with placeholder text, then replacing items as Images
  become available (progressive rendering).
- **Offscreen Browser rendering** — SWT Browsers typically need to be
  visible and in an open Shell to render. The offscreen Shell must be
  `.open()`'d and the Browser must have non-zero size. Test that offscreen
  positioning (negative coordinates) doesn't prevent rendering on macOS.
- **Image disposal** — every screenshot creates an `Image` that must be
  disposed. Missing a disposal path leaks native memory. Track all Images
  in an atom; dispose on popup close, on re-render, and on Shell dispose.
- **`BrowserFunction` lifecycle** — the function must be disposed when the
  Browser is disposed. Use the Browser's `DisposeListener`.
