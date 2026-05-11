#!/usr/bin/env bb

;; mcp-proxy.clj — Babashka MCP proxy for the Plan Server.
;;
;; Implements the MCP JSON-RPC protocol over stdio. Translates tool calls
;; to nREPL eval on the Plan Server JVM process.
;;
;; Auto-starts the Plan Server if it's not running. Uses PID-based
;; liveness checks and a lock file to prevent duplicate server launches.
;;
;; Usage:
;;   bb mcp-proxy.clj
;;
;; Register with Claude Code:
;;   claude mcp add winze -- bb ~/.local/share/winze/mcp-proxy.clj

(require '[babashka.process :as proc]
         '[cheshire.core :as json]
         '[bencode.core :as bencode]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def data-dir (str (System/getProperty "user.home") "/.local/share/winze"))
(def nrepl-port-file (str data-dir "/.nrepl-port"))
(def pid-file (str data-dir "/.pid"))
(def lock-file (str data-dir "/.startup-lock"))
(def log-file (str data-dir "/plan-server.log"))

;; Resolve server jar and java binary.
;;
;; Two layouts are supported:
;;
;;   dev-install (make install):
;;     data-dir/mcp-proxy.clj        ← script-dir == data-dir
;;     data-dir/lib/winze-server.jar
;;
;;   packaged (make package → install.sh):
;;     data-dir/bin/mcp-proxy.clj    ← script-dir == data-dir/bin
;;     data-dir/lib/winze-server.jar
;;     data-dir/jre/bin/java
;;
;; The JAR is always at lib/ relative to data-dir; only the proxy depth varies.
;; We probe both candidate paths in order and take the first that exists,
;; falling back to the legacy flat path (data-dir/winze-server.jar + system java).
(def ^:private script-dir
  (let [f (io/file *file*)]
    (when (.exists f) (.getParent (.getCanonicalFile f)))))

(defn- first-existing [& paths]
  (some (fn [f] (when (.exists f) (.getCanonicalPath f))) paths))

(def ^:private bundled-jar
  (when script-dir
    (first-existing
     (io/file script-dir "lib" "winze-server.jar")       ; dev-install: proxy at data-dir/
     (io/file script-dir ".." "lib" "winze-server.jar")))) ; packaged: proxy at data-dir/bin/

(def ^:private bundled-java
  (when script-dir
    (first-existing
     (io/file script-dir "jre" "bin" "java")             ; dev-install: proxy at data-dir/
     (io/file script-dir ".." "jre" "bin" "java"))))

(def server-jar (or bundled-jar (str data-dir "/winze-server.jar")))
(def java-cmd   (or bundled-java "java"))

;; ---------------------------------------------------------------------------
;; Logging (to file only — stdout is MCP JSON-RPC, stderr can also break clients)
;; ---------------------------------------------------------------------------

(defn- log-proxy [& args]
  (let [ts (.format (java.time.LocalDateTime/now)
                    (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss.SSS"))
        msg (str ts " [mcp-proxy] " (str/join " " args) "\n")]
    (spit log-file msg :append true)))

;; ---------------------------------------------------------------------------
;; nREPL client
;; ---------------------------------------------------------------------------

(defn- read-port []
  (when (.exists (io/file nrepl-port-file))
    (try (Integer/parseInt (str/trim (slurp nrepl-port-file)))
         (catch Exception _ nil))))

(defn- read-pid []
  (when (.exists (io/file pid-file))
    (try (Long/parseLong (str/trim (slurp pid-file)))
         (catch Exception _ nil))))

(defn- pid-alive?
  "Check if a process with the given PID is running."
  [pid]
  (when pid
    (try
      (let [result (proc/process {:cmd ["kill" "-0" (str pid)]
                                  :out :string :err :string})]
        (zero? (:exit @result)))
      (catch Exception _ false))))

(defn- nrepl-connect [port]
  (let [sock (java.net.Socket. "127.0.0.1" (int port))
        in   (java.io.PushbackInputStream. (io/input-stream sock))
        out  (io/output-stream sock)]
    {:socket sock :in in :out out}))

(defn- nrepl-eval
  "Send code to nREPL and return the result string."
  [{:keys [in out]} code]
  (let [msg {"op" "eval" "code" code "id" (str (random-uuid))}]
    (bencode/write-bencode out msg)
    (.flush out)
    ;; Read responses until we get "done" status
    (loop [result nil err nil]
      (let [resp (bencode/read-bencode in)
            resp-map (into {} (map (fn [[k v]] [(str k) (if (bytes? v) (String. ^bytes v "UTF-8") v)]) resp))]
        (cond
          ;; Error
          (get resp-map "err")
          (recur result (str err (get resp-map "err")))

          ;; Value
          (get resp-map "value")
          (recur (get resp-map "value") err)

          ;; Done
          (and (get resp-map "status")
               (some #(= "done" (if (bytes? %) (String. ^bytes % "UTF-8") (str %)))
                     (get resp-map "status")))
          (if err
            {:error err}
            {:value result})

          ;; Keep reading
          :else
          (recur result err))))))

;; ---------------------------------------------------------------------------
;; Server management
;; ---------------------------------------------------------------------------

(defn- nrepl-connectable?
  "Try to connect to the nREPL port. Returns true if successful."
  [port]
  (try
    (let [conn (nrepl-connect port)]
      (.close (:socket conn))
      true)
    (catch Exception _ false)))

(defn- clean-stale-files!
  "Remove .pid and .nrepl-port if the server process is not running."
  []
  (let [pid (read-pid)]
    (when (and pid (not (pid-alive? pid)))
      (log-proxy "cleaning stale files from dead PID" pid)
      (when (.exists (io/file pid-file)) (.delete (io/file pid-file)))
      (when (.exists (io/file nrepl-port-file)) (.delete (io/file nrepl-port-file))))))

(defn- server-running?
  "Check if the Plan Server is alive: PID exists AND is running AND nREPL is connectable."
  []
  (let [pid  (read-pid)
        port (read-port)]
    (and pid port
         (pid-alive? pid)
         (nrepl-connectable? port))))

(defn- acquire-lock!
  "Acquire the startup lock file. Returns true if acquired, false if another
   process holds it (and that process is still alive)."
  []
  (let [f (io/file lock-file)]
    (if (.exists f)
      ;; Lock exists — check if holder is alive
      (let [holder-pid (try (Long/parseLong (str/trim (slurp f)))
                            (catch Exception _ nil))]
        (if (and holder-pid (pid-alive? holder-pid))
          false ;; Another process is starting the server
          (do ;; Stale lock — take it
            (spit f (str (.pid (java.lang.ProcessHandle/current))))
            true)))
      (do
        (spit f (str (.pid (java.lang.ProcessHandle/current))))
        true))))

(defn- release-lock! []
  (let [f (io/file lock-file)]
    (when (.exists f)
      (.delete f))))

(defn- start-server! []
  (log-proxy "starting plan server...")
  ;; Redirect server stdout/stderr to log file — NEVER to :inherit
  ;; because our stdout IS the MCP JSON-RPC channel
  (let [log-out  (io/file log-file)
        macos?   (str/starts-with? (str/lower-case (System/getProperty "os.name" "")) "mac")
        jvm-args (cond-> ["--add-opens=java.base/java.nio=ALL-UNNAMED"
                          "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
                          "--enable-native-access=ALL-UNNAMED"]
                   macos? (conj "-XstartOnFirstThread"))
        p       (proc/process {:cmd (into [java-cmd] (conj jvm-args "-jar" server-jar))
                               :out :write :out-file log-out :out-append true
                               :err :write :err-file log-out :err-append true})]
    ;; Wait for .nrepl-port file to appear, or detect process exit (crash)
    ;; NOTE: bb/SCI doesn't have IllegalThreadStateException in its classlist,
    ;; so we use `realized?` on the :exit future to detect process termination.
    (loop [attempts 0]
      (Thread/sleep 1000)
      (cond
        ;; Success: server wrote its port and PID files
        (and (read-port) (read-pid))
        (do (log-proxy "server started (PID" (str (read-pid)) "port" (str (read-port)) ")")
            true)

        ;; Crash: process exited before writing port/PID files
        (realized? (:exit p))
        (do (log-proxy "FATAL: server process crashed (exit code"
                       (str @(:exit p)) ") — check" log-file)
            false)

        ;; Timeout
        (>= attempts 30)
        (do (log-proxy "ERROR: server did not start within 30s")
            false)

        :else (recur (inc attempts))))))

(defn- ensure-server!
  "Ensure exactly one Plan Server is running. Returns true on success."
  []
  ;; First, clean up stale files from crashed servers
  (clean-stale-files!)

  ;; Check if already running
  (if (server-running?)
    (do (log-proxy "server already running (PID" (str (read-pid)) "port" (str (read-port)) ")")
        true)
    ;; Need to start — acquire lock to prevent races.
    ;; Hold the lock throughout the entire startup+wait sequence so that
    ;; concurrent proxies don't each launch their own JVM.
    (if (acquire-lock!)
      (try
        ;; Double-check after acquiring lock (another proxy may have just started it)
        (if (server-running?)
          true
          (start-server!))
        (finally
          (release-lock!)))
      ;; Another process is starting the server — wait for it
      (do
        (log-proxy "another process is starting the server, waiting...")
        (loop [attempts 0]
          (Thread/sleep 1000)
          (cond
            (server-running?) true
            ;; If the lock was released (starter finished) but server isn't running,
            ;; the start failed — don't wait the full 30s
            (not (.exists (io/file lock-file)))
            (do (log-proxy "FATAL: another process tried to start the server but it failed")
                false)
            (< attempts 45) (recur (inc attempts))
            :else (do (log-proxy "ERROR: timed out waiting for server started by another process")
                      false)))))))

;; ---------------------------------------------------------------------------
;; Root discovery
;; ---------------------------------------------------------------------------

(def ^:private root-uri (atom nil))

(defn- discover-root
  "Discover the project root from PLANS_ROOT env var or CWD."
  []
  (or (System/getenv "PLANS_ROOT")
      (str "file://" (System/getProperty "user.dir"))))

;; ---------------------------------------------------------------------------
;; MCP protocol
;; ---------------------------------------------------------------------------

(def server-info
  {"name"    "winze"
   "version" "0.1.0"})

(def tool-definitions
  [{"name"        "search_plans"
    "description" "Search planning documents using semantic similarity.\n\nResults are scoped to the current project by default. Pass all_roots=true to search across all registered projects."
    "inputSchema" {"type"       "object"
                   "properties" {"query"     {"type" "string" "description" "Natural language query"}
                                 "n_results" {"type" "integer" "default" 5 "description" "Number of results (max 20)"}
                                 "detail"    {"type" "string" "default" "full" "enum" ["full" "summary" "files"]
                                              "description" "Detail level: full, summary, or files"}
                                 "dedupe"    {"type" "boolean" "default" true
                                              "description" "One result per file (default true)"}
                                 "status"    {"type" "string" "description" "Filter: active, complete, deferred"}
                                 "doc_type"  {"type" "string" "description" "Filter: context, plan, story, etc."}
                                 "group"     {"type" "string" "description" "Filter by work-item group"}
                                 "since"     {"type" "string" "description" "Filter: modified >= date (ISO)"}
                                 "all_roots" {"type" "boolean" "default" false
                                              "description" "Search across all registered projects (default: current project only)"}}
                   "required"   ["query"]}}

   {"name"        "list_plans"
    "description" "List all Planning document files indexed for the current project. Pass all_roots=true to list files from all projects."
    "inputSchema" {"type"       "object"
                   "properties" {"all_roots" {"type" "boolean" "default" false
                                              "description" "List files from all registered projects"}}}}

   {"name"        "related_plans"
    "description" "Find all documents in a work-item group and their cross-references. Scoped to current project by default."
    "inputSchema" {"type"       "object"
                   "properties" {"group"     {"type" "string" "description" "Group name (lowercase, hyphenated)"}
                                 "all_roots" {"type" "boolean" "default" false
                                              "description" "Search across all registered projects"}}
                   "required"   ["group"]}}

   {"name"        "recent_plans"
    "description" "List documents modified in the last N days. Scoped to current project by default."
    "inputSchema" {"type"       "object"
                   "properties" {"days"      {"type" "integer" "default" 7}
                                 "doc_type"  {"type" "string"}
                                 "status"    {"type" "string"}
                                 "all_roots" {"type" "boolean" "default" false
                                              "description" "Include files from all registered projects"}}}}

   {"name"        "plans_status"
    "description" "Report health of the planning system: embedding status, file/chunk counts, watcher status."
    "inputSchema" {"type" "object" "properties" {}}}

   {"name"        "index_plans"
    "description" "Reconcile or reindex Plans/ files. Default: incremental reconcile. Use reset=true for full reindex."
    "inputSchema" {"type"       "object"
                   "properties" {"reset"            {"type" "boolean" "default" false}
                                 "regenerate_index" {"type" "boolean" "default" true}}}}

   {"name"        "register_plans"
    "description" "Register a project root for Plans indexing.\n\nRegisters the project directory with the Plan Server, specifying which subdirectory contains planning documents. The server will index all .md files in that directory, start a filesystem watcher, and enable search."
    "inputSchema" {"type"       "object"
                   "properties" {"plans_dir" {"type" "string" "default" "Plans"
                                              "description" "Subdirectory containing planning documents (relative to project root)"}}}}

   {"name"        "list_plan_roots"
    "description" "List all registered project roots and their plans directories, file counts, and watcher status."
    "inputSchema" {"type" "object" "properties" {}}}])

(defn- write-json! [obj]
  (println (json/generate-string obj))
  (flush))

(defn- handle-initialize [msg]
  ;; Extract roots if client provides them
  (when-let [roots (get-in msg ["params" "roots"])]
    (when-let [first-root (first roots)]
      (reset! root-uri (get first-root "uri"))))
  ;; If no roots from client, use fallback
  (when-not @root-uri
    (reset! root-uri (discover-root)))
  (write-json! {"jsonrpc" "2.0"
                "id"      (get msg "id")
                "result"  {"protocolVersion" "2024-11-05"
                           "capabilities"    {"tools" {}}
                           "serverInfo"      server-info}}))

(defn- handle-tools-list [msg]
  (write-json! {"jsonrpc" "2.0"
                "id"      (get msg "id")
                "result"  {"tools" tool-definitions}}))

(defn- tool-call->nrepl-code
  "Translate an MCP tool call to a Clojure expression for nREPL eval."
  [tool-name args]
  (let [ruri      @root-uri
        ;; When all_roots is true, pass nil as root-uri so queries are unscoped
        scope-uri (if (get args "all_roots" false) nil ruri)]
    (case tool-name
      "search_plans"
      (let [detail (get args "detail" "full")
            detail-kw (str ":" detail)]
        (format "(llm-memory.tools/search-plans (llm-memory.server.main/store) %s {:n-results %s :detail %s :dedupe %s :status %s :doc-type %s :group %s :since %s :root-uri %s})"
                (pr-str (get args "query"))
                (or (get args "n_results") 5)
                detail-kw
                (get args "dedupe" true)
                (pr-str (get args "status"))
                (pr-str (get args "doc_type"))
                (pr-str (get args "group"))
                (pr-str (get args "since"))
                (pr-str scope-uri)))

      "list_plans"
      (format "(llm-memory.tools/list-plans (llm-memory.server.main/store) {:root-uri %s})"
              (pr-str scope-uri))

      "related_plans"
      (format "(llm-memory.tools/related-plans (llm-memory.server.main/store) %s {:root-uri %s})"
              (pr-str (get args "group"))
              (pr-str scope-uri))

      "recent_plans"
      (format "(llm-memory.tools/recent-plans (llm-memory.server.main/store) {:days %s :doc-type %s :status %s :root-uri %s})"
              (or (get args "days") 7)
              (pr-str (get args "doc_type"))
              (pr-str (get args "status"))
              (pr-str scope-uri))

      "plans_status"
      "(llm-memory.tools/plans-status (llm-memory.server.main/store))"

      "index_plans"
      (format "(llm-memory.tools/index-plans (llm-memory.server.main/store) %s {:reset %s :regenerate-index %s})"
              (pr-str ruri)
              (get args "reset" false)
              (get args "regenerate_index" true))

      "register_plans"
      (let [plans-dir (get args "plans_dir" "Plans")]
        (format "(let [store (llm-memory.server.main/store)
                       roots (llm-memory.core/list-roots store)
                       exists? (some #(= %s (:root/uri %%)) roots)]
                   (if exists?
                     (str \"Root already registered: \" %s)
                     (let [base (clojure.string/replace %s #\"^file://\" \"\")
                           pdir (clojure.java.io/file base %s)]
                       (if (.isDirectory pdir)
                         (do (llm-memory.core/register-root! store {:uri %s :plans-dir %s})
                             (llm-memory.server.main/write-roots-config! store)
                             (let [summary (llm-memory.index/reconcile! store %s)]
                               (llm-memory.watcher/start-watcher! store %s)
                               (str \"Registered \" %s \" (\" %s \"/\" %s \")\\n\"
                                    \"Indexed: \" (:new summary) \" new, \"
                                    (:unchanged summary) \" unchanged\")))
                         (str \"ERROR: Directory not found: \" (.getAbsolutePath pdir))))))"
                (pr-str ruri) (pr-str ruri) (pr-str ruri) (pr-str plans-dir)
                (pr-str ruri) (pr-str plans-dir) (pr-str ruri) (pr-str ruri)
                (pr-str ruri) (pr-str ruri) (pr-str plans-dir)))

      "list_plan_roots"
      "(let [store (llm-memory.server.main/store)
             roots (llm-memory.core/list-roots store)]
         (if (empty? roots)
           \"No roots registered. Use register_plans to add one.\"
           (clojure.string/join
             \"\\n\\n\"
             (for [root roots]
               (let [uri  (:root/uri root)
                     name (:root/name root)
                     pdir (:root/plans-dir root)
                     base (clojure.string/replace uri #\"^file://\" \"\")
                     dir  (clojure.java.io/file base pdir)
                     exists? (.isDirectory dir)
                     files (when exists?
                             (count (llm-memory.store.protocol/query
                                      store
                                      '[:find ?f :in $ ?ruri :where
                                        [?r :root/uri ?ruri]
                                        [?f :file/root ?r]]
                                      {:ruri uri})))
                     watching? (llm-memory.watcher/watching? uri)]
                 (str \"### \" name \"\\n\"
                      \"  URI: \" uri \"\\n\"
                      \"  Plans dir: \" pdir
                      (if exists? \"\" \" (MISSING)\") \"\\n\"
                      \"  Files: \" (or files \"n/a\") \"\\n\"
                      \"  Watcher: \" (if watching? \"active\" \"stopped\")))))))"

      ;; Unknown tool
      (str "(throw (ex-info \"Unknown tool: " tool-name "\" {}))"))))

(defn- ensure-root-registered!
  "Auto-register the current root with the plan server if a Plans/ directory
  exists. Runs reconcile + starts a filesystem watcher."
  [conn]
  (let [ruri      @root-uri
        plans-dir "Plans"
        code      (format "(let [store  (llm-memory.server.main/store)
                                 roots  (llm-memory.core/list-roots store)
                                 exists? (some #(= %s (:root/uri %%)) roots)]
                             (if exists?
                               :already-registered
                               (let [base (clojure.string/replace %s #\"^file://\" \"\")
                                     pdir (clojure.java.io/file base %s)]
                                 (if (.isDirectory pdir)
                                   (do (llm-memory.core/register-root! store {:uri %s :plans-dir %s})
                                       (llm-memory.server.main/write-roots-config! store)
                                       (let [summary (llm-memory.index/reconcile! store %s)]
                                         (llm-memory.watcher/start-watcher! store %s)
                                         (str :registered-new \" \" (:new summary) \" files\")))
                                   :no-plans-dir))))"
                          (pr-str ruri) (pr-str ruri) (pr-str plans-dir)
                          (pr-str ruri) (pr-str plans-dir) (pr-str ruri) (pr-str ruri))
        result    (nrepl-eval conn code)]
    (cond
      (:error result)
      (log-proxy "ERROR auto-registering root:" (:error result))

      (= ":already-registered" (:value result))
      (log-proxy "root already registered:" ruri)

      (= ":no-plans-dir" (:value result))
      (log-proxy "root has no Plans/ directory, skipping auto-register:" ruri)

      :else
      (log-proxy "auto-registered root:" ruri "—" (:value result)))))

(defn- handle-tools-call [msg conn]
  (let [tool-name (get-in msg ["params" "name"])
        args      (get-in msg ["params" "arguments"] {})
        code      (tool-call->nrepl-code tool-name args)
        result    (nrepl-eval conn code)]
    (if (:error result)
      (write-json! {"jsonrpc" "2.0"
                    "id"      (get msg "id")
                    "result"  {"content" [{"type" "text"
                                           "text" (str "Error: " (:error result))}]
                               "isError" true}})
      (let [;; nREPL returns Clojure string literals — read-string to unwrap
            raw-val (or (:value result) "\"\"")
            text    (try (read-string raw-val) (catch Exception _ raw-val))]
        (write-json! {"jsonrpc" "2.0"
                      "id"      (get msg "id")
                      "result"  {"content" [{"type" "text"
                                             "text" (str text)}]}})))))

;; ---------------------------------------------------------------------------
;; Main loop
;; ---------------------------------------------------------------------------

;; Deferred-connection state.
;;
;; The proxy responds to `initialize` and `tools/list` immediately from
;; static data — no backend needed.  The server is launched in a background
;; thread and the nREPL connection is established lazily on the first
;; `tools/call`.  This eliminates the race where Claude Code's MCP init
;; timeout expires while the JVM is still booting.

(def ^:private server-ready
  "Promise delivered with true/false once ensure-server! completes."
  (promise))

(def ^:private nrepl-conn
  "Atom holding the nREPL connection map, or nil if not yet connected."
  (atom nil))

(defn- ensure-connected!
  "Block until the server is ready and an nREPL connection is established.
   Returns the connection, or nil if the server failed to start."
  []
  (or @nrepl-conn
      (locking nrepl-conn
        (or @nrepl-conn
            (let [ok? (deref server-ready 60000 :timeout)]
              (cond
                (= :timeout ok?)
                (do (log-proxy "ERROR: timed out waiting for server startup")
                    nil)

                (not ok?)
                (do (log-proxy "ERROR: server startup failed")
                    nil)

                :else
                (let [port (read-port)
                      conn (nrepl-connect port)]
                  (log-proxy "connected to plan server on port" (str port))
                  (nrepl-eval conn "(require '[llm-memory.core] '[llm-memory.tools] '[llm-memory.index] '[llm-memory.watcher] '[llm-memory.server.main])")
                  (ensure-root-registered! conn)
                  (reset! nrepl-conn conn)
                  conn)))))))

(defn -main []
  ;; Launch server startup in background — don't block the MCP protocol loop
  (future
    (try
      (deliver server-ready (ensure-server!))
      (catch Exception e
        (log-proxy "FATAL: server startup exception:" (str e))
        (deliver server-ready false))))

  ;; Enter the JSON-RPC loop immediately so initialize/tools-list
  ;; are answered before the JVM finishes booting
  (try
    (let [rdr (io/reader *in*)]
      (doseq [line (line-seq rdr)]
        (when-not (str/blank? line)
          (let [msg (json/parse-string line)]
            (case (get msg "method")
              "initialize"
              (handle-initialize msg)

              "notifications/initialized"
              nil ;; No response needed

              "tools/list"
              (handle-tools-list msg)

              "tools/call"
              (if-let [conn (ensure-connected!)]
                (handle-tools-call msg conn)
                (write-json! {"jsonrpc" "2.0"
                              "id"      (get msg "id")
                              "result"  {"content" [{"type" "text"
                                                     "text" "Error: Plan server is not available. Check ~/.local/share/winze/plan-server.log for details."}]
                                         "isError" true}}))

              ;; Unknown method — respond with error
              (when (get msg "id")
                (write-json! {"jsonrpc" "2.0"
                              "id"      (get msg "id")
                              "error"   {"code"    -32601
                                         "message" (str "Unknown method: " (get msg "method"))}})))))))
    (finally
      (when-let [conn @nrepl-conn]
        (.close (:socket conn)))
      (log-proxy "disconnected"))))

(-main)
