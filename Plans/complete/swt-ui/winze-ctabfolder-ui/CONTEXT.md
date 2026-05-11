---
created: 2026-03-24
group: winze-ctabfolder-ui
related: [winze-server]
tags: [swt, ui, ctabfolder, clojure-desktop-toolkit]
---

# Context: Reimplement SWT Snippet165 & Snippet371 in Clojure (Winze)

## Goal

Add a `ctab-folder-demo` function to `llm-memory.ui.main-window` that demonstrates
CTabFolder features from two Eclipse SWT snippets, reimplemented idiomatically using
the clojure-desktop-toolkit (CDT v0.5.1).

**Prerequisite reading**: [`Plans/SWT-UI-GUIDE.md`](../SWT-UI-GUIDE.md) — threading model,
init function architecture, `widget` macro, `swtdoc`, event handling, and CTabItem
parent/child pattern.

**CDT documentation**: https://github.com/coconutpalm/clojure-desktop-toolkit/blob/main/docs/000-index.md

## Source Snippets

### Snippet165 — CTabFolder with min/max/close buttons and images

Creates a CTabFolder with:
- 8 closeable tabs, each with an icon and multiline Text content
- Minimize / maximize buttons on the folder
- Image shown only on the selected tab (`setUnselectedImageVisible(false)`)
- Close button shown only on selected tab (`setUnselectedCloseVisible(false)`)
- `setSimple(false)` for curved tab rendering
- CTabFolder2Listener for minimize/maximize/restore events that toggle GridData fill behavior and re-layout the shell

Key Java types:
- `CTabFolder`, `CTabItem` (org.eclipse.swt.custom)
- `CTabFolder2Adapter`, `CTabFolderEvent` (org.eclipse.swt.custom)
- `GridLayout`, `GridData` (org.eclipse.swt.layout)
- `Image`, `ImageGcDrawer` (org.eclipse.swt.graphics)
- `Text` (org.eclipse.swt.widgets)

### Snippet371 — CTabFolder with multi-line tab text

Creates a CTabFolder with:
- 2 closeable tabs; second tab has a newline `\n` in its text (multi-line tab title)
- FillLayout on the shell
- Each tab contains a simple Text widget

Key Java types:
- `CTabFolder`, `CTabItem` (org.eclipse.swt.custom)
- `FillLayout` (org.eclipse.swt.layout)
- `Point` (org.eclipse.swt.graphics)

## clojure-desktop-toolkit (CDT) API Summary

### Architecture

CDT wraps SWT via **init functions** — every UI element is an `(fn [props parent] ...)` that constructs a widget, runs child inits, and returns the widget. Sugar macros (`shell`, `label`, `menu`, etc.) generate these.

### Key Patterns

| Pattern | Example |
|---------|---------|
| Create a window (REPL) | `(show (shell SWT/SHELL_TRIM "Title" :layout (FillLayout.) ...))` → `[widget props]` |
| Create a window (standalone) | `(application (shell SWT/SHELL_TRIM "Title" ...))` — standalone JVM only |
| Bare strings | Auto-assign to `.setText` |
| `:keyword value` | Calls `.setKeyword(value)` via reflection (kebab-case → camelCase) |
| `(id! :ui/name)` | Saves widget ref in `props` atom |
| `(on e/event-name [props parent event] ...)` | Event handler |
| `(widget Class style & inits)` | Generic widget constructor (for classes without dedicated sugar) |
| `(with-property :layout (FillLayout.) :margin-height 10)` | Set properties on sub-objects |
| `(ui/\| style1 style2)` | Bitwise OR of style constants |
| Type coercion | Vectors auto-coerce to `int[]`, etc. |

### CTabFolder — Has Generated CDT Init Functions

Both `ctab-folder` and `ctab-item` are in CDT's auto-generated init function set
(confirmed via `(swtdoc :package :ui.SWT)` and `(swtdoc :swt :composites)`/`:items`).
Use them directly — no need for the `widget` escape hatch.

```clojure
(require '[ui.SWT :refer [ctab-folder ctab-item]])
```

Key property setters on `ctab-folder` (all work via `:keyword value` reflection):
`:simple`, `:minimize-visible`, `:maximize-visible`, `:m-r-u-visible`,
`:selection`, `:tab-height`, `:tab-position`, `:unselected-image-visible`,
`:unselected-close-visible`, `:layout-data`, `:border-visible`, etc.

Key property setters on `ctab-item`:
`:text`, `:image`, `:disabled-image`, `:control`, `:show-close`,
`:tool-tip-text`, `:font`, `:foreground`, `:selection-foreground`

**Critical**: `ctab-item` has a `:control` setter but `CTabItem` is not a `Composite`
— it cannot be a parent for `Text` or any other `Control`. CDT child init functions
receive `CTabItem` as parent, which fails for content widgets. Use a **custom init
function** passed as a child of `ctab-folder` instead (see sibling pattern below).

### CTabItem Sibling Pattern (Required for Tab Content)

The content widget of a tab must be created as a **sibling** child of `CTabFolder`,
then wired via `.setControl`. The CDT-idiomatic approach:

1. Construct the content widget first (as a child of `ctab-folder`), naming it with `(id! :ui/name)`.
2. Construct the `ctab-item` next, passing a `(control :ui/name)` init function that
   reads the widget from `props` and calls `.setControl` on the `CTabItem`.

**Why `:control (:ui/name @props)` doesn't work**: CDT init function args are evaluated at
the call site, where `props` is not in scope. The `:keyword value` pairs in a CDT init fn
are compiled — there's no `props` free variable available. The wiring must happen inside
an init function body where `props` is a parameter.

### `control` Helper (in `llm-memory.ui.util`)

```clojure
(defn control
  "Returns a CDT init function that calls .setControl on the parent widget,
  wiring it to a previously constructed widget stored in props under `child-key`.
  Works with any SWT widget that has a .setControl method (CTabItem,
  ScrolledComposite, ViewForm, etc.).
  Throws if `child-key` is not found in props (guards against init-order bugs)."
  [child-key]
  (fn [props parent]
    (let [widget (get @props child-key)]
      (when-not widget
        (throw (ex-info (str "control: " child-key " not found in props")
                        {:key        child-key
                         :props-keys (keys @props)})))
      (.setControl parent widget))))
```

Usage:

```clojure
(ctab-folder SWT/BORDER
  (text SWT/MULTI
    (id! :ui/tab1-content)
    "Tab content here")
  (ctab-item SWT/CLOSE
    :text "Tab Title"
    (control :ui/tab1-content)))
```

**Order matters**: the content widget init fn must run before `ctab-item` so that
`:ui/tab1-content` is in `@props` when `control`'s fn runs.

For multiple tabs, repeat the content→item pair:

```clojure
(ctab-folder SWT/BORDER
  (text SWT/MULTI (id! :ui/tab1-text) "Content for Tab 1")
  (ctab-item SWT/CLOSE :text "Tab 1" (control :ui/tab1-text))
  (text SWT/MULTI (id! :ui/tab2-text) "Content for Tab 2")
  (ctab-item SWT/CLOSE :text "Tab 2" (control :ui/tab2-text)))
```

For a loop (Snippet165 — 8 tabs), the `props`-based approach doesn't compose cleanly with
`doseq`. Use a custom init function instead:

```clojure
(fn [props parent]
  (doseq [i (range 8)]
    (let [item (CTabItem. parent SWT/CLOSE)
          text (Text. parent (bit-or SWT/MULTI SWT/V_SCROLL SWT/H_SCROLL))]
      (.setText item (str "Item " i))
      (.setControl item text))))
```

**Verified** (2026-03-24): The `id!`/`control` wiring pattern works — tested via probe
and full Snippet371 implementation. See PLAN.md Steps 2b–3.

### Event Handling for CTabFolder

CDT recognizes `CTabFolder2Listener` keywords via `(swtdoc :swt :listeners)`:

```
:close  :items-count  :maximize  :minimize  :restore  :show-list
```

**WARNING: Do NOT use multiple CDT `on` calls for CTabFolder2 events.** Each `on` call
registers a **separate** `reify CTabFolder2Listener` where 5 of 6 methods are empty
no-ops. These no-op methods interfere with SWT's event dispatch — handlers fire (logged
via atoms) but produce no visible effect. This was confirmed empirically on macOS SWT
(2026-03-24).

**Use a single `proxy [CTabFolder2Adapter]` instead**, passed as a custom init function:

```clojure
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

CDT `on` is fine for **single-method** listener interfaces (e.g. `SelectionListener`
via `:widget-selected`, `ShellListener` via `:shell-closed`). The problem is specific
to **multi-method** interfaces where multiple `on` calls create competing listeners.

The event type is `org.eclipse.swt.custom.CTabFolderEvent`.

### `swtdoc` — Interactive API Explorer

```clojure
(swtdoc)                                     ; Top-level help
(swtdoc :swt :composites)                    ; All composite widgets
(swtdoc :swt :composites "ctab-folder")      ; CTabFolder details
(swtdoc :swt :items)                         ; All item types
(swtdoc :swt :items "ctab-item")             ; CTabItem details
(swtdoc :swt :listeners)                     ; All listener → keyword mappings
(swtdoc :package :ui.SWT)                    ; CDT API functions
```

## Existing Code

### `llm-memory.ui.main-window` (`main_window.clj`)

```clojure
(ns llm-memory.ui.main-window
  (:require [llm-memory.ui.util :refer [control screenshot-widget! show]]
            [ui.events :as e]
            [ui.SWT :refer [application ctab-folder ctab-item id! label menu menu-item on
                            shell shell-invisible text tray-item ui]])
  (:import [org.eclipse.swt SWT]
           [org.eclipse.swt.graphics Point]
           [org.eclipse.swt.layout FillLayout]))
```

Contains `main-window` (system tray app) and `snippet-371` (CTabFolder demo, implemented).
`snippet-165` will be added alongside these.

### Server entry point (`main.clj`)

`-main` follows the CDT starter pattern (see SWT-UI-GUIDE §10): `DynamicClassLoader` →
`*repl*` binding → runtime `require 'ui.SWT` → Display init → `start!` → SWT event loop.
The event loop runs on the main thread so `syncExec` / `(ui ...)` can dispatch work from
nREPL threads. New demo functions should be callable from the REPL `(comment ...)` block.

## Platform Considerations

### macOS: `-XstartOnFirstThread` and the UI Thread

SWT on macOS requires the **main thread** to be the UI thread. The JVM argument
`-XstartOnFirstThread` arranges this.

**Why this matters for REPL-driven development**: The thread that calls `(Display/getDefault)`
becomes the UI thread. Winze's `-main` does this as its very first action. All subsequent UI
operations must run on that thread — you cannot just call SWT widget constructors from an
nREPL eval thread.

**REPL options** (in order of preference):

1. **Run the installed winze server, connect to its nREPL**. Winze already calls
   `(Display/getDefault)` on the main thread with `-XstartOnFirstThread`. Connect to
   its nREPL port (`~/.local/share/winze/.nrepl-port`) and use `(ui ...)` to run
   UI code on the SWT thread.

2. **Write a UI-only `-main`** for winze-server that starts only the nREPL (no server),
   but still calls `(Display/getDefault)` first on the main thread. The server's
   `start!`/`stop!` lifecycle functions can be called from the REPL later if needed.

3. **Start a standalone nREPL** with `-J-XstartOnFirstThread`. This gives the thread flag
   but you still must call `(Display/getDefault)` from the main thread before any UI work.

### The `ui` Macro — Running Code on the SWT Thread

CDT's `ui.SWT/ui` macro acts like Clojure's `do` but executes its body on the SWT UI thread:

```clojure
(require '[ui.SWT :refer [ui]])

;; From any thread (e.g. nREPL eval):
(ui (.setText some-widget "Hello"))
```

Under the hood, `ui` delegates to `with-ui*` which checks `(ui-thread?)`:
- If already on the UI thread → runs directly
- If not → uses `Display.syncExec()` to run on the UI thread, captures result/exceptions

**Threading rules for this task**:
- **All widget construction** must happen on the UI thread (use `ui` or `sync-exec!`)
- **UI state mutations in event handlers** should use `async-exec!` (queues after pending events)
- **Reading UI state** from background threads → use `sync-exec!` or `ui`
- The `application` function's thread becomes the UI thread for its event loop — but from
  the REPL, we're connecting to a server where the main thread is already the UI thread

### Image/Resource Lifecycle

SWT Image objects must be explicitly disposed. Use CDT's disposable resource pattern or
dispose in a shell-closed handler.

## Dependencies (already in winze-server/deps.edn)

```clojure
io.github.coconutpalm/clojure-desktop-toolkit {:mvn/version "0.5.1"}
```

**Note**: Clojure 1.12.3 is now explicitly declared in deps.edn (required for
`clojure.repl.deps` which CDT uses for dynamic SWT native loading).
See `Plans/SWT-UI-GUIDE.md` §10 for the full uberjar packaging pattern.

No new dependencies needed. Only new imports:
- `org.eclipse.swt.custom` — CTabFolder, CTabItem, CTabFolder2Adapter, CTabFolderEvent
- `org.eclipse.swt.layout` — GridLayout, GridData
- `org.eclipse.swt.graphics` — GC, Image, ImageLoader, ImageData, Point

## Visual Verification via Screenshots

Claude Code can **see** what it builds by capturing widget screenshots, saving them as PNGs,
and reading the image files back. This creates a visual test loop without requiring a human.

**Implementation**: `llm-memory.ui.util` namespace (created in Plan Step 1) provides:
- `(screenshot-widget! widget path)` — capture a widget as PNG
- `(screenshot-display! path)` — capture the entire screen as PNG
- `(show init-fn)` — run a CDT init fn on the UI thread, returns `[widget props]`
- `(control child-key)` — returns an init fn that wires `.setControl` via props

Both use CDT's `with-gc-on` for safe GC disposal. Technique based on SWT Snippet215
(screen capture via GC/copyArea) + Snippet246 (ImageLoader.save to PNG).

```clojure
(require '[llm-memory.ui.util :as ui-util])
(ui (ui-util/screenshot-widget! (:ui/shell @props) "/tmp/my-window.png"))
;; Then Claude Code reads the PNG via the Read tool (multimodal — it can see images)
```

See `Plans/SWT-UI-GUIDE.md` §9 for the full visual verification workflow.

**Key constraint**: Must run on the UI thread — wrap in `(ui ...)` from nREPL.

## Resolved Questions (from Steps 2–4 REPL Discovery, 2026-03-24)

1. **`ctab-folder` / `ctab-item` CDT init fns?** → **Both exist** in `ui.SWT` — no `widget` macro needed.
2. **`CTabFolder2Listener` event keywords?** → **`:close :items-count :maximize :minimize :restore :show-list`** — CDT's `on` macro handles them all; `proxy [CTabFolder2Adapter]` not needed.
3. **`:keyword value` on CTabFolder setters?** → **Yes** — full setter list confirmed via `swtdoc`. `:simple`, `:minimize-visible`, `:maximize-visible`, `:layout-data`, etc. all work.
4. **`ImageGcDrawer`?** → **Confirmed** in `org.eclipse.swt.graphics` — use `(reify ImageGcDrawer (drawOn [_ gc] ...))`.
5. **`:layout-data (GridData. ...)`?** → **Yes** — `setLayoutData(Object)` is in the properties list, inherited from `org.eclipse.swt.widgets.Control`.
6. **CTabItem sibling constraint still applies** — even with a generated `ctab-item` init fn, `CTabItem` is not a `Composite`, so content widgets cannot be parented to it. Custom init functions are required for each tab.
7. **`Image` constructor with `ImageGcDrawer` requires UI thread** — wrap in `(ui ...)` before passing to `show`. Nesting `ui` is safe (CDT checks `ui-thread?` and runs directly).
8. **`require :reload` fails for CDT namespaces** — CDT's `ui.SWT` vars are dynamically generated. Use `load-file` from the REPL instead of `require :reload`.
