(ns llm-memory.generate
  "Auto-generate INDEX.md and STATUS.md from indexed file metadata.

  Ports the Python `generate_index()` and `generate_status()` functions.
  These files are regenerated after every index operation."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [llm-memory.frontmatter :as fm]
            [llm-memory.metadata :as meta']
            [hyperfiddle.rcf :refer [tests]]
            [llm-memory.test-support :as ts]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- collect-file-metadata
  "Collect metadata for all .md files under a Plans directory.
   Returns a seq of maps with :rel, :title, :fm/*, :file-modified, :file-modified-str."
  [^java.io.File plans-dir]
  (->> (file-seq plans-dir)
       (filter #(and (.isFile ^java.io.File %)
                     (str/ends-with? (.getName ^java.io.File %) ".md")))
       sort
       (mapv (fn [^java.io.File f]
               (let [rp       (.toString (.relativize (.toPath plans-dir) (.toPath f)))
                     text     (slurp f)
                     [raw-fm body] (fm/parse-frontmatter text)
                     fm-meta  (fm/frontmatter->metadata raw-fm)
                     inferred (meta'/infer-metadata rp)
                     merged   (merge inferred fm-meta)
                     mtime    (quot (.lastModified f) 1000)
                     ;; Extract H1 title
                     title    (some #(when (str/starts-with? % "# ")
                                       (subs % 2))
                                    (str/split-lines body))]
                 (assoc merged
                        :rel rp
                        :title (or title "")
                        :file-modified mtime
                        :file-modified-str
                        (str (.toLocalDate
                              (.atZone (java.time.Instant/ofEpochSecond mtime)
                                       (java.time.ZoneId/of "UTC"))))))))))

;; ---------------------------------------------------------------------------
;; INDEX.md
;; ---------------------------------------------------------------------------

(def ^:private dir-labels
  {"todo"      "Active Development (`todo/`)"
   "dev"       "Active Development (`dev/`)"
   "reference" "Reference (`reference/`)"
   "complete"  "Completed (`complete/`)"})

(def ^:private dir-order ["todo" "dev" "reference" "complete"])

(defn generate-index
  "Generate INDEX.md from file metadata, grouped by directory and group.
   Writes to plans-dir/INDEX.md. Returns a summary string."
  [^java.io.File plans-dir]
  (let [all-meta  (collect-file-metadata plans-dir)
        today     (str (java.time.LocalDate/now))
        ;; Group by top-dir → group → [files]
        dir-groups (reduce (fn [acc m]
                             (let [top-dir (first (str/split (:rel m) #"/"))
                                   group   (or (:fm/group m) "_ungrouped")]
                               (update-in acc [top-dir group] (fnil conj []) m)))
                           {}
                           all-meta)
        lines (atom [(str "# Plans Index")
                     ""
                     (str "*Auto-generated on " today " — do not edit manually.*")
                     ""])]

    (doseq [d dir-order
            :when (contains? dir-groups d)]
      (swap! lines conj (str "## " (get dir-labels d d)))
      (swap! lines conj "")
      (doseq [group-name (sort (keys (get dir-groups d)))]
        (let [files (get-in dir-groups [d group-name])
              label (if (= group-name "_ungrouped")
                      "Ungrouped"
                      (let [jira-keys (->> files
                                           (keep :fm/jira)
                                           set
                                           sort)]
                        (if (seq jira-keys)
                          (str group-name " (" (str/join ", " jira-keys) ")")
                          group-name)))]
          (swap! lines conj (str "### " label))
          (doseq [m (sort-by :rel files)]
            (let [type-str  (when (:fm/type m) (str " [" (:fm/type m) "]"))
                  title-str (when (seq (:title m)) (str " — " (:title m)))]
              (swap! lines conj (str "- `" (:rel m) "`" (or type-str "") (or title-str "")))))
          (swap! lines conj ""))))

    (let [content (str/join "\n" @lines)
          out     (io/file plans-dir "INDEX.md")]
      (spit out content)
      (str "Generated " out " (" (count all-meta) " files)"))))

;; ---------------------------------------------------------------------------
;; STATUS.md
;; ---------------------------------------------------------------------------

(defn generate-status
  "Generate STATUS.md showing active work, recent completions, and deferred items.
   Writes to plans-dir/STATUS.md. Returns a summary string."
  [^java.io.File plans-dir]
  (let [all-meta (collect-file-metadata plans-dir)
        today    (str (java.time.LocalDate/now))
        lines    (atom [(str "# Plans Status")
                        ""
                        (str "*Auto-generated on " today " — do not edit manually.*")
                        ""])

        ;; Active work by group
        active (->> all-meta (filter #(= (:fm/status %) "active")))
        active-groups (group-by #(or (:fm/group %) "_ungrouped") active)]

    (swap! lines conj "## Active Work" "")
    (if (seq active-groups)
      (doseq [gname (sort (keys active-groups))]
        (let [files (get active-groups gname)
              jira-keys (->> files (keep :fm/jira) set sort)
              jira-str  (when (seq jira-keys)
                          (str " (" (str/join ", " jira-keys) ")"))]
          (swap! lines conj (str "- **" gname "**" (or jira-str "")
                                 ": " (count files) " file(s)"))))
      (swap! lines conj "*No active work items.*"))
    (swap! lines conj "")

    ;; Recently completed (last 14 days)
    (let [cutoff (- (quot (System/currentTimeMillis) 1000) (* 14 86400))
          recent (->> all-meta
                      (filter #(and (= (:fm/status %) "complete")
                                    (>= (:file-modified %) cutoff)))
                      (group-by #(or (:fm/group %) "_ungrouped")))
          ;; Most recent modification per group
          recent-sorted (->> recent
                             (map (fn [[g files]]
                                    [g (apply max (map :file-modified files))
                                     (:file-modified-str (first (sort-by :file-modified > files)))]))
                             (sort-by second >))]
      (swap! lines conj "## Recently Completed (last 14 days)" "")
      (if (seq recent-sorted)
        (doseq [[g _ mod-str] recent-sorted]
          (swap! lines conj (str "- **" g "** (last modified " mod-str ")")))
        (swap! lines conj "*None in the last 14 days.*"))
      (swap! lines conj ""))

    ;; Deferred
    (let [deferred-groups (->> all-meta
                               (filter #(= (:fm/status %) "deferred"))
                               (map #(or (:fm/group %) "_ungrouped"))
                               set
                               sort)]
      (swap! lines conj "## Deferred" "")
      (if (seq deferred-groups)
        (doseq [g deferred-groups]
          (swap! lines conj (str "- " g)))
        (swap! lines conj "*None.*"))
      (swap! lines conj ""))

    ;; Summary stats
    (let [statuses (frequencies (map #(or (:fm/status %) "unknown") all-meta))]
      (swap! lines conj "## Summary" "")
      (swap! lines conj (str "- **Total files**: " (count all-meta)))
      (doseq [s (sort (keys statuses))]
        (swap! lines conj (str "- **" s "**: " (get statuses s)))))

    (let [content (str/join "\n" @lines)
          out     (io/file plans-dir "STATUS.md")]
      (spit out content)
      (str "Generated " out))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn generate-all!
  "Generate both INDEX.md and STATUS.md for a root.
   Returns a summary string."
  [store root-uri]
  (let [root-info (first (llm-memory.store.protocol/query
                          store
                          '[:find ?dir
                            :in $ ?ruri
                            :where
                            [?r :root/uri ?ruri]
                            [?r :root/plans-dir ?dir]]
                          {:ruri root-uri}))
        plans-dir-name (or (first root-info) "Plans")
        base-path      (str/replace root-uri #"^file://" "")
        plans-dir      (io/file base-path plans-dir-name)]
    (str (generate-index plans-dir) "\n"
         (generate-status plans-dir))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(defn- setup-plans-dir!
  "Create a temp Plans/ directory with test files for generate tests."
  []
  (let [root (ts/tmp-dir)]
    (.mkdirs (io/file root "Plans/todo"))
    (.mkdirs (io/file root "Plans/complete/my-group"))
    (spit (io/file root "Plans/todo/FOO-CONTEXT.md")
          "---\nrelated: my-group\ntags: test\n---\n\n# Foo Context\n\n## Overview\n\nFoo context content about caching.\n\n## Details\n\nMore details here.")
    (spit (io/file root "Plans/complete/my-group/PLAN.md")
          "# My Group Plan\n\n## Steps\n\nStep one do things.")
    (io/file root "Plans")))

(tests
 "generate-index — creates INDEX.md with directory grouping"
 (let [plans-dir (setup-plans-dir!)
       result    (generate-index plans-dir)]
   (str/includes? result "Generated") := true
   (.exists (io/file plans-dir "INDEX.md")) := true
   (let [content (slurp (io/file plans-dir "INDEX.md"))]
     (str/includes? content "# Plans Index") := true
     (str/includes? content "Active Development") := true
     (str/includes? content "Completed") := true))
 :rcf)

(tests
 "generate-status — creates STATUS.md with active/completed/deferred sections"
 (let [plans-dir (setup-plans-dir!)
       result    (generate-status plans-dir)]
   (str/includes? result "Generated") := true
   (.exists (io/file plans-dir "STATUS.md")) := true
   (let [content (slurp (io/file plans-dir "STATUS.md"))]
     (str/includes? content "# Plans Status") := true
     (str/includes? content "Active Work") := true
     (str/includes? content "Summary") := true
     (str/includes? content "Total files") := true))
 :rcf)
