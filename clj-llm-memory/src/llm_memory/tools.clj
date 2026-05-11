(ns llm-memory.tools
  "Tool-level API — formatted markdown output matching the Python MCP server.

  These functions wrap the core API and return formatted markdown strings
  suitable for MCP tool responses. They are the interface that the Babashka
  MCP proxy will call."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [llm-memory.core :as mem]
            [llm-memory.generate :as gen]
            [llm-memory.index :as idx]
            [llm-memory.store.protocol :as store]
            [llm-memory.watcher :as watcher]
            [hyperfiddle.rcf :refer [tests]]
            [llm-memory.test-support :as ts]))

;; ---------------------------------------------------------------------------
;; Formatting helpers
;; ---------------------------------------------------------------------------

(defn- relevance-badge [relevance]
  (cond
    (> relevance 0.5) "strong"
    (> relevance 0.3) "partial"
    :else             "weak"))

(defn- format-annotations [result]
  (let [parts (cond-> []
                (:file/type result)   (conj (str "type=" (:file/type result)))
                (:file/status result) (conj (str "status=" (:file/status result)))
                (:file/group result)  (conj (str "group=" (:file/group result)))
                (:file/jira result)   (conj (str "jira=" (:file/jira result))))]
    (when (seq parts)
      (str "  (" (str/join ", " parts) ")"))))

(defn- format-search-result
  "Format a single search result with relevance badge and detail level."
  [result detail]
  (let [rel       (:file/path result)
        slug      (:chunk/slug result)
        relevance (:relevance result)
        badge     (relevance-badge relevance)
        ann       (or (format-annotations result) "")
        header    (format "### %s  (slug: [%s], relevance %.0f%% [%s])%s"
                          rel slug (* 100 relevance) badge ann)]
    (case detail
      :files   header
      :summary (let [text    (:chunk/text result)
                     preview (if (> (count text) 300)
                               (str (subs text 0 300) "…")
                               text)]
                 (str header "\n\n" preview "\n\n(" (count text) " chars)"))
      ;; :full (default)
      (str header "\n\n" (:chunk/text result)))))

;; ---------------------------------------------------------------------------
;; search-plans
;; ---------------------------------------------------------------------------

(defn search-plans
  "Semantic search — returns formatted markdown matching Python server output."
  [store query & [{:keys [n-results detail dedupe status doc-type group since root-uri]
                   :or   {n-results 5 detail :full dedupe true}}]]
  (let [results (mem/search store query
                            {:top      (min n-results 20)
                             :dedupe   dedupe
                             :root-uri root-uri
                             :status   status
                             :type     doc-type
                             :group    group
                             :since    since})]
    (if (empty? results)
      "No matching documents found."
      (str/join "\n\n---\n\n"
                (map #(format-search-result % detail) results)))))

;; ---------------------------------------------------------------------------
;; list-plans
;; ---------------------------------------------------------------------------

(defn list-plans
  "List all indexed file paths — returns formatted markdown."
  [store & [{:keys [root-uri]}]]
  (let [files (mem/list-files store {:root-uri root-uri})
        paths (sort (map :file/path files))]
    (if (empty? paths)
      "No documents indexed."
      (str (count paths) " indexed file(s):\n\n"
           (str/join "\n" paths)))))

;; ---------------------------------------------------------------------------
;; related-plans
;; ---------------------------------------------------------------------------

(defn related-plans
  "Group lookup with cross-references — returns formatted markdown."
  [store group & [{:keys [root-uri]}]]
  (let [{:keys [files cross-refs]} (mem/related store group {:root-uri root-uri})]
    (if (empty? files)
      (str "No documents found with group '" group "'.")
      (let [parts (atom [(str "## Group: " group "  (" (count files) " file(s))\n")])]
        (doseq [f (sort-by :file/path files)]
          (let [doc-type (:file/type f "?")
                fstatus  (:file/status f "?")
                jira-str (when (:file/jira f) (str "  [" (:file/jira f) "]"))]
            (swap! parts conj
                   (str "- **" (:file/path f) "** — type=" doc-type
                        ", status=" fstatus (or jira-str "")))))
        ;; Cross-references
        (when (seq cross-refs)
          (swap! parts conj
                 (str "\n## Cross-references: " (str/join ", " (sort cross-refs)) "\n"))
          (doseq [rg (sort cross-refs)]
            (let [{xfiles :files} (mem/related store rg {:root-uri root-uri})]
              (doseq [xf (sort-by :file/path xfiles)]
                (swap! parts conj (str "- " (:file/path xf)))))))
        (str/join "\n" @parts)))))

;; ---------------------------------------------------------------------------
;; recent-plans
;; ---------------------------------------------------------------------------

(defn recent-plans
  "Recently modified files — returns formatted markdown."
  [store & [{:keys [days doc-type status root-uri]
             :or   {days 7}}]]
  (let [files (mem/recent store {:days days :doc-type doc-type
                                 :status status :root-uri root-uri})]
    (if (empty? files)
      (str "No documents modified in the last " days " day(s).")
      (let [parts [(str "## Documents modified in the last " days
                        " day(s)  (" (count files) " file(s))\n")]]
        (str/join "\n"
                  (into parts
                        (map (fn [f]
                               (let [mod-str (some-> (:file/modified f)
                                                     (* 1000)
                                                     java.time.Instant/ofEpochMilli
                                                     (.atZone (java.time.ZoneId/of "UTC"))
                                                     .toLocalDate
                                                     str)
                                     grp-str (when (:file/group f)
                                               (str "  group=" (:file/group f)))]
                                 (str "- **" (:file/path f) "** — " (or mod-str "?")
                                      "  type=" (:file/type f "?") (or grp-str ""))))
                             files)))))))

;; ---------------------------------------------------------------------------
;; plans-status
;; ---------------------------------------------------------------------------

(defn plans-status
  "Health check — returns formatted markdown."
  [store]
  (let [st       (mem/status store)
        emb-info (:embedding st)
        hnsw     (mem/hnsw-health store)
        ws       (watcher/watcher-status)
        roots    (mem/list-roots store)
        hnsw-str (if (zero? (:missing hnsw))
                   (str "ok (" (:indexed hnsw) "/" (:total hnsw) ")")
                   (str "**DESYNC** — " (:missing hnsw) " missing ("
                        (:indexed hnsw) "/" (:total hnsw) ")"))]
    (str/join "\n"
              [(str "## Planning System Status\n")
               (str "- **Embedding provider**: " (:provider emb-info)
                    " (" (:model emb-info) ", " (:dims emb-info) "d)")
               (str "- **Store path**: " (:store-path st))
               (str "- **Total files**: " (:files st))
               (str "- **Total chunks**: " (:chunks st))
               (str "- **HNSW index**: " hnsw-str)
               (str "- **Registered roots**: " (:roots st))
               ""
               "### Roots"
               ""
               (str/join "\n"
                         (map (fn [r]
                                (let [wst (get ws (:root/uri r))
                                      watcher-str (if wst "running" "stopped")]
                                  (str "- **" (:root/name r) "** (" (:root/uri r)
                                       ") — watcher: " watcher-str)))
                              roots))])))

;; ---------------------------------------------------------------------------
;; index-plans
;; ---------------------------------------------------------------------------

(defn index-plans
  "Manual reconciliation/reindex trigger — returns formatted markdown."
  [store root-uri & [{:keys [reset regenerate-index]
                      :or   {reset false regenerate-index true}}]]
  (let [result (if reset
                 (idx/index-root! store root-uri)
                 (idx/reconcile! store root-uri))
        action (if reset "Reset and re-indexed" "Reconciled")
        summary (if reset
                  (str action " " (:files result) " file(s) → "
                       (:chunks result) " chunk(s).")
                  (str action ": "
                       (:unchanged result) " unchanged, "
                       (:modified result) " modified, "
                       (:renamed result) " renamed, "
                       (:renamed-modified result 0) " renamed+edited, "
                       (:new result) " new, "
                       (:gone result) " gone."
                       (when (seq (:errors result))
                         (str "\n\nErrors:\n"
                              (str/join "\n" (map #(str "- " (:op %) " " (:path %)
                                                        ": " (:error %))
                                                  (:errors result)))))))]
    (if regenerate-index
      (let [gen-fn (requiring-resolve 'llm-memory.generate/generate-all!)]
        (str summary "\n" (gen-fn store root-uri)))
      summary)))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(defn- setup!
  "Create test root with files and index them."
  []
  (let [root (ts/tmp-dir)
        s    (ts/fresh-store)
        ruri (ts/root-uri root)]
    (.mkdirs (io/file root "Plans/todo"))
    (.mkdirs (io/file root "Plans/complete/my-group"))
    (mem/register-root! s {:uri ruri :name "tst" :plans-dir "Plans"})
    (spit (io/file root "Plans/todo/FOO-CONTEXT.md")
          "---\nrelated: my-group\ntags: test\n---\n\n# Foo Context\n\n## Overview\n\nFoo context content about caching.\n\n## Details\n\nMore details here.")
    (.mkdirs (io/file root "Plans/complete/my-group"))
    (spit (io/file root "Plans/complete/my-group/PLAN.md")
          "# My Group Plan\n\n## Steps\n\nStep one do things.")
    (idx/index-root! s ruri)
    {:store s :root root :uri ruri}))

(tests
 "search-plans — returns formatted markdown with relevance badges"
 (let [{:keys [store]} (setup!)
       result (search-plans store "caching strategy" {:n-results 2 :detail :full})]
   (string? result) := true
   (str/includes? result "###") := true
   (str/includes? result "relevance") := true
   (boolean (re-find #"\[(?:strong|partial|weak)\]" result)) := true
   (store/disconnect! store))
 :rcf)

(tests
 "search-plans — :files detail returns headers only"
 (let [{:keys [store]} (setup!)
       result (search-plans store "caching" {:n-results 2 :detail :files})]
   (str/includes? result "###") := true
   (not (str/includes? result "More details here")) := true
   (store/disconnect! store))
 :rcf)

(tests
 "search-plans — :summary detail returns preview + char count"
 (let [{:keys [store]} (setup!)
       result (search-plans store "caching" {:n-results 2 :detail :summary})]
   (str/includes? result "chars)") := true
   (store/disconnect! store))
 :rcf)

(tests
 "list-plans — returns formatted file list"
 (let [{:keys [store]} (setup!)
       result (list-plans store)]
   (str/includes? result "2 indexed file(s)") := true
   (str/includes? result "todo/FOO-CONTEXT.md") := true
   (str/includes? result "complete/my-group/PLAN.md") := true
   (store/disconnect! store))
 :rcf)

(tests
 "related-plans — shows group files and cross-references"
 (let [{:keys [store]} (setup!)
       result (related-plans store "foo")]
   (str/includes? result "## Group: foo") := true
   (str/includes? result "todo/FOO-CONTEXT.md") := true
   (str/includes? result "Cross-references") := true
   (str/includes? result "my-group") := true
   (store/disconnect! store))
 :rcf)

(tests
 "recent-plans — shows recently modified files"
 (let [{:keys [store]} (setup!)
       result (recent-plans store {:days 1})]
   (str/includes? result "Documents modified") := true
   (str/includes? result "2 file(s)") := true
   (store/disconnect! store))
 :rcf)

(tests
 "plans-status — shows health info"
 (let [{:keys [store]} (setup!)
       result (plans-status store)]
   (str/includes? result "Planning System Status") := true
   (str/includes? result "inference4j") := true
   (str/includes? result "Total files") := true
   (str/includes? result "tst") := true
   (store/disconnect! store))
 :rcf)

(tests
 "index-plans — reconcile returns summary"
 (let [{:keys [store uri]} (setup!)
       result (index-plans store uri {:regenerate-index false})]
   (str/includes? result "Reconciled") := true
   (str/includes? result "unchanged") := true
   (store/disconnect! store))
 :rcf)
