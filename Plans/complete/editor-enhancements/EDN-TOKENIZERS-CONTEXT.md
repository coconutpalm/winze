# Language Tokenizers — Context

## Goal

Externalize the programming language syntax tokenizers from hardcoded Clojure
namespaces into declarative `.lang` files. Built-in languages ship in
`resources/languages/`, user-contributed languages live in
`~/.winze/languages/`. Each `.lang` file uses Clojure data literal syntax
(read with `*read-eval*` bound to `false`) so that regex patterns can be
written using Clojure's `#"..."` regex literals — no double-escaping.

## Related Work

- **editor-commands** (active) — the editor commands plan includes this work as
  part of the broader editor improvement. See
  [EDITOR-COMMANDS-CONTEXT.md](EDITOR-COMMANDS-CONTEXT.md).
- `complete/swt-ui/md-theme/` — the theme engine that consumes tokenizer output
  (`md_theme.clj:theme` calls `highlight/tokenize`).
- `highlight/core.clj` — the existing tokenizer registry (`register-tokenizer!`,
  `tokenize`). This stays unchanged; `.lang` tokenizers register through it.

## Current State

### Architecture

```
md_theme.clj:theme
    ↓ calls
highlight/core.clj:tokenize(lang, code)
    ↓ dispatches via
registry atom: {"clojure" → tokenize-fn, "python" → tokenize-fn, ...}
    ↓ registered by
highlight/clojure.clj, highlight/python.clj, highlight/shell.clj,
highlight/sql.clj, highlight/web.clj, highlight/data.clj
```

### Existing tokenizers

| File | Languages | Lines | Pattern |
|------|-----------|------:|---------|
| `clojure.clj` | clojure, clj, cljs, edn | 137 | 7-group master regex + keyword set (50+) + builtin set (50+) |
| `python.clj` | python, py | 86 | 6-group master regex + keyword set + builtin set + UpperCase→type heuristic |
| `shell.clj` | bash, sh, shell, zsh | 81 | 6-group master regex + keyword set + builtin set |
| `sql.clj` | sql | 92 | 5-group master regex + keyword set (case-insensitive) + builtin set |
| `web.clj` | js, javascript, ts, typescript, html, htm, css | 182 | Three tokenizers: JS (6-group + keywords + builtins + UpperCase→type), HTML (6-group), CSS (8-group) |
| `data.clj` | json, yaml, yml | 115 | Two tokenizers: JSON (5-group), YAML (6-group) |

**Total**: 693 lines of Clojure across 6 files, covering 17 language tags.

### Common structure

Every tokenizer follows the same template:
1. A single master regex with N numbered capture groups (alternation `|`)
2. A `Matcher.find()` loop
3. For each match: check which group matched → assign a `:token/*` role
4. For the "identifier" group (if present): classify via word sets
   (`keywords` → `:token/keyword`, `builtins` → `:token/builtin`,
   optional regex heuristic → `:token/type`, else → `:token/default`)

The only variation between tokenizers is:
- Which regex groups exist and what roles they map to
- Which words are in the keyword/builtin sets
- Whether keyword matching is case-sensitive (SQL: no, all others: yes)
- Whether there's an UpperCase→type heuristic (Python, JS: yes)

### Token roles (exhaustive)

| Role | Meaning | Used by |
|------|---------|---------|
| `:token/comment` | Line/block comments | All languages |
| `:token/string` | String literals, regex literals, char literals | All languages |
| `:token/number` | Numeric literals, hex colors (CSS) | All languages |
| `:token/keyword` | Language keywords, special forms | All languages |
| `:token/builtin` | Built-in functions, variables, decorators | All except JSON |
| `:token/type` | Type names (UpperCase heuristic), CSS selectors | Python, JS, CSS |
| `:token/operator` | Operators, punctuation, delimiters | Shell, SQL, JS, HTML, CSS, JSON, YAML |
| `:token/default` | Unclassified identifiers | All with identifier groups |

## File Format: `.lang`

### Why `.lang` instead of `.edn`

The `.lang` extension signals that these files are declarative language
definitions, not executable Clojure code and not generic EDN data. The key
advantage over EDN: Clojure's `#"..."` regex literal syntax, which eliminates
the double-escaping problem that makes regex-in-JSON/EDN painful.

Files are read with `clojure.core/read` (not `edn/read-string`) with
`*read-eval*` bound to `false` for safety. This gives access to `#"..."` regex
literals while preventing code execution.

### Design rationale

The format separates **structural patterns** (regexes for comments, strings,
numbers, operators) from **vocabulary** (word lists for keywords and builtins).
This matches how humans think about language grammars:
- Structural patterns are few, complex, and rarely change
- Vocabulary is large, simple, and frequently extended

A flat `regex → role` vector handles structural patterns. The special
`:token/identifier` role triggers secondary classification via word sets.
This avoids encoding 80+ words as regex alternations (slow) while keeping
the file clean and maintainable.

### File structure

```clojure
{:tags          ["python" "py"]
 :case-sensitive true
 :rules
 [{:regex #"(#[^\n]*)"
   :role :token/comment
   :comment "Line comment"}
  {:regex #"(\"\"\"[\s\S]*?\"\"\"|'''[\s\S]*?''')"
   :role :token/string
   :comment "Triple-quoted string"}
  {:regex #"(\"(?:[^\"\\]|\\.)*\"|'(?:[^'\\]|\\.)*')"
   :role :token/string
   :comment "Single/double quoted string"}
  {:regex #"(\d+\.?\d*(?:[eE][-+]?\d+)?)"
   :role :token/number}
  {:regex #"(@[a-zA-Z_][a-zA-Z0-9_.]*)"
   :role :token/builtin
   :comment "Decorator"}
  {:regex #"([a-zA-Z_][a-zA-Z0-9_]*)"
   :role :token/identifier
   :comment "Identifier — classified via keywords/builtins/type-pattern"}]
 :keywords     #{"def" "if" "else" "for" "while" "return" ...}
 :builtins     #{"print" "len" "range" ...}
 :type-pattern #"[A-Z].*"}
```

Note: `:regex` values are compiled `Pattern` objects (via `#"..."`), not
strings. `:type-pattern` is also a `Pattern`. The loader handles both
uniformly.

### Field reference

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `:tags` | Yes | `[string]` | Language tags matching code fence info strings |
| `:case-sensitive` | No | `boolean` | Default `true`. When `false`, keyword/builtin lookup lowercases the match before set membership check |
| `:rules` | Yes | `[map]` | Ordered vector of regex→role rules. Each regex MUST have exactly one capture group. Rules are concatenated with `\|` into a master regex; group ordering determines match priority |
| `:keywords` | No | `#{string}` | Word set → `:token/keyword` for `:token/identifier` matches |
| `:builtins` | No | `#{string}` | Word set → `:token/builtin` for `:token/identifier` matches |
| `:type-pattern` | No | `regex` | Regex applied to `:token/identifier` matches after keyword/builtin check. If it matches → `:token/type` |

### Rule fields

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `:regex` | Yes | `regex` | Clojure regex literal `#"..."` with exactly one capture group `(...)`. Regexes are concatenated in order, so earlier rules take priority |
| `:role` | Yes | `keyword` | One of `:token/comment`, `:token/string`, `:token/number`, `:token/keyword`, `:token/builtin`, `:token/type`, `:token/operator`, `:token/default`, or the special `:token/identifier` |
| `:comment` | No | `string` | Human-readable description of what this rule matches |

### The `:token/identifier` role

`:token/identifier` is a **classification trigger**, not a final role. When
the runtime encounters a match in an `:token/identifier` group, it applies
the following chain:

1. `text ∈ :keywords` → `:token/keyword`
2. `text ∈ :builtins` → `:token/builtin`
3. `text` matches `:type-pattern` → `:token/type`
4. Otherwise → `:token/default`

If `:case-sensitive` is `false`, the match text is lowercased before set
lookup.

A language with no `:keywords`, `:builtins`, or `:type-pattern` can omit
`:token/identifier` entirely and use direct roles like `:token/keyword`
for specific regex patterns (as JSON and YAML do).

## Compilation

At load time, each `.lang` file is read and compiled into a tokenize function:

```clojure
(defn compile-language
  "Compile a language definition into a tokenize-fn."
  [{:keys [rules keywords builtins type-pattern case-sensitive]
    :or   {case-sensitive true}}]
  (let [;; Extract pattern strings from compiled Pattern objects and join
        master-re  (re-pattern (str/join "|" (map #(.pattern (:regex %)) rules)))
        role-index (mapv :role rules)
        kw-set     (or keywords #{})
        bl-set     (or builtins #{})
        kw-set     (if case-sensitive kw-set (into #{} (map str/lower-case) kw-set))
        bl-set     (if case-sensitive bl-set (into #{} (map str/lower-case) bl-set))
        type-re    type-pattern  ;; already a compiled Pattern or nil
        normalize  (if case-sensitive identity str/lower-case)]
    (fn [code]
      (let [matcher (re-matcher master-re code)]
        (loop [acc []]
          (if (.find matcher)
            (let [s    (.start matcher)
                  e    (.end matcher)
                  text (.group matcher)
                  role (loop [i 0]
                         (if (>= i (count role-index))
                           :token/default
                           (if (.group matcher (inc i))
                             (nth role-index i)
                             (recur (inc i)))))
                  role (if (= role :token/identifier)
                         (let [t (normalize text)]
                           (cond
                             (contains? kw-set t) :token/keyword
                             (contains? bl-set t) :token/builtin
                             (and type-re (re-matches type-re text)) :token/type
                             :else :token/default))
                         role)]
              (recur (conj acc {:start s :length (- e s) :type role})))
            acc))))))
```

The key difference from the EDN approach: `:regex` values arrive as compiled
`java.util.regex.Pattern` objects (from the `#"..."` literals). The compiler
extracts their pattern strings via `.pattern` to build the master regex.

### Performance

The compiled function is a tight `Matcher.find()` loop with hash-set lookups
for identifier classification — the same as the current handcoded tokenizers.
The JIT optimizes the inner loop identically. No runtime interpretation of
the data structure occurs.

Pre-compiling the master regex at load time (not per-call) is critical.
`re-pattern` is expensive; the compiled `Pattern` object is what the JIT
optimizes.

## File Layout

```
winze-server/
├── resources/languages/          ← built-in language definitions
│   ├── clojure.lang
│   ├── python.lang
│   ├── shell.lang
│   ├── sql.lang
│   ├── javascript.lang
│   ├── html.lang
│   ├── css.lang
│   ├── json.lang
│   └── yaml.lang
└── src/llm_memory/highlight/
    ├── core.clj                  ← unchanged (registry + tokenize dispatch)
    ├── loader.clj                ← NEW: .lang loading + compilation + registration
    ├── clojure.clj               ← deleted after migration
    ├── python.clj                ← deleted after migration
    ├── shell.clj                 ← deleted after migration
    ├── sql.clj                   ← deleted after migration
    ├── web.clj                   ← deleted after migration
    └── data.clj                  ← deleted after migration

~/.winze/languages/               ← user-contributed (override by :tags)
└── rust.lang                     ← example user language
```

### Splitting `web.clj` and `data.clj`

`web.clj` contains three tokenizers (JS, HTML, CSS) and `data.clj` contains
two (JSON, YAML). Each becomes its own `.lang` file. This is cleaner: one file
per language.

### Override semantics

User files in `~/.winze/languages/` override built-in files when their `:tags`
overlap. Load order: built-in first, user second. User registrations overwrite
built-in registrations in the `core/registry` atom.

## Files to Modify

| File | Change |
|------|--------|
| `winze-server/src/llm_memory/highlight/loader.clj` | **New** — `.lang` file reading (Clojure reader with `*read-eval*` false), compilation, validation, registration |
| `winze-server/src/llm_memory/highlight/core.clj` | Add `load-languages!` call; possibly add `reload-languages!` |
| `winze-server/src/llm_memory/ui/md_theme.clj` | Remove requires for individual language namespaces; require `loader` instead |
| `winze-server/resources/languages/*.lang` | **New** — 9 `.lang` files for built-in languages |
| `winze-server/src/llm_memory/highlight/clojure.clj` | Delete after migration |
| `winze-server/src/llm_memory/highlight/python.clj` | Delete after migration |
| `winze-server/src/llm_memory/highlight/shell.clj` | Delete after migration |
| `winze-server/src/llm_memory/highlight/sql.clj` | Delete after migration |
| `winze-server/src/llm_memory/highlight/web.clj` | Delete after migration |
| `winze-server/src/llm_memory/highlight/data.clj` | Delete after migration |

## Risks

- **`*read-eval*` safety** — `clojure.core/read` with `*read-eval*` bound to
  `false` prevents `#=()` evaluation. This is the standard mitigation. The
  files contain only data literals: maps, vectors, sets, strings, keywords,
  and regex patterns. No function calls, no `def`, no side effects.
- **User-contributed file errors** — malformed regex, missing capture group,
  wrong field types. The loader must validate each file and log clear errors
  (with filename) rather than crashing. Skip invalid files, continue loading
  others.
- **Master regex group numbering** — the compilation relies on each rule's
  `:regex` having exactly one capture group `(...)`. If a rule has zero or
  multiple groups, the group-index mapping breaks. Validate at load time:
  count `(` that aren't `(?` (non-capturing) in the pattern string.
- **Behavioral parity** — the migrated `.lang` tokenizers must produce identical
  output to the current Clojure tokenizers. Verify with the existing RCF tests
  (run them against the compiled `.lang` tokenizers before deleting the old
  code).

## Design Decisions

### Why `.lang` files with Clojure reader instead of EDN

EDN strings require Java-style double-escaping for regex backslashes: `\\d`
in the file to get `\d` in the regex. This is the #1 source of errors when
authoring language definitions. Clojure's `#"..."` regex literal syntax
eliminates this entirely — you write the regex exactly as you'd write it in
a regex tester.

The custom `.lang` extension signals that these files are not executable
Clojure code. They are pure data read with `*read-eval*` bound to `false`.

### Why not grammar-like features (state machines, nested scopes)

Every existing tokenizer is a flat regex scan with no state, no nesting, and
no context-sensitivity. The closest thing to "state" is SQL's `/* ... */`
block comments, handled by `[\s\S]*?` (non-greedy multiline match). No
tokenizer needs to track "am I inside a string?" because the master regex
handles that via priority ordering (string patterns before identifier patterns).

If we ever need state (e.g., string interpolation in Ruby), we could add an
optional `:states` map to the format. But for syntax *highlighting* (not
parsing), flat regex is sufficient for all mainstream languages.

### Why Option A over inlining word sets as regex alternations

Encoding 80+ keywords as `(def|defn|defn-|defonce|...)` in a regex is:
1. Slower — regex engine does linear alternation, hash-set does O(1)
2. Harder to maintain — adding a keyword means editing a regex
3. Harder to read — a 500-character regex vs. a set of words

The `:token/identifier` + word-set pattern is idiomatic for tokenizers
(TextMate grammars, Monarch, CodeMirror all use this separation).

### Why one file per language, not one file for all

Each language is independently maintainable. Users can contribute a single
`rust.lang` without touching any other file. Override semantics are simpler:
if `~/.winze/languages/python.lang` exists, it replaces the built-in Python
tokenizer entirely (by `:tags` overlap).
