# Command Palette — Plan

**Prerequisites**:
- Read `winze/Plans/SWT-UI-GUIDE.md` before implementation
- See [COMMAND-PALETTE-CONTEXT.md](COMMAND-PALETTE-CONTEXT.md) for architecture
  and design decisions
- Related: [EDITOR-COMMANDS-CONTEXT.md](EDITOR-COMMANDS-CONTEXT.md),
  [EDN-TOKENIZERS-CONTEXT.md](EDN-TOKENIZERS-CONTEXT.md)

---

## Phase 1 — Command Registry + Scope System

### Step 1 — Command registry (`commands.clj`)

Create `winze-server/src/llm_memory/ui/commands.clj`.

```clojure
(ns llm-memory.ui.commands
  (:require [hyperfiddle.rcf :refer [tests]]))

(defonce registry (atom (sorted-map)))

(defn register!
  "Register a command. `cmd` is a map with :id, :label, :category, :action."
  [cmd]
  {:pre [(:id cmd) (:label cmd) (:action cmd)]}
  (swap! registry assoc (:id cmd) cmd))

(defn unregister!
  "Remove a command by ID. Used for per-editor-instance commands on dispose."
  [id]
  (swap! registry dissoc id))

(defn execute!
  "Execute the command with the given ID. Returns nil if not found."
  [id]
  (when-let [{:keys [action]} (get @registry id)]
    (action)))

(defn list-commands
  "Return all registered commands, optionally filtered by category."
  ([] (vals @registry))
  ([category] (filter #(= category (:category %)) (vals @registry))))
```

RCF tests: register, unregister, execute, list, list by category.

**Verify**: REPL — register a test command, execute by ID, list by category.

---

### Step 2 — Focus scope and context system (`keybindings.clj`)

Create `winze-server/src/llm_memory/ui/keybindings.clj`.

**2a — Scope query via named data**:

```clojure
(defn active-scope
  "Return the keybinding scope keyword for the currently focused widget,
  or :global if no scope is set."
  []
  (let [focus (.getFocusControl (Display/getCurrent))]
    (if focus
      (let [scope (.getData focus "scope")]
        (if (keyword? scope) scope :global))
      :global)))
```

Verify in the REPL that `.getData("scope")` returns the keyword after
`.setData("scope", :editor)` — no CDT conflict.

**2b — Active popup tracking**:

```clojure
(defonce active-popup (atom nil))

(defn set-active-popup! [type] (reset! active-popup type))
(defn clear-active-popup! [] (reset! active-popup nil))
```

**2c — Context building**:

```clojure
(defn build-context
  "Build the current keybinding context map for predicate evaluation."
  []
  {:in           (active-scope)
   :active-popup @active-popup
   :lang         (current-editor-lang)
   :mode         (current-tab-mode)})
```

**2d — Predicate evaluation with specificity**:

```clojure
(defn eval-when
  "Return true if the :when predicate map matches the given context.
  Each key in when-map must match the corresponding key in context.
  Values can be keywords (exact match) or sets (membership check)."
  [when-map context]
  (every? (fn [[k v]]
            (let [actual (get context k)]
              (if (set? v)
                (contains? v actual)
                (= v actual))))
          when-map))

(defn specificity
  "Number of predicate keys in a :when map. Higher = more specific."
  [when-map]
  (count when-map))
```

**2e — Platform detection**:

```clojure
(def mac? (str/includes? (System/getProperty "os.name") "Mac"))
```

**2f — RCF tests**:

```clojure
(tests
 (eval-when {} {:in :editor}) := true
 (eval-when {:in :editor} {:in :editor}) := true
 (eval-when {:in :editor} {:in :viewer}) := false
 (eval-when {:in #{:editor :viewer}} {:in :editor}) := true
 (eval-when {:in :editor :active-popup :content-assist}
            {:in :editor :active-popup :content-assist}) := true
 (eval-when {:in :editor :active-popup :content-assist}
            {:in :editor :active-popup nil}) := false
 (eval-when {:in :editor :mode :edit} {:in :editor :mode :edit}) := true
 (specificity {}) := 0
 (specificity {:in :editor}) := 1
 (specificity {:in :editor :active-popup :content-assist}) := 2
 :rcf)
```

**Verify**: REPL — test scope query on existing widgets, test predicate eval
with multi-level contexts.

---

### Step 3 — Keybinding loader and index

**3a — Modifier mapping (MOD1, not Cmd)**:

```clojure
(defn- event->mod-set
  "Extract the modifier set from an SWT key event using platform-correct MOD1."
  [event]
  (let [mask (.stateMask event)]
    (cond-> #{}
      (not= 0 (bit-and mask SWT/MOD1))  (conj :mod1)
      (not= 0 (bit-and mask SWT/SHIFT)) (conj :shift)
      (not= 0 (bit-and mask SWT/ALT))   (conj :alt))))
```

**3b — Special key mapping**:

```clojure
(def ^:private special-keys
  {:esc   SWT/ESC    :tab   SWT/TAB     :enter SWT/CR
   :space (int \space) :backspace SWT/BS :delete SWT/DEL
   :insert SWT/INSERT
   :f1 SWT/F1 :f2 SWT/F2 :f3 SWT/F3 :f4 SWT/F4
   :f5 SWT/F5 :f6 SWT/F6 :f7 SWT/F7 :f8 SWT/F8
   :f9 SWT/F9 :f10 SWT/F10 :f11 SWT/F11 :f12 SWT/F12
   :up SWT/ARROW_UP :down SWT/ARROW_DOWN
   :left SWT/ARROW_LEFT :right SWT/ARROW_RIGHT
   :home SWT/HOME :end SWT/END
   :page-up SWT/PAGE_UP :page-down SWT/PAGE_DOWN})

(defn- resolve-key [k]
  (cond
    (char? k)    (int k)
    (keyword? k) (get special-keys k)
    (int? k)     k))
```

**3c — Index builder (with prefix support)**:

```clojure
(defn- build-index
  "Build keybinding index from a flat list of binding maps.
  Returns {:normal  {[mod-set keyCode] → [binding ...]}
           :prefix  {[mod-set keyCode] → [binding ...]}
           :prefixed {[prefix-mod prefix-key mod-set keyCode] → [binding ...]}}."
  [bindings]
  (reduce
    (fn [idx {:keys [key mod prefix command when]
              :or {mod #{} when {}}}]
      (let [kc (resolve-key key)]
        (if prefix
          ;; Prefixed binding (two-key sequence)
          (let [pkc (resolve-key (:key prefix))
                pmod (or (:mod prefix) #{})]
            (-> idx
                ;; Register the prefix itself
                (update-in [:prefix [pmod pkc]] (fnil conj [])
                           {:type :prefix-trigger})
                ;; Register the full sequence
                (update-in [:prefixed [pmod pkc mod kc]] (fnil conj [])
                           {:command command :when when})))
          ;; Normal binding
          (update-in idx [:normal [mod kc]] (fnil conj [])
                     {:command command :when when}))))
    {:normal {} :prefix {} :prefixed {}}
    bindings))
```

**3d — Loading `.keybinding` files**:

```clojure
(defonce ^:private bindings-index (atom {:normal {} :prefix {} :prefixed {}}))

(defn load-keybindings!
  "Load all .keybinding files from the given directories.
  Later files override earlier ones for the same key+scope."
  [& dirs]
  (let [all-bindings (atom [])]
    (doseq [dir  dirs
            :let [d (io/file dir)]
            :when (and d (.isDirectory d))
            file  (sort (.listFiles d))
            :when (str/ends-with? (.getName file) ".keybinding")]
      (try
        (let [bindings (edn/read-string (slurp file))]
          (when (vector? bindings)
            (swap! all-bindings into bindings)
            (log/info "Loaded keybindings from" (.getName file))))
        (catch Exception e
          (log/error e "Failed to load keybindings" (.getName file)))))
    (reset! bindings-index (build-index @all-bindings))))
```

**Verify**: REPL — load keybinding files, query the index, confirm correct
command ID returned for normal and prefixed bindings.

---

### Step 4 — Scoped key dispatch with prefix support

**4a — Prefix state machine**:

```clojure
(defonce ^:private prefix-state
  (atom {:prefix nil :timer nil}))

(defn- enter-prefix-mode! [prefix-mod prefix-kc]
  (when-let [old (:timer @prefix-state)]
    (.cancel old false))
  (let [timer (.schedule res/executor
                (fn [] (reset! prefix-state {:prefix nil :timer nil}))
                1500 TimeUnit/MILLISECONDS)]
    (reset! prefix-state {:prefix [prefix-mod prefix-kc] :timer timer})))

(defn- exit-prefix-mode! []
  (when-let [timer (:timer @prefix-state)]
    (.cancel timer false))
  (reset! prefix-state {:prefix nil :timer nil}))
```

**4b — Dispatch function**:

```clojure
(defn dispatch-key!
  "Look up a key event in the keybinding index. Returns true if handled."
  [event]
  (let [mod-set (event->mod-set event)
        kc      (.keyCode event)
        ctx     (build-context)
        idx     @bindings-index
        pstate  @prefix-state]

    (if-let [[pmod pkc] (:prefix pstate)]
      ;; In prefix mode — look for prefixed binding
      (let [candidates (get-in idx [:prefixed [pmod pkc mod-set kc]])]
        (exit-prefix-mode!)
        (if-let [match (->> candidates
                            (filter #(eval-when (:when %) ctx))
                            (sort-by #(specificity (:when %)))
                            last)]
          (do (commands/execute! (:command match)) true)
          ;; No prefixed match — fall through to normal dispatch
          (dispatch-normal! idx mod-set kc ctx)))

      ;; Not in prefix mode
      (if (seq (get-in idx [:prefix [mod-set kc]]))
        ;; This key is a prefix trigger
        (do (enter-prefix-mode! mod-set kc) true)
        ;; Normal dispatch
        (dispatch-normal! idx mod-set kc ctx)))))

(defn- dispatch-normal! [idx mod-set kc ctx]
  (when-let [candidates (get-in idx [:normal [mod-set kc]])]
    (when-let [match (->> candidates
                          (filter #(eval-when (:when %) ctx))
                          (sort-by #(specificity (:when %)))
                          last)]
      (commands/execute! (:command match))
      true)))
```

**4c — Wire into Display filter** (`main_window.clj`):

```clojure
;; Replace existing filter:
(.addFilter @display SWT/KeyDown
  (reify Listener
    (handleEvent [_ event]
      (when (keybindings/dispatch-key! event)
        (set! (.-doit event) false)))))
```

**4d — Remove inline key listeners from editor** (lines 457-470 in
`toggle-mode!`).

**Verify**: REPL — press Esc in different scopes with different active popups,
confirm correct specificity-based dispatch. Test a prefix key sequence.

---

### Step 5 — Annotate existing widgets with scope data

| Widget | Location | Scope |
|--------|----------|-------|
| Search `Text` | `main_window.clj` body section | `:search-box` |
| Live search `Browser` | `main_window.clj` body section | `:viewer` |
| File viewer `Browser` | `main_window.clj` `custom-browser` | `:viewer` |
| Editor `StyledText` | `main_window.clj` `toggle-mode!` | `:editor` |

```clojure
(.setData widget "scope" :editor)   ;; etc.
```

**Verify**: REPL — click in different areas, call `(active-scope)`, confirm
correct scope returned.

---

### Step 6 — Register workbench commands

```clojure
(register! {:id :workbench/escape
            :label "Escape"
            :category :workbench
            :action (fn []
                      (let [ctx (build-context)]
                        ;; Escape command just delegates — the keybinding
                        ;; system already selects the right binding by
                        ;; specificity. This command exists for the base case.
                        (cond
                          (:active-popup ctx)
                          ;; Should not reach here — popup-specific Esc
                          ;; bindings are more specific and fire first
                          nil

                          (= :editor (:in ctx))
                          (toggle-mode!)

                          (= :viewer (:in ctx))
                          (go-to-search!)

                          (= :search-box (:in ctx))
                          (clear-search!))))})

(register! {:id :dismiss-content-assist
            :label "Dismiss Content Assist"
            :category :workbench
            :action dismiss-content-assist!})  ;; placeholder initially

(register! {:id :dismiss-link-preview
            :label "Dismiss Link Preview"
            :category :workbench
            :action dismiss-link-preview!})

(register! {:id :dismiss-find-bar
            :label "Dismiss Find Bar"
            :category :workbench
            :action dismiss-find-bar!})

(register! {:id :dismiss-command-palette
            :label "Dismiss Command Palette"
            :category :workbench
            :action dismiss-command-palette!})

(register! {:id :editor->viewer
            :label "Switch to View Mode"
            :category :workbench
            :action (fn [] (when-let [e (active-file-entry)]
                             (toggle-mode! (:abs-path e))))})

(register! {:id :viewer->search
            :label "Go to Search"
            :category :workbench
            :action go-to-search!})

(register! {:id :clear-search
            :label "Clear Search"
            :category :workbench
            :action clear-search!})

(register! {:id :workbench/toggle-mode
            :label "Toggle View/Edit Mode"
            :category :workbench
            :action (fn [] (when-let [e (active-file-entry)]
                             (toggle-mode! (:abs-path e))))})

(register! {:id :workbench/command-palette
            :label "Command Palette"
            :category :workbench
            :action open-command-palette!})
```

**Verify**: REPL — execute `:workbench/escape` from different contexts.

---

### Step 7 — Create keybinding files

**7a — `resources/keybindings/default.keybinding`** — see context doc for full
example with the Esc hierarchy, workbench bindings, and editor bindings.

**7b — `resources/keybindings/editor.keybinding`** — editor formatting/navigation
bindings, all with `:when {:in :editor}`.

**7c — `resources/keybindings/emacs.keybinding`** — prefix-key bindings:
```clojure
[{:prefix {:key \x :mod #{:mod1}} :key \s :mod #{:mod1}
  :command :workbench/save
  :comment "C-x C-s → Save"}
 {:key \a :mod #{:mod1} :when {:in :editor}
  :command :editor/beginning-of-line
  :comment "C-a → Beginning of line"}
 {:key \e :mod #{:mod1} :when {:in :editor}
  :command :editor/end-of-line
  :comment "C-e → End of line (overrides toggle-mode in editor)"}
 {:key \k :mod #{:mod1} :when {:in :editor}
  :command :editor/kill-line
  :comment "C-k → Kill to end of line"}
 ;; ...
 ]
```

**7d — `resources/keybindings/word.keybinding`** — Word/Docs conventions:
```clojure
[{:key \l :mod #{:mod1 :shift} :when {:in :editor}
  :command :editor/toggle-bullet
  :comment "Mod1+Shift+L → Bullet list (Word)"}
 {:key \x :mod #{:mod1 :shift} :when {:in :editor}
  :command :editor/toggle-strikethrough
  :comment "Mod1+Shift+X → Strikethrough (Docs)"}
 {:key \[ :mod #{:mod1} :when {:in :editor}
  :command :editor/outdent
  :comment "Mod1+[ → Decrease indent (Word)"}
 {:key \] :mod #{:mod1} :when {:in :editor}
  :command :editor/indent
  :comment "Mod1+] → Increase indent (Word)"}
 ;; ...
 ]
```

**Verify**: REPL — load keybindings, confirm index contains all entries.
Press Mod1+Shift+P, confirm palette command fires.

---

## Phase 2 — Command Palette UI

### Step 8 — Palette Shell widget (`command_palette.clj`)

Create `winze-server/src/llm_memory/ui/command_palette.clj`.

Widget structure:
```
Shell (SWT.TOOL | SWT.ON_TOP | SWT.NO_TRIM)
├── Text (filter field, scope :command-palette)
└── Table (SWT.SINGLE | SWT.FULL_SELECTION)
    ├── TableColumn (label, left-aligned)
    └── TableColumn (keybinding hint, right-aligned)
```

Position: centered horizontally on the main window, ~20% from top.
Size: ~500px wide, up to ~400px tall.
Styling: dark theme matching the editor.

**Verify**: REPL — call `open-command-palette!`, confirm Shell appears.

---

### Step 9 — Platform-correct keybinding hints

```clojure
(def ^:private mod-symbols
  "Platform-correct modifier display strings."
  (if mac?
    {:mod1 "⌘" :shift "⇧" :alt "⌥"}
    {:mod1 "Ctrl+" :shift "Shift+" :alt "Alt+"}))

(defn format-hint
  "Format a keybinding as a display string. E.g., '⌘B' or 'Ctrl+B'."
  [{:keys [key mod prefix]}]
  (let [fmt-one (fn [m k]
                  (str (str/join (map mod-symbols (sort (map name m))))
                       (if (keyword? k)
                         (str/upper-case (name k))
                         (str/upper-case (str k)))))]
    (if prefix
      (str (fmt-one (or (:mod prefix) #{}) (:key prefix))
           " " (fmt-one (or mod #{}) key))
      (fmt-one (or mod #{}) key))))
```

**Verify**: RCF tests — `format-hint` produces platform-correct strings.

---

### Step 10 — Palette filtering and display

**10a — Build available commands** (scope-aware, with hints).

**10b — Fuzzy filter**: split query on spaces, each token must be a
case-insensitive substring of the label.

**10c — Wire modify listener** on the Text widget to re-filter on each
keystroke.

**Verify**: REPL — open palette, type "bold", confirm only "Toggle Bold" shows.

---

### Step 11 — Palette interaction

- Up/Down: move Table selection
- Enter: execute selected command, close palette
- Esc: close palette (via scoped keybinding)
- Click row: execute, close
- Click outside / deactivate: close

**Verify**: REPL — full interaction cycle.

---

## Phase 3 — Integration and Polish

### Step 12 — Wire keybinding loading into startup

```clojure
(keybindings/load-keybindings!
  (some-> (io/resource "keybindings") io/file)
  (io/file (System/getProperty "user.home") ".winze" "keybindings"))
```

Load after commands are registered.

**Verify**: Cold restart — all keybindings work.

---

### Step 13 — Register editor undo/redo as scoped commands

Register once globally, dynamically find the active editor:

```clojure
(commands/register!
  {:id :editor/undo
   :label "Undo"
   :category :edit
   :action (fn []
             (when-let [st (active-styled-text)]
               (when-let [{:keys [history]} (get @open-files (active-file-path))]
                 (md-editor/undo! st history)
                 (md-editor/apply-theme! st (.getText st)))))})
```

**Verify**: REPL — type in editor, Mod1+Z undoes via the keybinding system.

---

### Step 14 — Ensure user directories exist

```clojure
(doseq [subdir ["keybindings" "languages"]]
  (let [d (io/file (System/getProperty "user.home") ".winze" subdir)]
    (when-not (.exists d) (.mkdirs d))))
```

**Verify**: Start server, confirm directories exist.

---

## Summary of Changes

| Phase | Files | Nature |
|-------|-------|--------|
| 1 | `commands.clj` (new), `keybindings.clj` (new), `main_window.clj`, `markdown_editor.clj`, `default.keybinding` (new), `editor.keybinding` (new), `emacs.keybinding` (new), `word.keybinding` (new) | Registry + scopes + prefix dispatch + keybinding files |
| 2 | `command_palette.clj` (new) | Palette UI with platform-correct hints |
| 3 | `main_window.clj`, `keybindings.clj` | Startup wiring, user directories |

## Verification Checkpoints

| Phase | How |
|-------|-----|
| 1 | Esc backs up correctly with multi-level specificity (popup > editor > viewer > search); Mod1+E toggles mode; prefix key C-x C-s works (Emacs preset); Mod1+Z undoes in editor only |
| 2 | Mod1+Shift+P opens palette with platform-correct hints (⌘ on macOS, Ctrl+ on Linux); typing filters; Enter executes; Esc closes |
| 3 | Cold restart works; user `.keybinding` files in `~/.winze/keybindings/` override defaults |

## Implementation Order Rationale

Phase 1 is the foundation — registry, scopes, multi-level dispatch, prefix
keys, and externalized bindings. Phase 2 is the palette UI. Phase 3 wires
everything into startup and ensures user customization works.

This plan delivers a working command palette with scoped keybindings,
platform-correct modifiers, prefix key support, and keybinding presets that
the editor-commands plan can then populate with formatting, heading, list,
and insert commands.
