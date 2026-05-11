---
created: 2026-03-27
related: [STYLEDTEXT-EDITOR-CONTEXT.md, MD-THEME-CONTEXT.md, MD-THEME-PLAN.md]
tags: [swt, ui, editor, markdown, styledtext]
---

# StyledText Markdown Editor — Plan

**Prerequisite**: MD-THEME steps 1–5 (fonts, colors, md-theme namespace,
highlight tokenizers) must be complete before starting Step 3 here.

## Step 1 — Shared ScheduledExecutorService in `resources.clj`

Add a single-thread executor for background tasks (auto-save, etc.):

```clojure
(defonce executor
  (java.util.concurrent.Executors/newSingleThreadScheduledExecutor
    (reify java.util.concurrent.ThreadFactory
      (newThread [_ r]
        (doto (Thread. r "winze-background")
          (.setDaemon true))))))
```

Daemon thread ensures it doesn't block JVM shutdown.

Add a platform-aware accelerator label helper:

```clojure
(def macos? (= "cocoa" (SWT/getPlatform)))

(defn accel-label
  "Return a label like 'Edit (⌘E)' on macOS or 'Edit (Ctrl+E)' elsewhere."
  [action key]
  (str action " (" (if macos? "⌘" "Ctrl+") key ")"))
```

All keyboard accelerators use `SWT/MOD1` (Cmd on macOS, Ctrl on
Windows/Linux) rather than `SWT/COMMAND` or `SWT/CTRL` directly.

## Step 2 — `llm-memory.ui.markdown-editor` namespace scaffold

Create `src/llm_memory/ui/markdown_editor.clj`:

```clojure
(ns llm-memory.ui.markdown-editor
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [llm-memory.ui.md-theme :as md-theme]
   [llm-memory.ui.resources :as res]
   [ui.SWT :refer [async-exec! id! on styled-text |]]
   [ui.events :as e]
   [hyperfiddle.rcf :refer [tests]])
  (:import
   [java.util.concurrent ScheduledFuture TimeUnit]
   [org.eclipse.swt SWT]))
```

## Step 3 — Debounced auto-save

### 3a — `schedule-save!`

```clojure
(def save-delay-ms 1500)

(defn- schedule-save!
  "Schedule a debounced save. Cancels any pending save for this editor.
  Returns the new ScheduledFuture."
  [^ScheduledFuture prev-future abs-path content on-saved]
  (when prev-future (.cancel prev-future false))
  (.schedule res/executor
    ^Runnable (fn []
               (try
                 (spit abs-path content)
                 (when on-saved (async-exec! on-saved))
                 (catch Throwable t
                   (log/error t "Auto-save failed" abs-path))))
    save-delay-ms
    TimeUnit/MILLISECONDS))
```

### 3b — `flush-save!`

```clojure
(defn- flush-save!
  "If there is a pending save, cancel the timer and save immediately."
  [^ScheduledFuture pending-future abs-path content]
  (when (and pending-future (not (.isDone pending-future)))
    (.cancel pending-future false)
    (try
      (spit abs-path content)
      (catch Throwable t
        (log/error t "Flush-save failed" abs-path)))))
```

RCF tests for `schedule-save!` / `flush-save!` — these are pure enough to
test if we extract the timing logic:

```clojure
(tests
  ;; After flush, file should contain the content
  (let [tmp (java.io.File/createTempFile "test-save" ".md")
        path (.getAbsolutePath tmp)]
    (spit path "original")
    (flush-save! nil path "updated")
    (slurp path) := "updated"
    (.delete tmp))
  :rcf)
```

## Step 4 — Undo / redo

### 4a — Snapshot helpers (pure, RCF-testable)

```clojure
(defn- capture-snapshot
  "Capture editor state as a snapshot map."
  [styled-text]
  {:text      (.getText styled-text)
   :top-pixel (.getTopPixel styled-text)
   :caret     (.getCaretOffset styled-text)})

(defn- restore-snapshot!
  "Replace editor content and restore scroll/cursor position.
  Must be called on the UI thread."
  [styled-text {:keys [text top-pixel caret]}]
  (.setText styled-text text)
  (.setTopPixel styled-text top-pixel)
  (.setCaretOffset styled-text caret))
```

### 4b — Undo/redo state

Per-editor atom, created in the `markdown-editor` factory:

```clojure
(let [history (atom {:undo [] :redo []})]
  ...)
```

### 4c — `push-undo!`

Called on load and on each save:

```clojure
(defn- push-undo!
  "Push a snapshot onto the undo stack. Clears the redo stack."
  [history snapshot]
  (swap! history (fn [h]
                   (-> h
                       (update :undo conj snapshot)
                       (assoc :redo [])))))
```

### 4d — `undo!` / `redo!`

```clojure
(defn- undo!
  "Undo: capture current state → redo stack, pop undo stack → restore."
  [styled-text history]
  (let [{:keys [undo]} @history]
    (when (seq undo)
      (let [current  (capture-snapshot styled-text)
            previous (peek undo)]
        (swap! history (fn [h]
                         (-> h
                             (update :undo pop)
                             (update :redo conj current))))
        (restore-snapshot! styled-text previous)))))

(defn- redo!
  "Redo: capture current state → undo stack, pop redo stack → restore."
  [styled-text history]
  (let [{:keys [redo]} @history]
    (when (seq redo)
      (let [current (capture-snapshot styled-text)
            next'   (peek redo)]
        (swap! history (fn [h]
                         (-> h
                             (update :undo conj current)
                             (update :redo pop))))
        (restore-snapshot! styled-text next')))))
```

### 4e — RCF tests for stack logic

The stack manipulation is pure and testable without SWT:

```clojure
(tests
  ;; push-undo! adds to undo, clears redo
  (let [h (atom {:undo [] :redo [{:text "r"}]})]
    (push-undo! h {:text "a"})
    (:undo @h) := [{:text "a"}]
    (:redo @h) := [])

  ;; undo pops undo, pushes current to redo
  (let [h (atom {:undo [{:text "first"}] :redo []})]
    ;; simulate undo stack manipulation (without SWT widget)
    (let [current {:text "second"}
          prev    (peek (:undo @h))]
      (swap! h (fn [h] (-> h (update :undo pop) (update :redo conj current))))
      (:undo @h) := []
      (:redo @h) := [{:text "second"}]
      prev := {:text "first"}))

  ;; undo on empty stack is no-op
  (let [h (atom {:undo [] :redo []})]
    (seq (:undo @h)) := nil)

  :rcf)
```

### 4f — Key bindings

Wire Cmd+Z / Cmd+Shift+Z via a `KeyListener` on the `StyledText`:

```clojure
(on e/key-down [props parent event]
    (let [mod  (.stateMask event)
          cmd? (not= 0 (bit-and mod SWT/MOD1))]
      (when cmd?
        (condp = (.keyCode event)
          (int \z) (if (not= 0 (bit-and mod SWT/SHIFT))
                     (redo! parent history)
                     (undo! parent history))
          nil))))
```

After both `undo!` and `redo!`, restyle the document with
`(md-theme/apply-theme! parent (.getText parent))`.

## Step 5 — `markdown-editor` CDT init function

The core factory. Creates a `StyledText` widget, attaches the restyle listener,
debounce save, and undo/redo:

```clojure
(defn markdown-editor
  "CDT init function: StyledText configured for markdown editing.
  `abs-path` — file to edit (loaded immediately).
  `on-dirty` / `on-saved` — callbacks for tab title updates (called on UI thread)."
  [abs-path & {:keys [on-dirty on-saved]}]
  (let [save-future (atom nil)
        history     (atom {:undo [] :redo []})
        dirty?      (atom false)
        content     (slurp abs-path)
        initial     {:text content :top-pixel 0 :caret 0}]
    ;; Seed the undo stack with the loaded state
    (push-undo! history initial)
    (styled-text (| SWT/MULTI SWT/V_SCROLL SWT/WRAP)
      :font       @res/body-font
      :background @res/color-mine-shaft
      :foreground @res/color-crystal-white
      :word-wrap  true
      :text       content

      (on e/modify-text [props parent event]
          (let [text (.getText parent)]
            (md-theme/apply-theme! parent text)
            ;; Any user edit clears the redo stack and marks dirty
            (swap! history assoc :redo [])
            (reset! dirty? true)
            (when on-dirty (on-dirty))
            (reset! save-future
                    (schedule-save! @save-future abs-path text
                      (fn []
                        ;; On save: mark clean, snapshot to undo stack
                        (reset! dirty? false)
                        (push-undo! history (capture-snapshot parent))
                        (when on-saved (on-saved)))))))

      (on e/key-down [props parent event]
          (let [mod  (.stateMask event)
                cmd? (not= 0 (bit-and mod SWT/MOD1))]
            (when cmd?
              (condp = (.keyCode event)
                (int \z) (do (if (not= 0 (bit-and mod SWT/SHIFT))
                               (redo! parent history)
                               (undo! parent history))
                             (md-theme/apply-theme! parent (.getText parent)))
                nil))))

      (on e/widget-disposed [props parent event]
          (flush-save! @save-future abs-path (.getText parent))))))
```

Verify in REPL: use `show` (SWT-UI-GUIDE §3) to open a standalone shell
containing `markdown-editor` pointed at a test `.md` file. Type a heading,
confirm styling appears and file saves after 1.5s. After save, Cmd+Z should
restore the previous saved state.

## Step 6 — Edit icon asset

Create the edit/pencil icon for the toolbar toggle button:

1. Design a 16×16 SVG: pencil silhouette in Amethyst `#9B8FE0` on transparent.
   Save to `resources/branding/ui/svg/winze-edit-16.svg`.
2. Create a 32×32 @2x variant: `winze-edit-32.svg`.
3. Rasterize via `resvg` (per BRAND-GUIDE.md):
   ```bash
   resvg winze-edit-16.svg png/winze-edit-16.png -w 16 -h 16
   resvg winze-edit-32.svg png/winze-edit-32.png -w 32 -h 32
   ```
4. Add `defonce` in `resources.clj`:
   ```clojure
   (defonce edit-icon
     (delay (hidpi-image "branding/ui/png/winze-edit-16.png"
                         "branding/ui/png/winze-edit-32.png")))
   ```

## Step 7 — Refactor `open-tab!` to use wrapper Composite

For file tabs (those with an `abs-path`), change `open-tab!` so the
`CTabItem`'s control is a wrapper `Composite` containing the `Browser`,
rather than the `Browser` directly:

```clojure
;; In open-tab!, when abs-path is provided:
(composite (id! wrapper-id) :layout (FillLayout.)
  (custom-browser (id! tab-id) :text html ...))
(ctab-item SWT/CLOSE title (control wrapper-id))
```

Update `open-files` entries to track the wrapper, mode, and per-tab state:

```clojure
{:tab-ids     #{tab-id}
 :rel-path    rel-path
 :abs-path    abs-path
 :wrapper-id  wrapper-id      ; Composite holding the active child
 :mode        :view           ; or :edit
 :dirty?      (atom false)    ; true between modify-text and save
 :history     (atom {:undo [] :redo []})
 :save-future (atom nil)}
```

Tabs without `abs-path` (live search, standalone search results) continue
to use `Browser` directly — no wrapper.

Update `refresh-open-tabs!`, `close-open-tabs!`, and `rename-open-tabs!` to
work with the wrapper pattern. The child widget is now
`(first (.getChildren wrapper))` rather than the `app-props` entry directly.

## Step 8 — Watcher save-suppression

In `refresh-open-tabs!`, add guards for edit mode. When the active child
is a `StyledText`, skip the refresh if:

1. The file content matches the editor text (our own save triggered the event).
2. The editor is dirty (user has typed since the last save — they are ahead
   of the save/refresh cycle and we must not clobber their work).

`StyledText` has no built-in dirty flag. Each editor has a `dirty?` atom
(scoped to the editor instance) — set `true` on `modify-text`, reset `false`
on save.

```clojure
(let [new-content (slurp abs-path)
      child       (first (.getChildren wrapper))]
  (cond
    ;; Edit mode: skip if content matches (our own save) or editor is dirty
    (instance? StyledText child)
    (when-not (or (= new-content (.getText child))
                  @(:dirty? entry))
      ;; External edit while editor is clean — safe to reload
      (.setText child new-content)
      (md-theme/apply-theme! child new-content))

    ;; View mode: re-render HTML
    (instance? Browser child)
    (refresh-browser-with-scroll! child (search/file-page new-content rel-path))))
```

## Step 9 — `toggle-mode!`

The core toggle function in `markdown-editor` namespace:

```clojure
(defn toggle-mode!
  "Switch the active tab between view and edit mode.
  Must be called on the UI thread."
  [abs-path]
  (when-let [entry (get @open-files abs-path)]
    (let [{:keys [wrapper-id mode history save-future rel-path]} entry
          wrapper (get @app-props wrapper-id)
          child   (first (.getChildren wrapper))]
      ;; Flush any pending save before switching
      (when (= mode :edit)
        (flush-save! @save-future abs-path (.getText child))
        (push-undo! history (capture-snapshot child)))
      ;; Dispose current child
      (.dispose child)
      ;; Create new child
      (if (= mode :view)
        ;; view → edit
        (let [content (slurp abs-path)
              st      (create-styled-text! wrapper abs-path entry)]
          (swap! open-files assoc-in [abs-path :mode] :edit))
        ;; edit → view
        (let [content  (slurp abs-path)
              html     (search/file-page content rel-path)
              browser  (create-browser! wrapper html)]
          (swap! open-files assoc-in [abs-path :mode] :view)))
      (.layout wrapper))))
```

`create-styled-text!` and `create-browser!` are helpers that instantiate the
respective widget inside the wrapper and wire up listeners. These are non-CDT
(direct SWT constructor calls) since we're creating widgets at runtime into an
existing Composite, not during the init tree.

## Step 10 — Toolbar setup

In `main-window`, after the `CTabFolder` is created:

```clojure
;; Create toolbar in top-right of tab folder
(let [folder  (element :main-folder)
      toolbar (ToolBar. folder SWT/FLAT)
      btn     (ToolItem. toolbar SWT/PUSH)]
  (.setImage btn @res/edit-icon)
  (.setToolTipText btn (res/accel-label "Edit" "E"))
  (.setEnabled btn false)
  (.setTopRight folder toolbar)
  (swap! app-props assoc :ui/edit-button btn)

  ;; Update button state on tab selection change
  (.addSelectionListener folder
    (proxy [SelectionAdapter] []
      (widgetSelected [e]
        (let [sel   (.getSelection folder)
              ctrl  (when sel (.getControl sel))
              entry (file-entry-for-wrapper ctrl)]
          (.setEnabled btn (boolean entry))
          (when entry
            (.setToolTipText btn
              (if (= :edit (:mode entry))
                (res/accel-label "View" "E")
                (res/accel-label "Edit" "E"))))))))

  ;; Toggle on click
  (.addSelectionListener btn
    (proxy [SelectionAdapter] []
      (widgetSelected [_e]
        (when-let [entry (active-file-entry)]
          (toggle-mode! (:abs-path entry)))))))
```

`file-entry-for-wrapper` looks up the `open-files` entry whose `:wrapper-id`
matches the given control. `active-file-entry` returns the entry for the
currently selected tab, or nil.

## Step 11 — Global Cmd+E accelerator

Extend the existing display-level key filter in `main-window`'s `defmain`:

```clojure
;; Inside the existing Display KeyDown filter:
(when (and cmd? (= (.keyCode event) (int \e)))
  (set! (.-doit event) false)
  (async-exec!
    (fn []
      (when-let [entry (active-file-entry)]
        (toggle-mode! (:abs-path entry))))))
```

This reuses the same `toggle-mode!` as the toolbar button.

## Verification checkpoints

| Step | How |
|------|-----|
| 1 — executor | REPL: `(.submit res/executor #(println "ok"))` |
| 2 — ns scaffold | Namespace loads without error |
| 3 — auto-save | RCF test for flush-save; REPL: open editor, type, wait 2s, `(slurp path)` reflects changes |
| 4 — undo/redo | RCF tests for stack logic; REPL: type, wait for save, Cmd+Z restores, Cmd+Shift+Z re-applies |
| 5 — init function | REPL via `show`: typing `# Hello` restyles to H1; code block gets highlighted |
| 6 — edit icon | Icon renders at correct size; screenshot the toolbar area |
| 7 — wrapper refactor | Existing file tabs still work; `open-tab!` creates wrapper Composites for file tabs |
| 8 — watcher suppression | Edit in editor → no cursor jump; external edit → content refreshes in both modes |
| 9 — toggle | REPL: `(toggle-mode! path)` swaps Browser↔StyledText; screenshot both states |
| 10 — toolbar | Button appears top-right; disabled on live search tab; enabled on file tab; click toggles |
| 11 — Cmd+E | Screenshot: view tab → Cmd+E → edit mode; Cmd+E again → view mode |
