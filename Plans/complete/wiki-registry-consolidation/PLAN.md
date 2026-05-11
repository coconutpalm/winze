---
created: 2026-04-14
tags: wiki, registry, backup, resilience, index
---

# Wiki Registry Consolidation — Plan

## Overview

Move `.wiki-registry.edn` from per-root Plans directories into a single central
file at `~/.local/share/winze/wiki-registry.edn`. Include it in backup ZIPs.
Add independent registry recovery on startup. Delete the old per-root files.

Companion: [WIKI-REGISTRY-CONSOLIDATION-CONTEXT.md](WIKI-REGISTRY-CONSOLIDATION-CONTEXT.md)

## Step 1 — Thread `data-dir` into `save-wiki-registry!` and `load-wiki-registry` (`index.clj`)

The registry path must now be derived from the server's data-dir, not from the
root's plans-dir. The data-dir is available in `main.clj` as the parent of
`.datalevin/`.

**1a** — Change `wiki-registry-path` to take `data-dir` instead of
`root-uri` + `plans-dir`:

```clojure
(defn wiki-registry-path
  "Return the absolute path to the central wiki registry file."
  [data-dir]
  (str data-dir "/wiki-registry.edn"))
```

**1b** — Change the registry format in `save-wiki-registry!` to the nested
`{root-uri {uuid→info}}` map:

```clojure
(defn save-wiki-registry!
  "Merge and write the wiki UUID map for one root into the central registry.
  data-dir is the server's data directory (parent of .datalevin/)."
  [store root-uri data-dir]
  (when-let [root (resolve-root store root-uri)]
    (let [path        (wiki-registry-path data-dir)
          existing    (or (load-central-registry data-dir) {})
          root-slice  (query-all-wikis-for-root store root-uri)
          merged      (assoc existing root-uri root-slice)]
      (spit path (pr-str merged)))))
```

Writes are serialized by the calling future — since only one future runs per
`index-file!` call and the spit is atomic, no locking is needed.

**1c** — Change `load-wiki-registry` to extract the per-root slice from the
central file:

```clojure
(defn load-wiki-registry
  "Load the wiki UUID→info map for a single root from the central registry.
  Returns a flat uuid→info map or nil if missing/unreadable."
  [root-uri data-dir]
  (when-let [central (load-central-registry data-dir)]
    (get central root-uri)))

(defn- load-central-registry
  "Load the full central registry EDN file, or nil if missing/unreadable."
  [data-dir]
  (let [f (io/file (wiki-registry-path data-dir))]
    (when (.exists f)
      (try (read-string (slurp f)) (catch Throwable _ nil)))))
```

**1d** — Update all call sites to pass `data-dir`. In `index.clj`:
- `index-root!` calls `load-wiki-registry` — add `data-dir` parameter
- The future in `index-file!` calls `save-wiki-registry!` — add `data-dir`

In `core.clj`, the public API (`index-root!`, `reconcile-and-watch!`) will
need `data-dir` threaded through. Pass it alongside `root-uri`.

**Verify**: REPL — call `index-file!` on a file, confirm
`~/.local/share/winze/wiki-registry.edn` is written with nested structure.
Call `load-wiki-registry` for both roots, confirm correct slices returned.

---

## Step 2 — Update `snapshot!` to embed the registry (`backup.clj`)

`snapshot!` currently zips the contents of `db-path` only. Extend it to also
include the registry file when present.

**2a** — Add an optional `registry-path` parameter with a nil default:

```clojure
(defn snapshot!
  "Zip the database directory to a timestamped archive.
  If registry-path is provided and the file exists, include it in the archive
  as 'wiki-registry.edn'.
  Returns the absolute path of the created archive (String)."
  ([db-path backup-dir]
   (snapshot! db-path backup-dir nil))
  ([db-path backup-dir registry-path]
   (.mkdirs (io/file backup-dir))
   (let [archive (io/file backup-dir (str backup-prefix (timestamp-str) backup-suffix))]
     (with-open [fos (FileOutputStream. ^File archive)
                 zos (ZipOutputStream. fos)]
       (add-dir-to-zip! zos (io/file db-path) (io/file db-path))
       (when-let [rf (and registry-path (io/file registry-path))]
         (when (.exists rf)
           (.putNextEntry zos (ZipEntry. "wiki-registry.edn"))
           (io/copy rf zos)
           (.closeEntry zos))))
     (str archive))))
```

Backward-compatible: callers that omit `registry-path` get the old behavior.

**2b** — Update `restore!` to skip `wiki-registry.edn` entries (DB restore
only; registry is handled separately):

```clojure
(defn restore!
  "Unzip backup-file to db-path, replacing any existing database.
  Skips 'wiki-registry.edn' entries (managed independently).
  Returns db-path (String)."
  [^File backup-file db-path]
  (delete-dir! db-path)
  (.mkdirs (io/file db-path))
  (with-open [fis (FileInputStream. backup-file)
              zis (ZipInputStream. fis)]
    (loop []
      (when-let [entry (.getNextEntry zis)]
        (when-not (= "wiki-registry.edn" (.getName entry))
          (let [target (io/file db-path (.getName entry))]
            (.mkdirs (.getParentFile target))
            (with-open [fos (FileOutputStream. ^File target)]
              (io/copy zis fos))))
        (.closeEntry zis)
        (recur))))
  db-path)
```

**2c** — Add `extract-registry-from-backup`:

```clojure
(defn extract-registry-from-backup
  "Read and parse the 'wiki-registry.edn' entry from a backup ZIP.
  Returns the registry map or nil if the entry is absent or unreadable."
  [^File backup-file]
  (try
    (with-open [fis (FileInputStream. backup-file)
                zis (ZipInputStream. fis)]
      (loop []
        (when-let [entry (.getNextEntry zis)]
          (if (= "wiki-registry.edn" (.getName entry))
            (read-string (slurp zis))
            (do (.closeEntry zis) (recur))))))
    (catch Throwable _ nil)))
```

**Verify**: REPL — snapshot a DB with a registry file, list the ZIP contents,
confirm `wiki-registry.edn` is present. Call `extract-registry-from-backup`,
confirm it returns the correct map. Call `restore!`, confirm only DB files
extracted (no `wiki-registry.edn` in `db-path`).

---

## Step 3 — Update the backup scheduler to pass `registry-path` (`main.clj`)

In the backup cycle (Step 3 of db-resilience, implemented in `main.clj`):

```clojure
;; Before: (backup/snapshot! db-path backup-dir)
;; After:
(backup/snapshot! db-path backup-dir (backup/wiki-registry-path data-dir))
```

The `wiki-registry-path` helper from `index.clj` (or duplicated as a
1-liner in `backup.clj`) provides the standard path.

---

## Step 4 — Add `load-best-registry!` to startup recovery (`main.clj`)

Add a function that implements the independent registry fallback chain:

```clojure
(defn- load-best-registry
  "Find the best available wiki registry.
  Tries the current central file first, then backup ZIPs newest-first.
  Returns the registry map or nil if none found."
  [data-dir backup-dir]
  (let [current-path (index/wiki-registry-path data-dir)
        current-file (io/file current-path)]
    (or
     ;; 1. Current central registry (most up-to-date)
     (when (.exists current-file)
       (try (read-string (slurp current-file)) (catch Throwable _ nil)))
     ;; 2. Most recent backup(s) that contain the registry
     (first (keep backup/extract-registry-from-backup
                  (backup/list-backups backup-dir))))))
```

**Integrate into `start!`**: Call `load-best-registry` before `open-store`.
Pass the loaded registry map into `index-root!` to seed UUIDs during initial
reconcile. The registry passes through to `load-wiki-registry` at the root
level.

The DB recovery loop (existing from db-resilience) runs independently.

**Verify**: REPL — rename a heading in a test file, verify UUID preserved.
Delete `~/.local/share/winze/wiki-registry.edn`, restart server, confirm
the registry from the most recent backup is used. Confirm the wiki link still
resolves to the renamed heading.

---

## Step 5 — Migration: copy old per-root registries into central format

On first startup after deployment, if the central registry does not yet exist
but old per-root registries do, merge them in.

Add to `start!` (after `load-best-registry`, before `open-store`):

```clojure
(defn- migrate-per-root-registries!
  "One-time migration: merge old per-root .wiki-registry.edn files into the
  central registry. No-op if the central registry already exists."
  [data-dir registered-roots]
  (let [central-path (index/wiki-registry-path data-dir)
        central-file (io/file central-path)]
    (when-not (.exists central-file)
      (let [merged (into {}
                         (keep (fn [{:keys [root-uri plans-dir]}]
                                 (let [old-path (str (str/replace root-uri #"^file://" "")
                                                     "/" plans-dir "/.wiki-registry.edn")
                                       old-file (io/file old-path)]
                                   (when (.exists old-file)
                                     (when-let [m (try (read-string (slurp old-file))
                                                       (catch Throwable _ nil))]
                                       [root-uri m])))))
                         registered-roots)]
        (when (seq merged)
          (spit central-path (pr-str merged))
          (log/info "Migrated" (count merged) "per-root wiki registries to central registry"))))))
```

The registered roots list is available from the store after `open-store` runs.
Run migration after the first successful `open-store` but before `reconcile-and-watch!`.

After confirming the central registry is populated correctly, remove the old
per-root files from git:

```bash
cd /path/to/_finance && git rm Plans/.wiki-registry.edn
cd /path/to/_finance/winze && git rm Plans/.wiki-registry.edn
```

---

## Step 6 — Tests

**`backup.clj` (RCF inline)**:
- `snapshot!` with registry-path: confirm `wiki-registry.edn` is in ZIP
- `snapshot!` without registry-path: confirm `wiki-registry.edn` is absent
- `restore!` from new-format ZIP: DB files extracted, `wiki-registry.edn` absent in db-path
- `extract-registry-from-backup` on new-format ZIP: returns registry map
- `extract-registry-from-backup` on old-format ZIP (no registry entry): returns nil
- Round-trip: seed data → snapshot(+registry) → corrupt both → restore DB → extract registry → data intact

**`index.clj` (RCF inline)**:
- `save-wiki-registry!` with two roots: confirm nested structure in central file
- `load-wiki-registry` extracts per-root slice correctly
- Multiple calls to `save-wiki-registry!` for same root: merges, doesn't duplicate

**`main.clj` (integration, REPL-based)**:
- `load-best-registry` with current file: returns map
- `load-best-registry` with missing file, valid backup: returns backup's registry
- `load-best-registry` with missing file, no backup: returns nil

---

## Step 7 — Logging

Add log messages:

```
"wiki-registry: migrated N per-root registries to central registry"
"wiki-registry: loaded from current central file (N roots, M entries)"
"wiki-registry: current registry missing/unreadable, trying backups"
"wiki-registry: loaded from backup <timestamp> (N roots, M entries)"
"wiki-registry: no registry found — deterministic UUIDs will be used"
```

---

## Implementation Order

1. **Step 1** (index.clj path + format change) — prerequisite for all others
2. **Step 2** (backup.clj snapshot/restore/extract) — independently testable
3. **Step 5** (migration) — run once on deploy; must come before old file deletion
4. **Step 3** (scheduler) — minor change, depends on Step 1+2
5. **Step 4** (startup recovery) — ties it together; depends on Step 1+2
6. **Step 6+7** (tests, logging) — throughout

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Central registry write race (two roots updated concurrently) | Serialization: futures from `index-file!` are independent but spit is sequential at OS level for small files. For safety, add a namespace-level `atom` to serialize writes: each `save-wiki-registry!` `swap!` with a write lock. |
| Migration reads stale per-root file | Migration reads the file at startup before any watcher fires. The file should reflect the last-run state. Acceptable — any deviations resolve after first full reconcile. |
| Backup ZIP grows slightly | Registry is ~126KB total for two roots. Negligible vs. typical LMDB sizes. |
| Old per-root files remain in git after migration | Clean up separately with `git rm` after confirming central registry is healthy. Not urgent — the new code ignores them. |
