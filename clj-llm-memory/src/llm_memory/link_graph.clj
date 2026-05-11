(ns llm-memory.link-graph
  "Wiki-link graph queries.

  Walks the :link/* entities emitted by index-file! to answer \"who links
  here?\" questions. Propagation (in llm-memory.index) uses these to
  rewrite inbound links when a file or heading is renamed.

  All queries use Datalog's collection-find form `[?x ...]` so callers
  receive a flat de-duplicated seq — a single linker file that contains
  both a file-level link and one or more anchor links to the same target
  appears exactly once in the result."
  (:require [llm-memory.store.protocol :as store]
            [hyperfiddle.rcf :refer [tests]]
            [llm-memory.test-support :as ts]))

(defn inbound-links
  "All files that have a wiki link pointing at target-file-id.
  target-file-id is the bare file-id (no `wiki:` prefix) e.g. \"root::path.md\".
  Returns a vector of distinct file-id strings."
  [store target-file-id]
  (vec (store/query store
                    '[:find [?from-id ...]
                      :in $ ?tid
                      :where
                      [?l :link/to-id ?tid]
                      [?l :link/from ?f]
                      [?f :file/id ?from-id]]
                    {:tid target-file-id})))

(defn heading-inbound-links
  "All files that link to a specific heading anchor (file-id + slug pair).
  slug is the bare anchor (no leading `#`); use \"\" for file-level links.
  Returns a vector of distinct file-id strings."
  [store target-file-id slug]
  (vec (store/query store
                    '[:find [?from-id ...]
                      :in $ ?tid ?slug
                      :where
                      [?l :link/to-id ?tid]
                      [?l :link/slug ?slug]
                      [?l :link/from ?f]
                      [?f :file/id ?from-id]]
                    {:tid target-file-id :slug slug})))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(defn- seed-link!
  "Transact a :link/* entity from `from-fid` → `to-id` (+ optional slug).
   Assumes the :file/id entity for from-fid already exists."
  ([store from-fid to-id] (seed-link! store from-fid to-id ""))
  ([store from-fid to-id slug]
   (store/transact! store [{:link/id    (str from-fid "@@" to-id "@@" slug)
                            :link/from  [:file/id from-fid]
                            :link/to-id to-id
                            :link/slug  slug}])))

(defn- seed-file!
  "Transact a minimal :file/* entity (no chunks/vectors) for link-graph tests."
  [store root-uri fid path]
  (store/transact! store [{:file/id           fid
                           :file/path         path
                           :file/root         [:root/uri root-uri]
                           :file/content-hash "stub-hash"
                           :file/modified     1}]))

(tests
 "inbound-links — returns empty for unknown target"
 (let [s (ts/fresh-store)]
   (inbound-links s "unknown::missing.md") := []
   (store/disconnect! s))
 :rcf)

(tests
 "inbound-links — returns distinct from-ids when multiple links point to same target"
 (let [s (ts/fresh-store)]
   (store/transact! s [{:root/uri "file:///g" :root/name "g" :root/plans-dir "Plans"}])
   (seed-file! s "file:///g" "g::A.md" "A.md")
   (seed-file! s "file:///g" "g::B.md" "B.md")
   (seed-file! s "file:///g" "g::C.md" "C.md")
   ;; A links to B twice (once at file level, once anchored); C links to B once
   (seed-link! s "g::A.md" "g::B.md" "")
   (seed-link! s "g::A.md" "g::B.md" "overview")
   (seed-link! s "g::C.md" "g::B.md" "")
   (let [r (inbound-links s "g::B.md")]
     (set r) := #{"g::A.md" "g::C.md"}
     (count r) := 2)
   (store/disconnect! s))
 :rcf)

(tests
 "heading-inbound-links — slug-specific match"
 (let [s (ts/fresh-store)]
   (store/transact! s [{:root/uri "file:///h" :root/name "h" :root/plans-dir "Plans"}])
   (seed-file! s "file:///h" "h::A.md" "A.md")
   (seed-file! s "file:///h" "h::B.md" "B.md")
   (seed-file! s "file:///h" "h::C.md" "C.md")
   (seed-link! s "h::A.md" "h::T.md" "design")
   (seed-link! s "h::B.md" "h::T.md" "design")
   (seed-link! s "h::C.md" "h::T.md" "overview")
   (set (heading-inbound-links s "h::T.md" "design"))   := #{"h::A.md" "h::B.md"}
   (set (heading-inbound-links s "h::T.md" "overview")) := #{"h::C.md"}
   (heading-inbound-links s "h::T.md" "missing") := []
   (store/disconnect! s))
 :rcf)
