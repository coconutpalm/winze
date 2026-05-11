(ns llm-memory.store.backup
  "Periodic database backup and corruption recovery.

  Provides snapshot/restore/prune operations for Datalevin store directories.
  All functions are pure filesystem operations — no store connections required.

  Backup archives are timestamped zip files:
    winze-backup-yyyyMMdd-HHmmss.zip

  Typical lifecycle (called by the scheduler in server/main.clj):
    1. snapshot!      — zip db-path to a timestamped archive
    2. prune-backups! — delete old archives beyond retention count
    3. restore!       — unzip an archive back to db-path (on startup corruption)"
  (:require [clojure.java.io :as io]
            [hyperfiddle.rcf :refer [tests]])
  (:import [java.io File FileInputStream FileOutputStream]
           [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]
           [java.util.zip ZipEntry ZipInputStream ZipOutputStream]))

(def ^:private backup-prefix "winze-backup-")
(def ^:private backup-suffix ".zip")
(def ^:private timestamp-fmt (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss"))

(defn- timestamp-str []
  (.format (LocalDateTime/now) timestamp-fmt))

;; ---------------------------------------------------------------------------
;; Directory utilities
;; ---------------------------------------------------------------------------

(defn delete-dir!
  "Recursively delete a directory and all its contents. No-op if it does not exist."
  [path]
  (let [root (io/file path)]
    (when (.exists root)
      ;; Delete files first (file-seq visits dirs too)
      (doseq [^File f (reverse (file-seq root))]
        (.delete f)))))

;; ---------------------------------------------------------------------------
;; Snapshot
;; ---------------------------------------------------------------------------

(defn- add-dir-to-zip!
  "Recursively add all files under `dir` to `zos`, using paths relative to `base`."
  [^ZipOutputStream zos ^File base ^File dir]
  (doseq [^File f (.listFiles dir)]
    (if (.isDirectory f)
      (add-dir-to-zip! zos base f)
      (let [rel  (str (.relativize (.toPath base) (.toPath f)))
            _    (.putNextEntry zos (ZipEntry. rel))]
        (io/copy f zos)
        (.closeEntry zos)))))

(defn snapshot!
  "Zip the database directory to a timestamped archive in backup-dir.
   Creates backup-dir if it does not exist.
   If registry-path is provided and the file exists, embeds it in the archive
   as 'wiki-registry.edn' (alongside the database files).
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

;; ---------------------------------------------------------------------------
;; Restore
;; ---------------------------------------------------------------------------

(defn restore!
  "Unzip backup-file to db-path, replacing any existing database.
   Skips 'wiki-registry.edn' entries — the registry is managed independently.
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

;; ---------------------------------------------------------------------------
;; List & Prune
;; ---------------------------------------------------------------------------

(defn list-backups
  "Return all backup archives in backup-dir sorted newest-first (by filename)."
  [backup-dir]
  (let [dir (io/file backup-dir)]
    (if (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(and (.isFile ^File %)
                         (.startsWith (.getName ^File %) backup-prefix)
                         (.endsWith   (.getName ^File %) backup-suffix)))
           (sort-by #(.getName ^File %) #(compare %2 %1)))
      [])))

(defn prune-backups!
  "Delete archives beyond the retention count, keeping the N most recent.
   Returns the number of archives deleted."
  [backup-dir retention]
  (let [to-delete (drop retention (list-backups backup-dir))]
    (doseq [^File f to-delete]
      (.delete f))
    (count to-delete)))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(tests
 "snapshot! creates a non-empty zip archive"
 (let [db-dir     (str "/tmp/winze-snap-test-" (System/nanoTime))
       backup-dir (str "/tmp/winze-snap-bkp-"  (System/nanoTime))]
   (.mkdirs (io/file db-dir))
   (spit (io/file db-dir "data.edn") "{:key :value}")
   (let [archive (snapshot! db-dir backup-dir)]
     (string? archive) := true
     (.exists (io/file archive)) := true
     (pos? (.length (io/file archive))) := true)
   (delete-dir! db-dir)
   (delete-dir! backup-dir))
 :rcf)

(tests
 "restore! recovers original contents"
 (let [db-dir     (str "/tmp/winze-restore-test-" (System/nanoTime))
       backup-dir (str "/tmp/winze-restore-bkp-"  (System/nanoTime))]
   (.mkdirs (io/file db-dir))
   (spit (io/file db-dir "data.edn") "{:key :value}")
   (let [archive (snapshot! db-dir backup-dir)]
     ;; Corrupt the database
     (spit (io/file db-dir "data.edn") "CORRUPTED")
     ;; Restore from backup
     (restore! (io/file archive) db-dir)
     (slurp (io/file db-dir "data.edn")) := "{:key :value}")
   (delete-dir! db-dir)
   (delete-dir! backup-dir))
 :rcf)

(tests
 "list-backups returns newest-first and ignores non-backup files"
 (let [backup-dir (str "/tmp/winze-list-test-" (System/nanoTime))]
   (.mkdirs (io/file backup-dir))
   (spit (io/file backup-dir "winze-backup-20260101-120000.zip") "a")
   (spit (io/file backup-dir "winze-backup-20260103-120000.zip") "c")
   (spit (io/file backup-dir "winze-backup-20260102-120000.zip") "b")
   (spit (io/file backup-dir "unrelated.txt") "x")
   (let [bs (list-backups backup-dir)]
     (count bs) := 3
     (.getName ^File (first bs))  := "winze-backup-20260103-120000.zip"
     (.getName ^File (last bs))   := "winze-backup-20260101-120000.zip")
   (delete-dir! backup-dir))
 :rcf)

(tests
 "prune-backups! keeps only N most recent"
 (let [backup-dir (str "/tmp/winze-prune-test-" (System/nanoTime))]
   (.mkdirs (io/file backup-dir))
   (doseq [d ["20260101" "20260102" "20260103" "20260104"]]
     (spit (io/file backup-dir (str "winze-backup-" d "-120000.zip")) d))
   (prune-backups! backup-dir 2) := 2
   (let [bs (list-backups backup-dir)]
     (count bs) := 2
     (.getName ^File (first bs)) := "winze-backup-20260104-120000.zip")
   (delete-dir! backup-dir))
 :rcf)

(tests
 "delete-dir! removes directory and all contents"
 (let [d (str "/tmp/winze-del-test-" (System/nanoTime))]
   (.mkdirs (io/file d "sub"))
   (spit (io/file d "sub" "f.txt") "hello")
   (delete-dir! d)
   (.exists (io/file d)) := false)
 :rcf)

(tests
 "snapshot! with registry-path embeds wiki-registry.edn in archive"
 (let [db-dir      (str "/tmp/winze-snap-reg-test-" (System/nanoTime))
       backup-dir  (str "/tmp/winze-snap-reg-bkp-"  (System/nanoTime))
       reg-path    (str "/tmp/winze-snap-reg-"       (System/nanoTime) ".edn")]
   (.mkdirs (io/file db-dir))
   (spit (io/file db-dir "data.edn") "{:key :value}")
   (spit reg-path (pr-str {"uuid-1" {:file-id "root::a.md" :slug "overview"}}))
   (let [archive (snapshot! db-dir backup-dir reg-path)
         entries (with-open [fis (java.io.FileInputStream. (io/file archive))
                             zis (java.util.zip.ZipInputStream. fis)]
                   (loop [names []]
                     (if-let [e (.getNextEntry zis)]
                       (do (.closeEntry zis) (recur (conj names (.getName e))))
                       names)))]
     (some #{"wiki-registry.edn"} entries) := "wiki-registry.edn")
   (delete-dir! db-dir)
   (delete-dir! backup-dir)
   (.delete (io/file reg-path)))
 :rcf)

(tests
 "snapshot! without registry-path does not embed wiki-registry.edn"
 (let [db-dir     (str "/tmp/winze-snap-noreg-test-" (System/nanoTime))
       backup-dir (str "/tmp/winze-snap-noreg-bkp-"  (System/nanoTime))]
   (.mkdirs (io/file db-dir))
   (spit (io/file db-dir "data.edn") "{:key :value}")
   (let [archive (snapshot! db-dir backup-dir)
         entries (with-open [fis (java.io.FileInputStream. (io/file archive))
                             zis (java.util.zip.ZipInputStream. fis)]
                   (loop [names []]
                     (if-let [e (.getNextEntry zis)]
                       (do (.closeEntry zis) (recur (conj names (.getName e))))
                       names)))]
     (some #{"wiki-registry.edn"} entries) := nil)
   (delete-dir! db-dir)
   (delete-dir! backup-dir))
 :rcf)

(tests
 "restore! skips wiki-registry.edn — only DB files land in db-path"
 (let [db-dir     (str "/tmp/winze-restore-noreg-test-" (System/nanoTime))
       backup-dir (str "/tmp/winze-restore-noreg-bkp-"  (System/nanoTime))
       reg-path   (str "/tmp/winze-restore-noreg-"      (System/nanoTime) ".edn")]
   (.mkdirs (io/file db-dir))
   (spit (io/file db-dir "data.edn") "{:key :value}")
   (spit reg-path (pr-str {"uuid-1" {:file-id "root::a.md" :slug "s"}}))
   (let [archive (snapshot! db-dir backup-dir reg-path)]
     (restore! (io/file archive) db-dir)
     ;; DB file must be restored
     (slurp (io/file db-dir "data.edn")) := "{:key :value}"
     ;; Registry must NOT land in db-path
     (.exists (io/file db-dir "wiki-registry.edn")) := false)
   (delete-dir! db-dir)
   (delete-dir! backup-dir)
   (.delete (io/file reg-path)))
 :rcf)

(tests
 "extract-registry-from-backup returns registry from new-format ZIP"
 (let [db-dir     (str "/tmp/winze-extract-test-" (System/nanoTime))
       backup-dir (str "/tmp/winze-extract-bkp-"  (System/nanoTime))
       reg-path   (str "/tmp/winze-extract-reg-"  (System/nanoTime) ".edn")
       registry   {"file:///proj" {"uuid-1" {:file-id "root::a.md" :slug "overview"
                                             :text "Overview" :level 2}}}]
   (.mkdirs (io/file db-dir))
   (spit (io/file db-dir "data.edn") "{}")
   (spit reg-path (pr-str registry))
   (let [archive (snapshot! db-dir backup-dir reg-path)]
     (extract-registry-from-backup (io/file archive)) := registry)
   (delete-dir! db-dir)
   (delete-dir! backup-dir)
   (.delete (io/file reg-path)))
 :rcf)

(tests
 "extract-registry-from-backup returns nil for old-format ZIP (no registry entry)"
 (let [db-dir     (str "/tmp/winze-extract-old-test-" (System/nanoTime))
       backup-dir (str "/tmp/winze-extract-old-bkp-"  (System/nanoTime))]
   (.mkdirs (io/file db-dir))
   (spit (io/file db-dir "data.edn") "{}")
   (let [archive (snapshot! db-dir backup-dir)]
     (extract-registry-from-backup (io/file archive)) := nil)
   (delete-dir! db-dir)
   (delete-dir! backup-dir))
 :rcf)
