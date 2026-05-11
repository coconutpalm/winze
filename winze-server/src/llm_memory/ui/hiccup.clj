(ns llm-memory.ui.hiccup
  "Markdown → Hiccup renderer with source-line annotations.
  Walks the commonmark-java AST directly, producing Hiccup vectors
  with {:data-line N} on block elements."
  (:require
   [clojure.string :as str]
   [hyperfiddle.rcf :refer [tests]]
   [llm-memory.chunk :as chunk])
  (:import
   [java.net URLEncoder]
   [java.nio.file Paths]
   [org.commonmark.ext.gfm.strikethrough Strikethrough StrikethroughExtension]
   [org.commonmark.ext.gfm.tables TableBlock TableBody TableCell
    TableCell$Alignment TableHead
    TableRow TablesExtension]
   [org.commonmark.ext.task.list.items TaskListItemMarker TaskListItemsExtension]
   [org.commonmark.node Block BlockQuote BulletList Code Document Emphasis
    FencedCodeBlock HardLineBreak Heading HtmlBlock
    HtmlInline Image IndentedCodeBlock Link ListBlock
    ListItem OrderedList Paragraph SoftLineBreak
    StrongEmphasis Text ThematicBreak]
   [org.commonmark.parser IncludeSourceSpans Parser]))

;; ---------------------------------------------------------------------------
;; Parser (thread-safe — reused across calls)
;; ---------------------------------------------------------------------------

(def ^:private ^Parser parser
  (.. (Parser/builder)
      (includeSourceSpans IncludeSourceSpans/BLOCKS)
      (extensions [(TablesExtension/create)
                   (StrikethroughExtension/create)
                   (TaskListItemsExtension/create)])
      build))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- source-line
  "Return the 0-based source line of `node`, or nil if unavailable."
  [node]
  (let [spans (.getSourceSpans node)]
    (when (seq spans)
      (.getLineIndex (first spans)))))

(defn- block-attrs
  "Return {:data-line N} for a block node, or {} if no source span.
   Adds `:line-offset` from ctx to every data-line value."
  [ctx node]
  (if-let [line (source-line node)]
    {:data-line (+ line (or (:line-offset ctx) 0))}
    {}))

;; ---------------------------------------------------------------------------
;; AST walker
;; ---------------------------------------------------------------------------

(declare node->hiccup)

(defn- walk-children
  "Return a vector of hiccup forms for all children of `node`.
  `ctx` carries rendering context (e.g. :tight? flag)."
  [ctx node]
  (loop [child (.getFirstChild node)
         acc   []]
    (if child
      (let [h (node->hiccup ctx child)]
        (recur (.getNext child)
               (if (sequential? h)
                 ;; Distinguish a single hiccup vector [:tag ...] from a seq-of-forms
                 ;; by checking whether the first element is a keyword.
                 (if (keyword? (first h))
                   (conj acc h)
                   (into acc h))
                 (cond-> acc h (conj h)))))
      acc)))

;; ---------------------------------------------------------------------------
;; Block-level rendering
;; ---------------------------------------------------------------------------

(defn- render-heading
  [ctx node]
  (let [level (.getLevel ^Heading node)
        tag   (keyword (str "h" level))
        slug  (chunk/slugify (chunk/text-content (.getFirstChild node)))
        attrs (cond-> (block-attrs ctx node)
                (not (str/blank? slug)) (assoc :id slug))]
    (into [tag attrs] (walk-children ctx node))))

(defn- render-paragraph
  [ctx node]
  (if (:tight? ctx)
    ;; Tight list — unwrap: return inline children directly (a seq, not a vector)
    (walk-children ctx node)
    (into [:p (block-attrs ctx node)] (walk-children ctx node))))

(defn- render-code-block
  [ctx node lang-info]
  (let [literal (or (.getLiteral node) "")
        lang    (when lang-info (str/trim lang-info))
        code-tag (if (and lang (not (str/blank? lang)))
                   (keyword (str "code.language-" lang))
                   :code)]
    [:pre (block-attrs ctx node) [code-tag literal]]))

(defn- render-list
  [ctx node tag]
  (let [tight? (.isTight ^ListBlock node)
        attrs  (block-attrs ctx node)
        attrs  (if (instance? OrderedList node)
                 (merge attrs {:start (.getMarkerStartNumber ^OrderedList node)})
                 attrs)]
    (into [tag attrs] (walk-children (assoc ctx :tight? tight?) node))))

;; ---------------------------------------------------------------------------
;; Inline-level rendering
;; ---------------------------------------------------------------------------

(defn- local-md-link?
  "True when `dest` is a relative path to a .md file (not a fragment, absolute
   URL, or already-schemed link)."
  [dest]
  (and (not (str/blank? dest))
       (not (str/starts-with? dest "#"))
       (not (str/includes? dest "://"))
       (not (str/starts-with? dest "wiki:"))
       (not (str/starts-with? dest "winze:"))
       (not (str/starts-with? dest "mailto:"))
       (not (str/starts-with? dest "file:"))
       (let [path-part (first (str/split dest #"#" 2))]
         (str/ends-with? (str/lower-case path-part) ".md"))))

(defn- rewrite-local-link
  "If `dest` is a relative .md path and ctx has :root-uri + :file-dir, resolve
   it to a winze:open-file URL. Otherwise return dest unchanged."
  [ctx dest]
  (if (and (:root-uri ctx) (:file-dir ctx) (local-md-link? dest))
    (let [[path-part fragment] (str/split dest #"#" 2)
          base     (Paths/get (str (:file-dir ctx)) (into-array String []))
          resolved (str (.normalize (.resolve base ^String path-part)))
          url      (str "winze:open-file?root="
                        (URLEncoder/encode (str (:root-uri ctx)) "UTF-8")
                        "&path="
                        (URLEncoder/encode resolved "UTF-8"))]
      (if fragment (str url "#" fragment) url))
    dest))

(defn- render-link
  [ctx node]
  (let [dest  (.getDestination ^Link node)
        title (.getTitle ^Link node)
        href  (rewrite-local-link ctx dest)
        attrs (cond-> {:href href}
                (not (str/blank? title)) (assoc :title title))]
    (into [:a attrs] (walk-children ctx node))))

(defn- render-image
  [node]
  (let [dest  (.getDestination ^Image node)
        title (.getTitle ^Image node)
        alt   (chunk/text-content (.getFirstChild node))
        attrs (cond-> {:src dest :alt alt}
                (not (str/blank? title)) (assoc :title title))]
    [:img attrs]))

;; ---------------------------------------------------------------------------
;; GFM: Table rendering
;; ---------------------------------------------------------------------------

(defn- cell-alignment-style
  [^TableCell cell]
  (when-let [a (.getAlignment cell)]
    (let [align (condp = a
                  TableCell$Alignment/LEFT   "left"
                  TableCell$Alignment/CENTER "center"
                  TableCell$Alignment/RIGHT  "right"
                  nil)]
      (when align {:style (str "text-align:" align)}))))

(defn- render-table-cell
  [ctx node]
  (let [cell  ^TableCell node
        tag   (if (.isHeader cell) :th :td)
        attrs (or (cell-alignment-style cell) {})]
    (into [tag attrs] (walk-children ctx node))))

;; ---------------------------------------------------------------------------
;; Main dispatch
;; ---------------------------------------------------------------------------

(defn- node->hiccup
  "Convert a single AST node to a Hiccup form.
  Returns a keyword-headed vector, a string, a seq-of-forms (tight paragraph),
  or nil for nodes that produce no output."
  [ctx node]
  (condp instance? node
    ;; Block nodes
    Document         (into [:div] (walk-children ctx node))
    Heading          (render-heading ctx node)
    Paragraph        (render-paragraph ctx node)
    BlockQuote       (into [:blockquote (block-attrs ctx node)] (walk-children ctx node))
    FencedCodeBlock  (render-code-block ctx node (.getInfo ^FencedCodeBlock node))
    IndentedCodeBlock (render-code-block ctx node nil)
    BulletList       (render-list ctx node :ul)
    OrderedList      (render-list ctx node :ol)
    ListItem         (into [:li] (walk-children ctx node))
    ThematicBreak    [:hr (block-attrs ctx node)]
    HtmlBlock        (.getLiteral ^HtmlBlock node)
    ;; Inline nodes
    Text             (.getLiteral ^Text node)
    Code             [:code (.getLiteral ^Code node)]
    Emphasis         (into [:em] (walk-children ctx node))
    StrongEmphasis   (into [:strong] (walk-children ctx node))
    Link             (render-link ctx node)
    Image            (render-image node)
    SoftLineBreak    " "
    HardLineBreak    [:br]
    HtmlInline       (.getLiteral ^HtmlInline node)
    ;; GFM extensions
    TableBlock       (into [:table (block-attrs ctx node)] (walk-children ctx node))
    TableHead        (into [:thead] (walk-children ctx node))
    TableBody        (into [:tbody] (walk-children ctx node))
    TableRow         (into [:tr] (walk-children ctx node))
    TableCell        (render-table-cell ctx node)
    Strikethrough    (into [:s] (walk-children ctx node))
    TaskListItemMarker [:input {:type "checkbox" :checked (.isChecked ^TaskListItemMarker node)}]
    ;; Fallback — unknown nodes: recurse into children
    (walk-children ctx node)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn md->hiccup
  "Parse markdown text and return a Hiccup vector.
  Block-level elements carry {:data-line N} (0-based source line).
  `line-offset` (default 0) is added to every data-line value — use this
  when the text was extracted from a larger document (e.g. after stripping
  YAML frontmatter) so data-line values remain absolute source lines.
  `opts` may include :root-uri and :file-dir for rewriting relative .md links
  to winze:open-file URLs.
  Uses commonmark-java with GFM extensions (tables, strikethrough, task lists)."
  ([markdown-text] (md->hiccup markdown-text 0))
  ([markdown-text line-offset] (md->hiccup markdown-text line-offset {}))
  ([markdown-text line-offset opts]
   (let [doc (.parse parser (or markdown-text ""))
         ctx (merge {:line-offset line-offset} opts)]
     (node->hiccup ctx doc))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(tests
  ;; Basic heading and paragraph
 (md->hiccup "# Hello") := [:div [:h1 {:data-line 0 :id "hello"} "Hello"]]

  ;; Heading slug — multi-word
 (md->hiccup "## Hello World") := [:div [:h2 {:data-line 0 :id "hello-world"} "Hello World"]]

  ;; Heading slug — special characters stripped
 (md->hiccup "## Step 3 — Details")
 := [:div [:h2 {:data-line 0 :id "step-3-details"} "Step 3 — Details"]]

  ;; Heading slug — must not leak sibling text
 (-> (md->hiccup "## First\n\nParagraph\n\n## Second")
     (nth 1) second :id) := "first"
 (-> (md->hiccup "## First\n\nParagraph\n\n## Second")
     (nth 3) second :id) := "second"

  ;; Heading slug — inline code
 (md->hiccup "## The `foo` function")
 := [:div [:h2 {:data-line 0 :id "the-foo-function"} "The " [:code "foo"] " function"]]

 (md->hiccup "para one\n\npara two")
 := [:div [:p {:data-line 0} "para one"] [:p {:data-line 2} "para two"]]

  ;; Fenced code block with language
 (md->hiccup "```clojure\n(+ 1 2)\n```")
 := [:div [:pre {:data-line 0} [:code.language-clojure "(+ 1 2)\n"]]]

  ;; Tight list — no <p> inside <li>
 (md->hiccup "- one\n- two")
 := [:div [:ul {:data-line 0} [:li "one"] [:li "two"]]]

  ;; Loose list — <p> inside <li>
 (md->hiccup "- one\n\n- two")
 := [:div [:ul {:data-line 0}
           [:li [:p {:data-line 0} "one"]]
           [:li [:p {:data-line 2} "two"]]]]

  ;; Bold and italic
 (md->hiccup "**bold** and *italic*")
 := [:div [:p {:data-line 0} [:strong "bold"] " and " [:em "italic"]]]

  ;; Inline code
 (md->hiccup "use `foo` here")
 := [:div [:p {:data-line 0} "use " [:code "foo"] " here"]]

  ;; Link — absolute URL unchanged
 (md->hiccup "[click](http://x)")
 := [:div [:p {:data-line 0} [:a {:href "http://x"} "click"]]]

  ;; Link — relative .md without context passes through unchanged
 (md->hiccup "[doc](other.md)")
 := [:div [:p {:data-line 0} [:a {:href "other.md"} "doc"]]]

  ;; Link — relative .md WITH context rewrites to winze:open-file
 (md->hiccup "[doc](other.md)" 0
             {:root-uri "file:///Users/me/proj" :file-dir "guides"})
 := [:div [:p {:data-line 0}
           [:a {:href "winze:open-file?root=file%3A%2F%2F%2FUsers%2Fme%2Fproj&path=guides%2Fother.md"} "doc"]]]

  ;; Link — ../traversal resolves correctly
 (md->hiccup "[ctx](../complete/gpu/CONTEXT.md)" 0
             {:root-uri "file:///proj" :file-dir "guides"})
 := [:div [:p {:data-line 0}
           [:a {:href "winze:open-file?root=file%3A%2F%2F%2Fproj&path=complete%2Fgpu%2FCONTEXT.md"} "ctx"]]]

  ;; Link — fragment-only link unchanged
 (md->hiccup "[sec](#heading)" 0
             {:root-uri "file:///proj" :file-dir "guides"})
 := [:div [:p {:data-line 0} [:a {:href "#heading"} "sec"]]]

  ;; Link — .md link with fragment preserves fragment
 (md->hiccup "[sec](other.md#heading)" 0
             {:root-uri "file:///proj" :file-dir "guides"})
 := [:div [:p {:data-line 0}
           [:a {:href "winze:open-file?root=file%3A%2F%2F%2Fproj&path=guides%2Fother.md#heading"} "sec"]]]

  ;; Link — non-.md relative file unchanged
 (md->hiccup "![img](pic.png)" 0
             {:root-uri "file:///proj" :file-dir "guides"})
 := [:div [:p {:data-line 0} [:img {:src "pic.png" :alt "img"}]]]

  ;; Link — https URL unchanged even with context
 (md->hiccup "[ext](https://example.com)" 0
             {:root-uri "file:///proj" :file-dir "guides"})
 := [:div [:p {:data-line 0} [:a {:href "https://example.com"} "ext"]]]

  ;; Image
 (md->hiccup "![alt text](img.png)")
 := [:div [:p {:data-line 0} [:img {:src "img.png" :alt "alt text"}]]]

  ;; Strikethrough
 (md->hiccup "~~deleted~~")
 := [:div [:p {:data-line 0} [:s "deleted"]]]

  ;; Table — check top-level tag
 (-> (md->hiccup "| a | b |\n|---|---|\n| 1 | 2 |")
     second first)
 := :table

  ;; Task list — first li should have a checked checkbox
 (let [h (md->hiccup "- [x] done\n- [ ] todo")]
   (some #(= [:input {:type "checkbox" :checked true}] %)
         (tree-seq sequential? seq h)))
 := true

 :rcf)
