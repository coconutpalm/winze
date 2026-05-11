---
created: 2026-03-30
status: complete
related: [CONTEXT.md, complete/hnsw-desync/PLAN.md]
tags: [datalevin, hnsw, vector-search, winze, plans-search]
---

# HNSW Re-Desync Investigation — Plan

## Goal

Identify which files have desynced HNSW chunks (34 missing), understand why the desync
occurred despite the retract-then-transact fix, then repair and prevent recurrence.

---

## Step 1 — Fix the diagnostic to correctly identify affected files

The previous attempt used `(map :chunk/id hnsw-results)` but `search-vec` returns
`{:eid :distance}` maps. Correct approach:

```clojure
(let [s       (server/store)
      root-uri "file:///Users/dorme/code/_finance"
      dims     384
      qvec     (let [v (float-array dims)]
                 (dotimes [i dims] (aset v i (float (- (Math/random) 0.5)))) v)
      ;; HNSW-reachable entity IDs
      hnsw-eids (into #{} (map :eid (sp/search-vec s qvec {:top 99999})))
      ;; All chunk entity IDs from KV
      all-eids  (into #{} (map first
                            (d/q '[:find [?e ...] :where [?e :chunk/id _]]
                                 (d/db @(.conn s)))))
      missing-eids (clojure.set/difference all-eids hnsw-eids)]
  ;; Join missing eids with file paths
  (d/q '[:find ?path (count ?c)
          :in $ [?c ...]
          :where [?c :chunk/file ?f] [?f :file/path ?path]]
       (d/db @(.conn s)) (vec missing-eids)))
```

**Note**: If `sp/search-vec` from the REPL still returns too few results, investigate
hypothesis H3 (non-deterministic `vec-neighbors`). Try calling `hnsw-health` from the
server namespace instead.

---

## Step 2 — Repair: re-index only the affected files

Once the affected files are identified, call `idx/index-file!` for each:

```clojure
(let [plans-dir "/Users/dorme/code/_finance/Plans"
      root-uri  "file:///Users/dorme/code/_finance"]
  (doseq [path affected-paths]
    (let [abs (str plans-dir "/" path)]
      (println "Re-indexing" path)
      (idx/index-file! s root-uri abs)))
  (println "After repair:" (mem/hnsw-health s)))
```

Verify with `mem/hnsw-health` → `:missing 0`.

**Fallback**: If identifying individual files proves unreliable, use `idx/index-root!`
to retract and re-index the full root. This takes ~30–60s but guarantees a clean state.

---

## Step 3 — Identify when the desync occurred (root cause)

Check git log for `clj-llm-memory/src/llm_memory/index.clj` to find the commit that
added the retract-then-transact fix:

```bash
cd winze/clj-llm-memory && git log --oneline src/llm_memory/index.clj | head -20
```

Then check modification times of the affected files (from Step 1) against the fix
deployment date. Files modified BETWEEN the source fix commit and the jar rebuild are
the suspects.

Also check whether the affected files are all in `complete/` (archived during that
window) which would suggest the archiving workflow is the trigger.

---

## Step 4 — Investigate `sp/search-vec` discrepancy (optional)

If `sp/search-vec` from REPL gives different results than `hnsw-health` (which calls the
same protocol function), this is a secondary bug that makes HNSW diagnostics unreliable
from the REPL.

Test: call `hnsw-health` from a fresh namespace in the running server REPL to confirm it
returns 0 missing after the repair. If the discrepancy persists, file a note in the
CONTEXT.md — it doesn't block the fix but affects diagnostic tooling.

---

## Step 5 — Add an automated repair to server startup

If the root cause is confirmed to be "files modified during source-fix → jar-rebuild
window", add a startup check to `main.clj`:

```clojure
;; In reconcile-and-watch! or start!
(let [h (mem/hnsw-health s)]
  (when (pos? (:missing h))
    (log "WARN: HNSW desync detected:" (:missing h) "chunks missing — running repair")
    ;; Force re-index of all files to repair HNSW
    (doseq [root (mem/list-roots s)]
      (idx/index-root! s (:root/uri root)))))
```

This adds ~30s to startup when a desync exists, but prevents silent search failures.

---

## Acceptance Criteria

- `(mem/hnsw-health s)` → `{:total N :indexed N :missing 0}` for both roots
- `search_plans("View ↔ Edit Scroll Sync")` finds `complete/view-edit-scroll-sync/CONTEXT.md`
- Root cause documented: confirmed which code path / timing window created the desyncs
- No regression in `make test` for `clj-llm-memory`

---

## Open Questions

1. Why does `sp/search-vec` from the REPL return fewer results than `hnsw-health`?
   Is `vec-neighbors {:top N}` truly approximate (graph traversal only reaches N closest)?
   Or is there a protocol dispatch issue?

2. Was the original upsert desync bug already fixed in Datalevin 0.10.7+? The simulated
   upsert test showed no desync, which contradicts the original bug report. If Datalevin
   fixed it, the retract-then-transact workaround is still safe (just unnecessary extra work).
