---
name: Live spellcheck — plan
description: Step-by-step plan to implement squiggly-underline spellcheck + right-click menu + user dictionary for the Winze Markdown editor
type: plan
---

# Live Spellcheck — Plan

Context lives in [CONTEXT.md](CONTEXT.md). Read it before starting.
SWT rules from [`Plans/SWT-UI-GUIDE.md`](../../SWT-UI-GUIDE.md) apply:
every UI mutation in `async-exec!`, no `:reload-all`, patch the running
server via `load-file`, and screenshot-verify every visual change.

All new code lands in `winze-server/`:

| File                                                    | Purpose                                               |
|---------------------------------------------------------|-------------------------------------------------------|
| `resources/dictionaries/en_US.txt`                      | Bundled English wordlist (new resource)               |
| `resources/dictionaries/README.md`                      | Source + license note for the wordlist (new resource) |
| `src/llm_memory/ui/resources.clj`                       | Add `color-spellcheck-error`                          |
| `src/llm_memory/ui/spellcheck.clj`                      | New — dictionary engine, tokeniser, span merge        |
| `src/llm_memory/ui/spellcheck_menu.clj`                 | New — context menu installer                          |
| `src/llm_memory/ui/markdown_editor.clj`                 | Hook into `apply-theme!`, `on e/modify-text`, init    |
| `src/llm_memory/ui/editor_commands.clj`                 | Register `:editor/spellcheck-*` commands              |
| `resources/keybindings/editor.keybinding`               | Default `Mod1+Space` → `:editor/spellcheck-suggest`   |

Split across two new namespaces so the pure logic (`spellcheck.clj`)
stays testable in a non-SWT REPL. `spellcheck_menu.clj` owns the SWT
glue.

## Step 1 — Vendored wordlist

1. Download SCOWL size-60 English wordlist (public domain / BSD).
   Convert to UTF-8, one word per line, lowercase-folded, deduplicated,
   sorted. Write to
   `winze-server/resources/dictionaries/en_US.txt`.
2. Create `winze-server/resources/dictionaries/README.md` stating the
   source URL, revision / SCOWL size, license, and the transform
   applied (fold, dedupe, sort). No frontmatter — this is a resource
   asset, not a plan document.
3. Confirm the file is picked up by the `:paths ["src" "resources"]`
   entry in [`winze-server/deps.edn`](../../../winze-server/deps.edn#L1).

No REPL check needed — file inclusion is verified by Step 3's load.

## Step 2 — Palette entry

Add to [`resources.clj:229-239`](../../../winze-server/src/llm_memory/ui/resources.clj#L229-L239)
alongside the other palette delays:

```clojure
(defonce color-spellcheck-error
  (delay (ui (Color. @display 0xE5 0x48 0x4A))))   ; #E5484A — accessible red on mine-shaft
```

No disposal wiring needed — `dispose-registry!` already reflects over
every realised delay holding a `Resource`
([`resources.clj:445-462`](../../../winze-server/src/llm_memory/ui/resources.clj#L445-L462)).

**REPL check** (dev nREPL, not the running server):

```clojure
(require '[llm-memory.ui.resources :as res] :reload)
@res/color-spellcheck-error   ; realises without error; Color instance
```

## Step 3 — `spellcheck.clj` — pure engine

Create `winze-server/src/llm_memory/ui/spellcheck.clj` with these
sections in order:

### 3a. Tokeniser

```clojure
(def ^:private word-pattern
  #"(?U)[A-Za-z]+(?:['\u2019][A-Za-z]+)*")

(defn- identifier-like? [w]
  (or (< (count w) 2)
      (every? #(Character/isUpperCase ^char %) w)
      (re-find #"[A-Z].*[a-z]|[a-z].*[A-Z]" w)))   ; PascalCase / camelCase

(defn tokens-in
  "Return a vector of {:start :length :text} for every plain-prose word
  inside [region-start region-start+region-length) of `text`."
  [text region-start region-length]
  ;; Use a java.util.regex Matcher; emit maps of absolute offsets.
  ,,,)
```

RCF block proves:

- A single `hello` returns one token with absolute start.
- `don't` is one token, not two.
- `HTTP` is ignored (all caps).
- `IsoDate` is ignored (camelCase).
- `a` is ignored (length < 2).
- The offsets map back to the original word via `(subs text start (+ start length))`.

### 3b. Prose-bearing span predicate

```clojure
(def ^:private prose-types
  #{:body
    :blockquote
    :inline/bold :inline/italic :inline/bold-italic
    :heading/h1 :heading/h2 :heading/h3
    :heading/h4 :heading/h5 :heading/h6})

(defn prose-span? [span] (contains? prose-types (:type span)))
```

Plus a helper `text-tokens` that takes the full text + the output of
`md-theme/theme` and returns all tokens in prose-bearing spans:

```clojure
(defn text-tokens [text spans]
  (into []
        (mapcat (fn [{:keys [start length]}]
                  (tokens-in text start length)))
        (filter prose-span? spans)))
```

### 3c. Dictionary & user-dict atoms

```clojure
(defonce ^:private main-dict-delay
  (delay
    (with-open [r (io/reader (io/resource "dictionaries/en_US.txt"))]
      (into #{} (map str/lower-case) (line-seq r)))))

(def ^:private user-dict-file
  (io/file (System/getProperty "user.home")
           ".winze" "spellcheck" "user-dictionary.txt"))

(defn- load-user-dict
  "Read the plain-text user dictionary into a lowercase-folded set.
  One word per line; blank lines and surrounding whitespace are
  ignored.  Missing file → empty set."
  []
  (try
    (when (.exists user-dict-file)
      (with-open [r (io/reader user-dict-file)]
        (into #{}
              (comp (map str/trim)
                    (remove str/blank?)
                    (map str/lower-case))
              (doall (line-seq r)))))
    (catch Throwable _ #{})))

(defonce user-dict (atom nil))
(defn user-dict* [] (or @user-dict (reset! user-dict (or (load-user-dict) #{}))))

(defonce session-ignores (atom #{}))
```

### 3d. The check itself

```clojure
(defn known? [word]
  (let [w (str/lower-case word)]
    (or (contains? @main-dict-delay w)
        (contains? (user-dict*) w)
        (contains? @session-ignores w))))

(defn misspellings-for
  "Return a vector of {:start :length :text} for every token in the
  prose-bearing spans that is NOT in any dictionary."
  [text spans]
  (into [] (remove #(known? (:text %))) (text-tokens text spans)))
```

RCF coverage: feed a synthetic span vector (bypassing
`md-theme/theme`) and a dictionary set (bind
`#'main-dict-delay` to `(delay #{"hello" "world"})` in a `with-redefs`)
— verify `teh` flags, `hello` does not, `world` inside a `:code-block`
span does not, `world` inside an `:inline/bold` span does flag if
unknown.

### 3e. Span-merge

```clojure
(defn- underline-span
  [span]
  (assoc span
         :underline true
         :underline-style SWT/UNDERLINE_SQUIGGLE
         :underline-color res/color-spellcheck-error))

(defn merge-misspellings
  "Return a new vector of theme spans where every misspelling region is
  split out and carries squiggly-underline style on top of its existing
  :type.  Spans remain non-overlapping and sorted by :start."
  [spans misspellings]
  ,,,)
```

The algorithm: iterate misspellings in sorted order, walk `spans` in
parallel, splitting any span that straddles a misspelling into up to
three pieces: pre (untouched), overlap (carry original `:type` +
underline fields), post (untouched, pushed back onto the work queue).

RCF coverage:

- Merge with an empty `misspellings` vector is identity.
- A single misspelling that aligns with a span's bounds produces a
  vector of the same length with one extra span carrying `:underline
  true`.
- A misspelling entirely inside a `:body` span splits it into three.
- Two adjacent misspellings across a span boundary keep the output
  non-overlapping and sorted.

`SWT/UNDERLINE_SQUIGGLE` is a compile-time constant — `require`
`org.eclipse.swt.SWT` via `(:import ...)` in the `ns` form. This
does not pull SWT natives at AOT time; it only resolves the integer
constant.

### 3f. Suggestions (SymSpell)

```clojure
(defn- deletes-within
  "All strings reachable from `w` by deleting up to `k` characters."
  [^String w k] ,,,)

(defonce ^:private deletes-index-delay
  (delay
    (reduce (fn [idx w]
              (reduce (fn [m d] (update m d (fnil conj #{}) w))
                      idx
                      (deletes-within w 2)))
            {}
            @main-dict-delay)))

(defn- edit-distance [^String a ^String b] ,,,)   ; Damerau-Levenshtein, cap 3

(defn suggestions
  "Return up to 10 dictionary words within edit distance ≤ 2 of
  `word`, sorted alphabetically.  Candidates are generated via the
  deletes index (distance ≤ 2), pruned to those whose actual
  Damerau–Levenshtein distance to `word` is ≤ 2, then — when more
  than 10 survive — trimmed to the 10 closest by distance before the
  final alphabetical sort.  Alphabetical ordering makes the menu
  predictably scannable; distance is used only to trim the pool."
  [word]
  ,,,)
```

The deletes index is a map `delete-string → #{candidate-words}`.
Lookup is O(|deletes-within-2(word)|) which is a few hundred strings
max, times an O(1) set probe.

RCF coverage:

- `(suggestions "teh")` contains `"the"`.
- `(suggestions "recieve")` contains `"receive"`.
- `(suggestions "xyzzyxyz")` is empty or irrelevant — distance too
  large.
- Result count ≤ 10.
- When there are ≥ 2 results, they are in alphabetical order
  (`(= result (sort result))`).

### 3g. User-dictionary mutation

```clojure
(defn add-to-user-dict!
  "Add `word` (lowercase-folded) to the user dictionary and persist
  the whole dictionary to disk as a sorted, one-word-per-line UTF-8
  text file.  Atomic: writes to `<path>.tmp` then renames."
  [word]
  (let [w    (str/lower-case word)
        next (conj (user-dict*) w)]
    (.mkdirs (.getParentFile user-dict-file))
    (let [tmp (io/file (str (.getAbsolutePath user-dict-file) ".tmp"))]
      (spit tmp (str (str/join "\n" (sort next)) "\n"))
      (.renameTo tmp user-dict-file))
    (reset! user-dict next)
    word))

(defn ignore-this-session! [word]
  (swap! session-ignores conj (str/lower-case word))
  word)
```

No test — these are thin IO wrappers. Smoke-test by hand in the REPL.

### 3h. Refresh broadcast

```clojure
(defonce refresh-listeners (atom []))

(defn register-refresh-listener! [f] (swap! refresh-listeners conj f))

(defn- broadcast-refresh! []
  (doseq [f @refresh-listeners] (try (f) (catch Throwable _))))

;; Fan-out on dictionary changes
(add-watch user-dict ::refresh-on-add
  (fn [_ _ _ _] (broadcast-refresh!)))

(add-watch session-ignores ::refresh-on-ignore
  (fn [_ _ _ _] (broadcast-refresh!)))
```

`markdown-editor` registers a refresh listener that calls
`apply-theme!` on its own `StyledText` via `async-exec!`.

**REPL check** for Step 3: `load-file` the namespace in the dev
nREPL; all RCF blocks pass.

## Step 4 — Wire `apply-theme!` to use the span-merge

In [`markdown_editor.clj:250-263`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L250-L263)
`apply-theme!` currently computes `spans` and converts to
StyleRanges. Introduce the misspelling merge **between** those two
steps, reading the current misspelling set from a per-editor atom
stashed on the widget via `setData`:

```clojure
(defn apply-theme! [styled-text text]
  (let [blocks        (md-theme/parse-blocks text)
        spans         (md-theme/theme text)
        misspellings  (or (.getData styled-text "spellcheck/miss") [])
        merged        (spellcheck/merge-misspellings spans misspellings)
        ranges        (into-array StyleRange (map span->style-range merged))]
    (.setStyleRanges styled-text ranges)
    (apply-code-block-line-backgrounds! styled-text blocks)
    (apply-line-spacing!                styled-text blocks)
    (apply-list-rendering!              styled-text blocks)
    (update-link-spans! styled-text spans)))     ; use unmerged spans — link registry unchanged
```

Crucially: `update-link-spans!` keeps its **unmerged** `spans`
argument. The merged spans exist only to drive rendering; link
hit-testing continues to work against the original `:inline/link`
spans.

## Step 5 — Debounced spellcheck job

Add a private helper in `markdown_editor.clj` that schedules a
spellcheck on `res/executor` and, when done, writes the result back
on the UI thread:

```clojure
(defn- schedule-spellcheck!
  "Cancel any pending spellcheck future for `st` and schedule a new
  one to run `debounce-ms` after the last call.  Results are written
  to (.setData st \"spellcheck/miss\" ...) on the UI thread, then
  apply-theme! re-runs."
  [^StyledText st ^ScheduledFuture prev text debounce-ms]
  (when prev (.cancel prev false))
  (.schedule res/executor
             ^Runnable
             (fn []
               (let [spans (md-theme/theme text)
                     miss  (spellcheck/misspellings-for text spans)]
                 (async-exec!
                  (fn []
                    (when-not (.isDisposed st)
                      (.setData st "spellcheck/miss" miss)
                      (apply-theme! st (.getText st)))))))
             (long debounce-ms)
             TimeUnit/MILLISECONDS))
```

Wire it into the `on e/modify-text` handler at
[`markdown_editor.clj:870-882`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L870-L882)
— the handler already owns a cancellable future atom for debounced
save; add a sibling for spellcheck:

```clojure
(let [save-future  (atom nil)
      spell-future (atom nil)
      ,,,]
  ,,,
  (on e/modify-text [props parent event]
      (let [text (.getText parent)]
        (apply-theme! parent text)
        ,,,  ; existing save-future logic
        (reset! spell-future
                (schedule-spellcheck! parent @spell-future text 400)))))
```

Also cancel `spell-future` from `on e/widget-disposed`, beside the
existing `flush-save!` call.

Register a refresh listener at init-time so dictionary changes
re-theme this editor:

```clojure
(fn [_props parent]
  (apply-theme! parent content)
  (install-link-interaction! parent abs-path)
  (install-content-assist-triggers! parent abs-path)
  (install-pair-wrap! parent)
  (spellcheck-menu/install! parent)           ; Step 6
  (spellcheck/register-refresh-listener!
   (fn [] (async-exec!
           #(when-not (.isDisposed parent)
              (apply-theme! parent (.getText parent))))))
  parent)
```

## Step 6 — `spellcheck_menu.clj` — SWT glue

Create `winze-server/src/llm_memory/ui/spellcheck_menu.clj`. Two public
entry points: `install!` (wires the right-click handler) and
`show-suggestion-menu-at-caret!` (used by the
`:editor/spellcheck-suggest` command). Both funnel through a private
`show-menu!` so the menu is built in one place; only the anchor point
differs.

```clojure
(defn- misspelling-at [^StyledText st ^long offset]
  (first (filter (fn [{:keys [start length]}]
                   (and (<= start offset)
                        (< offset (+ start length))))
                 (.getData st "spellcheck/miss"))))

(defn- replace-word! [^StyledText st {:keys [start length]} replacement]
  (async-exec!
   (fn []
     (.replaceTextRange st start length replacement)
     (.setSelection st (+ start (count replacement))))))

(defn- show-menu!
  "Build and show a spellcheck suggestion menu for `miss` at display
  coordinates (screen-x, screen-y).  Must be called on the UI thread.
  `spellcheck/suggestions` is called here — already sorted, already
  capped at 10."
  [^StyledText st miss screen-x screen-y]
  (let [menu (Menu. (.getShell st) SWT/POP_UP)]
    (doseq [s (spellcheck/suggestions (:text miss))]       ; already ≤ 10, A–Z
      (doto (MenuItem. menu SWT/PUSH)
        (.setText s)
        (.addListener SWT/Selection
          (reify Listener
            (handleEvent [_ _] (replace-word! st miss s))))))
    (when (pos? (.getItemCount menu))
      (MenuItem. menu SWT/SEPARATOR))
    (doto (MenuItem. menu SWT/PUSH)
      (.setText "Add to Dictionary")
      (.addListener SWT/Selection
        (reify Listener
          (handleEvent [_ _]
            (spellcheck/add-to-user-dict! (:text miss))))))
    (doto (MenuItem. menu SWT/PUSH)
      (.setText "Ignore")
      (.addListener SWT/Selection
        (reify Listener
          (handleEvent [_ _]
            (spellcheck/ignore-this-session! (:text miss))))))
    (.addMenuListener menu
      (reify org.eclipse.swt.events.MenuListener
        (menuShown  [_ _])
        (menuHidden [_ _] (async-exec! #(.dispose menu)))))
    (.setLocation menu (int screen-x) (int screen-y))
    (.setVisible menu true)))

(defn install!
  "Install a MenuDetectListener on `st` that opens the spellcheck
  suggestion menu when the user right-clicks on a misspelled word,
  and otherwise allows SWT's default context menu."
  [^StyledText st]
  (.addMenuDetectListener
   st
   (reify org.eclipse.swt.events.MenuDetectListener
     (menuDetected [_ event]
       (let [pt    (Point. (.x event) (.y event))
             loc   (.toControl st pt)
             off   (try (.getOffsetAtPoint st loc) (catch Throwable _ nil))
             miss  (when off (misspelling-at st off))]
         (when miss
           (set! (.doit event) false)
           (async-exec!
            #(show-menu! st miss (.x event) (.y event)))))))))

(defn show-suggestion-menu-at-caret!
  "Open the spellcheck suggestion menu anchored just below the caret
  if the caret is inside a misspelled word.  No-op otherwise.  Called
  from the `:editor/spellcheck-suggest` command."
  [^StyledText st]
  (async-exec!
   (fn []
     (when-not (.isDisposed st)
       (let [off  (.getCaretOffset st)
             miss (misspelling-at st off)]
         (when miss
           (let [^Point ctrl-pt (.getLocationAtOffset st off)
                 line-height    (.getLineHeight st)
                 ;; Drop one line so the menu appears below the caret line,
                 ;; not covering the word the user is asking about.
                 anchor         (Point. (.x ctrl-pt)
                                        (+ (.y ctrl-pt) line-height))
                 ^Point screen  (.toDisplay st anchor)]
             (show-menu! st miss (.x screen) (.y screen)))))))))
```

**Menu disposal** is critical — see SWT-UI-GUIDE rule 11. The
`MenuListener/menuHidden` callback disposes the menu asynchronously on
the next UI pulse. Both entry points share this disposal path because
they share `show-menu!`.

Imports needed (add to the `ns` form):

```clojure
(:import
 [org.eclipse.swt SWT]
 [org.eclipse.swt.custom StyledText]
 [org.eclipse.swt.events MenuListener MenuDetectListener Listener]
 [org.eclipse.swt.graphics Point]
 [org.eclipse.swt.widgets Menu MenuItem])
```

## Step 7 — Commands & default keybinding

In [`editor_commands.clj:574-789`](../../../winze-server/src/llm_memory/ui/editor_commands.clj#L574-L789),
register three scope-`:editor` commands. They operate on the **word at
the caret** so the command palette can reach them independently of
mouse input:

```clojure
(commands/register!
 {:id       :editor/spellcheck-suggest
  :label    "Show spellcheck suggestions"
  :category "Spellcheck"
  :action   (fn []
              (when-let [st (active-styled-text)]
                (spellcheck-menu/show-suggestion-menu-at-caret! st)))})

(commands/register!
 {:id       :editor/spellcheck-add-word
  :label    "Add word to dictionary"
  :category "Spellcheck"
  :action   (fn []
              (when-let [st (active-styled-text)]
                (when-let [w (word-at-caret st)]
                  (spellcheck/add-to-user-dict! w))))})

(commands/register!
 {:id       :editor/spellcheck-ignore-word
  :label    "Ignore word this session"
  :category "Spellcheck"
  :action   (fn []
              (when-let [st (active-styled-text)]
                (when-let [w (word-at-caret st)]
                  (spellcheck/ignore-this-session! w))))})
```

`word-at-caret` is a small helper: read `(.getCaretOffset st)`, find
the enclosing misspelling in `(.getData st "spellcheck/miss")` if
any, else walk from the caret forwards/backwards through
`word-pattern`. Co-locate it in `editor_commands.clj`.

Require the new namespace at the top of `editor_commands.clj`:

```clojure
[llm-memory.ui.spellcheck       :as spellcheck]
[llm-memory.ui.spellcheck-menu  :as spellcheck-menu]
```

### Default keybinding

Append to
[`resources/keybindings/editor.keybinding`](../../../winze-server/resources/keybindings/editor.keybinding)
(the editor scope is already defined by that file — see
[`keybindings.clj:27-34`](../../../winze-server/src/llm_memory/ui/keybindings.clj#L27-L34)
and the existing `editor.keybinding` entries):

```clojure
{:key :space :mod #{:mod1} :when {:in :editor}
 :command :editor/spellcheck-suggest
 :comment "Show spellcheck suggestions at caret"}
```

`:space` is already registered in the special-key table
([`keybindings.clj:89-101`](../../../winze-server/src/llm_memory/ui/keybindings.clj#L89-L101))
and `:mod1` resolves to Cmd on macOS, Ctrl on Linux/Windows
([`keybindings.clj:116-123`](../../../winze-server/src/llm_memory/ui/keybindings.clj#L116-L123)).

`Mod1+Space` does not clash with any existing binding:

```clojure
;; Sanity check at the REPL after editing editor.keybinding:
(->> (llm-memory.ui.keybindings/all-bindings)
     (filter #(and (= (:key %) :space)
                   (= (:mod %) #{:mod1}))))
;; => should contain exactly the new :editor/spellcheck-suggest entry.
```

The add / ignore commands have no default chord in v1 — the context
menu covers them, and users can bind palette-registered chords if they
want keyboard access.

## Step 8 — REPL verification in the running server

All changes must be loadable into the running server without
`:reload-all` (rule §13). In order (dependency-first, per §13):

```clojure
(clojure.core/load-file "winze-server/src/llm_memory/ui/resources.clj")
(clojure.core/load-file "winze-server/src/llm_memory/ui/spellcheck.clj")
(clojure.core/load-file "winze-server/src/llm_memory/ui/spellcheck_menu.clj")
(clojure.core/load-file "winze-server/src/llm_memory/ui/markdown_editor.clj")
(clojure.core/load-file "winze-server/src/llm_memory/ui/editor_commands.clj")
```

Already-open editor tabs will not pick up the new
`MenuDetectListener` or the spellcheck debounce from a bare
`load-file` — those are installed inside the CDT init function.
Open a new tab (`Cmd+N`) for the clean verification path. For the
visual comparison against an in-progress document, call the installer
directly on the existing StyledText via `(mw/element :markdown-editor)`
(or whatever id is registered — confirm in
[`main_window.clj`](../../../winze-server/src/llm_memory/ui/main_window.clj)).

### Interactive smoke tests

1. In a new editor tab, type `This sentense has tipos.` — wait 500 ms
   — verify `sentense` and `tipos` gain the red squiggly underline.
2. Type the same words inside a fenced code block (` ```text ` …
   ` ``` `) — verify no underline.
3. Type the same words inside an inline code span (`` `sentense` ``)
   — verify no underline.
4. Type `http://tipos.example.com` — verify no underline (URL path
   tokens are caught by the camelCase/ALL-CAPS / identifier filter or
   by being inside an `:inline/link` span).
5. Right-click `sentense` — verify the menu offers `sentence` (plus
   up to 9 more, so up to 10 suggestions total), then a separator,
   *Add to Dictionary*, and *Ignore*. Verify the suggestions are in
   alphabetical order. Pick `sentence` — verify the text is replaced
   and the underline clears.
6. Place the caret inside `tipos`, press `Mod1+Space` (Cmd+Space on
   macOS, Ctrl+Space on Linux/Windows). Verify the same suggestion
   menu opens, anchored just below the caret line (not under the
   mouse). Dismiss with `Esc` and verify it closes cleanly.
7. With the caret in a correctly-spelled word, press `Mod1+Space` —
   verify nothing happens (silent no-op, no menu, no beep).
8. Right-click `tipos` — pick *Add to Dictionary*. Verify the
   underline clears immediately here **and in a second open editor
   tab that also contains `tipos`**.
9. Restart the server via
   `(llm-memory.ui.main-window/quit!)` and re-launch — verify `tipos`
   is still accepted. Then
   `cat ~/.winze/spellcheck/user-dictionary.txt` from a shell and
   confirm the file is plain text, one word per line, sorted
   alphabetically, with a trailing newline. Add a line by hand
   (e.g. `foobarbaz`), save, restart Winze, verify that
   `foobarbaz` is now accepted — external editability is a
   supported workflow.
10. Use `Display.post` (SWT-UI-GUIDE rule 21) to fire a synthetic
    right-click over a known-misspelled offset to confirm the menu
    opens without manual interaction. Repeat with a synthetic
    `Mod1+Space` keypress over the same offset to confirm the
    keyboard path is driven end-to-end.

Screenshot-verify the underline and the menu with fully-qualified
`llm-memory.ui.util/screenshot-widget!` (rule 15 — aliases fail
intermittently).

### Negative checks

- Type inside a heading — `# Hallo world` — `Hallo` flags, `world`
  does not. Heading font/colour is preserved under the squiggle.
- Bold `**sentense**` — flag still appears, bold style preserved.
- Select `teh`, press Cmd+B (toggle bold) — wrap still produces
  `**teh**`, the underline reappears on the next debounce cycle.
- `Cmd+(` on a selection — wrap behaviour unchanged from the
  delimiter-pair feature; no crash.

## Step 9 — Tests

From `winze-server/`:

```bash
make test
```

RCF coverage (from Step 3):

- `tokens-in` boundary cases.
- `merge-misspellings` identity, single-span split, multi-span split,
  adjacent-misspellings boundary case.
- `suggestions` returns known fixes for `teh`, `recieve`,
  `seperate`.
- `known?` honours the session-ignores atom after a `swap!`.

No SWT tests in the automated suite — the menu and debounce are
verified by the interactive checks in Step 8 plus screenshot diffs.

## Step 10 — Completion

When the feature is verified in the running server:

1. Run `make test` in `winze-server/` and `clj-llm-memory/` (nothing
   in core library changes, but confirm no regression).
2. `make install-winze` from `winze-server/` to rebuild the uberjar
   and update `~/.local/share/winze/`. Restart via
   `(llm-memory.ui.main-window/quit!)` — never `pkill` (SWT-UI-GUIDE
   rule + CLAUDE.md; force-kill corrupts Datalevin).
3. Move `Plans/todo/spellcheck/` → `Plans/complete/spellcheck/` — same
   filename convention as
   `Plans/complete/delimiter-pair-wrap/` (no prefix on the inner
   files).
4. Remove the `Need a spellchecker!` bullet from
   [`Plans/todo/active-issues.md`](../active-issues.md) in the same
   commit.
5. Commit `winze-server/` separately from the top-level `_finance` repo
   (the winze submodule has its own git history) and from
   `winze/Plans/` (the top-level repo). Three commits total.

## Out of Scope (tracked for future)

- **Grammar checking.** Agreement, run-ons, passive voice. Would pull
  in LanguageTool. Revisit if the user asks.
- **Multiple languages & per-document language detection.** V1 is
  English only. Structure `spellcheck.clj` so the dictionary atom is
  the only language-coupled bit, for a future `:language` extension.
- **Per-file-type dictionaries.** Allow a Markdown frontmatter field
  (`lang: en-GB`) or a directory-level `.winze-dict` override.
- **Inline auto-correct.** Silent replacement while typing. Out of
  scope — users find it disruptive.
- **Highlight custom-coined terms differently.** e.g. the user may want
  project-specific technical vocabulary (`clj-oci`, `Winze`) coloured
  distinctly rather than just added to the dictionary. Future-only.
