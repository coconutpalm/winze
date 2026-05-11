(ns llm-memory.store.datahike
  "Stub Datahike implementation of PlanStore.

  This backend is deferred until:
    - Proximum reaches 1.0+ (currently 0.1.24, early beta)
    - JVM moves to Java 22+ (Proximum requires Panama Foreign Memory API)

  Architecture when implemented:
    Datahike  — Datalog storage with temporal queries (as-of, history)
    Proximum  — HNSW vector search (git-like versioning, S3 via Konserve)
    inference4j — in-JVM embeddings (all-MiniLM-L6-v2, ONNX Runtime)

  Datahike is preferred long-term because it preserves full transaction
  history — every index operation creates an immutable snapshot, enabling
  temporal queries like 'what did the index look like on March 1?'

  Proximum integration pattern (from the Einbetten demo):
    (d/q '[:find ?title ?text
           :in $ ?search-fn ?query
           :where
           [(?search-fn ?query 5) [?eid ...]]
           [?eid :chunk/text ?text]
           [?eid :chunk/article ?article]
           [?article :article/title ?title]]
         @conn
         #(search-similar-chunks prox-idx embedder %1 %2)
         \"machine learning algorithms\")"
  (:require [llm-memory.store.protocol :as proto]))

(defn- not-implemented! [method-name]
  (throw (UnsupportedOperationException.
          (str "DatahikePlanStore/" method-name " is not yet implemented. "
               "Datahike + Proximum backend is deferred until Proximum 1.0+ "
               "and Java 22+. Use DatalevinPlanStore for now."))))

(defrecord DatahikePlanStore [path embedder]
  proto/PlanStore
  (connect!    [_] (not-implemented! "connect!"))
  (disconnect! [_] (not-implemented! "disconnect!"))
  (db-exists?  [_] (not-implemented! "db-exists?"))
  (transact!   [_ _tx-data] (not-implemented! "transact!"))
  (retract!    [_ _eids]    (not-implemented! "retract!"))
  (query       [_ _q]       (not-implemented! "query"))
  (query       [_ _q _params] (not-implemented! "query"))
  (search-vec  [_ _embedding _opts] (not-implemented! "search-vec"))
  (pull-entity [_ _eid]     (not-implemented! "pull-entity"))
  (pull-many   [_ _eids]    (not-implemented! "pull-many")))
