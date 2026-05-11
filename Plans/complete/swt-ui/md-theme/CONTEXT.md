---
created: 2026-03-27
tags: [swt, ui, markdown, fonts, styling]
---

# Markdown Theme for StyledText — Context

## Goal

Implement a brand-consistent Markdown syntax theme for the winze SWT `StyledText` editor. The theme applies `StyleRange` objects to give different visual treatment to headings, body text, emphasis, code, blockquotes, and links — using the winze brand palette and Inter/JetBrains Mono typefaces.

Fenced code blocks with a declared language (e.g. ` ```clojure `) are syntax-highlighted using per-language regex tokenizers in a `llm-memory.highlight.*` namespace hierarchy.

## Brand Palette Mapping

From `resources/branding/BRAND-GUIDE.md`:

| Brand token       | Hex       | Markdown role                          |
|-------------------|-----------|----------------------------------------|
| Lavender Crystal  | `#C4B8FF` | H1 text, active link hover             |
| Amethyst          | `#9B8FE0` | H2/H3 text, link color, active states  |
| Deep Violet       | `#7B6FC0` | H4 text, blockquote border accent      |
| Royal Purple      | `#5548A0` | H5/H6 text, horizontal rule            |
| Crystal White     | `#E8E0FF` | Body text on dark background           |
| Mine Shaft        | `#1E1B2E` | Editor background                      |
| Bedrock           | `#0E0D18` | Code block background                  |
| Amethyst          | `#9B8FE0` | Inline code foreground                 |
| Pure White        | `#FFFFFF` | Bold / emphasis accent                 |

For emphasis and bold, prefer color + style rather than only color — the reader should not need to distinguish colors to understand structure.

## Typography

Brand guide specifies:
- **Primary font**: Inter; alternates: Plus Jakarta Sans, Outfit
- **Code font**: Not specified; use JetBrains Mono → Fira Code → Menlo → Consolas → Courier New
- **Heading weight**: 500 (Medium) — maps to `SWT/BOLD`
- **Body weight**: 400 (Regular) — maps to `SWT/NORMAL`
- **Wordmark weight**: 300 (Light) — no StyledText equivalent; use NORMAL

## Header Size Scale

Body text at **13 pt**. Scale chosen to make each level clearly distinct:

| Level | Size | Style       | Color             |
|-------|------|-------------|-------------------|
| H1    | 24   | BOLD        | Lavender `#C4B8FF`|
| H2    | 20   | BOLD        | Amethyst `#9B8FE0`|
| H3    | 17   | BOLD        | Amethyst `#9B8FE0`|
| H4    | 15   | BOLD        | Deep Violet `#7B6FC0` |
| H5    | 13   | BOLD+ITALIC | Royal Purple `#5548A0` |
| H6    | 13   | ITALIC      | Royal Purple `#5548A0` |
| body  | 13   | NORMAL      | Crystal White `#E8E0FF` |
| mono  | 13   | NORMAL      | Amethyst `#9B8FE0` |

## Font Helper API Design

SWT `Font` takes a single name — no built-in fallback list. We need a CSS-style font-stack helper in `llm-memory.ui.resources`:

```clojure
;; Check if a font name is available on the current display
(defn font-available? [name] ...)

;; Return the first available font name from a seq, or "" (SWT uses system default)
(defn first-available-font
  ([names])
  ([names fallback]))

;; Create an SWT Font on the UI thread using a font-stack seq
;; style: SWT/NORMAL, SWT/BOLD, SWT/ITALIC, (bit-or SWT/BOLD SWT/ITALIC)
(defn make-font [names size style] ...)
```

Font stacks (`def`, not `defonce` — just constant data):
```clojure
(def sans-stack ["Inter" "Plus Jakarta Sans" "Outfit"])
(def mono-stack ["JetBrains Mono" "Fira Code" "Menlo" "Consolas" "Courier New"])
```

Defonce font vars (delays, in `resources.clj`) — one per semantic role:

| Var                  | Stack      | Size | Style          |
|----------------------|------------|------|----------------|
| `body-font`          | sans-stack | 13   | NORMAL         |
| `body-bold-font`     | sans-stack | 13   | BOLD           |
| `body-italic-font`   | sans-stack | 13   | ITALIC         |
| `body-bold-italic-font` | sans-stack | 13 | BOLD+ITALIC  |
| `h1-font`            | sans-stack | 24   | BOLD           |
| `h2-font`            | sans-stack | 20   | BOLD           |
| `h3-font`            | sans-stack | 17   | BOLD           |
| `h4-font`            | sans-stack | 15   | BOLD           |
| `h5-font`            | sans-stack | 13   | BOLD+ITALIC    |
| `h6-font`            | sans-stack | 13   | ITALIC         |
| `mono-font`          | mono-stack | 13   | NORMAL         |
| `mono-bold-font`     | mono-stack | 13   | BOLD           |
| `mono-italic-font`   | mono-stack | 13   | ITALIC         |

No disposal needed — the OS reclaims font resources when the JVM exits.

## StyleRange Approach

The theme lives in a new namespace `llm-memory.ui.md-theme`. It provides functions that parse a plaintext markdown string, detect element boundaries (line-by-line for block elements, regex for inline), and return a vector of `StyleRange` objects ready to apply via `(.setStyleRanges styled-text ranges-array)`.

`StyleRange` fields used:
- `.font` — heading/mono/body/italic/bold variants
- `.foreground` — per-element brand color (SWT `Color` from hex)
- `.background` — nil for most; Bedrock `#0E0D18` for code blocks
- `.fontStyle` — redundant with `.font` but must match (SWT requirement)

SWT `Color` objects from hex: `(Color. @display r g b)`. These are also candidates for `defonce` in `resources.clj` alongside the fonts.

## Syntax Highlighting

Fenced code blocks are highlighted using regex-based tokenizers — one per language family, organized as separate namespaces within `winze-server`:

```
llm-memory.highlight.core       — dispatch by language tag, token-type→color mapping
llm-memory.highlight.clojure    — Clojure, ClojureScript, EDN
llm-memory.highlight.data       — JSON, YAML
llm-memory.highlight.web        — HTML, CSS, JavaScript, TypeScript
llm-memory.highlight.shell      — Bash/Shell
llm-memory.highlight.python     — Python
llm-memory.highlight.sql        — SQL
```

`llm-memory.highlight.core` exports a single entry point:

```clojure
(defn tokenize
  "Return a seq of {:start int :length int :type keyword} maps for `code`,
  dispatching to the tokenizer registered for `lang` (e.g. \"clojure\", \"js\").
  Returns an empty seq for unknown languages."
  [lang code] ...)
```

Token types (keywords) used across all languages:

| Keyword              | Brand color           | Notes                        |
|----------------------|-----------------------|------------------------------|
| `:token/keyword`     | Lavender `#C4B8FF`    | Language keywords            |
| `:token/string`      | Amethyst `#9B8FE0`    | String literals              |
| `:token/comment`     | Royal Purple `#5548A0`| Italic style applied too     |
| `:token/number`      | Deep Violet `#7B6FC0` | Numeric literals             |
| `:token/type`        | Lavender `#C4B8FF`    | Class/type names             |
| `:token/operator`    | Crystal White `#E8E0FF` (dimmed) | Operators, punctuation |
| `:token/builtin`     | Amethyst `#9B8FE0`    | Built-in fns / special forms |
| `:token/default`     | Crystal White `#E8E0FF` | Unclassified identifiers   |

Offsets are relative to the start of the code block content (not the full document). `md-theme` shifts them by the block's start offset before building `StyleRange` objects.

### Design principle: pure core, SWT at the boundary

All tokenizers and the `md-theme/theme` function are **purely functional** — they take strings and return plain Clojure maps. No SWT imports in the tokenizer or theme namespaces. Only `md-theme/apply-theme!` constructs `StyleRange` objects.

This makes the entire parsing and tokenizing layer testable with RCF tests that require no running Display.

## SWT Constraints

- `StyleRange` offsets are **character** positions in the widget's text content.
- Block-level detection (headings, code fences) is line-by-line; inline spans use regex within each line.
- When content changes, ranges must be fully recomputed — no incremental update API.
- Line height adjusts automatically for the tallest font on a line; H1 will visibly push the line height up.

## Testing Strategy

- **Tokenizer functions** (`highlight/*`, `md-theme/theme`): pure functions — test with inline `(tests ... :rcf)` blocks in each namespace. Cover: empty input, single token, multiple tokens, overlapping edge cases, language-specific corner cases (nested strings, multi-line comments, etc.).
- **Font/color helpers** (`resources.clj`): require a running Display — verify interactively in the REPL after UI startup. Not RCF-testable without a display.
- **`apply-theme!`**: tested visually via screenshot after applying to a live `StyledText`.
