---
created: 2026-03-24
group: winze-ctabfolder-ui
related: [winze-server]
tags: [swt, ui, ctabfolder, clojure-desktop-toolkit]
---

# Plan: Reimplement SWT Snippet165 & Snippet371 in Clojure

## Prerequisite: REPL Setup

**Critical**: macOS requires `-XstartOnFirstThread`, and the thread that calls
`(Display/getDefault)` becomes the SWT UI thread. All UI code must run on that thread.

### Option A: Connect to a running Winze server (preferred)

Winze's `-main` already calls `(Display/getDefault)` on the main thread with the correct
JVM flag. Connect to its nREPL:

```bash
# Start winze (if not already running)
make run    # from winze-server/, or let the MCP proxy auto-start it

# Find the port
cat ~/.local/share/winze/.nrepl-port

# Connect
clj-nrepl-eval -p <port> "(require '[ui.SWT :refer [ui swtdoc]])"
```

All UI code must be wrapped in the `(ui ...)` macro when eval'd from nREPL, since nREPL
eval threads are not the UI thread:

```clojure
(ui (.setText some-widget "Hello"))   ;; runs on SWT thread via syncExec
```

### Option B: UI-only `-main` (for dedicated UI exploration)

Write a minimal entry point that only initializes Display + nREPL (no server):

```clojure
(defn -ui-main [& _args]
  (reset! ui/display (Display/getDefault))
  ;; Start nREPL only — no server, no store, no watchers
  (let [server (nrepl/start-server :bind "127.0.0.1" :port 0)]
    (println "nREPL on port" (:port server)))
  @(promise))
```

Run with: `clojure -J-XstartOnFirstThread -M:dev -m llm-memory.server.main -ui`

This avoids starting the full server while still giving a proper UI thread.

### Option C: Standalone nREPL (least preferred)

```bash
cd winze/winze-server
clojure -J-XstartOnFirstThread \
  -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"} cider/cider-nrepl {:mvn/version "LATEST"}}}' \
  -M:dev -m nrepl.cmdline --middleware '["cider.nrepl/cider-middleware"]'
```

Then the first eval must initialize the Display on the main thread. This may not work
reliably because nREPL's main thread isn't guaranteed to be the thread running evals.
**Prefer Option A or B.**

## Step 1: Create `llm-memory.ui.util` — Shared UI Utilities ✅

Created and tested. Source file: `winze-server/src/llm_memory/ui/util.clj`

### Functions to implement

```clojure
(ns llm-memory.ui.util
  "Reusable SWT/CDT utility functions for screenshots, image I/O, and visual verification."
  (:require [ui.SWT :refer [with-gc-on]])
  (:import [org.eclipse.swt SWT]
           [org.eclipse.swt.graphics Image ImageData ImageLoader]
           [org.eclipse.swt.widgets Display]))

(defn save-image!
  "Save an Image to a PNG file at `path`. Does NOT dispose the image —
  the caller owns the Image lifecycle (see SWT-UI-GUIDE §7)."
  [image path]
  (let [loader (ImageLoader.)]
    (set! (.-data loader) (into-array ImageData [(.getImageData image)]))
    (.save loader path SWT/IMAGE_PNG)))

(defn screenshot-widget!
  "Capture a screenshot of `control` and save as PNG to `path`.
  Must be called on the UI thread (wrap in `ui` from nREPL).
  Creates and disposes its own Image; uses CDT's `with-gc-on` for the GC."
  [control path]
  (let [bounds (.getBounds control)
        image  (Image. (.getDisplay control) (.width bounds) (.height bounds))]
    (try
      (with-gc-on control
        (fn [gc] (.copyArea gc image 0 0)))
      (save-image! image path)
      (finally
        (.dispose image)))))

(defn screenshot-display!
  "Capture the entire screen and save as PNG to `path`.
  Must be called on the UI thread."
  [path]
  (let [display (Display/getDefault)
        bounds  (.getBounds display)
        image   (Image. display (.width bounds) (.height bounds))]
    (try
      (with-gc-on display
        (fn [gc] (.copyArea gc image 0 0)))
      (save-image! image path)
      (finally
        (.dispose image)))))
```

### Verification — Done

All three functions tested against running winze-server (port 65407):
- `save-image!` — created 50x50 Image, saved to PNG, verified file format
- `screenshot-display!` — captured full retina screen (5640x1169), visually confirmed
- `screenshot-widget!` — created a Shell with label, captured 300x200 PNG, visually confirmed

**Discovery during testing**: The SWT event loop (`readAndDispatch`/`sleep`) must be running
on the main thread for `syncExec` / `(ui ...)` to work. Without it, `syncExec` deadlocks.
`-main` was updated to run the event loop instead of `@(promise)`. See SWT-UI-GUIDE §10.

## Step 2: REPL Discovery — Validate CDT Support for CTabFolder ✅

Completed 2026-03-24. Results:

| Question | Result |
|---|---|
| `ctab-folder` CDT init fn? | **Yes** — `#'ui.SWT/ctab-folder` |
| `ctab-item` CDT init fn? | **Yes** — `#'ui.SWT/ctab-item` |
| `CTabFolder2Listener` keywords? | **`:close :items-count :maximize :minimize :restore :show-list`** |
| `:keyword value` on CTabFolder? | **Yes** — full setter list confirmed |
| `ImageGcDrawer` usable with `reify`? | **Yes** — in `org.eclipse.swt.graphics` |
| CTabItem sibling constraint? | **Still applies** — `CTabItem` is not a `Composite`; custom init fns required |

**Architecture decision**: Use `ctab-folder`/`ctab-item` init fns (not `widget`). Use CDT `on` macro
for `CTabFolder2Listener` events (not `proxy`). Use custom init fns for tab+content creation pairs.

## Step 3: Implement Snippet371 (Simpler — Multi-line Tab Text) ✅

Start with the simpler snippet to establish the CTabFolder creation pattern.

### Target behavior
- Shell with FillLayout, size 300×200
- CTabFolder with SWT/BORDER
- 2 CTabItems with SWT/CLOSE
  - Item 1: text "Item on one line", content Text "Content for Item 1"
  - Item 2: text "Item on\ntwo lines", content Text "Content for Item 2"

### Approach (resolved from Step 2)

Use `ctab-folder` with the `id!`/`control` sibling wiring pattern — content widget
first (named via `id!`), then `ctab-item` with a `(control :ui/name)` init fn.
`control` is a helper in `llm-memory.ui.util` (see CONTEXT.md and Step 2b below).

**Verify the `control` pattern works before writing the full implementation.**

```clojure
(defn snippet-371
  "CTabFolder with multi-line tab text (port of SWT Snippet371).
  Creates and opens a shell. Returns [shell props]."
  []
  (show
   (shell SWT/SHELL_TRIM "Snippet 371"
     :size   (Point. 300 200)
     :layout (FillLayout.)
     (ctab-folder SWT/BORDER
       (text SWT/MULTI (id! :ui/tab1-text) "Content for Item 1")
       (ctab-item SWT/CLOSE :text "Item on one line"   (control :ui/tab1-text))
       (text SWT/MULTI (id! :ui/tab2-text) "Content for Item 2")
       (ctab-item SWT/CLOSE :text "Item on\ntwo lines" (control :ui/tab2-text))))))
```

Required ns additions: `control`, `show` from `llm-memory.ui.util`; `id!`, `shell`,
`ctab-folder`, `ctab-item`, `text` from `ui.SWT`; `Point` (`org.eclipse.swt.graphics`),
`FillLayout` (`org.eclipse.swt.layout`).

### Step 2b: Implement `control` in `llm-memory.ui.util` ✅

Done. See [util.clj](../../winze/winze-server/src/llm_memory/ui/util.clj).

### Step 2c (was Step 3): Verify `control` + `id!` wiring ✅

Verified via probe test. Also discovered the correct REPL testing pattern — see below.

### REPL Testing Pattern (discovered during Step 2c)

`application` is **not usable** against a running server — it runs its own event loop and
disposes the Display on exit. Use `show` from `llm-memory.ui.util` instead:

```clojure
(let [[sh props] (show (shell SWT/SHELL_TRIM "Title" :layout (FillLayout.) ...))]
  @props                                       ; inspect id! bindings
  (ui (screenshot-widget! sh "/tmp/test.png"))
  (ui (.close sh)))
```

`show` creates a fresh `props` atom, runs the init fn on the UI thread, opens the shell,
and returns `[widget props]`. The existing main-thread event loop processes all events.
Do NOT use `(future (application ...))`.

---

## Step 4: Implement Snippet165 (Full-featured CTabFolder) ✅

Completed 2026-03-24. Implemented as three functions:
- `make-tab-icon` — creates 16×16 blue/yellow icon via `reify ImageGcDrawer`
- `make-tabs` — returns CDT init fn that creates 8 closeable tabs with `doseq`
- `snippet-165` — assembles the shell with `show`, wraps in `(ui ...)` for thread safety

Key discovery: `Image` constructor with `ImageGcDrawer` requires the UI thread — the
outer `(ui ...)` wrapper ensures `make-tab-icon` runs on the SWT thread before `show`
(which also calls `ui` internally — safe because CDT's `ui` checks `ui-thread?` and
runs directly if already on the UI thread).

Verified via screenshot: curved tabs, min/max buttons, tab overflow chevron, icon +
close button on selected tab only, multiline text content.

### Target behavior
- Shell with GridLayout, size 300×300
- Icon image: 16×16, blue background with yellow center square
- CTabFolder with SWT/BORDER, curved tabs, GridData fill
- 8 closeable tabs, each with icon and multiline Text
- Image visible only on selected tab
- Close button visible only on selected tab
- Minimize/maximize buttons visible
- CTabFolder2Listener: minimize/maximize/restore toggle GridData and re-layout shell

### Sub-steps

#### 3a. Image creation

```clojure
(let [display (Display/getDefault)
      drawer  (reify ImageGcDrawer
                (draw [_ gc w h]
                  (.setBackground gc (.getSystemColor display SWT/COLOR_BLUE))
                  (.fillRectangle gc 0 0 16 16)
                  (.setBackground gc (.getSystemColor display SWT/COLOR_YELLOW))
                  (.fillRectangle gc 3 3 10 10)))
      image   (Image. display drawer 16 16)]
  ...)
```

Or if `ImageGcDrawer` is a functional interface, use `reify`.

#### 3b. CTabFolder construction

Use `ctab-folder` init fn with `:keyword value` setters (all confirmed working):

```clojure
(ctab-folder SWT/BORDER
  :simple                   false
  :unselected-image-visible false
  :unselected-close-visible false
  :minimize-visible         true
  :maximize-visible         true
  :layout-data              (GridData. SWT/FILL SWT/FILL true false)
  ;; ... tab loop init fn
  ;; ... CTabFolder2Listener init fn
  )
```

#### 3c. Tab item loop

`doseq`/`for` cannot go inside CDT init fn arg lists (compile-time macro expansion).
Use a custom init function — pattern confirmed in Step 2:

```clojure
(fn [props parent]
  (doseq [i (range 8)]
    (let [item (CTabItem. parent SWT/CLOSE)
          text (Text. parent (bit-or SWT/MULTI SWT/V_SCROLL SWT/H_SCROLL))]
      (.setText item (str "Item " i))
      (.setImage item image)
      (.setText text (str "Text for item " i "\n\none, two, three\n\nabcdefghijklmnop"))
      (.setControl item text))))
```

#### 3d. CTabFolder2Listener

Use CDT's `on` macro — confirmed working with keywords `:minimize`, `:maximize`, `:restore`
(no need for `proxy [CTabFolder2Adapter]`):

```clojure
(on :minimize [props parent event]
  (.setMinimized parent true)
  (.setLayoutData parent (GridData. SWT/FILL SWT/FILL true false))
  (.layout (:ui/shell @props) true))

(on :maximize [props parent event]
  (.setMaximized parent true)
  (.setLayoutData parent (GridData. SWT/FILL SWT/FILL true true))
  (.layout (:ui/shell @props) true))

(on :restore [props parent event]
  (.setMinimized parent false)
  (.setMaximized parent false)
  (.setLayoutData parent (GridData. SWT/FILL SWT/FILL true false))
  (.layout (:ui/shell @props) true))
```

## Step 5: Integrate into `main_window.clj` ✅

Both `snippet-371` and `snippet-165` are in `llm-memory.ui.main-window` with shared
`(comment ...)` REPL block. Imports added: `CTabItem`, `Image`, `ImageGcDrawer`,
`GridData`, `GridLayout`, `Display`, `Text`.

**Note**: `require :reload` does not work for this namespace — CDT's `ui.SWT` vars are
dynamically generated and don't resolve during compile-time `:refer`. Use `load-file`
from the REPL instead.

## Step 6: Test in REPL + Visual Verification

Use `show` which returns `[widget props]` — no manual `(ui ...)` wrapping needed for creation:

```clojure
;; 1. Launch demo
(let [[sh props] (snippet-371)]
  ;; 2. Inspect id! bindings
  (keys @props)   ; => (:ui/breadcrumb :ui/tab1-text :ui/tab2-text)

  ;; 3. Screenshot
  (ui (screenshot-widget! sh "/tmp/snippet-371.png"))

  ;; 4. Claude Code reads the PNG via Read tool to verify visually

  ;; 5. Close when done
  (ui (.close sh)))
```

### Visual Test Loop

```
code change → reload ns → (show ...) → screenshot → read PNG → assess → iterate
```

See `Plans/SWT-UI-GUIDE.md` §2 for the full REPL testing pattern and §9 for screenshot details.

## Decisions Resolved (Step 2, 2026-03-24)

| Question | Decision |
|----------|----------|
| Use `widget` or generated init fn for CTabFolder? | **`ctab-folder` / `ctab-item`** — both generated |
| `:simple false` etc. via `:keyword value`? | **Yes** — all setters confirmed |
| CDT `on` for `:minimize`/`:maximize`/`:restore`? | **Yes** — no `proxy` needed |
| `for`/`doseq` inside CDT arg lists? | **No** — use custom `(fn [props parent] ...)` with `doseq` |
| `ImageGcDrawer` with `reify`? | **Yes** — use `(reify ImageGcDrawer (drawOn [_ gc] ...))` |

## Risk Assessment

- **Low risk**: CDT sugar works well for CTabFolder — `ctab-folder`, `ctab-item` init fns confirmed, `on` macro handles CTabFolder2Listener events
- **Resolved**: Step 2 REPL discovery showed CDT covers all needed patterns; Java interop fallback only needed for `doseq` loops (custom init fns)
- **No production impact**: Demo functions in `(comment ...)` blocks, not wired into the server lifecycle
