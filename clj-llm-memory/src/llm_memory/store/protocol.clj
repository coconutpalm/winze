(ns llm-memory.store.protocol
  "Storage abstraction for the Plans memory system.

  `PlanStore` defines the contract that both Datalevin and (future) Datahike
  backends must implement. All operations accept an optional `:root-uri` to
  scope to a single project; omitting it searches across all roots.")

(defprotocol PlanStore
  ;; --- Lifecycle ---
  (connect!    [this]          "Open/create the database. Returns this.")
  (disconnect! [this]          "Close the database. Returns nil.")
  (db-exists?  [this]          "True if the database already exists on disk.")

  ;; --- Transaction ---
  (transact!   [this tx-data]  "Transact a vec of datoms/maps. Returns tx-report.")
  (retract!    [this eids]     "Retract entities by eid. Returns tx-report.")

  ;; --- Query ---
  (query       [this q]
    [this q params] "Run a Datalog query. params is a map of :in bindings.")

  ;; --- Vector search ---
  (search-vec  [this embedding opts]
    "Search by pre-computed embedding vector (float[]).
     opts: {:top N, :root-uri str}
     Returns [{:eid N :distance F :chunk/id str ...} ...]")

  ;; --- Entity access ---
  (pull-entity [this eid]      "Pull all attributes for an entity.")
  (pull-many   [this eids]     "Pull all attributes for multiple entities."))
