---
created: 2026-03-25
related: datalevin-migration, winze-rename
tags: resilience, backup, recovery, datalevin, datahike
---

# Database Resilience — Plan

## Overview

Add periodic database backup and corruption recovery to the Winze Plan Server. Three-tier recovery: restore from backup → rebuild from source → server starts regardless.

## Step 1: Backup Infrastructure (`clj-llm-memory`)

**File**: `src/llm_memory/store/backup.clj` (new)

Create a `backup` namespace with pure functions for snapshot and restore operations:

- `snapshot!` — takes a db-path and backup-dir, zips the database directory to `backup-dir/<timestamp>.zip`. Returns the archive path.
- `restore!` — takes a backup archive path and a target db-path, deletes the target directory, unzips the archive into it. Returns true on success.
- `list-backups` — lists available backups in backup-dir, sorted newest-first. Returns `[{:path ... :timestamp ... :size-bytes ...}]`.
- `prune-backups!` — keeps the N most recent backups, deletes the rest. Returns count of deleted archives.

The backup directory defaults to `~/.local/share/winze/backups/`.

**Design notes**:
- Use `java.util.zip.ZipOutputStream` / `ZipInputStream` — no external dependencies.
- Timestamp format: `yyyy-MM-dd'T'HH-mm-ss` (filesystem-safe ISO 8601).
- `snapshot!` should verify the zip is readable before returning (catch corrupt-zip-on-write).

## Step 2: Store Gate (`clj-llm-memory`)

**File**: `src/llm_memory/store/datalevin.clj` (modify)

Add a read-write lock (or `CountDownLatch`-style gate) to the `DatalevinPlanStore` so that query operations block while the store is disconnected for backup, rather than failing with NPE on `@conn`.

Approach:
- Add a `java.util.concurrent.locks.ReentrantReadWriteLock` field to the record.
- Query/transact operations acquire the **read lock** (shared — many concurrent readers).
- Backup cycle acquires the **write lock** (exclusive — blocks all readers).
- Wrap `@conn` access in a helper that acquires the read lock and throws a clear error if conn is nil (store not yet opened, vs. temporarily closed for backup).

This keeps zero overhead for the normal path — `ReentrantReadWriteLock` is uncontended-fast.

## Step 3: Backup Scheduler (`winze-server`)

**File**: `src/llm_memory/server/main.clj` (modify)

Add a `ScheduledExecutorService` that runs the backup cycle at a configurable interval (default: 6 hours). The backup cycle:

1. Acquire the store's **write lock** (blocks new queries; in-flight queries complete first).
2. Stop all file watchers (`watcher/stop-all!`) — prevents index mutations during backup.
3. Disconnect the store (`store/disconnect!`).
4. Call `backup/snapshot!` to zip the database.
5. Call `backup/prune-backups!` to keep the last 4 backups (24 hours of coverage at 6h interval).
6. Reconnect the store (`store/connect!`).
7. Restart file watchers (`reconcile-and-watch!`).
8. Release the write lock.

The entire cycle should complete in < 2 seconds for typical data sizes (few MB).

**Configuration** (environment variables):
- `WINZE_BACKUP_INTERVAL_HOURS` — backup interval in hours (default: 6, 0 = disabled)
- `WINZE_BACKUP_RETENTION` — number of backups to keep (default: 4)

Wire the scheduler into `start!` and ensure the shutdown hook calls `.shutdownNow` on the executor.

## Step 4: Startup Recovery (`winze-server`)

**File**: `src/llm_memory/server/main.clj` (modify)

Replace the bare `mem/open-store` call in `start!` with a resilient open sequence:

```
try:
  open-store (normal path)
catch:
  log corruption error
  for each backup in list-backups (newest first):
    try:
      restore! backup → db-path
      open-store
      log "restored from backup <timestamp>"
      return store
    catch:
      log "backup <timestamp> also corrupt, trying next"
      continue
  ;; all backups failed or none exist
  delete db-path
  open-store (fresh empty database)
  log "rebuilt from scratch — reconcile will repopulate"
```

After the store opens (by any path), `reconcile-and-watch!` runs as usual — this is what repopulates a fresh database from source files.

## Step 5: Protocol Extension (`clj-llm-memory`)

**File**: `src/llm_memory/store/protocol.clj` (modify)

Add optional backup-related methods to the `PlanStore` protocol (with default no-op implementations so the Datahike stub doesn't break):

- `(backup-capable? [this])` — returns true if the store supports backup/restore
- `(snapshot-path [this])` — returns the database directory path (for the backup module to zip)

Alternatively, these can stay outside the protocol since backup operates on the filesystem path, not on the store abstraction. Evaluate during implementation — if `DatalevinPlanStore` already exposes `.path`, a separate protocol may be unnecessary.

## Step 6: Tests

**File**: `src/llm_memory/store/backup.clj` (RCF inline tests)

- `snapshot!` creates a valid zip containing the database files
- `restore!` from a snapshot produces a database that `d/get-conn` can open
- `prune-backups!` keeps exactly N newest, deletes the rest
- Round-trip: seed data → snapshot → corrupt db → restore → data intact
- Startup recovery: corrupt db + valid backup → server starts with data
- Startup recovery: corrupt db + no backup → server starts with empty db

## Step 7: Logging & Observability

Add structured log messages at each stage:
- `"backup: starting snapshot"` / `"backup: snapshot complete (N bytes, Nms)"`
- `"backup: pruned M old backups, keeping N"`
- `"startup: database corrupt — attempting recovery from backup"`
- `"startup: restored from backup <timestamp>"`
- `"startup: all backups failed — rebuilding from source"`

These go to the existing `plan-server.log` via `clojure.tools.logging`.

## Implementation Order

Steps 1 → 6 can be done incrementally, each independently testable:

1. **Step 1** (backup.clj) — pure functions, fully testable in isolation
2. **Step 4** (startup recovery) — immediately useful even without scheduled backups (manual `snapshot!` from REPL)
3. **Step 2** (store gate) — required before step 3
4. **Step 3** (scheduler) — ties it all together
5. **Step 5** (protocol) — evaluate necessity during implementation
6. **Step 6 + 7** (tests, logging) — throughout

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Backup zips a corrupt database | Rotation keeps 4 backups; oldest is likely clean. Startup tries each backup newest-first. |
| Write lock held too long | Close/zip/reopen is < 2s for few-MB databases. Log timing to detect drift. |
| Watcher events lost during backup window | Reconcile runs after reopen, catching any changes made during the window. |
| ScheduledExecutorService thread leak | Shutdown hook calls `.shutdownNow`. `stop!` function also shuts it down. |
| Backup dir fills disk | `prune-backups!` enforced after every snapshot. Each backup is ~few MB. |

## Future Considerations

- When Datahike replaces Datalevin, the same backup infrastructure applies — the zip target changes from `.datalevin/` to the Datahike store directory.
- Backup frequency may need tuning once we understand how much temporal history accumulates per hour.
- Could add a `backup!` MCP tool for on-demand snapshots from Claude Code.
