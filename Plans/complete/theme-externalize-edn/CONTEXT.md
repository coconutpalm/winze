---
name: theme-externalize-edn context
description: Externalize Winze theme colors/fonts into a user-editable EDN file with palette-refresh command and resilient reader.
type: context
---

# Externalize Theme to EDN — Context

## Goal

Move the Winze theme (colors + fonts) out of hard-coded `defonce` delays in
[`resources.clj`](../../../winze-server/src/llm_memory/ui/resources.clj) into a
user-editable **EDN file at `~/.winze/theme.edn`**.

- The file is **bundled as a classpath resource** (`resources/theme.edn`) and
  copied to the user directory on first launch if missing.
- From then on, the user-dir file is **canonical** — editing it changes the
  theme.
- A **command-palette entry** ("Reload Theme") re-reads the file, disposes the
  existing `Color`/`Font` resources, constructs new ones, and refreshes the UI.
- The reader is **error-tolerant**: bad entries are dropped, a list of errors
  is collected, and a `MessageBox` is shown at end of parse if non-empty.

Tracked as a wishlist item: [`todo/wishlist.md`](../wishlist.md) — *"externalize
theme colors and fonts into EDN files so people can make their own themes."*

## Current state — what the theme looks like today

**File:** [`winze-server/src/llm_memory/ui/resources.clj`](../../../winze-server/src/llm_memory/ui/resources.clj)

All theme resources are `defonce` delays, forced lazily after UI startup.

### Colors (14 — 12 currently in `resources.clj`, 2 new from `search.clj`)

| Var                       | Hex       | Usage                                                    |
|---------------------------|-----------|----------------------------------------------------------|
| `color-lavender`          | `#C4B8FF` | lightest brand tint                                      |
| `color-amethyst`          | `#9B8FE0` | primary accent                                           |
| `color-deep-violet`       | `#7B6FC0` | secondary accent                                         |
| `color-royal-purple`      | `#5548A0` | selection bg                                             |
| `color-indigo`            | `#4A3F90` | search-result card gradient (NEW — currently only in `search.clj/colors`) |
| `color-deep-amethyst`     | `#3A2F80` | search-result card border (NEW — currently only in `search.clj/colors`)   |
| `color-crystal-white`     | `#E8E0FF` | body foreground                                          |
| `color-mine-shaft`        | `#1E1B2E` | body background                                          |
| `color-obsidian`          | `#241E5E` | card background                                          |
| `color-find-bar`          | `#3A335E` | find-bar background                                      |
| `color-bedrock`           | `#0E0D18` | deepest shadow tone                                      |
| `color-pure-white`        | `#FFFFFF` | pure white                                               |
| `color-check-green`       | `#66BB6A` | status indicator                                         |
| `color-spellcheck-error`  | `#E5484A` | misspelling underline                                    |

Each color is (today):

```clojure
(defonce color-lavender (delay (ui (Color. @display 0xC4 0xB8 0xFF))))
```

The two "NEW" entries are defined today only as hex strings inside
[`search.clj`'s private `colors` map](../../../winze-server/src/llm_memory/ui/search.clj#L21-L32)
at [L26-L27](../../../winze-server/src/llm_memory/ui/search.clj#L26-L27); this work promotes
them to first-class registry entries and adds `defonce color-indigo` /
`defonce color-deep-amethyst` in `resources.clj`.

### Fonts (13 — built from two stacks)

Font stacks:

```clojure
(def sans-stack ["Inter" "Plus Jakarta Sans" "Outfit"
                 "Noto Sans" "Helvetica Neue" "Helvetica"])
(def mono-stack ["JetBrains Mono" "Fira Code"
                 "Noto Sans Mono" "Menlo" "Consolas" "Courier New"])
```

Built by `(make-font stack size style)` which calls `first-available-font` to
resolve the stack against installed fonts.

| Role          | Var                                         | Size | Style              |
|---------------|---------------------------------------------|------|--------------------|
| Body          | `body-font`, `body-bold-font`, `body-italic-font`, `body-bold-italic-font` | 13 | NORMAL/BOLD/ITALIC/BOLD+ITALIC |
| Headings      | `h1-font` … `h6-font`                       | 24/20/17/15/13/13 | BOLD, BOLD, BOLD, BOLD, BOLD+ITALIC, ITALIC |
| Mono          | `mono-font`, `mono-bold-font`, `mono-italic-font` | 13 | NORMAL/BOLD/ITALIC |

### Browser-rendered HTML palette (search.clj + find_replace.clj)

Two namespaces render content into SWT `Browser` widgets from HTML templates
interpolating raw hex strings, **not** SWT `Color` objects:

- [`search.clj:21-32`](../../../winze-server/src/llm_memory/ui/search.clj#L21-L32)
  — private `def ^:private colors` map with 11 hex-string entries used by
  `page-css` (consumed during every `set-live-search-content!` render to build
  the `<style>` block for result cards, h1–h6, code blocks, etc.). Two of its
  entries (`:indigo`, `:deep-amethyst`) do not exist elsewhere today — they
  become the "NEW" entries in the colors table above.

- [`find_replace.clj:194-195`](../../../winze-server/src/llm_memory/ui/find_replace.clj#L194-L195)
  — JavaScript string injected into the viewer Browser to colour match
  highlights (`'#C4B8FF'`, `'#5548A0'`, `'#0E0D18'`, `'#E8E0FF'`). Injected via
  `browser.execute(js-string)` when the user searches in viewer mode.

Both must follow the theme. Design consequence: HTML/JS rendering
cannot accept a `Color` — it needs a hex string. Rather than store a
parallel hex registry, the plan derives hex on demand from the live
`Color` atom via `.getRed`/`.getGreen`/`.getBlue` (see §"Key design
decisions" #9 — `theme/hex`). There is exactly one source of truth
per color (the `Color` atom); hex drift is impossible. Both templates
become fns (or read at render time) rather than static `def`s, and
both browsers re-render on theme reload. See [`PLAN.md`](PLAN.md)
Step 5 #23 (`hex` helper) and Steps 7c / 7e (re-render).

### Icons (7 file-based + 3 procedural)

Two distinct kinds of icons in `resources.clj`:

**File-based** (loaded from classpath PNG/BMP resources — THEMABLE in v1):

| Var                 | Bundled path                                              | HiDPI? | Consumers |
|---------------------|-----------------------------------------------------------|--------|-----------|
| `app-icon`          | `branding/icons/png/winze-icon-16.png`                    | no     | shell `:image` (L1115) |
| `statusbar-icon`    | `branding/statusbar/macos/winzeTemplate[@2x].png`         | yes    | tray, tab-item images |
| `tab-document-icon` | `branding/ui/png/winze-tab-document-[16,32].png`          | yes    | tab-item images on open files |
| `header-image`      | `branding/header/winze-wordmark-slogan-dark.png`          | no     | `(label :image …)` in `(header)` |

**Procedural** (drawn via SWT `GC` using raw-RGB `Color` literals — NOT THEMABLE in v1, deferred):

- `edit-icon` — quill, see `draw-quill-icon` (colors: amethyst/violet/lavender hard-coded at `resources.clj:93-96`)
- `back-icon` / `forward-icon` — chevrons, `draw-chevron-image` (color: amethyst hard-coded at `resources.clj:160`)

v1 scope: file-based icons become user-overridable via `~/.winze/icons/`;
procedural icons keep raw RGB literals. Documented in §"Non-goals".

### Registry disposal (already exists — generalises, does NOT need a parallel path)

[`resources.clj:447-464`](../../../winze-server/src/llm_memory/ui/resources.clj#L447-L464)
— `dispose-registry!` walks `ns-publics`, finds **realized delays** whose
deref is an SWT `Resource`, and disposes them. It gates on **both**
`(delay? val)` *and* `(realized? val)`. Once we replace color/font/icon
`defonce` delays with **atoms** (Step 4), those vars will no longer match
`delay?` and would be silently skipped by the existing function.

Consequence: generalise the `IDeref` guard from "Delay + realized" to
"IAtom OR realized Delay" (PLAN Step 11). One unified walk covers both:
- **IAtoms** (colors, fonts, themable file-based icons) — always safe
  to deref; `@atom` never realises anything. `IAtom` is the Clojure
  interface every atom implements; it is NOT implemented by delays,
  promises, futures, refs, or agents — so it uniquely identifies
  "swappable theme value".
- **Realized delays** (procedural icons: `edit-icon`, `back-icon`,
  `forward-icon` — not part of the theme registry, see design
  decision #8 below).

The rest of the function (checking `(instance? Resource held)`,
`(not (.isDisposed held))`, `try`/`log/warn`) is unchanged. This is a
minor generalisation of an existing pattern, not a rewrite.

## Consumers — who references theme resources

The rename from direct var derefs (`@resources/color-royal-purple`) to a
registry-backed lookup touches several files. Exhaustive list (verified via
`grep -n "res/color-\|res/.*-font\|@resources/"` on `winze-server/src/`):

- [`markdown_editor.clj`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj)
  - `type->style` map at lines 26-59 — 10+ color/font var references, captured at `def` time (stores the `IDeref`, derefed per-span in `span->style-range`)
  - Line 96 — `@res/color-bedrock` in `apply-code-block-line-backgrounds!`
  - Lines 176-179 — bullet rendering (amethyst, deep-violet, check-green)
  - Lines 941-945 — `styled-text` init args (5 refs) inside the `md-edit-styled-text` CDT init fn
- [`command_palette.clj`](../../../winze-server/src/llm_memory/ui/command_palette.clj) — lines 146-175 (palette Shell is freshly constructed on each `open-palette!`, so closed palette auto-themes on next open)
- [`content_assist.clj`](../../../winze-server/src/llm_memory/ui/content_assist.clj) — line 513 (assist popup font)
- [`find_replace.clj`](../../../winze-server/src/llm_memory/ui/find_replace.clj) — lines 402-407 (find bar shell)
- [`link_preview.clj`](../../../winze-server/src/llm_memory/ui/link_preview.clj) — line 91 (preview shell bg)
- [`spellcheck.clj:255`](../../../winze-server/src/llm_memory/ui/spellcheck.clj#L255) — `res/color-spellcheck-error` in the squiggly-underline style map (merged into editor spans; restyles with `md-editor/apply-theme!`)
- [`search.clj:21-32`](../../../winze-server/src/llm_memory/ui/search.clj#L21-L32) — private `colors` map (hex strings) consumed by `page-css` at every Browser render. Must be rewritten to read hex strings from the theme registry at render time. **Consumer of hex-string view**, not SWT Colors.
- [`find_replace.clj:194-195`](../../../winze-server/src/llm_memory/ui/find_replace.clj#L194-L195) — hardcoded hex strings inside a JavaScript template string injected into the viewer Browser. Must be string-interpolated from the hex registry at injection time. **Consumer of hex-string view**.
- [`main_window.clj`](../../../winze-server/src/llm_memory/ui/main_window.clj)
  - Line 529 — `(.setBackground wrapper @res/color-mine-shaft)` in `toggle-mode!` (view→edit path)
  - Lines 534-538 — StyledText font/bg/fg/selection-bg/selection-fg in `toggle-mode!`
  - Line 722 — `(label :image @res/header-image)` in `(header)` — **icon consumer**
  - Lines 859-860 — `(.setImage tray-item @res/statusbar-icon)` in `tray-item2` — **icon consumer**
  - Line 1115 — `:image @res/app-icon` on shell — **icon consumer**
  - `ctab-item` calls pass `@res/statusbar-icon` / `@res/tab-document-icon` as tab images — **icon consumer**

**Design consequence 1**: the existing `@resources/color-foo` call sites are
deref expressions (`IDeref`). If we preserve that shape — making each var a
re-resolving `IDeref` that reads the *current* registry atom — consumer
callsites do not change. This is cleaner than renaming every call.

**Design consequence 2 — wrapper Composite restyle**: the `main_window.clj:529`
`wrapper.setBackground` call is NOT covered by a markdown-editor-only refresh
listener. The wrapper Composite outlives the StyledText (mode toggles
dispose+recreate the StyledText but not the wrapper). A theme refresh must
re-apply `wrapper.setBackground` for each open edit-mode tab, or the wrapper
will keep painting with a disposed `Color`.

**Design consequence 3 — widget-construction derefs run during arg evaluation of `(application ...)`, BEFORE `application` itself is called**:
CDT's `application`, `shell`, `header`, `label`, `composite` are **regular
functions**, not macros. Clojure evaluates their arguments eagerly,
left-to-right, before calling the function. Concretely:

- `(shell SWT/SHELL_TRIM … :image @app-icon …)` at [main_window.clj:1113-1115](../../../winze-server/src/llm_memory/ui/main_window.clj#L1113-L1115)
  — `@app-icon` is an ordinary argument. It is forced **when `shell` is
  invoked as a varargs to `application`**, before `application`'s body
  ever runs.
- `(header)` at [:1119](../../../winze-server/src/llm_memory/ui/main_window.clj#L1119) calls
  `(composite … (label :image @header-image) …)` at [:722](../../../winze-server/src/llm_memory/ui/main_window.clj#L722).
  Same eager-arg behavior — `@header-image` is forced when `header`
  (and through it `label`) is evaluated as an arg to `shell`, which is
  evaluated as an arg to `application`.
- [`tray-item2`](../../../winze-server/src/llm_memory/ui/main_window.clj#L847-L867)
  is the one exception: it dereferences `@statusbar-icon` **inside the
  `(fn [props display] …)` body** that it returns. That deref is
  deferred to `run-inits` time, so any scheme that populates the registry
  before `run-inits` will work for tray.

**Why the "first init form" pattern does not fix this**: even if the first
init form passed to `application` populates every theme atom, the shell,
header, and label init functions have already closed over `nil` for their
`:image` values (they were evaluated as args before `application` was
ever called). `run-inits` then invokes those init fns with `nil`-image
closures, producing a Shell without an icon.

**Resolution — populate every theme atom before `(application …)` is called
at all.** The theme load must run **before** the `(let [result (application
…)] …)` form in `main-window`. See PLAN Step 10 for the exact wiring. This
is earlier in the startup sequence than any init form.

**Invariant** (assert in PLAN comments so future edits don't regress it):
*shell/header/body must not dereference any theme resource other than icons;
the icon derefs inside their argument lists require that
`(apply-theme-startup! @display)` has ALREADY been called before
`(application …)` is invoked. Populating the registry from within
`application`'s init sequence is too late.*

## Canonical rule book: `Plans/SWT-UI-GUIDE.md`

The guide governs all SWT work in this project. Directly-relevant sections:

| § | Rule | Implication for this work |
|---|------|--------------------------|
| **§1–2** | UI thread sacred; `ui`/`sync-exec!` to read, `async-exec!` to mutate | Color/Font construction and widget re-styling must run on the UI thread. The "Reload Theme" command action must wrap the work in `async-exec!`. |
| **§3** | Never call `application` against a running server | Theme reload must not restart the Display — just swap resources. |
| **§11** | If you created a resource, you must dispose it | Old `Color`/`Font` must be disposed **only after** no live widget still holds a reference. Re-applying theme to all widgets first, then disposing, is the safe order. |
| **§13/§22** | Targeted `:reload` only | REPL-side testing of the reload path uses `load-file`/targeted `defn` evals. |
| **§15** | Screenshot-verify visual changes | After implementing reload, screenshot the UI with the default theme, edit `~/.winze/theme.edn` (change one color obviously), invoke "Reload Theme", screenshot again. Required. |
| **§18** | Retrieve widgets via `mw/element` (never CDT init fns from REPL) | The reload walk must iterate live widgets via `element` lookups and existing reference atoms (e.g. `open-files`, tab widgets, `live-search-state`). |
| **§29–30** | Custom-color registry by semantic role; font registry by role+size+style | The EDN schema should key on semantic role. Current names (`color-royal-purple`, `body-font`) are **already role-like** — keep them verbatim as the registry keys to minimize churn. |
| **§31** | Icon registry (HiDPI) | Icons are out of scope for this change (they are drawn programmatically with palette colors, but changing them live is a harder problem). Document as deferred. |

## Existing patterns to reuse

### User-dir installation: `~/.winze/languages/`

[`highlight/loader.clj:170-194`](../../../winze-server/src/llm_memory/highlight/loader.clj#L170-L194)
already establishes the **`~/.winze/<category>/`** convention. Its
`user-lang-dir` helper creates the directory if missing:

```clojure
(defn- user-lang-dir []
  (let [d (io/file (System/getProperty "user.home") ".winze" "languages")]
    (when-not (.isDirectory d) (.mkdirs d))
    d))
```

Reuse this shape for `user-theme-file` → `~/.winze/theme.edn`. **Note**: that
loader already *overlays* user .lang files on top of bundled ones. Our theme
semantics are different: the user file is **canonical** once installed, not an
overlay. Read only from the user file after first-run install.

### Partial-success reader with error collection

[`highlight/loader.clj`](../../../winze-server/src/llm_memory/highlight/loader.clj)
is the template: `read-lang-source` → `validate-language` → returns a vector
of error strings; valid definitions register, bad ones are dropped, and a
`startup-errors` atom accumulates them.

At [`main_window.clj:898-910`](../../../winze-server/src/llm_memory/ui/main_window.clj#L898-L910),
`show-lang-errors!` pops a `MessageBox` listing any validation errors. This
is **exactly the pattern** we need for theme errors.

```clojure
(let [mb (MessageBox. (element :main-window) (| SWT/ICON_WARNING SWT/OK))]
  (.setText mb "Language File Errors")
  (.setMessage mb (str "Some syntax highlighting languages failed to load:\n\n"
                       (str/join "\n" errors) ...))
  (.open mb))
```

### Command registration

Registry: [`llm_memory.ui.commands`](../../../winze-server/src/llm_memory/ui/commands.clj) — a `sorted-map` atom keyed by `:id`.

Registration pattern (from [`command_palette.clj:283-293`](../../../winze-server/src/llm_memory/ui/command_palette.clj#L283-L293)):

```clojure
(commands/register!
 {:id       :workbench/open-command-palette
  :label    "Open Command Palette"
  :category :workbench
  :action   (fn [] (async-exec! open-palette!))})
```

Registered in bulk from `register-workbench-commands!` which is called from
`defmain` in `main-window` ([`main_window.clj:1164`](../../../winze-server/src/llm_memory/ui/main_window.clj#L1164)). Add our `:workbench/reload-theme` alongside.

The palette fuzzy-filters over all registered commands — no extra wiring to
make it searchable.

### Classpath resource read (JAR-safe)

From `resources.clj:58-63`:

```clojure
(let [stream (.getResourceAsStream (clojure.lang.RT/baseLoader) path)]
  ...)
```

And from project `CLAUDE.md`: **never** `(io/file (io/resource "path"))` —
fails inside uberjars. Use `(io/reader (io/resource "path"))` for text
resources.

## Key design decisions (resolve in PLAN Step 1)

1. **EDN schema shape**. Proposal — keep current var names as registry keys,
   include the two new `search.clj`-only colors:

    ```clojure
    {:colors {:lavender        "#C4B8FF"
              :amethyst        "#9B8FE0"
              :deep-violet     "#7B6FC0"
              :royal-purple    "#5548A0"
              :indigo          "#4A3F90"   ; NEW — search.clj
              :deep-amethyst   "#3A2F80"   ; NEW — search.clj
              :crystal-white   "#E8E0FF"
              :mine-shaft      "#1E1B2E"
              :obsidian        "#241E5E"
              :find-bar        "#3A335E"
              :bedrock         "#0E0D18"
              :pure-white      "#FFFFFF"
              :check-green     "#66BB6A"
              :spellcheck-error "#E5484A"}
     :font-stacks {:sans ["Inter" "Plus Jakarta Sans" ...]
                   :mono ["JetBrains Mono" "Fira Code" ...]}
     :fonts  {:body     {:stack :sans :size 13 :style :normal}
              :body-bold {:stack :sans :size 13 :style :bold}
              :h1       {:stack :sans :size 24 :style :bold}
              :mono     {:stack :mono :size 13 :style :normal}
              ...}
     :icons  {:app          {:src "my-app-icon.png"}
              :statusbar    {:src-1x "my-tray.png" :src-2x "my-tray@2x.png"}
              :tab-document {:src-1x "my-tab.png" :src-2x "my-tab@2x.png"}
              :header       {:src "my-wordmark.png"}}}
    ```

    Style values: `:normal`, `:bold`, `:italic`, `:bold-italic` (mapped to
    SWT constants internally — users should not write `SWT/BOLD`).

    Icon entries are **optional** at the outer level — a missing `:icons`
    block means all icons use bundled defaults. A partially-overridden icon
    (e.g. HiDPI icon supplied with `:src-1x` only) is an **explicit
    validation error** — see design decision #7. An empty `:icons {}` is
    valid (no overrides).

2. **Per-entry validation** (reader resilience). Each color entry is parsed
   independently: `{key "#RRGGBB"}` with a regex. Bad entries → error string
   containing key name + reason; good entries → `{key [r g b]}`. Same for
   fonts: validate `:stack` is a vector of strings, `:size` is positive int,
   `:style` is a known keyword. Errors accumulate into a single vector;
   returned map has only valid entries.

3. **Default fallback on missing key**. If the user's `theme.edn` is missing
   `:color-check-green` entirely, what happens? Three options:
   - (a) Merge user over bundled defaults → missing keys use defaults.
   - (b) Treat missing required keys as errors → show them in the MessageBox.
   - (c) Widgets using undefined keys fail silently.

   **Recommend (a)**: always merge user values on top of bundled defaults at
   parse time. The bundled theme defines the full set; the user file may
   override any subset. Missing keys are not errors. This matches user
   expectations for overlaying tweaks.

4. **Per-var atom shape (self-maintaining via naming convention)**.
   Each themable resource becomes a plain atom:

   ```clojure
   (defonce color-royal-purple (atom nil))   ; colors
   (defonce body-font          (atom nil))   ; fonts
   (defonce app-icon           (atom nil))   ; icons
   (defonce header-image       (atom nil))   ; icons
   ```

   Consumer call sites (`@resources/color-royal-purple`) are unchanged —
   atom deref and `defonce`-delay deref are indistinguishable at the
   `@` call site. A reload swaps each atom's value via `reset!`.

   **Enumeration without a companion map**: `theme.clj` walks
   `ns-publics` filtered by `(instance? clojure.lang.IAtom val)` + a
   naming-convention parser that maps var names to EDN keys
   (`color-royal-purple` → `[:colors :royal-purple]`,
   `body-bold-font` → `[:fonts :body-bold]`,
   `tab-document-icon` → `[:icons :tab-document]`,
   `header-image` → `[:icons :header]`). Procedural icons stay as
   `(defonce … (delay …))` and are filtered out by the `IAtom` check
   (they share the `-icon` suffix but not the type).

   This design mirrors the existing
   [`dispose-registry!`](../../../winze-server/src/llm_memory/ui/resources.clj#L447-L464)
   pattern (ns-publics walk guarded by an IDeref-type check). **No
   separate companion map is maintained** — adding a new theme var
   requires exactly two edits: the `defonce` in `resources.clj` and the
   EDN entry. The reload machinery self-maintains.

   See PLAN Step 4 for the full list of atoms, Step 5 #21a for the
   name-parser, and §"Key design decisions" #9 below for the hex-view
   follow-on.

5. **UI-refresh mechanics after reload**. Swapping the registry atom changes
   what `@resources/color-foo` returns, but **widgets that have already had
   `setBackground` called still reference the old `Color` object**. SWT does
   not re-read the color when the app mutates state.

   Two strategies:

   - **Broadcast + restyle** (same shape as
     `spellcheck/register-refresh-listener!`): every widget that applies
     theme colors registers a 0-arg refresh callback on init; on reload,
     walk the listener set and call each. Each callback re-applies theme
     (e.g. `(apply-theme! parent styled-text (.getText styled-text))` for
     markdown editors, `re-style-palette-shell` for the palette, etc.).

   - **Walk app-props widgets** from `main_window`: iterate known widget keys
     (`:main-folder`, `:search`, `:main-window`, each open tab's wrapper) and
     call a per-widget-type restyle.

   **Recommend the broadcast model**. It's the existing idiom (spellcheck
   already uses it for user-dict changes), places restyle logic near the
   widget that owns it, and scales to future widgets without editing a
   central restyle list.

6. **Disposal ordering on reload**:

   1. Parse new EDN → `{:theme ... :errors [...]}`.
   2. Snapshot pre-swap atom values: walk every theme atom via
      `theme-atoms`, collect `{atom-ref → @atom-ref}`.
   3. Create new `Color`/`Font`/`Image` SWT objects **on the UI thread** →
      return a sectioned map `{:colors … :fonts … :icons …}`.
   4. `(reset! atom-ref new-val)` for each theme atom — derefs now return
      new values. (If the new map has no entry for a given atom, install
      a magenta sentinel and push a completeness error.)
   5. Broadcast restyle → every widget re-calls `setBackground`/`setFont`
      with the new resources.
   6. **After** restyle completes (enqueue disposal via `async-exec!` so it
      runs after listener-enqueued restyles on the FIFO event queue), walk
      the pre-swap snapshot and dispose every `Resource` that's not
      already disposed. This ensures no widget is still painting with a
      freshly-disposed resource.
   7. Show `MessageBox` with any accumulated errors.

   The bundled defaults are always merged under the user file (decision
   #3), so `build-swt-resources!` always has a valid input map — every
   atom gets a value (or the magenta sentinel), and no half-applied
   state is possible. There is no "keep old atom values if theme map is
   empty" branch.

7. **Icon resolver — `~/.winze/icons/` search path, classpath fallback**.
   Icons have a fixed set of **semantic keys** (`:app`, `:statusbar`,
   `:tab-document`, `:header`). For each key, the resolver needs a bundled
   classpath path (hard-coded in `theme.clj` as the `bundled-icons` table)
   *and* optionally a user-chosen filename from `theme.edn`.

   Validation is two-layered:

   - **Parse-time (`validate-icon-entry`)**: takes the `bundled-icons` table
     as input. Verifies the icon key is known, that a non-HiDPI key uses
     `:src` (not `:src-1x`/`:src-2x`), and that a HiDPI key supplies **both**
     `:src-1x` **and** `:src-2x`. A partial HiDPI override (only one of the
     two slots) is an error; filename containing `/` or `\` is an error.
     Invalid entries are dropped; the whole icon falls back to bundled.
   - **Build-time (`resolve-icon-source` / `open-icon-stream`)**: even after
     parse-time validation, the user file may not exist or be unreadable.
     Each such case is logged + pushed to `errors-atom`, then the slot falls
     back to the classpath resource. Never blocks theme load.

   The builder accepts whatever slots survived validation; a missing slot
   for a known icon is by definition a validation error (and therefore the
   whole icon's user entry was already dropped).

   Key implementation constraint: `(io/file (io/resource "…"))` fails inside
   uberjars. Use `InputStream` directly, built either from a `FileInputStream`
   (user dir) or from `(.getResourceAsStream (clojure.lang.RT/baseLoader) path)`
   (classpath). `Image.` accepts any `InputStream`. For HiDPI icons, the
   `ImageDataProvider` closure must reconstruct fresh `InputStream`s on each
   `getImageData` call because streams are single-use.

   **User-dir bootstrap**: `~/.winze/icons/` is created on first launch
   (`mkdirs`) but left **empty**. We don't copy the bundled PNGs there —
   the directory exists only as an override slot.

8. **What stays out of the theme-atom walk**. Procedural icons
   (`edit-icon`, `back-icon`, `forward-icon`) keep their existing
   `defonce (delay …)` shape. They are NOT atoms, so the
   `(instance? clojure.lang.IAtom val)` filter in `theme-atoms` naturally
   excludes them from the reload/refresh walk. The shutdown
   `dispose-registry!` walk still catches them via its "IAtom OR
   realized Delay" guard (PLAN Step 11). Rationale: their colors are raw
   RGB literals in `draw-quill-icon` / `draw-chevron-image`, so re-theming
   them is a separate piece of work (would need to switch literals to
   registry derefs and re-render via `ImageDataProvider`). Tracked in
   §"Non-goals".

9. **Hex-string view for Browser-rendered HTML — derived on demand, no
    parallel storage**. `theme/hex` computes the hex string from the
    live `Color` atom's value via `.getRed`/`.getGreen`/`.getBlue`:

    ```clojure
    (defn hex [k]
      ;; k is a bare color keyword like :lavender
      (when-let [var-ref (ns-resolve 'llm-memory.ui.resources
                                     (symbol (str "color-" (name k))))]
        (when-let [^Color c @@var-ref]
          (format "#%02X%02X%02X" (.getRed c) (.getGreen c) (.getBlue c)))))
    ```

    No parallel hex-atom or hex-registry entries are maintained.
    Rationale: the `Color` is the single source of truth; deriving hex
    from it on every call eliminates the class of drift bugs where SWT
    state and hex state get out of sync during partial reloads.
    `Color.getRed/Green/Blue` are pure field reads — not UI-thread
    gated — so `theme/hex` is safe from any thread.

    Access from consumers:
    - `@res/color-lavender` — unchanged (SWT `Color`, via atom deref).
    - `(theme/hex :lavender)` — helper returning `"#C4B8FF"`.

    `search.clj` and `find_replace.clj` are the two Browser-HTML consumers
    today; any future Browser content reads via the same helper. The
    EDN file carries only `:colors`, `:fonts`, `:icons` sections — no
    `:hex/*` keys anywhere in the schema.

10. **CTabItem icon discriminator on reload**. There are three distinct
    tab kinds in [`open-tab!`](../../../winze-server/src/llm_memory/ui/main_window.clj#L221-L280):

    - **File tabs** — have `abs-path`; built with a wrapper Composite
      (registered in `app-props` under a generated wrapper-id) around a
      Browser/StyledText (registered under a separate browser-id); the
      wrapper-id is recorded in `@open-files` under `:wrapper-id`;
      they use `tab-document-icon`.
    - **Search-result tabs** — no `abs-path`; Browser child directly under
      the folder; use `statusbar-icon`.
    - **Live-search tab** — permanent first tab. Its wrapper Composite has
      `(id! :ui/live-search-wrapper)` and its Browser has
      `(id! :ui/live-search-browser)`. Always uses `statusbar-icon`,
      even when transiently holding a file in view mode (at which point
      `open-files` records `:wrapper-id :ui/live-search-wrapper`).

    The Step 7d reload walk discriminates on **the wrapper Composite
    control**, not on the browser: it looks up each CTabItem's control
    and excludes `(element :live-search-wrapper)` from the set of
    "file-tab wrappers". Any tab whose control is NOT a
    non-live-search wrapper-Composite key in `@open-files` is a
    `statusbar-icon` tab.

11. **Editor consolidation is OUT of scope — see `markdown-editor-widget`**.
    Today [`markdown-editor`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L929-L978)
    is defined but never invoked — the live StyledText is hand-rolled in
    `toggle-mode!` at
    [`main_window.clj:521-549`](../../../winze-server/src/llm_memory/ui/main_window.clj#L521-L549).
    Earlier drafts of this plan bundled a `toggle-mode!` → CDT-init
    consolidation into this work; that refactor has its own active plan
    at [`todo/MARKDOWN-EDITOR-WIDGET-PLAN.md`](../MARKDOWN-EDITOR-WIDGET-PLAN.md)
    and should land separately.

    Theme-refresh-listener ownership adapts accordingly: if the editor
    refactor has already landed, the listener lives inside the CDT init
    alongside `install-spellcheck!` (one registration point); otherwise
    it is registered in BOTH the CDT init (for future reuse) AND the
    hand-rolled `toggle-mode!` block, with a `FIXME(theme-externalize-edn):`
    breadcrumb on the duplicate so the editor-refactor lander can
    collapse them. See PLAN Step 6.5 (now just a dependency note) and
    Step 7a.

12. **Reload entry point naming**. The existing
    [`md-editor/apply-theme!`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L951)
    applies per-span styling to a StyledText on every `modifyText`. To
    avoid a name collision the theme-reload entry point is named
    `theme/reload-theme!` (matching the command id
    `:workbench/reload-theme`). `theme/apply-theme-startup!` remains the
    first-load entry point.

## Risks & open questions

- **Font family not installed on user's system**: existing `first-available-font`
  already handles this. No change needed — the fallback stack logic just runs
  on every reload.
- **Consumers that cache a `Color` locally**: any local `(let [c @resources/color-foo] ...)` that outlives the reload will still reference the disposed color. Audit callsites; most just read at render time. If any cache, they must be rewritten to fetch at use time.
- **`draw-quill-icon` / `draw-chevron-image`**: these currently construct ad-hoc `Color` objects from raw RGB (not registry entries). They are **not hot-reloaded** by this change. Acceptable — documented as deferred. If the user changes palette colors, existing procedural icons stay on old palette until they're rewritten to read from the registry. (Hot-reloading programmatic icons is a separate piece of work — redraw via ImageDataProvider.)
- **Reader edge cases** (all use `clojure.edn/read` with a `PushbackReader` — **never** `clojure.core/read-string`, which eval's `#=(…)` and is a code-execution risk on untrusted files):
  - Unreadable EDN (`edn/read` throws): catch, surface as a single error `"theme.edn: unreadable — <exception message>"`, keep the current registry.
  - Empty file → `edn/read` returns `nil` → treat as `"theme.edn: no data (empty file)"`, keep the current registry.
  - Non-map top-level form: error out with `"theme.edn: top-level form must be a map"`, keep the current registry.
- **Icon edge cases**:
  - User filename doesn't exist under `~/.winze/icons/`: log + error entry, fall back to bundled. Not fatal.
  - User file exists but isn't a valid image: `Image.` constructor throws. Catch, emit error, fall back to bundled. Not fatal.
  - User provides `:src-1x` but not `:src-2x` (or vice versa): resolve each slot independently. Mix-and-match is allowed.
- **Find-bar double-close on reload is harmless**: the
  `shellDeactivated` listener at
  [main_window.clj:1183-1189](../../../winze-server/src/llm_memory/ui/main_window.clj#L1183-L1189)
  also closes the find bar asynchronously when the main window loses
  focus. `close-transient-shells!` (called synchronously at the start
  of `reload-theme!`) closes it first; the deferred handler then
  no-ops because `find-bar-open?` returns false. Double-close is
  benign — just noting it so future changes to either path don't
  assume single-ownership.
- **Short-lived popups open at reload**: find-bar, content-assist popup, and link-preview Shell may be open when Reload Theme fires. They cache `Color`/`Font` references set at Shell construction; if we dispose old resources without re-styling these popups, they'll paint with a disposed resource on the next redraw. Two policies considered:
  - (a) **Force-close the popups** at reload start — simple, user-visible flicker.
  - (b) **Restyle them inline** — more code, no flicker.

  v1 picks **(a)**. Rationale: reload is an explicit, infrequent user action; a one-frame close of a transient popup is harmless and simpler to reason about. Implemented by calling a small `close-transient-shells!` helper at the start of `apply-theme!` *before* any SWT mutation.
- **`main_window.clj` wrapper-Composite restyle**: lines 529-538 set bg/fg/font on the wrapper Composite AND on the StyledText. A per-editor refresh listener must re-apply BOTH, not just the StyledText. Easy to miss — explicit line in the restyle callback.
- **Tray-item and tab-item icons**: `TrayItem.setImage` is called once at construction. On theme reload, the tray image must be re-set. Same for open `CTabItem`s (`tab-document-icon`) and the live-search tab (`statusbar-icon`).
- **Watcher-driven reload (stretch)**: The brief asks for a command. A filesystem watcher that auto-reloads on save would be a natural next step — out of scope here; document as future work.

## Non-goals for this work item

- **Hot-reloading procedural icons** (quill, chevrons). They use raw RGB
  literals in `draw-quill-icon` / `draw-chevron-image`; re-theming them
  requires switching to registry derefs AND an `ImageDataProvider`-based
  re-render on each reload. Out of scope. If the user changes colors that
  the quill/chevron visually "should" pick up (amethyst/violet/lavender),
  those icons stay on old colors until app restart — acceptable for v1.
- **Copying bundled PNGs into `~/.winze/icons/`**. The user-dir is an
  override slot. Leaving it empty keeps things simple; if the user wants a
  starting point they can pull icons out of the JAR manually.
- Theme switching (multiple themes, pick one). The file is a single theme.
- **GUI theme editor, or "open theme.edn" menu command.** Users edit
  `~/.winze/theme.edn` in their own text editor — Winze itself is a
  markdown editor, not an EDN editor (no paren balancing, no keyword
  completion, no schema validation in the buffer), and there is
  currently no UI affordance to open the file inside Winze. This is a
  deliberate v1 limitation. If a future agent investigates improving
  the theme-editing UX, the options are roughly: (a) promote Winze's
  editor to handle `.edn` via the existing language-tokenizer system
  + basic structural cues; (b) add a "Open Theme File" command-palette
  entry that opens theme.edn in an external `$EDITOR`; (c) build a
  dedicated GUI theme editor. None of these are planned.
- Changing consumer callsite shape (`@resources/color-foo`). The registry-backed
  `IDeref` preserves this.
- **Schema migration / versioned theme.edn.** The bundled file carries
  no `:version` marker. Forward compatibility strategy is simple:
  **add new keys; never remove or rename existing keys.** New keys
  flow through via the "merge user over bundled defaults" rule
  (decision #3), so existing user files continue to work unchanged.
  Removal would orphan entries in user files (harmless — unused entries
  allocate a Color and leak it from `build-swt-resources!`'s output);
  renaming is equivalent to removal + addition and carries the same
  cost. If we ever have to remove/rename, do it at a major version
  boundary and accept the user-file churn.
