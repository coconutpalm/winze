---
group: hnsw-desync
type: context
related: search-improvement
tags: [datalevin, hnsw, vector-search, watcher, bug]
---

# HNSW Index Desync on File Modification — Context

## Problem

When the filesystem watcher detects a file modification and re-indexes it, some or all of the file's chunk vectors become **invisible to HNSW search** — despite the `:chunk/vec` attribute being correctly stored in the Datalevin entity. The chunks exist, their vectors exist, but `vec-neighbors` cannot find them.

This means any file edited after initial indexing silently drops out of search results.

## Root Cause

`index-file!` (in `llm_memory/index.clj:213`) uses **upsert semantics** via `:db.unique/identity` on `:chunk/id`. When a file is modified:

1. Chunks whose `:chunk/id` is unchanged (same slug) are **upserted** — Datalevin updates the entity's attributes in place.
2. Chunks whose `:chunk/id` is new are **inserted** — Datalevin creates a new entity.
3. Chunks whose `:chunk/id` no longer exists are **retracted** via `retract!`.

The bug is in case (1): when Datalevin upserts an entity that has a `:db.type/vec` attribute, it updates the stored vector value but **does not re-insert it into the HNSW graph**. The old HNSW graph node is removed (or invalidated), and the new vector is never added back.

### Evidence

Diagnosed on 2026-03-25. After editing 3 files (SWT-UI-GUIDE.md, LIVE-SEARCH-CONTEXT.md, LIVE-SEARCH-PLAN.md) during a session:

- Total chunks with `:chunk/vec`: **1242**
- HNSW index entries (via `vec-neighbors` with zero vector, top 9999): **1209**
- Missing: **33 chunks** — exactly the chunks from the 3 modified files
- All 33 had valid `:chunk/vec` values in their entities
- None appeared in any `vec-neighbors` result regardless of query

### Workaround

Re-transacting the same `{:chunk/id ... :chunk/vec ...}` map for affected chunks forces Datalevin to re-add them to the HNSW index:

```clojure
;; /tmp/fix-hnsw.clj — run via (load-file "/tmp/fix-hnsw.clj") on the server nREPL
;; See Plans/dev/HNSW-DESYNC-PLAN.md for the diagnostic/fix script
```

After re-transacting 33 chunks, the HNSW index went from 1209 → 1242 and SWT-UI-GUIDE.md became searchable.

## Affected Code Path

```
File modified on disk
  → watcher.clj:handle-create-or-modify!
    → index.clj:index-file!
      → retract stale chunks (slug changed) — works correctly
      → transact file + chunk entities (upsert) — BUG: HNSW not updated for upserted vecs
        → datalevin.clj:transact! → d/transact!
```

### Key Files

| File | Role |
|------|------|
| `clj-llm-memory/src/llm_memory/index.clj:213-285` | `index-file!` — the indexing function |
| `clj-llm-memory/src/llm_memory/store/datalevin.clj:85-90` | `transact!` — thin wrapper around `d/transact!` |
| `clj-llm-memory/src/llm_memory/store/datalevin.clj:26-52` | Schema — `:chunk/vec {:db/valueType :db.type/vec}` |
| `clj-llm-memory/src/llm_memory/watcher.clj:139-156` | `handle-create-or-modify!` — watcher entry point |

## Scope

This affects **every file modification** detected by the watcher. New files (case 2 above) are fine because they create new entities with new eids. Only files that are re-indexed with existing chunk IDs are affected.

The `reconcile!` function also calls `index-file!` for modified files, so it has the same bug — reconcile cannot fix the problem, only reproduce it.

## Datalevin Version

- Datalevin: 0.10.7 (via `clj-llm-memory/deps.edn`)
- dtlvnative: 0.16.5
- Vector type: `:db.type/vec` with external embedder (not `:db/embedding`)
- HNSW config: `{:dimensions 384, :metric-type :cosine}`

## Possible Upstream Bug

This may be a Datalevin bug in how `:db.type/vec` handles upserts. The `:db.unique/identity` upsert path likely updates the KV store but skips the HNSW graph mutation. Worth checking:
- Datalevin issue tracker for known HNSW upsert bugs
- Whether `d/transact!` with an explicit `:db/id` (instead of identity upsert) behaves differently
- Whether retracting the entity first, then transacting fresh, avoids the issue

