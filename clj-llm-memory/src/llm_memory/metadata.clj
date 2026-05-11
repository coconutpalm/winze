(ns llm-memory.metadata
  "Infer document metadata from Plans/ path conventions.

  Covers ~95% of metadata fields without any frontmatter:
    - fm_status  from directory: dev/|todo/→active, complete/→complete, dev/|todo/deferred/→deferred
    - fm_type    from filename patterns: *-CONTEXT.md→context, AAO-*.md→jira, etc.
    - fm_group   from filename prefix or parent directory
    - fm_jira    via regex on filename"
  (:require [clojure.string :as str]
            [hyperfiddle.rcf :refer [tests]]))

(def ^:private excluded-dirs
  "Directories that are NOT valid group names."
  #{"dev" "todo" "complete" "reference" "releng" "deferred" "jira"})

(def ^:private bare-types
  "Bare filenames (without .md) that map directly to document types."
  {"CONTEXT" "context"
   "PLAN"    "plan"
   "STORY"   "story"
   "CODEMAP" "codemap"
   "REPORT"  "report"
   "RESULTS" "results"
   "INFO"    "info"})

(def ^:private type-suffixes
  "Suffixes to match in prefixed filenames (e.g. GPU-REPORT-CONTEXT.md)."
  ["CONTEXT" "PLAN" "STORY" "CODEMAP" "REPORT" "RESULTS"])

(defn infer-metadata
  "Derive type, status, group, and jira key from a Plans/ relative path.

  rel-path is relative to the Plans/ root (e.g. \"dev/CACHE-CONTEXT.md\"
  or \"complete/gpu-report/PLAN.md\").

  Returns a map with string values:
    {:fm/status \"active\", :fm/type \"context\", :fm/group \"cache\", :fm/jira \"AAO-30\"}"
  [rel-path]
  (let [parts (str/split rel-path #"/")
        name  (str/replace (last parts) #"\.md$" "")
        meta  (transient {})]

    ;; --- Status from directory ---
    (cond
      (= (first parts) "complete")
      (assoc! meta :fm/status "complete")

      (and (> (count parts) 1) (= (second parts) "deferred"))
      (assoc! meta :fm/status "deferred")

      (#{"dev" "todo"} (first parts))
      (assoc! meta :fm/status "active")

      (= (first parts) "reference")
      (assoc! meta :fm/status "active"))

    ;; --- Type from filename ---
    (cond
      ;; Jira documents: /jira/ path or AAO-* prefix
      (or (str/includes? rel-path "/jira/")
          (str/starts-with? name "AAO-"))
      (do
        (assoc! meta :fm/type "jira")
        (when-let [m (re-find #"(AAO-\d+)" name)]
          (assoc! meta :fm/jira (if (string? m) m (second m))))
        ;; Group from parent directory (e.g. complete/all-instance-report/AAO-28.md)
        (when (>= (count parts) 3)
          (let [parent (get parts (- (count parts) 2))]
            (when-not (contains? excluded-dirs parent)
              (assoc! meta :fm/group parent)))))

      ;; INDEX, COMPLETED-WORK
      (or (= name "INDEX") (= name "COMPLETED-WORK"))
      (assoc! meta :fm/type "index")

      ;; OPEN-QUESTIONS
      (= name "OPEN-QUESTIONS")
      (assoc! meta :fm/type "tracker")

      ;; Bare type name (e.g. complete/gpu-report/CONTEXT.md)
      (contains? bare-types name)
      (do
        (assoc! meta :fm/type (get bare-types name))
        (when (>= (count parts) 2)
          (let [parent (get parts (- (count parts) 2))]
            (when-not (contains? excluded-dirs parent)
              (assoc! meta :fm/group parent)))))

      ;; -INFO suffix (e.g. DEPLOY-INFO.md)
      (str/ends-with? name "-INFO")
      (assoc! meta :fm/type "info")

      ;; Prefixed type (e.g. GPU-REPORT-CONTEXT.md, CACHE_GAP_DETECT_PLAN2.md)
      :else
      (let [name-stripped (str/replace name #"\d+$" "")]
        (some (fn [suffix]
                (when (or (str/ends-with? name-stripped (str "-" suffix))
                          (str/ends-with? name-stripped (str "_" suffix)))
                  (assoc! meta :fm/type (str/lower-case suffix))
                  ;; Group = prefix before the type suffix
                  (let [prefix-len (- (count name-stripped) (inc (count suffix)))
                        prefix     (when (pos? prefix-len)
                                     (subs name-stripped 0 prefix-len))]
                    (when (seq prefix)
                      (assoc! meta :fm/group (-> prefix
                                                 str/lower-case
                                                 (str/replace "_" "-")))))
                  true))
              type-suffixes)))

    (persistent! meta)))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(tests
 "infer-metadata — status from directory"
 (:fm/status (infer-metadata "dev/FOO-CONTEXT.md"))                := "active"
 (:fm/status (infer-metadata "dev/deferred/FOO-PLAN.md"))          := "deferred"
 (:fm/status (infer-metadata "complete/foo/PLAN.md"))              := "complete"
 (:fm/status (infer-metadata "reference/DATA-VALIDATION-REPORT.md")) := "active"
 :rcf)

(tests
 "infer-metadata — type from bare filename"
 (:fm/type (infer-metadata "complete/gpu-report/CONTEXT.md"))  := "context"
 (:fm/type (infer-metadata "complete/gpu-report/PLAN.md"))     := "plan"
 (:fm/type (infer-metadata "complete/gpu-report/STORY.md"))    := "story"
 (:fm/type (infer-metadata "complete/gpu-report/CODEMAP.md"))  := "codemap"
 (:fm/type (infer-metadata "complete/gpu-report/REPORT.md"))   := "report"
 (:fm/type (infer-metadata "complete/gpu-report/RESULTS.md"))  := "results"
 (:fm/type (infer-metadata "complete/gpu-report/INFO.md"))     := "info"
 :rcf)

(tests
 "infer-metadata — group from parent directory (bare types)"
 (:fm/group (infer-metadata "complete/gpu-report/PLAN.md"))    := "gpu-report"
 (:fm/group (infer-metadata "complete/cache-gap-detect/CONTEXT.md")) := "cache-gap-detect"
 (contains? (infer-metadata "dev/CONTEXT.md") :fm/group) := false
 :rcf)

(tests
 "infer-metadata — type from prefixed filename"
 (:fm/type  (infer-metadata "dev/CACHE-GAP-DETECT-CONTEXT.md")) := "context"
 (:fm/group (infer-metadata "dev/CACHE-GAP-DETECT-CONTEXT.md")) := "cache-gap-detect"
 (:fm/type  (infer-metadata "dev/GPU-REPORT-PLAN.md"))          := "plan"
 (:fm/group (infer-metadata "dev/GPU-REPORT-PLAN.md"))          := "gpu-report"
 (:fm/type  (infer-metadata "dev/CACHE_PLAN2.md"))              := "plan"
 (:fm/group (infer-metadata "dev/CACHE_PLAN2.md"))              := "cache"
 :rcf)

(tests
 "infer-metadata — jira documents"
 (:fm/type (infer-metadata "dev/jira/AAO-66.md"))                 := "jira"
 (:fm/jira (infer-metadata "dev/jira/AAO-66.md"))                 := "AAO-66"
 (:fm/type (infer-metadata "complete/gpu-report/AAO-30.md"))      := "jira"
 (:fm/jira (infer-metadata "complete/gpu-report/AAO-30.md"))      := "AAO-30"
 (:fm/group (infer-metadata "complete/gpu-report/AAO-30.md"))     := "gpu-report"
 (contains? (infer-metadata "dev/jira/AAO-66.md") :fm/group)     := false
 :rcf)

(tests
 "infer-metadata — special filenames"
 (:fm/type (infer-metadata "dev/INDEX.md"))                   := "index"
 (:fm/type (infer-metadata "COMPLETED-WORK.md"))              := "index"
 (:fm/type (infer-metadata "dev/OPEN-QUESTIONS.md"))          := "tracker"
 :rcf)

(tests
 "infer-metadata — -INFO suffix"
 (:fm/type (infer-metadata "dev/DEPLOY-INFO.md")) := "info"
 :rcf)
