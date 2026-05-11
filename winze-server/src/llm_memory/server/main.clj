(ns llm-memory.server.main
  "Plan Server entry point — long-running JVM process.

  Opens a Datalevin store, reconciles all registered roots, starts file
  watchers, and exposes the library via nREPL for the Babashka MCP proxy.

  Startup resilience: if the Datalevin database is corrupt, the server tries
  to restore from the most recent backup archive. If all backups fail, it
  deletes the database and rebuilds from source files (~30s).

  Periodic backup: a ScheduledExecutorService snapshots the database at a
  configurable interval (default 6h) while queries are blocked via a
  ReentrantReadWriteLock held by the backup cycle."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [nrepl.server :as nrepl]
            [llm-memory.core :as mem]
            [llm-memory.index :as idx]
            [llm-memory.store.backup :as backup]
            [llm-memory.store.protocol :as store]
            [llm-memory.watcher :as watcher])
  ;; SWT + CDT loaded at runtime only — see -main.
  ;; AOT compilation excludes SWT (platform-specific) and CDT (requires clojure.repl.deps).
  (:import [java.util.concurrent ScheduledExecutorService ScheduledThreadPoolExecutor TimeUnit]
           [java.util.concurrent.locks ReentrantReadWriteLock]
           [java.lang ProcessHandle])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def ^:private default-data-dir
  (str (System/getProperty "user.home") "/.local/share/winze"))

(defn- data-dir []
  (or (System/getenv "PLANS_DB_PATH")
      default-data-dir))

(defn- db-path []
  (str (data-dir) "/.datalevin"))

(defn- backup-dir []
  (str (data-dir) "/backups"))

(defn- pid-file []
  (io/file (data-dir) ".pid"))

(defn- nrepl-port-file []
  (io/file (data-dir) ".nrepl-port"))

(defn- backup-interval-hours []
  (Long/parseLong (or (System/getenv "WINZE_BACKUP_INTERVAL_HOURS") "1")))

(defn- backup-retention []
  (Long/parseLong (or (System/getenv "WINZE_BACKUP_RETENTION") "6")))

(defn- roots-config-path
  "Path to the roots.edn config file (alongside the database directory)."
  []
  (io/file (data-dir) "roots.edn"))

(defn- read-roots-config
  "Read roots.edn; returns [] if the file doesn't exist or is malformed."
  []
  (let [f (roots-config-path)]
    (if (.exists f)
      (try (edn/read-string (slurp f))
           (catch Exception e
             (log/warn "could not read roots.edn:" (.getMessage e))
             []))
      [])))

(defn write-roots-config!
  "Write the current registered roots to roots.edn."
  [s]
  (let [roots (mem/list-roots s)
        data  (mapv (fn [r] {:uri       (:root/uri r)
                             :name      (:root/name r)
                             :plans-dir (:root/plans-dir r)})
                    roots)]
    (spit (roots-config-path) (pr-str data))
    (log/info "roots.edn updated:" (count data) "root(s)")))

(defn- sync-roots-from-config!
  "Re-register any roots in roots.edn that are missing from the store.
  Called on startup after open-store-resilient to recover from store deletion/restore."
  [s]
  (let [config-roots (read-roots-config)
        store-uris   (set (map :root/uri (mem/list-roots s)))
        missing      (remove #(store-uris (:uri %)) config-roots)]
    (when (seq missing)
      (log/info "roots.edn has" (count missing) "root(s) not in store — re-registering"))
    (doseq [{:keys [uri name plans-dir]} missing]
      (try
        (log/info "re-registering root:" uri)
        (mem/register-root! s {:uri uri :name name :plans-dir plans-dir})
        (catch Exception e
          (log/warn "failed to re-register root" uri ":" (.getMessage e)))))))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce ^:private state (atom nil))

;; Set to true when the user explicitly quits the UI (via quit! or window close).
;; When false and the event loop exits, we keep the server alive headless.
(defonce ^:private quitting? (atom false))

(defn set-quitting!
  "Signal that the application is shutting down deliberately (called from UI quit!)."
  []
  (reset! quitting? true))

(defn store
  "Return the active PlanStore. For use by nREPL eval from the proxy."
  []
  (:store @state))

;; ---------------------------------------------------------------------------
;; Lifecycle helpers
;; ---------------------------------------------------------------------------

(defn- write-file! [^java.io.File f content]
  (.mkdirs (.getParentFile f))
  (spit f (str content)))

(defn- delete-file! [^java.io.File f]
  (when (.exists f)
    (.delete f)))

(defn- log [& args]
  (log/info (str/join " " args)))

(defn- plans-dir-exists?
  "Check if a root's Plans directory exists on disk."
  [root]
  (let [base-path (str/replace (:root/uri root) #"^file://" "")
        plans-dir (io/file base-path (:root/plans-dir root))]
    (.isDirectory plans-dir)))

(defn- reconcile-and-watch!
  "For each registered root: reconcile db vs. disk, then start watcher.
  Skips roots whose Plans directory no longer exists (e.g. stale registrations).
  Returns a vec of {:root name :summary reconcile-summary} for diagnostic use."
  [s]
  (let [roots (mem/list-roots s)]
    (reduce
     (fn [summaries root]
       (let [uri  (:root/uri root)
             name (:root/name root)]
         (if-not (plans-dir-exists? root)
           (do (log "SKIP" name "— Plans directory not found (stale root?):" uri)
               summaries)
           (try
             (log "reconciling" name "...")
             (let [summary (idx/reconcile! s uri)]
               (log "  " name ":"
                    (:unchanged summary) "unchanged,"
                    (:modified summary) "modified,"
                    (:renamed summary) "renamed,"
                    (:new summary) "new,"
                    (:gone summary) "gone")
               (watcher/start-watcher! s uri)
               (conj summaries {:root name :summary summary}))
             (catch Throwable e
               (log "WARN: error reconciling/watching" name "—" (.getMessage e))
               summaries)))))
     []
     roots)))

;; ---------------------------------------------------------------------------
;; Startup resilience
;; ---------------------------------------------------------------------------

(defn- open-store-resilient
  "Open the Datalevin store, falling back to backup restore or fresh rebuild
   if the database is corrupt.

   Recovery sequence:
     1. Normal open
     2. For each backup (newest-first): restore → try open
     3. Delete database, open fresh (reconcile will rebuild from source files)"
  []
  (let [path (db-path)
        bdir (backup-dir)]
    (or
     ;; 1. Normal open
     (try
       (log "opening store...")
       (mem/open-store {:path path :embedding :inference4j})
       (catch Throwable e
         (log "WARN: store open failed — trying backups. Error:" (.getMessage e))
         nil))
     ;; 2. Restore from each backup, newest-first
     (reduce (fn [_ bk]
               (when-let [s (try
                              (log "restoring from backup" (.getName ^java.io.File bk) "...")
                              (backup/restore! bk path)
                              (mem/open-store {:path path :embedding :inference4j})
                              (catch Throwable ex
                                (log "WARN: restore from" (.getName ^java.io.File bk)
                                     "failed:" (.getMessage ex))
                                nil))]
                 (reduced s)))
             nil
             (backup/list-backups bdir))
     ;; 3. Last resort: delete and open fresh (reconcile rebuilds from source files)
     (do
       (log "WARN: all backups failed — deleting store, will rebuild from source files")
       (backup/delete-dir! path)
       (mem/open-store {:path path :embedding :inference4j})))))

;; ---------------------------------------------------------------------------
;; Startup migration
;; ---------------------------------------------------------------------------

(defn- retract-wiki-schema-data!
  "Remove all :wiki/* entities left over from the UUID permalink system."
  [s]
  (let [eids (store/query s '[:find [?e ...] :where [?e :wiki/id]])]
    (when (seq eids)
      (log "retracting" (count eids) "stale :wiki/* entities from prior UUID system")
      (store/retract! s (vec eids)))))

;; ---------------------------------------------------------------------------
;; Backup cycle
;; ---------------------------------------------------------------------------

(defn- backup-cycle!
  "Acquire write lock, snapshot database, prune old archives, reconnect.
   Blocks query operations via ReentrantReadWriteLock while disconnected."
  [s bdir retention]
  (let [db-path (:path s)
        wl      (.writeLock ^ReentrantReadWriteLock (:lock s))]
    (log "backup: starting cycle")
    (.lock wl)
    (try
      ;; Phase 1: quiesce
      (watcher/stop-all!)
      (store/disconnect! s)
      ;; Phase 2: snapshot — best-effort, don't abort reconnect on failure
      (try
        (let [archive (backup/snapshot! db-path bdir)]
          (log "backup: snapshot" archive)
          (let [pruned (backup/prune-backups! bdir retention)]
            (when (pos? pruned)
              (log "backup: pruned" pruned "old archive(s)"))))
        (catch Throwable e
          (log "WARN: backup snapshot failed:" (.getMessage e))))
      ;; Phase 3: resume
      (store/connect! s)
      (reconcile-and-watch! s)
      (log "backup: cycle complete")
      (catch Throwable e
        (log "ERROR: backup cycle aborted:" (.getMessage e))
        ;; Best-effort: reconnect if needed, then restart watchers
        (try
          (when-not @(:conn s)
            (store/connect! s))
          (reconcile-and-watch! s)
          (catch Throwable re
            (log "ERROR: failed to resume after backup error:" (.getMessage re)))))
      (finally
        (.unlock wl)))))

(defn- schedule-with-fixed-delay!
  "Thin wrapper to drive direct Java dispatch — ^long params ensure primitives,
   avoiding Clojure reflector's primitive/boxed mismatch at the call site."
  [^ScheduledExecutorService svc ^Runnable task ^long delay ^TimeUnit unit]
  (.scheduleWithFixedDelay svc task delay delay unit))

(defn- start-backup-scheduler!
  "Start a scheduled backup cycle. Returns nil if interval is 0 (disabled)."
  [s]
  (let [interval (backup-interval-hours)]
    (when (pos? interval)
      (log "backup: scheduler starting, interval" interval "hours, retention" (backup-retention))
      (let [sched (ScheduledThreadPoolExecutor. 1)]
        (schedule-with-fixed-delay!
         sched
         (reify Runnable
           (run [_]
             (try
               (backup-cycle! s (backup-dir) (backup-retention))
               (catch Throwable e
                 (log "ERROR: unhandled backup error:" (.getMessage e))))))
         (long interval) TimeUnit/HOURS)
        sched))))

;; ---------------------------------------------------------------------------
;; HNSW repair
;; ---------------------------------------------------------------------------

(defn- repair-hnsw-desync!
  "Detect and repair HNSW desynced chunks by re-indexing only affected files.
  Logs reconcile summaries when a desync is found to aid troubleshooting.
  No-op (silent) when the HNSW index is healthy."
  [s reconcile-summaries]
  (let [desynced (mem/hnsw-desynced-files s)]
    (when (seq desynced)
      (let [total-missing (reduce + (map :missing-chunks desynced))]
        (log "WARN: HNSW desync detected —" total-missing "chunks missing across"
             (count desynced) "file(s)")
        (log "reconcile summaries (for troubleshooting):")
        (doseq [{:keys [root summary]} reconcile-summaries]
          (log "  " root ":" summary))
        (doseq [{:keys [root/uri file/path root/plans-dir missing-chunks]} desynced]
          (let [base (str/replace uri #"^file://" "")
                abs  (str base "/" plans-dir "/" path)]
            (log "  re-indexing" path "(" missing-chunks "missing chunks)")
            (try
              (idx/index-file! s uri abs)
              (catch Exception e
                (log "  WARN: failed to re-index" path "—" (.getMessage e))))))
        (let [after (mem/hnsw-desynced-files s)]
          (if (empty? after)
            (log "HNSW repair complete — all chunks now indexed")
            (log "WARN: HNSW repair incomplete —" (count after) "file(s) still desynced:"
                 (mapv :file/path after))))))))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn start!
  "Start the Plan Server. Opens store (with backup recovery), reconciles, starts nREPL."
  []
  (log "starting plan server...")
  (log "data dir:" (data-dir))
  (log "db path:" (db-path))

  (let [s (open-store-resilient)]
    (log "store opened, embedding model loaded")

    ;; Re-register any roots lost after store deletion or restore
    (sync-roots-from-config! s)

    ;; Remove stale :wiki/* entities from the old UUID permalink system
    (retract-wiki-schema-data! s)

    (let [reconcile-summaries (reconcile-and-watch! s)]

      ;; Detect and repair HNSW desynced chunks (targeted — affected files only)
      (repair-hnsw-desync! s reconcile-summaries))

      ;; Start periodic backup scheduler and nREPL
    (let [sched  (start-backup-scheduler! s)
          server (nrepl/start-server :bind "127.0.0.1" :port 0)
          port   (:port server)]
      (log "nREPL listening on 127.0.0.1:" port)

        ;; Write port and PID files
      (write-file! (nrepl-port-file) port)
      (write-file! (pid-file) (-> (java.lang.ProcessHandle/current) .pid))

        ;; Save state
      (reset! state {:store            s
                     :nrepl-server     server
                     :port             port
                     :backup-scheduler sched})

        ;; Register shutdown hook
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread.
        (fn []
          (log "shutting down...")
          (watcher/stop-all!)
          (when-let [sched (:backup-scheduler @state)]
            (.shutdown ^ScheduledExecutorService sched))
          (nrepl/stop-server server)
          (mem/close-store! s)
          (delete-file! (nrepl-port-file))
          (delete-file! (pid-file))
          (log "shutdown complete"))))

      (log "plan server ready")
      server)))

(defn stop!
  "Stop the Plan Server. When running headless, also terminates the JVM."
  []
  (when-let [{:keys [store nrepl-server backup-scheduler]} @state]
    (watcher/stop-all!)
    (when backup-scheduler
      (.shutdown ^ScheduledExecutorService backup-scheduler))
    (nrepl/stop-server nrepl-server)
    (mem/close-store! store)
    (delete-file! (nrepl-port-file))
    (delete-file! (pid-file))
    (reset! state nil)
    (log "stopped")))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn- check-already-running!
  "Exit immediately if another winze process is already running.
  Reads the .pid file; if the recorded process is alive, prints an error
  and exits. Stale .pid files (dead process) are cleaned up and ignored."
  []
  (let [pf (pid-file)]
    (when (.exists pf)
      (let [pid (parse-long (str/trim (slurp pf)))]
        (if (and pid (.isPresent (ProcessHandle/of pid)))
          (do
            (binding [*out* *err*]
              (println (str "winze: another instance is already running (PID " pid "). Exiting.")))
            (System/exit 1))
          (do
            (log "stale .pid file found (PID" pid "not running) — removing")
            (delete-file! pf)))))))

(defn -main [& _args]
  (check-already-running!)

  ;; CDT dynamically loads platform-specific SWT natives via clojure.repl.deps/add-libs.
  ;; This requires: (1) a DynamicClassLoader so add-libs can inject URLs, and
  ;; (2) *repl* bound to true since add-libs is gated on it.
  ;; Pattern from: github.com/coconutpalm/clojure-desktop-toolkit/examples/starter
  (let [cl (.getContextClassLoader (Thread/currentThread))]
    (.setContextClassLoader (Thread/currentThread) (clojure.lang.DynamicClassLoader. cl)))

  (binding [*repl* true]
    ;; Load CDT — this triggers SWT platform detection and native library loading
    (require 'ui.SWT)

    ;; Initialize SWT Display on the main thread (macOS requires -XstartOnFirstThread)
    (eval '(do
             (org.eclipse.swt.widgets.Display/setAppName "Winze")
             (reset! ui.SWT/display (org.eclipse.swt.widgets.Display/getDefault))))

    ;; Install uncaught exception handler on Display — prevents silent crashes
    (eval '(let [d @ui.SWT/display]
             (.addListener d org.eclipse.swt.SWT/ERROR
                           (reify org.eclipse.swt.widgets.Listener
                             (handleEvent [_ event]
                               (let [t (.-data event)]
                                 (clojure.tools.logging/error
                                  (if (instance? Throwable t) t (Throwable. (str t)))
                                  "SWT uncaught error")))))))

    (start!)

    (try
      (require '[llm-memory.ui.main-window :as ui])
      (catch Throwable t
        (clojure.tools.logging/error t (.getMessage t))
        (log "ERROR: failed to load UI:" (.getMessage t))
        (.printStackTrace t)))

    ;; Run the SWT UI/event loop on the main thread so that syncExec (used by the
    ;; CDT `ui` macro) can dispatch UI work from nREPL threads. Without this,
    ;; syncExec deadlocks because nobody drains the Display's event queue.
    (when (find-ns 'llm-memory.ui.main-window)
      (try
        (eval '(ui/main-window))
        (catch Throwable t
          (clojure.tools.logging/error t (.getMessage t))))))

  (stop!)
  (shutdown-agents)
  ;; macOS: NSApplication stays in menu bar until the JVM exits.
  ;; Disposing the Display only tears down SWT's event loop; System/exit
  ;; is needed to terminate the process and remove it from the Dock.
  (System/exit 0))
