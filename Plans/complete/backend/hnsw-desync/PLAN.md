---
group: hnsw-desync
type: plan
related: search-improvement
tags: [datalevin, hnsw, vector-search, watcher, bug]
---

# HNSW Index Desync Fix — Plan

Fix the bug where file modifications cause chunk vectors to drop out of Datalevin's HNSW search index, making edited files invisible to search.

## Step 1: Reproduce and characterize

Confirm the bug in an RCF test by:
1. Creating a store, indexing a file, verifying `vec-neighbors` finds its chunks
2. Modifying the file (change text but keep the same H2 slugs → same `:chunk/id`s)
3. Re-indexing via `index-file!`
4. Asserting `vec-neighbors` still finds the chunks — **this should fail**, confirming the bug

Also test the case where slugs change (new `:chunk/id`) — this should work because it's an insert, not an upsert.

## Step 2: Identify the fix strategy

Three candidate approaches, ordered by preference:

### Option A: Retract-then-transact in `index-file!`

Instead of relying on upsert for existing chunks, explicitly retract all existing chunks for the file, then transact the new ones. This converts every re-index from an upsert into a delete + insert.

```clojure
;; In index-file! — replace the stale-only retract with retract-all-chunks:
(let [all-existing-eids (store/query store
                          '[:find [?c ...]
                            :in $ ?fid
                            :where [?f :file/id ?fid] [?c :chunk/file ?f]]
                          {:fid file-id})]
  (when (seq all-existing-eids)
    (store/retract! store (vec all-existing-eids))))
;; Then transact fresh chunk entities (with new eids)
(store/transact! store (into [file-entity] chunk-entities))
```

**Pro**: Simple, no Datalevin internals knowledge needed. Guaranteed to work since new entities always get HNSW entries.
**Con**: Slightly more work per re-index (retract + insert vs upsert). Eids change on every edit — but nothing external depends on eids.

### Option B: Post-transact HNSW repair

After the upsert transact, detect which chunks were upserted (not new) and re-transact just their `:chunk/vec` to force HNSW re-insertion. Similar to the manual `/tmp/fix-hnsw.clj` workaround but automated.

**Pro**: Minimal change to existing flow.
**Con**: Fragile — relies on the assumption that a second transact with the same vec forces HNSW insertion. This may break in future Datalevin versions.

### Option C: Report upstream and wait

File a Datalevin issue. `:db.type/vec` upserts should update the HNSW index.

**Pro**: Correct long-term fix.
**Con**: Unknown timeline. We need a workaround now.

**Recommendation**: Option A (retract-then-transact) as the immediate fix, Option C as a follow-up.

## Step 3: Implement Option A

**File**: `clj-llm-memory/src/llm_memory/index.clj`

Modify `index-file!` (lines 263–284):

**Current flow**:
1. Retract only stale chunks (where `:chunk/id` no longer exists)
2. Upsert file + all chunk entities

**New flow**:
1. Retract **all** existing chunks for the file
2. Transact file entity (upsert via `:file/id` identity — this is fine, no vec involved)
3. Transact all chunk entities (these are now inserts, not upserts — new eids, HNSW works)

Key detail: the file entity upsert is unaffected (no `:db.type/vec` attribute), so it can stay as-is. Only chunk handling changes.

## Step 4: Add diagnostic tooling

Add a health-check function to `llm-memory.core` or `llm-memory.tools` that detects HNSW desync:

```clojure
(defn hnsw-health
  "Compare chunk count vs HNSW index size. Returns {:total N :indexed N :missing N}."
  [store]
  ...)
```

Expose this via the MCP `plans_status` tool so the proxy can report it. This catches any future regressions.

## Step 5: Verify with RCF tests

1. The reproducer test from Step 1 should now pass
2. Run `make test` from `clj-llm-memory/`
3. REPL-verify against the running server: edit a file, wait for watcher, search for it

## Step 6: Verify on running server

1. `make install` from `clj-llm-memory/` to update the local Maven dep
2. Rebuild winze-server: `make install` from `winze-server/`
3. Restart the server
4. Edit a Plans file, wait for watcher re-index, search for it
5. Run the `hnsw-health` check — should report 0 missing

## Diagnostic Script

The `/tmp/fix-hnsw.clj` script from the initial investigation serves as both a diagnostic and a one-time fix. For reference:

```clojure
;; Detect: compare HNSW index size vs total chunk vectors
(let [s       (server/store)
      db      (d/db @(.conn s))
      hnsw    (count (d/q '[:find ?e :in $ ?dummy
                             :where [(vec-neighbors $ :chunk/vec ?dummy {:top 9999})
                                     [[?e _ _]]]]
                          db (float-array 384)))
      total   (count (d/q '[:find ?e :where [?e :chunk/vec _]] db))]
  (println "HNSW:" hnsw "Total:" total "Missing:" (- total hnsw)))

;; Fix: re-transact missing vectors (forces HNSW re-insertion)
;; See /tmp/fix-hnsw.clj for full script
```

## Files Modified

| File | Change |
|------|--------|
| `clj-llm-memory/src/llm_memory/index.clj` | Retract all chunks before re-transact (not just stale) |
| `clj-llm-memory/src/llm_memory/core.clj` or `tools.clj` | Add `hnsw-health` diagnostic |
| `mcp-proxy.clj` or `winze-server` tools | Expose health check in `plans_status` |

## Risks

- **Retract-all changes eids on every edit**: Nothing in the codebase uses raw Datalevin eids externally. The MCP tools use `:chunk/id` (string identity) and `:file/path`. No impact expected.
- **Performance**: Retract + insert is slightly more work than upsert, but `index-file!` is already dominated by embedding time (~50ms/chunk). The extra retract is negligible.
- **Datalevin version upgrade**: If a future Datalevin version fixes the HNSW upsert bug, Option A still works correctly (just does unnecessary retract+insert instead of upsert). No harm.
