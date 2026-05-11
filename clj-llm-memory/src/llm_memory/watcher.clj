(ns llm-memory.watcher
  "Filesystem watcher — watches Plans/ directories for changes and
  incrementally updates the index.

  Features:
    - Multi-root support (one watcher per root)
    - 500ms debounce window (coalesces rapid editor saves)
    - Rename detection (delete+create within 1s with matching content hash)
    - Filters: only .md files, ignores INDEX.md, STATUS.md, dotfiles, temp files
    - INFO-level logging with root name prefix"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nextjournal.beholder :as beholder]
            [llm-memory.index :as idx]
            [llm-memory.store.protocol :as store]
            [hyperfiddle.rcf :refer [tests]]
            [llm-memory.test-support :as ts])
  (:import [java.security MessageDigest]
           [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

;; {root-uri → {:watcher beholder-handle :root-name str}}
(defonce ^:private watchers (atom {}))

;; {root-uri → [{:path str :content-hash str :timestamp long :file-id str} ...]}
(defonce ^:private pending-deletes (atom {}))

;; {[root-uri abs-path] → {:future ScheduledFuture :action fn}}
(defonce ^:private debounce-state (atom {}))

;; [(fn [root-uri abs-path event-type extra]) ...]
;; event-type: :create, :modify, :delete, :rename
;; extra: nil for non-rename events; {:old-path str, :new-path str} for :rename
(defonce ^:private change-listeners (atom []))

;; Shared single-thread scheduler for debounce timers
(defonce ^:private scheduler (delay (Executors/newSingleThreadScheduledExecutor)))

;; ---------------------------------------------------------------------------
;; Change listener public API
;; ---------------------------------------------------------------------------

(defn add-change-listener!
  "Register a callback invoked after debounce for every indexed file change.
   Signature: (fn [root-uri abs-path event-type extra])
   - event-type: :create, :modify, :delete, :rename
   - extra: nil for non-rename events; {:old-path str, :new-path str} for :rename"
  [listener-fn]
  (swap! change-listeners conj listener-fn))

(defn remove-change-listener!
  "Remove a previously registered change listener."
  [listener-fn]
  (swap! change-listeners (fn [ls] (vec (remove #{listener-fn} ls)))))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- sha256 [^String text]
  (let [md (MessageDigest/getInstance "SHA-256")
        bytes (.digest md (.getBytes text "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

(defn- ignored-file?
  "True if this file should not be indexed."
  [^String filename]
  (or (= filename "INDEX.md")
      (= filename "STATUS.md")
      (str/starts-with? filename ".")
      (str/ends-with? filename "~")
      (str/starts-with? filename "#")))

(defn- md-file?
  "True if this path is a markdown file we should process."
  [^java.nio.file.Path path]
  (let [filename (.toString (.getFileName path))]
    (and (str/ends-with? filename ".md")
         (not (ignored-file? filename)))))

(defn- log [root-name level & args]
  (let [ts (.format (java.time.LocalTime/now)
                    (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss"))
        msg (str/join " " args)]
    (println (format "[%s] [%s] [%s] %s" ts level root-name msg))))

(defn- notify-listeners!
  "Notify all registered change listeners. Catches and logs errors per listener."
  [root-name root-uri abs-path event-type extra]
  (doseq [l @change-listeners]
    (try (l root-uri abs-path event-type extra)
         (catch Throwable t
           (log root-name "WARN" "change listener error:" (.getMessage t))))))

;; ---------------------------------------------------------------------------
;; Debouncing
;; ---------------------------------------------------------------------------

(defn- debounce!
  "Schedule an action after delay-ms, cancelling any pending action for the same key.
   Key is [root-uri abs-path]."
  [key delay-ms action]
  (let [old (get @debounce-state key)]
    ;; Cancel previous timer if exists
    (when-let [fut (:future old)]
      (.cancel fut false))
    ;; Schedule new
    (let [fut (.schedule ^ScheduledExecutorService @scheduler
                         ^Runnable (fn []
                                     (swap! debounce-state dissoc key)
                                     (try
                                       (action)
                                       (catch Exception e
                                         (println "[WARN] watcher action failed:" (.getMessage e)))))
                         (long delay-ms)
                         TimeUnit/MILLISECONDS)]
      (swap! debounce-state assoc key {:future fut :action action}))))

;; ---------------------------------------------------------------------------
;; Rename detection
;; ---------------------------------------------------------------------------

(def ^:private rename-window-ms 1000)
(def ^:private delete-cleanup-ms 2000)

(defn- record-delete!
  "Record a delete event for potential rename matching."
  [root-uri abs-path content-hash file-id]
  (swap! pending-deletes update root-uri
         (fnil conj [])
         {:path abs-path
          :content-hash content-hash
          :file-id file-id
          :timestamp (System/currentTimeMillis)})
  ;; Schedule cleanup of this delete record
  (.schedule ^ScheduledExecutorService @scheduler
             ^Runnable (fn []
                         (swap! pending-deletes update root-uri
                                (fn [deletes]
                                  (vec (remove #(= abs-path (:path %))
                                               (or deletes []))))))
             (long delete-cleanup-ms)
             TimeUnit/MILLISECONDS))

(defn- match-rename
  "Check if a create event matches a recent delete (same root, same content hash).
   Returns the matched delete record or nil."
  [root-uri content-hash]
  (let [deletes (get @pending-deletes root-uri [])
        now     (System/currentTimeMillis)
        match   (first (filter (fn [d]
                                 (and (= content-hash (:content-hash d))
                                      (< (- now (:timestamp d)) rename-window-ms)))
                               deletes))]
    (when match
      ;; Remove the matched delete
      (swap! pending-deletes update root-uri
             (fn [ds] (vec (remove #(= (:path %) (:path match)) (or ds [])))))
      match)))

;; ---------------------------------------------------------------------------
;; Event handlers
;; ---------------------------------------------------------------------------

(defn- stored-hash
  "Return the DB content hash for abs-path under this root, or nil.
  Keys by the event's own file-id (computed via idx/compute-file-id) —
  a :create event for a freshly-renamed file has no entity yet, so
  stored-hash returns nil and rename detection proceeds normally. The
  guard only short-circuits for path-stable re-entries."
  [store root-name ^java.io.File plans-abs-dir ^String abs-path]
  (let [fid (idx/compute-file-id root-name plans-abs-dir abs-path)]
    (ffirst (store/query store
                         '[:find ?hash
                           :in $ ?fid
                           :where
                           [?f :file/id ?fid]
                           [?f :file/content-hash ?hash]]
                         {:fid fid}))))

(defn- handle-create-or-modify!
  "Handle a create or modify event (debounced).

  Content-hash guard: before any indexing, compare the on-disk SHA-256
  against the stored :file/content-hash. A match means this event was
  almost certainly self-triggered by propagation's file rewrite — the
  file is already correctly indexed. Skip the reindex but still fire
  listeners so downstream consumers see the one disk change as one
  event. See CONTEXT.md 'Content-hash guard in the watcher'.

  On genuine content change (hash mismatch), :modify events route
  through index-file-with-heading-diff! so inline heading renames
  propagate. :create events with a matching content hash against a
  pending-delete record are treated as renames; otherwise they are
  indexed fresh. The nil-return branch on rename-file! (file was not
  previously indexed) now falls back to :create + index-file! — see
  the plan's Step 12 for the intentional behavior change vs. the
  previous :rename-for-unindexed-file latent bug."
  [store root-uri root-name ^java.io.File plans-abs-dir abs-path event-type]
  (let [file      (io/file abs-path)
        text      (slurp file)
        disk-hash (sha256 text)
        db-hash   (stored-hash store root-name plans-abs-dir abs-path)]
    (if (= disk-hash db-hash)
      (do
        (log root-name "DEBUG" "skip reindex (hash match); notifying listeners" abs-path)
        (notify-listeners! root-name root-uri abs-path event-type nil))
      (if (= event-type :create)
        (if-let [match (match-rename root-uri disk-hash)]
          (if-let [{:keys [old-id new-id]}
                   (idx/rename-file! store root-uri (:path match) abs-path)]
            (do
              (log root-name "INFO" "rename" (:path match) "→" abs-path)
              (idx/propagate-file-rename! store old-id new-id)
              (notify-listeners! root-name root-uri abs-path :rename
                                 {:old-path (:path match) :new-path abs-path}))
            ;; rename-file! returned nil — old entity was not indexed.
            ;; Fall through to fresh index + :create listener (intentional
            ;; fix for the latent :rename-for-unindexed-file bug).
            (do
              (log root-name "INFO" "index (new — rename candidate unindexed)" abs-path)
              (idx/index-file! store root-uri abs-path)
              (notify-listeners! root-name root-uri abs-path :create nil)))
          (do
            (log root-name "INFO" "index (new)" abs-path)
            (idx/index-file! store root-uri abs-path)
            (notify-listeners! root-name root-uri abs-path :create nil)))
        (do
          (log root-name "INFO" "index (modified)" abs-path)
          (idx/index-file-with-heading-diff! store root-uri abs-path)
          (notify-listeners! root-name root-uri abs-path :modify nil))))))

(defn- handle-delete!
  "Handle a delete event — record for rename detection, retract after timeout.

  Takes the absolute Plans/ directory (`plans-abs-dir`, a File) so the
  file-id formula goes through idx/compute-file-id — no inline
  reconstruction of the `root-name::rel-path` string."
  [store root-uri root-name ^java.io.File plans-abs-dir abs-path]
  (let [file-id      (idx/compute-file-id root-name plans-abs-dir abs-path)
        hash-result  (store/query store
                                  '[:find ?hash
                                    :in $ ?fid
                                    :where [?f :file/id ?fid]
                                    [?f :file/content-hash ?hash]]
                                  {:fid file-id})
        content-hash (ffirst hash-result)]

    (if content-hash
      (do
        (record-delete! root-uri abs-path content-hash file-id)
        ;; Schedule retraction after rename window
        (.schedule ^ScheduledExecutorService @scheduler
                   ^Runnable (fn []
                               ;; Only retract if delete wasn't consumed by a rename match
                               (when (some #(= abs-path (:path %))
                                           (get @pending-deletes root-uri []))
                                 (log root-name "INFO" "retract (deleted)" abs-path)
                                 (try
                                   (idx/retract-file-by-id! store file-id)
                                   (catch Exception e
                                     (println "[WARN] retract failed:" (.getMessage e))))
                                 (notify-listeners! root-name root-uri abs-path :delete nil)
                                 ;; Clean up the delete record
                                 (swap! pending-deletes update root-uri
                                        (fn [ds] (vec (remove #(= abs-path (:path %)) (or ds [])))))))
                   (long (+ rename-window-ms 100))
                   TimeUnit/MILLISECONDS))
      (log root-name "WARN" "delete event for unknown file:" abs-path))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(def ^:private debounce-delay-ms 500)

(defn start-watcher!
  "Start watching a root's Plans/ directory for changes.
   Returns the root-uri (used as a handle to stop the watcher)."
  [store root-uri]
  (let [root-info (first (store/query store
                                      '[:find ?name ?dir
                                        :in $ ?ruri
                                        :where
                                        [?r :root/uri ?ruri]
                                        [?r :root/name ?name]
                                        [?r :root/plans-dir ?dir]]
                                      {:ruri root-uri}))
        _         (when-not root-info
                    (throw (ex-info (str "Root not found: " root-uri)
                                    {:root-uri root-uri})))
        [root-name plans-dir] root-info
        base-path (str/replace root-uri #"^file://" "")
        watch-dir (io/file base-path plans-dir)
        _         (when-not (.isDirectory watch-dir)
                    (throw (ex-info (str "Plans directory not found: " watch-dir)
                                    {:root-uri root-uri :watch-dir (str watch-dir)})))

        handler   (fn [{:keys [type path]}]
                    (when (md-file? path)
                      (let [abs-path (.toString (.toAbsolutePath path))
                            key      [root-uri abs-path]]
                        (case type
                          :create (debounce! key debounce-delay-ms
                                             #(handle-create-or-modify!
                                               store root-uri root-name watch-dir abs-path :create))
                          :modify (debounce! key debounce-delay-ms
                                             #(handle-create-or-modify!
                                               store root-uri root-name watch-dir abs-path :modify))
                          :delete (handle-delete! store root-uri root-name watch-dir abs-path)
                          nil))))

        watcher   (beholder/watch handler (.toString watch-dir))]

    (swap! watchers assoc root-uri {:watcher watcher :root-name root-name})
    (log root-name "INFO" "watcher started on" (.toString watch-dir))
    root-uri))

(defn stop-watcher!
  "Stop the watcher for a given root URI."
  [root-uri]
  (when-let [{:keys [watcher root-name]} (get @watchers root-uri)]
    (beholder/stop watcher)
    (swap! watchers dissoc root-uri)
    ;; Cancel any pending debounce timers for this root
    (doseq [[[ruri _] {:keys [future]}] @debounce-state
            :when (= ruri root-uri)]
      (.cancel ^java.util.concurrent.ScheduledFuture future false))
    (swap! debounce-state (fn [m] (into {} (remove (fn [[[ruri _] _]] (= ruri root-uri)) m))))
    ;; Clean up pending deletes
    (swap! pending-deletes dissoc root-uri)
    (log (or root-name root-uri) "INFO" "watcher stopped")
    true))

(defn stop-all!
  "Stop all watchers. Call on shutdown."
  []
  (doseq [root-uri (keys @watchers)]
    (stop-watcher! root-uri)))

(defn watcher-status
  "Return status of all active watchers."
  []
  (into {}
        (map (fn [[root-uri {:keys [root-name]}]]
               [root-uri {:root-name root-name :status :running}])
             @watchers)))

(defn watching?
  "Return true if a watcher is active for the given root-uri."
  [root-uri]
  (contains? @watchers root-uri))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(tests
 "watcher — start and stop"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       ruri (ts/root-uri root)
       reg! (requiring-resolve 'llm-memory.core/register-root!)]
   (.mkdirs (io/file root "Plans/todo"))
   (reg! s {:uri ruri :name "lifecycle" :plans-dir "Plans"})
   (start-watcher! s ruri)
   (contains? (watcher-status) ruri) := true
   (stop-watcher! ruri)
   (contains? (watcher-status) ruri) := false
   (store/disconnect! s))
 :rcf)

(tests
 "watcher — detects new .md file creation"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       ruri (ts/root-uri root)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       stat (requiring-resolve 'llm-memory.core/status)]
   (.mkdirs (io/file root "Plans/todo"))
   (reg! s {:uri ruri :name "create-test" :plans-dir "Plans"})
   (start-watcher! s ruri)
   (spit (io/file root "Plans/todo/NEW.md") "# New\n\n## Sec\n\nNew content.")
   (Thread/sleep 2000)
   (:files (stat s)) := 1
   (:chunks (stat s)) := 1
   (stop-watcher! ruri)
   (store/disconnect! s))
 :rcf)

(tests
 "watcher — detects file modification"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       ruri (ts/root-uri root)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       stat (requiring-resolve 'llm-memory.core/status)]
   (.mkdirs (io/file root "Plans/todo"))
   (reg! s {:uri ruri :name "mod-test" :plans-dir "Plans"})
   (spit (io/file root "Plans/todo/M.md") "# M\n\n## Sec\n\nOriginal.")
   (idx/index-file! s ruri (.getAbsolutePath (io/file root "Plans/todo/M.md")))
   (:chunks (stat s)) := 1
   (start-watcher! s ruri)
   (spit (io/file root "Plans/todo/M.md") "# M\n\n## Sec\n\nModified.\n\n## New Sec\n\nAdded.")
   (Thread/sleep 2000)
   (:chunks (stat s)) := 2
   (stop-watcher! ruri)
   (store/disconnect! s))
 :rcf)

(tests
 "watcher — detects file deletion"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       ruri (ts/root-uri root)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       stat (requiring-resolve 'llm-memory.core/status)]
   (.mkdirs (io/file root "Plans/todo"))
   (reg! s {:uri ruri :name "del-test" :plans-dir "Plans"})
   (spit (io/file root "Plans/todo/D.md") "# D\n\n## Sec\n\nDoomed.")
   (idx/index-file! s ruri (.getAbsolutePath (io/file root "Plans/todo/D.md")))
   (:files (stat s)) := 1
   (start-watcher! s ruri)
   (.delete (io/file root "Plans/todo/D.md"))
   (Thread/sleep 3000)
   (:files (stat s)) := 0
   (stop-watcher! ruri)
   (store/disconnect! s))
 :rcf)

(tests
 "watcher — detects rename (delete+create within window)"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       ruri (ts/root-uri root)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       stat (requiring-resolve 'llm-memory.core/status)
       lstf (requiring-resolve 'llm-memory.core/list-files)]
   (.mkdirs (io/file root "Plans/todo"))
   (.mkdirs (io/file root "Plans/complete"))
   (reg! s {:uri ruri :name "ren-test" :plans-dir "Plans"})
   (spit (io/file root "Plans/todo/OLD-CONTEXT.md") "# Rename\n\n## Sec\n\nContent to move.")
   (idx/index-file! s ruri (.getAbsolutePath (io/file root "Plans/todo/OLD-CONTEXT.md")))
   (:files (stat s)) := 1
   (start-watcher! s ruri)
   (.mkdirs (io/file root "Plans/complete/rename-detect"))
   (.delete (io/file root "Plans/todo/OLD-CONTEXT.md"))
   (Thread/sleep 200)
   (spit (io/file root "Plans/complete/rename-detect/CONTEXT.md")
         "# Rename\n\n## Sec\n\nContent to move.")
   (Thread/sleep 3000)
   (:files (stat s)) := 1
   (let [f (first (lstf s))]
     (:file/path f) := "complete/rename-detect/CONTEXT.md"
     (:file/status f) := "complete")
   (stop-watcher! ruri)
   (store/disconnect! s))
 :rcf)

(tests
 "watcher — ignores INDEX.md, STATUS.md, dotfiles, temp files"
 (let [root (ts/tmp-dir)
       s    (ts/fresh-store)
       ruri (ts/root-uri root)
       reg! (requiring-resolve 'llm-memory.core/register-root!)
       stat (requiring-resolve 'llm-memory.core/status)]
   (.mkdirs (io/file root "Plans/todo"))
   (reg! s {:uri ruri :name "filter-test" :plans-dir "Plans"})
   (start-watcher! s ruri)
   (spit (io/file root "Plans/INDEX.md") "# Index")
   (spit (io/file root "Plans/STATUS.md") "# Status")
   (spit (io/file root "Plans/todo/.hidden.md") "# Hidden")
   (spit (io/file root "Plans/todo/backup.md~") "# Backup")
   (Thread/sleep 2000)
   (:files (stat s)) := 0
   (stop-watcher! ruri)
   (store/disconnect! s))
 :rcf)

(tests
 "watcher — content-hash guard skips reindex on identical content, still fires listener"
 ;; Calls handle-create-or-modify! directly — macOS FSEvents elides :modify
 ;; events when the on-disk bytes are unchanged, so the Beholder/debounce
 ;; path would be flaky here. The guard logic is what we pin; event
 ;; delivery is Beholder's responsibility.
 (let [root     (ts/tmp-dir)
       s        (ts/fresh-store)
       ruri     (ts/root-uri root)
       reg!     (requiring-resolve 'llm-memory.core/register-root!)
       events   (atom [])
       listener (fn [_ruri _abs t _extra] (swap! events conj t))
       hcm!     (deref #'handle-create-or-modify!)]
   (.mkdirs (io/file root "Plans/todo"))
   (reg! s {:uri ruri :name "hash-guard" :plans-dir "Plans"})
   (spit (io/file root "Plans/todo/G.md") "# G\n\n## S\n\nOriginal.")
   (idx/index-file! s ruri (.getAbsolutePath (io/file root "Plans/todo/G.md")))
   (add-change-listener! listener)
   ;; Re-invoke with identical content — guard should short-circuit indexing.
   (hcm! s ruri "hash-guard" (io/file root "Plans")
         (.getAbsolutePath (io/file root "Plans/todo/G.md")) :modify)
   (count @events) := 1
   (first @events) := :modify
   (remove-change-listener! listener)
   (store/disconnect! s))
 :rcf)

(tests
 "watcher — rename path: match-rename + rename-file! + propagate-file-rename! rewrites linker on disk"
 ;; Calls handle-delete! + handle-create-or-modify! directly for the same
 ;; reason: Beholder delivery order/timing on macOS varies enough to make
 ;; the test flaky in CI. The component under test is the watcher's
 ;; rename code path + propagation — not Beholder.
 (let [root   (ts/tmp-dir)
       s      (ts/fresh-store)
       ruri   (ts/root-uri root)
       reg!   (requiring-resolve 'llm-memory.core/register-root!)
       hd!    (deref #'handle-delete!)
       hcm!   (deref #'handle-create-or-modify!)]
   (.mkdirs (io/file root "Plans/todo"))
   (.mkdirs (io/file root "Plans/complete"))
   (reg! s {:uri ruri :name "wr-prop" :plans-dir "Plans"})
   (let [a-old   (io/file root "Plans/todo/A.md")
         a-new   (io/file root "Plans/complete/A.md")
         b-file  (io/file root "Plans/todo/B.md")
         watch-d (io/file root "Plans")]
     (spit a-old "# A\n\n## S\n\nPinned target content.")
     (spit b-file "# B\n\n## S\n\nReference to [a](wiki:wr-prop::todo/A.md).")
     (idx/index-file! s ruri (.getAbsolutePath a-old))
     (idx/index-file! s ruri (.getAbsolutePath b-file))
     (.delete a-old)
     (hd! s ruri "wr-prop" watch-d (.getAbsolutePath a-old))
     (spit a-new "# A\n\n## S\n\nPinned target content.")
     (hcm! s ruri "wr-prop" watch-d (.getAbsolutePath a-new) :create)
     (.contains (slurp b-file) "wiki:wr-prop::complete/A.md") := true
     (.contains (slurp b-file) "wiki:wr-prop::todo/A.md")     := false)
   (store/disconnect! s))
 :rcf)

(tests
 "watcher — change listeners notified on create and modify"
 (let [root     (ts/tmp-dir)
       s        (ts/fresh-store)
       ruri     (ts/root-uri root)
       reg!     (requiring-resolve 'llm-memory.core/register-root!)
       events   (atom [])
       listener (fn [_root-uri _abs-path event-type _extra]
                  (swap! events conj event-type))]
   (.mkdirs (io/file root "Plans/todo"))
   (reg! s {:uri ruri :name "listener-test" :plans-dir "Plans"})
   (add-change-listener! listener)
   (start-watcher! s ruri)
   ;; Trigger create
   (spit (io/file root "Plans/todo/L.md") "# L\n\n## Sec\n\nContent.")
   (Thread/sleep 2000)
   (count @events) := 1
   (first @events) := :create
   ;; Trigger modify
   (reset! events [])
   (spit (io/file root "Plans/todo/L.md") "# L\n\n## Sec\n\nModified.")
   (Thread/sleep 2000)
   (count @events) := 1
   (first @events) := :modify
   ;; Cleanup
   (remove-change-listener! listener)
   (stop-watcher! ruri)
   (store/disconnect! s))
 :rcf)
