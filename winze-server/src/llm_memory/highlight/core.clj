(ns llm-memory.highlight.core
  (:require
   [clojure.string :as str]
   [hyperfiddle.rcf :refer [tests]]))

(defonce ^:private registry (atom {}))

(defn register-tokenizer!
  "Register `tokenize-fn` for each language `tags`."
  [tags tokenize-fn]
  (doseq [tag tags]
    (swap! registry assoc (str/lower-case tag) tokenize-fn)))

(defn tokenize
  "Return a seq of {:start int :length int :type keyword} for `code`,
  dispatching to the tokenizer registered for `lang`.
  Returns an empty seq for unknown languages."
  [lang code]
  (if-let [f (get @registry (str/lower-case (or lang "")))]
    (f code)
    []))

(tests
 (tokenize "unknown-language" "hello") := []
 (tokenize nil "hello") := []
 (tokenize "" "hello") := []
 :rcf)
