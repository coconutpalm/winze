---
created: 2026-04-14
tags: wiki, registry, backup, resilience, index
---

# Wiki Registry Consolidation — Context

## Problem

The `.wiki-registry.edn` sidecar files currently live inside each root's Plans
directory (e.g. `Plans/.wiki-registry.edn`). This placement has three problems:

1. **Brittle across renames**: If a Plans directory is moved or renamed, the
   registry file may not move with it automatically. The registry needs to stay
   permanently coupled to the Datalevin store, not to the content directory.

2. **Workspace clutter**: The registry is database metadata — it has no meaning
   to a human reader. Storing it in the Plans directory mixes infrastructure
   artifacts with planning documents.

3. **Not backed up**: The existing `backup.clj` `snapshot!` function only zips
   the `.datalevin/` database directory. The registry is not included. If the
   database is restored from backup, the registry may be stale or missing,
   causing UUIDs for renamed headings to be regenerated (breaking `wiki:uuid`
   links in markdown files).

Two registry files currently exist on disk:
- `Plans/.wiki-registry.edn` (~73 KB, _finance root)
- `winze/Plans/.wiki-registry.edn` (~53 KB, winze root)

## Why the Registry Matters

Wiki UUIDs are *deterministic* by design: `UUID/nameUUIDFromBytes(file-id + "#" + slug)`.
For an unchanged heading, you can always recompute the UUID. The registry
is needed to handle **heading renames**:

- When a heading is renamed, the store detects it (embedding similarity match),
  keeps the same UUID, and updates `:wiki/slug` and `:wiki/text` in Datalevin.
- The UUID in every markdown file referencing that heading stays the same.
- On store rebuild, if we recompute from scratch (current slug), we'd get a
  **different** UUID — breaking all existing `[text](wiki:uuid)` links.
- The registry captures the canonical UUID→info mapping including these
  "deviated" entries (where uuid ≠ `wiki-uuid(file-id, current-slug)`).

So the registry is not just a cache — it's the **authoritative source of truth**
for any heading whose UUID has drifted from deterministic due to rename history.

## Current Implementation

**Write path** (`index.clj:save-wiki-registry!`):
- After `index-file!` transacts wiki entities, a future calls `save-wiki-registry!`
- Queries all `:wiki/*` entities for the root, writes as a flat EDN map:
  ```clojure
  {"uuid-str" {:file-id "root::path.md" :slug "current-slug" :text "..." :level 2}
   ...}
  ```
- Location computed by `wiki-registry-path`:
  `<root-dir>/<plans-dir>/.wiki-registry.edn`

**Read path** (`index.clj:load-wiki-registry`):
- Called during `index-root!` (full rebuild) to seed UUIDs from the registry
  before fresh indexing, preserving link stability
- Returns the flat `uuid→info` map or nil if file missing/unreadable

**Backup** (`backup.clj:snapshot!`):
- Zips `db-path` (`.datalevin/`) contents only
- Registry is **not included** in any backup

**Restore** (`backup.clj:restore!`):
- Unzips a backup archive into `db-path`
- No awareness of the registry file

## Design Decisions

### New registry location: `~/.local/share/winze/wiki-registry.edn`

The registry belongs next to the Datalevin store, not next to the markdown
files. The data-dir (`~/.local/share/winze/`) is the natural home — it
contains `.datalevin/`, `.nrepl-port`, `.pid`, and `backups/`. Adding
`wiki-registry.edn` here makes the data-dir the single source of truth for
all persistent server state.

The dotfile prefix (`.wiki-registry.edn`) was used in the Plans directory to
avoid cluttering file explorers and git status. Inside `~/.local/share/winze/`
that concern doesn't apply — the directory is already hidden infrastructure.

### New format: nested by root-uri

The single central file covers all registered roots:

```clojure
{"file:///path/to/_finance" {"uuid-1" {:file-id "_finance::path.md" :slug "..." :text "..." :level 2}
                              "uuid-2" {...}}
 "file:///path/to/winze"   {"uuid-3" {:file-id "winze::path.md" :slug "..." ...}}}
```

Keying by root-uri makes it safe to update a single root's slice without
touching entries from other roots. Writes are atomic (a single `spit` of the
full merged map).

### Registry embedded in backup ZIPs

Modify `snapshot!` to also include the current `wiki-registry.edn` in the
backup ZIP at the fixed entry name `wiki-registry.edn`. Its `.edn` extension
already distinguishes it from LMDB's `.mdb` files. This makes each backup
self-contained — you can recover both the database and registry from a single
archive.

`restore!` (DB restore only) skips `wiki-registry.edn` entries, since the
registry is managed independently.

### Independent fallback chains

The database and registry have different failure characteristics:

| | Database | Registry |
|--|---------|---------|
| Storage | LMDB memory-mapped files | Plain EDN text file |
| Corruption risk | Yes (crash during mmap flush) | Very low (plain text, spit is atomic) |
| Loss risk | Backup handles this | Could be missing or stale |

Because of this asymmetry, the two recovery paths are **independent**:

**Registry recovery** (at startup, before store open):
1. Try `~/.local/share/winze/wiki-registry.edn` — if readable, use it.
2. Otherwise, iterate backup ZIPs newest-first:
   - Extract `wiki-registry.edn` from each archive
   - Use the first readable one
3. If no registry found anywhere, proceed with deterministic UUID generation
   (fresh store; existing `wiki:uuid` links may break for renamed headings only).

**DB recovery** (unchanged, existing behavior):
1. Try opening current `.datalevin/` — if OK, proceed normally.
2. Iterate backup ZIPs newest-first:
   - Restore the DB from backup, try opening
3. If all backups fail, delete `.datalevin/` and start fresh (rebuild from source).

These chains run independently. The most likely failure scenario — corrupt DB
with intact registry — is handled correctly: the current registry is used while
the DB is restored from backup.

### Migration path

No data migration step is needed. Because UUIDs are deterministic for unchanged
headings, the central registry will be populated correctly after the first
watcher-triggered `index-file!` for each root. Existing `wiki:uuid` links will
continue to work because the UUIDs in the markdown files match what the store
computes.

The old per-root registry files (`Plans/.wiki-registry.edn`) should be removed
from git (`git rm`) after the central registry is populated. They are no longer
read by the new code.

**Important edge case**: Any heading that was renamed (UUID deviated from
deterministic) will be temporarily non-resolvable after migration if the central
registry is populated from a fresh index rather than the old per-root file. To
avoid this, the migration step should explicitly copy the old per-root files into
the central registry format on first startup rather than discarding them.

## Key Files

| File | Change |
|------|--------|
| `clj-llm-memory/src/llm_memory/index.clj` | New `wiki-registry-path`, `save-wiki-registry!`, `load-wiki-registry` (central format) |
| `clj-llm-memory/src/llm_memory/store/backup.clj` | `snapshot!` includes registry; `restore!` skips it; new `extract-registry-from-backup` |
| `winze-server/src/llm_memory/server/main.clj` | `load-best-registry!`, independent DB + registry recovery; thread `data-dir` |
| `Plans/.wiki-registry.edn` | Delete from git after migration |
| `winze/Plans/.wiki-registry.edn` | Delete from git after migration |
