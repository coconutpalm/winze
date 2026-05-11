# Command Palette — Context

## Goal

Implement a command palette for the Winze editor, along with the underlying
keybinding system that supports it. This is a prerequisite for the editor
commands work (see [EDITOR-COMMANDS-CONTEXT.md](EDITOR-COMMANDS-CONTEXT.md)).

The work has four parts:
1. **Scoped keybinding system** — focus-aware key dispatch with externalized
   bindings, prefix key support, and platform-correct modifier handling
2. **Command registry** — named actions that the palette and keybindings both
   reference
3. **Command palette UI** — fuzzy-filtered popup listing all available commands
4. **Keybinding presets** — bundled profiles (default, Emacs, Word) with user
   override support

## Current State

### Keybinding infrastructure

All keybindings are currently hardcoded in two places:

**Global Display filter** (`main_window.clj:756-777`):
```clojure
(.addFilter @display SWT/KeyDown
  (reify Listener
    (handleEvent [_ event]
      (cond
        (= (.keyCode event) SWT/ESC)
        ;; Always: focus search, clear tab selection
        ...
        (and cmd? (= (.keyCode event) (int \e)))
        ;; Always: toggle view/edit mode
        ...))))
```

**Editor key listener** (`main_window.clj:457-470`, inline in `toggle-mode!`):
```clojure
(.addKeyListener st
  (reify KeyListener
    (keyPressed [_ event]
      (when (and cmd? (= (.keyCode event) (int \z)))
        ;; Undo/redo
        ...))))
```

Both are inline `condp`/`cond` dispatches with no concept of scope, priority,
or external configuration.

### Problem: Esc is a blunt instrument

The current Esc handler always does the same thing: switch to tab 0 (live
search), clear the search field, focus the search box. This is wrong once we
have overlays and popups:

- If content assist is open → Esc should dismiss content assist, not jump to
  live search
- If find/replace bar is open → Esc should close the find bar
- If the link preview is showing → Esc should dismiss the preview
- If the editor has focus (no overlays) → Esc should switch to viewer mode
- If the viewer has focus → Esc should go to live search

Esc needs to **back the user up one level** in the UI hierarchy, not teleport
to a fixed destination.

### Widget identity

SWT widgets have no built-in concept of "what kind of part am I for keybinding
purposes." The CDT framework stores widgets in `app-props` by ID (e.g.,
`:ui/search`, `:ui/main-folder`), but there's no annotation on the widget
itself that says "I'm the search box" vs. "I'm an editor."

## Platform-Correct Modifiers

### MOD1 everywhere, not Cmd

The keybinding system uses SWT's `MOD1` constant (Cmd on macOS, Ctrl on
Windows/Linux) — **never** a hardcoded Cmd or Ctrl. In keybinding files and
the internal representation, the modifier keyword is `:mod1`:

```clojure
;; In .keybinding files:
{:key \b :mod #{:mod1}
 :when {:in :editor}
 :command :editor/toggle-bold}
```

The dispatch code tests `SWT/MOD1`:
```clojure
(not= 0 (bit-and (.stateMask event) SWT/MOD1)) → (conj :mod1)
```

### Platform-aware display in the UI

The palette and any keybinding hint UI renders modifiers using platform
conventions:

| Internal | macOS | Windows/Linux |
|----------|-------|---------------|
| `:mod1` | ⌘ | Ctrl+ |
| `:shift` | ⇧ | Shift+ |
| `:alt` | ⌥ | Alt+ |

Detection at startup:
```clojure
(def mac? (str/includes? (System/getProperty "os.name") "Mac"))
```

The hint formatter maps `:mod1` to `⌘` or `Ctrl+` accordingly.

## Scoped Keybinding Design

### Focus scopes via `.setData("scope", ...)`

Every focusable widget sets its keybinding scope using the named-data SWT API
(to avoid conflicts with CDT's automatic `.setData(props)`):

```clojure
(.setData widget "scope" :editor)       ;; StyledText in edit mode
(.setData widget "scope" :viewer)       ;; Browser in view mode
(.setData widget "scope" :search-box)   ;; Search Text field
(.setData widget "scope" :content-assist) ;; Content assist popup
(.setData widget "scope" :find-bar)     ;; Find/replace bar
```

At key dispatch time, the active scope is queried:

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

### Multi-level context with `:when` predicates

Keybindings specify conditions via a `:when` map. All predicates are
AND-combined. A binding with no `:when` is always active (global binding).

```clojure
{:key :esc
 :when {:in :editor :active-popup :content-assist}
 :command :dismiss-content-assist}
```

#### Context map

The keybinding system builds a context map on every key event:

```clojure
(defn build-context
  "Build the current keybinding context map for predicate evaluation."
  []
  {:in            (active-scope)
   :active-popup  (active-popup-type)    ;; :content-assist, :link-preview, :find-bar, or nil
   :lang          (current-editor-lang)  ;; keywordized language tag from .lang file, or nil
   :mode          (current-tab-mode)})   ;; :edit or :view, or nil
```

#### Predicate keys

| Key | Type | Source | Meaning |
|-----|------|--------|---------|
| `:in` | keyword or set | `(.getData focus "scope")` | Focus scope must match |
| `:active-popup` | keyword or set | Popup state atom | An overlay popup is currently open and has this type |
| `:lang` | keyword or set | Active editor's file extension → `.lang` tags | Active editor language |
| `:mode` | `:edit` or `:view` | `open-files` state | Active tab mode |

Values can be a keyword (exact match) or a set (membership check):

```clojure
(defn eval-when
  "Return true if the :when predicate map matches the given context."
  [when-map context]
  (every? (fn [[k v]]
            (let [actual (get context k)]
              (if (set? v)
                (contains? v actual)
                (= v actual))))
          when-map))
```

#### Esc hierarchy via multi-level `:when`

The Esc "back up one level" behavior is expressed as keybindings with
progressively more specific `:when` predicates. More-specific bindings
(more predicate keys) win over less-specific ones:

```clojure
;; Most specific — dismiss content assist popup in editor
{:key :esc
 :when {:in :editor :active-popup :content-assist}
 :command :dismiss-content-assist}

;; Dismiss link preview in editor
{:key :esc
 :when {:in :editor :active-popup :link-preview}
 :command :dismiss-link-preview}

;; Dismiss find bar in editor
{:key :esc
 :when {:in :editor :active-popup :find-bar}
 :command :dismiss-find-bar}

;; Editor with no popups → switch to viewer
{:key :esc
 :when {:in :editor}
 :command :editor->viewer}

;; Viewer → go to search
{:key :esc
 :when {:in :viewer}
 :command :viewer->search}

;; Command palette
{:key :esc
 :when {:in :command-palette}
 :command :dismiss-command-palette}

;; Search box → clear text
{:key :esc
 :when {:in :search-box}
 :command :clear-search}
```

**Specificity rule**: When multiple bindings match the same key, the binding
with the **most predicate keys** wins. If tied, the last-loaded binding wins.
This is the same specificity model as CSS.

```clojure
(defn specificity
  "Number of predicate keys in a :when map."
  [when-map]
  (count when-map))
```

The dispatch selects the highest-specificity match:

```clojure
(->> candidates
     (filter #(eval-when (:when %) ctx))
     (sort-by #(specificity (:when %)))
     last)
```

### Active popup tracking

Popups (content assist, link preview, find bar) register themselves on open
and deregister on close:

```clojure
(defonce active-popup (atom nil))

(defn set-active-popup! [type] (reset! active-popup type))
(defn clear-active-popup! [] (reset! active-popup nil))

(defn active-popup-type [] @active-popup)
```

The context builder reads this atom:
```clojure
:active-popup (active-popup-type)
```

This is simpler than trying to detect popup state from the widget tree.

## Prefix Keys

### Design

Prefix keys enable multi-keystroke sequences like Emacs's `C-x C-s` or
VS Code's `Cmd+K Cmd+0`. They also enable limited Vi-style selection
sequences like `Esc d i (` (delete inside parens).

A binding can specify a `:prefix` key that must be pressed first:

```clojure
;; VS Code style: Cmd+K, then Cmd+0 → fold all
{:prefix {:key \k :mod #{:mod1}}
 :key    \0 :mod #{:mod1}
 :when   {:in :editor}
 :command :editor/fold-all}

;; Emacs style: C-x, then C-s → save
{:prefix {:key \x :mod #{:mod1}}
 :key    \s :mod #{:mod1}
 :command :workbench/save}
```

### State machine

The prefix key system is a simple two-state machine:

```
IDLE ──(prefix key pressed)──→ WAITING
  ↑                               │
  │    (timeout 1.5s)             │
  ├────────────────────────────────┤
  │    (second key pressed)       │
  ├────────────────────────────────┘
  │    (non-prefix key pressed)
  └────────────────────────────────
```

```clojure
(defonce ^:private prefix-state
  (atom {:prefix nil      ;; the prefix binding that was matched, or nil
         :timer  nil}))   ;; ScheduledFuture for timeout

(defn- enter-prefix-mode!
  "Record the prefix and start the timeout."
  [prefix-binding]
  (when-let [old-timer (:timer @prefix-state)]
    (.cancel old-timer false))
  (let [timer (.schedule executor
                (fn [] (reset! prefix-state {:prefix nil :timer nil}))
                1500 TimeUnit/MILLISECONDS)]
    (reset! prefix-state {:prefix prefix-binding :timer timer})))

(defn- exit-prefix-mode!
  "Clear prefix state and cancel timeout."
  []
  (when-let [timer (:timer @prefix-state)]
    (.cancel timer false))
  (reset! prefix-state {:prefix nil :timer nil}))
```

### Dispatch with prefix awareness

The key dispatch flow becomes:

```
SWT KeyDown event
    ↓
1. If in prefix mode:
   a. Check if [prefix + current-key] matches a prefixed binding
   b. If yes → execute command, exit prefix mode
   c. If no → exit prefix mode, re-dispatch current key as normal
2. If not in prefix mode:
   a. Check if current key matches a prefix-only binding
   b. If yes → enter prefix mode (consume the event, show indicator)
   c. If no → check normal (non-prefixed) bindings as before
```

### Visual indicator

When in prefix mode, show a brief status message (e.g., in the status bar or
as a tooltip): "Mod1+K pressed — waiting for second key..." This tells the
user the prefix was recognized and they should press the second key.

### Vi-style selection sequences

Limited Vi-like sequences use the prefix system. The prefix key is Esc (which
exits editing context), followed by an operator key, then a motion/text-object:

```clojure
;; Esc, then d, then i, then ( → delete inside parens
;; This requires a 3-key prefix chain: Esc → d → i → (
```

Actually, three-key chains need a different approach. The two-state prefix
machine handles `prefix + key` (two steps). For Vi-style `operator +
text-object` sequences (three+ steps), extend the state machine to allow
**chained prefixes**: after a prefix match, the system can enter another
prefix mode if the matched binding specifies another prefix.

However, this adds complexity. For the initial implementation:

- **Two-key prefixes**: Supported natively (Emacs `C-x C-s`, VS Code
  `Cmd+K Cmd+0`)
- **Vi-style sequences**: Deferred to a future enhancement. The two-key prefix
  covers the most common cases. Full Vi emulation would be a separate work
  item.

The prefix system is designed to be extensible to deeper chains later:
```clojure
;; Future: the :prefix field could itself be a binding with a :prefix
{:prefix {:prefix {:key :esc} :key \d}
 :key    \(
 :when   {:in :editor}
 :command :editor/delete-inside-parens}
```

## Keybinding Files

### File extension: `.keybinding`

Keybinding files use the `.keybinding` extension with plain EDN content. The
custom extension signals purpose (like `.lang` for tokenizers). No special
reader is needed — standard `edn/read-string`.

### File locations and load order

```
resources/keybindings/
├── default.keybinding       ← default keybinding profile (always loaded first)
├── emacs.keybinding         ← Emacs-style prefix bindings (optional preset)
├── word.keybinding          ← Microsoft Word / Google Docs bindings (optional preset)
└── editor.keybinding        ← editor formatting/navigation bindings

~/.winze/keybindings/
└── *.keybinding             ← user overrides and additions (loaded last)
```

### Load and merge semantics

1. **Always load**: `default.keybinding` (workbench essentials: Esc, Mod1+E,
   palette triggers)
2. **Always load**: `editor.keybinding` (formatting, headings, line operations)
3. **Optionally load**: preset files (`emacs.keybinding`, `word.keybinding`)
   — selected by a user preference or config setting
4. **Always load**: user files in `~/.winze/keybindings/*.keybinding`

Later bindings for the same `[mod-set key when-map]` tuple override earlier
ones. This means:
- User files override any built-in binding
- Preset files override default bindings where they conflict
- The default profile provides the baseline

### Preset profiles

**`default.keybinding`** — the standard profile, modeled on VS Code/Obsidian:
- Mod1+B = bold, Mod1+I = italic, etc.
- Mod1+Shift+P = command palette
- Standard Esc hierarchy

**`emacs.keybinding`** — Emacs-flavored additions:
- C-x C-s = save (prefix key)
- C-x C-f = open file (prefix key)
- C-a / C-e = beginning/end of line
- C-k = kill line
- M-w = copy, C-w = cut, C-y = paste

**`word.keybinding`** — Microsoft Word / Google Docs conventions:
- Mod1+Shift+L = bullet list (Word)
- Mod1+Shift+7 = numbered list (Google Docs)
- Mod1+Shift+X = strikethrough (Google Docs)
- Mod1+[ / Mod1+] = decrease/increase indent (Word)

These presets are **additive** — they layer on top of `default.keybinding`,
overriding bindings where they conflict. A user who selects the Emacs preset
still gets all default bindings that Emacs doesn't override.

### Keybinding data format

```clojure
;; Each binding is a map:
{:key     \b                ;; character, or keyword for special keys (:esc, :f3, etc.)
 :mod     #{:mod1}          ;; modifier set (optional, defaults to #{})
 :command :editor/toggle-bold
 :when    {:in :editor}     ;; optional context predicate map
 :prefix  {:key \k :mod #{:mod1}}  ;; optional prefix key (for multi-key sequences)
 :comment "Toggle Bold"}    ;; optional, ignored at runtime
```

### Special key keywords

```clojure
:esc :tab :enter :backspace :delete :insert :space
:f1 :f2 :f3 :f4 :f5 :f6 :f7 :f8 :f9 :f10 :f11 :f12
:up :down :left :right :home :end :page-up :page-down
```

### Example: `default.keybinding`

```clojure
[;; --- Esc hierarchy (most-specific first for clarity, but specificity
 ;;     is determined by predicate count, not file order) ---
 {:key :esc :when {:in :editor :active-popup :content-assist}
  :command :dismiss-content-assist}
 {:key :esc :when {:in :editor :active-popup :link-preview}
  :command :dismiss-link-preview}
 {:key :esc :when {:in :editor :active-popup :find-bar}
  :command :dismiss-find-bar}
 {:key :esc :when {:in :command-palette}
  :command :dismiss-command-palette}
 {:key :esc :when {:in :editor}
  :command :editor->viewer}
 {:key :esc :when {:in :viewer}
  :command :viewer->search}
 {:key :esc :when {:in :search-box}
  :command :clear-search}

 ;; --- Workbench ---
 {:key \e :mod #{:mod1}
  :command :workbench/toggle-mode}
 {:key \p :mod #{:mod1 :shift}
  :command :workbench/command-palette}
 {:key :f3
  :command :workbench/command-palette}

 ;; --- Editor (scoped) ---
 {:key \z :mod #{:mod1} :when {:in :editor}
  :command :editor/undo}
 {:key \z :mod #{:mod1 :shift} :when {:in :editor}
  :command :editor/redo}
 {:key \b :mod #{:mod1} :when {:in :editor}
  :command :editor/toggle-bold}
 {:key \i :mod #{:mod1} :when {:in :editor}
  :command :editor/toggle-italic}
 ;; ... additional editor bindings
 ]
```

## Key Dispatch Flow

```
SWT KeyDown event (Display filter)
    ↓
1. If in prefix mode:
   a. Build [prefix + mod-set + keyCode] lookup key
   b. Check for prefixed bindings matching current context
   c. If match → execute command, exit prefix mode, consume event
   d. If no match → exit prefix mode, fall through to step 2
    ↓
2. Check if [mod-set + keyCode] matches a prefix-only binding
   a. If yes → enter prefix mode, consume event, show indicator
    ↓
3. Check normal (non-prefixed) bindings:
   a. Look up [mod-set keyCode] in the keybinding index
   b. Filter candidates by :when predicate against current context
   c. Select highest-specificity match
   d. If match → execute command, consume event
   e. If no match → let event propagate (doit = true)
```

## Command Registry

The command registry is a map of `{command-id → command-def}`:

```clojure
{:id       :workbench/escape
 :label    "Escape"
 :category :workbench
 :action   (fn [] ...)}
```

Commands have **no hotkey field** — keybindings are external. This separation
means:
- The same command can have multiple keybindings
- Keybindings can be overridden without touching command definitions
- The command palette queries the registry for all commands

## Command Palette UI

### Trigger keybinding

Multiple bindings to accommodate muscle memory from different editors:

| Binding | Precedent |
|---------|-----------|
| Mod1+Shift+P | Atom, VS Code, Logseq, Sublime Text |
| F3 | Eclipse |

Skip Mod1+P — it conflicts with "print" (macOS) and "quick open" (VS Code).
If we add quick-open later, Mod1+P is the natural binding for that.

### Widget structure

```
Shell (SWT.TOOL | SWT.ON_TOP | SWT.NO_TRIM)
├── Text (filter field, scope :command-palette)
└── Table (result list — plain text rows, not HTML)
    ├── Row: "Toggle Bold          ⌘B"       [formatting]
    ├── Row: "Toggle Italic        ⌘I"       [formatting]
    ├── Row: "Heading 1            ⌘1"       [heading]
    └── ...
```

Keybinding hints use platform-correct symbols (⌘ on macOS, Ctrl+ on
Windows/Linux).

### Behavior

1. **On open**: Show all commands available in the current scope context.
   Pre-filter to commands whose `:when` matches (or have no `:when`).
2. **Typing filters**: Fuzzy substring match against `:label`. Re-filter on
   every keystroke.
3. **Category grouping**: Results grouped by `:category` with visual labels.
4. **Keybinding hints**: Right-aligned, platform-correct modifier symbols.
5. **Selection**: Up/Down arrows navigate. Enter executes. Esc closes.
6. **Click**: Clicking a row executes that command.

### Fuzzy matching

Simple substring match for v1. The query is split on spaces; each token must
appear as a substring of the label (in any order). "tog bold" matches
"Toggle Bold", "h1" matches "Heading 1."

### Scope-aware filtering

The palette only shows commands applicable in the current context. This is
evaluated by checking each command's bindings' `:when` predicates. Commands
with no `:when` on any binding are always shown.

## Files to Create/Modify

| File | Change |
|------|--------|
| `winze-server/src/llm_memory/ui/keybindings.clj` | **New** — keybinding loading, index building, scoped dispatch, prefix state machine, context evaluation, platform hint formatting |
| `winze-server/src/llm_memory/ui/commands.clj` | **New** — command registry, registration, execution |
| `winze-server/src/llm_memory/ui/command_palette.clj` | **New** — palette Shell widget, fuzzy filter, result display |
| `winze-server/src/llm_memory/ui/main_window.clj` | Replace Display filter with keybinding dispatch; add scope annotations to existing widgets |
| `winze-server/src/llm_memory/ui/markdown_editor.clj` | Add scope annotation to StyledText; remove inline key listener |
| `winze-server/resources/keybindings/default.keybinding` | **New** — default keybinding profile |
| `winze-server/resources/keybindings/editor.keybinding` | **New** — editor command keybindings |
| `winze-server/resources/keybindings/emacs.keybinding` | **New** — Emacs-style preset (optional) |
| `winze-server/resources/keybindings/word.keybinding` | **New** — Word/Docs-style preset (optional) |

## Related Work

- **editor-commands** (active) — the command registry design originated there;
  this plan refines it and adds the keybinding system and palette UI. See
  [EDITOR-COMMANDS-CONTEXT.md](EDITOR-COMMANDS-CONTEXT.md).
- **edn-tokenizers** (active) — the `:lang` predicate in `:when` maps uses
  language tags from `.lang` files. See
  [EDN-TOKENIZERS-CONTEXT.md](EDN-TOKENIZERS-CONTEXT.md).
- **wiki-links** (active) — Ctrl-click navigation coexists with the
  keybinding system (Ctrl-click is a mouse handler, not a keybinding).
- **search-history-nav** (active) — Mod1+[/] bindings will be externalized.
- `complete/swt-ui/global-esc/` — the current global Esc handler that this
  work supersedes.
- `complete/search/search-keybindings/` — the current search Enter/Esc behavior
  that integrates into the new scope system.

## Design Decisions

### Why MOD1 instead of Cmd/Ctrl

SWT provides `SWT/MOD1` which maps to Cmd on macOS and Ctrl on Windows/Linux.
Using `:mod1` in keybinding files means the same file works on all platforms.
The UI rendering layer translates `:mod1` to the platform-appropriate symbol
(⌘ or Ctrl+). This is the same approach SWT itself uses throughout its API.

### Why multi-level `:when` with specificity instead of a scope hierarchy

The original design had a separate `esc-chain` data structure mapping scopes
to actions. The multi-level `:when` approach is better because:
- Esc behavior is expressed as regular keybindings (no special case)
- Adding a new popup level = adding one keybinding entry
- The specificity rule handles precedence naturally
- `:active-popup` captures ephemeral UI state that doesn't map cleanly to
  focus scopes (a popup may be open while focus is still in the editor)

### Why `.keybinding` extension

Same reasoning as `.lang` for tokenizers: signals purpose, prevents accidental
processing by generic EDN tools, establishes a file-type convention. Unlike
`.lang`, these files use standard EDN (no `#"..."` regex literals needed),
but the custom extension still communicates "this is a keybinding definition."

### Why prefix keys instead of a full modal system

Prefix keys (two-key sequences) cover:
- All VS Code `Cmd+K ...` commands
- All Emacs `C-x ...` commands
- The most useful Vi motions (with Esc as the prefix)

A full modal system (Vi command mode vs. insert mode) would require tracking
a persistent mode state, rebinding every key in command mode, and handling
mode indicators in the UI. That's a much larger scope. The prefix key approach
gives 80% of the value for 20% of the complexity, and the architecture extends
to deeper chains later if needed.

### Why scopes on widget `.setData("scope", ...)` instead of a registry

The `.setData("scope", :keyword)` named-data approach is self-describing:
the scope annotation lives on the widget itself, set at creation time, and
is queryable in O(1). The named-data variant (with `"scope"` key) avoids
conflicts with CDT's automatic `.setData(props)` call.

### Why Mod1+Shift+P and F3 for the palette

- **Mod1+Shift+P**: De facto standard (VS Code, Atom, Sublime Text, Logseq).
- **F3**: Eclipse convention. Single-key alternative.
- **Mod1+P omitted**: Conflicts with "print" (macOS) and "quick open"
  (VS Code). Reserve for future file quick-open.

### Why separate preset files instead of a single merged config

Each preset is a cohesive set of bindings that make sense together. Merging
Emacs bindings into the default file would make it harder to reason about
what's active. Separate files also let users delete or replace a preset
cleanly. The load-order merge semantics (last wins) handles conflicts.

### Why plain Table for the palette, not HTML Browser

The palette shows text labels and keybinding hints — no rich formatting. A
plain SWT `Table` is faster, lighter, and easier to implement than HTML.

## Risks

- **CDT named-data variant**: `.setData(String, Object)` is standard SWT API
  on all `Widget` subclasses. No CDT conflict. Verify in REPL during Step 1.
- **Prefix key timeout UX**: If the timeout is too short, users fumble. If
  too long, the app feels stuck. 1.5s is the VS Code default and a reasonable
  starting point. Make it configurable later if needed.
- **Specificity ties**: If two bindings have the same number of `:when`
  predicates and both match, last-loaded wins. This is deterministic but may
  surprise users. Document the behavior clearly.
- **Display filter ordering**: The global filter must run before widget-level
  key listeners. SWT guarantees this — Display filters fire first.
- **Preset activation**: Currently no GUI for selecting presets. Users activate
  a preset by placing its `.keybinding` file in `~/.winze/keybindings/`. A
  GUI for this is explicitly out of scope.
