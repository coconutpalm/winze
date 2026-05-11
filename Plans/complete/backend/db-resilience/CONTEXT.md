---
created: 2026-03-25
related: datalevin-migration, winze-rename
tags: resilience, backup, recovery, datalevin, datahike
---

# Database Resilience — Context

## Problem

The Winze Plan Server crashes on startup if its Datalevin database is corrupt. The `connect!` call in `datalevin.clj:62` invokes `d/get-conn` with no error handling — any LMDB corruption propagates as an uncaught exception, and the server fails to start. The MCP proxy's auto-start mechanism retries, but hits the same corruption each time, leaving the search system permanently unavailable until manual intervention (`rm -rf ~/.local/share/winze/.datalevin` + restart).

This is the only failure mode where the server cannot self-recover.

## Current State

### Database lifecycle (happy path)

1. `main.clj:start!` → `mem/open-store` → `datalevin/create-store` → `store/connect!`
2. `connect!` calls `(d/get-conn path schema {:vector-opts vector-opts})`
3. On shutdown, the JVM hook calls `mem/close-store!` → `store/disconnect!` → `(d/close c)`

### Error handling inventory

| Layer | Behavior on error | Code |
|-------|-------------------|------|
| `connect!` | **Unhandled** — exception propagates, server exits | `datalevin.clj:62-65` |
| `reconcile-and-watch!` | Caught per-root — logs warning, continues to next root | `main.clj:80-91` |
| `reconcile!` | Per-file try/catch — collects errors, returns summary | `index.clj:577-626` |
| Watcher actions | Caught — logs warning, watcher continues | `watcher.clj:83-90` |

The gap is exclusively at the store-open level.

### Data characteristics

The Datalevin store is a **derived index** — every entity (root, file, chunk + embedding) is computed from markdown files on disk. A full rebuild from source files takes ~30 seconds. However, the planned migration to **Datahike** changes this: Datahike is bitemporal, preserving full transaction history (every index operation creates an immutable snapshot). After migration, the store will contain **primary data** (temporal history) that cannot be reconstructed from source files alone.

### Corruption causes

Datalevin uses LMDB (memory-mapped B-tree). Corruption typically results from:
- Force-kill during mmap flush (`kill -9`, OOM killer, power loss)
- Filesystem-level issues (disk errors, macOS APFS bugs)
- Upgrading Datalevin versions with incompatible storage format changes

LMDB is copy-on-write and crash-safe by design, so corruption is rare but not impossible.

## Requirements

1. **Startup resilience**: The server must start even if the database is corrupt.
2. **History preservation**: Recovery should preserve transaction history when possible (preparing for Datahike migration).
3. **Graceful degradation**: Prefer restoring from backup (fast, preserves history) over full rebuild (slow, loses history).
4. **Zero runtime overhead**: Normal operation (no corruption) should not be slower.
5. **Blocking queries during backup**: Acceptable — backup is fast (zip of ~few MB).
6. **Backup rotation**: Keep multiple backups to avoid the "backed up corruption" problem.

## Design Decision: Periodic Backup + Restore-or-Rebuild

**Strategy**: Periodically close the database, zip it to an archive, then reopen. On startup corruption, try restoring from the most recent backup; if that fails, fall back to full rebuild from source files.

**Why not just rebuild?** Today, rebuilding from source is lossless because the store is a derived index. But the planned Datahike migration introduces bitemporal history that can't be reconstructed. Building backup infrastructure now means it works when history preservation matters.

**Why not continuous/incremental backup?** LMDB's memory-mapped architecture makes point-in-time snapshots the natural backup unit. The database is small (few MB) so full snapshots are fast. Incremental approaches would add complexity for negligible time savings.

**Why close-then-zip instead of file copy?** LMDB allows readers during writes, but copying the data files while the database is open risks capturing a partially-written page. Closing the connection guarantees a consistent snapshot. The close/zip/reopen cycle takes < 1 second for our data sizes.

## Key Files

| File | Role |
|------|------|
| `winze-server/src/llm_memory/server/main.clj` | Server lifecycle — `start!`, `stop!`, shutdown hook |
| `clj-llm-memory/src/llm_memory/store/datalevin.clj` | `connect!`, `disconnect!`, schema |
| `clj-llm-memory/src/llm_memory/store/protocol.clj` | `PlanStore` protocol (lifecycle + query) |
| `clj-llm-memory/src/llm_memory/core.clj` | `open-store`, `close-store!` public API |
| `clj-llm-memory/src/llm_memory/index.clj` | `reconcile!`, `index-root!` (full rebuild) |
| `clj-llm-memory/src/llm_memory/watcher.clj` | Filesystem watcher (must pause during backup) |

## Scope Boundaries

**In scope**: Backup scheduler, restore-on-corruption, rebuild fallback, backup rotation, query blocking during backup window.

**Out of scope**: Datahike migration itself, replication, remote/offsite backups, backup encryption.
