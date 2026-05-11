# Winze Language Tokenizer Format

This document describes the `.lang` file format for defining syntax
highlighting rules for programming languages in the Winze editor. Each
language is defined in a single `.lang` file. Built-in languages ship in
`resources/languages/`; user-contributed languages go in
`~/.winze/languages/`.

## Quick Start

Here is a minimal language definition that highlights Python comments, strings,
numbers, and keywords:

```clojure
{:tags ["python" "py"]
 :rules
 [{:regex #"(#[^\n]*)"                      :role :token/comment}
  {:regex #"(\"(?:[^\"\\]|\\.)*\")"         :role :token/string}
  {:regex #"(\d+)"                           :role :token/number}
  {:regex #"([a-zA-Z_][a-zA-Z0-9_]*)"       :role :token/identifier}]
 :keywords #{"def" "if" "else" "for" "while" "return"}}
```

Save this as `~/.winze/languages/python.lang` and restart Winze. Any
` ```python ` code fence will now use these rules.

## File Format

`.lang` files use **Clojure data literal syntax**, read with `*read-eval*`
bound to `false` for safety. This means you can use Clojure's `#"..."` regex
literal syntax — **write regexes exactly as you would in a regex tester**, with
no double-escaping:

```clojure
;; In a .lang file — write the regex naturally:
{:regex #"(\d+\.?\d*)"  :role :token/number}

;; Compared to what EDN/JSON would require:
;; {:regex "(\\d+\\.?\\d*)"  :role :token/number}
```

The files contain only data: maps, vectors, sets, strings, keywords, and regex
patterns. No function definitions, no side effects, no code execution. The
`.lang` extension signals this is a data format, not executable Clojure.

## How It Works

At load time, Winze:

1. Reads each `.lang` file with `clojure.core/read` (`*read-eval*` = `false`)
2. Validates the structure (see Validation below)
3. Extracts the pattern string from each `#"..."` regex literal
4. Concatenates all rule patterns with `|` into a single master regex
5. Compiles the master regex into a `java.util.regex.Pattern`
6. Builds a role lookup from group index → role keyword
7. Produces a tokenize function and registers it for the language's `:tags`

At highlight time, the tokenize function runs `Matcher.find()` in a loop over
the source code. Each match is assigned a role based on which capture group
matched. For `:token/identifier` matches, word sets provide secondary
classification.

## Top-Level Fields

| Field | Required | Type | Default | Description |
|-------|----------|------|---------|-------------|
| `:tags` | Yes | `[string]` | — | Language tags that match code fence info strings. E.g., `["javascript" "js"]` matches ` ```javascript ` and ` ```js `. |
| `:rules` | Yes | `[map]` | — | Ordered vector of tokenizer rules (see Rules below). |
| `:case-sensitive` | No | `boolean` | `true` | When `false`, keyword and builtin lookups lowercase the matched text before checking set membership. Use `false` for case-insensitive languages like SQL. |
| `:keywords` | No | `#{string}` | `#{}` | Words that map to `:token/keyword` when matched by a `:token/identifier` rule. |
| `:builtins` | No | `#{string}` | `#{}` | Words that map to `:token/builtin` when matched by a `:token/identifier` rule. |
| `:type-pattern` | No | `#"regex"` | — | A regex applied to `:token/identifier` matches after keyword and builtin checks. If it matches the full text, the token becomes `:token/type`. Useful for languages where capitalized identifiers are types (e.g., `#"[A-Z].*"` for Python, JavaScript). |

## Rules

Each rule is a map with these fields:

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `:regex` | Yes | `#"regex"` | A Clojure regex literal. **Must contain exactly one capture group** `(...)`. See the One Capture Group Rule below. |
| `:role` | Yes | `keyword` | The token role to assign when this rule matches. See Token Roles below. |
| `:comment` | No | `string` | Human-readable description. Ignored at runtime. |

### Rule ordering = match priority

Rules are tried in the order they appear in the vector. When two rules could
match at the same position, the **first** rule wins. This is because all rule
regexes are joined into a single alternation (`rule1|rule2|rule3|...`) and
Java's regex engine tries alternatives left-to-right.

**Always place more specific rules before less specific ones:**

```clojure
;; CORRECT — triple-quoted strings before regular strings
[{:regex #"(\"\"\"[\s\S]*?\"\"\")"       :role :token/string}   ; triple-quote
 {:regex #"(\"(?:[^\"\\]|\\.)*\")"       :role :token/string}]  ; regular string

;; WRONG — regular string regex would match the opening """ first
[{:regex #"(\"(?:[^\"\\]|\\.)*\")"       :role :token/string}   ; matches """
 {:regex #"(\"\"\"[\s\S]*?\"\"\")"       :role :token/string}]  ; never reached
```

Common priority ordering:
1. Comments (prevent comment markers inside strings from matching)
2. Multi-line strings / triple-quoted strings
3. Single-line strings
4. Regex literals, character literals (language-specific)
5. Keywords as literal patterns (only if not using word sets)
6. Numbers
7. Operators / punctuation
8. Identifiers (catch-all, with word-set classification)

### The One Capture Group Rule

**Each rule's `:regex` must contain exactly one capture group `(...)`.**

This is not optional — the entire compilation model depends on it. The master
regex is built by joining all rule patterns with `|`. After a match, the
engine checks `Matcher.group(1)`, `Matcher.group(2)`, etc. to determine
which rule matched. If rule N has one capture group, it corresponds to
`Matcher.group(N)`. If any rule has zero or two groups, the numbering shifts
and every subsequent rule maps to the wrong role.

```clojure
;; CORRECT — one group
{:regex #"(;[^\n]*)" :role :token/comment}

;; WRONG — zero groups (nothing to capture)
{:regex #";[^\n]*" :role :token/comment}

;; WRONG — two groups (shifts all subsequent group indices)
{:regex #"(;([^\n]*))" :role :token/comment}
```

**If you need grouping for alternation or quantification inside your regex,
use non-capturing groups `(?:...)`:**

```clojure
;; CORRECT — non-capturing group for alternation, one capturing group overall
{:regex #"((?:true|false|null)\b)" :role :token/keyword}

;; WRONG — two capturing groups
{:regex #"((true|false|null)\b)" :role :token/keyword}
```

The loader validates this at load time and reports an error with the filename
and rule index if a rule has the wrong number of capture groups.

## Token Roles

These are the roles recognized by the Winze theme engine. Each maps to a
specific color and font in the editor.

| Role | Meaning | Typical use |
|------|---------|-------------|
| `:token/comment` | Comments | `// ...`, `# ...`, `/* ... */`, `; ...` |
| `:token/string` | String literals | `"..."`, `'...'`, template literals, regex literals, char literals |
| `:token/number` | Numeric literals | `42`, `3.14`, `0xFF`, hex colors in CSS |
| `:token/keyword` | Language keywords | `if`, `def`, `SELECT`, `function` |
| `:token/builtin` | Built-in names | `print`, `console`, `echo`, decorators |
| `:token/type` | Type names | `String`, `MyClass` (typically capitalized identifiers) |
| `:token/operator` | Operators, punctuation | `+`, `=>`, `{}`, `;`, `\|\|` |
| `:token/default` | Unclassified identifiers | Regular variable and function names |
| `:token/identifier` | **Classification trigger** | Not a visual role — see below |

### The `:token/identifier` role

`:token/identifier` is special. It is **not** rendered directly. When a match
falls on a rule with `:role :token/identifier`, the matched text is classified
through this chain:

1. Is the text in `:keywords`? → `:token/keyword`
2. Is the text in `:builtins`? → `:token/builtin`
3. Does the text match `:type-pattern`? → `:token/type`
4. Otherwise → `:token/default`

If `:case-sensitive` is `false`, the text is lowercased before steps 1 and 2.

Use `:token/identifier` for the catch-all "any identifier" rule. Use direct
roles (`:token/keyword`, `:token/string`, etc.) for rules that match
structural patterns that don't need secondary classification.

**Languages without identifiers** (like JSON and YAML where keywords are
literal patterns like `true`/`false`/`null`) don't need `:token/identifier`
at all — use `:token/keyword` directly on a regex that matches those literals:

```clojure
{:regex #"((?:true|false|null)\b)" :role :token/keyword}
```

## Complete Example: Rust

```clojure
{:tags ["rust" "rs"]
 :case-sensitive true
 :rules
 [{:regex #"(//[^\n]*|/\*[\s\S]*?\*/)"
   :role :token/comment
   :comment "Line comment or block comment"}
  {:regex #"(\"(?:[^\"\\]|\\.)*\")"
   :role :token/string
   :comment "String literal"}
  {:regex #"(r#*\"[\s\S]*?\"#*)"
   :role :token/string
   :comment "Raw string literal"}
  {:regex #"('(?:[^'\\]|\\.)')"
   :role :token/string
   :comment "Character literal"}
  {:regex #"(\d[\d_]*\.?[\d_]*(?:[eE][-+]?\d+)?(?:f32|f64|i8|i16|i32|i64|i128|u8|u16|u32|u64|u128|usize|isize)?)"
   :role :token/number
   :comment "Numeric literal with optional type suffix"}
  {:regex #"(0[xX][0-9a-fA-F_]+|0[oO][0-7_]+|0[bB][01_]+)"
   :role :token/number
   :comment "Hex, octal, binary literal"}
  {:regex #"('[a-zA-Z_]\w*)"
   :role :token/type
   :comment "Lifetime parameter"}
  {:regex #"(=>|->|::|\.\.=?|&&|\|\||<<|>>|[+\-*/%=!<>&|^~?@#])"
   :role :token/operator
   :comment "Operators"}
  {:regex #"([a-zA-Z_][a-zA-Z0-9_]*!?)"
   :role :token/identifier
   :comment "Identifier (includes macros with trailing !)"}]
 :keywords
 #{"as" "async" "await" "break" "const" "continue" "crate" "dyn" "else"
   "enum" "extern" "false" "fn" "for" "if" "impl" "in" "let" "loop"
   "match" "mod" "move" "mut" "pub" "ref" "return" "self" "Self"
   "static" "struct" "super" "trait" "true" "type" "unsafe" "use"
   "where" "while" "yield"}
 :builtins
 #{"println" "eprintln" "format" "panic" "assert" "assert_eq"
   "assert_ne" "debug_assert" "vec" "todo" "unimplemented"
   "unreachable" "cfg" "include" "include_str" "include_bytes"
   "env" "option_env" "concat" "stringify" "file" "line" "column"
   "module_path" "compile_error"
   "Box" "Vec" "String" "HashMap" "HashSet" "BTreeMap" "BTreeSet"
   "Option" "Result" "Some" "None" "Ok" "Err"
   "Arc" "Rc" "Mutex" "RwLock" "Cell" "RefCell"
   "Iterator" "IntoIterator" "FromIterator"
   "Display" "Debug" "Clone" "Copy" "Default" "Drop"
   "From" "Into" "TryFrom" "TryInto" "AsRef" "AsMut"
   "Send" "Sync" "Sized" "Unpin"}
 :type-pattern #"[A-Z].*"}
```

## Common Patterns Reference

### Line comments

```clojure
;; Python / Shell style: # to end of line
{:regex #"(#[^\n]*)" :role :token/comment}

;; C / Java / JS style: // to end of line
{:regex #"(//[^\n]*)" :role :token/comment}

;; SQL style: -- to end of line
{:regex #"(--[^\n]*)" :role :token/comment}

;; Clojure / Lisp style: ; to end of line
{:regex #"(;[^\n]*)" :role :token/comment}
```

### Block comments

```clojure
;; C-style /* ... */ (non-greedy, spans newlines)
{:regex #"(/\*[\s\S]*?\*/)" :role :token/comment}

;; Combined line + block comment in one rule
{:regex #"(//[^\n]*|/\*[\s\S]*?\*/)" :role :token/comment}
```

### Strings

```clojure
;; Double-quoted with backslash escapes
{:regex #"(\"(?:[^\"\\]|\\.)*\")" :role :token/string}

;; Single-quoted with backslash escapes
{:regex #"('(?:[^'\\]|\\.)*')" :role :token/string}

;; Both in one rule
{:regex #"(\"(?:[^\"\\]|\\.)*\"|'(?:[^'\\]|\\.)*')" :role :token/string}

;; Python triple-quoted (must come BEFORE regular strings)
{:regex #"(\"\"\"[\s\S]*?\"\"\"|'''[\s\S]*?''')" :role :token/string}

;; JS template literals
{:regex #"(`(?:[^`\\]|\\.)*`)" :role :token/string}
```

### Numbers

```clojure
;; Integer and decimal
{:regex #"(\d+\.?\d*)" :role :token/number}

;; With scientific notation
{:regex #"(\d+\.?\d*(?:[eE][-+]?\d+)?)" :role :token/number}

;; With hex, binary, octal
{:regex #"(\d+\.?\d*(?:[eE][-+]?\d+)?|0[xX][0-9a-fA-F]+|0[bB][01]+|0[oO][0-7]+)"
 :role :token/number}
```

### Identifiers (catch-all)

```clojure
;; Standard: letter or underscore, then word characters
{:regex #"([a-zA-Z_][a-zA-Z0-9_]*)" :role :token/identifier}

;; With dots and hyphens (CSS property names, Clojure symbols)
{:regex #"([a-zA-Z_][a-zA-Z0-9_.-]*)" :role :token/identifier}

;; Clojure-style (includes !, ?, -, >, <, etc.)
{:regex #"([a-zA-Z_$!?+\-*/<>=][\w./*+\-!?'<>=]*)" :role :token/identifier}
```

### Keyword literals (no word-set classification needed)

```clojure
;; JSON/YAML true/false/null
{:regex #"((?:true|false|null)\b)" :role :token/keyword}
```

### Variables / interpolation

```clojure
;; Shell variables: $HOME, ${PATH}
{:regex #"(\$\{[^}]+\}|\$[a-zA-Z_][a-zA-Z0-9_]*)" :role :token/builtin}

;; Python decorators: @staticmethod
{:regex #"(@[a-zA-Z_][a-zA-Z0-9_.]*)" :role :token/builtin}
```

## Override Semantics

When a file in `~/.winze/languages/` has `:tags` that overlap with a built-in
language, the user file **replaces** the built-in tokenizer for those tags.
Load order is: built-in first, then user. User registrations overwrite built-in
registrations.

To extend a built-in language (e.g., add keywords), copy the built-in `.lang`
file to `~/.winze/languages/`, edit it, and save. There is no merge mechanism —
the user file is a complete replacement.

## Troubleshooting

**No highlighting appears**:
- Check that `:tags` includes the tag used in the code fence (e.g., `py` vs.
  `python` — include both)
- Check the server log for validation errors at startup
- Ensure every rule has exactly one capture group

**Wrong tokens get the wrong color**:
- A rule with two capture groups shifts all subsequent group indices. Check
  for `(` that should be `(?:` (non-capturing)
- Rules are priority-ordered — ensure more specific patterns come first

**Regex doesn't match expected text**:
- Test your regex in a Java regex tester (not JavaScript — syntax differs
  slightly, e.g., `\s` includes vertical tab in Java but not JS)
- Block comments with `.*` won't span newlines — use `[\s\S]*?` instead
- With `#"..."` literals, write the regex exactly as you'd write it in a tester
  — no double-escaping needed

**Keywords not recognized**:
- Ensure you have a rule with `:role :token/identifier` that matches
  identifiers. Without it, the keyword/builtin sets are never consulted
- Check `:case-sensitive` — SQL keywords need `false` because `SELECT` and
  `select` should both highlight
