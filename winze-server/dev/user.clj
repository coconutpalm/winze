(ns user
  (:require
   [clojure.tools.namespace.repl :as repl]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as string]
   [portal.api :as p]
   [hyperfiddle.rcf :as rcf]
   [llm-memory.test-support :as ts :refer [debug-format]]))

;;========================================================================================
;; Utilities for development REPL below...

;; Enable RCF test macro — (tests ...) blocks execute on require/reload
(rcf/enable!)

;;========================================================================================
;; Portal data visualizer support

(defn portal
  "Open a standalone Portal debugger window."
  []
  (p/open))

(defn portal-vsc
  "Open a Portal view in Visual Studio Code (if the REPL is running inside VSC
   and the Portal extension is installed in VSC)."
  []
  (p/open {:launcher :vs-code}))

(defn portal-ij
  "Open a Portal view in Intellij (if the REPL is running inside Intellij
   and the Portal extension is installed in Intellij)."
  []
  (p/open {:launcher :intellij}))

;;========================================================================================
;; `tap>` and general debug output support that shouldn't live in prod code

(def ^{:doc "Retains evaluation metadata from the last REPL operation when Portal is active."}
  *repl-eval-metadata nil)

(defn- repl-output?
  [x]
  (let [vals (map (fn [kw] (kw x)) [:ns :time :file :column :line :runtime :code])]
    (every? identity vals)))

(defn- tap-printer
  [x]
  (if (string? x)
    (println x)
    (if (repl-output? x) ; Don't print (tap> ) output from REPL evaluations; these are already printed.
      (when-not (string/ends-with? (string/trim (:code x)) "*repl-eval-metadata")
        (alter-var-root #'*repl-eval-metadata (constantly x)))
      (pprint x))))

(defn- init-taps
  "(tap> something) sends to both Portal (if it's open) and pprint."
  []
  ;; Remove taps if they're there (does nothing if they aren't)
  (remove-tap #'p/submit)
  (remove-tap #'tap-printer)

  (add-tap #'p/submit)
  (add-tap #'tap-printer))

(init-taps)

;;========================================================================================
;; Various debug output functions and macros.

(defn format-code-metadata [ns loc] (merge {:package (ns-name ns)} loc))

(defmacro code-metadata
  "Return source position in the form `{:package package-name :line 1 :column 1}`.

   The nullary form is for standalone usage.  The unary form must be used
   if you're calling this from within another macro as done in
   `devtap>` below."
  ([]
   (let [pos (meta &form)]
     `(format-code-metadata *ns* ~pos)))
  ([form-meta]
   `(format-code-metadata *ns* ~form-meta)))

(defmacro dbg>
  "Print a header, then `xs`.  Returns (last `xs`)."
  [& xs]
  (let [xss (if (seq? xs) (vec xs) [xs])
        m (code-metadata (meta &form))]
    `(let [xs# ~xss]
       (print
        (debug-format #(do (pr %) (println)) (quote ~m) xs#))
       (last xs#))))

(defmacro dbgpp>
  "Print a header, then pprint `xs`.  Returns (last `xs`)"
  [& xs]
  (let [xss (if (seq? xs) (vec xs) [xs])
        m (code-metadata (meta &form))]
    `(let [xs# ~xss]
       (print
        (debug-format pprint (quote ~m) xs#))
       (last xs#))))

;;========================================================================================
;; REPL refresh support

(defn refresh
  "Refresh the REPL by reloading changed namespaces."
  []
  (repl/refresh))
