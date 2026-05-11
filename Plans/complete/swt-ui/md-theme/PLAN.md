---
created: 2026-03-27
related: [MD-THEME-CONTEXT.md]
tags: [swt, ui, markdown, fonts, styling]
---

# Markdown Theme for StyledText — Plan

## Step 1 — Font helpers in `resources.clj`

Add imports:
```clojure
[org.eclipse.swt.graphics Font]
```

Add functions (before the `defonce` image vars):

```clojure
(defn font-available?
  "Return true if a font with `name` is installed on the current display."
  [name]
  (boolean (seq (.getFontList @display name true))))

(defn first-available-font
  "Return the first name from `names` available on the display,
  or `fallback` (default: empty string, which lets SWT use the system font)."
  ([names] (first-available-font names ""))
  ([names fallback]
   (or (first (filter font-available? names)) fallback)))

(defn make-font
  "Create an SWT Font on the UI thread from a CSS-style font-stack vector.
  `names` — tried in order; first available wins.
  `size`  — point size.
  `style` — SWT/NORMAL, SWT/BOLD, SWT/ITALIC, or (bit-or SWT/BOLD SWT/ITALIC)."
  [names size style]
  (ui (Font. @display (first-available-font names) size style)))
```

Add font-stack constants (plain `def` — just data):
```clojure
(def sans-stack ["Inter" "Plus Jakarta Sans" "Outfit"])
(def mono-stack ["JetBrains Mono" "Fira Code" "Menlo" "Consolas" "Courier New"])
```

Verify in the REPL: load the namespace, then force a font delay and inspect it.

## Step 2 — Defonce font vars in `resources.clj`

Add after the image `defonce`s:

```clojure
;; Body fonts
(defonce body-font           (delay (make-font sans-stack 13 SWT/NORMAL)))
(defonce body-bold-font      (delay (make-font sans-stack 13 SWT/BOLD)))
(defonce body-italic-font    (delay (make-font sans-stack 13 SWT/ITALIC)))
(defonce body-bold-italic-font
  (delay (make-font sans-stack 13 (bit-or SWT/BOLD SWT/ITALIC))))

;; Heading fonts
(defonce h1-font (delay (make-font sans-stack 24 SWT/BOLD)))
(defonce h2-font (delay (make-font sans-stack 20 SWT/BOLD)))
(defonce h3-font (delay (make-font sans-stack 17 SWT/BOLD)))
(defonce h4-font (delay (make-font sans-stack 15 SWT/BOLD)))
(defonce h5-font (delay (make-font sans-stack 13 (bit-or SWT/BOLD SWT/ITALIC))))
(defonce h6-font (delay (make-font sans-stack 13 SWT/ITALIC)))

;; Monospace fonts
(defonce mono-font        (delay (make-font mono-stack 13 SWT/NORMAL)))
(defonce mono-bold-font   (delay (make-font mono-stack 13 SWT/BOLD)))
(defonce mono-italic-font (delay (make-font mono-stack 13 SWT/ITALIC)))
```

`SWT/NORMAL`, `SWT/BOLD`, `SWT/ITALIC` are already accessible via the `org.eclipse.swt.SWT` import; add it if not already present.

Verify: in a live UI REPL, `@body-font` and `@h1-font` should return distinct `Font` objects with the correct names and sizes.

## Step 3 — Brand colors in `resources.clj`

Add brand color `defonce`s alongside the fonts. SWT `Color` is a display resource:

```clojure
;; Brand palette colors
(defonce color-lavender    (delay (ui (Color. @display 0xC4 0xB8 0xFF))))  ; #C4B8FF
(defonce color-amethyst    (delay (ui (Color. @display 0x9B 0x8F 0xE0))))  ; #9B8FE0
(defonce color-deep-violet (delay (ui (Color. @display 0x7B 0x6F 0xC0))))  ; #7B6FC0
(defonce color-royal-purple (delay (ui (Color. @display 0x55 0x48 0xA0)))) ; #5548A0
(defonce color-crystal-white (delay (ui (Color. @display 0xE8 0xE0 0xFF))));; #E8E0FF
(defonce color-mine-shaft  (delay (ui (Color. @display 0x1E 0x1B 0x2E))))  ; #1E1B2E
(defonce color-bedrock     (delay (ui (Color. @display 0x0E 0x0D 0x18))))  ; #0E0D18
(defonce color-pure-white  (delay (ui (Color. @display 0xFF 0xFF 0xFF))))  ; #FFFFFF
```

Add `Color` to the `(:import [org.eclipse.swt.graphics ...])` clause.

Note: unlike fonts, `Color` objects technically should be disposed. However since these are global singleton resources tied to the display lifetime, we follow the same "OS handles it on exit" convention as the fonts.

## Step 4 — New namespace `llm-memory.ui.md-theme`

Create `src/llm_memory/ui/md_theme.clj`. **No SWT imports here** — purely functional, returns plain Clojure maps. Only `apply-theme!` touches SWT.

```clojure
(ns llm-memory.ui.md-theme
  (:require
   [clojure.string :as str]
   [llm-memory.highlight.core :as highlight]
   [hyperfiddle.rcf :refer [tests]]))
```

### 4a — Span map format

All internal functions return sequences of span maps:

```clojure
{:start  <int>     ; character offset from start of full document text
 :length <int>
 :type   <keyword> ; e.g. :heading/h1, :inline/bold, :code-block, :token/keyword
 :lang   <string>  ; code blocks only — the declared language or nil
}
```

### 4b — Block-level parser

Walk lines tracking character offset. Returns a seq of block spans:
- `^#{1,6} ` → `:heading/h1` … `:heading/h6` (whole line)
- ` ``` lang` / ` ``` ` (fence pair) → `:code-block` with `:lang` (content between fences)
- `^> ` → `:blockquote`
- Otherwise → `:body`

```clojure
(defn- heading-level [line]
  (when-let [[_ hashes] (re-matches #"^(#{1,6}) .*" line)]
    (count hashes)))
```

Include RCF tests for: empty string, H1–H6 detection, fenced block with and without lang tag, nested blockquote, mixed content.

### 4c — Inline span detector (non-code lines)

Regex scan within a line for (in priority order to avoid overlap):
- `\*\*\*[^*]+\*\*\*` → `:inline/bold-italic`
- `\*\*[^*]+\*\*` → `:inline/bold`
- `\*[^*]+\*` or `_[^_]+_` → `:inline/italic`
- `` `[^`]+` `` → `:inline/code`
- `\[.*?\]\(.*?\)` → `:inline/link`

Returns spans with offsets relative to line start; caller shifts by line offset.

Include RCF tests for: each inline type, overlapping markers, empty markers, inline code containing `*`.

### 4d — `theme` — main entry point

```clojure
(defn theme
  "Parse `text` and return a seq of span maps covering the full document.
  Code blocks with a known language include syntax token spans (shifted to
  document offsets). No SWT objects created."
  [text]
  ...)
```

RCF tests covering the full pipeline:
```clojure
(tests
  (theme "# Hello") := [{:start 0 :length 7 :type :heading/h1}]

  ;; inline spans within body
  (let [spans (theme "some **bold** text")]
    (some #(= :inline/bold (:type %)) spans)) := true

  ;; code block — content span present
  (let [spans (theme "```clojure\n(+ 1 2)\n```")]
    (some #(= :code-block (:type %)) spans)) := true

  ;; code block — token spans present for known language
  (let [spans (theme "```clojure\n(+ 1 2)\n```")]
    (some #(= :token/builtin (:type %)) spans)) := true

  :rcf)
```

### 4e — `apply-theme!`

In a separate section of the file (or a thin `llm-memory.ui.md-theme-swt` namespace if keeping SWT out of md-theme is preferred):

```clojure
(defn apply-theme!
  "Apply the markdown theme to a StyledText widget. Must be called on the UI thread."
  [styled-text text]
  (let [spans  (theme text)
        ranges (into-array StyleRange (map span->style-range spans))]
    (.setStyleRanges styled-text ranges)))
```

`span->style-range` maps `:type` keywords to font/color resources from `llm-memory.ui.resources`.

## Step 5 — Syntax highlighting namespaces

Create `src/llm_memory/highlight/` with one file per language family. Each exports a `tokenize` function:

```clojure
(defn tokenize
  "Return a seq of {:start int :length int :type keyword} for `code`.
  Offsets are relative to the start of `code`."
  [code] ...)
```

`llm-memory.highlight.core` maintains a registry (`lang-tag → tokenize-fn`) and dispatches:

```clojure
(defn tokenize [lang code]
  (if-let [f (get registry (str/lower-case (or lang "")))]
    (f code)
    []))
```

Language coverage and aliases:

| Namespace                        | Tags                                    |
|----------------------------------|-----------------------------------------|
| `llm-memory.highlight.clojure`   | `clojure` `clj` `cljs` `edn`           |
| `llm-memory.highlight.data`      | `json` `yaml` `yml`                    |
| `llm-memory.highlight.web`       | `html` `css` `js` `javascript` `ts` `typescript` |
| `llm-memory.highlight.shell`     | `bash` `sh` `shell` `zsh`              |
| `llm-memory.highlight.python`    | `python` `py`                           |
| `llm-memory.highlight.sql`       | `sql`                                   |

**Each tokenizer namespace must include RCF tests** covering:
- Empty string → `[]`
- Single token of each type
- Keywords not mistaken for identifiers (e.g. `clojure` ns forms)
- String with embedded delimiters (e.g. `"say \"hi\""`)
- Line comments consuming to EOL
- A realistic multi-token snippet (5–10 tokens, verifies offsets are correct)

## Step 6 — Wire into the editor

The consumer (`StyledText` widget in a future `llm-memory.ui.editor` namespace) calls `apply-theme!` on content change via a `ModifyListener`. Set defaults first:

```clojure
(.setFont styled-text @res/body-font)
(.setBackground styled-text @res/color-mine-shaft)
(.setForeground styled-text @res/color-crystal-white)
```

## Verification checkpoints

After each step, verify before moving on:

| Step | How |
|------|-----|
| 1/2 — fonts | REPL: `(res/font-available? "Inter")`, `(type @res/h1-font)`, `.getFontData` on the result |
| 3 — colors | REPL: `(type @res/color-amethyst)` |
| 4 — md-theme | RCF tests pass in `make test`; spot-check `(md-theme/theme "# Hi\n\n**bold**")` |
| 5 — tokenizers | RCF tests pass for each `highlight/*` namespace in `make test` |
| 6 — wiring | Screenshot of a live `StyledText` showing themed markdown with a highlighted code block |
