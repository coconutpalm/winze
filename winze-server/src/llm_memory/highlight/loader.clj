(ns llm-memory.highlight.loader
  "Load, validate, compile, and register .lang tokenizer definitions.
  Built-in languages ship in resources/languages/; user-contributed
  languages live in ~/.winze/languages/."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [llm-memory.highlight.core :as core]
   [clojure.tools.logging :as log]
   [hyperfiddle.rcf :refer [tests]])
  (:import
   [java.io PushbackReader]
   [java.util.regex Pattern]))

;; ---------------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------------

(defn- read-lang-source
  "Read a .lang definition from any io/reader-compatible source (File, URL, etc.).
  `label` is used in error messages. Returns the parsed data structure or nil."
  [source label]
  (try
    (with-open [rdr (PushbackReader. (io/reader source))]
      (binding [*read-eval* false]
        (read rdr)))
    (catch Exception e
      (log/error "Failed to read" label (.getMessage e))
      nil)))

(defn- classpath-lang-urls
  "Return a sorted seq of [name URL] pairs for all .lang files under
  the 'languages/' classpath prefix. Works inside JARs and on the filesystem."
  []
  (let [loader (clojure.lang.RT/baseLoader)]
    (when-let [root-url (.getResource loader "languages/")]
      (case (.getProtocol root-url)
        ;; Development: resource is a real directory
        "file"
        (->> (.listFiles (io/file root-url))
             (filter #(str/ends-with? (.getName %) ".lang"))
             (sort-by #(.getName %))
             (map (fn [f] [(.getName f) (.toURL (.toURI f))])))

        ;; Uberjar: enumerate JAR entries
        "jar"
        (let [conn    (.openConnection root-url)
              jar-file (.getJarFile conn)]
          (->> (enumeration-seq (.entries jar-file))
               (filter #(and (str/starts-with? (.getName %) "languages/")
                             (str/ends-with? (.getName %) ".lang")))
               (sort-by #(.getName %))
               (map (fn [entry]
                      (let [name (last (str/split (.getName entry) #"/"))]
                        [name (.getResource loader (.getName entry))])))))

        ;; Unknown protocol — skip
        nil))))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn- count-capture-groups
  "Count capture groups in a compiled regex Pattern.
  Uses Java's Matcher.groupCount for correct handling of character classes,
  non-capturing groups, lookahead, etc."
  [^Pattern pat]
  (.groupCount (re-matcher pat "")))

(defn- validate-rule
  "Validate a single rule map. Returns nil on success, error string on failure."
  [{:keys [regex role]} idx filename]
  (cond
    (not (instance? Pattern regex))
    (format "%s rule %d: :regex must be a regex literal #\"...\"" filename idx)

    (not (keyword? role))
    (format "%s rule %d: :role must be a keyword" filename idx)

    (not= 1 (count-capture-groups regex))
    (format "%s rule %d: :regex must have exactly one capture group, found %d"
            filename idx (count-capture-groups regex))

    :else nil))

(defn- validate-language
  "Validate a language definition map. Returns [errors] (empty = valid)."
  [lang-def filename]
  (let [errors (transient [])]
    (when-not (and (vector? (:tags lang-def))
                   (every? string? (:tags lang-def)))
      (conj! errors (str filename ": :tags must be a vector of strings")))
    (when-not (vector? (:rules lang-def))
      (conj! errors (str filename ": :rules must be a vector of maps")))
    (when (vector? (:rules lang-def))
      (doseq [[idx rule] (map-indexed vector (:rules lang-def))]
        (when-let [err (validate-rule rule idx filename)]
          (conj! errors err))))
    (when (and (:type-pattern lang-def)
               (not (instance? Pattern (:type-pattern lang-def))))
      (conj! errors (str filename ": :type-pattern must be a regex literal")))
    (persistent! errors)))

;; ---------------------------------------------------------------------------
;; Compilation
;; ---------------------------------------------------------------------------

(defn compile-language
  "Compile a validated .lang definition into a tokenize-fn.
  The returned function takes a code string and returns a vector of
  {:start int :length int :type keyword} maps."
  [{:keys [rules keywords builtins type-pattern case-sensitive]
    :or   {case-sensitive true}}]
  (let [master-re  (re-pattern
                    (str/join "|" (map #(.pattern ^Pattern (:regex %)) rules)))
        role-index (mapv :role rules)
        n-roles    (count role-index)
        kw-set     (if case-sensitive
                     (or keywords #{})
                     (into #{} (map str/lower-case) (or keywords #{})))
        bl-set     (if case-sensitive
                     (or builtins #{})
                     (into #{} (map str/lower-case) (or builtins #{})))
        type-re    type-pattern
        normalize  (if case-sensitive identity str/lower-case)]
    (fn [code]
      (let [matcher (re-matcher master-re code)]
        (loop [acc []]
          (if (.find matcher)
            (let [s    (.start matcher)
                  e    (.end matcher)
                  text (.group matcher)
                  role (loop [i 0]
                         (if (>= i n-roles)
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

;; ---------------------------------------------------------------------------
;; Loading and registration
;; ---------------------------------------------------------------------------

(defn- load-and-register!
  "Read, validate, compile, and register a single .lang source.
  `source` is anything io/reader accepts (File, URL). `label` is for logging.
  Returns a vector of error strings (empty on success)."
  [source label]
  (if-let [lang-def (read-lang-source source label)]
    (let [errors (validate-language lang-def label)]
      (if (seq errors)
        (do (doseq [err errors] (log/warn err))
            (vec errors))
        (do (core/register-tokenizer! (:tags lang-def) (compile-language lang-def))
            (log/info "Loaded language" (:tags lang-def) "from" label)
            [])))
    [(str label ": failed to read")]))

(defn- user-lang-dir
  "Return the user languages directory, creating it if needed."
  []
  (let [d (io/file (System/getProperty "user.home") ".winze" "languages")]
    (when-not (.isDirectory d) (.mkdirs d))
    d))

(defn load-languages!
  "Load all .lang files from the classpath and user directory.
  User languages override built-in ones (by :tags overlap).
  Returns a vector of validation error strings (empty if all loaded cleanly)."
  []
  (let [errors (transient [])]
    ;; Built-in languages from classpath (works in both dev and uberjar)
    (doseq [[name url] (classpath-lang-urls)]
      (doseq [err (load-and-register! url name)]
        (conj! errors err)))
    ;; User-contributed languages from ~/.winze/languages/
    (let [d (user-lang-dir)]
      (when (.isDirectory d)
        (doseq [file (sort (.listFiles d))
                :when (str/ends-with? (.getName file) ".lang")]
          (doseq [err (load-and-register! file (.getName file))]
            (conj! errors err)))))
    (persistent! errors)))

(def reload-languages!
  "Re-read all .lang files and re-register tokenizers.
  Useful for REPL development and testing user-contributed languages."
  load-languages!)

;; ---------------------------------------------------------------------------
;; Auto-load on require
;; ---------------------------------------------------------------------------

(defonce startup-errors (atom []))

(defonce _init
  (reset! startup-errors (load-languages!)))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(tests
 ;; count-capture-groups
 ;; count-capture-groups — takes a Pattern
 (count-capture-groups #"(foo)") := 1
 (count-capture-groups #"(foo)(bar)") := 2
 (count-capture-groups #"(?:foo)(bar)") := 1
 (count-capture-groups #"no groups") := 0
 ;; Parens inside character classes are literals, not groups
 (count-capture-groups #"([{}();:,])") := 1
 (count-capture-groups #"([|&;()<>]|>>|<<|&&|\|\|)") := 1

 ;; validate-rule — valid
 (validate-rule {:regex #"(foo)" :role :token/comment} 0 "test.lang") := nil

 ;; validate-rule — missing group
 (string? (validate-rule {:regex #"foo" :role :token/comment} 0 "test.lang")) := true

 ;; validate-rule — not a Pattern
 (string? (validate-rule {:regex "foo" :role :token/comment} 0 "test.lang")) := true

 ;; validate-language — returns errors for bad rules
 (let [bad-lang {:tags  ["test"]
                 :rules [{:regex #"foo" :role :token/comment}]}]
   (count (validate-language bad-lang "test.lang"))) := 1

 ;; validate-language — empty errors for valid lang
 (let [good-lang {:tags  ["test"]
                  :rules [{:regex #"(foo)" :role :token/comment}]}]
   (validate-language good-lang "test.lang")) := []

 ;; load-languages! returns empty errors when all files are valid
 (vector? (load-languages!)) := true

 ;; compile-language with a minimal definition
 (let [lang {:tags          ["test"]
             :case-sensitive true
             :rules         [{:regex #"(;[^\n]*)" :role :token/comment}
                             {:regex #"(\"[^\"]*\")" :role :token/string}
                             {:regex #"(\d+)" :role :token/number}
                             {:regex #"([a-z]+)" :role :token/identifier}]
             :keywords      #{"if" "else"}
             :builtins      #{"print"}}
       tok (compile-language lang)]
   (tok "") := []
   (tok "; hi") := [{:start 0 :length 4 :type :token/comment}]
   (tok "\"s\"") := [{:start 0 :length 3 :type :token/string}]
   (tok "42") := [{:start 0 :length 2 :type :token/number}]
   (tok "if") := [{:start 0 :length 2 :type :token/keyword}]
   (tok "print") := [{:start 0 :length 5 :type :token/builtin}]
   (tok "foo") := [{:start 0 :length 3 :type :token/default}])

 ;; case-insensitive keywords
 (let [lang {:tags          ["test"]
             :case-sensitive false
             :rules         [{:regex #"([a-zA-Z]+)" :role :token/identifier}]
             :keywords      #{"select"}}
       tok (compile-language lang)]
   (tok "SELECT") := [{:start 0 :length 6 :type :token/keyword}]
   (tok "select") := [{:start 0 :length 6 :type :token/keyword}])

 ;; type-pattern
 (let [lang {:tags          ["test"]
             :case-sensitive true
             :rules         [{:regex #"([a-zA-Z]+)" :role :token/identifier}]
             :keywords      #{"if"}
             :type-pattern  #"[A-Z].*"}
       tok (compile-language lang)]
   (tok "MyClass") := [{:start 0 :length 7 :type :token/type}]
   (tok "if") := [{:start 0 :length 2 :type :token/keyword}]
   (tok "foo") := [{:start 0 :length 3 :type :token/default}])

 :rcf)

;; ---------------------------------------------------------------------------
;; Language-specific tests — verify .lang files produce correct output
;; ---------------------------------------------------------------------------

(defn- load-lang
  "Load a .lang file from the classpath and compile it."
  [resource-name]
  (-> (io/resource (str "languages/" resource-name))
      (read-lang-source resource-name)
      compile-language))

(tests
 ;; --- Clojure ---
 (let [tok (load-lang "clojure.lang")]
   (tok "") := []
   (tok "; hello") := [{:start 0 :length 7 :type :token/comment}]
   (tok "\"hello\"") := [{:start 0 :length 7 :type :token/string}]
   (tok "\"say \\\"hi\\\"\"") := [{:start 0 :length 12 :type :token/string}]
   (tok ":foo") := [{:start 0 :length 4 :type :token/keyword}]
   (tok "42") := [{:start 0 :length 2 :type :token/number}]
   (tok "defn") := [{:start 0 :length 4 :type :token/keyword}]
   (tok "count") := [{:start 0 :length 5 :type :token/builtin}]
   (tok "my-fn") := [{:start 0 :length 5 :type :token/default}]
   (let [tokens (tok "(defn foo [x] (+ x 1))")]
     (count tokens) := 6
     (:type (nth tokens 0)) := :token/keyword
     (:type (nth tokens 1)) := :token/default
     (:type (nth tokens 2)) := :token/default
     (:type (nth tokens 3)) := :token/builtin
     (:type (nth tokens 4)) := :token/default
     (:type (nth tokens 5)) := :token/number))

 ;; --- Python ---
 (let [tok (load-lang "python.lang")]
   (tok "") := []
   (tok "# comment") := [{:start 0 :length 9 :type :token/comment}]
   (tok "\"hello\"") := [{:start 0 :length 7 :type :token/string}]
   (tok "42") := [{:start 0 :length 2 :type :token/number}]
   (tok "def") := [{:start 0 :length 3 :type :token/keyword}]
   (tok "print") := [{:start 0 :length 5 :type :token/builtin}]
   (tok "True") := [{:start 0 :length 4 :type :token/keyword}]
   (let [tokens (tok "def foo(x):\n    return x + 1")]
     (:type (first tokens)) := :token/keyword
     (some #(= :token/number (:type %)) tokens) := true))

 ;; --- Shell ---
 (let [tok (load-lang "shell.lang")]
   (tok "") := []
   (tok "# comment") := [{:start 0 :length 9 :type :token/comment}]
   (tok "\"hello\"") := [{:start 0 :length 7 :type :token/string}]
   (tok "$HOME") := [{:start 0 :length 5 :type :token/builtin}]
   (tok "${PATH}") := [{:start 0 :length 7 :type :token/builtin}]
   (tok "if") := [{:start 0 :length 2 :type :token/keyword}]
   (tok "echo") := [{:start 0 :length 4 :type :token/builtin}]
   (let [tokens (tok "for f in *.txt; do echo $f; done")]
     (:type (first tokens)) := :token/keyword
     (some #(= :token/builtin (:type %)) tokens) := true))

 ;; --- SQL ---
 (let [tok (load-lang "sql.lang")]
   (tok "") := []
   (tok "-- comment") := [{:start 0 :length 10 :type :token/comment}]
   (tok "'hello'") := [{:start 0 :length 7 :type :token/string}]
   (tok "42") := [{:start 0 :length 2 :type :token/number}]
   (tok "SELECT") := [{:start 0 :length 6 :type :token/keyword}]
   (tok "select") := [{:start 0 :length 6 :type :token/keyword}]
   (tok "count") := [{:start 0 :length 5 :type :token/builtin}]
   (let [tokens (tok "SELECT name FROM users WHERE id = 1")]
     (:type (first tokens)) := :token/keyword
     (some #(= :token/number (:type %)) tokens) := true))

 ;; --- JavaScript ---
 (let [tok (load-lang "javascript.lang")]
   (tok "") := []
   (tok "// comment") := [{:start 0 :length 10 :type :token/comment}]
   (tok "\"hello\"") := [{:start 0 :length 7 :type :token/string}]
   (tok "42") := [{:start 0 :length 2 :type :token/number}]
   (tok "const") := [{:start 0 :length 5 :type :token/keyword}]
   (tok "console") := [{:start 0 :length 7 :type :token/builtin}]
   (tok "MyClass") := [{:start 0 :length 7 :type :token/type}]
   (let [tokens (tok "const x = 42;")]
     (count tokens) := 5
     (:type (first tokens)) := :token/keyword
     (:type (nth tokens 2)) := :token/operator
     (:type (nth tokens 3)) := :token/number))

 ;; --- HTML ---
 (let [tok (load-lang "html.lang")]
   (tok "") := []
   (tok "<div class=\"foo\">")
   := [{:start 0  :length 4 :type :token/keyword}
       {:start 5  :length 5 :type :token/builtin}
       {:start 11 :length 5 :type :token/string}
       {:start 16 :length 1 :type :token/operator}])

 ;; --- CSS ---
 (let [tok (load-lang "css.lang")]
   (tok "") := []
   (tok ".foo { color: red; }")
   := [{:start 0  :length 4 :type :token/type}
       {:start 5  :length 1 :type :token/operator}
       {:start 7  :length 5 :type :token/builtin}
       {:start 12 :length 1 :type :token/operator}
       {:start 17 :length 1 :type :token/operator}
       {:start 19 :length 1 :type :token/operator}])

 ;; --- JSON ---
 (let [tok (load-lang "json.lang")]
   (tok "") := []
   (tok "\"hello\"") := [{:start 0 :length 7 :type :token/string}]
   (tok "42") := [{:start 0 :length 2 :type :token/number}]
   (tok "true") := [{:start 0 :length 4 :type :token/keyword}]
   (tok "{}") := [{:start 0 :length 1 :type :token/operator}
                  {:start 1 :length 1 :type :token/operator}]
   (let [tokens (tok "{\"key\": 123, \"flag\": true}")]
     (count tokens) := 9
     (:type (first tokens)) := :token/operator))

 ;; --- YAML ---
 (let [tok (load-lang "yaml.lang")]
   (tok "") := []
   (tok "# comment") := [{:start 0 :length 9 :type :token/comment}]
   (tok "key: value") := [{:start 0 :length 3 :type :token/keyword}]
   (tok "\"hello\"") := [{:start 0 :length 7 :type :token/string}]
   (tok "42") := [{:start 0 :length 2 :type :token/number}]
   (tok "true") := [{:start 0 :length 4 :type :token/keyword}]
   (tok "---") := [{:start 0 :length 3 :type :token/operator}])

 :rcf)
