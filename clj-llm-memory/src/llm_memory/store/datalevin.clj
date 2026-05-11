(ns llm-memory.store.datalevin
  "Datalevin implementation of PlanStore.

  Uses `:db.type/vec` with an external Embedder (inference4j) for vector
  search. Datalevin's built-in embedding (`:db/embedding`) is not available
  in dtlvnative 0.16.5 (shipped with Datalevin 0.10.7).

  Schema entities:
    :root/*   — project root (uri, name, plans-dir)
    :file/*   — indexed markdown file (path, hash, metadata, belongs to root)
    :chunk/*  — text chunk within a file (text, vector, slug, belongs to file)

  Thread safety:
    A ReentrantReadWriteLock guards the connection against backup cycles.
    All query/transact operations acquire the read lock; the backup scheduler
    acquires the write lock before disconnect/snapshot/reconnect."
  (:require [datalevin.core :as d]
            [llm-memory.store.protocol :as proto])
  (:import [java.io File]
           [java.util.concurrent.locks ReentrantReadWriteLock]))

;; ---------------------------------------------------------------------------
;; Schema
;; ---------------------------------------------------------------------------

(def schema
  {;; --- Root entities ---
   :root/uri       {:db/valueType :db.type/string  :db/unique :db.unique/identity}
   :root/name      {:db/valueType :db.type/string}
   :root/plans-dir {:db/valueType :db.type/string}

   ;; --- File entities ---
   :file/id           {:db/valueType :db.type/string  :db/unique :db.unique/identity}
   :file/path         {:db/valueType :db.type/string}
   :file/root         {:db/valueType :db.type/ref}
   :file/content-hash {:db/valueType :db.type/string}
   :file/modified     {:db/valueType :db.type/long}
   :file/modified-str {:db/valueType :db.type/string}
   :file/status       {:db/valueType :db.type/string}
   :file/type         {:db/valueType :db.type/string}
   :file/group        {:db/valueType :db.type/string}
   :file/jira         {:db/valueType :db.type/string}
   :file/related      {:db/valueType :db.type/string}
   :file/tags         {:db/valueType :db.type/string}
   :file/created      {:db/valueType :db.type/string}

   ;; --- Chunk entities ---
   :chunk/id   {:db/valueType :db.type/string  :db/unique :db.unique/identity}
   :chunk/file {:db/valueType :db.type/ref}
   :chunk/text {:db/valueType :db.type/string}
   :chunk/vec  {:db/valueType :db.type/vec}
   :chunk/slug {:db/valueType :db.type/string}
   :chunk/section {:db/valueType :db.type/long}

   ;; --- Outbound wiki-link entities ---
   ;; :link/id is the composite identity "<from-fid>@@<to-fid>@@<slug>".
   ;; :link/to-id stores the bare file-id (no "wiki:" prefix) so inbound-links
   ;; queries can key off the raw file-id produced by compute-file-id.
   ;; :link/slug is "" for file-only links, otherwise the heading anchor.
   :link/id    {:db/valueType :db.type/string  :db/unique :db.unique/identity}
   :link/from  {:db/valueType :db.type/ref}
   :link/to-id {:db/valueType :db.type/string}
   :link/slug  {:db/valueType :db.type/string}

   ;; --- Page title (first H1 content, full text) ---
   :file/title {:db/valueType :db.type/string}})

(def ^:private vector-opts
  {:dimensions  384
   :metric-type :cosine})

;; ---------------------------------------------------------------------------
;; DatalevinPlanStore
;; ---------------------------------------------------------------------------

(defrecord DatalevinPlanStore [path      ;; String — database directory path
                               embedder  ;; Embedder — for search queries
                               conn      ;; atom wrapping Datalevin connection
                               lock      ;; ReentrantReadWriteLock — guards backup cycles
                               ]
  proto/PlanStore

  ;; connect! and disconnect! are called while the write lock is held by the
  ;; backup cycle — they must NOT acquire the read lock themselves.
  (connect! [this]
    (let [c (d/get-conn path schema {:vector-opts vector-opts})]
      (reset! conn c))
    this)

  (disconnect! [_]
    (when-let [c @conn]
      (d/close c)
      (reset! conn nil))
    nil)

  (db-exists? [_]
    (.exists (File. ^String path)))

  (transact! [_ tx-data]
    (let [rl (.readLock ^ReentrantReadWriteLock lock)]
      (.lock rl)
      (try
        (d/transact! @conn tx-data)
        (finally (.unlock rl)))))

  (retract! [_ eids]
    (let [rl          (.readLock ^ReentrantReadWriteLock lock)
          retractions (mapv (fn [eid] [:db.fn/retractEntity eid]) eids)]
      (.lock rl)
      (try
        (d/transact! @conn retractions)
        (finally (.unlock rl)))))

  (query [_ q]
    (let [rl (.readLock ^ReentrantReadWriteLock lock)]
      (.lock rl)
      (try
        (d/q q (d/db @conn))
        (finally (.unlock rl)))))

  (query [_ q params]
    (let [rl (.readLock ^ReentrantReadWriteLock lock)]
      (.lock rl)
      (try
        (apply d/q q (d/db @conn) (vals params))
        (finally (.unlock rl)))))

  (search-vec [_ embedding opts]
    (let [rl (.readLock ^ReentrantReadWriteLock lock)]
      (.lock rl)
      (try
        (let [{:keys [top]
               :or   {top 5}} opts
              db  (d/db @conn)
              ;; Use vec-neighbors inside Datalog for correct eid mapping.
              ;; Returns [eid attr vec] tuples — no distance from Datalog,
              ;; so we compute cosine distance from the returned vectors.
              raw (d/q '[:find ?e ?vec
                         :in $ ?qvec ?top
                         :where
                         [(vec-neighbors $ :chunk/vec ?qvec {:top ?top}) [[?e _ ?vec]]]]
                       db embedding top)]
          (mapv (fn [[eid vec-val]]
                  (let [dot    (areduce ^floats embedding i acc (float 0)
                                        (+ acc (* (aget ^floats embedding i)
                                                  (aget ^floats vec-val i))))
                        norm-a (Math/sqrt (areduce ^floats embedding i acc (float 0)
                                                   (+ acc (* (aget ^floats embedding i)
                                                             (aget ^floats embedding i)))))
                        norm-b (Math/sqrt (areduce ^floats vec-val i acc (float 0)
                                                   (+ acc (* (aget ^floats vec-val i)
                                                             (aget ^floats vec-val i)))))
                        cosine-sim (if (or (zero? norm-a) (zero? norm-b))
                                     0.0
                                     (/ dot (* norm-a norm-b)))
                        distance   (- 1.0 cosine-sim)]
                    {:eid eid :distance distance}))
                raw))
        (finally (.unlock rl)))))

  (pull-entity [_ eid]
    (let [rl (.readLock ^ReentrantReadWriteLock lock)]
      (.lock rl)
      (try
        (d/pull (d/db @conn) '[*] eid)
        (finally (.unlock rl)))))

  (pull-many [_ eids]
    (let [rl (.readLock ^ReentrantReadWriteLock lock)]
      (.lock rl)
      (try
        (d/pull-many (d/db @conn) '[*] eids)
        (finally (.unlock rl))))))

(defn create-store
  "Create a DatalevinPlanStore (not yet connected).
   Call (connect! store) to open the database.

   Options:
     :path      — directory path for the Datalevin database (required)
     :embedder  — an Embedder instance for search queries (required)"
  [{:keys [path embedder]}]
  {:pre [(string? path) (some? embedder)]}
  (->DatalevinPlanStore path embedder (atom nil) (ReentrantReadWriteLock.)))
