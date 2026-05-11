---
name: theme-externalize-edn plan
description: Step-by-step plan to move Winze colors/fonts to ~/.winze/theme.edn with resilient reader and command-palette reload.
type: plan
---

# Externalize Theme to EDN — Plan

> Canonical rule book for every SWT decision below: [`Plans/SWT-UI-GUIDE.md`](../../SWT-UI-GUIDE.md).
> See [CONTEXT.md](CONTEXT.md) for the color/font inventory, design rationale,
> and the consumer-audit list.

---

## Step 1 — Design the EDN schema; commit a bundled default

**File (new)**: `winze-server/resources/theme.edn`

Use the proposed schema from CONTEXT.md §"Key design decisions" #1. Write
every current color and font value verbatim so the bundled theme is
byte-equivalent to today's hard-coded resources:

```clojure
;; Winze theme file — edit this to customize colors, fonts, and icons.
;; Run "Reload Theme" from the command palette (Cmd+Shift+P) to apply.
;;
;; Known limitation: the quill (edit), back, and forward toolbar icons are
;; drawn programmatically with the stock amethyst/violet/lavender palette.
;; If you change those palette colors below, those three icons will keep
;; their original colors until a future release adds dynamic redraw.

{:colors {:lavender         "#C4B8FF"
          :amethyst         "#9B8FE0"
          :deep-violet      "#7B6FC0"
          :royal-purple     "#5548A0"
          :indigo           "#4A3F90"   ; NEW — from search.clj palette
          :deep-amethyst    "#3A2F80"   ; NEW — from search.clj palette
          :crystal-white    "#E8E0FF"
          :mine-shaft       "#1E1B2E"
          :obsidian         "#241E5E"
          :find-bar         "#3A335E"
          :bedrock          "#0E0D18"
          :pure-white       "#FFFFFF"
          :check-green      "#66BB6A"
          :spellcheck-error "#E5484A"}
 :font-stacks
 {:sans ["Inter" "Plus Jakarta Sans" "Outfit"
         "Noto Sans" "Helvetica Neue" "Helvetica"]
  :mono ["JetBrains Mono" "Fira Code"
         "Noto Sans Mono" "Menlo" "Consolas" "Courier New"]}
 :fonts
 {:body             {:stack :sans :size 13 :style :normal}
  :body-bold        {:stack :sans :size 13 :style :bold}
  :body-italic      {:stack :sans :size 13 :style :italic}
  :body-bold-italic {:stack :sans :size 13 :style :bold-italic}
  :h1 {:stack :sans :size 24 :style :bold}
  :h2 {:stack :sans :size 20 :style :bold}
  :h3 {:stack :sans :size 17 :style :bold}
  :h4 {:stack :sans :size 15 :style :bold}
  :h5 {:stack :sans :size 13 :style :bold-italic}
  :h6 {:stack :sans :size 13 :style :italic}
  :mono             {:stack :mono :size 13 :style :normal}
  :mono-bold        {:stack :mono :size 13 :style :bold}
  :mono-italic      {:stack :mono :size 13 :style :italic}}

 ;; :icons is OPTIONAL. Omitting a key OR a src slot → bundled default.
 ;; Bundled defaults map `theme.clj` constants (not in this file) to
 ;; classpath paths. Users place override files in ~/.winze/icons/ and
 ;; reference them by bare filename here.
 :icons {}}
```

The file-based icons that can be overridden (bundled defaults hard-coded
in `theme.clj` per design decision #7 in CONTEXT.md):

| Key            | HiDPI | Bundled classpath path(s)                                                                   |
|----------------|-------|---------------------------------------------------------------------------------------------|
| `:app`         | no    | `branding/icons/png/winze-icon-16.png`                                                      |
| `:statusbar`   | yes   | `branding/statusbar/macos/winzeTemplate.png` + `branding/statusbar/macos/winzeTemplate@2x.png` |
| `:tab-document`| yes   | `branding/ui/png/winze-tab-document-16.png` + `branding/ui/png/winze-tab-document-32.png`   |
| `:header`      | no    | `branding/header/winze-wordmark-slogan-dark.png`                                            |

Procedural icons (`edit-icon`, `back-icon`, `forward-icon`) are not in the
registry and cannot be theme-overridden — see CONTEXT.md §"Non-goals".

**Verify**: the file exists under `resources/` and is on the classpath
(`resources/` is already a resource root in `deps.edn`).

---

## Step 2 — Write the resilient reader

**File (new)**: `winze-server/src/llm_memory/ui/theme.clj`

Namespace outline:

```clojure
(ns llm-memory.ui.theme
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.io File PushbackReader]))
```

Required functions (small, composable — see project conventions):

1. `parse-hex-color [s]` — return `[r g b]` or `nil` if `s` isn't `#RRGGBB`.
2. `parse-style [kw]` — map `:normal`/`:bold`/`:italic`/`:bold-italic` to
   SWT style bits (use `SWT/NORMAL`, `SWT/BOLD`, `SWT/ITALIC`, and
   `(| SWT/BOLD SWT/ITALIC)`). Unknown style → `nil`.
3. `validate-color-entry [k v]` — return `{:ok [k rgb]}` or `{:error msg}`.
4. `validate-font-entry [k {:keys [stack size style]} stacks]` — same shape;
   errors include unknown stack keyword, stack resolving to a non-vector
   or an **empty** vector (`first-available-font` would silently fall back
   to `""` — the system default — which is almost certainly not the user's
   intent), non-positive size, unknown style.
5. `validate-icon-entry [bundled-icons k v]` — return `{:ok [k normalized]}`
   or `{:error msg}`. Takes the **bundled-icons table** (defined in Step 5
   §18) so it can look up `:hidpi?` per key and enforce the correct slot
   shape. Rules per key:
     - **Unknown key** (not in bundled-icons) → error.
     - **Non-HiDPI key** (e.g. `:app`, `:header`): requires exactly `:src`
       (string). `:src-1x`/`:src-2x` present → error. `:src` missing or
       non-string → error.
     - **HiDPI key** (e.g. `:statusbar`, `:tab-document`): requires **both**
       `:src-1x` and `:src-2x` (strings). Either missing → error ("HiDPI
       icon requires both :src-1x and :src-2x; got only :src-1x"). `:src`
       present → error.
     - Any filename containing `/` or `\` → error (force bare filenames so
       users can't escape `~/.winze/icons/`).

   Any `{:error msg}` drops the WHOLE icon entry for that key (the icon
   falls back entirely to bundled). Builders then see a missing user entry
   for that key and take the bundled path without any further branching —
   this is what "the builder accepts any combination of slots" means: it
   only ever sees slot combinations that have already passed validation,
   or no user entry at all.

6. `parse-theme [edn-map bundled-icons]` — walks `:colors`, `:font-stacks`,
   `:fonts`, `:icons`, drops invalid entries, returns
   `{:colors {kw [r g b]} :font-stacks {kw [str]} :fonts {kw {:stack [str] :size n :style bits}} :icons {kw {:src str?, :src-1x str?, :src-2x str?}} :errors [str]}`.
   Takes `bundled-icons` and threads it into `validate-icon-entry`.
   Handle top-level edge cases:
     - `nil` input (empty file) → `{:errors ["theme.edn: no data (empty file)"]}` (all registry sections empty)
     - non-map input → `{:errors ["theme.edn: top-level form must be a map"]}`
7. `read-theme-source [source label]` — mirrors `loader/read-lang-source`:
   wraps `edn/read` in try/catch; on exception returns
   `{:errors [(str label ": unreadable — " (.getMessage e))]}`.

**Tests (RCF)** — co-located, cover:
- Good hex → RGB; bad hex (wrong length, non-hex, missing `#`) → nil.
- `parse-style` round-trip for all 4 valid styles + unknown keyword → nil.
- `validate-font-entry` rejects an empty stack (`:stack []`) and a stack
  keyword that is not present in `:font-stacks`.
- `parse-theme` with a map containing one bad color and one good color
  returns the good one and a single error string.
- `parse-theme` with unreadable EDN (caller hands us the exception path).
- `parse-theme` on empty input (`nil`) returns empty sections + one error.
- `parse-theme` on non-map input returns empty sections + one error.
- `validate-icon-entry` rejects a filename containing `/` or `\`.
- `validate-icon-entry` rejects partial HiDPI (only `:src-1x` without `:src-2x`) with a clear error string.
- `validate-icon-entry` rejects `:src-1x`/`:src-2x` on a non-HiDPI key.
- `validate-icon-entry` rejects `:src` on a HiDPI key.
- `validate-icon-entry` rejects an unknown icon key.

Run the new namespace in the REPL; confirm every RCF assertion passes
before moving on (project convention: REPL-first).

---

## Step 3 — User-directory install + load

Still in `llm-memory.ui.theme`:

8. `user-theme-file []` — mirror [`highlight/loader.clj:170-175`](../../../winze-server/src/llm_memory/highlight/loader.clj#L170-L175):

    ```clojure
    (defn- user-theme-file []
      (let [winze-dir (io/file (System/getProperty "user.home") ".winze")]
        (when-not (.isDirectory winze-dir) (.mkdirs winze-dir))
        (io/file winze-dir "theme.edn")))
    ```

9. `user-icon-dir []` — same shape, for `~/.winze/icons/`:

    ```clojure
    (defn- user-icon-dir []
      (let [d (io/file (System/getProperty "user.home") ".winze" "icons")]
        (when-not (.isDirectory d) (.mkdirs d))
        d))
    ```

    This directory exists only as an **override slot** — we do NOT copy
    bundled icons into it. Empty by default.

10. `install-default-theme-if-missing! []` — if the user `theme.edn` doesn't
    exist, copy `resources/theme.edn` into place **atomically** via a
    `theme.edn.tmp` sibling + `Files/move … ATOMIC_MOVE`:

    ```clojure
    (let [target (user-theme-file)
          tmp    (io/file (.getParentFile target) "theme.edn.tmp")]
      (with-open [in  (io/input-stream (io/resource "theme.edn"))
                  out (io/output-stream tmp)]
        (io/copy in out))
      (try
        (java.nio.file.Files/move
          (.toPath tmp) (.toPath target)
          (into-array java.nio.file.CopyOption
                      [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                       java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
        (catch java.nio.file.AtomicMoveNotSupportedException _
          ;; Fallback: ~/.winze on a different filesystem (rare on
          ;; single-user macOS/Linux, possible on Windows with network
          ;; user profiles). Non-atomic REPLACE_EXISTING is still correct
          ;; — the tmp file was fully written before the move attempt.
          (java.nio.file.Files/move
            (.toPath tmp) (.toPath target)
            (into-array java.nio.file.CopyOption
                        [java.nio.file.StandardCopyOption/REPLACE_EXISTING])))))
    ```

    Rationale: if the JVM crashes mid-copy, no partial `theme.edn` is left
    behind — the next launch reinstalls cleanly. (NOT
    `(io/file (io/resource …))` — see project `CLAUDE.md` JAR-safety
    note.) Do NOT attempt to populate `~/.winze/icons/`.

11. `load-bundled-theme []` — parses `resources/theme.edn` via `io/reader`.
    Used as the "defaults" baseline that user theme entries overlay onto.

12. `load-user-theme []` — parses `(user-theme-file)`.

13. `merge-themes [defaults user]` — user entries override defaults per-key
    across `:colors`, `:font-stacks`, `:fonts`; missing user keys fall
    through to defaults. `:icons` merges differently — user entries are
    **partial per-slot** overlays (see Step 5's `resolve-icon-source`). Do
    NOT merge `:icons` with the bundled list; instead carry the user
    `:icons` map through and let the icon builder resolve each src slot
    against user-then-bundled. Errors from both sources concatenate.

14. `load-theme! []` — top-level entry: install default `theme.edn` if
    missing, ensure `~/.winze/icons/` exists, load bundled, load user,
    merge, return `{:theme merged :errors all-errors}`.

**Tests (RCF)**:
- Temp-dir fixture: set `user.home` to tmp; call `install-default-theme-if-missing!`; assert file appears, contents match resource. (Use `System/setProperty` in a fixture, restore after.)
- Call twice — second call is a no-op.
- `user-icon-dir` creates the directory but does not populate it.
- Merge: user `{:colors {:lavender "#000000"}}` + defaults with full palette → merged has user lavender + default everything else.
- Merge: user `:icons {:app {:src "my.png"}}` overlays on empty bundled `:icons` without overwriting other slots; the :app key is carried through for the builder to resolve.

---

## Step 4 — Per-var atoms in `resources.clj` (self-maintaining via naming convention)

**Edit**: `winze-server/src/llm_memory/ui/resources.clj`

**Design**: each themable resource becomes a plain atom. The atom's value
is swapped by `reload-theme!`; consumers continue to write
`@resources/color-lavender` — atom deref is indistinguishable from the
current `defonce`-delay deref at call sites. Enumeration of all theme
atoms is done by walking `ns-publics` and filtering by
`(instance? clojure.lang.IAtom val)` — mirroring the existing
[`dispose-registry!`](../../../winze-server/src/llm_memory/ui/resources.clj#L447-L464)
pattern, which already walks `ns-publics` looking for resource-holding
IDerefs. `IAtom` is the **type-level invariant** that distinguishes
"reload-swappable theme value" from "immutable procedural delay" —
not a naming convention, which could silently drift. **No separate
registry map** is maintained — the ns itself is the registry.

Replace every `(defonce color-foo (delay (ui (Color. ...))))` with a
bare atom. **Above the block**, add a comment that preserves the
IAtom-as-type-invariant rationale — future maintainers must
understand why these are atoms and not delays, reifies, or refs:

```clojure
;; ---------------------------------------------------------------------------
;; Theme resources (colors/fonts/file-based icons)
;;
;; Each var here is an IAtom holding the current SWT resource (Color /
;; Font / Image) for one themable slot. Call sites use `@color-foo`
;; unchanged — atom deref is identical to delay deref at the `@` site.
;;
;; WHY ATOMS (IAtom), NOT DELAYS: `theme/reload-theme!` enumerates these
;; vars via `ns-publics` and filters on `(instance? clojure.lang.IAtom v)`.
;; Every Clojure atom implements IAtom; delays, promises, futures,
;; refs, and agents do NOT. That type check — not a naming convention —
;; is what separates "reload-swappable theme value" from everything
;; else in this namespace (procedural-icon delays, state atoms like
;; `open-files`, executors, etc.). Do NOT change these `defonce`s to
;; delays, refs, or reify-IDeref values: `reload-theme!` will silently
;; skip anything that isn't an IAtom.
;;
;; Initial value is `nil`; populated by `theme/apply-theme-startup!`
;; before any widget dereferences them (see PLAN Step 10 timing
;; invariant — derefs fire at widget-construction time, before
;; `defmain` runs).
;; ---------------------------------------------------------------------------

(defonce color-lavender      (atom nil))
(defonce color-amethyst      (atom nil))
(defonce color-deep-violet   (atom nil))
(defonce color-royal-purple  (atom nil))
(defonce color-indigo        (atom nil))   ; NEW — promoted from search.clj
(defonce color-deep-amethyst (atom nil))   ; NEW — promoted from search.clj
(defonce color-crystal-white (atom nil))
(defonce color-mine-shaft    (atom nil))
(defonce color-obsidian      (atom nil))
(defonce color-find-bar      (atom nil))
(defonce color-bedrock       (atom nil))
(defonce color-pure-white    (atom nil))
(defonce color-check-green   (atom nil))
(defonce color-spellcheck-error (atom nil))
```

No current code consumes `@res/color-indigo` or `@res/color-deep-amethyst`
(today they only appear as hex strings in `search.clj/colors`), so adding
these vars is zero-risk — they become the SWT handle for future consumers.

Same for fonts:

```clojure
(defonce body-font             (atom nil))
(defonce body-bold-font        (atom nil))
(defonce body-italic-font      (atom nil))
(defonce body-bold-italic-font (atom nil))
(defonce h1-font (atom nil))  (defonce h2-font (atom nil))
(defonce h3-font (atom nil))  (defonce h4-font (atom nil))
(defonce h5-font (atom nil))  (defonce h6-font (atom nil))
(defonce mono-font        (atom nil))
(defonce mono-bold-font   (atom nil))
(defonce mono-italic-font (atom nil))
```

**Same for file-based icons** — replace
`(defonce app-icon (delay (new-image-resource "…")))` etc. with atoms:

```clojure
(defonce app-icon          (atom nil))
(defonce statusbar-icon    (atom nil))
(defonce tab-document-icon (atom nil))
(defonce header-image      (atom nil))
```

### Naming-convention contract

The ns-publics walk parses var names into EDN registry keys via the
following contract — the **only** rules the codebase must follow:

| Var name pattern       | EDN section | EDN key               | Example                                |
|------------------------|-------------|-----------------------|----------------------------------------|
| `color-<name>`         | `:colors`   | `(keyword <name>)`    | `color-royal-purple` → `:royal-purple` |
| `<name>-font`          | `:fonts`    | `(keyword <name>)`    | `body-bold-font`    → `:body-bold`     |
| `<name>-icon`          | `:icons`    | `(keyword <name>)`    | `tab-document-icon` → `:tab-document`  |
| `<name>-image`         | `:icons`    | `(keyword <name>)`    | `header-image`      → `:header`        |
| anything else          | —           | —                     | not a theme var; skipped               |

`theme/var-name->theme-key` (Step 5 #21 below) implements this. RCF
tests cover each case + a non-match.

**Procedural icons stay as `defonce`-delays** — critical: delays do
NOT implement `IAtom`, so the `(instance? clojure.lang.IAtom val)`
filter in the reload walk excludes them by type, not by name. The
`-icon` suffix is shared with themable atoms, but only `IAtom`
instances are reload-reset:

```clojure
;; ---------------------------------------------------------------------------
;; Procedural icons — NOT theme resources.
;;
;; These share the `-icon` suffix with themable icons (app-icon,
;; statusbar-icon, etc.) but are deliberately delays, not atoms.
;; The IAtom type check in `theme/reload-theme!` and `theme-atoms`
;; excludes them — a themable icon would be `(atom nil)`, a procedural
;; icon is `(delay …)`. Do not "fix" the apparent inconsistency by
;; making these atoms: their pixels are drawn from raw RGB literals
;; inside `draw-quill-icon` / `draw-chevron-image` (see resources.clj)
;; and so cannot participate in the theme-reload cycle without also
;; teaching those drawers to read from the palette atoms + re-render
;; via ImageDataProvider (deferred — see CONTEXT.md §Non-goals).
;;
;; Shutdown disposal IS handled: `dispose-registry!`'s unified walk
;; (PLAN Step 11) branches by type — IAtom for themable resources,
;; realized Delay for these three procedural icons.
;; ---------------------------------------------------------------------------
(defonce edit-icon    (delay (quill-hidpi-image)))
(defonce back-icon    (delay (chevron-hidpi-image :left)))
(defonce forward-icon (delay (chevron-hidpi-image :right)))
```

Delete `sans-stack`, `mono-stack`, and `make-font`/`first-available-font`
helpers from `resources.clj` — they move into `theme.clj` (Step 5).
**Exception**: keep `font-available?` and `first-available-font` if
they're consumed elsewhere (grep first; likely only used for font
construction). Also move `new-image-resource` + `hidpi-image` into
`theme.clj` and generalise them to accept arbitrary `InputStream`-producing
sources (Step 5).

**CRITICAL TIMING INVARIANT** — icon derefs fire during widget
construction, *before* `defmain` runs (see CONTEXT.md §"Consumers" design
consequence #3). Specifically: `@app-icon` in the shell init
([main_window.clj:1115](../../../winze-server/src/llm_memory/ui/main_window.clj#L1115)),
`@statusbar-icon` in `tray-item2` ([:859-860](../../../winze-server/src/llm_memory/ui/main_window.clj#L859-L860)),
`@header-image` in `(header)` ([:722](../../../winze-server/src/llm_memory/ui/main_window.clj#L722)).
This means the theme registry MUST be populated before any of these run —
see Step 6's `theme-init-form` and Step 10's `application` wiring.

**Do not change** the `app-props` / `executor` / history blocks in this
file.

---

## Step 5 — `build-swt-resources!` — construct Colors/Fonts/Icons from parsed theme

In `llm-memory.ui.theme`:

15. `font-available? [display name]` and `first-available-font` — move here
    from `resources.clj`.
16. `build-color [display [r g b]]` — `(ui (Color. display r g b))`.
17. `build-font [display stacks {:keys [stack size style]}]` — resolves
    `(stacks stack)` → vector of names → `first-available-font` → `(ui (Font. display name size style))`.

### Var-name → theme-key parser

21a. `var-name->theme-key [sym]` — maps a `resources.clj` var name to
    its EDN registry coordinate `[section key]`, per the Step 4
    naming-convention contract. Returns `nil` for non-matching names
    (which the ns-publics walk treats as "not a theme var, skip"):

    ```clojure
    (defn- var-name->theme-key
      "Map a public-var symbol in `resources.clj` to its EDN registry
       coordinate [section key], or nil if the symbol doesn't match the
       theme naming convention. Section is :colors, :fonts, or :icons."
      [sym]
      (let [s (name sym)]
        (cond
          (str/starts-with? s "color-")  [:colors (keyword (subs s 6))]
          (str/ends-with?   s "-font")   [:fonts  (keyword (subs s 0 (- (count s) 5)))]
          (str/ends-with?   s "-icon")   [:icons  (keyword (subs s 0 (- (count s) 5)))]
          (str/ends-with?   s "-image")  [:icons  (keyword (subs s 0 (- (count s) 6)))])))
    ```

    RCF tests:
    - `color-royal-purple` → `[:colors :royal-purple]`
    - `body-bold-font`     → `[:fonts  :body-bold]`
    - `tab-document-icon`  → `[:icons  :tab-document]`
    - `header-image`       → `[:icons  :header]`
    - `app-props`          → `nil` (not a theme var)
    - `open-files`         → `nil`

21b. `theme-atoms []` — return a seq of `[sym atom-ref section key]`
    tuples for every theme atom in `llm-memory.ui.resources`:

    ```clojure
    (defn- theme-atoms
      "Enumerate theme atoms in `llm-memory.ui.resources`.
       Returns a seq of [sym atom-ref section key] tuples, where
       section ∈ #{:colors :fonts :icons} and key is the EDN lookup
       key derived from the var name.

       FILTER: `(instance? clojure.lang.IAtom val)`. This is the
       type-level invariant that separates theme resources (which
       MUST be atoms so reload can `reset!` them) from procedural
       icons (which are delays, not atoms) and from other ns state
       atoms that don't match the naming convention. Using the
       `IAtom` interface rather than the `Atom` concrete class:
         - Catches every Clojure atom (they all implement IAtom).
         - Excludes delays, promises, futures, refs, agents, and
           custom reify-IDeref proxies — none implement IAtom.
         - Robust to any future atom-like subtype that implements
           the interface.
       DO NOT relax this to `IDeref`: that would match the
       procedural-icon delays (edit-icon, back-icon, forward-icon)
       and `reload-theme!` would try to `reset!` them, which errors."
      []
      (for [[sym v] (ns-publics 'llm-memory.ui.resources)
            :let   [val (var-get v)]
            :when  (instance? clojure.lang.IAtom val)
            :let   [coord (var-name->theme-key sym)]
            :when  coord]
        (into [sym val] coord)))
    ```

    This walk is used by `apply-theme-startup!`, `reload-theme!`, and
    `dispose-registry!` — three call sites, one source of truth (the ns
    itself). No companion registry map exists or needs to be maintained;
    adding a new theme atom in `resources.clj` + an EDN entry is the
    complete change-set.

### Icon builders (§"Key design decisions" #7)

18. **Bundled defaults table** — hard-coded in `theme.clj`:

    ```clojure
    (def ^:private bundled-icons
      {:app          {:hidpi? false
                      :src    "branding/icons/png/winze-icon-16.png"}
       :statusbar    {:hidpi? true
                      :src-1x "branding/statusbar/macos/winzeTemplate.png"
                      :src-2x "branding/statusbar/macos/winzeTemplate@2x.png"}
       :tab-document {:hidpi? true
                      :src-1x "branding/ui/png/winze-tab-document-16.png"
                      :src-2x "branding/ui/png/winze-tab-document-32.png"}
       :header       {:hidpi? false
                      :src    "branding/header/winze-wordmark-slogan-dark.png"}})
    ```

19. `open-icon-stream [source errors-atom bundled-path]` — helper that
    takes either a `java.io.File` (user dir) or `nil` (fall back to
    classpath). Returns a fresh `InputStream`. If the user File exists but
    can't be opened, log + push an error into `errors-atom` and fall back
    to the classpath. **Stream is caller-closed** (wrapped in
    `with-open`).

    ```clojure
    (defn- open-icon-stream ^java.io.InputStream [^java.io.File user-file bundled-path errors-atom label]
      (or (when (and user-file (.isFile user-file))
            (try
              (java.io.FileInputStream. user-file)
              (catch Throwable t
                (swap! errors-atom conj (str "theme.edn icon " label ": " (.getMessage t)))
                nil)))
          (.getResourceAsStream (clojure.lang.RT/baseLoader) bundled-path)))
    ```

20. `resolve-icon-source [user-entry bundled-entry slot-key]` — returns
    either a `java.io.File` (if user override exists under
    `~/.winze/icons/` and passes basic existence check) or `nil` (meaning
    "use bundled"). Checks: user-entry non-nil, slot-key filename non-nil,
    file exists + `.isFile`, filename has no path separators (defense in
    depth — parse-time validation already rejects these).

21. `build-icon! [display user-entry bundled-entry errors-atom label]` —
    dispatches on `:hidpi?`:

    - Non-HiDPI: one slot (`:src`). Open stream → `(ui (Image. display stream))` → `.close`.
    - HiDPI: build an `ImageDataProvider` that returns pre-computed
      `ImageData` for 1x and 2x. Critical detail — streams are single-use,
      so resolve to `ImageData` *once* up front:

      ```clojure
      (let [data-1x (with-open [s (open-icon-stream ...)] (ImageData. s))
            data-2x (with-open [s (open-icon-stream ...)] (ImageData. s))]
        (ui (Image. display
                    (reify ImageDataProvider
                      (getImageData [_ zoom]
                        (if (>= zoom 200) data-2x data-1x))))))
      ```

    Any exception during construction → error into `errors-atom` + fall
    back to bundled-only construction. If bundled construction also fails
    (shouldn't happen in a well-formed build), return `nil` — the registry
    entry for that icon will be missing and consumers that deref it will
    see `nil`.

22. `build-swt-resources! [display theme errors-atom]` —
    on the UI thread. Takes the caller's `errors-atom` (so all upstream
    parse/load errors and downstream icon-fallback errors end up in a
    single collection) and **mutates it**. Returns a sectioned map
    keyed exactly the way `var-name->theme-key` produces — so each
    theme atom's `[section key]` coordinate looks the value up directly:

    ```clojure
    {:colors {:lavender     <Color>
              :amethyst     <Color>
              :royal-purple <Color>
              :indigo       <Color>
              :deep-amethyst <Color>
              ...}
     :fonts  {:body      <Font>
              :body-bold <Font>
              :h1        <Font>
              ...}
     :icons  {:app          <Image>
              :statusbar    <Image>
              :tab-document <Image>
              :header       <Image>}}
    ```

    **No `:hex/…` section.** The hex string is derived on demand from
    the `Color` object (see #23 below). This eliminates a parallel
    storage path and removes any possibility of drift between SWT
    `Color` state and hex-string state — there is one source of truth.

    (Errors accumulate into `errors-atom`, not the return map.)

    All SWT construction happens inside `(ui ...)`. Icons resolve each
    src slot via `resolve-icon-source` then `build-icon!`. The bundled
    table ensures every icon key is present in the output.

    **Completeness check** (collapses into the reload walk — no separate
    pre-pass). `apply-theme-startup!` and `reload-theme!` iterate
    `(theme-atoms)` and look up each `[section key]` in the map this
    function returns. If a theme atom has no corresponding value:
    - Push an error into `errors-atom`:
      `"theme: no bundled default for <section>/<key> — resources/theme.edn and resources.clj are out of sync"`.
    - Install a magenta sentinel into the atom so the screen paints
      hot-pink at the bad slot (loudly visible rather than silently
      `nil`). Construction:
      `(ui (.getSystemColor display SWT/COLOR_MAGENTA))` — this is a
      system color; it is NOT owned by the app (SWT manages its
      lifecycle), so the disposal walk must also skip "system" Colors.
      The simplest way is to tag the sentinel path via an explicit
      set `@sentinel-resources`, populated when sentinels are installed
      and checked at disposal time. Or, more pragmatically, use
      `(ui (Color. display 0xFF 0x00 0xFF))` — an owned Color, disposed
      normally on the next reload. Pick the owned-Color variant to
      keep disposal symmetric.

    The "`ns-publics walk looking for ThemeRef proxies`" completeness
    design from the earlier IDeref-proxy draft is **obsolete** — since
    every theme atom's key is recoverable from its var name, the
    reload walk IS the completeness check.

23. `hex [k]` (public helper in `llm-memory.ui.theme`) — returns the
    current hex string for a color key, derived on demand from the
    live `Color` object. **No hex registry.**

    ```clojure
    (defn hex
      "Return the current hex string (`#RRGGBB`) for a color key (e.g. `:lavender`).
       Derives from the live `Color` via .getRed/Green/Blue — no parallel
       hex storage, so no drift between SWT state and hex state.
       Returns nil if the color atom is uninitialised or the key is unknown.
       Safe to call from any thread (Color getter methods are pure reads)."
      [k]
      (when-let [var-ref (ns-resolve 'llm-memory.ui.resources
                                     (symbol (str "color-" (name k))))]
        (when-let [^Color c @@var-ref]
          (format "#%02X%02X%02X" (.getRed c) (.getGreen c) (.getBlue c)))))
    ```

    Consumers: `search.clj` `page-css` (change `colors` from `def` to a
    zero-arg `fn` that reads via `theme/hex` at call time); `find_replace.clj`
    JS-injection string (interpolate `(theme/hex :lavender)` etc. via `str`
    at emission time — see Step 7e).

**Tests (RCF)**: light coverage —
- Construct one Color, assert not disposed; dispose it.
- `resolve-icon-source` with a user-dir File that doesn't exist → `nil`.
- `resolve-icon-source` with a filename containing `/` → `nil` (safety).
- `open-icon-stream` falls back to classpath when user File is `nil`.
- Build all 4 icons from bundled-only input; verify each is an `Image` and
  not disposed.

---

## Step 6 — Apply-theme orchestration (first-load + reload)

Two entry points are needed: one for **startup** (runs synchronously inside
the first `application` init form, before any widget is constructed) and
one for **reload** (runs from the command-palette action, must close
transient popups + broadcast refresh + defer disposal).

In `llm-memory.ui.theme`:

24. `close-transient-shells! []` — close the find bar, content-assist
    popup, link-preview Shell, **and command palette** if open. Exact
    helpers:
    [`find-replace/close-find-bar!`](../../../winze-server/src/llm_memory/ui/find_replace.clj)
    guarded by `find-replace/find-bar-open?`;
    [`content-assist/close-content-assist!`](../../../winze-server/src/llm_memory/ui/content_assist.clj#L704)
    guarded by `content-assist/popup-open?`;
    [`link-preview/hide-preview!`](../../../winze-server/src/llm_memory/ui/link_preview.clj)
    guarded by `link-preview/preview-open?`;
    `command-palette/close-palette!` guarded by
    `command-palette/palette-open?` (add these helpers if they don't
    exist — see Step 7b).
    Safe to call when nothing is open. Rationale: CONTEXT.md §"Risks" — we
    don't restyle transient popups; we close them so no widget holds a
    disposed `Color` after reload.

25. `apply-theme-startup! [display]` — the **first-load** entry point.
    Runs synchronously on the UI thread (caller must be on UI thread or
    call via `sync-exec!`). Populates every theme atom; returns
    `{:errors [...]}`. Does NOT dispose anything (nothing to dispose on
    first load) and does NOT broadcast (no widgets exist yet).

    ```clojure
    (defn apply-theme-startup!
      "First-load entry point. Must be called on the UI thread, before any
       widget that dereferences theme resources is constructed (e.g. before
       (application ...) builds the shell).  Returns {:errors [...]} —
       caller shows the MessageBox after the main window is visible."
      [display]
      (let [{:keys [theme errors]}  (load-theme!)
            errors-atom              (atom (vec errors))
            resources-map            (build-swt-resources! display theme errors-atom)]
        ;; Walk every theme atom in resources.clj, reset! its value from
        ;; resources-map. The walk is the completeness check: missing
        ;; entries install a magenta sentinel + push an error.
        (doseq [[sym atom-ref section key] (resources/theme-atoms)]
          (if-let [v (get-in resources-map [section key])]
            (reset! atom-ref v)
            (do (swap! errors-atom conj
                       (format "theme: no bundled default for %s/%s (%s) — resources/theme.edn and resources.clj are out of sync"
                               (name section) (name key) sym))
                (reset! atom-ref (ui (Color. display 0xFF 0x00 0xFF))))))
        {:errors @errors-atom}))
    ```

26. `reload-theme! []` — the **reload** entry point (renamed from the
    earlier `apply-theme!` to avoid colliding with
    [`md-editor/apply-theme!`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L951),
    which applies per-span styling on every `modifyText`). Assumes the
    registry is already populated and widgets are live.

    ```clojure
    (defn reload-theme!
      "Re-read theme.edn, build new SWT resources on the UI thread, swap
       each theme atom's value, broadcast refresh, dispose the old
       resources. Returns a vector of error strings (empty on clean load)."
      []
      (let [{:keys [theme errors]} (load-theme!)
            errors-atom             (atom (vec errors))
            ;; Snapshot current atom values BEFORE swapping; needed to
            ;; dispose the old Resources after widgets re-apply new ones.
            old-values              (into {} (for [[_sym a _s _k] (resources/theme-atoms)]
                                               [a @a]))
            ;; Build FIRST, outside the atom swaps and without touching
            ;; widgets, so a mid-construction throw (e.g. an Image load
            ;; that died after N Fonts succeeded) leaks only the
            ;; partially-built new Resources — the LIVE atoms stay
            ;; intact, widgets keep painting, and no transient popup
            ;; gets force-closed for a reload that isn't going to land.
            new-resources-or-ex
            (sync-exec!
             (fn []
               (try
                 (build-swt-resources! @display theme errors-atom)
                 (catch Throwable t
                   (log/error t "Theme reload: build-swt-resources! threw")
                   (swap! errors-atom conj
                          (str "theme: build failed — " (.getMessage t)))
                   t))))]
        (if (instance? Throwable new-resources-or-ex)
          ;; Build failed wholesale. Keep the old atom values in place so
          ;; widgets continue painting. Do NOT dispose anything. Do NOT
          ;; close transient popups — they still hold live Resources.
          @errors-atom
          (do
            ;; Only now, with a valid new resources map in hand, disturb
            ;; live widgets: close transients, swap each atom, broadcast.
            (sync-exec! close-transient-shells!)
            (doseq [[sym atom-ref section key] (resources/theme-atoms)]
              (if-let [v (get-in new-resources-or-ex [section key])]
                (reset! atom-ref v)
                (do (swap! errors-atom conj
                           (format "theme: no bundled default for %s/%s (%s)"
                                   (name section) (name key) sym))
                    (reset! atom-ref (ui (Color. @display 0xFF 0x00 0xFF))))))
            (broadcast-theme-refresh!)
            ;; Defer disposal until after widgets have re-applied new colors.
            (async-exec! #(dispose-old-values! old-values))
            @errors-atom))))
    ```

    Notes on partial failure:
    - If parsing produced zero valid entries (all-errors case), the
      bundled defaults still populated `:theme`, so `build-swt-resources!`
      has something to work with and every atom gets a value.
    - If `build-swt-resources!` *throws* mid-construction (a rare case —
      e.g. disk-corrupt icon PNG that gets past validation), we catch in
      `sync-exec!`, leak the partially-built Resources (acceptable — they
      were never attached to an atom), preserve the old atom values, and
      surface the error to the MessageBox. Widgets never paint with `nil`.
    - Individual per-slot icon failures do NOT reach this path: Step 5's
      `build-icon!` catches its own exceptions and falls back to bundled,
      pushing the error into `errors-atom` but returning a valid Image.

27. `dispose-old-values! [old-values]` — iterate values; for each
    `Resource` (Color, Font, Image) that's not disposed, `.dispose`. Log
    failures. `old-values` is the pre-swap snapshot from `reload-theme!`;
    disposing is safe because every widget has already been restyled
    via `broadcast-theme-refresh!` before the `async-exec!` callback
    fires (FIFO event queue).

    ```clojure
    (defn- dispose-old-values! [old-values]
      (doseq [[_ v] old-values
              :when (and (instance? Resource v)
                         (not (.isDisposed ^Resource v)))]
        (try (.dispose ^Resource v)
             (catch Throwable t
               (log/warn t "Failed to dispose old theme resource")))))
    ```

Ordering matters (see CONTEXT §"Key design decisions" #6 — Disposal
ordering on reload): close transient popups → construct new → swap
registry → broadcast restyle → async-exec dispose old. With all refresh
listeners enqueueing their SWT mutations via `async-exec!`, the FIFO
`asyncExec` queue guarantees new styles are applied before old resources
are disposed.

---

## Step 6.5 — (REMOVED — consolidated into `markdown-editor-widget` plan)

Earlier drafts bundled a `toggle-mode!` → CDT-init consolidation into this
work. That refactor is substantial on its own and already has an active
work item with its own rigorous plan:

- [`todo/MARKDOWN-EDITOR-WIDGET-CONTEXT.md`](../MARKDOWN-EDITOR-WIDGET-CONTEXT.md)
- [`todo/MARKDOWN-EDITOR-WIDGET-PLAN.md`](../MARKDOWN-EDITOR-WIDGET-PLAN.md)

### Dependency ordering

`markdown-editor-widget` **should land before** (or at latest, in parallel
with) this theme work. Once it does, Step 7a's theme-refresh listener
lives inside the `markdown-editor` CDT init, co-located with
`install-spellcheck!` — a single registration point.

### If `markdown-editor-widget` has NOT landed when this work starts

Register the theme-refresh listener twice — once in the dead-code
`markdown-editor` CDT init (for future reuse) and once at the bottom of
the hand-rolled editor construction block in `toggle-mode!`
([main_window.clj:521-549](../../../winze-server/src/llm_memory/ui/main_window.clj#L521-L549)).
Both locations have access to the StyledText they construct. Mark a
`FIXME(theme-externalize-edn):` breadcrumb next to the duplicate
registration so the later `markdown-editor-widget` lander knows to
collapse them. The listener body is identical in both places (see
Step 7a).

This keeps the two plans decoupled — theme work does NOT have to wait on
the editor refactor, and does NOT absorb its scope. The cost is a single
duplicate listener registration that gets deleted whenever the editor
refactor lands.
- Screenshot-verify (§15).

---

## Step 7 — Refresh-listener broadcast (mirror spellcheck)

**Edit**: `llm-memory.ui.theme` (or `resources.clj` — whichever owns the
registry; recommend `theme.clj` to keep concerns tidy).

Follow [`spellcheck.clj:476-498`](../../../winze-server/src/llm_memory/ui/spellcheck.clj#L476-L498):

```clojure
(defonce refresh-listeners (atom #{}))

(defn register-refresh-listener! [f]
  (swap! refresh-listeners conj f) f)

(defn unregister-refresh-listener! [f]
  (swap! refresh-listeners disj f))

(defn- broadcast-theme-refresh! []
  (doseq [f @refresh-listeners]
    (try (f) (catch Throwable t
              (log/warn t "Theme refresh listener failed")))))
```

Now wire listeners from each widget that paints with the theme. Register in
the widget's init (or `toggle-mode!` for editors), and **unregister on
widget dispose**.

### 7a — Markdown editor (StyledText + wrapper Composite)

Register the listener immediately after the StyledText is constructed,
alongside the spellcheck listener installed by `install-spellcheck!` at
[`markdown_editor.clj:544`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L544).
If the `markdown-editor-widget` refactor has already landed (see Step 6.5),
there is one registration point — inside the CDT init. If it has NOT
landed, register in BOTH the CDT init (for future reuse) and the
hand-rolled block in `toggle-mode!`
([main_window.clj:521-549](../../../winze-server/src/llm_memory/ui/main_window.clj#L521-L549)) —
with a `FIXME(theme-externalize-edn):` breadcrumb on the duplicate so the
editor refactor can collapse them when it lands.

Two widgets need restyling: the StyledText and its wrapper Composite
([main_window.clj:527-529](../../../winze-server/src/llm_memory/ui/main_window.clj#L527-L529)).
The listener reaches the wrapper via `(.getParent st)` — both the CDT-init
path and the `toggle-mode!` path have wrapper-as-parent (see CONTEXT
§"Consumers" design consequence #2):

```clojure
(let [theme-tok (theme/register-refresh-listener!
                 (fn []
                   (async-exec!
                    (fn []
                      (when-not (.isDisposed st)
                        (let [wrapper (.getParent st)]
                          (when (and wrapper (not (.isDisposed wrapper)))
                            (.setBackground wrapper @resources/color-mine-shaft)))
                        (.setFont st                 @resources/body-font)
                        (.setBackground st           @resources/color-mine-shaft)
                        (.setForeground st           @resources/color-crystal-white)
                        (.setSelectionBackground st  @resources/color-royal-purple)
                        (.setSelectionForeground st  @resources/color-pure-white)
                        ;; Re-run the markdown restyle pass with the new Colors/Fonts.
                        (apply-theme! st (.getText st)))))))]
  (on e/widget-disposed [props parent event]
      (theme/unregister-refresh-listener! theme-tok)))
```

Notes:
- `apply-theme!` in this namespace takes `[st text]` — NOT `[parent st text]`. Verify against [markdown_editor.clj:951](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L951).
- The wrapper Composite is always the StyledText's parent (both the CDT-init path at [markdown_editor.clj:940](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L940) and the `toggle-mode!` path at [main_window.clj:528-531](../../../winze-server/src/llm_memory/ui/main_window.clj#L528-L531)). Using `.getParent` covers both.
- Listener guards with `isDisposed` so that a stale listener (widget gone but not yet unregistered) no-ops instead of crashing.

### 7b — Command palette shell

The command palette is a **transient shell** freshly constructed per
`open-palette!` (it reads theme values at construction time — see
[command_palette.clj:146-175](../../../winze-server/src/llm_memory/ui/command_palette.clj#L146-L175)).
Treat it the same as the find-bar / content-assist / link-preview popups:
**force-close on reload, rebuild on next open.** Add `close-palette!`
(guarded by `palette-open?`) to [`command_palette.clj`](../../../winze-server/src/llm_memory/ui/command_palette.clj)
if it doesn't already exist, then call it from `close-transient-shells!`
(Step 6 #24). No refresh listener — the next `open-palette!` reads the
updated registry.

Rationale: consistency with every other transient popup in the app.
Reload is an explicit, infrequent user action; a one-frame close of a
popup that the user just invoked Reload Theme from is harmless.

### 7c — Browser-rendered HTML (search.clj + file-view Browsers)

**Require the theme namespace.** Add to
[`search.clj`'s `:require` list](../../../winze-server/src/llm_memory/ui/search.clj#L1-L15)
(currently does NOT depend on `theme.clj`):

```clojure
[llm-memory.ui.theme :as theme]
```

**Rewrite `search.clj` colors source.** Replace the static
[`def ^:private colors` at search.clj:21-32](../../../winze-server/src/llm_memory/ui/search.clj#L21-L32)
with a zero-arg fn that reads the current hex strings from the theme
registry at call time:

```clojure
(defn- colors []
  {:lavender      (theme/hex :lavender)
   :amethyst      (theme/hex :amethyst)
   :deep-violet   (theme/hex :deep-violet)
   :royal-purple  (theme/hex :royal-purple)
   :indigo        (theme/hex :indigo)
   :deep-amethyst (theme/hex :deep-amethyst)
   :obsidian      (theme/hex :obsidian)
   :mine-shaft    (theme/hex :mine-shaft)
   :bedrock       (theme/hex :bedrock)
   :crystal-white (theme/hex :crystal-white)
   :pure-white    (theme/hex :pure-white)})
```

Update every callsite inside `page-css` (the only consumer — every
reference to `colors` in `search.clj` today is a map lookup of the form
`(:kw colors)` inside CSS string interpolation) from `(:lavender colors)`
to `(:lavender (colors))` — i.e. call the fn. `page-css` is already a
`defn` called fresh on each Browser render, so adding one more function
call is zero structural churn.

**Re-render the live-search Browser on reload.** Register a theme refresh
listener in `main_window.clj` `defmain` after `apply-theme-startup!` has
run. On refresh, re-derive the current content and call
`set-live-search-content!`:

**Public surface in `search.clj` (verified against current code):**

- `search/home-page` at [search.clj:513](../../../winze-server/src/llm_memory/ui/search.clj#L513) — builds the synthetic home-page HTML (no args).
- `search/results` at [search.clj:561](../../../winze-server/src/llm_memory/ui/search.clj#L561) — debounced search → HTML → Browser.
- `search/file-page` at [search.clj:453](../../../winze-server/src/llm_memory/ui/search.clj#L453) — `[markdown-text file-path & [metadata root-uri]]` → HTML. This IS the file-view renderer: `open-file-in-tab!` already uses it at [main_window.clj:142](../../../winze-server/src/llm_memory/ui/main_window.clj#L142), `set-live-search-content!`'s file-mode path uses it via `search/home-page`'s `{:mode :file :html …}` map, and `on-file-changed` re-renders via it at [main_window.clj:328, 393](../../../winze-server/src/llm_memory/ui/main_window.clj#L328). Use it here too.
- `search/file-metadata-by-path` (searchable in search.clj) — `[root-uri rel-path]` → metadata map (may be nil). Required as the third arg to `file-page` so the file-header card renders correctly.

```clojure
(theme/register-refresh-listener!
  (fn []
    (async-exec!
     (fn []
       (let [{:keys [mode abs-path]}
             @resources/live-search-state

             {:keys [query]}
             (or @resources/last-search-query {})]
         (cond
           ;; Synthetic mode with an active query → re-run the query.
           (and (= mode :synthetic) (seq query))
           (search/results query
                           (element :live-search-browser)
                           set-live-search-content!)

           ;; Synthetic mode with no query → show home page.
           (= mode :synthetic)
           (set-live-search-content! (search/home-page))

           ;; File mode — re-render via search/file-page. The
           ;; live-search-state carries :abs-path; open-files carries
           ;; :rel-path + :root-uri needed to resolve metadata and
           ;; rewrite wiki links.
           (and (= mode :file) abs-path)
           (when-let [{:keys [rel-path root-uri]}
                      (get @resources/open-files abs-path)]
             (let [content  (slurp abs-path)
                   metadata (when root-uri
                              (search/file-metadata-by-path root-uri rel-path))
                   html     (search/file-page content rel-path metadata root-uri)]
               (set-live-search-content!
                {:mode :file :abs-path abs-path
                 :root-uri root-uri :rel-path rel-path :html html})))))))))
```

**File-view-mode Browsers (other file tabs).** Walk `@open-files`; for
each `:view`-mode entry, re-invoke `search/file-page` with the same args
`open-file-in-tab!` passed originally:

```clojure
(doseq [[abs-path {:keys [mode rel-path root-uri tab-ids]}] @resources/open-files
        :when (= mode :view)
        tab-id tab-ids
        :let  [browser (get @app-props tab-id)]
        :when (and browser (not (.isDisposed browser)))]
  (let [content  (slurp abs-path)
        metadata (when root-uri (search/file-metadata-by-path root-uri rel-path))
        html     (search/file-page content rel-path metadata root-uri)]
    (.setText browser html)))
```

Verify end-to-end via Step 12 #11 (screenshot live-search in
synthetic+query, synthetic+home, and file-view states; confirm each
re-renders after Reload Theme).

### 7d — File-based icons (main shell, header label, tray-item, CTabItems)

Icons set via `setImage` at construction need re-setting on reload. **Four**
widget types hold icon references:

- **Main application shell**: [main_window.clj:1113-1115](../../../winze-server/src/llm_memory/ui/main_window.clj#L1113-L1115) — `(shell SWT/SHELL_TRIM (id! :ui/main-window) :image @app-icon …)`. The shell is the app's taskbar/dock icon; without this listener, reload leaves the dock painting a disposed `Image`. Reachable via `(element :main-window)`:

  ```clojure
  (theme/register-refresh-listener!
    (fn []
      (async-exec!
       (fn []
         (when-let [sh (element :main-window)]
           (when-not (.isDisposed sh)
             (.setImage sh @resources/app-icon)))))))
  ```

- **Header logo label**: [main_window.clj:722](../../../winze-server/src/llm_memory/ui/main_window.clj#L722) — `(label :image @res/header-image)` inside `(header)`. Assign an `id!` to this label (e.g. `:ui/header-logo`) so it's reachable via `mw/element`. Refresh listener:

  ```clojure
  (theme/register-refresh-listener!
    (fn []
      (async-exec!
       (fn []
         (when-let [lbl (element :header-logo)]
           (when-not (.isDisposed lbl)
             (.setImage lbl @resources/header-image)))))))
  ```

- **System tray item**: [main_window.clj:859-860](../../../winze-server/src/llm_memory/ui/main_window.clj#L859-L860). Capture the `TrayItem` into `app-props` (give it an `id!`) then:

  ```clojure
  (when-let [ti (element :tray-item)]
    (.setImage ti @resources/statusbar-icon)
    (.setHighlightImage ti @resources/statusbar-icon))
  ```

- **Open CTabItems**: three kinds of tabs (CONTEXT design decision #11):
  file tabs use `@tab-document-icon`; live-search and search-result tabs use
  `@statusbar-icon`. Runtime discriminator:

  1. The **live-search tab** is the one whose control is the fixed
     `:ui/live-search-wrapper` Composite. Always `statusbar-icon`,
     independent of whether the wrapper currently renders a file
     (via `set-live-search-content!`) or the synthetic page.
  2. A **file tab** has its control registered as a `wrapper-id` value in
     `@res/open-files` *and* that control is NOT the live-search wrapper.
  3. All other tabs are **search-result tabs** → `statusbar-icon`.

  ```clojure
  (let [live-wrapper (get @app-props :ui/live-search-wrapper)
        file-tab-wrappers (->> (vals @resources/open-files)
                               (keep :wrapper-id)
                               (map #(get @app-props %))
                               (remove #(identical? % live-wrapper))
                               set)]
    (doseq [item (.getItems (element :main-folder))]
      (when-not (.isDisposed item)
        (let [ctrl (.getControl item)
              icon (if (contains? file-tab-wrappers ctrl)
                     @resources/tab-document-icon
                     @resources/statusbar-icon)]
          (.setImage item icon)))))
  ```

  This catches all three cases correctly: the live-search tab is
  explicitly excluded from file-tab-wrappers even when `open-files`
  transiently registers its wrapper (file-view mode), and search-result
  tabs fall through the default.

Register all four listeners in `defmain` after `(apply-theme-startup!)`
has run, before widgets paint. Each registers a 0-arg callback and,
because the shell/header/tray/folder live for the whole app, no
unregister is needed (they dispose at shutdown alongside the shell).

### 7e — Find-bar JavaScript injection (hex interpolation at emit time)

[`find_replace.clj:158-206`](../../../winze-server/src/llm_memory/ui/find_replace.clj#L158-L206)
defines `browser-highlight-js` as a **`def` of a single JS template string**
containing four hardcoded hex literals at lines 194-195:

```
mark.style.background = (i === currentIdx) ? '#C4B8FF' : '#5548A0';
mark.style.color = (i === currentIdx) ? '#0E0D18' : '#E8E0FF';
```

The same string ALSO contains three format placeholders (`'%s'`, `%s`, `%d`
for query / case-sensitive / current-idx at line 206), which the caller at
[:222](../../../winze-server/src/llm_memory/ui/find_replace.clj#L222) fills
in via `(format browser-highlight-js query cs idx)`.

**Don't add more `%s` placeholders.** Adding format tokens for the hex
values would break the call-site's arity and require double-escaping any
future literal `%` in the JS. Instead, convert `browser-highlight-js`
from a `def` to a zero-arg `defn` that reads `theme/hex` at call time
and bakes the hex in while **leaving `%s`/`%s`/`%d` intact** for the
outer format:

```clojure
(defn- browser-highlight-js []
  ;; Rebuilt on every call so theme reloads pick up new hex.
  ;; Leaves '%s', %s, %d placeholders for the outer (format …) call.
  (str "return (function(q, cs, currentIdx) {
          …
          mark.style.background = (i === currentIdx) ? '" (theme/hex :lavender) "' : '" (theme/hex :royal-purple) "';
          mark.style.color      = (i === currentIdx) ? '" (theme/hex :bedrock)   "' : '" (theme/hex :crystal-white)  "';
          …
        })('%s', %s, %d);"))
```

Using `str` (not nested `format`) means there is only ONE `%`-escape
pass — no need to double-escape the JS literals, and no ordering
coupling between hex and the query/case/idx args.

Update the call site:

```clojure
;; BEFORE: (format browser-highlight-js query cs idx)
;; AFTER:
(format (browser-highlight-js) query cs idx)
```

**Require the theme namespace.** Add to
[`find_replace.clj`'s `:require` list](../../../winze-server/src/llm_memory/ui/find_replace.clj)
(currently does NOT depend on `theme.clj`):

```clojure
[llm-memory.ui.theme :as theme]
```

No refresh listener needed here — the find bar is a **transient popup**
that `close-transient-shells!` force-closes at the start of
`reload-theme!`. The next time the user opens it, the injected JS is
freshly built via `(browser-highlight-js)` with the new hex strings.

**Known cosmetic limitation** (worth documenting, not worth fixing in
v1): if the user had marks highlighted in a viewer Browser and closed
the find bar BEFORE running Reload Theme, the stale `<mark>` elements
persist with old hex until the next search injects fresh JS. Harmless
(no disposed-Color crash — these are CSS strings, not SWT Colors).

### 7f — Other transient popups

Content assist and link preview popups are similarly force-closed by
`close-transient-shells!`. They pick up the new theme the next time the
user opens them — no listener needed. See CONTEXT.md §"Risks".

Keep each refresh callback **local** to the widget it refreshes — don't
centralize widget-walking logic. Use `mw/element :key` to fetch live widgets
(§18), never call CDT init fns.

---

## Step 8 — Register `:workbench/reload-theme` command

**Edit**: [`main_window.clj:947`](../../../winze-server/src/llm_memory/ui/main_window.clj#L947) (`register-workbench-commands!`).

Add:

```clojure
(commands/register!
 {:id       :workbench/reload-theme
  :label    "Reload Theme"
  :category :workbench
  :action   (fn []
              (async-exec!
               (fn []
                 (let [errors (theme/reload-theme!)]
                   (when (seq errors)
                     (show-theme-errors! errors))))))})
```

No keybinding needed (command-palette-only per the brief). Palette finds
it automatically via the registry.

---

## Step 9 — `show-theme-errors!` MessageBox

**Edit**: `main_window.clj`. Mirror `show-lang-errors!` ([`main_window.clj:898-910`](../../../winze-server/src/llm_memory/ui/main_window.clj#L898-L910)):

```clojure
(defn- show-theme-errors!
  "Show a MessageBox listing theme.edn validation errors, if any."
  [errors]
  (when (seq errors)
    (let [mb (MessageBox. (element :main-window)
                          (| SWT/ICON_WARNING SWT/OK))]
      (.setText mb "Theme File Errors")
      (.setMessage mb
                   (str "Some entries in theme.edn failed to load:\n\n"
                        (str/join "\n" errors)
                        "\n\nAffected entries will use bundled defaults."))
      (.open mb))))
```

Uses the exact shape of `show-lang-errors!` — same `MessageBox` constructor,
same `| SWT/ICON_WARNING SWT/OK` style.

---

## Step 9.5 — REPL / observability helpers + rollback + validation commands

### `theme/current-palette`

Small public helper in `llm-memory.ui.theme` that returns the currently
live color palette as a plain `{kw "#RRGGBB"}` map — useful at the REPL
for debugging "what colors is the app actually painting with?".

```clojure
(defn current-palette
  "Return the currently live color palette as {kw \"#RRGGBB\"}.
   Walks the ns-publics color atoms and derives hex via `theme/hex`."
  []
  (into (sorted-map)
        (for [[sym _a section key] (resources/theme-atoms)
              :when (= section :colors)]
          [key (hex key)])))
```

### `theme/validate-user-file`

Public helper that reads `~/.winze/theme.edn` and runs it through the
full `read-theme-source` → `parse-theme` pipeline **without touching the
registry**. Returns `{:theme merged :errors [...]}`. Pure — safe to call
from any thread.

```clojure
(defn validate-user-file
  "Read ~/.winze/theme.edn and validate it without touching the
   registry. Returns {:theme merged :errors [...]}.
   Use from the REPL or from the `:workbench/validate-theme` command."
  []
  (load-theme!))  ; reuses Step 3 #14 — merge already runs both parse passes
```

### `:workbench/validate-theme` command

Register alongside `:workbench/reload-theme` in `register-workbench-commands!`:

```clojure
(commands/register!
 {:id       :workbench/validate-theme
  :label    "Validate Theme"
  :category :workbench
  :action   (fn []
              (async-exec!
               (fn []
                 (let [{:keys [errors]} (theme/validate-user-file)]
                   (if (seq errors)
                     (show-theme-errors! errors)
                     (let [mb (MessageBox. (element :main-window)
                                           (| SWT/ICON_INFORMATION SWT/OK))]
                       (.setText mb "Theme OK")
                       (.setMessage mb "~/.winze/theme.edn parsed successfully.")
                       (.open mb)))))))})
```

Reuses `show-theme-errors!` from Step 9 for the failure case.

### `:workbench/reset-theme` command (rollback)

Register a rollback command that deletes `~/.winze/theme.edn` (after
user confirmation) and runs `reload-theme!`. The next load reinstalls
the bundled default via `install-default-theme-if-missing!` in Step 3.

```clojure
(commands/register!
 {:id       :workbench/reset-theme
  :label    "Reset Theme to Default"
  :category :workbench
  :action   (fn []
              (async-exec!
               (fn []
                 (let [mb (MessageBox. (element :main-window)
                                       (| SWT/ICON_QUESTION SWT/YES SWT/NO))]
                   (.setText mb "Reset Theme?")
                   (.setMessage mb
                                (str "This deletes ~/.winze/theme.edn and "
                                     "reverts to the bundled default.\n\n"
                                     "Your current theme.edn is NOT backed up. "
                                     "Proceed?"))
                   (when (= SWT/YES (.open mb))
                     (.delete (theme/user-theme-file))
                     (let [errors (theme/reload-theme!)]
                       (when (seq errors)
                         (show-theme-errors! errors))))))))})
```

Note: we deliberately do NOT back up the user file — users editing
theme.edn are editing a plain text file under version control (or git
or backups) of their choice; we don't want to accumulate `.bak` files
in `~/.winze/`. The confirmation MessageBox is the safety net.

---

## Step 10 — Startup integration

**Edit**: `main_window.clj` (around [`main_window.clj:1098-1193`](../../../winze-server/src/llm_memory/ui/main_window.clj#L1098-L1193)).

Theme loading must happen **before any widget dereferences a theme
resource**. The subtle constraint (CONTEXT §"Consumers" design consequence
#3) is that CDT's `shell`, `label`, `composite` etc. are **regular
functions**, not macros — their arguments are evaluated eagerly by Clojure
before the function is called:

- `(shell SWT/SHELL_TRIM … :image @app-icon …)` forces `@app-icon` when
  `shell` is evaluated as a vararg to `application`.
- `(header)` runs `(composite … (label :image @header-image) …)` which
  forces `@header-image` at the same time.

By the time `application`'s `run-inits` invokes its init forms, those
calls have already happened. A "first init form" scheme does **not**
help — the shell/label init fns have already closed over `nil`-image
values captured from the empty registry.

The only correct placement is to call `(theme/apply-theme-startup!
@display)` **before the `(application …)` form is evaluated at all** —
i.e. at the top of `main-window`, after `register-about-handler!` but
before the `let result (application …)`:

```clojure
(defn main-window
  "Main application window.
  Returns nil on normal shutdown. CDT's `application` silently catches all
  Throwables and returns them — we log here so UI init failures aren't lost."
  []
  (register-about-handler!)

  ;; *** Populate theme registry BEFORE `(application …)` is evaluated. ***
  ;; `(shell :image @app-icon …)` and `(label :image @header-image)` force
  ;; their derefs as eager args — waiting until a first-init-form to
  ;; populate the registry is too late (CONTEXT §"Consumers" #3).
  ;;
  ;; INVARIANT — DO NOT MOVE `apply-theme-startup!` AFTER `(application …)`.
  ;; @display is reset by server/main.clj before main-window runs
  ;; (see server/main.clj:436 `(reset! ui.SWT/display …)`). If a future
  ;; edit reorders server/main.clj and @display is nil at this call,
  ;; `build-swt-resources!` will throw on `(Color. @display …)` and the
  ;; server will crash on startup. That's the intended failure mode —
  ;; loud crash beats silent nil-icon startup. If the crash occurs, fix
  ;; the @display init order in server/main.clj — don't defer this call.
  (let [{startup-errors :errors} (theme/apply-theme-startup! @display)
        result (application
                (tray-item2 ...)
                (shell SWT/SHELL_TRIM (id! :ui/main-window)
                       :text app-name
                       :image @app-icon      ; ← now resolves to a real Image
                       ...)
                (defmain [props parent]
                  (reset! app-props @props)   ; MUST come before show-*-errors!
                  ...
                  (register-workbench-commands!)
                  ...
                  ;; Theme errors appear in the same pass as lang errors.
                  ;; Both MessageBoxes parent on (element :main-window), which
                  ;; requires @app-props to be populated — that's why these
                  ;; calls come after `(reset! app-props @props)`, not before.
                  (show-lang-errors!)
                  (show-theme-errors! startup-errors)
                  ...))]
    (when (instance? Throwable result) ...)))
```

`startup-errors` is an immutable local binding — no atom needed. It
bridges the value from the pre-`application` call into `defmain` via
lexical closure.

**Why `@display` is valid here**: `server/main.clj` already calls
`(reset! ui.SWT/display (Display/getDefault))` before invoking
`main-window` — see [`server/main.clj:436`](../../../winze-server/src/llm_memory/server/main.clj#L436).
`apply-theme-startup!` can therefore rely on `@display` and
construct SWT `Color`/`Font`/`Image` resources immediately (via `(ui …)`).

**Invariant 1 — registry populated before `application`**:
`apply-theme-startup!` MUST run before the `(application …)` form is
evaluated. Any change that moves it later (e.g. into a first init form,
into `defmain`) will silently drop icons on cold start.

**Invariant 2 — error display after `app-props` is set**:
`(show-theme-errors! …)` must run AFTER `(reset! app-props @props)` in
`defmain`, because the MessageBox is parented on `(element :main-window)`
which reads from `@app-props`. Violating this order makes the MessageBox
parent-less or crashes.

Confirm before marking this step done:
- `resources.clj` no longer does SWT construction at load time (all color/font/file-icon `defonce` bodies are now `(atom nil)`, which is pure Clojure).
- `apply-theme-startup!` runs on the main thread before `(application …)`;
  the `(ui …)` wrapper inside `build-swt-resources!` handles the UI-thread
  syncExec for Color/Font/Image construction.
- `@app-icon` / `@statusbar-icon` / `@header-image` in `tray-item2`/`shell`/`(header)` return real `Image`s (not `nil`). Verify from the REPL after startup: `(instance? org.eclipse.swt.graphics.Image @llm-memory.ui.resources/app-icon)` should be `true`, and `(.isDisposed @llm-memory.ui.resources/app-icon)` should be `false`. Repeat for `statusbar-icon` and `header-image`. A `nil` registry entry would fail the `instance?` check — the signal that `apply-theme-startup!` ran too late (or not at all).
- `@color-mine-shaft` etc. in on-demand paths (`toggle-mode!`, popups) work once any widget calls them — they run after `defmain`.
- Procedural icons (`edit-icon`, `back-icon`, `forward-icon`) still resolve via their existing `defonce`-delay, untouched.

---

## Step 11 — Shutdown disposal

The existing `resources/dispose-registry!` walks `ns-publics` looking
for **realized delays** deref'ing to `Resource`. After Step 4:

- Color/Font/file-icon vars are now **atoms**. The existing
  `(delay? val)` guard filters them out, so the current body would
  silently skip every themable resource on shutdown.
- Procedural icons (`edit-icon`, `back-icon`, `forward-icon`) remain
  `defonce`-delays and must still be disposed via the same walk.

**Generalise the IDeref guard** so a single walk covers both kinds:

```clojure
(defn dispose-registry!
  "Dispose every SWT Resource owned by this namespace.
  Must run on the UI thread, before Display disposal.

  One unified walk over ns-publics. Two resource storage kinds live in
  this namespace and each has its own type-level marker:

    - IAtom  → theme-swappable resources (colors, fonts, file-based
               icons). @atom is always safe — no realization. `IAtom`
               is what `theme/reload-theme!` also keys on, so keeping
               the same discriminator here means disposal and reload
               see exactly the same set of vars.
    - Delay  → procedural icons (edit-icon, back-icon, forward-icon).
               Deref ONLY when realized — forcing an unrealized delay
               would construct a Resource just to dispose it.
    - anything else → skip. Non-IDeref public vars and non-resource
               atoms (e.g. `open-files`, `live-search-state`) pass
               through untouched.

  Why IAtom and not the concrete `Atom` class: `IAtom` is the Clojure
  interface every atom implements. Delays, promises, futures, refs,
  and agents do NOT implement it. Using the interface keeps the walk
  robust against any future atom-like type without broadening the
  match to things we don't want (delays, refs, etc.)."
  []
  (doseq [[sym v] (ns-publics 'llm-memory.ui.resources)
          :let  [val (var-get v)
                 held (cond
                        ;; IAtom — theme-swappable; always safe to deref.
                        (instance? clojure.lang.IAtom val) @val
                        ;; Delay — procedural icon; deref only if already forced.
                        (and (delay? val) (realized? val)) @val)]
          :when (and held
                     (instance? Resource held)
                     (not (.isDisposed ^Resource held)))]
    (try (.dispose ^Resource held)
         (catch Throwable t
           (log/warn t "Failed to dispose registry resource" sym))))
  ;; Atoms keep pointing at their now-disposed Resources. The app is
  ;; shutting down so no consumer observes; disposed Resources are
  ;; inert. A future change wanting a cleaner shutdown state can
  ;; `reset!` each atom to nil after disposal, but it's cosmetic.
  )
```

This walk is idempotent and safe to call concurrently with no-op effect.
It mirrors the shape of the pre-change code — the only difference is
generalising the guard from "Delay + realized" to "IAtom OR realized
Delay". Everything else (the `Resource` + `isDisposed` checks, the
`try`/`log/warn`) is unchanged.

Confirm `shell-closed` ([main_window.clj:1132](../../../winze-server/src/llm_memory/ui/main_window.clj#L1132))
still calls `dispose-registry!`; no change needed there.

---

## Step 12 — Screenshot-verify (REQUIRED — SWT-UI-GUIDE §15)

No screenshot, no sign-off.

1. `make install` from `winze-server/`, restart the Winze server.
2. From the running app: open a markdown tab. Screenshot with
   `llm-memory.ui.util/screenshot-widget!` (fully-qualified — aliases fail
   intermittently).
3. **Color reload**: edit `~/.winze/theme.edn`: change `:mine-shaft` to
   something obviously different like `"#2E0B1B"` (dark red background).
4. Run "Reload Theme" via the command palette (Cmd+Shift+P → "reload").
   Verify the binding matches what's registered for `:workbench/open-command-palette`
   in `keybindings.json` (do NOT assume — check first).
5. Screenshot again — verify the editor background AND the wrapper
   margin changed (both must update; see Step 7a).
6. **Header logo icon reload**: drop a PNG (any image — even a solid
   color swatch will do) named e.g. `my-wordmark.png` into `~/.winze/icons/`,
   add `:icons {:header {:src "my-wordmark.png"}}` to `theme.edn`, run
   "Reload Theme". Screenshot — header label above the search field now
   shows the new image.
7. **Tray + tab icon reload**: same procedure with `:statusbar` and/or
   `:tab-document`. Verify system tray icon changes AND all open file tabs
   show the new `:tab-document` icon.
8. **Missing file fallback**: add `:icons {:app {:src "does-not-exist.png"}}`.
   Reload. Expect MessageBox listing "app icon: does-not-exist.png not
   found, using bundled" (or similar). The shell icon stays on the
   bundled default. Verify app did not crash.
9. **Color error**: change `:lavender` to `"not-a-color"`. Save. Run
   "Reload Theme". Verify `MessageBox` pops with an error naming lavender;
   all *other* entries still apply correctly; screenshot the MessageBox.
10. **Transient popup reload**: open the find bar (Cmd+F), then run Reload
    Theme. Find bar should close (per `close-transient-shells!`); next
    Cmd+F opens it with the new theme. No crash.
11. **Browser-rendered page (search.clj)**: type a query in the live-search
    box. Confirm results render. Run Reload Theme after changing
    `:lavender` / `:indigo`. Screenshot — result-card gradient / h1
    headings / code-block bg all reflect the new palette (proves
    `search.clj/colors` is now driven by the registry).
12. **Find-bar highlight colors (find_replace.clj)**: open a file in view
    mode, Cmd+F to open find bar, type a term that matches, run Reload
    Theme (find bar will close), then open find bar again and re-search.
    Current match highlight = new `:lavender`; other matches = new
    `:royal-purple`. Screenshot.
13. **Main-shell icon reload**: put a clearly-distinct PNG at
    `~/.winze/icons/my-app.png`, add `:icons {:app {:src "my-app.png"}}`,
    run Reload Theme, then observe the macOS Dock / Windows taskbar
    shell icon. It should update to the new image WITHOUT requiring an
    app restart. This catches the Step 7d main-shell listener
    specifically — without it the Dock/taskbar stays on the old (now
    disposed) icon.
14. **Live-search-tab CTabItem icon under file-view mode**: the
    live-search tab must ALWAYS show `:statusbar`, even when it
    transiently holds a file in view mode. Repro: from home page click
    a file preview to load it into the live-search tab (puts the
    live-search wrapper into `@open-files` — the tricky case). Run
    Reload Theme. Verify the live-search tab icon is still `:statusbar`
    and not `:tab-document`. This exercises the `identical? live-wrapper`
    filter in Step 7d's CTabItem walk.
15. **Build-failure resilience** (Step 6 partial-failure path): simulate
    a catastrophic build failure by temporarily renaming a bundled icon
    PNG inside the JAR (or patch `build-icon!` at the REPL to throw).
    Run Reload Theme. Expected: the MessageBox lists the error, the app
    keeps painting with the old theme, NO "widget is disposed"
    exceptions fire, and a subsequent successful Reload Theme (restore
    the icon / unpatch) works normally.
16. **Partial-failure resilience** (per-icon fallback): set
    `:icons {:statusbar {:src-1x "does-not-exist.png" :src-2x "missing.png"}}`.
    Run Reload Theme. Expected: MessageBox lists both missing files,
    tray/tab icons still visible using bundled defaults, app keeps
    running.
17. **Editor (markdown editor, whichever path is live)**: open a file,
    toggle to edit, make a keystroke, verify spellcheck underlines a
    misspelling, MOD1+click a wiki link, verify link preview on hover.
    No behavioral regression. If `markdown-editor-widget` has landed,
    the editor construction goes through the CDT init; if not, the
    hand-rolled `toggle-mode!` path is still live — both should work
    identically since Step 4's IDeref proxies preserve semantics.
18. **Validate Theme command**: run `:workbench/validate-theme` with a
    good theme.edn → expect the "Theme OK" info dialog. Change
    `:lavender` to `"not-a-color"`, save, run validate → expect the
    same error MessageBox used by Reload Theme, listing the bad entry.
    Registry should NOT have been touched — running Reload Theme
    afterward reads from disk and applies errors as usual.
19. **Reset Theme command**: with a modified `~/.winze/theme.edn`,
    run `:workbench/reset-theme` → confirmation dialog → YES → file
    is deleted, bundled default reinstalled, theme reverts. Screenshot
    before + after to confirm visual revert.
20. **`current-palette` REPL inspection**: from a dev-alias REPL,
    `(theme/current-palette)` returns the live hex palette as a
    sorted-map. Use this to verify what colors the registry currently
    holds (e.g. after a partial-failure reload).
21. **Command palette closes on reload**: open the command palette,
    click into the filter box, then re-invoke Reload Theme via a
    keybinding (not the palette itself — that action closes it
    anyway). The palette should close; next Cmd+Shift+P opens a
    fresh palette with the new theme.

---

## Step 13 — Update wishlist

**Edit**: [`Plans/todo/wishlist.md`](../wishlist.md) — remove the
"externalize theme colors and fonts into EDN files" bullet from the General
section.

---

## Step 14 — Test before declaring done

1. **RCF tests**: `make test` from `winze-server/` — every new RCF block in
   `theme.clj` passes. `resources.clj` existing tests still pass.
2. **Manual reload** (Step 12 above) — succeeds for: good reloads,
   partially-malformed `theme.edn`, missing icon files, transient popup
   open at reload time, catastrophic build failure (Step 12 #15).
3. **Classpath test**: temporarily rename your `~/.winze/theme.edn`, run
   the app, confirm it's reinstalled from the bundled resource.
4. **JAR-mode test**: `make uber` → run the uberjar once to confirm:
   - bundled `theme.edn` is readable via `io/resource` from inside the JAR.
   - bundled icon PNGs (all four icon classpath paths from the
     `bundled-icons` table) are readable via `.getResourceAsStream` from
     inside the JAR.
5. **Disposal test**: set up a minimal REPL scenario:
   - Start fresh, theme loads.
   - Open a markdown tab (forces color/font registry use).
   - Run Reload Theme 3x in a row.
   - `quit!`.
   - No SIGSEGV; no "widget is disposed" exceptions in the log.
   - Re-start: store is intact, app launches normally.
6. **Browser-content test**:
   - Type a query to populate live-search, screenshot.
   - Edit `:indigo` to something obviously different, Reload Theme.
   - Screenshot — result-card gradient uses the new indigo.
   - Confirm `search.clj`'s `(colors)` fn is consulted on every render
     (put an inline `def` in `page-css` temporarily if needed to verify).
7. **Editor construction regression** (verify Step 4's atom substitution
   didn't break any editor path):
   - Open a file → toggle to edit → toggle back to view → toggle to edit.
   - Verify link-preview (hover over a `[[wiki-link]]`), MOD1+click
     navigation, spellcheck squiggles, undo/redo all still work.
   - Any regression here points at Step 4 — atom deref should be
     semantically identical to `defonce (delay …)` for every `@` call
     site (both return the held value; the only difference is that
     atoms have an already-populated value at first deref rather than
     forcing-on-first-deref).

---

## Step 15 — Move to `Plans/complete/theme-externalize-edn/` on completion

Per project CLAUDE.md convention: rename files to drop no-longer-needed
prefixes (CONTEXT.md and PLAN.md are already correctly named), archive any
jira `AAO-*.md` alongside them. Transition the Jira issue to Done via the
Atlassian MCP server if one was filed.

---

## Deferred / out of scope

- **Procedural-icon theming** (`draw-quill-icon`, `draw-chevron-image`) —
  their palette colors are raw RGB literals, not registry keys. v1 users
  who change palette colors will see a mismatch between the purple
  edit/back/forward icons and their new palette until those functions are
  rewritten to read from the registry and re-render via
  `ImageDataProvider`.
- **Restyling short-lived popups in place** (find bar, content assist,
  link preview). v1 force-closes them on reload. If flicker turns out to
  be annoying in practice, switch to in-place restyle.
- **Filesystem watcher on `theme.edn`** (auto-reload on save). Low effort —
  Winze already has a watcher (`llm-memory.watcher`). File for a follow-up.
- **Copying bundled icons to `~/.winze/icons/`** to give users a starting
  kit. Users extract from the JAR or source tree if they want a baseline;
  we don't maintain a copy.
- **GUI theme editor.**
- **Multiple-theme selection** (light/dark variants picked by OS setting).

## Naming convention

- `theme/apply-theme-startup!` — first-load entry point (only called from
  the `application` init-form in `main_window`).
- `theme/reload-theme!` — reload entry point (called from the
  `:workbench/reload-theme` command). Renamed from an earlier-draft
  `apply-theme!` to avoid colliding with
  [`md-editor/apply-theme!`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L951),
  which is the per-span markdown styling pass invoked on every
  `modifyText`. The two are unrelated — do not merge.
- `theme/hex` — public helper returning the current hex string for a
  color key, for Browser-HTML and JavaScript consumers (search.clj,
  find_replace.clj).
- `theme/current-palette` — REPL / debugging helper; returns the live
  palette as `{kw "#RRGGBB"}` (Step 9.5).
- `theme/validate-user-file` — pure helper; parses `~/.winze/theme.edn`
  and returns `{:theme :errors}` without touching the registry
  (Step 9.5). Driven by the `:workbench/validate-theme` command.

## Workbench commands introduced

- `:workbench/reload-theme` — Step 8.
- `:workbench/validate-theme` — Step 9.5 (validates without applying).
- `:workbench/reset-theme` — Step 9.5 (deletes user file, restores bundled).

## Traceability

- Wishlist trigger: [`todo/wishlist.md`](../wishlist.md) — "externalize theme colors and fonts into EDN files"
- Governing rules: [`Plans/SWT-UI-GUIDE.md`](../../SWT-UI-GUIDE.md) (canonical), esp. §11, §15, §18, §22, §29, §30, §31
- Reader pattern template: [`winze-server/src/llm_memory/highlight/loader.clj`](../../../winze-server/src/llm_memory/highlight/loader.clj)
- MessageBox template: [`main_window.clj:898-910`](../../../winze-server/src/llm_memory/ui/main_window.clj#L898-L910)
- Broadcast pattern: [`spellcheck.clj:476-498`](../../../winze-server/src/llm_memory/ui/spellcheck.clj#L476-L498)
- Registry disposal: [`resources.clj:447-464`](../../../winze-server/src/llm_memory/ui/resources.clj#L447-L464)
- Related work (dependency): [`todo/MARKDOWN-EDITOR-WIDGET-PLAN.md`](../MARKDOWN-EDITOR-WIDGET-PLAN.md) — consolidates the StyledText editor into the CDT init; removes the "two construction sites" problem that Step 6.5 originally addressed.
- Icon consumers (verify during implementation):
  - shell `:image` (main window) → [main_window.clj:1115](../../../winze-server/src/llm_memory/ui/main_window.clj#L1115) — **has its own refresh listener** (Step 7d)
  - tray-item → [main_window.clj:859-860](../../../winze-server/src/llm_memory/ui/main_window.clj#L859-L860)
  - header logo → [main_window.clj:722](../../../winze-server/src/llm_memory/ui/main_window.clj#L722)
  - CTabItem images → search `ctab-item` in `main_window.clj` / `open-tab!`
- JAR-safe resource loading: project `CLAUDE.md` §"Classpath Resource Access"
