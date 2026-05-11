(ns llm-memory.ui.search
  "Live search: debounced vector search → Hiccup HTML → SWT Browser widget."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [hyperfiddle.rcf :refer [tests]]
            [llm-memory.ui.hiccup :as hiccup]
            [llm-memory.core :as core]
            [llm-memory.frontmatter :as frontmatter]
            [llm-memory.ui.resources :as resources]
            [llm-memory.ui.theme :as theme]
            [llm-memory.server.main :as server]
            [llm-memory.store.protocol :as store]
            [ui.SWT :refer [async-exec!]])
  (:import [java.io File]
           [java.net URLEncoder]
           [java.util.concurrent Executors ScheduledFuture TimeUnit]))

;; ---------------------------------------------------------------------------
;; Brand palette (derived from the live theme registry)
;; ---------------------------------------------------------------------------

;; Zero-arg fn, not a static map — hex strings must be re-read on every
;; Browser render so `theme/reload-theme!` picks up palette edits
;; without requiring a namespace reload here.
(defn- colors []
  {:lavender       (theme/hex :lavender)
   :amethyst       (theme/hex :amethyst)
   :deep-violet    (theme/hex :deep-violet)
   :royal-purple   (theme/hex :royal-purple)
   :indigo         (theme/hex :indigo)
   :deep-amethyst  (theme/hex :deep-amethyst)
   :obsidian       (theme/hex :obsidian)
   :mine-shaft     (theme/hex :mine-shaft)
   :bedrock        (theme/hex :bedrock)
   :crystal-white  (theme/hex :crystal-white)
   :pure-white     (theme/hex :pure-white)})

;; ---------------------------------------------------------------------------
;; CSS
;; ---------------------------------------------------------------------------

(defn page-css []
  ;; Bind the live palette locally so the string interpolations below
  ;; remain unchanged — `(:kw colors)` reads from this local map. Called
  ;; once per render via `(colors)`, so `theme/reload-theme!` propagates.
  (let [colors (colors)]
    (h/raw
     (str
      "* { margin: 0; padding: 0; box-sizing: border-box; }
    ::selection {
      background: " (:lavender colors) ";
      color: " (:bedrock colors) ";
    }
    body {
      font-family: Inter, system-ui, -apple-system, sans-serif;
      font-size: 14px; font-weight: 400; line-height: 1.6;
      background: " (:mine-shaft colors) ";
      color: " (:crystal-white colors) ";
      padding: 16px;
    }
    .header {
      font-size: 13px; font-weight: 500;
      color: " (:amethyst colors) ";
      margin-bottom: 16px;
      padding-bottom: 8px;
      border-bottom: 1px solid " (:obsidian colors) ";
    }
    .result-card {
      background: " (:obsidian colors) ";
      border: 1px solid " (:deep-amethyst colors) ";
      border-radius: 6px;
      padding: 12px 16px;
      margin-bottom: 12px;
    }
    .result-card:last-child { margin-bottom: 0; }
    .result-header {
      display: flex; justify-content: space-between; align-items: center;
      margin-bottom: 8px;
    }
    .file-path {
      font-size: 12px; font-weight: 500;
      color: " (:amethyst colors) ";
      font-family: 'JetBrains Mono', 'Fira Code', 'Noto Sans Mono', Menlo, monospace;
      cursor: pointer; text-decoration: none;
    }
    .file-path:hover { text-decoration: underline; }
    .badge {
      font-size: 11px; font-weight: 500;
      padding: 2px 8px; border-radius: 10px;
      color: " (:mine-shaft colors) ";
    }
    .badge-strong  { background: " (:lavender colors) "; }
    .badge-partial { background: " (:deep-violet colors) "; color: " (:crystal-white colors) "; }
    .badge-weak    { background: " (:indigo colors) "; color: " (:crystal-white colors) "; }
    .pills { margin-bottom: 8px; display: flex; gap: 6px; flex-wrap: wrap; }
    .pill {
      font-size: 11px; font-weight: 500;
      padding: 2px 8px; border-radius: 4px;
      background: " (:royal-purple colors) ";
      color: " (:crystal-white colors) ";
    }
    .result-body {
      font-size: 13px; line-height: 1.7;
      color: " (:crystal-white colors) ";
    }
    .result-body h1, .result-body h2, .result-body h3,
    .result-body h4, .result-body h5, .result-body h6 {
      font-weight: 700; margin: 12px 0 4px 0;
    }
    .result-body h1 { font-size: 24px; color: " (:lavender colors) "; }
    .result-body h2 { font-size: 20px; color: " (:amethyst colors) "; }
    .result-body h3 { font-size: 17px; color: " (:amethyst colors) "; }
    .result-body h4 { font-size: 15px; color: " (:deep-violet colors) "; }
    .result-body h5 { font-size: 13px; font-style: italic; color: " (:royal-purple colors) "; }
    .result-body h6 { font-size: 13px; font-weight: 400; font-style: italic; color: " (:royal-purple colors) "; }
    .result-body strong { color: " (:pure-white colors) "; }
    .result-body blockquote {
      font-style: italic; color: " (:deep-violet colors) ";
      border-left: 3px solid " (:deep-violet colors) ";
      padding-left: 12px; margin: 6px 0;
    }
    .result-body blockquote strong {
      color: inherit;
    }
    .result-body a { color: " (:amethyst colors) "; text-decoration: none; }
    .result-body a:hover { text-decoration: underline; }
    .result-body a[href^=\"wiki:\"] { color: " (:lavender colors) "; }
    .result-body code {
      font-family: 'JetBrains Mono', 'Fira Code', 'Noto Sans Mono', Menlo, monospace;
      font-size: 12px;
      background: " (:bedrock colors) ";
      color: " (:lavender colors) ";
      padding: 1px 5px; border-radius: 3px;
    }
    .result-body pre {
      background: " (:bedrock colors) ";
      border: 1px solid " (:obsidian colors) ";
      border-radius: 4px;
      padding: 10px 12px; margin: 8px 0;
      overflow-x: auto;
      line-height: normal;
    }
    .result-body pre code {
      background: none; padding: 0;
    }
    .result-body ul, .result-body ol { padding-left: 32px; margin: 6px 0; }
    .result-body li { margin: 2px 0; }
    .result-body li > input[type=\"checkbox\"],
    .result-body li > p > input[type=\"checkbox\"] { margin-right: 6px; }
    .result-body p { margin: 6px 0; }
    .result-body table {
      border-collapse: collapse; margin: 8px 0; width: 100%;
    }
    .result-body th, .result-body td {
      border: 1px solid " (:deep-amethyst colors) ";
      padding: 4px 8px; font-size: 12px; text-align: left;
    }
    .result-body th {
      background: " (:deep-amethyst colors) ";
      font-weight: 500;
    }
    .result-header-left { display: flex; align-items: center; gap: 6px; min-width: 0; }
    .status-indicator { font-size: 14px; flex-shrink: 0; }
    .status-active   { color: " (:deep-violet colors) "; }
    .status-complete { color: #66BB6A; }
    .created-date { font-size: 11px; color: " (:deep-violet colors) "; margin-left: 6px; }
    a.pill { text-decoration: none; cursor: pointer; color: inherit; }
    a.pill:hover { opacity: 0.7; }
    a.pill.pill-active { background: " (:lavender colors) "; color: " (:obsidian colors) "; }
    .empty {
      text-align: center; padding: 80px 20px;
      color: " (:deep-violet colors) ";
      font-size: 16px; font-weight: 500;
    }
    .no-results {
      text-align: center; padding: 60px 20px;
      color: " (:deep-violet colors) ";
      font-size: 14px;
    }
    .welcome {
      max-width: 540px; margin: 60px auto; padding: 0 20px;
    }
    .welcome h1 {
      font-size: 32px; font-weight: 700; letter-spacing: -0.5px;
      color: " (:lavender colors) ";
      margin-bottom: 6px;
    }
    .tagline {
      font-size: 15px; font-style: italic;
      color: " (:amethyst colors) ";
      margin-bottom: 20px;
    }
    .intro {
      font-size: 14px; line-height: 1.7;
      color: " (:crystal-white colors) ";
      margin-bottom: 28px;
    }
    .actions { display: flex; flex-direction: column; gap: 10px; margin-bottom: 32px; }
    a.primary, a.secondary {
      display: inline-block; padding: 9px 20px; border-radius: 6px;
      font-size: 14px; font-weight: 500; text-decoration: none;
      cursor: pointer; text-align: center;
    }
    a.primary {
      background: " (:amethyst colors) ";
      color: " (:obsidian colors) ";
    }
    a.primary:hover { opacity: 0.85; }
    a.secondary {
      border: 1px solid " (:deep-amethyst colors) ";
      color: " (:lavender colors) ";
      background: transparent;
    }
    a.secondary:hover { background: " (:obsidian colors) "; }
    .current-roots { margin-bottom: 24px; }
    .current-roots h3 {
      font-size: 12px; font-weight: 600; text-transform: uppercase;
      letter-spacing: 0.06em; color: " (:deep-violet colors) ";
      margin-bottom: 8px;
    }
    .current-roots ul { list-style: none; padding: 0; }
    .current-roots li {
      font-size: 13px; padding: 4px 0;
      color: " (:crystal-white colors) ";
    }
    .current-roots .path {
      font-family: 'JetBrains Mono', 'Fira Code', 'Noto Sans Mono', Menlo, monospace;
      font-size: 11px; color: " (:deep-violet colors) ";
    }
    .footnote {
      font-size: 11px; color: " (:deep-violet colors) ";
      margin-top: 24px;
    }
    .footnote code {
      font-family: 'JetBrains Mono', 'Fira Code', 'Noto Sans Mono', Menlo, monospace;
      background: " (:bedrock colors) ";
      color: " (:lavender colors) ";
      padding: 1px 5px; border-radius: 3px;
    }
    .no-roots-hint {
      margin-top: 16px; font-size: 12px; color: " (:deep-violet colors) ";
    }
    .no-roots-hint a { color: " (:amethyst colors) "; text-decoration: none; }
    .no-roots-hint a:hover { text-decoration: underline; }
    .frontmatter-block {
      font-family: 'JetBrains Mono', 'Fira Code', 'Noto Sans Mono', Menlo, monospace;
      font-size: 12px; line-height: 1.5;
      background: " (:bedrock colors) ";
      color: " (:deep-violet colors) ";
      border: 1px solid " (:obsidian colors) ";
      border-radius: 4px;
      padding: 10px 12px; margin-bottom: 16px;
      overflow-x: auto; white-space: pre;
    }"))))

;; ---------------------------------------------------------------------------
;; Query parsing
;; ---------------------------------------------------------------------------

(def ^:private status-values #{"active" "complete" "deferred"})
(def ^:private type-values   #{"context" "plan" "story" "report" "codemap"
                               "results" "info" "jira" "index" "tracker"})
(def ^:private jira-pattern  #"(?i)AAO-\d+")

(defn- classify-tag
  "Map a #tag value to its [filter-key value] pair."
  [tag]
  (let [v (str/lower-case tag)]
    (cond
      (status-values v)             [:status v]
      (type-values v)               [:type v]
      (re-matches jira-pattern tag) [:jira (str/upper-case tag)]
      :else                         [:keyword v])))

(defn- parse-query
  "Parse a search string into {:text \"...\" :filters {:status \"...\" ...}}.
   Tokens prefixed with # are metadata filters; the rest is the semantic query."
  [raw]
  (let [trimmed (str/trim raw)]
    (if (str/blank? trimmed)
      {:text "" :filters {}}
      (let [tokens              (str/split trimmed #"\s+")
            {tags true, text false} (group-by #(str/starts-with? % "#") tokens)
            filters             (into {} (map (fn [t] (classify-tag (subs t 1)))) tags)]
        {:text    (str/join " " (or text []))
         :filters filters}))))

;; ---------------------------------------------------------------------------
;; Result card components
;; ---------------------------------------------------------------------------

(defn- relevance-class
  "Map a relevance score (0–1) to a badge CSS class."
  [relevance]
  (cond
    (>= relevance 0.6) "badge badge-strong"
    (>= relevance 0.4) "badge badge-partial"
    :else              "badge badge-weak"))

(defn- metadata-pills
  "Render clickable metadata pills for group, related, and tags."
  [result active-filters]
  (let [group-pills   (when (:file/group result)
                        [(:file/group result)])
        related-pills (when (:file/related result)
                        (map str/trim (str/split (:file/related result) #",")))
        tag-pills     (when (:file/tags result)
                        (map str/trim (str/split (:file/tags result) #",")))
        all-pills     (concat (or group-pills [])
                              (or related-pills [])
                              (or tag-pills []))
        active-vals   (set (vals active-filters))]
    (when (seq all-pills)
      [:div.pills
       (for [p all-pills]
         [:a {:class (str "pill" (when (active-vals p) " pill-active"))
              :href  (str "winze:search?q="
                          (URLEncoder/encode (str "#" p) "UTF-8"))}
          (str "#" p)])])))

(defn- file-header
  "Render the standard file header: status indicator, path, date, and pills.
   When `relevance` is present, also renders the percentage badge."
  [{:keys [file/path root/uri relevance] :as metadata} active-filters]
  [:div
   [:div.result-header
    [:div.result-header-left
     [:span {:class (str "status-indicator"
                         (case (:file/status metadata)
                           "active"   " status-active"
                           "complete" " status-complete"
                           ""))}
      (case (:file/status metadata)
        "active"   "☐"
        "complete" "✓"
        "")]
     [:a.file-path {:href (str "winze:open-file?root="
                               (URLEncoder/encode (or uri "") "UTF-8")
                               "&path="
                               (URLEncoder/encode (or path "") "UTF-8"))}
      (or path "—")]
     (when (:file/created metadata)
       [:span.created-date (str "(" (:file/created metadata) ")")])]
    (when relevance
      [:span {:class (relevance-class relevance)}
       (format "%.0f%%" (* 100.0 relevance))])]
   (metadata-pills metadata active-filters)])

(defn result-card
  "Render a single search result as a Hiccup vector."
  [{:keys [chunk/text] :as result} active-filters]
  [:div.result-card
   (file-header result active-filters)
   [:div.result-body (hiccup/md->hiccup (or text ""))]])

(defn card-html
  "Render a single search result as a self-contained HTML string.
  Suitable for embedding in a mini Browser widget (content assist, link preview)."
  ([result] (card-html result {}))
  ([result active-filters]
   (str
    (h/html
     [:html
      [:head [:style (page-css)]]
      [:body (result-card result active-filters)]]))))

;; ---------------------------------------------------------------------------
;; Page assembly
;; ---------------------------------------------------------------------------

(defn- results-page
  "Build a full HTML page from search results."
  [results query-string filters]
  (str
   (h/html
    [:html
     [:head
      [:meta {:charset "UTF-8"}]
      [:style (page-css)]]
     [:body
      [:div.header
       (str (count results) " result" (when (not= 1 (count results)) "s")
            " for \"" query-string "\"")]
      (if (empty? results)
        [:div.no-results "No matching documents found."]
        (for [r results]
          (result-card r filters)))]])))

(defn- root-abs-path
  "Resolve absolute filesystem path for a relative file within a root."
  [root-uri plans-dir rel-path]
  (let [base (str/replace root-uri #"^file://" "")]
    (str base "/" plans-dir "/" rel-path)))

(defn- home-md-paths
  "Return absolute paths where home.md could be created, one per registered root."
  []
  (try
    (let [store (server/store)
          roots (core/list-roots store)]
      (mapv (fn [{:keys [root/uri root/plans-dir]}]
              (root-abs-path uri plans-dir "home.md"))
            roots))
    (catch Throwable _ [])))

(defn welcome-page
  "Welcome tab page. `registered-roots` is a seq of root maps with :root/name and :root/uri."
  [registered-roots]
  (str
   (h/html
    [:html
     [:head
      [:meta {:charset "UTF-8"}]
      [:style (page-css)]]
     [:body
      [:div.welcome
       [:h1 "Winze"]
       [:p.tagline "Nothing forgotten. Everything found."]
       [:p.intro
        "Winze indexes your markdown planning documents and makes them "
        "searchable by meaning, not just keywords. "
        "Point it at a folder of .md files to get started."]
       [:div.actions
        [:a.primary  {:href "winze:register-root"}    "Add a folder\u2026"]
        [:a.secondary {:href "winze:install-sample-kb"} "Try the sample knowledge base"]]
       (when (seq registered-roots)
         [:div.current-roots
          [:h3 "Folders you\u2019ve added"]
          [:ul
           (for [{:root/keys [name uri]} registered-roots]
             [:li
              [:strong name] " "
              [:span.path (str/replace uri #"^file://" "")]])]])
       [:p.footnote
        "Or run " [:code "/register-plans"] " from Claude Code."]]]])))

(tests
 "welcome-page contains required pseudo-URLs"
 (let [html (welcome-page [])]
   (str/includes? html "winze:register-root")) := true
 (let [html (welcome-page [])]
   (str/includes? html "winze:install-sample-kb")) := true
 "welcome-page renders registered roots block"
 (let [html (welcome-page [{:root/name "X" :root/uri "file:///foo"}])]
   (str/includes? html "Folders you")) := true
 "welcome-page omits roots block when empty"
 (let [html (welcome-page [])]
   (str/includes? html "Folders you")) := false
 :rcf)

(defn empty-page
  "Placeholder page when query is blank or too short.
   Shows hints about where to create home.md for each registered root."
  []
  (let [paths (home-md-paths)]
    (str
     (h/html
      [:html
       [:head
        [:meta {:charset "UTF-8"}]
        [:style (page-css)]]
       [:body
        [:div.empty
         "Type to search\u2026"
         (when (seq paths)
           [:div {:style "margin-top: 24px; font-size: 12px; color: #4A3F90; font-weight: 400;"}
            "Create a home page at:"
            (for [p paths]
              [:div {:style "font-family: 'JetBrains Mono', 'Fira Code', 'Noto Sans Mono', Menlo, monospace; margin-top: 4px;"} p])])
         (when (empty? paths)
           [:div.no-roots-hint
            "No folders registered \u2014 see the "
            [:a {:href "winze:open-welcome"} "Welcome tab"]
            "."])]]]))))

;; ---------------------------------------------------------------------------
;; File viewer
;; ---------------------------------------------------------------------------

(defn resolve-file-path
  "Resolve absolute filesystem path from a root URI and relative file path."
  [root-uri rel-path]
  (let [store (server/store)
        root  (->> (core/list-roots store)
                   (filter #(= root-uri (:root/uri %)))
                   first)
        base  (str/replace root-uri #"^file://" "")]
    (str base "/" (:root/plans-dir root) "/" rel-path)))

(defn file-metadata-by-path
  "Look up indexed metadata for a file by root URI and relative path.
   Returns a map with :file/* keys, or nil if the file is not indexed."
  [root-uri rel-path]
  (let [store     (server/store)
        root-name (->> (core/list-roots store)
                       (filter #(= root-uri (:root/uri %)))
                       first
                       :root/name)
        file-id   (str root-name "::" rel-path)
        eids      (store/query store
                               '[:find [?f ...]
                                 :in $ ?fid
                                 :where [?f :file/id ?fid]]
                               {:fid file-id})]
    (when (seq eids)
      (let [e (store/pull-entity store (first eids))]
        (cond-> {:file/path rel-path
                 :root/uri  root-uri}
          (:file/status e)  (assoc :file/status  (:file/status e))
          (:file/created e) (assoc :file/created (:file/created e))
          (:file/group e)   (assoc :file/group   (:file/group e))
          (:file/tags e)    (assoc :file/tags    (:file/tags e))
          (:file/related e) (assoc :file/related (:file/related e))
          (:file/type e)    (assoc :file/type    (:file/type e)))))))

(defn- extract-raw-yaml
  "Return the raw YAML text between --- fences, or nil if no frontmatter."
  [text]
  (when (str/starts-with? text "---\n")
    (when-let [end (str/index-of text "\n---\n" 4)]
      (subs text 4 end))))

(defn extract-h1
  "Return the text of the first H1 heading in markdown, or nil."
  [markdown-text]
  (when markdown-text
    (some->> (re-find #"(?m)^# +(.+)" markdown-text)
             second
             str/trim)))

(tests
 (extract-h1 "# Hello World\nsome text")       := "Hello World"
 (extract-h1 "---\ntitle: x\n---\n# My Title") := "My Title"
 (extract-h1 "## Only H2\nno h1 here")         := nil
 (extract-h1 nil)                               := nil
 (extract-h1 "")                                := nil
 :rcf)

(def ^:private h1-punctuation-re
  "Matches the first punctuation character that should truncate an H1 for tab titles."
  #"( [\-])|([.,:;\(\)\u2014\u2013])") ; The first group: hyphenated words shouldn't break the title

(defn tab-title
  "Build a tab title from a filename and markdown text.
   If the file has an H1, use the H1 text up to first punctuation (no filename).
   If no H1, fall back to the basename. Filename is still in the tooltip."
  [filename markdown-text]
  (if-let [h1 (extract-h1 markdown-text)]
    (let [prefix (-> (str/split h1 h1-punctuation-re 2)
                     first
                     str/trim)]
      (if (str/blank? prefix)
        filename
        prefix))
    filename))

(tests
 (tab-title "CONTEXT.md" "# Live Search Home Page — Context\ntext") := "Live Search Home Page"
 (tab-title "PLAN.md" "# GPU Report: Implementation\ntext") := "GPU Report"
 (tab-title "PLAN.md" "# Step 1. Do something\ntext") := "Step 1"
 (tab-title "GPU-REPORT-CONTEXT.md" "# GPU Report — Context\ntext") := "GPU Report"
 (tab-title "AAO-44.md" "# Some Jira Story\ntext") := "Some Jira Story"
 (tab-title "CLOUD-GPU-GUIDE.md" "# Multi-Cloud GPU Cost Report - Cross-Provider Guide") := "Multi-Cloud GPU Cost Report"
 ;; H1 starts with punctuation → basename fallback
 (tab-title "CONTEXT.md" "# (parenthetical)\ntext") := "CONTEXT.md"
 ;; No H1 → basename
 (tab-title "README.md" "no heading here") := "README.md"
 :rcf)

(defn- file-dir
  "Extract the directory portion of a relative path, or \"\" for top-level files."
  [rel-path]
  (let [i (when rel-path (str/last-index-of rel-path "/"))]
    (if i (subs rel-path 0 i) "")))

(defn file-page
  "Render a markdown file's full content as a styled HTML page.
   When metadata is provided, renders the search-card-style header with
   status indicator, clickable path, created date, and metadata pills.
   YAML frontmatter is rendered as a styled code block.
   When root-uri is provided, relative .md links are rewritten to
   winze:open-file URLs."
  [markdown-text file-path & [metadata root-uri]]
  (let [[_fm body]  (frontmatter/parse-frontmatter markdown-text)
        raw-yaml    (extract-raw-yaml markdown-text)
        fm-offset   (- (count (str/split-lines markdown-text))
                       (count (str/split-lines body)))
        hiccup-opts (when root-uri
                      {:root-uri root-uri
                       :file-dir (file-dir file-path)})]
    (str
     (h/html
      [:html
       [:head
        [:meta {:charset "UTF-8"}]
        [:style (page-css)]]
       [:body
        (if metadata
          (file-header metadata {})
          [:div.header file-path])
        (when raw-yaml
          [:pre.frontmatter-block raw-yaml])
        [:div.result-body (hiccup/md->hiccup body fm-offset (or hiccup-opts {}))]]]))))

;; ---------------------------------------------------------------------------
;; Home page
;; ---------------------------------------------------------------------------

(defn home-files
  "Discover which registered roots have a Plans/home.md file.
   Returns a vector of {:root/uri :root/name :rel-path :abs-path} maps."
  []
  (let [store (server/store)
        roots (core/list-roots store)]
    (into []
          (keep (fn [{:keys [root/uri root/name root/plans-dir]}]
                  (let [abs (root-abs-path uri plans-dir "home.md")]
                    (when (.isFile (File. ^String abs))
                      {:root/uri uri
                       :root/name name
                       :rel-path "home.md"
                       :abs-path abs}))))
          roots)))

(defn- home-card
  "Render a home.md file as a result card with clickable title."
  [{:keys [root/uri rel-path abs-path]}]
  (let [content  (slurp abs-path)
        metadata (file-metadata-by-path uri rel-path)]
    [:div.result-card
     (file-header (or metadata {:file/path rel-path :root/uri uri}) {})
     [:div.result-body (hiccup/md->hiccup content 0
                                          {:root-uri uri
                                           :file-dir (file-dir rel-path)})]]))

(defn home-page
  "Build home page content. Returns a map or nil:
   - Single home.md:   {:mode :file :html ... :abs-path ... :root-uri ... :rel-path ...}
   - Multiple home.md: {:mode :synthetic :html ...}
   - No home.md:       nil"
  []
  (let [homes (home-files)]
    (case (count homes)
      0 nil
      1 (let [{:keys [root/uri rel-path abs-path]} (first homes)
              content  (slurp abs-path)
              metadata (file-metadata-by-path uri rel-path)
              html     (file-page content rel-path metadata uri)]
          {:mode     :file
           :html     html
           :abs-path abs-path
           :root-uri uri
           :rel-path rel-path})
      ;; Multiple — render as cards sorted by H1
      (let [cards (sort-by (fn [{:keys [abs-path]}]
                             (let [h1 (extract-h1 (slurp abs-path))]
                               (or h1 abs-path)))
                           homes)
            html  (str (h/html
                        [:html
                         [:head [:meta {:charset "UTF-8"}]
                          [:style (page-css)]]
                         [:body
                          [:div.header (str (count homes) " home pages")]
                          (for [home cards]
                            (home-card home))]]))]
        {:mode :synthetic
         :html html}))))

;; ---------------------------------------------------------------------------
;; Debounce
;; ---------------------------------------------------------------------------

(def ^:private executor (Executors/newSingleThreadScheduledExecutor))

(def ^:private pending (atom nil))

(def ^:private debounce-ms 300)

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn results
  "Run live search. Called from the UI thread on every keystroke (via async-exec!).
   Debounces, runs search off the UI thread, then pushes HTML back via async-exec!.
   `on-home-page` is a callback (fn [home-result]) called when the query is too short,
   where home-result is the return value of `home-page` (map or nil)."
  [query-string browser-widget on-home-page]
  ;; Cancel any pending search
  (when-let [^ScheduledFuture fut @pending]
    (.cancel fut false))
  (let [q                  (str/trim (or query-string ""))
        {:keys [text filters]} (parse-query q)]
    (if (and (< (count text) 3) (empty? filters))
      ;; Short/empty query — show home page or placeholder
      (do (reset! resources/last-search-query nil)
          (async-exec! #(on-home-page (home-page))))
      ;; Schedule debounced search on executor thread
      (reset! pending
              (.schedule executor
                         ^Callable
                         (fn []
                           (try
                             (let [store (server/store)
                                   opts  (merge {:top 10 :dedupe true} filters)
                                   hits  (if (str/blank? text)
                                           (core/metadata-query store filters)
                                           (core/search store text opts))
                                   html  (results-page hits q filters)]
                               (reset! resources/last-search-query {:query q :text text :filters filters})
                               (async-exec!
                                (fn []
                                  ;; Deregister file mode so home page can be restored on clear
                                  (when-let [old-path (:abs-path @resources/live-search-state)]
                                    (swap! resources/open-files dissoc old-path))
                                  (swap! resources/live-search-state assoc :mode :synthetic :abs-path nil)
                                  (.setText browser-widget html))))
                             (catch Throwable t
                               (async-exec!
                                #(.setText browser-widget
                                           (str (h/html
                                                 [:html
                                                  [:head [:style (page-css)]]
                                                  [:body
                                                   [:div.no-results
                                                    (str "Search error: " (.getMessage t))]]])))))))
                         (long debounce-ms)
                         TimeUnit/MILLISECONDS)))))

(defn refresh-last-search
  "Re-run the last search query and return the HTML string, or nil if no query is active."
  []
  (when-let [{:keys [query text filters]} @resources/last-search-query]
    (let [store (server/store)
          opts  (merge {:top 10 :dedupe true} filters)
          hits  (if (str/blank? text)
                  (core/metadata-query store filters)
                  (core/search store text opts))]
      (results-page hits query filters))))
