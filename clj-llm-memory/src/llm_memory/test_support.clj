(ns llm-memory.test-support
  "Shared test helpers and dev utilities.

   Test helpers (fresh-store, tmp-dir, etc.) are used from inline RCF (tests ...) blocks.
   Dev utilities (debug-format) are used from dev/user.clj REPL namespaces."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [llm-memory.store.protocol :as store]
            [llm-memory.store.datalevin :as dtlv]
            [llm-memory.embed.inference4j :as i4j]
            [llm-memory.embed.protocol :as embed])
  (:import [java.io File]))

(defonce ^:private test-embedder (delay (i4j/create-embedder)))

(defn tmp-dir
  "Create a temp directory with Plans/ subdirectories."
  ^File []
  (let [d (io/file (str "/tmp/llm-idx-test-" (System/nanoTime)))]
    (.mkdirs d)
    d))

(defn fresh-store
  "Create and connect a fresh DatalevinPlanStore at a temp path."
  []
  (let [s (dtlv/create-store {:path (str "/tmp/llm-store-" (System/nanoTime))
                              :embedder @test-embedder})]
    (store/connect! s)
    s))

(defn write-plan!
  "Write a markdown file under root/Plans/rel-path."
  [^File root rel-path content]
  (let [f (io/file root "Plans" rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)
    f))

(defn root-uri
  "Build a file:// URI from a root File."
  [^File root]
  (str "file://" (.getAbsolutePath root)))

(defn seed-root!
  "Register a root and transact a file with one chunk.
   For tests that need pre-seeded data without going through index-file!."
  [store {:keys [root-uri root-name file-id file-path text slug
                 status type group]}]
  (store/transact! store [{:root/uri root-uri
                           :root/name root-name
                           :root/plans-dir "Plans"}])
  (store/transact! store [{:file/id file-id
                           :file/path file-path
                           :file/root [:root/uri root-uri]
                           :file/content-hash (str (hash text))
                           :file/modified (quot (System/currentTimeMillis) 1000)
                           :file/status status
                           :file/type type
                           :file/group group}
                          {:chunk/id (str file-id "::" slug)
                           :chunk/file [:file/id file-id]
                           :chunk/text text
                           :chunk/vec (embed/embed-text @test-embedder text)
                           :chunk/slug slug
                           :chunk/section 0}]))

;; ---------------------------------------------------------------------------
;; Dev REPL utilities
;; ---------------------------------------------------------------------------

(defn debug-format
  "Return a formatted string of debug information.

   `printer` is a function that serializes its arguments to `*out*` — typically
   `#(do (pr %) (println))` or `clojure.pprint/pprint`.

   `code-meta` identifies the source location (from the `code-metadata` macro
   in the dev `user` namespace).

   `xs` are the values to output via `printer`."
  [printer code-meta xs]
  (with-out-str
    (println (str ">> " code-meta))
    (doall (map printer xs))
    (println "<<<<")))

(comment
  (debug-format pp/pprint {:package 'user :line 42} [{:a 1} {:b 2}])
  :rcf)
