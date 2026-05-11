(ns llm-memory.chunk
  "Markdown chunking and AST-based text extraction.

  Provides:
    text-content     — extract plain text from a commonmark-java AST node
    slugify          — convert heading text to a URL-friendly slug
    extract-headings — parse headings from markdown text (regex-based)
    split-sections   — split a markdown document into [slug text] chunks

  split-sections algorithm:
    1. Split on `## ` (H2) boundaries
    2. Prepend H1 title to every chunk for search relevance
    3. Size-split at paragraph boundaries (`\\n\\n`) when chunk > 4000 chars
    4. Generate semantic slug IDs (lowercase, alphanumeric + hyphens, max 60 chars, dedup)"
  (:require [clojure.string :as str]
            [hyperfiddle.rcf :refer [tests]])
  (:import [org.commonmark.node Code HardLineBreak SoftLineBreak Text]
           [org.commonmark.parser Parser]))

(defn slugify
  "Convert a markdown heading to a URL-friendly slug.
   Strips leading `#` chars, lowercases, keeps only [a-z0-9 -], collapses
   whitespace to hyphens, trims, and truncates to max-len."
  ([heading] (slugify heading 60))
  ([heading max-len]
   (let [slug (-> heading
                  (str/replace #"^#+\s*" "")
                  str/trim
                  str/lower-case
                  (str/replace #"[^a-z0-9\s-]" "")
                  (str/replace #"\s+" "-")
                  (str/replace #"^-+" "")
                  (str/replace #"-+$" ""))]
     (if (> (count slug) max-len)
       (subs slug 0 max-len)
       slug))))

(defn extract-headings
  "Parse headings from markdown text. Returns a vector of
  {:text \"Design Decisions\" :level 2 :slug \"design-decisions\" :line N}
  where :line is 0-based."
  [text]
  (when-not (str/blank? text)
    (into []
          (comp
           (map-indexed vector)
           (keep (fn [[line-num line]]
                   (when-let [[_ hashes rest'] (re-matches #"^(#{1,6})\s+(.*?)\s*$" line)]
                     (let [heading-text (str/trim rest')]
                       (when (seq heading-text)
                         {:text  heading-text
                          :level (count hashes)
                          :slug  (slugify heading-text)
                          :line  line-num}))))))
          (str/split-lines text))))

(defn page-title
  "Extract the page title (first H1 content) from markdown text, or nil."
  [text]
  (some (fn [{:keys [level text]}]
          (when (= level 1) text))
        (extract-headings text)))

(defn text-content
  "Recursively extract plain text from a commonmark-java AST node and its
  siblings. Handles Text, inline Code, SoftLineBreak (→ space), HardLineBreak
  (→ newline), and recurses into composite nodes via their first child.

  Used to extract display text from Heading nodes — ignoring link destinations,
  since .getDestination is a Java attribute, not a child node. This produces
  correct slugs for headings that contain links, unlike the regex-based
  extract-headings."
  [node]
  (let [sb (StringBuilder.)]
    (loop [n node]
      (when n
        (cond
          (instance? Text n)          (.append sb (.getLiteral ^Text n))
          (instance? Code n)          (.append sb (.getLiteral ^Code n))
          (instance? SoftLineBreak n) (.append sb " ")
          (instance? HardLineBreak n) (.append sb "\n")
          :else (let [child (.getFirstChild n)]
                  (when child
                    (.append sb (text-content child)))))
        (recur (.getNext n))))
    (str sb)))

(tests
 "text-content — plain heading"
 (let [doc (.parse (.. (Parser/builder) build) "## Hello World")
       h   (.getFirstChild doc)]
   (text-content (.getFirstChild h)) := "Hello World")
 :rcf)

(tests
 "text-content — heading containing a wiki link (display text only, not destination)"
 (let [doc (.parse (.. (Parser/builder) build)
                   "## [My Section](wiki:root::file.md#anchor)")
       h   (.getFirstChild doc)]
   (text-content (.getFirstChild h)) := "My Section")
 :rcf)

(defn- split-by-paragraphs
  "Split text exceeding max-chars at paragraph boundaries (\\n\\n).
   Single paragraphs exceeding the limit are kept intact."
  [text max-chars]
  (let [paragraphs (str/split text #"\n\n")]
    (loop [paras       paragraphs
           current     []
           current-len 0
           result      []]
      (if-let [para (first paras)]
        (let [para-len (+ (count para) 2)]
          (if (and (seq current) (> (+ current-len para-len) max-chars))
            (recur paras [] 0 (conj result (str/join "\n\n" current)))
            (recur (rest paras)
                   (conj current para)
                   (+ current-len para-len)
                   result)))
        (if (seq current)
          (conj result (str/join "\n\n" current))
          result)))))

(defn- collect-raw-chunks
  "Pass 1: Split lines into raw chunks at H2 boundaries.
   Returns [{:heading str-or-nil :lines [str ...]} ...]"
  [lines title]
  (loop [remaining lines
         current   []
         heading   nil
         chunks    []]
    (if-let [line (first remaining)]
      (cond
        ;; H2 boundary and we have accumulated lines
        (and (str/starts-with? line "## ") (seq current))
        (let [chunk-text (str/trim (str/join "\n" current))]
          (if (and (seq chunk-text) (not= chunk-text title))
            ;; Emit the chunk, start new section
            (recur remaining [] nil
                   (conj chunks {:heading heading :lines current}))
            ;; Title-only preamble — merge with this H2 section
            (recur (rest remaining)
                   (conj current line)
                   (str/trim line)
                   chunks)))

        ;; H2 line (at start or first line)
        (str/starts-with? line "## ")
        (recur (rest remaining)
               (conj current line)
               (str/trim line)
               chunks)

        ;; Normal line
        :else
        (recur (rest remaining)
               (conj current line)
               heading
               chunks))

      ;; End of lines — flush
      (let [chunk-text (str/trim (str/join "\n" current))]
        (if (seq chunk-text)
          (conj chunks {:heading heading :lines current})
          chunks)))))

(defn- build-slug-text-pairs
  "Pass 2: Convert raw chunks to [slug text] pairs with title prepend,
   slug deduplication, and paragraph sub-splitting."
  [raw-chunks title max-chars]
  (let [slug-counts (volatile! {})]
    (into []
          (mapcat
           (fn [[idx {:keys [heading lines]}]]
             (let [chunk-text (str/trim (str/join "\n" lines))]
               (when (seq chunk-text)
                 (let [;; Prepend title if chunk doesn't start with H1
                       full-text (if (and title (not (str/starts-with? chunk-text "# ")))
                                   (str title "\n\n" chunk-text)
                                   chunk-text)
                       ;; Derive slug from heading
                       base-slug (if heading
                                   (slugify heading)
                                   (str "section-" idx))
                       ;; Deduplicate slugs
                       counts @slug-counts
                       slug   (if (contains? counts base-slug)
                                (let [n (inc (get counts base-slug))]
                                  (vswap! slug-counts assoc base-slug n)
                                  (str base-slug "-" n))
                                (do (vswap! slug-counts assoc base-slug 0)
                                    base-slug))]
                   ;; Sub-split oversized chunks at paragraph boundaries
                   (if (> (count full-text) max-chars)
                     (let [sub-chunks (split-by-paragraphs full-text max-chars)]
                       (if (> (count sub-chunks) 1)
                         (map-indexed (fn [si sc] [(str slug "-" si) sc]) sub-chunks)
                         [[slug full-text]]))
                     [[slug full-text]]))))))
          (map-indexed vector raw-chunks))))

(defn split-sections
  "Split a markdown document into [slug, text] chunks.

  First splits at H2 (`## `) boundaries, then at paragraph boundaries for
  chunks exceeding max-chars (default 4000). Prepends the H1 title to every
  chunk so searches match on document title.

  Returns a vector of [slug text] pairs. The slug is derived from the H2
  heading (semantic ID), falling back to \"section-N\"."
  ([text] (split-sections text 4000))
  ([text max-chars]
   (if (str/blank? text)
     []
     (let [lines      (str/split-lines text)
           title      (some #(when (str/starts-with? % "# ") (str/trim %)) lines)
           raw-chunks (collect-raw-chunks lines title)]
       (if (empty? raw-chunks)
         (let [trimmed (str/trim text)]
           (if (seq trimmed)
             [["section-0" trimmed]]
             []))
         (build-slug-text-pairs raw-chunks title max-chars))))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(tests
 "slugify — heading to URL-friendly slug"
 (slugify "## Phase 0: Validate Datalevin") := "phase-0-validate-datalevin"
 (slugify "## What's New in v2.0?")         := "whats-new-in-v20"
 (slugify "## Step 1 — Configure")           := "step-1-configure"
 (slugify "## simple heading")               := "simple-heading"
 (slugify "## ")                             := ""
 (slugify "## UPPER CASE")                   := "upper-case"
 :rcf)

(tests
 "split-sections — basic H2 splitting with title prepend"
 (let [text   "# My Doc\n\nIntro.\n\n## Alpha\n\nContent A.\n\n## Beta\n\nContent B."
       chunks (split-sections text)]
   (count chunks) := 3
   (first (first chunks)) := "section-0"
   (first (second chunks)) := "alpha"
   (str/starts-with? (second (second chunks)) "# My Doc") := true
   (first (nth chunks 2)) := "beta"
   (str/starts-with? (second (nth chunks 2)) "# My Doc") := true)
 :rcf)

(tests
 "split-sections — title-only preamble merges with first H2"
 (let [chunks (split-sections "# Title\n\n## Sec A\n\nA content.\n\n## Sec B\n\nB content.")]
   (count chunks) := 2
   (first (first chunks)) := "sec-a"
   (str/includes? (second (first chunks)) "# Title") := true)
 :rcf)

(tests
 "split-sections — slug deduplication"
 (let [chunks (split-sections "# Doc\n\n## Step\n\n1\n\n## Step\n\n2\n\n## Step\n\n3")]
   (mapv first chunks) := ["step" "step-1" "step-2"])
 :rcf)

(tests
 "split-sections — paragraph sub-splitting for oversized chunks"
 (let [long-para (apply str (repeat 50 "word "))
       text      (str "# Doc\n\n## Big\n\n" long-para "\n\n" long-para "\n\n" long-para)
       chunks    (split-sections text 300)]
   (> (count chunks) 1) := true
   (str/starts-with? (first (first chunks)) "big") := true)
 :rcf)

(tests
 "split-sections — empty and blank input"
 (split-sections "") := []
 (split-sections "   \n  \n  ") := []
 :rcf)

(tests
 "split-sections — no H2 headings (single chunk)"
 (let [chunks (split-sections "# Just a Title\n\nSome content without H2 sections.")]
   (count chunks) := 1
   (first (first chunks)) := "section-0")
 :rcf)

(tests
 "extract-headings — parses all heading levels"
 (let [text "# Title\n\nIntro.\n\n## Design Decisions\n\nText.\n\n### Sub-section\n\nMore."
       hs   (extract-headings text)]
   (count hs) := 3
   (:text (first hs))  := "Title"
   (:level (first hs)) := 1
   (:slug (first hs))  := "title"
   (:line (first hs))  := 0
   (:text (second hs))  := "Design Decisions"
   (:level (second hs)) := 2
   (:slug (second hs))  := "design-decisions"
   (:line (second hs))  := 4
   (:level (nth hs 2)) := 3)
 :rcf)

(tests
 "extract-headings — empty/blank input"
 (extract-headings "") := nil
 (extract-headings "  \n  ") := nil
 :rcf)

(tests
 "extract-headings — ignores non-heading lines"
 (let [hs (extract-headings "No headings here.\n\nJust paragraphs.")]
   hs := [])
 :rcf)

(tests
 "page-title — returns first H1"
 (page-title "## Not this\n\n# My Page\n\nContent.") := "My Page"
 (page-title "## Only H2\n\nContent.") := nil
 (page-title "") := nil
 :rcf)
