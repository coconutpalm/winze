---
created: 2026-03-24
doc_type: reference
tags: [swt, ui, clojure-desktop-toolkit, repl, threading]
---

# SWT + Clojure Desktop Toolkit: Rules

Rules you must follow when writing SWT UI code with CDT. For worked examples,
implementation details, and architecture, see [SWT-REFERENCE.md](SWT-REFERENCE.md).

**CDT documentation**: https://github.com/coconutpalm/clojure-desktop-toolkit/blob/main/docs/000-index.md


## 1. The UI thread is sacred

- The thread that calls `(Display/getDefault)` becomes the SWT UI thread.
- On macOS, that **must** be the JVM main thread (`-XstartOnFirstThread`).
- **All widget construction and mutation** must happen on the UI thread.
- nREPL eval threads are **not** the UI thread.

> Details: [SWT-REFERENCE.md §1](SWT-REFERENCE.md#1-threading-model-details) — `sync-exec!` vs `async-exec!` table, `application` vs `ui` table, event loop mechanics.


## 2. Use `ui` to read, `async-exec!` to mutate

From any non-UI thread (nREPL, `future`, executor):

- **Read** UI state with `(ui ...)` / `sync-exec!` (blocks until result is returned).
- **Mutate** UI state with `async-exec!` (queues after pending events).

**Always wrap widget mutations in `async-exec!`**, even if the current caller
happens to be on the UI thread. This makes the function safe from any thread:

```clojure
;; BAD — works from event handlers, throws from nREPL
(defn update-browser [browser html]
  (.setText browser html))

;; GOOD — works from any thread
(defn update-browser [browser html]
  (async-exec! #(.setText browser html)))
```

**Never mutate UI state directly inside an event handler.** Use `async-exec!`
to queue mutations after pending events complete — this prevents platform-specific
event chain interruption (e.g. mouse-down -> focus-out -> focus-in -> mouse-up).


## 3. Never call `application` against a running server

`application` runs its own event loop and calls `(.dispose display)` on exit,
killing the entire Display and crashing the server.

Use `show` from `llm-memory.ui.util` for REPL testing against a running server:

```clojure
(let [[sh props] (show (shell SWT/SHELL_TRIM "Test" :layout (FillLayout.) ...))]
  @props                                         ; inspect id! bindings
  (ui (llm-memory.ui.util/screenshot-widget! sh "/tmp/test.png"))
  (ui (.close sh)))
```

> Details: [SWT-REFERENCE.md §2](SWT-REFERENCE.md#2-repl-setup-for-ui-work) — REPL setup, connecting to Winze nREPL. [§3](SWT-REFERENCE.md#3-the-show-function-and-props-atom) — `show` implementation, `props` atom as test interface.


## 4. CDT idioms over Java interop

Write UI code using CDT's declarative init functions (`shell`, `composite`,
`label`, `text`, `button`, `browser`, `on`, `id!`, etc.) — not raw Java interop.

If you find yourself writing `.setText`, `.addListener`, or `(new Widget ...)` in
bulk, step back and look for CDT equivalents. Only drop to interop for things CDT
genuinely doesn't cover (e.g. centering dialogs, modal event loops).

When you do need a Java object (e.g. `GridData`, `Image`), prefer the **0-arg
constructor + CDT keyword properties** over multi-arg constructors. CDT's
keyword setters (kebab-case -> camelCase) keep the code declarative and readable:

```clojure
;; BAD — positional args, meaning unclear without checking Javadoc
:layout-data (GridData. SWT/CENTER SWT/CENTER true false)

;; BEST — use a named CDT helper when one matches
(grid/align-center-hgrab)

;; GOOD — 0-arg constructor + keyword properties when no helper fits
(grid/grid-data :horizontal-alignment        SWT/CENTER
                :vertical-alignment          SWT/CENTER
                :grab-excess-horizontal-space true)
```

Prefer named helpers (`grid/hgrab`, `grid/align-center-hgrab`, etc.) when they
match your needs. Fall back to `grid/grid-data` with explicit keywords when
no helper fits.

> Details: [SWT-REFERENCE.md §4](SWT-REFERENCE.md#4-cdt-sugar-functions-and-properties) — keyword property assignment, helper table, `with-property`, type coercion.


## 5. Every widget is an init function: `(fn [props parent] ...)`

CDT's compositional model:

- Every UI element is an init function that receives `props` (shared atom) and
  `parent` (SWT Composite), constructs a widget, runs child inits, returns the widget.
- Store widget refs with `(id! :ui/name)` — retrievable as `(:ui/name @props)`.
- Store state with `(swap! props assoc :key val)`.
- Convention: `:ui/` namespace for widget refs.

> Details: [SWT-REFERENCE.md §3](SWT-REFERENCE.md#3-the-show-function-and-props-atom) — `props` atom usage, `id!` bindings, test interface pattern.


## 6. Prefer CDT sugar; `widget` is the escape hatch

CDT auto-generates init functions for standard SWT widgets. **Always check
`swtdoc` at the REPL before assuming you need `widget`:**

```clojure
(swtdoc :swt :composites)       ; composite types
(swtdoc :swt :widgets)          ; non-composite widgets
(swtdoc :swt :items)            ; Item subclasses
```

Confirmed generated (CDT v0.5.1): `shell`, `composite`, `label`, `text`,
`button`, `browser`, `ctab-folder`, `ctab-item`, `ccombo`, `cbanner`, `clabel`,
`sash-form`, `scrolled-composite`, `styled-text`, `view-form`, `table-cursor`,
`tree-cursor`, `menu`, `menu-item`, `tray-item`.

Only use `widget` for third-party or undetected SWT controls:

```clojure
(widget SomeThirdPartyControl SWT/BORDER
  :some-property value
  child-init-fn)
```

> Details: [SWT-REFERENCE.md §4](SWT-REFERENCE.md#4-cdt-sugar-functions-and-properties) — full sugar catalog, keyword properties, `with-property`, type coercion. [§5](SWT-REFERENCE.md#5-swtdoc--interactive-api-explorer) — all `swtdoc` query forms.


## 7. Custom control factories: `apply` + `extra-inits`

When multiple call sites need the same widget type with shared configuration,
wrap an existing CDT init function — don't rebuild from scratch:

```clojure
(defn- custom-browser [& extra-inits]
  (apply browser SWT/WEBKIT
         :javascript-enabled true
         (on e/changing [props parent event] ,,,)
         extra-inits))

;; Callers compose normally:
(custom-browser (id! :ui/results) :text initial-html)
```

> Details: [SWT-REFERENCE.md §6](SWT-REFERENCE.md#6-custom-control-factory--worked-example) — full `custom-browser` example with `LocationListener`, mutual recursion, `open-tab!`.


## 8. Single `proxy` for multi-method listeners

CDT's `on` macro is safe for **single-method** listener interfaces
(`:widget-selected`, `:shell-closed`, etc.).

For **multi-method** listeners (e.g. `CTabFolder2Listener`), `on` registers
separate listeners per call, causing visual failures on macOS. Use a **single
`proxy`** instead:

```clojure
(.addCTabFolder2Listener parent
  (proxy [CTabFolder2Adapter] []
    (minimize [_e] ,,,)
    (maximize [_e] ,,,)
    (restore  [_e] ,,,)))
```

> Details: [SWT-REFERENCE.md §7](SWT-REFERENCE.md#7-event-handling) — `on` macro syntax, event keywords, full `proxy` example with `CTabFolder2Adapter`.


## 9. CTabItem content is a sibling, not a child

CTabItem is not a Composite — its content widget is a child of the
**CTabFolder**, wired via `.setControl`. Create the content first with `id!`,
then reference it with the `control` helper. **Order matters** — the content
init fn must run before the item init fn:

```clojure
(ctab-folder SWT/BORDER
  (text SWT/MULTI (id! :ui/tab1-content) "Content")   ; content first
  (ctab-item SWT/CLOSE :text "Tab" (control :ui/tab1-content)))  ; item second
```

For dynamic/looped tab creation, use a custom init function with `doseq`.

> Details: [SWT-REFERENCE.md §8](SWT-REFERENCE.md#8-ctabitem--worked-examples) — `control` helper implementation, dynamic tab loops, runtime `child-of` tab creation.


## 10. Use `defchildren` to add multiple children via `child-of`

`child-of` takes a single init function. When you need to add more than one
child to an existing widget at runtime, wrap them in `defchildren`:

```clojure
(child-of folder app-props
  (defchildren
    (custom-browser (id! tab-id) :text html)
    (ctab-item SWT/CLOSE "Tab Title" (control tab-id))))
```

Without `defchildren`, only the last init function would be passed to `child-of`.

> Details: [SWT-REFERENCE.md §8](SWT-REFERENCE.md#8-ctabitem--worked-examples) — full `open-tab!` example using `defchildren` with `child-of`.


## 11. If you created a resource, you must dispose it

SWT resources (`Image`, `GC`, `Color`, `Font`, `Region`) are native OS handles
**not garbage-collected**. If you called `new`, you must call `.dispose`.

**Always use `try`/`finally`:**

```clojure
(let [image (Image. display 100 100)]
  (try
    ;; ... work ...
    (finally (.dispose image))))
```

For long-lived resources, dispose in `(on e/widget-disposed ...)` or
`(on e/shell-closed ...)`.

Use CDT's `with-gc-on` / `doto-gc-on` for safe GC creation and disposal.

> Details: [SWT-REFERENCE.md §9](SWT-REFERENCE.md#9-resource-lifecycle-details) — `with-gc-on` / `doto-gc-on` API, long-lived resource disposal patterns.


## 12. Draw icons with GC, not PNG files with alpha

On macOS, SWT's Cocoa image renderer mishandles **semi-transparent pixels**
(0 < alpha < 255) in PNG images — they render as a purple/garbage-colored blob
instead of blending correctly. Fully transparent (alpha=0) and fully opaque
(alpha=255) pixels are fine; the problem is partial transparency.

This affects any PNG rasterized from an SVG that uses `fill-opacity`,
`stroke-opacity`, or gradient-based transparency (e.g. icons drawn in the brand
Amethyst palette with soft edges).

**Two workarounds:**

1. **Opaque background**: Design the SVG with a filled background (as the app
   icon does with its dark circular background). All pixels are fully opaque, so
   no alpha issues. Suitable for app icons and images on known backgrounds.

2. **Programmatic GC drawing** (preferred for toolbar/UI icons): Draw the icon
   at runtime using SWT `GC` on a blank `Image`. Drawn strokes and fills are
   fully opaque; untouched pixels remain fully transparent. No partial alpha.

```clojure
(defn- draw-my-icon [size]
  (ui
   (let [image (Image. @display size size)
         color (Color. @display 0x9B 0x8F 0xE0)]
     (with-gc-on image
       (fn [gc]
         ;; Fill with widget background so the icon blends with the toolbar.
         ;; System colors are managed by SWT — do NOT dispose them.
         (.setBackground gc (.getSystemColor @display SWT/COLOR_WIDGET_BACKGROUND))
         (.fillRectangle gc 0 0 size size)
         (.setAntialias gc SWT/ON)
         (.setForeground gc color)
         ;; ... drawing commands ...
         ))
     (.dispose color)
     image)))
```

Wrap in an `ImageDataProvider` for HiDPI support (draw at 16×16 and 32×32,
capture `ImageData`, dispose temporary images, create final `Image` from
provider). See `resources.clj` for working examples.

**Never use PNGs with partial transparency for SWT icons on macOS.**


## 13. Targeted `:reload` only, never `:reload-all`

`:reload-all` on namespaces that transitively require CDT causes
`Alias i already exists in namespace` errors. Always reload specific namespaces:

```clojure
;; BAD
(require '[llm-memory.ui.main-window :as mw] :reload-all)

;; GOOD
(require '[llm-memory.ui.search :as search] :reload)
(require '[llm-memory.ui.main-window :as mw] :reload)
```


## 14. Forward-declare mutual recursion

When a factory's event handler calls a function that uses the factory (e.g.
`custom-browser` -> `open-tab!` -> `custom-browser`), **you must
`(declare fn-name)`**. Without it, namespace compilation fails and the server
crashes on startup.

> Details: [SWT-REFERENCE.md §6](SWT-REFERENCE.md#6-custom-control-factory--worked-example) — full mutual recursion example with `declare`, `custom-browser`, and `open-tab!`.


## 15. Screenshot-verify all visual changes

Never report a visual UI change as done without a screenshot. Use the
fully-qualified namespace (aliases fail intermittently in long nREPL sessions):

```clojure
(ui (llm-memory.ui.util/screenshot-widget! (mw/element :main-window) "/tmp/window.png"))
```

Then use Claude Code's `Read` tool on the PNG to visually inspect.

> Details: [SWT-REFERENCE.md §10](SWT-REFERENCE.md#10-screenshot-api-and-visual-test-loop) — full screenshot API, visual test loop (simulate typing + capture), how `screenshot-widget!` works internally.


## 16. `setAppName` before `Display/getDefault`

Once the Display is created, the macOS NSApplication name is locked in. Call
`Display/setAppName` **before** creating the Display.

> Details: [SWT-REFERENCE.md §12](SWT-REFERENCE.md#12-macos-integration) — `setAppName` example, system menu hooks, modal dialogs, notch/overflow.


## 17. `System/exit` for clean shutdown on macOS

Disposing the Display does not terminate the JVM. Non-daemon threads (nREPL,
agent pools) keep it alive, and the Cocoa NSApplication stays in the Dock.
Always call `(System/exit 0)` after cleanup.

> Details: [SWT-REFERENCE.md §12](SWT-REFERENCE.md#12-macos-integration) — clean exit pattern with `stop!`, `shutdown-agents`, `System/exit`.


## 18. Retrieve widgets with `mw/element`, never init functions

`(mw/element :main-window)` retrieves the existing widget. `(mw/main-window)`
constructs an **entirely new window** on top of the running one.

```clojure
;; BAD — creates a second window
(mw/main-window)

;; GOOD — gets the existing one
(mw/element :main-window)
(mw/element :search)
(mw/element :live-search-results)
```

> Details: [SWT-REFERENCE.md §14](SWT-REFERENCE.md#14-winze-ui-architecture) — full widget tree, named widget keys, dynamic tab management, event flow, pseudo-URL scheme.


## 19. Use `async-exec!` for event "transactions"

A single user action (keypress, mouse click, traverse) can spawn multiple SWT
events in sequence (e.g. KeyDown → Traverse → KeyUp, or mouse-down → focus-out
→ focus-in → mouse-up). If an event handler mutates the UI synchronously
(moving focus, disposing widgets, changing text), it can interrupt the remaining
events in the sequence, causing platform-specific misbehavior.

**Pattern**: consume the event immediately (`set! (.-doit event) false`), then
queue mutations via `async-exec!` so they run after all pending events in the
current batch complete:

```clojure
;; Inside a Display event filter or widget listener:
(when (= (.keyCode event) SWT/ESC)
  (set! (.-doit event) false)           ; consume immediately
  (async-exec!                          ; defer mutations
   (fn []
     (.setText (element :search) "")
     (.setFocus (element :search)))))
```

This is distinct from rule §2 (thread safety). Even code already on the UI
thread must defer mutations when an event "transaction" is in progress.
Traverse events are particularly notorious for needing this treatment.


## 20. `Browser.evaluate` requires an explicit `return`

SWT's `Browser.evaluate(String)` wraps your code in an anonymous JavaScript
function. Without an explicit `return`, the result is always `nil`:

```clojure
;; BAD — returns nil
(.evaluate browser "document.querySelectorAll('a').length")

;; GOOD — returns 42.0
(.evaluate browser "return document.querySelectorAll('a').length;")
```

This applies to all calls to `.evaluate`. `.execute` (fire-and-forget, no return
value) does not need `return`.


## 21. Test UI behavior with synthetic events via `Display.post`

`(.post display event)` injects a synthetic OS-level input event into the SWT
event queue, simulating real user input. This enables automated testing of
keyboard shortcuts, focus behavior, and event filters from the REPL:

```clojure
;; Post a synthetic Esc keypress
(ui (let [event (org.eclipse.swt.widgets.Event.)]
      (set! (.-type event) SWT/KeyDown)
      (set! (.-keyCode event) SWT/ESC)
      (.post @display event)))
```

The event goes through the full SWT pipeline — Display filters, widget
listeners, traverse handling — just like a physical keypress. Use this to
verify global hotkeys, focus management, and event consumption (`doit = false`)
without manual interaction.

`Display.post` supports any OS-level input event — not just key events.
Simulated mouse events are particularly useful for testing click targets,
drag behavior, and context menus:

```clojure
;; Post a synthetic mouse click at coordinates (100, 200)
(ui (let [event (org.eclipse.swt.widgets.Event.)]
      (set! (.-type event) SWT/MouseDown)
      (set! (.-button event) 1)
      (set! (.-x event) 100)
      (set! (.-y event) 200)
      (.post @display event)))
```


## 22. Prefer targeted function redefinition over full namespace reload

When iterating on a small change, redefine only the affected function(s) at the
REPL rather than reloading the entire namespace. This is faster, safer for
stateful namespaces, and avoids CDT alias errors (§13).

**Pattern**: switch to the target namespace with `in-ns`, then `defn` (or
`defn-` for privates) just the changed function:

```clojure
(in-ns 'llm-memory.ui.search)
(defn- page-css []
  ;; ... updated body only ...
  )
```

The var is replaced in the live JVM immediately. Any subsequent call to
`page-css` — including calls from other already-loaded functions — picks up
the new definition automatically, because Clojure vars are late-bound.

### When to use targeted redefinition

Use `in-ns` + `defn` when the change is confined to **one or a few pure or
nearly-pure functions** — functions that:

- Take inputs and return a value with no side effects (pure computations,
  string/HTML builders, CSS generators, data transformers)
- Have no `def`-level state of their own that needs re-initializing
- Are not referenced by `def` vars whose values were captured at load time
  (e.g. a `def` that calls the function at startup captures the old return
  value — redefining the function won't change the captured value)

### When to do a full namespace reload instead

Use `(require '[ns] :reload)` when:

- You added, removed, or renamed a var (the namespace's public surface changed)
- You changed a `def` whose value is computed at load time and must be
  re-evaluated
- You changed a multimethod dispatch function or a protocol implementation
- Multiple functions changed and the dependency order between them matters
- The namespace has no SWT state to protect and a reload is simply cleaner

### Never use `:reload-all` (see §13)

`:reload-all` transitively reloads CDT internals, causing alias conflicts.
Always reload specific namespaces one at a time, in dependency order
(dependencies before dependents).


## 23. Search for a theme/design document before writing visual code

**Before creating any `Color`, `Font`, icon, spacing value, or other visual
element, search the project for a theme or design guidelines document.** Look
for files named `THEME.md`, `DESIGN.md`, `theme.clj`, `colors.clj`, `palette.clj`,
or anything containing `theme`, `design`, `style`, `brand`, or `color`.

If a theme document exists, treat it as the authoritative source for every
colour, typeface, size, spacing, and icon decision. Never substitute your own
visual choices. If no theme document exists, fall back to SWT system colors
(`Display.getSystemColor`) and standard platform fonts.


## 24. Consult SWT Snippets for unfamiliar widgets

Eclipse maintains an official directory of small, focused SWT examples (Java)
called **Snippets**, indexed by widget type:

**https://eclipse.dev/eclipse/swt/snippets/**

When you need to know how to use an SWT widget or feature correctly, look for a
relevant snippet there first. Then translate the Java code into idiomatic CDT
Clojure — replacing direct interop with CDT init functions, `on` for event
handlers, `grid-layout`/`grid-data` helpers for layout, etc.


## 25. API style: widget constructor line hygiene

At most one item from this precedence order may appear on the same line as a
widget constructor — everything else goes on subsequent lines:

1. Style bits (e.g. `SWT/BORDER`, `(| SWT/PUSH SWT/BORDER)`)
2. `(id! :kw)`
3. A bare string label/title

```clojure
;; BAD — layout on the constructor line
(composite (grid-layout :numColumns 2) ...)

;; BAD — style bits AND id! on the constructor line
(text SWT/BORDER (id! :ui/name-field) ...)

;; GOOD
(text SWT/BORDER
  (id! :ui/name-field)
  (hgrab))

;; GOOD — bare string is the one allowed item
(button SWT/PUSH "Save"
  (on e/widget-selected [props parent event] ...))
```


## 26. Bare strings, not `:text`

CDT converts bare strings to `.setText` calls automatically. Use the bare string
form — it's more idiomatic:

```clojure
;; BAD
(label :text "Address:")
(button SWT/PUSH :text "Save")

;; GOOD
(label "Address:")
(button SWT/PUSH "Save")
```


## 27. Don't set defaults; don't pass `SWT/NONE`

Only set properties that differ from SWT defaults. If a property is set, it
should signal intent. `(composite)` and `(composite SWT/NONE)` are equivalent —
the former is preferred.


## 28. Use `|` for style bits, not `bit-or`

CDT provides `|` in `ui.SWT` specifically for composing SWT style constants,
mirroring Java's `|` operator:

```clojure
;; BAD
(shell (bit-or SWT/APPLICATION_MODAL SWT/DIALOG_TRIM))

;; GOOD
(shell (| SWT/APPLICATION_MODAL SWT/DIALOG_TRIM))
```


## 29. Prefer system colors; use a color registry for custom colors

SWT provides platform-appropriate colors via `Display.getSystemColor(SWT/COLOR_*)`
— these are pre-allocated, never need disposal, and adapt to light/dark mode.
Use them wherever the design permits.

When a theme specifies custom colors, maintain a **color registry** — a global
map from role keyword to `Color` — so each custom color is constructed once
and reused. Request colors by **semantic role** (`:brand-primary`, `:surface`,
`:error`), not raw RGB values. **Never construct ad-hoc `Color` objects inline**
in init functions — they almost certainly leak.

Dispose all registry colors on application exit in `(on e/shell-closed ...)`.


## 30. Font registry: request by role, resolve a fallback stack

Maintain a **font registry** — a global map from `[role size style]` to `Font`
— so fonts are constructed once and reused. Request fonts by role (`:sans`,
`:serif`, `:mono`) rather than by name, resolved through a typeface fallback
stack that is checked at startup against installed fonts.

Dispose all registry fonts on application exit.


## 31. Icon registry with `ImageDataProvider` for HiDPI

Maintain an **icon registry** — a global map from `[role size]` to `Image` —
so each programmatic icon is drawn once and reused. Always construct registry
images using an `ImageDataProvider` (draw at 1x and 2x, return `ImageData`,
dispose temporary images). `ImageDataProvider` is safe on all platforms:
non-HiDPI displays request zoom=100; HiDPI displays request zoom=200 and get
the crisply-drawn result. Returning `null` for unsupported zoom levels is
explicitly allowed by the Javadoc — SWT falls back to scaling the 100% image.

Dispose all registry images on application exit.

> Details: [SWT-REFERENCE.md §13](SWT-REFERENCE.md#13-hidpi--retina-support) — `ImageDataProvider` example.


## 32. Automatic type coercion: use plain vectors for arrays

CDT automatically converts Clojure vectors to Java array types that SWT
expects. Use plain vectors instead of constructing arrays by hand:

```clojure
;; BAD
:weights (into-array Integer/TYPE [25 75])

;; GOOD — CDT coerces the vector to int[]
:weights [25 75]
```

Extend via `righttypes.core/convert` multimethod for custom type pairs.

> Details: [SWT-REFERENCE.md §4](SWT-REFERENCE.md#4-cdt-sugar-functions-and-properties) — type coercion.


## 33. Use `with-property` for sub-object configuration

When you need to set multiple properties on a layout or other property value,
use `with-property` instead of `let`-binding and Java field mutation.
`with-property` assigns the named property to its parent widget *and* applies
keyword sub-inits to the value itself:

```clojure
;; BAD — let-binding and field mutation
(fn [props parent]
  (let [l (FillLayout.)]
    (set! (.marginHeight l) 10)
    (set! (.marginWidth l) 10)
    (.setLayout parent l)))

;; GOOD — with-property sets sub-properties and assigns in one step
(with-property :layout (FillLayout.)
  :margin-height 10
  :margin-width  10)
```


## 34. Application entry point (`-main`) for uberjar

CDT loads platform-specific SWT native libraries dynamically. The `-main`
function must set up the classloader **before** any UI namespace is touched:

1. Replace the thread's classloader with `DynamicClassLoader`
2. `(binding [*repl* true] ...)` to enable `add-libs`
3. `(require 'ui.SWT)` inside the binding — **not in the `ns` form**
4. Use `eval` to call UI functions (compiler doesn't know about runtime-required vars)

```clojure
(ns my.app (:gen-class))  ; do NOT require ui.SWT here

(defn -main [& args]
  (let [cl (.getContextClassLoader (Thread/currentThread))]
    (.setContextClassLoader (Thread/currentThread)
      (clojure.lang.DynamicClassLoader. cl)))
  (binding [*repl* true]
    (require 'ui.SWT)
    (eval `(my.ui/start ~@args))))
```

On macOS, launch with `java -XstartOnFirstThread -jar app.jar`. Call
`(System/exit 0)` after the event loop returns (see §17).

> Details: [SWT-REFERENCE.md §11](SWT-REFERENCE.md#11-uberjar-packaging-with-cdt--swt) — full `-main`, deps.edn, build.clj, JVM args.


## 35. System tray: minimize-to-tray pattern

> **macOS `.app` bundles**: see §39 — the JVM must not be spawned directly
> by `launchd`, or the tray icon renders as zero pixels on macOS 26.

System tray entries (via CDT `tray-item` or custom init functions) must be
direct children of `application`, at the same level as `shell` — not inside
a shell.

The canonical "minimize to tray" pattern intercepts `e/shell-closed` to hide
the window instead of closing it, using a `:closing` flag in props to
distinguish a real quit from a regular window close:

```clojure
(on e/shell-closed [props parent event]
  (when-not (:closing @props)
    (set! (. event doit) false)               ; veto close (synchronous)
    (async-exec! #(.setVisible parent false)))) ; hide instead
```

Set `:closing` to `true` in the tray menu's Quit handler before calling
`.close` on the shell.


## 36. `ui.widgets.console` — embedded ANSI terminal

CDT ships a console widget (`ui.widgets.console`) that turns a `Browser` into
a read-only ANSI terminal with a black background:

```clojure
(require '[ui.widgets.console :as console])

;; In the UI tree
(browser SWT/WEBKIT (id! :ui/log-browser))

;; Initialize, then append from any thread
(async-exec! #(console/init (:ui/log-browser @props)))
(async-exec! #(console/append (:ui/log-browser @props) "Hello \033[1;32mworld\033[0m\n"))
(async-exec! #(console/clear  (:ui/log-browser @props) ""))
```

Note: loads `ansi_up` from a CDN at runtime — requires network on first use.


## 37. Browser widget: ambiguous `on` dispatch — use explicit adapters

CDT's `on` macro resolves event keywords to listener interfaces via
reflection. For most widgets, each event name maps to exactly one listener.
**`Browser` is an exception** — four different listener interfaces all have a
`changed` method:

| Listener                | Event class        | Has `.location`? |
|-------------------------|--------------------|------------------|
| `LocationListener`      | `LocationEvent`    | **Yes**          |
| `StatusTextListener`    | `StatusTextEvent`  | No               |
| `ProgressListener`      | `ProgressEvent`    | No               |
| `TitleListener`         | `TitleEvent`       | No               |

`(on e/changed ...)` picks one non-deterministically (hash-map key ordering).
If it binds to `StatusTextListener` or `ProgressListener`, any access to
`.location` throws `IllegalArgumentException` at runtime. This is
**intermittent** — it depends on whether WebKit fires the wrong event type
during the narrow startup window.

**`changing` is safe** — only `LocationListener` has it, so CDT resolves it
unambiguously.

### Fix: use explicit adapters for `changed` on Browser

```clojure
(:import [org.eclipse.swt.browser LocationAdapter])

;; Instead of (on e/changed [props parent event] (.location event) ...)
;; use an explicit LocationAdapter:
(fn [_props ^Browser parent]
  (.addLocationListener
   parent
   (proxy [LocationAdapter] []
     (changed [^LocationEvent event]
       (let [loc (.location event)]
         ;; ... handle location change
         )))))
```

Use `(meta/widget-to-listener-methods Browser)` in the REPL to inspect which
listeners a widget supports and spot ambiguities before they bite at runtime.

> Incident: `ce9b81a` — intermittent server crash (~60% failure rate) caused
> by `(on e/changed ...)` binding to `StatusTextListener` on the home-page
> Browser. The crash only manifested when the MCP proxy auto-started the
> server, because WebKit's initial render timing varied.


## 38. CDT ships SWT natives as zipped distributions

`io.github.coconutpalm/clojure-desktop-toolkit` bundles SWT 4.38 for **all six**
(os, arch) combinations as ZIP files inside its own JAR:

- `swt-4.38-cocoa-macosx-aarch64.zip`
- `swt-4.38-cocoa-macosx-x86_64.zip`
- `swt-4.38-gtk-linux-aarch64.zip`
- `swt-4.38-gtk-linux-x86_64.zip`
- `swt-4.38-win32-win32-aarch64.zip`
- `swt-4.38-win32-win32-x86_64.zip`

At runtime, `ui.internal.SWT_deps` picks the correct ZIP for the current
`os.name`/`os.arch`, extracts it to a temp dir, and adds the contained JARs +
natives to the classpath via `clojure.repl.deps/add-lib`. This requires
**Clojure 1.12+**.

**Verify**: `unzip -l <uberjar> | grep swt-4.38` — all six zips should be at
the uberjar root.

### Implications for packaging

- **A single cross-platform uberjar is sufficient.** No per-platform SWT JAR
  build is needed, no `:classifier` dance in `deps.edn`. Only the JRE and
  Babashka binary differ per target.
- **Do NOT declare a platform-specific SWT JAR in `deps.edn`**
  (e.g. `org.eclipse.swt/org.eclipse.swt.cocoa.macosx.aarch64`). CDT handles
  SWT dynamically; declaring it directly breaks the CDT loader.
- **`java.desktop` must be in the jlink module set** — SWT/AWT dependencies
  pull it in.

### Supported target matrix

| Platform              | Supported? | Notes                                                     |
|-----------------------|------------|-----------------------------------------------------------|
| macOS arm64           | ✅          | Primary dev platform                                      |
| macOS x86_64          | ⚠️          | SWT zip present; dtlvnative has no `macosx-x86_64` native |
| Linux aarch64 / GTK   | 🟡         | Best-effort — GTK version incompatibilities can break UI  |
| Linux x86_64 / GTK    | 🟡         | Best-effort — as above                                    |
| Windows x86_64        | ✅          | Supported                                                 |
| Windows aarch64       | ❌          | SWT zip present, but ONNX/dtlvnative lack Windows arm64   |

The Linux/GTK caveat is a CDT-level known issue: SWT 4.38's bundled GTK can
conflict with various distro GTK versions. The headless MCP path (search,
indexing, nREPL) works reliably on Linux; the SWT UI may fail at runtime on
some distros.

> See `Plans/todo/PLATFORM-PACKAGING-CONTEXT.md` § "Cross-platform uberjar"
> for the complete native-library table (dtlvnative, ONNX, JNA, zstd-jni, SWT).


## 39. macOS `.app` launcher: do NOT `exec` the JVM

On macOS 26, `SystemUIServer` refuses to render `NSStatusItem` custom views
when the JVM is the direct `launchd`-tracked app process. The tray item is
registered in the menu bar (Accessibility sees it at size 24×24,
`isVisible=1`) but the compositor renders zero pixels.

**Rule**: the launcher script in `Contents/MacOS/` must spawn the JVM as a
**child process**, not `exec` it. Interposing the shell between `launchd`
and `java` fixes rendering.

```sh
#!/bin/sh
# Contents/MacOS/<app>-launcher

# ... bundle-path setup, first-run install, etc. ...

# Spawn the JVM as a child of this shell (NOT via exec).
"$APP_RESOURCES/jre/bin/java" \
  -XstartOnFirstThread \
  -Xdock:name=MyApp \
  "-Xdock:icon=$APP_RESOURCES/myapp.icns" \
  -jar "$APP_RESOURCES/lib/myapp.jar" &

JAVA_PID=$!
# Forward Cmd-Q / Force Quit (launchd → sh → JVM) for clean shutdown.
trap 'kill -TERM "$JAVA_PID" 2>/dev/null; wait "$JAVA_PID"' TERM INT HUP
wait "$JAVA_PID"
```

The signal-forwarding trap is **not optional**: `launchd` sends SIGTERM to
the tracked process (now `sh`, not the JVM) on Cmd-Q, Force Quit, logout,
or shutdown. Without forwarding, the JVM orphans and shutdown hooks don't
run — risking corruption in Datalevin, LMDB, SQLite, or any store with
open write transactions.

### Diagnostic shortcut

If a macOS SWT app launched via `open /Applications/MyApp.app` has a
broken tray icon BUT it works when you run
`/Applications/MyApp.app/Contents/MacOS/<launcher>` directly from a shell,
the cause is this lineage bug — fix the launcher, not the SWT code.

See `Plans/complete/swt-tray-debug/` for the full debugging record.
