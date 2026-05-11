---
created: 2026-03-27
doc_type: reference
tags: [swt, ui, clojure-desktop-toolkit, repl, threading, winze]
---

# SWT + Clojure Desktop Toolkit: Reference

Detailed examples, implementation patterns, and architecture for SWT UI work
with CDT. For the rules that govern all UI code, see [SWT-UI-GUIDE.md](SWT-UI-GUIDE.md).

**CDT documentation**: https://github.com/coconutpalm/clojure-desktop-toolkit/blob/main/docs/000-index.md


## 1. Threading Model Details

### `sync-exec!` vs `async-exec!`

| Function | Blocks? | Use for |
|----------|---------|---------|
| `sync-exec!` / `ui` | Yes | **Reading** UI state from background threads |
| `async-exec!` | No | **Mutating** UI state (queues after pending events) |

The `ui` macro checks `(ui-thread?)` — if already on the UI thread, runs
directly; otherwise uses `syncExec`, captures result, rethrows exceptions.

### `application` vs `ui`

| Entry point | Thread behavior | Use case |
|-------------|----------------|----------|
| `(application ...)` | Blocks calling thread, runs own event loop, **disposes Display on exit** | Standalone JVM entry point only |
| `(ui ...)` | Dispatches to existing UI thread via syncExec | All REPL UI work against a running server |

### SWT Event Loop

The event loop (`readAndDispatch`/`sleep`) must run on the main thread for
`syncExec` (and thus `(ui ...)`) to work from nREPL threads. Without it,
`syncExec` deadlocks:

```clojure
(let [display (Display/getDefault)]
  (while (not (.isDisposed display))
    (when-not (.readAndDispatch display)
      (.sleep display))))
```


## 2. REPL Setup for UI Work

### Preferred: Connect to running Winze server

Winze's `-main` initializes SWT on the main thread with `-XstartOnFirstThread`.
Its nREPL is ready for UI work:

```bash
cat ~/.local/share/winze/.nrepl-port   # find port
clj-nrepl-eval -p <port> "(require '[ui.SWT :refer [ui swtdoc widget]])"
```

### Standalone nREPL (unreliable)

```bash
clojure -J-XstartOnFirstThread -M:dev -m nrepl.cmdline ...
```

Unreliable because nREPL's main thread isn't guaranteed to be the eval thread,
so `(Display/getDefault)` may bind to a thread that never processes events.


## 3. The `show` Function and `props` Atom

`show` (in `llm-memory.ui.util`) runs a CDT init function on the UI thread with
a fresh `props` atom, returning `[widget props]`:

```clojure
(defn show [init-fn]
  (ui
    (let [props  (atom {})
          widget (init-fn props (Display/getDefault))]
      [widget props])))
```

Usage — REPL testing against a running server:

```clojure
(require '[llm-memory.ui.util :refer [show]])
(require '[ui.SWT :refer [ui shell id!]])
(import '[org.eclipse.swt SWT]
        '[org.eclipse.swt.layout FillLayout])

(let [[sh props] (show
                   (shell SWT/SHELL_TRIM "My Window"
                     :layout (FillLayout.)
                     (label "Hello" (id! :ui/greeting))))]
  @props                                         ; inspect id! bindings
  (ui (.getText (:ui/greeting @props)))          ; read widget state
  (ui (llm-memory.ui.util/screenshot-widget! sh "/tmp/test.png"))
  (ui (.close sh)))
```

### `props` as a Test Interface

The `props` atom is CDT's shared state bus — `id!` writes widget refs, event
handlers read them. Returning it from `show` gives REPL tests direct access:

```clojure
(let [[sh props] (show (shell SWT/SHELL_TRIM "Test" ...))]
  (assert (:ui/my-widget @props))               ; verify id! bindings
  (swap! props assoc :my-flag true)              ; drive state like event handlers
  (ui (.getText (:ui/my-label @props))))         ; read widget identity
```

Key points:
- `(shell ...)` returns a CDT init fn — `show` calls it with `[props display]`
- The shell is created **and opened** on the UI thread
- The existing main-thread event loop handles events for the new window
- **Do not wrap in `future`** — `show` returns immediately


## 4. CDT Sugar Functions and Properties

### Sugar Functions

CDT auto-generates init functions for standard SWT widgets:
`shell`, `composite`, `label`, `text`, `button`, `browser`, `ctab-folder`,
`ctab-item`, `ccombo`, `cbanner`, `clabel`, `sash-form`, `scrolled-composite`,
`styled-text`, `view-form`, `table-cursor`, `tree-cursor`, `menu`, `menu-item`,
`tray-item`.

```clojure
(shell SWT/SHELL_TRIM "Window Title"
  :layout (FillLayout.)
  (label "Hello"))
```

- Bare strings -> `.setText()`
- `:keyword value` -> `.setKeyword(value)` via reflection (kebab-case -> camelCase)
- Nested calls -> child widgets
- `(ui/| style1 style2)` -> bitwise OR of style constants

### Keyword Properties

`:keyword value` in an init function calls the corresponding setter via reflection:

```clojure
(widget CTabFolder SWT/BORDER
  :simple false                           ; .setSimple(false)
  :minimize-visible true)                 ; .setMinimizeVisible(true)
```

### 0-Arg Constructors + Keyword Properties

When you need a Java object (e.g. `GridData`), prefer the 0-arg constructor
combined with CDT keyword properties over multi-arg constructors. CDT provides
init functions that follow this pattern — for example, `grid/grid-data`:

```clojure
;; BAD — positional args, meaning unclear without Javadoc
:layout-data (GridData. SWT/CENTER SWT/CENTER true false)

;; GOOD — CDT init fn: 0-arg constructor + keyword setters
(grid/grid-data :horizontal-alignment        SWT/CENTER
                :vertical-alignment          SWT/CENTER
                :grab-excess-horizontal-space true)
```

CDT also provides named convenience helpers built on top of `grid-data`:

| Helper | Equivalent |
|--------|-----------|
| `(grid/hgrab)` | `(grid/grid-data :grab-excess-horizontal-space true :horizontal-alignment SWT/FILL)` |
| `(grid/grab-both)` | `(grid/grid-data :grab-excess-horizontal-space true :grab-excess-vertical-space true :horizontal-alignment SWT/FILL :vertical-alignment SWT/FILL)` |
| `(grid/align-center)` | `(grid/grid-data :horizontal-alignment SWT/CENTER :vertical-alignment SWT/FILL)` |
| `(grid/align-center-hgrab)` | `(grid/grid-data :horizontal-alignment SWT/CENTER :vertical-alignment SWT/CENTER :grab-excess-horizontal-space true)` |

When a named helper matches your needs exactly, use it. When you need different
field combinations, use `grid/grid-data` with explicit keyword properties.

### `with-property` for Sub-object Properties

```clojure
(with-property :layout (FillLayout.)
  :margin-height 10
  :margin-width 10)
```

### Type Coercion

CDT auto-coerces common types (e.g. vectors -> `int[]`):

```clojure
(sash-form SWT/HORIZONTAL
  (label "left") (label "right")
  :weights [25 75])    ; PersistentVector -> int[]
```

Extend via `righttypes.core/convert` multimethod for custom type pairs.


## 5. `swtdoc` — Interactive API Explorer

```clojure
(require '[ui.SWT :refer [swtdoc]])

(swtdoc)                                  ; top-level help / navigation
(swtdoc :swt :composites)                 ; all composite widget types
(swtdoc :swt :widgets)                    ; all non-composite widgets
(swtdoc :swt :items)                      ; all Item subclasses
(swtdoc :swt :events)                     ; all event types
(swtdoc :swt :listeners)                  ; all listener classes
(swtdoc :swt :graphics)                   ; graphics types
(swtdoc :swt :SWT)                        ; SWT constants / style bits
(swtdoc :swt :composites "group")         ; details on a specific widget
(swtdoc :package :ui.SWT)                 ; CDT's own API functions
```

Run `swtdoc` first when working with a new widget type to discover:
- Whether it has a generated CDT init function or needs `widget`
- Valid parent/child relationships
- Available property setters and their types
- Mapped event/listener keywords


## 6. Custom Control Factory — Worked Example

A `custom-browser` factory with a `LocationListener` for URL scheme dispatch,
demonstrating `apply` + `extra-inits`, mutual recursion with `declare`, and
`async-exec!` for thread-safe tab creation:

```clojure
(declare ^:private open-tab!)     ; mutual recursion: custom-browser <-> open-tab!

(defn- custom-browser
  "A Browser with shared configuration. Accepts additional init functions
  (id!, :text, event handlers, etc.) appended after the defaults."
  [& extra-inits]
  (apply browser SWT/WEBKIT
         :javascript-enabled true

         (on e/changing [props parent event]
             ;; intercept pseudo-URL navigation
             (when-let [url (.text event)]
               (when (str/starts-with? url "winze:")
                 (set! (.doit event) false)        ; cancel browser navigation
                 (async-exec! #(handle-url! url)))))

         extra-inits))

(defn- open-tab!
  "Create a new file-viewer tab in the main CTabFolder."
  [title html tooltip]
  (let [tab-id (next-tab-id!)]
    (child-of folder app-props
      (custom-browser (id! tab-id)
                      :text html
                      (on e/widget-disposed [props parent event]
                          (swap! app-props dissoc tab-id))))
    ,,,))
```

Callers look like normal CDT code:

```clojure
;; Static layout — live search tab
(custom-browser (id! :ui/live-search-results)
                :text (search/empty-page))

;; Dynamic construction — file viewer tabs
(open-tab! "Title" "<h1>Content</h1>" "Tooltip text")
```


## 7. Event Handling

### The `on` Macro

```clojure
(require '[ui.events :as e])
(require '[ui.SWT :refer [on]])

(on e/widget-selected [props parent event]
  (println "Selected!" (.getText parent)))
```

- Event keywords from `ui.events` namespace (or plain keywords: `:widget-selected`)
- Parameter vector is always `[props parent event]`
- CDT discovers the listener interface, implements it, registers it

### Multi-Method Listeners — `proxy` Pattern

For multi-method interfaces (e.g. `CTabFolder2Listener`), use a single `proxy`
as a custom init function:

```clojure
(import '[org.eclipse.swt.custom CTabFolder2Adapter])

(fn [props parent]
  (let [sh-fn #(:ui/shell @props)]
    (.addCTabFolder2Listener parent
      (proxy [CTabFolder2Adapter] []
        (minimize [_e]
          (.setMinimized parent true)
          (.setLayoutData parent (GridData. SWT/FILL SWT/FILL true false))
          (.layout (sh-fn) true))
        (maximize [_e]
          (.setMaximized parent true)
          (.setLayoutData parent (GridData. SWT/FILL SWT/FILL true true))
          (.layout (sh-fn) true))
        (restore [_e]
          (.setMinimized parent false)
          (.setMaximized parent false)
          (.setLayoutData parent (GridData. SWT/FILL SWT/FILL true true))
          (.layout (sh-fn) true))))))
```

CDT keyword -> listener mapping (use `(swtdoc :swt :listeners)`):

```
CTabFolder2Listener: :close :items-count :maximize :minimize :restore :show-list
```


## 8. CTabItem — Worked Examples

### Static Tabs: `id!` + `control`

```clojure
(ctab-folder SWT/BORDER
  ;; Content first — saved to props via id!
  (text SWT/MULTI
    (id! :ui/tab1-content)
    "Tab content here")
  ;; Item second — reads content from props via control
  (ctab-item SWT/CLOSE
    :text "Tab Title"
    (control :ui/tab1-content)))
```

The `control` helper (in `llm-memory.ui.util`):

```clojure
(defn control
  "Returns a CDT init function that calls .setControl on the parent widget,
  wiring it to a previously constructed widget stored in props under `child-key`.
  Throws if `child-key` is absent (guards against init-order bugs)."
  [child-key]
  (fn [props parent]
    (let [control (get @props child-key)]
      (when-not control
        (throw (ex-info (str "control: " child-key " not found in props")
                        {:key child-key :props-keys (keys @props)})))
      (.setControl parent control))))
```

### Dynamic Tabs: Custom Init Function with Loop

CDT sugar arg lists are compile-time. Use a custom init fn for `doseq`/`for`:

```clojure
(fn [props parent]
  (doseq [i (range 8)]
    (let [item (CTabItem. parent SWT/CLOSE)
          text (Text. parent (bit-or SWT/MULTI SWT/V_SCROLL SWT/H_SCROLL))]
      (.setText item (str "Item " i))
      (.setControl item text))))
```

### Runtime Tab Creation: `child-of` + `defchildren`

Use `child-of` to add children to an existing widget at runtime. `child-of`
takes a single init function — when you need to add **multiple children**,
wrap them in `defchildren`:

```clojure
(defn open-tab!
  ([title html] (open-tab! title html title))
  ([title html tooltip]
   (let [folder (element :main-folder)
         tab-id (next-tab-id!)]
     (child-of folder app-props
       (defchildren
         (custom-browser (id! tab-id)
                         :text html
                         (on e/widget-disposed [props parent event]
                             (swap! app-props dissoc tab-id)))
         (ctab-item SWT/CLOSE (word-wrap 30 title)
                    :image          @statusbar-icon
                    :tool-tip-text  tooltip
                    (control tab-id))))
     (.setSelection folder (dec (.getItemCount folder))))))
```

Key points:
- `defchildren` groups multiple init functions into one, so `child-of` runs
  them all as children of the target parent
- Without `defchildren`, only the last init function would be passed to `child-of`
- The CTabItem sibling pattern (§9 in the guide) still applies: the Browser
  content widget is created before the CTabItem that references it via `control`


## 9. Resource Lifecycle Details

### `with-gc-on` and `doto-gc-on`

CDT helpers that create a `GC` on a drawable and guarantee disposal:

```clojure
;; Functional style — receives the GC, returns f's result
(with-gc-on drawable
  (fn [gc]
    (.setBackground gc (.getSystemColor display SWT/COLOR_BLUE))
    (.fillRectangle gc 0 0 100 100)))

;; Macro style — forms execute inside a (doto gc ...) block
(doto-gc-on drawable
  (.setBackground (.getSystemColor display SWT/COLOR_BLUE))
  (.fillRectangle 0 0 100 100))
```

### Long-Lived Resources

Dispose in an event handler tied to the widget's lifecycle:

```clojure
(let [image (Image. display drawer 16 16)]
  ;; ... use image in tab items, etc. ...
  (on e/widget-disposed [props parent event]
    (.dispose image)))
```


## 10. Screenshot API and Visual Test Loop

### API: `llm-memory.ui.util`

| Function | Purpose |
|----------|---------|
| `(screenshot-widget! widget path)` | Capture a specific widget's client area as PNG |
| `(screenshot-display! path)` | Capture the entire screen as PNG |
| `(save-image! image path)` | Save an externally-managed Image to PNG (does NOT dispose) |

Both `screenshot-*` functions handle their own resource disposal (`Image` in
`try`/`finally`, `GC` via `with-gc-on`).

### Setup (run once per REPL session)

```clojure
(require '[llm-memory.ui.main-window :as mw])
(require '[ui.SWT :refer [ui]])
(require 'llm-memory.ui.util)   ;; load the ns — do NOT alias it
```

### Quick Screenshots

```clojure
;; Main window
(ui (llm-memory.ui.util/screenshot-widget! (mw/element :main-window) "/tmp/window.png"))

;; Specific named widget
(ui (llm-memory.ui.util/screenshot-widget! (mw/element :live-search-results) "/tmp/search.png"))

;; Entire screen
(ui (llm-memory.ui.util/screenshot-display! "/tmp/screen.png"))
```

### Full Visual Test Loop

Simulate user interaction and capture results without a human in the loop:

```clojure
;; 1. Simulate typing — .setText fires modify-text events
(ui (.setText (mw/element :search) "gpu report cost"))

;; 2. Wait for async work (debounced search, rendering)
(Thread/sleep 3000)

;; 3. Screenshot
(ui (llm-memory.ui.util/screenshot-widget! (mw/element :live-search-results) "/tmp/results.png"))

;; 4. Read /tmp/results.png to verify visually

;; 5. Edge cases
(ui (.setText (mw/element :search) ""))    ;; empty -> placeholder
(Thread/sleep 500)
(ui (llm-memory.ui.util/screenshot-widget! (mw/element :live-search-results) "/tmp/empty.png"))

(ui (.setText (mw/element :search) "ab"))  ;; too short -> placeholder
(Thread/sleep 500)
(ui (llm-memory.ui.util/screenshot-widget! (mw/element :live-search-results) "/tmp/short.png"))
```

### How It Works

SWT's `GC` copies pixel data from any `Control` or `Display` into an `Image`:

1. Create `Image` -> wrap in `try`/`finally` for disposal
2. `with-gc-on` widget -> `gc.copyArea(image, 0, 0)` (GC auto-disposed)
3. `ImageLoader` + `image.getImageData()` -> `.save(path, SWT/IMAGE_PNG)`
4. `finally` -> `.dispose` the Image

Key details:
- **Must run on UI thread**: wrap in `(ui ...)` from nREPL
- **`GC(Control)`**: captures only that widget's client area
- **`GC(Display)`**: captures entire screen
- **Timing**: widget must be fully rendered; insert delay or use `async-exec!`
  to queue capture after layout completes


## 11. Uberjar Packaging with CDT + SWT

CDT dynamically loads platform-specific SWT native libraries at runtime using
`clojure.repl.deps/add-libs`. This creates several packaging requirements.

### deps.edn

Declare Clojure 1.12+ explicitly so `clojure.repl.deps` ends up in the uberjar:

```clojure
:deps {org.clojure/clojure {:mvn/version "1.12.3"}
       io.github.coconutpalm/clojure-desktop-toolkit {:mvn/version "0.5.1"}
       ,,,}
```

### `-main` — Classloader + `*repl*` before any CDT require

```clojure
(ns my.app
  ;; Do NOT require ui.SWT or any CDT namespace here
  (:gen-class))

(defn -main [& args]
  ;; 1. DynamicClassLoader so add-libs can inject URLs
  (let [cl (.getContextClassLoader (Thread/currentThread))]
    (.setContextClassLoader (Thread/currentThread) (clojure.lang.DynamicClassLoader. cl)))

  ;; 2. Bind *repl* so add-libs doesn't throw
  (binding [*repl* true]
    ;; 3. Require CDT — triggers SWT platform detection + native loading
    (require 'ui.SWT)

    ;; 4. Initialize Display on main thread (macOS: -XstartOnFirstThread)
    (eval '(reset! ui.SWT/display (org.eclipse.swt.widgets.Display/getDefault)))

    ;; 5. Start application on background threads
    ,,,

    ;; 6. Run event loop on main thread (required for syncExec / ui macro)
    (eval
     '(let [display (org.eclipse.swt.widgets.Display/getDefault)]
        (while (not (.isDisposed display))
          (when-not (.readAndDispatch display)
            (.sleep display)))))))
```

### build.clj — AOT only the main namespace

```clojure
(b/compile-clj {:basis @basis
                :ns-compile ['my.app]   ; only this ns + static deps
                :class-dir class-dir})
```

### JVM Args

```
-XstartOnFirstThread                           ;; macOS SWT requirement
--add-opens=java.base/java.nio=ALL-UNNAMED     ;; Datalevin (winze-specific)
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED   ;; Datalevin (winze-specific)
--enable-native-access=ALL-UNNAMED             ;; Datalevin (winze-specific)
```

For a CDT-only app without Datalevin, only `-XstartOnFirstThread` is needed.

Pattern from: `github.com/coconutpalm/clojure-desktop-toolkit/examples/starter`


## 12. macOS Integration

### Application Name

`Display/setAppName` sets the leftmost menu bar entry. Must be called before
`Display/getDefault`:

```clojure
(require 'ui.SWT)
(Display/setAppName "Winze")
(eval '(reset! ui.SWT/display (org.eclipse.swt.widgets.Display/getDefault)))
```

### System Menu Items

The system "About", "Preferences", and "Quit" items live in `Display.getSystemMenu()`,
not in SWT's widget hierarchy. Use `SWT/ID_ABOUT`, `SWT/ID_PREFERENCES`,
`SWT/ID_QUIT` to find them:

```clojure
(when-let [sys-menu (.getSystemMenu (Display/getDefault))]
  (doseq [item (.getItems sys-menu)]
    (when (= (.getID item) SWT/ID_ABOUT)
      (.addListener item SWT/Selection
        (reify Listener
          (handleEvent [_ _event]
            (show-about-dialog! parent-shell)))))))
```

### Modal Dialogs (Nested Event Loop)

```clojure
(let [shell (Shell. parent-shell (bit-or SWT/DIALOG_TRIM SWT/APPLICATION_MODAL))]
  ;; ... build dialog contents ...
  (.open shell)
  (.layout shell)
  (let [disp (.getDisplay parent-shell)]
    (while (not (.isDisposed shell))
      (when-not (.readAndDispatch disp)
        (.sleep disp)))))
```

Uncaught exceptions in event handlers can silently dispose the Display — always
wrap handlers in `try`/`catch`.

### Clean Process Exit

```clojure
(stop!)              ;; clean up nREPL, store, watchers
(shutdown-agents)
(System/exit 0)      ;; terminates NSApplication + JVM
```

### MacBook Notch and Menu Bar Overflow

The display notch limits menu bar space. macOS silently hides status bar items
that overflow. No programmatic fix — users can manage with Bartender or Ice.


## 13. HiDPI / Retina Support

### ImageDataProvider for Multi-Resolution Images

```clojure
(import '[org.eclipse.swt.graphics Image ImageData ImageDataProvider])

(defn hidpi-image [^String path-1x ^String path-2x]
  (Image. (Display/getDefault)
    (reify ImageDataProvider
      (getImageData [_ zoom]
        (let [path   (if (>= zoom 200) path-2x path-1x)
              stream (.getResourceAsStream (clojure.lang.RT/baseLoader) path)]
          (ImageData. stream))))))
```

- `zoom=100` -> standard DPI (external monitors)
- `zoom=200` -> Retina (MacBook built-in display)
- The `Image` reports logical size regardless of zoom

Use for **system tray icons** and any image that must render crisply on both
standard and Retina. Follow macOS naming: `fooTemplate.png` (1x),
`fooTemplate@2x.png` (2x).


## 14. Winze UI Architecture

### Widget Tree

```
Shell [SWT/SHELL_TRIM]
├── tray-item (system tray icon, HiDPI)
│   ├── left-click -> toggle window visibility
│   └── right-click -> tray-menu (Toggle / About / Quit)
├── GridLayout [1 column]
├── header [Composite]
│   ├── logo Image
│   └── search Text widget
│       ├── modify-text -> live search (debounced)
│       ├── default-selected -> open tab from search
│       └── cancel (Esc) -> clear search
├── body [Composite]
│   └── CTabFolder
│       ├── live-search tab [Browser via custom-browser]
│       │   └── LocationListener dispatches pseudo-URLs
│       │       ├── winze:open-file?root=...&path=... -> open-tab!
│       │       └── winze:search?q=... -> set search text
│       └── file-viewer tabs [dynamic, via open-tab!]
│           └── Browser with markdown-rendered file content
└── macOS system menu hooks (About -> modal dialog)
```

### Event Flow: Live Search

1. User types in search Text widget
2. `modify-text` event fires (on UI thread)
3. Handler calls `search/results` with `async-exec!`
4. Debouncer (300ms `ScheduledExecutorService`) cancels pending, schedules new
5. Executor thread runs `core/search`, builds HTML via Hiccup + nextjournal/markdown
6. `async-exec!` queues `.setText` on Browser with results HTML
7. Browser renders styled HTML with clickable metadata pills and result cards

### Pseudo-URL Scheme

The `custom-browser` factory installs a `LocationListener` that intercepts
navigation to `winze:` URLs, cancels browser navigation (`(.doit event) false`),
and dispatches:

| URL | Action |
|-----|--------|
| `winze:open-file?root=<uri>&path=<relpath>` | Open file in new tab |
| `winze:search?q=<query>` | Set search box text (triggers live search) |

### Named Widget Access

```clojure
(mw/element :main-window)         ;; the Shell
(mw/element :search)              ;; the search Text widget
(mw/element :live-search-results) ;; the search results Browser
(mw/element :main-folder)         ;; the CTabFolder
```

### Dynamic Tab Management

- `tab-counter` atom + `next-tab-id!` generate unique `:ui/tab-N` keywords
- Tabs are created with `child-of folder app-props (custom-browser ...)`
- Closed tabs clean up via `(on e/widget-disposed ... (swap! app-props dissoc tab-id))`
