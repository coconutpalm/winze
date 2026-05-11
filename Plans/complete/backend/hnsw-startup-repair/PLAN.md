---
created: 2026-03-30
status: complete
related: [CONTEXT.md, complete/hnsw-redesync/CONTEXT.md]
tags: [datalevin, hnsw, vector-search, winze, startup]
---

# HNSW Startup Repair — Plan

## Step 1 — Add `hnsw-desynced-files` to `llm-memory.core` ✓

Added `hnsw-desynced-files` at `core.clj:457-490`. Returns
`[{:root/uri :file/path :root/plans-dir :missing-chunks}]` or `[]`.

Algorithm: set-difference between all KV chunk eids and HNSW-reachable eids
(via `search-vec {:top 99999}`), joined with `:chunk/file` → `:file/path` and
`:file/root` → `:root/uri` + `:root/plans-dir` via a single Datalog query.

Also added `clojure.set` to the ns requires.

## Step 2 — Add `repair-hnsw-desync!` to `main.clj` ✓

Added `repair-hnsw-desync!` at `main.clj:277-302`.

1. Calls `hnsw-desynced-files` — returns immediately (no log) when healthy
2. Logs total missing chunks and affected file count
3. Logs all reconcile summaries for troubleshooting
4. Re-indexes each affected file via `idx/index-file!` (targeted, not full root)
5. Verifies repair via second `hnsw-desynced-files` call; warns if still desynced

## Step 3 — Wire into `start!` ✓

- Modified `reconcile-and-watch!` to return `[{:root name :summary map}]`
  (was void). Uses `reduce` instead of `doseq`.
- `start!` passes reconcile summaries to `repair-hnsw-desync!`.
- Removed the earlier `index-root!`-based repair.

## Step 4 — Test via REPL ✓

1. Verified `hnsw-desynced-files` returns `[]` on healthy store
2. Introduced desync: `[:db/retract eid :chunk/vec]` on a known chunk
3. Verified `hnsw-desynced-files` correctly identified the file + chunk count
4. Ran `repair-hnsw-desync!` with mock reconcile summaries — repair succeeded
5. Post-repair: `hnsw-health` → `{:missing 0}`, search found repaired chunk
6. Confirmed all log messages appeared in `plan-server.log`

**Test note**: Simulating the original upsert bug (retract vec then re-add via
`:chunk/id` upsert) did NOT create a desync — Datalevin may have fixed the
underlying HNSW upsert issue. Retracting `:chunk/vec` alone reliably creates
a detectable desync for testing.

## Acceptance Criteria — All Met ✓

- `hnsw-desynced-files` returns `[]` on a healthy store ✓
- `hnsw-desynced-files` correctly identifies files with missing chunks ✓
- `repair-hnsw-desync!` re-indexes only affected files (not entire roots) ✓
- Reconcile summaries are logged when a desync is detected ✓
- Post-repair `hnsw-health` shows `:missing 0` ✓
- Server compiles cleanly (`load-file`) ✓
