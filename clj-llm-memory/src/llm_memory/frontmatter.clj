(ns llm-memory.frontmatter
  "YAML frontmatter parsing for Plan markdown files.

  Extracts optional YAML between `---` delimiters at the top of a file.
  Frontmatter values override inferred metadata. List fields (related, tags)
  are serialized as comma-separated strings for storage compatibility."
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [hyperfiddle.rcf :refer [tests]]))

(defn parse-frontmatter
  "Extract YAML frontmatter if present.
   Returns [metadata-map, remaining-body-text].
   If no frontmatter, returns [{}, original-text]."
  [text]
  (if (str/starts-with? text "---\n")
    (let [end (str/index-of text "\n---\n" 4)]
      (if end
        (let [yaml-str (subs text 4 end)
              fm       (or (yaml/parse-string yaml-str) {})]
          [fm (subs text (+ end 5))])
        [{} text]))
    [{} text]))

(def ^:private key-map
  "Maps frontmatter YAML keys to storage-prefixed keys."
  {"type"       :fm/type
   "status"     :fm/status
   "group"      :fm/group
   "jira"       :fm/jira
   "created"    :fm/created
   "updated"    :fm/updated
   "related"    :fm/related
   "supersedes" :fm/supersedes
   "tags"       :fm/tags})

(defn- serialize-value
  "Convert a frontmatter value to a string for storage.
   Lists become comma-separated. Dates become ISO strings."
  [v]
  (cond
    (sequential? v)              (str/join "," (map str v))
    (instance? java.util.Date v) (let [sdf (java.text.SimpleDateFormat. "yyyy-MM-dd")]
                                   (.setTimeZone sdf (java.util.TimeZone/getTimeZone "UTC"))
                                   (.format sdf v))
    :else                        (str v)))

(defn frontmatter->metadata
  "Convert parsed frontmatter dict to storage-compatible metadata map.
   Returns a map with namespaced :fm/* keys."
  [fm]
  (reduce-kv
   (fn [acc k v]
     (if-let [dest-key (get key-map (name k))]
       (assoc acc dest-key (serialize-value v))
       acc))
   {}
   fm))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(tests
 "parse-frontmatter — extracts YAML between --- delimiters"
 (let [[fm body] (parse-frontmatter
                  "---\ncreated: 2026-03-20\ntype: context\ntags: [a, b]\n---\n\n# Body")]
   (some? fm) := true
   (:type fm) := "context"
   (str/starts-with? body "\n# Body") := true)
 :rcf)

(tests
 "parse-frontmatter — no frontmatter returns empty map"
 (let [[fm body] (parse-frontmatter "# No Frontmatter\n\nJust content.")]
   fm := {}
   body := "# No Frontmatter\n\nJust content.")
 :rcf)

(tests
 "frontmatter->metadata — converts to :fm/* keys"
 (let [fm {:type "plan" :status "active" :group "my-group"
           :related ["a" "b"] :tags ["x" "y"]}
       m  (frontmatter->metadata fm)]
   (:fm/type m)    := "plan"
   (:fm/status m)  := "active"
   (:fm/group m)   := "my-group"
   (:fm/related m) := "a,b"
   (:fm/tags m)    := "x,y")
 :rcf)
