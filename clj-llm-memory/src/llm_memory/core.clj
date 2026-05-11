(ns llm-memory.core
  "Public API for the clj-llm-memory library.

  This is the primary namespace consumers interact with. It provides
  store lifecycle, root management, indexing, and search — all backed
  by a pluggable PlanStore and Embedder."
  (:require [clojure.set :as cset]
            [clojure.string :as str]
            [llm-memory.store.protocol :as store]
            [llm-memory.store.datalevin :as datalevin]
            [llm-memory.store.datahike :as datahike]
            [llm-memory.embed.protocol :as embed]
            [llm-memory.embed.inference4j :as inference4j]
            [hyperfiddle.rcf :refer [tests]]
            [llm-memory.test-support :as ts]))

;; ---------------------------------------------------------------------------
;; Embedder resolution
;; ---------------------------------------------------------------------------

(defn- resolve-embedder
  "Resolve an embedding configuration to an Embedder instance.
   Accepts:
     :inference4j keyword — creates Inference4jEmbedder with defaults
     a map with :provider key — creates provider with options
     an Embedder instance — passes through"
  [embedding-config]
  (cond
    ;; Already an Embedder instance
    (satisfies? embed/Embedder embedding-config)
    embedding-config

    ;; Keyword shorthand
    (= :inference4j embedding-config)
    (inference4j/create-embedder)

    ;; Map with :provider key
    (map? embedding-config)
    (let [{:keys [provider]} embedding-config
          opts (dissoc embedding-config :provider)]
      (case provider
        :inference4j (inference4j/create-embedder opts)
        (throw (ex-info (str "Unknown embedding provider: " provider)
                        {:provider provider}))))

    :else
    (throw (ex-info "Invalid embedding config — expected :inference4j, a map, or an Embedder"
                    {:config embedding-config}))))

;; ---------------------------------------------------------------------------
;; Store lifecycle
;; ---------------------------------------------------------------------------

(defn open-store
  "Open a PlanStore backed by Datalevin.

  Options:
    :path      — database directory path (required)
    :embedding — embedding config:
                   :inference4j           — use inference4j defaults
                   {:provider :inference4j :model-id \"...\"}  — with options
                   an Embedder instance   — BYO embedder"
  [{:keys [path embedding] :or {embedding :inference4j}}]
  {:pre [(string? path)]}
  (let [embedder (resolve-embedder embedding)
        store    (datalevin/create-store {:path path :embedder embedder})]
    (store/connect! store)))

(defn close-store!
  "Close a PlanStore, releasing all resources."
  [store]
  (store/disconnect! store))

;; ---------------------------------------------------------------------------
;; Root management
;; ---------------------------------------------------------------------------

;; [(fn [event-type root-map]) ...]
;; event-type: :register or :remove
;; root-map:  {:root/uri ... :root/name ... :root/plans-dir ...}
(defonce ^:private root-listeners (atom []))

(defn add-root-listener!
  "Register a callback invoked after register-root! or remove-root!.
   Signature: (fn [event-type root-map])
   - event-type: :register or :remove
   - root-map:  {:root/uri ... :root/name ... :root/plans-dir ...}"
  [f]
  (swap! root-listeners conj f))

(defn- notify-root-listeners!
  "Fire all registered root listeners. Catches and prints errors per listener."
  [event-type root-map]
  (doseq [f @root-listeners]
    (try (f event-type root-map)
         (catch Throwable t
           (println "WARN: root-listener threw" (.getMessage t))))))

(defn register-root!
  "Register a project root for indexing.

  Enforces two uniqueness invariants required by cross-root wiki-link
  propagation (which resolves link targets by the root-name embedded in
  each file-id):

    1. A given :root/name must not already belong to a different :root/uri
       — would make file-ids ambiguous across roots.
    2. A given :root/uri must not already be registered under a different
       :root/name — root renames are unsupported; they would invalidate every
       stored old-name::* file-id and every :link/to-id referencing this root.

  Re-registering the same (uri, name) pair is idempotent.

  root-config:
    :uri       — unique URI, e.g. \"file:///Users/me/project\" (required)
    :name      — display name (defaults to last path segment)
    :plans-dir — relative path to Plans directory (default: \"Plans\")"
  [store {:keys [uri name plans-dir]
          :or   {plans-dir "Plans"}}]
  {:pre [(string? uri)]}
  (let [root-name       (or name (last (clojure.string/split uri #"/")))
        name-collisions (->> (store/query store
                                          '[:find ?uri
                                            :in $ ?nm
                                            :where
                                            [?r :root/name ?nm]
                                            [?r :root/uri ?uri]]
                                          {:nm root-name})
                             (map first)
                             (remove #{uri}))
        uri-rename      (->> (store/query store
                                          '[:find ?nm
                                            :in $ ?u
                                            :where
                                            [?r :root/uri ?u]
                                            [?r :root/name ?nm]]
                                          {:u uri})
                             (map first)
                             (remove #{root-name})
                             first)]
    (when (seq name-collisions)
      (throw (ex-info (str "Root name already in use by a different URI: " root-name)
                      {:name root-name :existing-uri (first name-collisions) :new-uri uri})))
    (when uri-rename
      (throw (ex-info (str "Root rename is not supported — URI " uri
                           " is already registered as '" uri-rename "'."
                           " Remove the root and re-register under the new name.")
                      {:uri uri :existing-name uri-rename :new-name root-name})))
    (store/transact! store [{:root/uri       uri
                             :root/name      root-name
                             :root/plans-dir plans-dir}])
    (notify-root-listeners! :register {:root/uri       uri
                                       :root/name      root-name
                                       :root/plans-dir plans-dir})))

(defn list-roots
  "Return all registered roots as maps."
  [store]
  (let [results (store/query store
                             '[:find ?e ?uri ?name ?dir
                               :where
                               [?e :root/uri ?uri]
                               [?e :root/name ?name]
                               [?e :root/plans-dir ?dir]])]
    (mapv (fn [[eid uri root-name plans-dir]]
            {:eid       eid
             :root/uri  uri
             :root/name root-name
             :root/plans-dir plans-dir})
          results)))

(defn remove-root!
  "Remove a root and all its files/chunks/links.

  Query-then-retract invariant: all four eid sets (chunks, links, files,
  roots) are captured up front in a single let. The link-eids query joins
  through :file/root, so it must run while file entities still exist;
  moving it below the file retract would return empty and leave orphan
  :link/* entities behind.

  Retract order: chunk-vecs → chunks → links → files → root.
  - chunk-vecs first: retract-chunk-vecs! from index.clj removes HNSW index
    nodes for :chunk/vec datoms. Skipping this step leaves stale HNSW nodes
    that throw NPE on the next vec-neighbors traversal.
  - links before files: the link-eids query already captured all relevant
    ids; this order just mirrors the query ordering for readability."
  [store uri]
  (let [root-data          (->> (list-roots store)
                                (filter #(= uri (:root/uri %)))
                                first)
        retract-chunk-vecs! (requiring-resolve 'llm-memory.index/retract-chunk-vecs!)
        root-eids  (store/query store
                                '[:find [?e ...]
                                  :in $ ?uri
                                  :where [?e :root/uri ?uri]]
                                {:uri uri})
        file-eids  (store/query store
                                '[:find [?f ...]
                                  :in $ ?uri
                                  :where
                                  [?r :root/uri ?uri]
                                  [?f :file/root ?r]]
                                {:uri uri})
        chunk-eids (store/query store
                                '[:find [?c ...]
                                  :in $ ?uri
                                  :where
                                  [?r :root/uri ?uri]
                                  [?f :file/root ?r]
                                  [?c :chunk/file ?f]]
                                {:uri uri})
        link-eids  (store/query store
                                '[:find [?l ...]
                                  :in $ ?uri
                                  :where
                                  [?r :root/uri ?uri]
                                  [?f :file/root ?r]
                                  [?l :link/from ?f]]
                                {:uri uri})]
    (when (seq chunk-eids)
      (retract-chunk-vecs! store chunk-eids)
      (store/retract! store (vec chunk-eids)))
    (when (seq link-eids)  (store/retract! store (vec link-eids)))
    (when (seq file-eids)  (store/retract! store (vec file-eids)))
    (when (seq root-eids)  (store/retract! store (vec root-eids)))
    (when root-data
      (notify-root-listeners! :remove root-data))))

;; ---------------------------------------------------------------------------
;; Indexing (single file — higher-level indexing in llm-memory.index)
;; ---------------------------------------------------------------------------

(defn index-file!
  "Index a single markdown file into the store.
  Delegates to llm-memory.index (loaded lazily to avoid circular deps).
  See llm-memory.index/index-file! for the full implementation."
  [store root-uri abs-path]
  (let [index-fn (requiring-resolve 'llm-memory.index/index-file!)]
    (index-fn store root-uri abs-path)))

(defn retract-file!
  "Remove a file and its chunks from the store."
  [store root-uri abs-path]
  (let [retract-fn (requiring-resolve 'llm-memory.index/retract-file!)]
    (retract-fn store root-uri abs-path)))

(defn rename-file!
  "Update a file's path (preserving entity identity)."
  [store root-uri old-path new-path]
  (let [rename-fn (requiring-resolve 'llm-memory.index/rename-file!)]
    (rename-fn store root-uri old-path new-path)))

(defn reconcile!
  "Diff db vs. disk for a root, handling renames/modifications/deletions."
  [store root-uri]
  (let [reconcile-fn (requiring-resolve 'llm-memory.index/reconcile!)]
    (reconcile-fn store root-uri)))

(defn index-root!
  "Full reindex — drop all data for this root and re-index from disk."
  [store root-uri]
  (let [index-root-fn (requiring-resolve 'llm-memory.index/index-root!)]
    (index-root-fn store root-uri)))

;; ---------------------------------------------------------------------------
;; Search helpers
;; ---------------------------------------------------------------------------

(defn- kw-match?
  "True if `val` appears as a trimmed token in a comma-separated string field,
   or equals the group name directly."
  [kw group tags related]
  (letfn [(contains-token? [field]
            (when field
              (some #(= kw (str/trim %)) (str/split field #","))))]
    (or (= kw group)
        (contains-token? tags)
        (contains-token? related))))

;; ---------------------------------------------------------------------------
;; Search
;; ---------------------------------------------------------------------------

(defn search
  "Semantic search across indexed chunks.

  query — text string to search for
  opts:
    :root-uri — scope to a single root (nil = all roots)
    :top      — max results (default 5)
    :status   — filter by file status (\"active\", \"complete\", \"deferred\")
    :type     — filter by doc type (\"context\", \"plan\", \"story\", etc.)
    :group    — filter by work-item group
    :since    — filter by modification date (ISO string or epoch long)
    :dedupe   — one result per file (default true)
    :detail   — :full (default), :summary, :files"
  [store query-text opts]
  (let [{:keys [top dedupe]
         :or   {top 5 dedupe true}} opts
        embedder (.-embedder store)
        qvec     (embed/embed-text embedder query-text)
        ;; Fetch extra results for deduplication headroom
        raw-top  (if dedupe (* top 3) top)
        raw      (store/search-vec store qvec {:top (min raw-top 60)})
        db       (fn [eid] (store/pull-entity store eid))]
    (->> raw
         ;; Enrich with entity data
         (map (fn [{:keys [eid distance]}]
                (let [chunk  (db eid)
                      file   (when-let [fref (:chunk/file chunk)]
                               (db (if (map? fref) (:db/id fref) fref)))
                      root   (when-let [rref (:file/root file)]
                               (db (if (map? rref) (:db/id rref) rref)))]
                  (merge
                   {:eid       eid
                    :distance  distance
                    :relevance (- 1.0 distance)
                    :chunk/id  (:chunk/id chunk)
                    :chunk/text (:chunk/text chunk)
                    :chunk/slug (:chunk/slug chunk)
                    :file/path (:file/path file)
                    :file/id   (:file/id file)}
                   (when (:file/status file)   {:file/status (:file/status file)})
                   (when (:file/type file)     {:file/type (:file/type file)})
                   (when (:file/group file)    {:file/group (:file/group file)})
                   (when (:file/modified file) {:file/modified (:file/modified file)})
                   (when (:file/jira file)     {:file/jira     (:file/jira file)})
                   (when (:file/tags file)     {:file/tags     (:file/tags file)})
                   (when (:file/related file)  {:file/related  (:file/related file)})
                   (when (:file/created file)  {:file/created  (:file/created file)})
                   (when root                  {:root/uri  (:root/uri root)
                                                :root/name (:root/name root)})))))
         ;; Apply metadata filters
         (filter (fn [r]
                   (let [{:keys [root-uri status type group keyword jira since]} opts
                         since-epoch (when since
                                       (if (number? since)
                                         since
                                         (quot (.toEpochMilli
                                                (.atStartOfDay
                                                 (java.time.LocalDate/parse since)
                                                 java.time.ZoneOffset/UTC))
                                               1000)))]
                     (and (or (nil? root-uri) (= root-uri (:root/uri r)))
                          (or (nil? status)   (= status (:file/status r)))
                          (or (nil? type)     (= type (:file/type r)))
                          (or (nil? group)    (= group (:file/group r)))
                          (or (nil? keyword)  (kw-match? keyword (:file/group r)
                                                         (:file/tags r)
                                                         (:file/related r)))
                          (or (nil? jira)     (= jira (:file/jira r)))
                          (or (nil? since-epoch)
                              (and (:file/modified r)
                                   (>= (:file/modified r) since-epoch)))))))
         ;; Deduplicate by file/id (unique across roots)
         (reduce (fn [acc r]
                   (if (and dedupe (contains? (:seen acc) (:file/id r)))
                     acc
                     (-> acc
                         (update :seen conj (:file/id r))
                         (update :results conj r))))
                 {:seen #{} :results []})
         :results
         (sort-by :distance)
         (take top)
         vec)))

(defn- first-chunk-text
  "Return the text of the first chunk (lowest :chunk/section) for a file eid."
  [store file-eid]
  (let [chunk-eids (store/query store
                                '[:find [?c ...]
                                  :in $ ?f
                                  :where [?c :chunk/file ?f]]
                                {:f file-eid})
        chunks     (seq (map #(store/pull-entity store %) chunk-eids))]
    (when chunks
      (:chunk/text (apply min-key #(or (:chunk/section %) 0) chunks)))))

(defn metadata-query
  "Query files by metadata filters only, without semantic search.
   Returns results sorted by modification date (most recent first),
   in the same shape as `search`."
  [store opts]
  (let [{:keys [status type group keyword jira]} opts
        db    (fn [eid] (store/pull-entity store eid))
        eids  (store/query store '[:find [?f ...] :where [?f :file/id]])
        files (map db eids)]
    (->> files
         (filter (fn [file]
                   (and (or (nil? status)  (= status (:file/status file)))
                        (or (nil? type)    (= type   (:file/type file)))
                        (or (nil? group)   (= group  (:file/group file)))
                        (or (nil? keyword) (kw-match? keyword (:file/group file)
                                                      (:file/tags file)
                                                      (:file/related file)))
                        (or (nil? jira)    (= jira   (:file/jira file))))))
         (map (fn [file]
                (let [root       (when-let [rref (:file/root file)]
                                   (db (if (map? rref) (:db/id rref) rref)))
                      first-text (first-chunk-text store (:db/id file))]
                  (cond-> {:file/path (:file/path file)
                           :file/id   (:file/id file)}
                    (:file/status file)   (assoc :file/status   (:file/status file))
                    (:file/type file)     (assoc :file/type     (:file/type file))
                    (:file/group file)    (assoc :file/group    (:file/group file))
                    (:file/modified file) (assoc :file/modified (:file/modified file))
                    (:file/jira file)     (assoc :file/jira     (:file/jira file))
                    (:file/tags file)     (assoc :file/tags     (:file/tags file))
                    (:file/related file)  (assoc :file/related  (:file/related file))
                    (:file/created file)  (assoc :file/created  (:file/created file))
                    root                  (merge {:root/uri  (:root/uri root)
                                                  :root/name (:root/name root)})
                    first-text            (assoc :chunk/text first-text)))))
         (sort-by (fn [r] (or (:file/modified r) 0)) >)
         vec)))

(defn related
  "Find all documents in a work-item group, plus cross-references.
   Returns {:files [...] :cross-refs #{group-name ...}}."
  [store group-name & [{:keys [root-uri]}]]
  (let [;; Find files in this group (use pull for optional attrs)
        file-eids (store/query store
                               '[:find [?f ...]
                                 :in $ ?group
                                 :where [?f :file/group ?group]]
                               {:group group-name})
        files     (mapv (fn [eid]
                          (let [e (store/pull-entity store eid)]
                            (cond-> {:file/id     (:file/id e)
                                     :file/path   (:file/path e)}
                              (:file/type e)   (assoc :file/type   (:file/type e))
                              (:file/status e) (assoc :file/status (:file/status e))
                              (:file/jira e)   (assoc :file/jira   (:file/jira e))
                              (:file/related e) (assoc :file/related (:file/related e)))))
                        file-eids)
        ;; Collect cross-reference groups from fm_related
        cross-refs (->> files
                        (keep :file/related)
                        (mapcat #(clojure.string/split % #","))
                        (map clojure.string/trim)
                        (remove #{group-name ""})
                        set)]
    {:files      (if root-uri
                   (filterv #(some? %) files) ;; TODO: root scoping
                   files)
     :cross-refs cross-refs}))

(defn recent
  "List recently modified files."
  [store & [{:keys [days root-uri doc-type status]
             :or   {days 7}}]]
  (let [cutoff    (- (quot (System/currentTimeMillis) 1000)
                     (* days 86400))
        results   (store/query store
                               '[:find ?fid ?path ?type ?status ?mod
                                 :in $ ?cutoff
                                 :where
                                 [?f :file/modified ?mod]
                                 [(>= ?mod ?cutoff)]
                                 [?f :file/id ?fid]
                                 [?f :file/path ?path]
                                 [?f :file/type ?type]
                                 [?f :file/status ?status]]
                               {:cutoff cutoff})]
    (->> results
         (map (fn [[fid path ftype fstatus mod-time]]
                {:file/id     fid
                 :file/path   path
                 :file/type   ftype
                 :file/status fstatus
                 :file/modified mod-time}))
         ;; Apply optional filters
         (filter (fn [r]
                   (and (or (nil? root-uri) true) ;; TODO: root scoping
                        (or (nil? doc-type) (= doc-type (:file/type r)))
                        (or (nil? status)   (= status (:file/status r))))))
         (sort-by :file/modified >)
         vec)))

(defn list-files
  "Enumerate all indexed files with their metadata."
  [store & [{:keys [root-uri]}]]
  (let [eids (if root-uri
               (store/query store
                            '[:find [?f ...]
                              :in $ ?ruri
                              :where
                              [?r :root/uri ?ruri]
                              [?f :file/root ?r]]
                            {:ruri root-uri})
               (store/query store
                            '[:find [?f ...]
                              :where [?f :file/id]]))]
    (mapv (fn [eid]
            (let [e (store/pull-entity store eid)]
              (cond-> {:file/id     (:file/id e)
                       :file/path   (:file/path e)}
                (:file/type e)   (assoc :file/type   (:file/type e))
                (:file/status e) (assoc :file/status (:file/status e))
                (:file/group e)  (assoc :file/group  (:file/group e))
                (:file/jira e)   (assoc :file/jira   (:file/jira e))
                (:file/title e)  (assoc :file/title  (:file/title e)))))
          eids)))

(defn status
  "Health check — store stats, embedder info, root summaries."
  [store]
  (let [file-count  (ffirst (store/query store
                                         '[:find (count ?f)
                                           :where [?f :file/id]]))
        chunk-count (ffirst (store/query store
                                         '[:find (count ?c)
                                           :where [?c :chunk/id]]))
        root-count  (ffirst (store/query store
                                         '[:find (count ?r)
                                           :where [?r :root/uri]]))
        embedder    (.-embedder store)]
    {:files      (or file-count 0)
     :chunks     (or chunk-count 0)
     :roots      (or root-count 0)
     :embedding  (embed/embedding-info embedder)
     :store-path (.-path store)}))

(defn hnsw-health
  "Compare total chunks vs HNSW-reachable chunks.
  Detects the desync bug where upserted vectors drop out of the HNSW index.
  Returns {:total N :indexed N :missing N}.

  Note: :db.type/vec attributes are not queryable via standard Datalog patterns,
  so we use :chunk/id count as the total and search-vec for the HNSW count.
  A random vector is used because cosine similarity is undefined for zero vectors."
  [store]
  (let [total   (or (ffirst (store/query store
                                         '[:find (count ?c)
                                           :where [?c :chunk/id]]))
                    0)
        dims    (embed/dimensions (.-embedder store))
        ;; Random unit-ish vector for cosine-safe HNSW enumeration
        qvec    (let [v (float-array dims)]
                  (dotimes [i dims]
                    (aset v i (float (- (Math/random) 0.5))))
                  v)
        indexed (if (pos? total)
                  (count (store/search-vec store qvec {:top 99999}))
                  0)]
    {:total   total
     :indexed indexed
     :missing (- total indexed)}))

(defn hnsw-desynced-files
  "Identify files with chunks missing from the HNSW index.
  Returns [{:root/uri str :file/path str :missing-chunks N} ...], or []
  when healthy. Uses set-difference between KV chunk eids and
  HNSW-reachable eids, then joins back to file/root metadata."
  [store]
  (let [all-eids  (set (store/query store
                                    '[:find [?e ...]
                                      :where [?e :chunk/id _]]))
        dims      (embed/dimensions (.-embedder store))
        qvec      (let [v (float-array dims)]
                    (dotimes [i dims]
                      (aset v i (float (- (Math/random) 0.5))))
                    v)
        hnsw-eids (if (seq all-eids)
                    (into #{} (map :eid) (store/search-vec store qvec {:top 99999}))
                    #{})
        missing   (cset/difference all-eids hnsw-eids)]
    (if (empty? missing)
      []
      (->> (store/query store
                        '[:find ?uri ?path ?plans-dir (count ?c)
                          :in $ [?c ...]
                          :where
                          [?c :chunk/file ?f]
                          [?f :file/path ?path]
                          [?f :file/root ?r]
                          [?r :root/uri ?uri]
                          [?r :root/plans-dir ?plans-dir]]
                        {:c (vec missing)})
           (mapv (fn [[uri path plans-dir cnt]]
                   {:root/uri       uri
                    :file/path      path
                    :root/plans-dir plans-dir
                    :missing-chunks cnt}))))))

;; ---------------------------------------------------------------------------
;; File watching (delegates to llm-memory.watcher)
;; ---------------------------------------------------------------------------

(defn start-watcher!
  "Start watching a root's Plans directory for changes."
  [store root-uri]
  (let [start-fn (requiring-resolve 'llm-memory.watcher/start-watcher!)]
    (start-fn store root-uri)))

(defn stop-watcher!
  "Stop a file watcher."
  [watcher]
  (let [stop-fn (requiring-resolve 'llm-memory.watcher/stop-watcher!)]
    (stop-fn watcher)))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(tests
 "Inference4jEmbedder — embed-text returns 384d float array"
 (let [emb @(deref #'ts/test-embedder)
       v   (embed/embed-text emb "hello world")]
   (.isArray (type v)) := true
   (count v) := 384)

 "Inference4jEmbedder — embed-texts returns a vector of float arrays"
 (let [emb @(deref #'ts/test-embedder)
       vs  (embed/embed-texts emb ["hello" "world"])]
   (count vs) := 2
   (.isArray (type (first vs))) := true)

 "Inference4jEmbedder — dimensions"
 (embed/dimensions @(deref #'ts/test-embedder)) := 384

 "Inference4jEmbedder — embedding-info"
 (let [info (embed/embedding-info @(deref #'ts/test-embedder))]
   (:provider info) := :inference4j
   (:dims info) := 384
   (string? (:model info)) := true)
 :rcf)

(tests
 "DatalevinPlanStore — connect, transact, query, disconnect"
 (let [s (ts/fresh-store)]
   (store/transact! s [{:root/uri "file:///test" :root/name "test" :root/plans-dir "Plans"}])
   (let [roots (store/query s '[:find ?uri :where [?e :root/uri ?uri]])]
     (count roots) := 1
     (ffirst roots) := "file:///test")
   (store/disconnect! s)
   nil := nil)
 :rcf)

(tests
 "Multi-root — two roots with same-named files produce separate entities"
 (let [s (ts/fresh-store)]
   (ts/seed-root! s {:root-uri "file:///project-a" :root-name "project-a"
                     :file-id "a::CONTEXT.md" :file-path "dev/CONTEXT.md"
                     :text "Project A handles cache invalidation"
                     :slug "overview" :status "active" :type "context" :group "cache"})
   (ts/seed-root! s {:root-uri "file:///project-b" :root-name "project-b"
                     :file-id "b::CONTEXT.md" :file-path "dev/CONTEXT.md"
                     :text "Project B handles GPU cost reporting"
                     :slug "overview" :status "active" :type "context" :group "gpu"})
   (let [files (store/query s '[:find ?id :where [?f :file/id ?id]])]
     (count files) := 2)
   (let [chunks (store/query s '[:find ?id :where [?c :chunk/id ?id]])]
     (count chunks) := 2)
   (store/disconnect! s))
 :rcf)

(tests
 "Search scoping — root-uri filters results to one root"
 (let [s (ts/fresh-store)]
   (ts/seed-root! s {:root-uri "file:///alpha" :root-name "alpha"
                     :file-id "alpha::PLAN.md" :file-path "dev/PLAN.md"
                     :text "Kubernetes deployment strategy with Helm charts"
                     :slug "k8s" :status "active" :type "plan" :group "k8s"})
   (ts/seed-root! s {:root-uri "file:///beta" :root-name "beta"
                     :file-id "beta::PLAN.md" :file-path "dev/PLAN.md"
                     :text "Docker container orchestration and scaling"
                     :slug "docker" :status "active" :type "plan" :group "docker"})
   (let [all-results (search s "container deployment" {:top 10})]
     (>= (count all-results) 2) := true)
   (let [alpha-results (search s "deployment strategy"
                               {:top 10 :root-uri "file:///alpha"})]
     (every? #(= "file:///alpha" (:root/uri %)) alpha-results) := true)
   (store/disconnect! s))
 :rcf)

(tests
 "Search scoping — metadata filters (status, type, group)"
 (let [s (ts/fresh-store)]
   (ts/seed-root! s {:root-uri "file:///proj" :root-name "proj"
                     :file-id "proj::ACTIVE.md" :file-path "dev/ACTIVE.md"
                     :text "Active work on rate limiting implementation"
                     :slug "rate-limit" :status "active" :type "plan" :group "rate-limiting"})
   (ts/seed-root! s {:root-uri "file:///proj" :root-name "proj"
                     :file-id "proj::DONE.md" :file-path "complete/DONE.md"
                     :text "Completed rate limiting deployment and verification"
                     :slug "done" :status "complete" :type "story" :group "rate-limiting"})
   (let [active (search s "rate limiting" {:top 10 :status "active"})]
     (every? #(= "active" (:file/status %)) active) := true)
   (let [plans (search s "rate limiting" {:top 10 :type "plan"})]
     (every? #(= "plan" (:file/type %)) plans) := true)
   (let [rl (search s "implementation" {:top 10 :group "rate-limiting"})]
     (every? #(= "rate-limiting" (:file/group %)) rl) := true)
   (store/disconnect! s))
 :rcf)

(tests
 "Retract — retract! removes entities"
 (let [s (ts/fresh-store)]
   (store/transact! s [{:root/uri "file:///x" :root/name "x" :root/plans-dir "Plans"}])
   (let [eid (ffirst (store/query s '[:find ?e :where [?e :root/uri "file:///x"]]))]
     (store/retract! s [eid])
     (count (store/query s '[:find ?e :where [?e :root/uri]])) := 0))
 :rcf)

(tests
 "Core API — open-store + register-root! + list-roots + status"
 (let [s (open-store {:path (str "/tmp/llm-mem-test-" (System/nanoTime))
                      :embedding :inference4j})]
   (let [st (status s)]
     (:files st) := 0
     (:chunks st) := 0
     (:roots st) := 0
     (:store-path st) := (.-path s))
   (register-root! s {:uri "file:///project-one" :name "one"})
   (register-root! s {:uri "file:///project-two" :name "two"})
   (let [roots (list-roots s)]
     (count roots) := 2)
   (close-store! s))
 :rcf)

(tests
 "Core API — remove-root! cascades to files and chunks"
 (let [s (ts/fresh-store)]
   (ts/seed-root! s {:root-uri "file:///doomed" :root-name "doomed"
                     :file-id "doomed::X.md" :file-path "X.md"
                     :text "This root will be removed"
                     :slug "sec" :status "active" :type "context" :group "test"})
   (count (store/query s '[:find ?e :where [?e :root/uri]])) := 1
   (count (store/query s '[:find ?e :where [?e :file/id]])) := 1
   (count (store/query s '[:find ?e :where [?e :chunk/id]])) := 1
   (remove-root! s "file:///doomed")
   (count (store/query s '[:find ?e :where [?e :root/uri]])) := 0
   (count (store/query s '[:find ?e :where [?e :file/id]])) := 0
   (count (store/query s '[:find ?e :where [?e :chunk/id]])) := 0
   (store/disconnect! s))
 :rcf)

(tests
 "register-root! — rejects cross-URI name collision"
 (let [s (ts/fresh-store)]
   (register-root! s {:uri "file:///a" :name "foo"})
   ;; Same name under a different URI must throw
   (try
     (register-root! s {:uri "file:///b" :name "foo"})
     :should-not-reach
     (catch clojure.lang.ExceptionInfo e
       (.contains (.getMessage e) "already in use by a different URI") := true))
   (store/disconnect! s))
 :rcf)

(tests
 "register-root! — rejects root-rename (same URI, different name)"
 (let [s (ts/fresh-store)]
   (register-root! s {:uri "file:///a" :name "foo"})
   (try
     (register-root! s {:uri "file:///a" :name "bar"})
     :should-not-reach
     (catch clojure.lang.ExceptionInfo e
       (.contains (.getMessage e) "Root rename is not supported") := true))
   (store/disconnect! s))
 :rcf)

(tests
 "register-root! — idempotent when same (uri, name) re-registered"
 (let [s (ts/fresh-store)]
   (register-root! s {:uri "file:///a" :name "foo"})
   (register-root! s {:uri "file:///a" :name "foo"})
   ;; Still exactly one root entity
   (count (store/query s '[:find ?e :where [?e :root/uri]])) := 1
   (store/disconnect! s))
 :rcf)

(tests
 "remove-root! — retracts :link/* entities too (no orphans)"
 (let [s (ts/fresh-store)]
   (ts/seed-root! s {:root-uri "file:///doomed-links" :root-name "doomed-links"
                     :file-id "doomed-links::A.md" :file-path "A.md"
                     :text "A has outbound wiki links"
                     :slug "sec" :status "active" :type "context" :group "test"})
   ;; Transact a :link/* entity referencing the seeded file
   (store/transact! s [{:link/id    "doomed-links::A.md@@doomed-links::B.md@@"
                        :link/from  [:file/id "doomed-links::A.md"]
                        :link/to-id "doomed-links::B.md"
                        :link/slug  ""}])
   (count (store/query s '[:find ?e :where [?e :link/id]])) := 1
   (remove-root! s "file:///doomed-links")
   ;; Every :link/* entity for this root should be gone
   (count (store/query s '[:find ?e :where [?e :link/id]])) := 0
   (store/disconnect! s))
 :rcf)

(tests
 "remove-root! — HNSW health is clean after whole-root removal (no stale vec nodes)"
 (let [s (ts/fresh-store)]
   (ts/seed-root! s {:root-uri "file:///hnsw-root" :root-name "hnsw-root"
                     :file-id "hnsw-root::H.md" :file-path "H.md"
                     :text "Content with vector for HNSW hygiene check"
                     :slug "sec" :status "active" :type "context" :group "test"})
   (:total   (hnsw-health s)) := 1
   (:indexed (hnsw-health s)) := 1
   (remove-root! s "file:///hnsw-root")
   ;; After removal, both counts must be zero. Before the retract-chunk-vecs!
   ;; fix, the HNSW node survived the entity retract and :missing was > 0.
   (:total   (hnsw-health s)) := 0
   (:missing (hnsw-health s)) := 0
   (store/disconnect! s))
 :rcf)

(tests
 "Core API — related returns files by group"
 (let [s (ts/fresh-store)]
   (ts/seed-root! s {:root-uri "file:///proj" :root-name "proj"
                     :file-id "proj::CTX.md" :file-path "dev/CTX.md"
                     :text "GPU report context" :slug "ctx"
                     :status "active" :type "context" :group "gpu-report"})
   (ts/seed-root! s {:root-uri "file:///proj" :root-name "proj"
                     :file-id "proj::PLAN.md" :file-path "dev/PLAN.md"
                     :text "GPU report implementation plan" :slug "plan"
                     :status "active" :type "plan" :group "gpu-report"})
   (let [{:keys [files]} (related s "gpu-report")]
     (count files) := 2
     (set (map :file/type files)) := #{"context" "plan"})
   (store/disconnect! s))
 :rcf)

(tests
 "Core API — recent returns recently modified files"
 (let [s (ts/fresh-store)]
   (ts/seed-root! s {:root-uri "file:///proj" :root-name "proj"
                     :file-id "proj::NEW.md" :file-path "dev/NEW.md"
                     :text "A newly created document" :slug "new"
                     :status "active" :type "context" :group "fresh"})
   (let [recents (recent s {:days 1})]
     (>= (count recents) 1) := true
     (:file/id (first recents)) := "proj::NEW.md")
   (store/disconnect! s))
 :rcf)

(tests
 "Core API — hnsw-health reports zero missing for freshly seeded store"
 (let [s (ts/fresh-store)]
   (ts/seed-root! s {:root-uri "file:///proj" :root-name "proj"
                     :file-id "proj::H.md" :file-path "H.md"
                     :text "HNSW health check test content"
                     :slug "health" :status "active" :type "context" :group "test"})
   (let [h (hnsw-health s)]
     (:total h)   := 1
     (:indexed h) := 1
     (:missing h) := 0)
   (store/disconnect! s))
 :rcf)

(tests
 "Core API — list-files enumerates all indexed files"
 (let [s (ts/fresh-store)]
   (ts/seed-root! s {:root-uri "file:///proj" :root-name "proj"
                     :file-id "proj::A.md" :file-path "A.md"
                     :text "File A" :slug "a"
                     :status "active" :type "context" :group "test"})
   (ts/seed-root! s {:root-uri "file:///proj" :root-name "proj"
                     :file-id "proj::B.md" :file-path "B.md"
                     :text "File B" :slug "b"
                     :status "complete" :type "plan" :group "test"})
   (let [all-files (list-files s)]
     (count all-files) := 2)
   (store/disconnect! s))
 :rcf)

(tests
 "DatahikePlanStore stub — throws UnsupportedOperationException"
 (let [stub (datahike/->DatahikePlanStore "/tmp/x" nil)]
   (try
     (store/connect! stub)
     :should-not-reach
     (catch UnsupportedOperationException e
       (.contains (.getMessage e) "not yet implemented") := true)))
 :rcf)
