(ns llm-memory.ui.md-theme
  (:require
   [clojure.string :as str]
   [llm-memory.highlight.core :as highlight]
   [llm-memory.highlight.loader]
   [hyperfiddle.rcf :refer [tests]]))

;; ---------------------------------------------------------------------------
;; Block-level parsing
;; ---------------------------------------------------------------------------

(defn- heading-level
  "Return 1–6 if `line` is a markdown heading, nil otherwise."
  [line]
  (when-let [[_ hashes] (re-matches #"^(#{1,6}) .+" line)]
    (count hashes)))

(defn- fence-info
  "If `line` is a code fence opener/closer, return the language tag (or \"\").
  Returns nil if not a fence line."
  [line]
  (when-let [[_ lang] (re-matches #"^```(\w*).*" line)]
    lang))

(defn- blockquote?
  "Return true if `line` starts with '> '."
  [line]
  (str/starts-with? line "> "))

(def ^:private heading-type
  {1 :heading/h1, 2 :heading/h2, 3 :heading/h3
   4 :heading/h4, 5 :heading/h5, 6 :heading/h6})

(def ^:private heading-marker-type
  {1 :heading/h1-marker, 2 :heading/h2-marker, 3 :heading/h3-marker
   4 :heading/h4-marker, 5 :heading/h5-marker, 6 :heading/h6-marker})

(defn parse-list-item
  "If `line` is a list item, return a map with :type, :indent (nesting level),
  :marker-len (total: indent spaces + marker), :bullet-len (marker only, no
  indent spaces), and optionally :number. Returns nil for non-list lines."
  [line]
  (or
   ;; Checkbox: ( *)[-*+] [([ xX])] ...
   (when-let [[_ spaces check] (re-matches #"^( *)[-*+] \[([ xX])\] .*" line)]
     (let [indent (quot (count spaces) 2)]
       {:type       (if (#{"x" "X"} check) :checkbox-checked :checkbox-unchecked)
        :indent     indent
        :marker-len (+ (count spaces) 6)
        :bullet-len 6}))
   ;; Numbered: ( *)(\d+)\. ...
   (when-let [[_ spaces num-str] (re-matches #"^( *)(\d+)\. .*" line)]
     (let [indent (quot (count spaces) 2)
           blen   (+ (count num-str) 2)]
       {:type       :numbered-item
        :indent     indent
        :number     (parse-long num-str)
        :marker-len (+ (count spaces) blen)
        :bullet-len blen}))
   ;; Bullet: ( *)[-*+] ...
   (when-let [[_ spaces] (re-matches #"^( *)[-*+] .*" line)]
     (let [indent (quot (count spaces) 2)]
       {:type       :bullet-item
        :indent     indent
        :marker-len (+ (count spaces) 2)
        :bullet-len 2}))))

(defn parse-blocks
  "Walk `text` line-by-line, returning a seq of block span maps.
  Each span has :start, :length, :type, and optionally :lang."
  [text]
  (let [lines (str/split-lines text)]
    (loop [i       0
           offset  0
           in-code false
           lang    nil
           code-start 0
           result  []]
      (if (>= i (count lines))
        ;; Flush unclosed code block
        (if in-code
          (conj result {:start  code-start
                        :length (- (count text) code-start)
                        :type   :code-block
                        :lang   lang})
          result)
        (let [line     (nth lines i)
              line-len (count line)
              ;; +1 for \n except on last line
              advance  (if (< i (dec (count lines)))
                         (inc line-len)
                         line-len)
              fi       (fence-info line)]
          (cond
            ;; Opening fence
            (and (not in-code) fi)
            (recur (inc i) (+ offset advance) true fi (+ offset advance) result)

            ;; Closing fence
            (and in-code (some? (fence-info line)))
            (let [code-len (- offset code-start)]
              (recur (inc i) (+ offset advance) false nil 0
                     (if (pos? code-len)
                       (conj result {:start  code-start
                                     :length code-len
                                     :type   :code-block
                                     :lang   lang})
                       result)))

            ;; Inside code block — skip (handled when fence closes)
            in-code
            (recur (inc i) (+ offset advance) true lang code-start result)

            ;; Heading — emit a marker span (#…# ) and a text span
            :else
            (if-let [level (heading-level line)]
              (let [marker-len (inc level)] ; '#'×level plus the space
                (recur (inc i) (+ offset advance) false nil 0
                       (conj result
                             {:start  offset
                              :length marker-len
                              :type   (heading-marker-type level)}
                             {:start  (+ offset marker-len)
                              :length (- line-len marker-len)
                              :type   (heading-type level)})))
              ;; Blockquote
              (if (blockquote? line)
                (recur (inc i) (+ offset advance) false nil 0
                       (conj result {:start  offset
                                     :length line-len
                                     :type   :blockquote}))
                ;; List item
                (if-let [li (parse-list-item line)]
                  (recur (inc i) (+ offset advance) false nil 0
                         (conj result (assoc li
                                             :start  offset
                                             :length line-len)))
                  ;; Body line (skip empty lines)
                  (recur (inc i) (+ offset advance) false nil 0
                         (if (pos? line-len)
                           (conj result {:start  offset
                                         :length line-len
                                         :type   :body})
                           result)))))))))))

(tests
 (parse-blocks "") := []

 (parse-blocks "# Hello")
 := [{:start 0 :length 2 :type :heading/h1-marker}
     {:start 2 :length 5 :type :heading/h1}]

 (parse-blocks "## Sub\n### Third")
 := [{:start 0 :length 3 :type :heading/h2-marker}
     {:start 3 :length 3 :type :heading/h2}
     {:start 7 :length 4 :type :heading/h3-marker}
     {:start 11 :length 5 :type :heading/h3}]

 (parse-blocks "###### H6 heading")
 := [{:start 0 :length 7 :type :heading/h6-marker}
     {:start 7 :length 10 :type :heading/h6}]

 (parse-blocks "plain text")
 := [{:start 0 :length 10 :type :body}]

 (parse-blocks "> quote")
 := [{:start 0 :length 7 :type :blockquote}]

 ;; List items
 (parse-blocks "- item")
 := [{:start 0 :length 6 :type :bullet-item :indent 0 :marker-len 2 :bullet-len 2}]

 (parse-blocks "* item")
 := [{:start 0 :length 6 :type :bullet-item :indent 0 :marker-len 2 :bullet-len 2}]

 (parse-blocks "  - nested")
 := [{:start 0 :length 10 :type :bullet-item :indent 1 :marker-len 4 :bullet-len 2}]

 (parse-blocks "1. first")
 := [{:start 0 :length 8 :type :numbered-item :indent 0 :number 1 :marker-len 3 :bullet-len 3}]

 (parse-blocks "- [ ] todo")
 := [{:start 0 :length 10 :type :checkbox-unchecked :indent 0 :marker-len 6 :bullet-len 6}]

 (parse-blocks "- [x] done")
 := [{:start 0 :length 10 :type :checkbox-checked :indent 0 :marker-len 6 :bullet-len 6}]

 ;; Mixed content
 (let [blocks (parse-blocks "# Title\n- item\n1. num")]
   (mapv :type blocks)
   := [:heading/h1-marker :heading/h1 :bullet-item :numbered-item])

  ;; Code block with language
 (let [text "```clojure\n(+ 1 2)\n```"
       blocks (parse-blocks text)]
   (count blocks) := 1
   (:type (first blocks)) := :code-block
   (:lang (first blocks)) := "clojure"
   (subs text (:start (first blocks))
         (+ (:start (first blocks)) (:length (first blocks))))
   := "(+ 1 2)\n")

  ;; Code block without language
 (let [blocks (parse-blocks "```\nfoo\n```")]
   (:lang (first blocks)) := "")

 :rcf)

;; ---------------------------------------------------------------------------
;; Inline span detection
;; ---------------------------------------------------------------------------

(def ^:private inline-patterns
  "Ordered by priority — longer/more specific patterns first.
  :inline/wiki-draft must precede :inline/link so `[[` is not consumed by `[`."
  [[:inline/bold-italic #"\*\*\*([^*]+)\*\*\*"]
   [:inline/bold        #"\*\*([^*]+)\*\*"]
   ;; Emphasis requires left-flanking (opener followed by non-whitespace) and
   ;; right-flanking (closer preceded by non-whitespace) per CommonMark.
   ;; Without this, a line like `* Some *italic* word` matches the bullet
   ;; marker as the opener and closes at the first real `*`.
   [:inline/italic      #"(?:(?<!\*)\*(?=\S)([^*]+?)(?<=\S)\*(?!\*)|(?<!_)_(?=\S)([^_]+?)(?<=\S)_(?!_))"]
   [:inline/code        #"`([^`]+)`"]
   [:inline/wiki-draft  #"\[\[([^\]]*)\]\]"]
   [:inline/link        #"\[([^\]]*)\]\(([^\)]*)\)"]])

(defn- extract-dest
  "Extract a :dest value from a regex match, based on span type.
  For :inline/link `[text](url)` — the URL (group 2).
  For :inline/wiki-draft `[[target]]` — the inner text (group 1).
  Returns nil for types without link destinations."
  [type-kw ^java.util.regex.Matcher matcher]
  (case type-kw
    :inline/link       (.group matcher 2)
    :inline/wiki-draft (.group matcher 1)
    nil))

(defn- find-inline-spans
  "Find all inline spans within `line`, returning maps with
  :start (relative to line start), :length, :type, and optionally :dest."
  [line]
  (let [;; Collect all matches with their positions
        matches (for [[type-kw pattern] inline-patterns
                      :let [matcher (re-matcher pattern line)]
                      :when matcher
                      match (re-seq pattern line)
                      ;; We need positions, re-seq doesn't give them
                      ;; Use a loop with .find instead
                      :let [_ nil]]
                  nil)]
    ;; re-seq doesn't give positions, use Matcher directly
    (loop [pairs  inline-patterns
           result []
           used   #{}]  ; set of char positions already claimed
      (if (empty? pairs)
        (sort-by :start result)
        (let [[type-kw pattern] (first pairs)
              matcher           (re-matcher pattern line)
              spans             (loop [acc []]
                                  (if (.find matcher)
                                    (let [s    (.start matcher)
                                          e    (.end matcher)
                                          len  (- e s)
                                          dest (extract-dest type-kw matcher)]
                                      (if (some used (range s e))
                                        (recur acc) ; overlaps with higher-priority match
                                        (recur (conj acc (cond-> {:start s :length len :type type-kw}
                                                           dest (assoc :dest dest))))))
                                    acc))
              new-used          (into used (mapcat (fn [{:keys [start length]}]
                                                     (range start (+ start length)))
                                                   spans))]
          (recur (rest pairs) (into result spans) new-used))))))

(tests
 (find-inline-spans "hello **bold** world")
 := [{:start 6 :length 8 :type :inline/bold}]

 (find-inline-spans "***both***")
 := [{:start 0 :length 10 :type :inline/bold-italic}]

 (find-inline-spans "*italic* and **bold**")
 := [{:start 0 :length 8 :type :inline/italic}
     {:start 13 :length 8 :type :inline/bold}]

 (find-inline-spans "`code` here")
 := [{:start 0 :length 6 :type :inline/code}]

 ;; Link with :dest extraction
 (find-inline-spans "[link](http://x)")
 := [{:start 0 :length 16 :type :inline/link :dest "http://x"}]

 ;; Wiki-draft with :dest extraction
 (find-inline-spans "see [[my page]] here")
 := [{:start 4 :length 11 :type :inline/wiki-draft :dest "my page"}]

 ;; Wiki-draft with empty content
 (find-inline-spans "[[]]")
 := [{:start 0 :length 4 :type :inline/wiki-draft :dest ""}]

 ;; Wiki link (wiki:uuid) via standard link
 (find-inline-spans "[title](wiki:abc-123)")
 := [{:start 0 :length 21 :type :inline/link :dest "wiki:abc-123"}]

 ;; Wiki-draft does not consume [link](...) syntax
 (find-inline-spans "[text](url) and [[draft]]")
 := [{:start 0 :length 11 :type :inline/link :dest "url"}
     {:start 16 :length 9 :type :inline/wiki-draft :dest "draft"}]

 (find-inline-spans "plain text")
 := []

 ;; Bullet marker must not be treated as an italic opener.
 (find-inline-spans "* Some *italic* word")
 := [{:start 7 :length 8 :type :inline/italic}]

 ;; Nested bullet (leading whitespace before `*`) — same rule applies.
 (find-inline-spans "  * nested *em* text")
 := [{:start 11 :length 4 :type :inline/italic}]

 ;; Horizontal rule-ish lines of bare `*` separated by spaces must not
 ;; match as italic.
 (find-inline-spans "* * *")
 := []

 ;; CommonMark: trailing whitespace invalidates emphasis.
 (find-inline-spans "*has trailing *")
 := []

 ;; Underscore analogue.
 (find-inline-spans "_em_ word")
 := [{:start 0 :length 4 :type :inline/italic}]

 :rcf)

;; ---------------------------------------------------------------------------
;; Theme — main entry point
;; ---------------------------------------------------------------------------

(defn- split-around
  "Given a parent span and sorted child spans, produce non-overlapping body
  segments for the gaps between children, plus the children themselves."
  [parent-start parent-length parent-type children]
  (let [end (+ parent-start parent-length)]
    (loop [pos      parent-start
           children children
           acc      []]
      (if (empty? children)
        (if (< pos end)
          (conj acc {:start pos :length (- end pos) :type parent-type})
          acc)
        (let [{:keys [start length] :as child} (first children)
              gap (- start pos)]
          (recur (+ start length)
                 (rest children)
                 (cond-> acc
                   (pos? gap) (conj {:start pos :length gap :type parent-type})
                   true       (conj child))))))))

(defn theme
  "Parse `text` and return a seq of span maps covering the full document.
  Code blocks with a known language include syntax token spans (shifted to
  document offsets). No SWT objects created.
  Spans are non-overlapping and sorted by start offset."
  [text]
  (let [blocks (parse-blocks text)]
    (reduce
     (fn [acc block]
       (case (:type block)
          ;; Code blocks — split around token spans
         :code-block
         (let [code    (subs text (:start block)
                             (+ (:start block) (:length block)))
               tokens  (when (seq (:lang block))
                         (highlight/tokenize (:lang block) code))
               shifted (map (fn [t]
                              (-> t
                                  (update :start + (:start block))
                                  (dissoc :lang)))
                            tokens)]
           (if (seq shifted)
             (into acc (split-around (:start block) (:length block)
                                     :code-block shifted))
             (conj acc block)))

          ;; Body lines — split around inline spans
         :body
         (let [line    (subs text (:start block)
                             (+ (:start block) (:length block)))
               inlines (find-inline-spans line)
               shifted (map (fn [s] (update s :start + (:start block)))
                            inlines)]
           (if (seq shifted)
             (into acc (split-around (:start block) (:length block)
                                     :body shifted))
             (conj acc block)))

          ;; List items — split around inline spans, propagate list metadata
         (:bullet-item :numbered-item :checkbox-unchecked :checkbox-checked)
         (let [line    (subs text (:start block)
                             (+ (:start block) (:length block)))
               inlines (find-inline-spans line)
               shifted (map (fn [s] (update s :start + (:start block)))
                            inlines)
               meta-keys (select-keys block [:indent :marker-len :bullet-len :number])]
           (if (seq shifted)
             (into acc (map #(merge % meta-keys)
                            (split-around (:start block) (:length block)
                                          (:type block) shifted)))
             (conj acc block)))

          ;; Everything else (headings, blockquotes) — pass through
         (conj acc block)))
     []
     blocks)))

(tests
 (theme "# Hello")
 := [{:start 0 :length 2 :type :heading/h1-marker}
     {:start 2 :length 5 :type :heading/h1}]

  ;; Body with inline — no overlaps
 (let [spans (theme "some **bold** text")]
   (some #(= :body (:type %)) spans) := true
   (some #(= :inline/bold (:type %)) spans) := true
   ;; Verify non-overlapping: each span starts at or after the previous ends
   (every? true?
           (map (fn [a b] (<= (+ (:start a) (:length a)) (:start b)))
                spans (rest spans))) := true)

  ;; Code block with tokens
 (let [spans (theme "```clojure\n(+ 1 2)\n```")]
   (some #(= :code-block (:type %)) spans) := true
   (some #(= :token/builtin (:type %)) spans) := true)

  ;; :dest survives through theme pipeline for links
 (let [spans (theme "click [here](http://x) now")]
   (:dest (first (filter #(= :inline/link (:type %)) spans)))
   := "http://x")

  ;; :dest survives for wiki-draft
 (let [spans (theme "see [[my page]] here")]
   (:dest (first (filter #(= :inline/wiki-draft (:type %)) spans)))
   := "my page")

 :rcf)
