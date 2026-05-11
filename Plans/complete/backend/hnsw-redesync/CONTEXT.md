---
created: 2026-03-30
status: complete
related: [complete/hnsw-desync/CONTEXT.md, complete/hnsw-desync/PLAN.md]
tags: [datalevin, hnsw, vector-search, winze, plans-search]
---

# HNSW Re-Desync Investigation — Context

## Problem Statement

After the original `hnsw-desync` fix was shipped (retract-then-transact in `index-file!`),
the running Winze server still shows 34 chunks missing from the HNSW index
(`hnsw-health: {:total 1467, :indexed 1418→1433, :missing 49→34}`).

The immediate symptom: `search_plans("View ↔ Edit Scroll Sync")` returned no results even
though `list_plans` showed `complete/view-edit-scroll-sync/CONTEXT.md` and `PLAN.md`.
Those 15 chunks were re-indexed manually, reducing missing from 49 → 34. The remaining 34
are still unidentified.

## What We Know

### The deployed fix is correct

The retract-then-transact fix is present in the deployed jar
(`~/.local/share/winze/lib/winze-server.jar`):

```
grep "Retract ALL" → found at line 290 of jar-embedded index.clj
```

### Code paths tested — none reproduce HNSW desync

All tested paths use the correct API and do NOT create new desyncs:

| Code path | Result |
|-----------|--------|
| `rename-file!` alone | No desync ✓ |
| `reconcile!` with exact rename (same content-hash) | No desync ✓ |
| `reconcile!` with new file | No desync ✓ |
| Simulated pre-fix upsert of `:chunk/vec` | No desync observed (surprising) |

**Note**: The simulated upsert test did NOT reproduce the original desync bug. This is
unexpected. Either (a) Datalevin's behavior changed, or (b) the test conditions differed
from the original bug trigger.

### `search-vec` diagnostic was wrong

`DatalevinPlanStore/search-vec` returns `{:eid N :distance D}` maps — NOT maps with
`:chunk/id`. Earlier REPL diagnostics called `(map :chunk/id hnsw-results)` which mapped
over nil for every entry, producing a spurious "1393 missing" count. The only reliable
source of truth is `mem/hnsw-health` which correctly reports 34 missing.

### Affected files identified (Step 1 — DONE)

The diagnostic from Step 1 of the plan was executed correctly. Set-difference between
all chunk entity IDs (KV) and HNSW-reachable entity IDs (via `search-vec {:top 99999}`):

| File | Root | Missing chunks | File modified |
|------|------|---------------|---------------|
| `dev/STYLEDTEXT-EDITOR-PLAN.md` | winze | 13 | Mar 27 15:50 |
| `dev/CODE-BLOCK-LINE-BG-CONTEXT.md` | _finance | 6 | Mar 30 13:09 |
| `complete/search-keybindings/PLAN.md` | _finance | 6 | Mar 26 12:31 |
| `dev/CODE-BLOCK-LINE-BG-PLAN.md` | _finance | 4 | Mar 30 13:09 |
| `complete/search-keybindings/CONTEXT.md` | _finance | 4 | Mar 26 12:31 |
| `complete/winze-ctabfolder-ui/PLAN.md` | _finance | 1 | Mar 24 17:42 |

**Total: 34 missing chunks across 6 files, 2 roots.**

### `sp/search-vec` from REPL works correctly now

The earlier discrepancy where `sp/search-vec` returned far fewer results from the REPL
was NOT reproduced. In this session, `sp/search-vec` from the `user` namespace returned
exactly 1445 results (matching `hnsw-health`). The earlier observation may have been a
transient issue (lock contention, query vector quality, or connection state).

### Known HNSW health values

| Time | Total | Indexed | Missing |
|------|-------|---------|---------|
| Earlier session start | 1467 | 1418 | 49 |
| After manual re-index of view-edit-scroll-sync | 1467 | 1433 | 34 |
| This session (pre-repair) | 1479 | 1445 | 34 |
| **After targeted re-index (Step 2)** | **1479** | **1479** | **0** |

## Root Cause — CONFIRMED: H1

### H1 confirmed: Legacy desync from source-fix → jar-rebuild gap

**Timeline:**

| Event | Date |
|-------|------|
| Initial HNSW desync fix committed (`c4e6f0e`) | Mar 26 10:09 |
| Follow-up stale-node fix committed (`d83d4c4`) | Mar 27 12:48 |
| Jar rebuilt with both fixes (current) | Mar 30 13:20 |

All 6 affected files were modified between Mar 24 and Mar 30 13:09, while the running
server was still using a pre-fix jar. The old buggy `index-file!` (without retract-then-transact)
processed these files and created HNSW desyncs. The `reconcile!` on startup doesn't repair
HNSW desyncs because it only checks content hashes — if the file content hasn't changed,
reconcile skips it.

### H2 and H3 — not needed

- H2 (concurrent watcher operations): Not the cause. The watcher serializes via a
  single-thread scheduler, and no evidence of race conditions was found.
- H3 (non-deterministic `vec-neighbors`): Not confirmed. `search-vec {:top 99999}`
  returned the correct count in this session.

## Fix Applied

### Repair (immediate)

Re-indexed the 6 affected files via `idx/index-file!` with correct root URIs. HNSW health
went from `{:missing 34}` to `{:missing 0}`.

### Prevention (startup check)

Added HNSW health check to `winze-server/src/llm_memory/server/main.clj` `start!` function,
after `reconcile-and-watch!`. If any chunks are missing from the HNSW index, it runs
`idx/index-root!` for all roots to repair. This adds ~30s only when a desync exists.

## Files Involved

| File | Role |
|------|------|
| `winze/clj-llm-memory/src/llm_memory/index.clj` | `index-file!`, `rename-file!`, `reconcile!`, `retract-chunk-vecs!` |
| `winze/clj-llm-memory/src/llm_memory/core.clj` | `hnsw-health` |
| `winze/clj-llm-memory/src/llm_memory/store/datalevin.clj` | `DatalevinPlanStore/search-vec` (returns `{:eid :distance}`) |
| `winze/clj-llm-memory/src/llm_memory/watcher.clj` | Watcher event handlers; delete is immediate, create/modify debounced 500ms |
| `winze/winze-server/src/llm_memory/server/main.clj` | **Modified**: added HNSW repair on startup |
| `~/.local/share/winze/lib/winze-server.jar` | Deployed uberjar |
