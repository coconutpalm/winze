# Language Tokenizers — Plan

**Prerequisites**:
- Read [EDN-TOKENIZERS-CONTEXT.md](EDN-TOKENIZERS-CONTEXT.md) for format spec
  and design rationale
- Read [EDN-TOKENIZERS-DOCS.md](EDN-TOKENIZERS-DOCS.md) for the `.lang` file
  format reference
- Related: [EDITOR-COMMANDS-CONTEXT.md](EDITOR-COMMANDS-CONTEXT.md)

---

## Step 1 — `.lang` file loader and compiler (`loader.clj`)

Create `winze-server/src/llm_memory/highlight/loader.clj`.

**1a — Safe reader**:

```clojure
(defn- read-lang-file
  "Read a .lang file using the Clojure reader with *read-eval* disabled.
  Returns the parsed data structure or nil on error."
  [file]
  (try
    (with-open [rdr (java.io.PushbackReader. (io/reader file))]
      (binding [*read-eval* false]
        (read rdr)))
    (catch Exception e
      (log/error e "Failed to read" (.getName file))
      nil)))
```

**1b — Validation**:

```clojure
(defn- validate-rule
  "Validate a single rule map. Returns nil on success, error string on failure."
  [{:keys [regex role]} idx filename]
  (cond
    (not (instance? java.util.regex.Pattern regex))
    (format "%s rule %d: :regex must be a regex literal #\"...\"" filename idx)

    (not (keyword? role))
    (format "%s rule %d: :role must be a keyword" filename idx)

    ;; Check exactly one capture group
    (let [pat (.pattern regex)
          groups (count (re-seq #"(?<!\\)\((?!\?)" pat))]
      (not= 1 groups))
    (format "%s rule %d: :regex must have exactly one capture group, found %d"
            filename idx
            (count (re-seq #"(?<!\\)\((?!\?)" (.pattern regex))))

    :else nil))

(defn- validate-language
  "Validate a language definition map. Returns [errors] (empty = valid)."
  [lang-def filename]
  (let [errors (atom [])]
    (when-not (vector? (:tags lang-def))
      (swap! errors conj (str filename ": :tags must be a vector of strings")))
    (when-not (vector? (:rules lang-def))
      (swap! errors conj (str filename ": :rules must be a vector of maps")))
    (when (vector? (:rules lang-def))
      (doseq [[idx rule] (map-indexed vector (:rules lang-def))]
        (when-let [err (validate-rule rule idx filename)]
          (swap! errors conj err))))
    (when (and (:type-pattern lang-def)
               (not (instance? java.util.regex.Pattern (:type-pattern lang-def))))
      (swap! errors conj (str filename ": :type-pattern must be a regex literal")))
    @errors))
```

**1c — Compilation**:

```clojure
(defn compile-language
  "Compile a validated .lang definition into a tokenize-fn."
  [{:keys [rules keywords builtins type-pattern case-sensitive]
    :or   {case-sensitive true}}]
  (let [;; Extract pattern strings from compiled Pattern objects
        master-re  (re-pattern
                     (str/join "|" (map #(.pattern (:regex %)) rules)))
        role-index (mapv :role rules)
        kw-set     (if case-sensitive
                     (or keywords #{})
                     (into #{} (map str/lower-case) (or keywords #{})))
        bl-set     (if case-sensitive
                     (or builtins #{})
                     (into #{} (map str/lower-case) (or builtins #{})))
        type-re    type-pattern   ;; already a compiled Pattern or nil
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

**1d — Loading and registration**:

```clojure
(defn load-languages!
  "Load all .lang files from the given directories.
  Later directories override earlier ones (by :tags overlap)."
  [& dirs]
  (doseq [dir  dirs
          :let [d (io/file dir)]
          :when (and d (.isDirectory d))
          file  (sort (.listFiles d))
          :when (str/ends-with? (.getName file) ".lang")]
    (when-let [lang-def (read-lang-file file)]
      (let [errors (validate-language lang-def (.getName file))]
        (if (seq errors)
          (doseq [err errors] (log/warn err))
          (let [tokenize-fn (compile-language lang-def)]
            (core/register-tokenizer! (:tags lang-def) tokenize-fn)
            (log/info "Loaded language" (:tags lang-def)
                      "from" (.getName file))))))))
```

**1e — RCF tests**:

- `validate-rule` catches missing group, non-Pattern `:regex`, wrong types
- `compile-language` with a minimal inline definition produces correct output
- `read-lang-file` with `*read-eval*` false rejects `#=(...)` forms

**Verify**: REPL — call `compile-language` with a minimal definition, tokenize
a snippet, confirm output matches expectations.

---

## Step 2 — Convert Clojure tokenizer to `.lang`

Create `winze-server/resources/languages/clojure.lang` by transcribing
`highlight/clojure.clj`. With `#"..."` regex literals, the regexes are
identical to the source `.clj` file — no escaping gymnastics:

```clojure
{:tags ["clojure" "clj" "cljs" "edn"]
 :case-sensitive true
 :rules
 [{:regex #"(;[^\n]*)"
   :role :token/comment
   :comment "Line comment"}
  {:regex #"(\"(?:[^\"\\]|\\.)*\")"
   :role :token/string
   :comment "String literal"}
  {:regex #"(#\"(?:[^\"\\]|\\.)*\")"
   :role :token/string
   :comment "Regex literal"}
  {:regex #"(\\(?:newline|space|tab|backspace|formfeed|return|[^ \n]))"
   :role :token/string
   :comment "Character literal"}
  {:regex #"(:[:a-zA-Z_][\w./*+-]*)"
   :role :token/keyword
   :comment "Keyword"}
  {:regex #"([-+]?(?:\d+\.?\d*(?:[eE][-+]?\d+)?|\d+/\d+|0[xX][0-9a-fA-F]+)[MNr]?)"
   :role :token/number
   :comment "Number (decimal, ratio, hex)"}
  {:regex #"([a-zA-Z_$!?+\-*/<>=][\w./*+\-!?'<>=]*)"
   :role :token/identifier
   :comment "Symbol — classified via keywords/builtins"}]
 :keywords
 #{"def" "defn" "defn-" "defonce" "defmacro" "defmethod" "defmulti"
   "defprotocol" "defrecord" "deftype" "defstruct"
   "fn" "fn*" "let" "loop" "recur" "if" "if-let" "if-not" "if-some"
   "when" "when-let" "when-not" "when-some" "when-first"
   "do" "quote" "var" "throw" "try" "catch" "finally"
   "case" "cond" "cond->" "cond->>" "condp"
   "and" "or" "not"
   "for" "doseq" "dotimes" "doall" "dorun"
   "ns" "require" "import" "use" "refer" "in-ns"
   "binding" "with-open" "with-local-vars" "with-redefs"
   "lazy-seq" "lazy-cat" "delay" "future" "promise"
   "atom" "ref" "agent" "swap!" "reset!" "alter" "send" "send-off"
   "deref" "set!" "new" "monitor-enter" "monitor-exit"
   "reify" "proxy" "extend-type" "extend-protocol"
   "->>" "->" "as->" "some->" "some->>"
   "comp" "partial" "juxt" "complement" "constantly" "identity"
   "apply" "map" "filter" "reduce" "into" "mapv" "filterv"
   "assoc" "dissoc" "update" "get" "get-in" "assoc-in" "update-in"
   "conj" "cons" "concat" "first" "rest" "next" "seq" "vec" "set"
   "str" "pr-str" "prn" "println" "format"
   "true" "false" "nil"}
 :builtins
 #{"count" "empty?" "not-empty" "seq?" "map?" "vector?" "set?"
   "string?" "number?" "keyword?" "symbol?" "fn?" "nil?" "some?"
   "pos?" "neg?" "zero?" "even?" "odd?"
   "inc" "dec" "+" "-" "*" "/" "mod" "rem" "quot"
   "=" "==" "not=" "<" ">" "<=" ">="
   "contains?" "keys" "vals" "merge" "select-keys"
   "take" "drop" "partition" "group-by" "sort" "sort-by" "reverse"
   "range" "repeat" "iterate" "cycle"
   "interpose" "interleave" "zipmap" "frequencies"
   "name" "namespace" "keyword" "symbol" "type" "class"
   "re-find" "re-matches" "re-seq" "re-pattern"
   "slurp" "spit" "read-string" "pr-str"
   "nth" "peek" "pop" "subvec"
   "meta" "with-meta" "vary-meta"}}
```

**Verification strategy**: Run the existing RCF test cases from `clojure.clj`
against the `.lang`-compiled tokenizer. Every assertion must pass identically.

```clojure
(let [lang (read-lang-file (io/resource "languages/clojure.lang"))
      tok  (compile-language lang)]
  (tok "")          ;= []
  (tok "; hello")   ;= [{:start 0 :length 7 :type :token/comment}]
  (tok "\"hello\"") ;= [{:start 0 :length 7 :type :token/string}]
  ;; ... all existing RCF assertions from clojure.clj
  )
```

**Verify**: REPL — load the `.lang` Clojure tokenizer, run all existing test
cases, confirm identical output.

---

## Step 3 — Convert remaining built-in languages to `.lang`

Create one `.lang` file per language in `resources/languages/`:

| `.lang` file | Source | Notes |
|--------------|--------|-------|
| `python.lang` | `python.clj` | Has `:type-pattern #"[A-Z].*"` |
| `shell.lang` | `shell.clj` | Straightforward |
| `sql.lang` | `sql.clj` | `:case-sensitive false` |
| `javascript.lang` | `web.clj` (JS section) | Has `:type-pattern #"[A-Z].*"` |
| `html.lang` | `web.clj` (HTML section) | No identifier group, no word sets |
| `css.lang` | `web.clj` (CSS section) | No identifier group, no word sets |
| `json.lang` | `data.clj` (JSON section) | No identifier group, no word sets |
| `yaml.lang` | `data.clj` (YAML section) | No identifier group, no word sets |

For each:
1. Transcribe the regex groups into `:rules` using `#"..."` literals
2. Transcribe the keyword/builtin sets
3. Run the existing RCF test cases against the compiled `.lang` tokenizer
4. Confirm identical output

**Verify**: REPL — for each language, load the `.lang` file, run all existing
test cases from the corresponding `.clj` file, confirm identical output.

---

## Step 4 — Wire loader into startup

**4a — Update `md_theme.clj`**:

Replace:
```clojure
(:require
 [llm-memory.highlight.core :as highlight]
 [llm-memory.highlight.clojure]
 [llm-memory.highlight.data]
 [llm-memory.highlight.python]
 [llm-memory.highlight.shell]
 [llm-memory.highlight.sql]
 [llm-memory.highlight.web]
 ...)
```

With:
```clojure
(:require
 [llm-memory.highlight.core :as highlight]
 [llm-memory.highlight.loader :as highlight-loader]
 ...)
```

**4b — Load on namespace init**:

Add to `loader.clj` (top-level, runs on require):
```clojure
(defonce _init
  (load-languages!
    ;; Built-in (classpath) — io/resource returns a URL, resolve to File
    (some-> (io/resource "languages") io/file)
    ;; User-contributed
    (io/file (System/getProperty "user.home") ".winze" "languages")))
```

**4c — Add `reload-languages!`** for REPL convenience:
```clojure
(defn reload-languages!
  "Re-read all .lang files and re-register tokenizers.
  Useful for REPL development and testing user-contributed languages."
  []
  (load-languages!
    (some-> (io/resource "languages") io/file)
    (io/file (System/getProperty "user.home") ".winze" "languages")))
```

**Verify**: REPL — require `md_theme`, confirm all languages tokenize correctly
via the `.lang`-loaded tokenizers. Open a file with code blocks in the editor,
confirm syntax highlighting works.

---

## Step 5 — Delete old tokenizer namespaces

After confirming all tests pass through the `.lang` tokenizers:

1. Delete `highlight/clojure.clj`
2. Delete `highlight/python.clj`
3. Delete `highlight/shell.clj`
4. Delete `highlight/sql.clj`
5. Delete `highlight/web.clj`
6. Delete `highlight/data.clj`

**Preserve the RCF tests**: Move the test assertions from the old files into
`loader.clj` as tests that load each `.lang` file and verify the compiled
tokenizer's output. This ensures the `.lang` files remain correct across edits.

**Verify**: `make test` passes. Editor syntax highlighting works for all
languages.

---

## Step 6 — Create `~/.winze/languages/` directory

Ensure the user languages directory exists (or is created on first use):

```clojure
(let [user-dir (io/file (System/getProperty "user.home") ".winze" "languages")]
  (when-not (.exists user-dir)
    (.mkdirs user-dir)))
```

Add this to `load-languages!` or server startup. The directory is created
empty; users populate it with `.lang` files.

**Verify**: Start the server, confirm `~/.winze/languages/` exists. Place a
test `rust.lang` there, restart, confirm Rust code blocks get highlighting.

---

## Summary of Changes

| Step | Files | Nature |
|------|-------|--------|
| 1 | `loader.clj` (new) | Safe reader + compiler + validator + loader |
| 2 | `resources/languages/clojure.lang` (new) | First `.lang` conversion |
| 3 | `resources/languages/*.lang` (8 new files) | Remaining `.lang` conversions |
| 4 | `md_theme.clj`, `loader.clj` | Wire into startup |
| 5 | `highlight/{clojure,python,shell,sql,web,data}.clj` (delete) | Remove old code |
| 6 | `loader.clj` | Create user directory |

**Net effect**: ~693 lines of Clojure deleted, replaced by ~150 lines of
`loader.clj` + 9 `.lang` files. User-extensible language highlighting with
readable regex literals.

## Verification Checkpoints

| Step | How |
|------|-----|
| 1 | `compile-language` produces working tokenize-fn from inline definition |
| 2 | Clojure `.lang` tokenizer passes all existing `clojure.clj` RCF tests |
| 3 | All 9 `.lang` tokenizers pass their corresponding RCF tests |
| 4 | Editor syntax highlighting works for all languages after startup |
| 5 | `make test` passes with old files deleted |
| 6 | `~/.winze/languages/rust.lang` works after restart |
