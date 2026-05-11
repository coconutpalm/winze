(ns llm-memory.index
  "Indexing engine — transacts file/chunk entities into the PlanStore.

  Provides:
    index-file!   — index a single markdown file (read, chunk, embed, transact)
    retract-file! — remove a file and its chunks
    rename-file!  — update path/metadata preserving entity identity
    reconcile!    — diff db vs. disk, handle all six categories
    index-root!   — full reindex (drop + re-create)"
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [llm-memory.chunk :as chunk]
            [llm-memory.frontmatter :as fm]
            [llm-memory.link-graph :as link-graph]
            [llm-memory.link-rewriter :as link-rewriter]
            [llm-memory.metadata :as meta']
            [llm-memory.embed.protocol :as embed]
            [llm-memory.store.protocol :as store]
            [hyperfiddle.rcf :refer [tests]]
            [llm-memory.test-support :as ts])
  (:import [java.io File]
           [java.security MessageDigest]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- sha256
  "Compute SHA-256 hex digest of a string."
  [^String text]
  (let [md    (MessageDigest/getInstance "SHA-256")
        bytes (.digest md (.getBytes text "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

(defn- resolve-root
  "Look up a root entity by URI. Returns {:eid :root/uri :root/name :root/plans-dir}."
  [store root-uri]
  (let [results (store/query store
                             '[:find ?e ?name ?dir
                               :in $ ?uri
                               :where
                               [?e :root/uri ?uri]
                               [?e :root/name ?name]
                               [?e :root/plans-dir ?dir]]
                             {:uri root-uri})]
    (when-let [[eid root-name plans-dir] (first results)]
      {:eid eid :root/uri root-uri :root/name root-name :root/plans-dir plans-dir})))

(defn- plans-dir-path
  "Resolve the absolute Plans/ directory path for a root URI."
  [root-uri plans-dir]
  (let [path (str/replace root-uri #"^file://" "")]
    (io/file path plans-dir)))

(defn- rel-path
  "Compute relative path from plans-dir to file."
  [^File plans-dir ^File file]
  (.toString (.relativize (.toPath plans-dir) (.toPath file))))

(defn compute-file-id
  "Single source of truth for the `root-name::rel-path` file-id formula.

  Every caller that constructs a file-id — index-file!, rename-file!,
  retract-file!, watcher/handle-delete!, watcher/stored-hash — delegates
  to this helper. Do not reproduce the formula inline anywhere.

  Public (defn, not defn-) so watcher.clj can call it across namespaces."
  [^String root-name ^File plans-abs-dir ^String abs-path]
  (str root-name "::" (rel-path plans-abs-dir (io/file abs-path))))

(defn- extract-outbound-wiki-links
  "Parse markdown `body` (frontmatter already stripped), return distinct
  `{:to-id str :slug str}` maps for every verified inline wiki link.

  Delegates to link-rewriter/extract-wiki-links — which already filters
  reference-style links, synthetic links, and any node whose span cannot
  be exact-match-verified. Dropping unverified links here keeps the
  `:link/*` graph free of entities that could never be rewritten on a
  target rename."
  [body]
  (let [recs (link-rewriter/extract-wiki-links body)]
    (into []
          (comp
           (map (fn [{:keys [destination]}]
                  (let [bare (subs destination (count "wiki:"))
                        [to-id slug] (str/split bare #"#" 2)]
                    {:to-id to-id :slug (or slug "")})))
           (distinct))
          recs)))

(defn- file-modified-epoch
  "File modification time as Unix epoch seconds."
  [^File file]
  (quot (.lastModified file) 1000))

(defn- file-modified-str
  "File modification time as ISO date string."
  [^File file]
  (let [instant (java.time.Instant/ofEpochSecond (file-modified-epoch file))
        date    (java.time.LocalDate/ofInstant instant (java.time.ZoneId/of "UTC"))]
    (.toString date)))

;; ---------------------------------------------------------------------------
;; Vector math for fuzzy rename detection
;; ---------------------------------------------------------------------------

(def ^:private ^:const rename-similarity-threshold
  "Minimum cosine similarity to consider a gone+new pair as a rename+edit.
   0.6 is conservative — typical renamed+lightly-edited files score 0.8+."
  0.6)

(defn- cosine-similarity
  "Cosine similarity between two float arrays. Returns 0.0–1.0."
  ^double [^floats a ^floats b]
  (let [len (alength a)]
    (loop [i 0, dot (float 0), na (float 0), nb (float 0)]
      (if (< i len)
        (let [ai (aget a i)
              bi (aget b i)]
          (recur (unchecked-inc i)
                 (+ dot (* ai bi))
                 (+ na  (* ai ai))
                 (+ nb  (* bi bi))))
        (let [denom (* (Math/sqrt na) (Math/sqrt nb))]
          (if (zero? denom) 0.0 (/ dot denom)))))))

(defn- centroid
  "Average of a sequence of float arrays. Returns float[] or nil if empty."
  ^floats [vecs]
  (when (seq vecs)
    (let [n   (count vecs)
          dim (alength ^floats (first vecs))
          sum (float-array dim)]
      (doseq [^floats v vecs]
        (dotimes [i dim]
          (aset sum i (+ (aget sum i) (aget v i)))))
      (dotimes [i dim]
        (aset sum i (/ (aget sum i) (float n))))
      sum)))

(defn- file-centroid
  "Compute the centroid embedding for a file already in the store.
   Queries all chunk vectors for the given file-id and averages them."
  [store file-id]
  (let [vecs (store/query store
                          '[:find [?vec ...]
                            :in $ ?fid
                            :where
                            [?f :file/id ?fid]
                            [?c :chunk/file ?f]
                            [?c :chunk/vec ?vec]]
                          {:fid file-id})]
    (centroid vecs)))

(defn- text-centroid
  "Embed a markdown text and return the centroid of its chunk vectors."
  [embedder text]
  (let [[_ body]  (fm/parse-frontmatter text)
        chunks    (chunk/split-sections body)
        texts     (mapv second chunks)
        vecs      (embed/embed-texts embedder texts)]
    (centroid vecs)))

(defn- match-fuzzy-renames
  "Given unmatched gone paths and new paths, use embedding similarity to detect
   rename+edit pairs. Returns {:renamed-modified [...] :new [...] :gone [...]}.

   Each entry in :renamed-modified is {:old-path str :new-path str :similarity double}."
  [store embedder gone-paths new-paths db-state disk-state]
  (if (or (empty? gone-paths) (empty? new-paths))
    {:renamed-modified [] :new (vec new-paths) :gone (vec gone-paths)}
    (let [;; Compute centroids for gone files (from stored vectors)
          gone-centroids (reduce (fn [acc gp]
                                   (let [fid (:file-id (get db-state gp))
                                         c   (file-centroid store fid)]
                                     (if c (assoc acc gp c) acc)))
                                 {}
                                 gone-paths)
          ;; Compute centroids for new files (embed from disk)
          new-centroids  (reduce (fn [acc np]
                                   (let [abs (:abs-path (get disk-state np))
                                         c   (text-centroid embedder (slurp abs))]
                                     (if c (assoc acc np c) acc)))
                                 {}
                                 new-paths)
          ;; Greedy best-match: for each new file, find best gone match
          matches (atom [])
          used-gone (atom #{})
          ;; Sort new-paths to get deterministic results
          sorted-new (sort new-paths)]
      (doseq [np sorted-new
              :let [nc (get new-centroids np)]
              :when nc]
        (let [best (reduce (fn [best gp]
                             (if (contains? @used-gone gp)
                               best
                               (if-let [gc (get gone-centroids gp)]
                                 (let [sim (cosine-similarity nc gc)]
                                   (if (or (nil? best) (> sim (:similarity best)))
                                     {:old-path gp :new-path np :similarity sim}
                                     best))
                                 best)))
                           nil
                           (sort gone-paths))]
          (when (and best (>= (:similarity best) rename-similarity-threshold))
            (swap! matches conj best)
            (swap! used-gone conj (:old-path best)))))
      (let [matched-new  (set (map :new-path @matches))
            matched-gone @used-gone]
        {:renamed-modified @matches
         :new  (vec (remove matched-new new-paths))
         :gone (vec (remove matched-gone gone-paths))}))))

(tests
 "cosine-similarity — identical vectors score 1.0"
 (let [a (float-array [1.0 0.0 0.0])
       b (float-array [1.0 0.0 0.0])]
   (cosine-similarity a b) := 1.0)
 :rcf)

(tests
 "cosine-similarity — orthogonal vectors score 0.0"
 (let [a (float-array [1.0 0.0 0.0])
       b (float-array [0.0 1.0 0.0])]
   (cosine-similarity a b) := 0.0)
 :rcf)

(tests
 "centroid — averages vectors correctly"
 (let [v1 (float-array [2.0 4.0])
       v2 (float-array [6.0 8.0])
       r  (centroid [v1 v2])]
   (seq r) := [(float 4.0) (float 6.0)])
 :rcf)

(tests
 "centroid — nil on empty input"
 (centroid []) := nil
 :rcf)

;; ---------------------------------------------------------------------------
;; HNSW cleanup helper
;; ---------------------------------------------------------------------------

(defn retract-chunk-vecs!
  "Retract :chunk/vec attribute values explicitly before entity retraction.

  Datalevin's :db.fn/retractEntity removes datoms from the KV store but does
  NOT remove corresponding HNSW index entries for :db.type/vec attributes.
  Stale HNSW nodes cause a NullPointerException in vec-neighbors queries:
    'Cannot read field \"e\" because \"d\" is null'
  because the HNSW graph traverses the dead node and calls doc-ref->eav,
  which returns a null datom.

  Explicit [:db/retract eid :chunk/vec val] does trigger HNSW removal.
  Call this before any store/retract! on chunk entity IDs.

  Public — called from llm-memory.core/remove-root! (cross-namespace) to
  apply the same HNSW hygiene during whole-root teardown."
  [store chunk-eids]
  (when (seq chunk-eids)
    (let [chunks       (store/pull-many store (vec chunk-eids))
          vec-retracts (into [] (keep (fn [e]
                                        (when-let [v (:chunk/vec e)]
                                          [:db/retract (:db/id e) :chunk/vec v])))
                             chunks)]
      (when (seq vec-retracts)
        (store/transact! store vec-retracts)))))

(defn- query-link-eids
  "Return all Datalevin entity IDs for :link/* entities whose :link/from
  points at the file with `file-id`. Used to retract stale link entities
  before re-indexing, and by retract-file-by-id! before deletion.

  Joins through :file/id, so callers MUST invoke this before retracting
  the file entity — otherwise the join returns empty and orphan link
  entities survive."
  [store file-id]
  (store/query store
               '[:find [?l ...]
                 :in $ ?fid
                 :where
                 [?f :file/id ?fid]
                 [?l :link/from ?f]]
               {:fid file-id}))

(defn resolve-wiki-ref
  "Resolve a wiki file-ref to navigation info.
  Accepts 'root-name::rel-path' or 'root-name::rel-path#slug'.
  Returns:
    {:type :heading :file-path str :root-uri str :slug str} — heading target
    {:type :file    :file-path str :root-uri str}           — file target
    nil                                                      — not found"
  [store wiki-ref]
  (let [[file-part slug] (str/split wiki-ref #"#" 2)
        result (first (store/query store
                                   '[:find ?path ?root-uri
                                     :in $ ?fid
                                     :where
                                     [?f :file/id ?fid]
                                     [?f :file/path ?path]
                                     [?f :file/root ?r]
                                     [?r :root/uri ?root-uri]]
                                   {:fid file-part}))]
    (when result
      (let [[file-path root-uri] result]
        (if (seq slug)
          {:type :heading :file-path file-path :root-uri root-uri :slug slug}
          {:type :file    :file-path file-path :root-uri root-uri})))))

(tests
 "resolve-wiki-ref — nil for unknown ref"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       _    (reg! s {:uri (ts/root-uri root) :name "test" :plans-dir "Plans"})]
   (resolve-wiki-ref s "test::nonexistent.md") := nil
   (store/disconnect! s))
 :rcf)

;; ---------------------------------------------------------------------------
;; index-file!
;; ---------------------------------------------------------------------------

(defn index-file!
  "Index a single markdown file into the store.

  Reads the file, parses frontmatter, splits into chunks, embeds each chunk,
  and transacts file + chunk entities. Uses identity keys for upsert semantics."
  [store root-uri abs-path]
  (let [root   (resolve-root store root-uri)
        _      (when-not root
                 (throw (ex-info (str "Root not found: " root-uri)
                                 {:root-uri root-uri})))
        rname  (:root/name root)
        pdir   (plans-dir-path root-uri (:root/plans-dir root))
        file   (io/file abs-path)
        text   (slurp file)
        rp     (rel-path pdir file)
        file-id (compute-file-id rname pdir abs-path)

        ;; Parse frontmatter + chunk
        [raw-fm body] (fm/parse-frontmatter text)
        fm-meta       (fm/frontmatter->metadata raw-fm)
        inferred      (meta'/infer-metadata rp)
        merged        (merge inferred fm-meta)
        chunks        (chunk/split-sections body)
        content-hash  (sha256 text)
        embedder      (.-embedder store)

        title         (chunk/page-title body)

        ;; Build file entity
        file-entity (cond-> {:file/id           file-id
                             :file/path         rp
                             :file/root         [:root/uri root-uri]
                             :file/content-hash content-hash
                             :file/modified     (file-modified-epoch file)
                             :file/modified-str (file-modified-str file)}
                      (:fm/status merged) (assoc :file/status (:fm/status merged))
                      (:fm/type merged)   (assoc :file/type   (:fm/type merged))
                      (:fm/group merged)  (assoc :file/group  (:fm/group merged))
                      (:fm/jira merged)   (assoc :file/jira   (:fm/jira merged))
                      (:fm/related merged)  (assoc :file/related  (:fm/related merged))
                      (:fm/tags merged)     (assoc :file/tags    (:fm/tags merged))
                      (:fm/created merged)  (assoc :file/created (:fm/created merged))
                      title               (assoc :file/title title))

        ;; Build chunk entities
        chunk-entities (mapv (fn [[idx [slug text']]]
                               {:chunk/id      (str file-id "::" slug)
                                :chunk/file    [:file/id file-id]
                                :chunk/text    text'
                                :chunk/vec     (embed/embed-text embedder text')
                                :chunk/slug    slug
                                :chunk/section idx})
                             (map-indexed vector chunks))

        ;; Build outbound wiki-link entities. Extract from body (not text) to
        ;; avoid spurious matches inside YAML frontmatter. :link/id composite
        ;; key lets re-transact upsert surviving (from, to, slug) triples.
        outbound       (extract-outbound-wiki-links body)
        link-entities  (mapv (fn [{:keys [to-id slug]}]
                               {:link/id    (str file-id "@@" to-id "@@" slug)
                                :link/from  [:file/id file-id]
                                :link/to-id to-id
                                :link/slug  slug})
                             outbound)]

    ;; Retract ALL existing chunks for this file before transacting.
    ;; Datalevin's HNSW index does not update on upsert of :db.type/vec
    ;; attributes — only on fresh insert. By retracting first, we ensure
    ;; every chunk is a new entity with a new eid, which correctly inserts
    ;; into the HNSW graph. See Plans/complete/backend/hnsw-desync/CONTEXT.md.
    (let [existing-chunk-eids (store/query store
                                           '[:find [?c ...]
                                             :in $ ?fid
                                             :where
                                             [?f :file/id ?fid]
                                             [?c :chunk/file ?f]]
                                           {:fid file-id})
          existing-link-eids  (query-link-eids store file-id)]
      (when (seq existing-chunk-eids)
        (retract-chunk-vecs! store existing-chunk-eids)
        (store/retract! store (vec existing-chunk-eids)))
      (when (seq existing-link-eids)
        (store/retract! store (vec existing-link-eids))))
    ;; Single-transact invariant: file + chunks + links commit atomically.
    ;; A crash mid-commit cannot leave skew between chunk and link state.
    (store/transact! store (concat [file-entity] chunk-entities link-entities))
    {:file-id file-id :chunks (count chunks) :links (count link-entities)}))

(tests
 "index-file! — indexes a file and creates file + chunk entities"
 (let [root  (ts/tmp-dir)
       s     (ts/fresh-store)
       reg!  (requiring-resolve 'llm-memory.core/register-root!)
       stat  (requiring-resolve 'llm-memory.core/status)
       lstf  (requiring-resolve 'llm-memory.core/list-files)
       _     (reg! s {:uri (ts/root-uri root) :name "test" :plans-dir "Plans"})
       _     (ts/write-plan! root "todo/CACHE-CONTEXT.md"
                             "---\ntags: cache\n---\n\n# Cache Context\n\n## Overview\n\nCache invalidation strategy.\n\n## Details\n\nMore details here.")
       abs   (.getAbsolutePath (io/file root "Plans/todo/CACHE-CONTEXT.md"))
       result (index-file! s (ts/root-uri root) abs)]
   (:file-id result) := "test::todo/CACHE-CONTEXT.md"
   (:chunks result)  := 2
   (let [files (lstf s)]
     (count files) := 1
     (:file/type (first files))   := "context"
     (:file/group (first files))  := "cache"
     (:file/status (first files)) := "active"
     (:file/title (first files))  := "Cache Context")
   (let [st (stat s)]
     (:chunks st) := 2)
   ;; Verify resolve-wiki-ref works after indexing
   (let [r (resolve-wiki-ref s "test::todo/CACHE-CONTEXT.md")]
     (:type r)      := :file
     (:file-path r) := "todo/CACHE-CONTEXT.md")
   (let [r (resolve-wiki-ref s "test::todo/CACHE-CONTEXT.md#overview")]
     (:type r)  := :heading
     (:slug r)  := "overview")
   (store/disconnect! s))
 :rcf)

(tests
 "index-file! — re-indexing cleans up stale chunks"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       stat (requiring-resolve 'llm-memory.core/status)
       _    (reg! s {:uri (ts/root-uri root) :name "test" :plans-dir "Plans"})
       f    (ts/write-plan! root "dev/FOO-PLAN.md"
                            "# Foo\n\n## Step 1\n\nDo thing.\n\n## Step 2\n\nDo other thing.")
       abs  (.getAbsolutePath f)]
   (index-file! s (ts/root-uri root) abs)
   (:chunks (stat s)) := 2
   (spit f "# Foo\n\n## Step 1\n\nDo thing only.")
   (index-file! s (ts/root-uri root) abs)
   (:chunks (stat s)) := 1
   (store/disconnect! s))
 :rcf)

(tests
 "index-file! — extracts outbound wiki links into :link/* entities"
 (let [root  (ts/tmp-dir)
       s     (ts/fresh-store)
       reg!  (requiring-resolve 'llm-memory.core/register-root!)
       _     (reg! s {:uri (ts/root-uri root) :name "lg" :plans-dir "Plans"})
       _     (ts/write-plan! root "dev/A.md"
                             (str "# A\n\n## Intro\n\n"
                                  "Go to [context](wiki:lg::dev/B.md) and see "
                                  "[design](wiki:lg::dev/B.md#design)."))
       abs   (.getAbsolutePath (io/file root "Plans/dev/A.md"))
       r     (index-file! s (ts/root-uri root) abs)]
   (:links r) := 2
   ;; Both target the same file-id, distinct by slug
   (let [rows (store/query s '[:find ?tid ?slug
                               :in $ ?fid
                               :where
                               [?f :file/id ?fid]
                               [?l :link/from ?f]
                               [?l :link/to-id ?tid]
                               [?l :link/slug ?slug]]
                           {:fid "lg::dev/A.md"})]
     (set rows) := #{["lg::dev/B.md" ""] ["lg::dev/B.md" "design"]})
   (store/disconnect! s))
 :rcf)

(tests
 "index-file! — :link/to-id is bare (no wiki: prefix) so inbound-links matches"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       _    (reg! s {:uri (ts/root-uri root) :name "bare" :plans-dir "Plans"})
       _    (ts/write-plan! root "A.md" "# A\n\n## X\n\nLink: [b](wiki:bare::B.md).")
       abs  (.getAbsolutePath (io/file root "Plans/A.md"))]
   (index-file! s (ts/root-uri root) abs)
   ;; :link/to-id must be the bare file-id — not prefixed with "wiki:"
   (ffirst (store/query s '[:find ?tid :where [_ :link/to-id ?tid]])) := "bare::B.md"
   (store/disconnect! s))
 :rcf)

(tests
 "index-file! — re-indexing with removed link also removes the :link/* entity"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       _    (reg! s {:uri (ts/root-uri root) :name "rm" :plans-dir "Plans"})
       f    (ts/write-plan! root "A.md" "# A\n\n## X\n\nKept: [b](wiki:rm::B.md) and [c](wiki:rm::C.md).")
       abs  (.getAbsolutePath f)]
   (index-file! s (ts/root-uri root) abs)
   (count (store/query s '[:find ?l :where [?l :link/id]])) := 2
   ;; Rewrite without the link to C
   (spit f "# A\n\n## X\n\nKept only: [b](wiki:rm::B.md).")
   (index-file! s (ts/root-uri root) abs)
   (count (store/query s '[:find ?l :where [?l :link/id]])) := 1
   (store/disconnect! s))
 :rcf)

(tests
 "index-file! — re-indexed chunks remain in HNSW search index (desync regression)"
 ;; Reproduces the HNSW desync bug: when a file is re-indexed with same slugs
 ;; (same :chunk/id), the old code used upsert which updated :chunk/vec in KV
 ;; but did NOT re-insert into the HNSW graph.
 ;; With the retract-then-insert fix, vectors should remain searchable.
 (let [root  (ts/tmp-dir)
       s     (ts/fresh-store)
       reg!  (requiring-resolve 'llm-memory.core/register-root!)
       srch  (requiring-resolve 'llm-memory.core/search)
       _     (reg! s {:uri (ts/root-uri root) :name "test" :plans-dir "Plans"})
       f     (ts/write-plan! root "dev/HNSW-TEST.md"
                             "# HNSW Test\n\n## Overview\n\nKubernetes deployment and scaling strategy.")
       abs   (.getAbsolutePath f)]
   ;; First index — chunks should be searchable
   (index-file! s (ts/root-uri root) abs)
   (let [results (srch s "Kubernetes deployment" {:top 5})]
     (pos? (count results)) := true)

   ;; Modify file (same slugs → same :chunk/id) and re-index
   (spit f "# HNSW Test\n\n## Overview\n\nDocker container orchestration and scaling.")
   (index-file! s (ts/root-uri root) abs)

   ;; Chunks must still be searchable via HNSW — this would fail with upsert bug
   (let [results (srch s "Docker container" {:top 5})]
     (pos? (count results)) := true
     ;; Verify it's the updated content, not stale
     (str/includes? (:chunk/text (first results)) "Docker") := true)
   (store/disconnect! s))
 :rcf)

;; ---------------------------------------------------------------------------
;; retract-file!
;; ---------------------------------------------------------------------------

(defn retract-file-by-id!
  "Remove a file, all its chunks, and all its outbound :link/* entities.

  Query-then-retract invariant: chunk-eids, link-eids, and file-eids are
  all captured in the same let-binding BEFORE any retract runs. The
  link-eids query joins through :file/id, so it must see the file entity
  alive. Retract order: chunk-vecs → chunks → links → files."
  [store file-id]
  (let [chunk-eids (store/query store
                                '[:find [?c ...]
                                  :in $ ?fid
                                  :where
                                  [?f :file/id ?fid]
                                  [?c :chunk/file ?f]]
                                {:fid file-id})
        link-eids  (query-link-eids store file-id)
        file-eids  (store/query store
                                '[:find [?f ...]
                                  :in $ ?fid
                                  :where [?f :file/id ?fid]]
                                {:fid file-id})]
    (when (seq chunk-eids)
      (retract-chunk-vecs! store chunk-eids)
      (store/retract! store (vec chunk-eids)))
    (when (seq link-eids)
      (store/retract! store (vec link-eids)))
    (when (seq file-eids)
      (store/retract! store (vec file-eids)))
    {:retracted-chunks (count chunk-eids)
     :retracted-links  (count link-eids)
     :retracted-files  (count file-eids)}))

(defn retract-file!
  "Remove a file and all its chunks from the store."
  [store root-uri abs-path]
  (let [root  (resolve-root store root-uri)
        _     (when-not root
                (throw (ex-info (str "Root not found: " root-uri)
                                {:root-uri root-uri})))
        rname (:root/name root)
        pdir  (plans-dir-path root-uri (:root/plans-dir root))
        fid   (compute-file-id rname pdir abs-path)]
    (retract-file-by-id! store fid)))

(tests
 "retract-file! — removes file and chunks"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       stat (requiring-resolve 'llm-memory.core/status)
       _    (reg! s {:uri (ts/root-uri root) :name "test" :plans-dir "Plans"})
       f    (ts/write-plan! root "dev/DOOMED.md" "# Doomed\n\n## Content\n\nGone soon.")
       abs  (.getAbsolutePath f)]
   (index-file! s (ts/root-uri root) abs)
   (:files (stat s)) := 1
   (retract-file! s (ts/root-uri root) abs)
   (:files (stat s))  := 0
   (:chunks (stat s)) := 0
   (store/disconnect! s))
 :rcf)

(tests
 "retract-file! — HNSW is clean after retraction (no stale nodes)"
 ;; Verifies that retract-chunk-vecs! is called before entity retraction.
 ;; Without it, :db.fn/retractEntity leaves stale HNSW nodes that cause NPE
 ;; in vec-neighbors: 'Cannot read field \"e\" because \"d\" is null'.
 (let [root  (ts/tmp-dir)
       s     (ts/fresh-store)
       reg!  (requiring-resolve 'llm-memory.core/register-root!)
       srch  (requiring-resolve 'llm-memory.core/search)
       hlth  (requiring-resolve 'llm-memory.core/hnsw-health)
       _     (reg! s {:uri (ts/root-uri root) :name "test" :plans-dir "Plans"})
       f     (ts/write-plan! root "dev/EPHEMERAL.md"
                             "# Ephemeral\n\n## Section\n\nThis file will be retracted.")
       abs   (.getAbsolutePath f)]
   (index-file! s (ts/root-uri root) abs)
   ;; Sanity: file is searchable (dedupe=true gives 1 result per file)
   (count (srch s "ephemeral retracted" {:top 5})) := 1
   (retract-file! s (ts/root-uri root) abs)
   ;; HNSW must have zero entries — stale nodes show up as :missing > 0
   ;; or throw NPE when traversed
   (:total   (hlth s)) := 0
   (:indexed (hlth s)) := 0
   (:missing (hlth s)) := 0
   (store/disconnect! s))
 :rcf)

;; ---------------------------------------------------------------------------
;; rename-file!
;; ---------------------------------------------------------------------------

(defn rename-file!
  "Update a file's path and re-infer metadata, preserving entity identity.

  Also updates all child chunk IDs (they include the rel-path prefix).
  This is used when a file moves between directories (e.g. dev/ → complete/)."
  [store root-uri old-path new-path]
  (let [root     (resolve-root store root-uri)
        _        (when-not root
                   (throw (ex-info (str "Root not found: " root-uri)
                                   {:root-uri root-uri})))
        rname    (:root/name root)
        pdir     (plans-dir-path root-uri (:root/plans-dir root))
        new-rp   (rel-path pdir (io/file new-path))
        old-fid  (compute-file-id rname pdir old-path)
        new-fid  (compute-file-id rname pdir new-path)
        new-file (io/file new-path)

        ;; Re-infer metadata from new path
        text        (slurp new-file)
        [raw-fm _]  (fm/parse-frontmatter text)
        fm-meta     (fm/frontmatter->metadata raw-fm)
        inferred    (meta'/infer-metadata new-rp)
        merged      (merge inferred fm-meta)

        ;; Find existing file entity
        file-eid (ffirst (store/query store
                                      '[:find ?e
                                        :in $ ?fid
                                        :where [?e :file/id ?fid]]
                                      {:fid old-fid}))]

    (when file-eid
      ;; Update file entity
      (let [file-update (cond-> {:db/id         file-eid
                                 :file/id       new-fid
                                 :file/path     new-rp
                                 :file/modified     (file-modified-epoch new-file)
                                 :file/modified-str (file-modified-str new-file)}
                          (:fm/status merged)  (assoc :file/status  (:fm/status merged))
                          (:fm/type merged)    (assoc :file/type    (:fm/type merged))
                          (:fm/group merged)   (assoc :file/group   (:fm/group merged))
                          (:fm/jira merged)    (assoc :file/jira    (:fm/jira merged))
                          (:fm/related merged)  (assoc :file/related  (:fm/related merged))
                          (:fm/tags merged)     (assoc :file/tags    (:fm/tags merged))
                          (:fm/created merged)  (assoc :file/created (:fm/created merged)))

            ;; Update chunk IDs (replace old file-id prefix with new)
            chunk-updates (mapv (fn [[ceid old-cid]]
                                  {:db/id    ceid
                                   :chunk/id (str/replace old-cid old-fid new-fid)})
                                (store/query store
                                             '[:find ?c ?cid
                                               :in $ ?fid
                                               :where
                                               [?f :file/id ?fid]
                                               [?c :chunk/file ?f]
                                               [?c :chunk/id ?cid]]
                                             {:fid old-fid}))]

        (store/transact! store (into [file-update] chunk-updates))
        {:old-id old-fid :new-id new-fid :chunks-updated (count chunk-updates)}))))

;; ---------------------------------------------------------------------------
;; Heading-slug snapshots + matching (Steps 7, 8)
;; ---------------------------------------------------------------------------

(defn- snapshot-chunk-slugs
  "Query {slug → chunk-vec} for a file, used as the before/after snapshot in
  heading-rename detection. Filters nil/blank slugs defensively — today's
  chunk.clj always assigns `section-N` to pre-H2 chunks, but a future
  regression that produced blank slugs could cause cross-chunk collision
  inside match-heading-renames. One invariant, one enforcement site."
  [store file-id]
  (into {}
        (filter (fn [[slug _]] (not (str/blank? slug))))
        (store/query store
                     '[:find ?slug ?vec
                       :in $ ?fid
                       :where
                       [?f :file/id ?fid]
                       [?c :chunk/file ?f]
                       [?c :chunk/slug ?slug]
                       [?c :chunk/vec ?vec]]
                     {:fid file-id})))

(defn- match-heading-renames
  "Greedy cosine-similarity matching over old/new {slug → vec} maps.
  Mirrors match-fuzzy-renames (same threshold) but at heading granularity.

  Returns {:renamed [{:old-slug :new-slug :similarity} ...]
           :added [str]  :removed [str]  :unchanged [str]}.

  Slugs that appear in both old and new are classified :unchanged and do
  NOT participate in rename matching — an exact slug-name match is always
  an identity match (no link-rewriting needed), regardless of whether
  the chunk vector changed. Only slugs that are exclusively in old
  (candidate :removed) compete against slugs exclusively in new
  (candidate :added) for pairing as :renamed."
  [old-slugs new-slugs]
  (let [old-keys (set (keys old-slugs))
        new-keys (set (keys new-slugs))
        both     (set/intersection old-keys new-keys)
        sorted-new-candidates (sort (remove both new-keys))
        sorted-old-candidates (sort (remove both old-keys))
        used-old (atom #{})
        matches  (atom [])]
    (doseq [ns sorted-new-candidates
            :let [nv (get new-slugs ns)]
            :when nv]
      (let [best (reduce (fn [best os]
                           (if (contains? @used-old os)
                             best
                             (if-let [ov (get old-slugs os)]
                               (let [sim (cosine-similarity nv ov)]
                                 (if (or (nil? best) (> sim (:similarity best)))
                                   {:old-slug os :new-slug ns :similarity sim}
                                   best))
                               best)))
                         nil
                         sorted-old-candidates)]
        (when (and best (>= (:similarity best) rename-similarity-threshold))
          (swap! matches conj best)
          (swap! used-old conj (:old-slug best)))))
    (let [matched-new  (set (map :new-slug @matches))
          matched-old  @used-old
          added        (filterv #(not (contains? matched-new %)) sorted-new-candidates)
          removed      (filterv #(not (contains? matched-old %)) sorted-old-candidates)]
      {:renamed @matches :added added :removed removed :unchanged (vec (sort both))})))

;; ---------------------------------------------------------------------------
;; Propagation (Steps 6, 9)
;; ---------------------------------------------------------------------------

(defn- resolve-linker-abs-path
  "Resolve a linker file-id → {:abs-path File :fruri str} by consulting its
  OWN root's metadata (the linker may live in a different root than the
  renamed target). Returns nil if the linker's root metadata cannot be
  found — caller should log WARN and record `:no-root-metadata`."
  [store from-id]
  (let [row (first (store/query store
                                '[:find ?path ?ruri ?rdir
                                  :in $ ?fid
                                  :where
                                  [?f :file/id ?fid]
                                  [?f :file/path ?path]
                                  [?f :file/root ?r]
                                  [?r :root/uri ?ruri]
                                  [?r :root/plans-dir ?rdir]]
                                {:fid from-id}))]
    (when row
      (let [[fpath fruri frdir] row
            base-path (str/replace fruri #"^file://" "")
            abs-path  (io/file base-path frdir fpath)]
        {:abs-path abs-path :fruri fruri}))))

(defn- expected-root-from-fid
  "Parse `<root-name>` out of a `<root-name>::<path>` file-id. Used only in
  the `no-root-metadata` error branch so the operator sees which root
  registration the query expected."
  [from-id]
  (first (str/split from-id #"::" 2)))

(defn propagate-file-rename!
  "Rewrite and re-index every linker file whose wiki link points at
  `old-fid`, updating it to `new-fid`. Preserves any `#slug` suffix via
  prefix-mode rewrite.

  Per-linker work is wrapped in its own try/catch — one bad linker (DB
  corruption, missing file on disk, rewrite failure) must not abort
  propagation for its siblings.

  Returns {:rewritten [from-id ...] :errors [{:op :from-id ...} ...]}.

  Does NOT accept a root-uri parameter: each linker resolves its own
  root URI from the DB, because in a cross-root case the linker is in a
  different root than the renamed target."
  [store old-fid new-fid]
  (let [linkers   (link-graph/inbound-links store old-fid)
        old-dest  (str "wiki:" old-fid)
        new-dest  (str "wiki:" new-fid)
        rewritten (atom [])
        errors    (atom [])]
    (doseq [from-id linkers]
      (try
        (if-let [{:keys [abs-path fruri]} (resolve-linker-abs-path store from-id)]
          (let [result (link-rewriter/rewrite-links-in-file!
                        (.getAbsolutePath ^File abs-path) old-dest new-dest)]
            (cond
              (= result :modified)
              (try
                (index-file! store fruri (.getAbsolutePath ^File abs-path))
                (swap! rewritten conj from-id)
                (catch Exception e
                  (swap! errors conj {:op :propagate-rename
                                      :from-id  from-id
                                      :abs-path (str abs-path)
                                      :error    (.getMessage e)})))
              (= result :error)
              (swap! errors conj {:op :propagate-rename
                                  :from-id  from-id
                                  :abs-path (str abs-path)
                                  :error    "rewrite-failed"})
              :else nil))
          (let [expected-root (expected-root-from-fid from-id)]
            (println "[WARN] propagate: no root metadata for from-id" from-id
                     "(expected root:" expected-root ")")
            (swap! errors conj {:op :propagate-rename
                                :from-id       from-id
                                :expected-root expected-root
                                :error         "no-root-metadata"})))
        (catch Exception e
          (swap! errors conj {:op :propagate-rename
                              :from-id from-id
                              :error   (.getMessage e)}))))
    {:rewritten @rewritten :errors @errors}))

(defn propagate-heading-rename!
  "Rewrite and re-index every linker whose wiki link points at
  `file-id#old-slug`, updating the anchor to `new-slug`.

  Uses `:exact? true` so `#step-1` does NOT match `#step-10`.
  Per-linker try/catch as in propagate-file-rename!.

  Returns {:rewritten [from-id ...] :errors [...]}."
  [store file-id old-slug new-slug]
  (let [linkers   (link-graph/heading-inbound-links store file-id old-slug)
        old-dest  (str "wiki:" file-id "#" old-slug)
        new-dest  (str "wiki:" file-id "#" new-slug)
        rewritten (atom [])
        errors    (atom [])]
    (doseq [from-id linkers]
      (try
        (if-let [{:keys [abs-path fruri]} (resolve-linker-abs-path store from-id)]
          (let [result (link-rewriter/rewrite-links-in-file!
                        (.getAbsolutePath ^File abs-path) old-dest new-dest
                        :exact? true)]
            (cond
              (= result :modified)
              (try
                (index-file! store fruri (.getAbsolutePath ^File abs-path))
                (swap! rewritten conj from-id)
                (catch Exception e
                  (swap! errors conj {:op :propagate-heading
                                      :from-id  from-id
                                      :abs-path (str abs-path)
                                      :old-slug old-slug :new-slug new-slug
                                      :error    (.getMessage e)})))
              (= result :error)
              (swap! errors conj {:op :propagate-heading
                                  :from-id  from-id
                                  :abs-path (str abs-path)
                                  :old-slug old-slug :new-slug new-slug
                                  :error    "rewrite-failed"})
              :else nil))
          (let [expected-root (expected-root-from-fid from-id)]
            (println "[WARN] propagate-heading: no root metadata for from-id" from-id
                     "(expected root:" expected-root ")")
            (swap! errors conj {:op :propagate-heading
                                :from-id       from-id
                                :expected-root expected-root
                                :old-slug old-slug :new-slug new-slug
                                :error         "no-root-metadata"})))
        (catch Exception e
          (swap! errors conj {:op :propagate-heading
                              :from-id from-id
                              :old-slug old-slug :new-slug new-slug
                              :error   (.getMessage e)}))))
    {:rewritten @rewritten :errors @errors}))

;; ---------------------------------------------------------------------------
;; Watcher helper (Step 10) — used by watcher.clj for :modify events
;; ---------------------------------------------------------------------------

(defn index-file-with-heading-diff!
  "Like index-file!, but also snapshots chunk slugs before and after, and
  propagates heading renames to inbound linkers.

  Single-file use only — the watcher fires this on :modify events, where
  there is no multi-file race. reconcile! inlines the same logic because
  it needs to DEFER propagation to pass 2.

  If the file is not yet indexed (no prior chunks), the before-snapshot
  is empty and no propagation fires — the file is freshly created."
  [store root-uri abs-path]
  (let [root     (resolve-root store root-uri)
        _        (when-not root
                   (throw (ex-info (str "Root not found: " root-uri)
                                   {:root-uri root-uri})))
        rname    (:root/name root)
        pdir     (plans-dir-path root-uri (:root/plans-dir root))
        file-id  (compute-file-id rname pdir abs-path)
        old-slugs (snapshot-chunk-slugs store file-id)
        result    (index-file! store root-uri abs-path)
        new-slugs (snapshot-chunk-slugs store file-id)
        renames   (when (seq old-slugs)
                    (:renamed (match-heading-renames old-slugs new-slugs)))
        propagated (atom [])
        errors     (atom [])]
    (doseq [{:keys [old-slug new-slug]} renames]
      (let [summary (propagate-heading-rename! store file-id old-slug new-slug)]
        (swap! propagated into (:rewritten summary))
        (swap! errors    into (:errors summary))))
    (assoc result
           :heading-renames (count renames)
           :propagated      (count (distinct @propagated))
           :errors          @errors)))

;; ---------------------------------------------------------------------------
;; reconcile!
;; ---------------------------------------------------------------------------

(defn- db-files
  "Load all indexed files for a root from the database.
   Returns {rel-path {:eid N :content-hash str :modified long :file-id str}}."
  [store root-uri]
  (let [results (store/query store
                             '[:find ?fid ?path ?hash ?mod ?e
                               :in $ ?ruri
                               :where
                               [?r :root/uri ?ruri]
                               [?e :file/root ?r]
                               [?e :file/id ?fid]
                               [?e :file/path ?path]
                               [?e :file/content-hash ?hash]
                               [?e :file/modified ?mod]]
                             {:ruri root-uri})]
    (into {} (map (fn [[fid path hash mod eid]]
                    [path {:eid eid :content-hash hash :modified mod :file-id fid}])
                  results))))

(defn- disk-files
  "Scan the filesystem for .md files under the plans directory.
   Returns {rel-path {:abs-path str :content-hash str :modified long}}."
  [^File plans-dir]
  (let [md-files (->> (file-seq plans-dir)
                      (filter #(and (.isFile ^File %)
                                    (str/ends-with? (.getName ^File %) ".md"))))]
    (into {} (map (fn [^File f]
                    (let [rp   (rel-path plans-dir f)
                          text (slurp f)]
                      [rp {:abs-path     (.getAbsolutePath f)
                           :content-hash (sha256 text)
                           :modified     (file-modified-epoch f)}]))
                  md-files))))

(defn- classify-files
  "Classify files into six categories by comparing db state and disk state.

   Two-pass rename detection:
   1. Exact hash match — catches pure renames (no content change)
   2. Embedding similarity — catches rename+edit (content changed)

   Returns {:unchanged [...] :modified [...] :renamed [...] :renamed-modified [...]
            :new [...] :gone [...]}.

   :renamed entries are {:old-path str :new-path str}.
   :renamed-modified entries add :similarity double."
  [db-state disk-state store embedder]
  (let [db-paths   (set (keys db-state))
        disk-paths (set (keys disk-state))
        both       (set/intersection db-paths disk-paths)
        db-only    (set/difference db-paths disk-paths)
        disk-only  (set/difference disk-paths db-paths)

        ;; Classify files present in both
        {unchanged :unchanged modified :modified}
        (reduce (fn [acc path]
                  (let [db-hash   (get-in db-state [path :content-hash])
                        disk-hash (get-in disk-state [path :content-hash])]
                    (if (= db-hash disk-hash)
                      (update acc :unchanged conj path)
                      (update acc :modified conj path))))
                {:unchanged [] :modified []}
                both)

        ;; Pass 1: exact-hash rename matching
        db-only-by-hash (reduce (fn [acc path]
                                  (let [hash (get-in db-state [path :content-hash])]
                                    (update acc hash (fnil conj []) path)))
                                {}
                                db-only)
        {renamed :renamed hash-new :new hash-gone :gone}
        (loop [disk-paths-left (seq disk-only)
               db-hash-map     db-only-by-hash
               renamed         []
               new-files       []]
          (if-let [dp (first disk-paths-left)]
            (let [disk-hash (get-in disk-state [dp :content-hash])
                  match     (first (get db-hash-map disk-hash))]
              (if match
                (recur (rest disk-paths-left)
                       (update db-hash-map disk-hash (comp vec rest))
                       (conj renamed {:old-path match :new-path dp})
                       new-files)
                (recur (rest disk-paths-left)
                       db-hash-map
                       renamed
                       (conj new-files dp))))
            (let [matched-db-paths (set (map :old-path renamed))
                  gone             (vec (remove matched-db-paths db-only))]
              {:renamed renamed :new new-files :gone gone})))

        ;; Pass 2: fuzzy rename matching via embedding similarity
        {renamed-modified :renamed-modified
         remaining-new    :new
         remaining-gone   :gone}
        (match-fuzzy-renames store embedder hash-gone hash-new
                             db-state disk-state)]

    {:unchanged        unchanged
     :modified         modified
     :renamed          renamed
     :renamed-modified renamed-modified
     :new              remaining-new
     :gone             remaining-gone}))

(defn reconcile!
  "Diff database vs. filesystem for a root. Uses the 2-pass structure:

    Pass 1 — DB mutations: every category (renamed, renamed-modified,
             modified, new, gone) commits to the DB. Pass 1 also captures
             records for pass 2 (rename-records, heading-records).
             Heading slug snapshots are taken inline HERE (not via
             index-file-with-heading-diff!) because pass 2 propagation
             needs to defer; the wrapper cannot.

    Pass 2 — Propagation: file-rename propagation runs first, then
             heading-rename propagation. By this point the DB is fully
             settled, so every propagating linker resolves the correct
             current file paths.

  Ordering in pass 2 matters: when both a file and a heading rename apply
  to the same target, file-rename runs first (rewriting linkers'
  :link/to-id from old-fid → new-fid), then heading-rename queries
  heading-inbound-links(new-fid, old-slug) and rewrites anchors.

  Returns:
    {:unchanged N :modified N :renamed N :renamed-modified N
     :new N :gone N :propagated N :errors [...]}"
  [store root-uri]
  (let [root      (resolve-root store root-uri)
        _         (when-not root
                    (throw (ex-info (str "Root not found: " root-uri)
                                    {:root-uri root-uri})))
        pdir      (plans-dir-path root-uri (:root/plans-dir root))
        embedder  (.-embedder store)
        db-state  (db-files store root-uri)
        disk-st   (disk-files pdir)
        classified (classify-files db-state disk-st store embedder)
        errors    (atom [])
        rename-records  (atom [])   ;; [{:old-fid :new-fid} ...]
        heading-records (atom [])   ;; [{:file-id :renames [...]} ...]
        rewritten (atom [])]        ;; collected from pass 2 propagations

    ;; --- Pass 1 — DB mutations + snapshot capture -------------------------

    ;; 1. Exact renames (content unchanged)
    (doseq [{:keys [old-path new-path]} (:renamed classified)]
      (try
        (let [old-abs (.getAbsolutePath (io/file pdir old-path))
              new-abs (.getAbsolutePath (io/file pdir new-path))]
          (when-let [{:keys [old-id new-id]} (rename-file! store root-uri old-abs new-abs)]
            (swap! rename-records conj {:old-fid old-id :new-fid new-id})))
        (catch Exception e
          (swap! errors conj {:op :rename :path new-path :error (.getMessage e)}))))

    ;; 2. Rename + content change: snapshot old slugs BEFORE retract, then
    ;;    re-index under the new file-id and snapshot new slugs. Preserving
    ;;    heading-rename detection across a rename+edit is the whole reason
    ;;    this snapshot happens here and not inside index-file!.
    (doseq [{:keys [old-path new-path]} (:renamed-modified classified)]
      (try
        (let [old-fid   (get-in db-state [old-path :file-id])
              old-slugs (snapshot-chunk-slugs store old-fid)
              new-abs   (.getAbsolutePath (io/file pdir new-path))]
          (retract-file-by-id! store old-fid)
          (let [{:keys [file-id]} (index-file! store root-uri new-abs)
                new-slugs (snapshot-chunk-slugs store file-id)
                renames   (:renamed (match-heading-renames old-slugs new-slugs))]
            (swap! rename-records conj {:old-fid old-fid :new-fid file-id})
            (when (seq renames)
              (swap! heading-records conj {:file-id file-id :renames renames}))))
        (catch Exception e
          (swap! errors conj {:op :rename-modified :path new-path :error (.getMessage e)}))))

    ;; 3. Modified (same path, content changed): snapshot, re-index, diff.
    (doseq [path (:modified classified)]
      (try
        (let [abs       (.getAbsolutePath (io/file pdir path))
              file-id   (get-in db-state [path :file-id])
              old-slugs (snapshot-chunk-slugs store file-id)]
          (index-file! store root-uri abs)
          (let [new-slugs (snapshot-chunk-slugs store file-id)
                renames   (:renamed (match-heading-renames old-slugs new-slugs))]
            (when (seq renames)
              (swap! heading-records conj {:file-id file-id :renames renames}))))
        (catch Exception e
          (swap! errors conj {:op :modify :path path :error (.getMessage e)}))))

    ;; 4. New files — no propagation needed (nothing was linking to them yet).
    (doseq [path (:new classified)]
      (try
        (let [abs (.getAbsolutePath (io/file pdir path))]
          (index-file! store root-uri abs))
        (catch Exception e
          (swap! errors conj {:op :new :path path :error (.getMessage e)}))))

    ;; 5. Gone — retract file + its outbound links.
    (doseq [path (:gone classified)]
      (try
        (let [fid (get-in db-state [path :file-id])]
          (retract-file-by-id! store fid))
        (catch Exception e
          (swap! errors conj {:op :gone :path path :error (.getMessage e)}))))

    ;; --- Pass 2 — Propagation (DB is fully settled) -----------------------

    (doseq [{:keys [old-fid new-fid]} @rename-records]
      (try
        (let [summary (propagate-file-rename! store old-fid new-fid)]
          (swap! errors    into (:errors summary))
          (swap! rewritten into (:rewritten summary)))
        (catch Exception e
          (swap! errors conj {:op :propagate-rename
                              :old-fid old-fid :new-fid new-fid
                              :error (.getMessage e)}))))

    (doseq [{:keys [file-id renames]} @heading-records]
      (doseq [{:keys [old-slug new-slug]} renames]
        (try
          (let [summary (propagate-heading-rename! store file-id old-slug new-slug)]
            (swap! errors    into (:errors summary))
            (swap! rewritten into (:rewritten summary)))
          (catch Exception e
            (swap! errors conj {:op :propagate-heading
                                :file-id file-id
                                :old-slug old-slug :new-slug new-slug
                                :error (.getMessage e)})))))

    {:unchanged        (count (:unchanged classified))
     :modified         (count (:modified classified))
     :renamed          (count (:renamed classified))
     :renamed-modified (count (:renamed-modified classified))
     :new              (count (:new classified))
     :gone             (count (:gone classified))
     :propagated       (count (distinct @rewritten))
     :errors           @errors}))

;; ---------------------------------------------------------------------------
;; index-root! (full reindex)
;; ---------------------------------------------------------------------------

(defn index-root!
  "Full reindex — retract all data for this root, then index every .md on disk.

  Query-then-retract invariant (matches retract-file-by-id! and
  core/remove-root!): chunk-eids, link-eids, and file-eids are all
  captured in the same let-binding BEFORE any retract runs. The
  link-eids query joins through :file/root, so it must run while file
  entities still exist. Retract order: chunk-vecs → chunks → links →
  files."
  [store root-uri]
  (let [root (resolve-root store root-uri)
        _    (when-not root
               (throw (ex-info (str "Root not found: " root-uri)
                               {:root-uri root-uri})))
        pdir (plans-dir-path root-uri (:root/plans-dir root))
        chunk-eids (store/query store
                                '[:find [?c ...]
                                  :in $ ?ruri
                                  :where
                                  [?r :root/uri ?ruri]
                                  [?f :file/root ?r]
                                  [?c :chunk/file ?f]]
                                {:ruri root-uri})
        link-eids  (store/query store
                                '[:find [?l ...]
                                  :in $ ?ruri
                                  :where
                                  [?r :root/uri ?ruri]
                                  [?f :file/root ?r]
                                  [?l :link/from ?f]]
                                {:ruri root-uri})
        file-eids  (store/query store
                                '[:find [?f ...]
                                  :in $ ?ruri
                                  :where
                                  [?r :root/uri ?ruri]
                                  [?f :file/root ?r]]
                                {:ruri root-uri})]

    (when (seq chunk-eids)
      (retract-chunk-vecs! store chunk-eids)
      (store/retract! store (vec chunk-eids)))
    (when (seq link-eids)
      (store/retract! store (vec link-eids)))
    (when (seq file-eids)
      (store/retract! store (vec file-eids)))

    (let [md-files (->> (file-seq pdir)
                        (filter #(and (.isFile ^File %)
                                      (str/ends-with? (.getName ^File %) ".md")))
                        sort)
          results  (mapv (fn [^File f]
                           (index-file! store root-uri (.getAbsolutePath f)))
                         md-files)]
      {:files  (count results)
       :chunks (reduce + (map :chunks results))
       :links  (reduce + (keep :links results))})))

(tests
 "index-root! — full reindex replaces all data"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       ruri (ts/root-uri root)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       _    (reg! s {:uri ruri :name "test" :plans-dir "Plans"})]
   (ts/write-plan! root "todo/X.md" "# X\n\n## S\n\nContent X.")
   (ts/write-plan! root "todo/Y.md" "# Y\n\n## S\n\nContent Y.")
   (let [result (index-root! s ruri)]
     (:files result)  := 2
     (:chunks result) := 2)
   (.delete (io/file root "Plans/todo/X.md"))
   (let [result (index-root! s ruri)]
     (:files result)  := 1
     (:chunks result) := 1)
   (store/disconnect! s))
 :rcf)

(tests
 "reconcile! — detects unchanged, modified, new, and gone files"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       ruri (ts/root-uri root)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       stat (requiring-resolve 'llm-memory.core/status)
       _    (reg! s {:uri ruri :name "test" :plans-dir "Plans"})]
   (ts/write-plan! root "todo/A.md" "# A\n\n## Sec\n\nOriginal A.")
   (ts/write-plan! root "todo/B.md" "# B\n\n## Sec\n\nOriginal B.")
   (index-root! s ruri)
   (:files (stat s)) := 2
   (spit (io/file root "Plans/todo/B.md") "# B\n\n## Sec\n\nModified B content.")
   (ts/write-plan! root "todo/C.md" "# C\n\n## Sec\n\nNew file C.")
   (.delete (io/file root "Plans/todo/A.md"))
   (let [summary (reconcile! s ruri)]
     (:unchanged summary) := 0
     (:modified summary)  := 1
     (:new summary)       := 1
     (:gone summary)      := 1
     (:errors summary)    := [])
   (:files (stat s)) := 2
   (store/disconnect! s))
 :rcf)

(tests
 "reconcile! — detects renames by content hash"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       ruri (ts/root-uri root)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       stat (requiring-resolve 'llm-memory.core/status)
       lstf (requiring-resolve 'llm-memory.core/list-files)
       _    (reg! s {:uri ruri :name "test" :plans-dir "Plans"})]
   (ts/write-plan! root "todo/FOO-CONTEXT.md" "# Foo Context\n\n## Overview\n\nFoo context content here.")
   (index-root! s ruri)
   (:files (stat s)) := 1
   (.mkdirs (io/file root "Plans/complete/foo"))
   (spit (io/file root "Plans/complete/foo/CONTEXT.md")
         "# Foo Context\n\n## Overview\n\nFoo context content here.")
   (.delete (io/file root "Plans/todo/FOO-CONTEXT.md"))
   (let [summary (reconcile! s ruri)]
     (:renamed summary)   := 1
     (:new summary)       := 0
     (:gone summary)      := 0
     (:unchanged summary) := 0)
   (let [files (lstf s)]
     (count files) := 1
     (:file/path (first files))   := "complete/foo/CONTEXT.md"
     (:file/status (first files)) := "complete"
     (:file/group (first files))  := "foo")
   (store/disconnect! s))
 :rcf)

(tests
 "reconcile! — detects rename+edit via embedding similarity"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       ruri (ts/root-uri root)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       stat (requiring-resolve 'llm-memory.core/status)
       lstf (requiring-resolve 'llm-memory.core/list-files)
       _    (reg! s {:uri ruri :name "test" :plans-dir "Plans"})]
   (ts/write-plan! root "todo/RATE-LIMITING-CONTEXT.md"
                   "# Rate Limiting Context\n\n## Overview\n\nOCI Usage API rate-limits aggressively after 17 requests.\nHTTP 429 TooManyRequests errors require exponential backoff with jitter.\n\n## Retry Strategy\n\nImplement retry with exponential backoff.\nMax 3 retries per request. Base delay 1 second, multiplier 2x.")
   (index-root! s ruri)
   (:files (stat s)) := 1
   (.delete (io/file root "Plans/todo/RATE-LIMITING-CONTEXT.md"))
   (ts/write-plan! root "complete/rate-limiting/CONTEXT.md"
                   "# Rate Limiting Context\n\n## Overview\n\nThe OCI Usage API enforces aggressive rate limits.\nAfter approximately 17 sequential requests, HTTP 429 errors occur.\nExponential backoff with jitter is the standard mitigation.\n\n## Retry Strategy\n\nRetry with exponential backoff: max 3 attempts, base delay 1s, 2x multiplier.\n\n## Results\n\nAll 43 GPU instances now return cost data successfully.")
   (let [summary (reconcile! s ruri)]
     (:renamed-modified summary) := 1
     (:renamed summary)          := 0
     (:new summary)              := 0
     (:gone summary)             := 0
     (:errors summary)           := [])
   (let [files (lstf s)]
     (count files) := 1
     (:file/path (first files))   := "complete/rate-limiting/CONTEXT.md"
     (:file/status (first files)) := "complete"
     (:file/group (first files))  := "rate-limiting")
   (store/disconnect! s))
 :rcf)

(tests
 "reconcile! — completely unrelated files stay as gone+new"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       ruri (ts/root-uri root)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       stat (requiring-resolve 'llm-memory.core/status)
       _    (reg! s {:uri ruri :name "test" :plans-dir "Plans"})]
   (ts/write-plan! root "todo/CACHE-PLAN.md"
                   "# Cache Plan\n\n## Strategy\n\nImplement Datahike persistence layer with file-backed store.\nUse nippy serialization for fast cache reads.")
   (index-root! s ruri)
   (:files (stat s)) := 1
   (.delete (io/file root "Plans/todo/CACHE-PLAN.md"))
   (ts/write-plan! root "todo/K8S-DEPLOY-PLAN.md"
                   "# Kubernetes Deployment\n\n## Architecture\n\nArgoCD watches the staging branch of the release repo.\nKustomize overlays for dev, staging, production environments.\nNginx ingress with TLS termination.")
   (let [summary (reconcile! s ruri)]
     (:renamed-modified summary) := 0
     (:renamed summary)          := 0
     (:new summary)              := 1
     (:gone summary)             := 1
     (:errors summary)           := [])
   (store/disconnect! s))
 :rcf)

(tests
 "reconcile! — mixed: exact rename + fuzzy rename + new + gone"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       ruri (ts/root-uri root)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       stat (requiring-resolve 'llm-memory.core/status)
       lstf (requiring-resolve 'llm-memory.core/list-files)
       _    (reg! s {:uri ruri :name "test" :plans-dir "Plans"})]
   (ts/write-plan! root "todo/A-CONTEXT.md"
                   "# A Context\n\n## Details\n\nThis file will be moved without changes.")
   (ts/write-plan! root "todo/B-PLAN.md"
                   "# B Plan\n\n## Steps\n\nImplement the B feature with retry logic and backoff.\nStep 1: Add retry wrapper. Step 2: Configure timeouts.")
   (ts/write-plan! root "todo/C-STORY.md"
                   "# C Story\n\n## Description\n\nThis file will be deleted entirely.")
   (index-root! s ruri)
   (:files (stat s)) := 3
   (.delete (io/file root "Plans/todo/A-CONTEXT.md"))
   (ts/write-plan! root "complete/a/CONTEXT.md"
                   "# A Context\n\n## Details\n\nThis file will be moved without changes.")
   (.delete (io/file root "Plans/todo/B-PLAN.md"))
   (ts/write-plan! root "complete/b/PLAN.md"
                   "# B Plan\n\n## Steps\n\nImplement the B feature with retry logic and exponential backoff.\nStep 1: Add retry wrapper around HTTP calls.\nStep 2: Configure timeouts and jitter.\n\n## Results\n\nAll steps completed successfully.")
   (.delete (io/file root "Plans/todo/C-STORY.md"))
   (ts/write-plan! root "todo/D-INFO.md"
                   "# D Information\n\n## Overview\n\nBrand new document about deployment pipelines.")
   (let [summary (reconcile! s ruri)]
     (:renamed summary)          := 1
     (:renamed-modified summary) := 1
     (:gone summary)             := 1
     (:new summary)              := 1
     (:errors summary)           := [])
   (let [files (lstf s)
         paths (set (map :file/path files))]
     (count files) := 3
     (contains? paths "complete/a/CONTEXT.md") := true
     (contains? paths "complete/b/PLAN.md")    := true
     (contains? paths "todo/D-INFO.md")        := true)
   (store/disconnect! s))
 :rcf)

;; ---------------------------------------------------------------------------
;; index-status
;; ---------------------------------------------------------------------------

(defn index-status
  "Return indexing stats for a root."
  [store root-uri]
  (let [file-count  (ffirst (store/query store
                                         '[:find (count ?f)
                                           :in $ ?ruri
                                           :where
                                           [?r :root/uri ?ruri]
                                           [?f :file/root ?r]]
                                         {:ruri root-uri}))
        chunk-count (ffirst (store/query store
                                         '[:find (count ?c)
                                           :in $ ?ruri
                                           :where
                                           [?r :root/uri ?ruri]
                                           [?f :file/root ?r]
                                           [?c :chunk/file ?f]]
                                         {:ruri root-uri}))
        newest      (ffirst (store/query store
                                         '[:find (max ?mod)
                                           :in $ ?ruri
                                           :where
                                           [?r :root/uri ?ruri]
                                           [?f :file/root ?r]
                                           [?f :file/modified ?mod]]
                                         {:ruri root-uri}))
        oldest      (ffirst (store/query store
                                         '[:find (min ?mod)
                                           :in $ ?ruri
                                           :where
                                           [?r :root/uri ?ruri]
                                           [?f :file/root ?r]
                                           [?f :file/modified ?mod]]
                                         {:ruri root-uri}))]
    {:files       (or file-count 0)
     :chunks      (or chunk-count 0)
     :newest-mod  newest
     :oldest-mod  oldest}))

(tests
 "index-status — returns per-root stats"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       ruri (ts/root-uri root)
       _    ((requiring-resolve 'llm-memory.core/register-root!) s {:uri ruri :name "test" :plans-dir "Plans"})]
   (ts/write-plan! root "dev/Z.md" "# Z\n\n## S\n\nContent Z.")
   (index-root! s ruri)
   (let [st (index-status s ruri)]
     (:files st)  := 1
     (:chunks st) := 1
     (some? (:newest-mod st)) := true
     (some? (:oldest-mod st)) := true)
   (store/disconnect! s))
 :rcf)

;; ---------------------------------------------------------------------------
;; Link-graph integration tests (defined here because they use
;; retract-file-by-id! and index-root!, which are defined earlier in the file).
;; ---------------------------------------------------------------------------

(tests
 "retract-file-by-id! — retracts outgoing :link/* entities"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       _    (reg! s {:uri (ts/root-uri root) :name "rfi" :plans-dir "Plans"})
       _    (ts/write-plan! root "A.md" "# A\n\n## S\n\nLink: [b](wiki:rfi::B.md).")
       abs  (.getAbsolutePath (io/file root "Plans/A.md"))]
   (index-file! s (ts/root-uri root) abs)
   (count (store/query s '[:find ?l :where [?l :link/id]])) := 1
   (retract-file-by-id! s "rfi::A.md")
   (count (store/query s '[:find ?l :where [?l :link/id]])) := 0
   (store/disconnect! s))
 :rcf)

(tests
 "retract-file-by-id! — :link/to-id is a bare string, orphan targets are valid state"
 ;; A's outbound link entity points at B via :link/to-id "orphan::B.md" — a
 ;; bare string, not a ref. Retracting B does NOT cascade to A's link entity;
 ;; A's markdown still contains the link, so the graph still records it.
 ;; inbound-links returns A because A's :link/to-id still matches, even
 ;; though B itself is no longer indexed. This confirms unresolved targets
 ;; are a valid graph state — the invariant the Phase 9b link-integrity-report
 ;; (deferred) would scan to detect rename-crash orphans.
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       ib   (requiring-resolve 'llm-memory.link-graph/inbound-links)
       _    (reg! s {:uri (ts/root-uri root) :name "orphan" :plans-dir "Plans"})
       _    (ts/write-plan! root "A.md" "# A\n\n## S\n\nLink: [b](wiki:orphan::B.md).")
       _    (ts/write-plan! root "B.md" "# B\n\n## S\n\nTarget content.")]
   (index-file! s (ts/root-uri root) (.getAbsolutePath (io/file root "Plans/A.md")))
   (index-file! s (ts/root-uri root) (.getAbsolutePath (io/file root "Plans/B.md")))
   (ib s "orphan::B.md") := ["orphan::A.md"]
   (retract-file-by-id! s "orphan::B.md")
   ;; A's outbound link entity is untouched by B's removal
   (count (store/query s '[:find ?l :where [?l :link/id]])) := 1
   ;; inbound-links still finds A (the link is still recorded pointing at B's file-id)
   (ib s "orphan::B.md") := ["orphan::A.md"]
   ;; An entirely-unknown file-id returns []
   (ib s "orphan::never-existed.md") := []
   (store/disconnect! s))
 :rcf)

(tests
 "index-root! — retracts :link/* entities alongside chunks and files"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       ruri (ts/root-uri root)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       _    (reg! s {:uri ruri :name "ir" :plans-dir "Plans"})]
   (ts/write-plan! root "A.md" "# A\n\n## S\n\nLink: [b](wiki:ir::B.md).")
   (ts/write-plan! root "B.md" "# B\n\n## S\n\nContent.")
   (index-root! s ruri)
   (pos? (count (store/query s '[:find ?l :where [?l :link/id]]))) := true
   ;; Delete A on disk, then re-index — the bulk retract should clear A's link entity.
   (.delete (io/file root "Plans/A.md"))
   (index-root! s ruri)
   (count (store/query s '[:find ?l :where [?l :link/id]])) := 0
   (store/disconnect! s))
 :rcf)

;; ---------------------------------------------------------------------------
;; Propagation tests (Steps 6 + 7-10)
;; ---------------------------------------------------------------------------

(tests
 "propagate-file-rename! — rewrites B's link when A is renamed"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       ruri (ts/root-uri root)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       _    (reg! s {:uri ruri :name "pfr" :plans-dir "Plans"})
       _    (ts/write-plan! root "A.md" "# A\n\n## S\n\nThe A file.")
       b-file (ts/write-plan! root "B.md"
                              "# B\n\n## S\n\nSee [alpha](wiki:pfr::A.md) for context.")]
   (index-file! s ruri (.getAbsolutePath (io/file root "Plans/A.md")))
   (index-file! s ruri (.getAbsolutePath b-file))
   ;; Simulate a rename on disk: move A.md → renamed/A2.md
   (.mkdirs (io/file root "Plans/renamed"))
   (.renameTo (io/file root "Plans/A.md") (io/file root "Plans/renamed/A2.md"))
   (rename-file! s ruri
                 (.getAbsolutePath (io/file root "Plans/A.md"))
                 (.getAbsolutePath (io/file root "Plans/renamed/A2.md")))
   (let [summary (propagate-file-rename! s "pfr::A.md" "pfr::renamed/A2.md")]
     (:rewritten summary) := ["pfr::B.md"]
     (:errors summary)    := [])
   ;; B's file on disk now points at the new path
   (.contains (slurp b-file) "wiki:pfr::renamed/A2.md") := true
   (.contains (slurp b-file) "wiki:pfr::A.md") := false
   ;; B's :link/* entity also updated via the re-index
   (ffirst (store/query s '[:find ?tid :where [_ :link/to-id ?tid]])) := "pfr::renamed/A2.md"
   (store/disconnect! s))
 :rcf)

(tests
 "propagate-file-rename! — preserves #slug suffix on anchored links"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       ruri (ts/root-uri root)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       _    (reg! s {:uri ruri :name "slug" :plans-dir "Plans"})
       _    (ts/write-plan! root "A.md" "# A\n\n## Design\n\nContent.")
       b-file (ts/write-plan! root "B.md"
                              "# B\n\n## S\n\nSee [design](wiki:slug::A.md#design).")]
   (index-file! s ruri (.getAbsolutePath (io/file root "Plans/A.md")))
   (index-file! s ruri (.getAbsolutePath b-file))
   (.renameTo (io/file root "Plans/A.md") (io/file root "Plans/NEW-A.md"))
   (rename-file! s ruri
                 (.getAbsolutePath (io/file root "Plans/A.md"))
                 (.getAbsolutePath (io/file root "Plans/NEW-A.md")))
   (propagate-file-rename! s "slug::A.md" "slug::NEW-A.md")
   ;; The #design anchor must survive the rename
   (.contains (slurp b-file) "wiki:slug::NEW-A.md#design") := true
   (store/disconnect! s))
 :rcf)

(tests
 "propagate-file-rename! — self-referential link is rewritten + re-indexed"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       ruri (ts/root-uri root)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       _    (reg! s {:uri ruri :name "self" :plans-dir "Plans"})
       a-file (ts/write-plan! root "A.md"
                              "# A\n\n## TOC\n\nTable of contents: [toc](wiki:self::A.md#toc).")]
   (index-file! s ruri (.getAbsolutePath a-file))
   (.renameTo a-file (io/file root "Plans/A-RENAMED.md"))
   (rename-file! s ruri
                 (.getAbsolutePath (io/file root "Plans/A.md"))
                 (.getAbsolutePath (io/file root "Plans/A-RENAMED.md")))
   (let [summary (propagate-file-rename! s "self::A.md" "self::A-RENAMED.md")]
     (:rewritten summary) := ["self::A-RENAMED.md"])
   (.contains (slurp (io/file root "Plans/A-RENAMED.md"))
              "wiki:self::A-RENAMED.md#toc") := true
   (store/disconnect! s))
 :rcf)

(tests
 "match-heading-renames — detects renamed slug via cosine similarity"
 (let [old-slugs {"alpha"   (float-array [1.0 0.0 0.0])
                  "beta"    (float-array [0.0 1.0 0.0])}
       new-slugs {"alpha-2" (float-array [1.0 0.0 0.0])
                  "beta"    (float-array [0.0 1.0 0.0])}
       r (match-heading-renames old-slugs new-slugs)]
   (count (:renamed r))    := 1
   (:old-slug (first (:renamed r))) := "alpha"
   (:new-slug (first (:renamed r))) := "alpha-2"
   ;; "beta" is unchanged across snapshots
   (contains? (set (:unchanged r)) "beta") := true))

(tests
 "propagate-heading-rename! — :exact? true prevents step-1 prefix-matching step-10"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       ruri (ts/root-uri root)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       _    (reg! s {:uri ruri :name "ex" :plans-dir "Plans"})
       _    (ts/write-plan! root "A.md" "# A\n\n## S\n\nContent.")
       b-file (ts/write-plan! root "B.md"
                              "# B\n\n## S\n\nSee [one](wiki:ex::A.md#step-1) and [ten](wiki:ex::A.md#step-10).")]
   (index-file! s ruri (.getAbsolutePath (io/file root "Plans/A.md")))
   (index-file! s ruri (.getAbsolutePath b-file))
   (propagate-heading-rename! s "ex::A.md" "step-1" "first")
   (.contains (slurp b-file) "wiki:ex::A.md#first")   := true
   (.contains (slurp b-file) "wiki:ex::A.md#step-10") := true
   ;; #step-1 should no longer appear literally (it was renamed to #first)
   (.contains (slurp b-file) "#step-1)") := false
   (store/disconnect! s))
 :rcf)

(tests
 "reconcile! — 2-pass: cross-file rename propagates to linker via pass-2"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       ruri (ts/root-uri root)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       _    (reg! s {:uri ruri :name "r2p" :plans-dir "Plans"})
       _    (ts/write-plan! root "A.md" "# A\n\n## S\n\nContent of A.")
       _    (ts/write-plan! root "B.md"
                            "# B\n\n## S\n\nSee [alpha](wiki:r2p::A.md) for context.")]
   (index-root! s ruri)
   ;; Rename A.md → renamed/A.md on disk (exact-hash rename — no content change)
   (.mkdirs (io/file root "Plans/renamed"))
   (.renameTo (io/file root "Plans/A.md") (io/file root "Plans/renamed/A.md"))
   (let [summary (reconcile! s ruri)]
     (:renamed summary)    := 1
     (:propagated summary) := 1
     (:errors summary)     := [])
   (.contains (slurp (io/file root "Plans/B.md"))
              "wiki:r2p::renamed/A.md") := true
   (store/disconnect! s))
 :rcf)

(tests
 "reconcile! — 2-pass: multi-rename (A and B both renamed; B links to A) propagates correctly"
 ;; The critical test: pass-2 path resolution must read A's settled new
 ;; DB path, not the pre-rename one. If pass-1 per-rename propagation
 ;; were used, B's linker lookup would resolve B's OLD path on disk and
 ;; fail.
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       ruri (ts/root-uri root)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       _    (reg! s {:uri ruri :name "mr" :plans-dir "Plans"})
       _    (ts/write-plan! root "A.md" "# A\n\n## S\n\nOriginal A content.")
       _    (ts/write-plan! root "B.md"
                            "# B\n\n## S\n\nPointer to [a](wiki:mr::A.md).")]
   (index-root! s ruri)
   ;; Rename BOTH in one cycle
   (.mkdirs (io/file root "Plans/x"))
   (.renameTo (io/file root "Plans/A.md") (io/file root "Plans/x/A.md"))
   (.renameTo (io/file root "Plans/B.md") (io/file root "Plans/x/B.md"))
   (let [summary (reconcile! s ruri)]
     (:renamed summary)    := 2
     (:errors summary)     := []
     ;; B is rewritten because A renamed. A has no inbound linkers, so
     ;; its own rename does not bump :propagated.
     (:propagated summary) := 1)
   (.contains (slurp (io/file root "Plans/x/B.md"))
              "wiki:mr::x/A.md") := true
   (store/disconnect! s))
 :rcf)

(tests
 "index-file-with-heading-diff! — propagates heading renames on modify"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       ruri (ts/root-uri root)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       _    (reg! s {:uri ruri :name "hd" :plans-dir "Plans"})
       a-file (ts/write-plan! root "A.md"
                              "# A\n\n## Design Overview\n\nOriginal design content with specific details about the architecture.")
       b-file (ts/write-plan! root "B.md"
                              "# B\n\n## S\n\nSee [design](wiki:hd::A.md#design-overview).")]
   (index-file! s ruri (.getAbsolutePath a-file))
   (index-file! s ruri (.getAbsolutePath b-file))
   ;; Rewrite A's heading (content stays semantically similar — required for cosine match)
   (spit a-file "# A\n\n## Architecture Overview\n\nOriginal design content with specific details about the architecture.")
   (let [result (index-file-with-heading-diff! s ruri (.getAbsolutePath a-file))]
     (pos? (:heading-renames result)) := true
     (:propagated result)             := 1
     (:errors result)                 := [])
   ;; B's file on disk now points at the new anchor
   (.contains (slurp b-file) "wiki:hd::A.md#architecture-overview") := true
   (.contains (slurp b-file) "wiki:hd::A.md#design-overview") := false
   (store/disconnect! s))
 :rcf)
