---
created: 2026-03-30
status: complete
related: [CONTEXT.md, complete/hnsw-redesync/CONTEXT.md, complete/hnsw-desync/CONTEXT.md]
tags: [datalevin, hnsw, vector-search, winze, startup]
---

# HNSW Startup Repair — Context

## Goal

Add a targeted HNSW repair step to server startup that detects and fixes
desynced chunks without reindexing entire roots. Minimal database deltas
are critical because the project is moving toward Datahike (bitemporal) where
preserving history matters.

## Background

The HNSW desync investigation (HNSW-REDESYNC) confirmed that 34 chunks were
missing from the HNSW index because files were indexed by a pre-fix jar. The
fix (retract-then-transact in `index-file!`) is now deployed, but legacy
desyncs may persist across server restarts unless repaired.

## Design Constraints

1. **Minimal blast radius** — only re-index files with missing chunks, not
   entire roots. This keeps database deltas small for future Datahike migration.
2. **Run after `reconcile-and-watch!`** — reconcile may itself introduce desyncs
   if a new bug exists, so detection must happen after reconcile.
3. **Log reconcile summary on desync** — if a desync is detected post-reconcile,
   log what reconcile did so we can troubleshoot whether reconcile is the cause.
4. **Every startup** — the check runs unconditionally, not just once.

## Algorithm

### Detection

1. Call `hnsw-health` after `reconcile-and-watch!`.
2. If `:missing` > 0, identify affected files:
   - Get all chunk entity IDs from KV: `[:find [?e ...] :where [?e :chunk/id _]]`
   - Get HNSW-reachable entity IDs from `search-vec` with `{:top 99999}`
   - Set-difference → missing eids
   - Join missing eids → `:chunk/file` → `:file/path` + `:file/root` → `:root/uri`

### Targeted Repair

For each affected `{root-uri, file-path}` pair:
- Compute absolute path: `(plans-dir-path root-uri plans-dir) + "/" + rel-path`
- Call `idx/index-file!` (which does retract-then-transact internally)
- This touches only the affected file's chunks, not the entire root

### Post-Repair Verification

Call `hnsw-health` again. If still > 0, log a warning (possible deeper bug).

## Key Functions

| Function | Namespace | Role |
|----------|-----------|------|
| `hnsw-health` | `llm-memory.core` | Count total vs HNSW-indexed chunks |
| `hnsw-desynced-files` | `llm-memory.core` | **NEW** — identify files with missing HNSW chunks |
| `search-vec` | `llm-memory.store.protocol` | HNSW nearest-neighbor query |
| `query` | `llm-memory.store.protocol` | Datalog query for chunk/file eids |
| `index-file!` | `llm-memory.index` | Retract-then-transact a single file |
| `repair-hnsw-desync!` | `llm-memory.server.main` | **NEW** — targeted repair at startup |

## Files Modified

| File | Change |
|------|--------|
| `winze-server/src/llm_memory/server/main.clj` | Added `repair-hnsw-desync!` (L277-302); modified `reconcile-and-watch!` to return summaries; wired into `start!` |
| `clj-llm-memory/src/llm_memory/core.clj` | Added `hnsw-desynced-files` (L457-490); added `clojure.set` require |

## Testing Observations

- Simulating the original upsert bug (retract `:chunk/vec` then re-add via
  `:chunk/id` upsert) did NOT create a desync in the current Datalevin version.
  The HNSW upsert issue may be fixed upstream. The retract-then-transact
  workaround in `index-file!` remains safe (just unnecessary extra work).
- Retracting `:chunk/vec` alone reliably creates a detectable desync for testing.
- `sp/search-vec` from the REPL returned correct counts in this session (no
  discrepancy with `hnsw-health`), contradicting earlier observations.
