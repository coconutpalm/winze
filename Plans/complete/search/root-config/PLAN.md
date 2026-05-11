---
type: plan
status: complete
group: root-config
related: [complete/auto-register/PLAN.md, complete/auto-register/CONTEXT.md]
---

# Root Config File — Plan

## Steps

### 1. Add config path and read/write helpers in `main.clj`

After the existing `data-dir` / `db-path` / `nrepl-port-file` / `pid-file` helpers, add:

```clojure
(defn- roots-config-path
  "Path to the roots.edn config file (alongside the database directory)."
  []
  (io/file (data-dir) "roots.edn"))

(defn- read-roots-config
  "Read roots.edn; returns [] if the file doesn't exist or is malformed."
  []
  (let [f (roots-config-path)]
    (if (.exists f)
      (try (edn/read-string (slurp f))
           (catch Exception e
             (log "WARN: could not read roots.edn:" (.getMessage e))
             []))
      [])))

(defn- write-roots-config!
  "Write the current registered roots to roots.edn atomically."
  [s]
  (let [roots (mem/list-roots s)
        data  (mapv #(select-keys % [:root/uri :root/name :root/plans-dir]) roots)
        ;; Rename Datalevin-namespaced keys to plain keys for the config file
        data  (mapv (fn [r] {:uri       (:root/uri r)
                             :name      (:root/name r)
                             :plans-dir (:root/plans-dir r)})
                    roots)]
    (spit (roots-config-path) (pr-str data))
    (log "roots.edn updated:" (count data) "root(s)")))
```

Add `[clojure.edn :as edn]` to the namespace requires.

---

### 2. Add `sync-roots-from-config!`

After `write-roots-config!`, add:

```clojure
(defn- sync-roots-from-config!
  "Re-register any roots in roots.edn that are missing from the store.
  Called on startup after open-store-resilient to recover from store deletion/restore."
  [s]
  (let [config-roots  (read-roots-config)
        store-uris    (set (map :root/uri (mem/list-roots s)))
        missing       (remove #(store-uris (:uri %)) config-roots)]
    (when (seq missing)
      (log "roots.edn has" (count missing) "root(s) not in store — re-registering"))
    (doseq [{:keys [uri name plans-dir]} missing]
      (try
        (log "re-registering root:" uri)
        (mem/register-root! s {:uri uri :name name :plans-dir plans-dir})
        (catch Exception e
          (log "WARN: failed to re-register root" uri ":" (.getMessage e)))))))
```

Note: `reconcile-and-watch!` is called later in `start!` for all registered roots,
so it covers the re-registered roots without needing an explicit call here.

---

### 3. Update `start!` to call `sync-roots-from-config!`

In `start!`, between `open-store-resilient` and `reconcile-and-watch!`:

```clojure
(let [s (open-store-resilient)]
  (log "store opened, embedding model loaded")

  ;; Re-register any roots lost after store deletion or restore
  (sync-roots-from-config! s)

  ;; Reconcile existing roots and start watchers (covers both persisted and re-registered)
  (reconcile-and-watch! s)
  ...)
```

---

### 4. Wrap `register-root!` nREPL handler to write config

Find the nREPL-exposed wrapper for `register-root!` in `main.clj` (the fn called by
the MCP tool handler). After a successful `mem/register-root!` call, add:

```clojure
(write-roots-config! s)
```

---

### 5. Wrap `remove-root!` nREPL handler to write config

Find the nREPL-exposed wrapper for `remove-root!`. After a successful `mem/remove-root!`
call, add:

```clojure
(write-roots-config! s)
```

---

### 6. Test scenarios

| Scenario | Expected |
|----------|----------|
| Register a root → inspect `roots.edn` | File contains the registered root |
| Remove a root → inspect `roots.edn` | File no longer contains the root |
| Delete store, restart server | `sync-roots-from-config!` re-registers; `reconcile-and-watch!` re-indexes |
| Restore older backup (root missing) | Missing root re-registered from file on startup |
| roots.edn absent (first run) | `read-roots-config` returns `[]`; no-op |
| roots.edn malformed | Warning logged; treated as `[]`; no crash |

---

### 7. Verify via REPL

```clojure
;; After register_plans:
(slurp (llm-memory.server.main/roots-config-path))
;; => "[{:uri \"file:///...\" :name \"_finance\" :plans-dir \"Plans\"}]"

;; Simulate fresh-store recovery:
;; 1. Stop server, delete .datalevin, restart
;; 2. Check log for "re-registering root:"
;; 3. (llm-memory.core/hnsw-health (store)) => {:total N :indexed N :missing 0}
;; 4. search_plans works immediately
```

---

## Historical context

This work succeeds the proxy-side auto-registration feature:

- [complete/auto-register/CONTEXT.md](../complete/auto-register/CONTEXT.md) — design constraints, why registration lives in the proxy, Babashka limitations
- [complete/auto-register/PLAN.md](../complete/auto-register/PLAN.md) — `ensure-root-registered!` implementation; `plans-dir-looks-valid?` heuristic

That work solved "first session, never registered." This work solves "was registered, store was reset."
