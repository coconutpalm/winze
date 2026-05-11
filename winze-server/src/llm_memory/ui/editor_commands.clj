(ns llm-memory.ui.editor-commands
  "Editor commands — text manipulation primitives and command registrations
  for formatting, headings, lists, line operations, and insert commands."
  (:require
   [clojure.string :as str]
   [llm-memory.ui.commands :as commands]
   [llm-memory.ui.find-replace :as find-replace]
   [llm-memory.ui.link-preview :as link-preview]
   [llm-memory.ui.markdown-editor :as md-editor]
   [llm-memory.ui.md-theme :as md-theme]
   [llm-memory.ui.spellcheck :as spellcheck]
   [llm-memory.ui.spellcheck-menu :as spellcheck-menu]
   [ui.SWT :refer [async-exec!]]
   [hyperfiddle.rcf :refer [tests]])
  (:import
   [java.time LocalDate LocalTime]
   [java.time.format DateTimeFormatter]
   [org.eclipse.swt.custom StyledText]
   [org.eclipse.swt.graphics Point]))

;; ---------------------------------------------------------------------------
;; Active StyledText provider — set by main-window at startup to break
;; the circular dependency (editor-commands <-> main-window).
;; ---------------------------------------------------------------------------

(defonce ^:private active-styled-text-fn (atom nil))

(defn set-active-styled-text-fn!
  "Set the function that returns the active StyledText widget (or nil).
  Called by main-window during initialization."
  [f]
  (reset! active-styled-text-fn f))

(defn- active-styled-text
  "Return the active StyledText widget, or nil."
  []
  (when-let [f @active-styled-text-fn]
    (f)))

;; ---------------------------------------------------------------------------
;; Text manipulation primitives (pure helpers operating on StyledText)
;; ---------------------------------------------------------------------------

(defn selected-lines-range
  "Return [first-line last-line] (0-based) for the current selection or caret line.
  If no selection, both values are the caret line."
  [^StyledText st]
  (let [sel   (.getSelectionRange st)
        start (.-x sel)
        len   (.-y sel)]
    (if (zero? len)
      (let [line (.getLineAtOffset st start)]
        [line line])
      (let [first-line (.getLineAtOffset st start)
            ;; end offset is exclusive, back up 1 to get the last selected char
            end-offset (+ start len -1)
            last-line  (.getLineAtOffset st (max start end-offset))]
        [first-line last-line]))))

(defn toggle-inline-wrap
  "Wrap or unwrap the selection with `delimiter` (e.g. \"**\" for bold).
  Checks three cases:
    1. Selection includes delimiters (e.g. selected \"**hello**\") → unwrap
    2. Delimiters surround the selection in the text (e.g. selected \"hello\" in \"**hello**\") → unwrap
    3. Otherwise → wrap, and keep the inner text selected
  If no selection, inserts paired delimiters with caret between."
  [^StyledText st ^String delimiter]
  (let [text     (.getText st)
        tlen     (count text)
        sel      (.getSelectionRange st)
        start    (.-x sel)
        len      (.-y sel)
        dlen     (count delimiter)
        selected (subs text start (+ start len))]
    (if (pos? len)
      (cond
        ;; Case 1: selection itself starts+ends with delimiter → unwrap selection
        (and (>= (count selected) (* 2 dlen))
             (str/starts-with? selected delimiter)
             (str/ends-with? selected delimiter))
        (let [inner (subs selected dlen (- (count selected) dlen))]
          {:replace-start  start
           :replace-length len
           :replacement    inner
           :select-after   [start (count inner)]})

        ;; Case 2: surrounding text has delimiters → unwrap by removing them
        (and (>= start dlen)
             (<= (+ start len dlen) tlen)
             (= delimiter (subs text (- start dlen) start))
             (= delimiter (subs text (+ start len) (+ start len dlen))))
        {:replace-start  (- start dlen)
         :replace-length (+ len (* 2 dlen))
         :replacement    selected
         :select-after   [(- start dlen) (count selected)]}

        ;; Case 3: wrap — add delimiters, keep inner text selected
        :else
        {:replace-start  start
         :replace-length len
         :replacement    (str delimiter selected delimiter)
         :select-after   [(+ start dlen) (count selected)]})

      ;; No selection — insert paired delimiters with caret between
      {:replace-start  start
       :replace-length 0
       :replacement    (str delimiter delimiter)
       :caret          (+ start dlen)})))

(defn- strip-any-prefix
  "If `line` starts with any string in `prefixes`, return the stripped line
  and the prefix that was matched. Otherwise [line nil]."
  [line prefixes]
  (if-let [p (first (filter #(str/starts-with? line %) prefixes))]
    [(subs line (count p)) p]
    [line nil]))

(defn toggle-line-prefix
  "Add or remove `prefix` on each line in the selection range.
  `alt-prefixes` (optional) — additional prefixes to recognise as equivalent
  when detecting whether a line already has the prefix (e.g. [\"* \"] for bullets).
  Returns {:replace-start :replace-length :replacement :caret}."
  ([^StyledText st ^String prefix] (toggle-line-prefix st prefix nil))
  ([^StyledText st ^String prefix alt-prefixes]
   (let [[first-line last-line] (selected-lines-range st)
         all-prefixes (into [prefix] alt-prefixes)
         lines   (mapv #(.getLine st %) (range first-line (inc last-line)))
         parsed  (mapv #(strip-any-prefix % all-prefixes) lines)
         all-have? (every? (fn [[_ matched]] matched) parsed)
         caret   (.getCaretOffset st)
         caret-line (.getLineAtOffset st caret)
         caret-col  (- caret (.getOffsetAtLine st caret-line))
         ;; If removing: strip matched prefix. If adding: prepend canonical prefix.
         new-lines (if all-have?
                     (mapv first parsed)
                     (mapv (fn [line]
                             (let [[_ matched] (strip-any-prefix line all-prefixes)]
                               (if matched
                                 line
                                 (str prefix line))))
                           lines))
         start  (.getOffsetAtLine st first-line)
         end    (if (= last-line (dec (.getLineCount st)))
                  (count (.getText st))
                  (.getOffsetAtLine st (inc last-line)))
         text-in-range (subs (.getText st) start end)
         trailing-nl?  (str/ends-with? text-in-range "\n")
         replacement   (str (str/join "\n" new-lines)
                            (when trailing-nl? "\n"))
         ;; Preserve cursor column: adjust for prefix length change on the caret's line
         caret-line-idx (- caret-line first-line)
         old-line (get lines caret-line-idx)
         new-line (get new-lines caret-line-idx)
         prefix-delta (- (count new-line) (count old-line))
         new-caret-col (max 0 (+ caret-col prefix-delta))
         new-caret-line-start (+ start (reduce + 0 (map #(inc (count %)) (take caret-line-idx new-lines))))
         new-caret (+ new-caret-line-start new-caret-col)]
     {:replace-start  start
      :replace-length (- end start)
      :replacement    replacement
      :caret          (min new-caret (+ start (count replacement)))})))

(defn set-heading-level
  "Set the current line to heading level N (1-6). Toggle off if already at level N.
  Preserves cursor position relative to the text content."
  [^StyledText st n]
  (let [caret  (.getCaretOffset st)
        line   (.getLineAtOffset st caret)
        text   (.getLine st line)
        col    (- caret (.getOffsetAtLine st line))
        match  (re-find #"^(#{1,6})\s" text)
        current-level (when match (count (second match)))
        old-prefix-len (if current-level (+ current-level 1) 0)
        target-prefix (str (apply str (repeat n \#)) " ")
        start  (.getOffsetAtLine st line)
        [new-text new-prefix-len]
        (if (= current-level n)
          [(str/replace-first text #"^#{1,6}\s+" "") 0]
          (if current-level
            [(str target-prefix (str/replace-first text #"^#{1,6}\s+" "")) (count target-prefix)]
            [(str target-prefix text) (count target-prefix)]))
        ;; Adjust cursor: keep position relative to content after prefix
        content-col (max 0 (- col old-prefix-len))
        new-col (+ new-prefix-len content-col)]
    {:replace-start  start
     :replace-length (count text)
     :replacement    new-text
     :caret          (min (+ start new-col) (+ start (count new-text)))}))

(defn insert-at-cursor
  "Insert `text-to-insert` at the caret position.
  `cursor-offset-from-start` — if non-nil, place caret this many chars after the insert start.
  Returns {:replace-start :replace-length :replacement :caret}."
  [^StyledText st ^String text-to-insert & {:keys [cursor-offset]}]
  (let [caret (.getCaretOffset st)]
    {:replace-start  caret
     :replace-length 0
     :replacement    text-to-insert
     :caret          (if cursor-offset
                       (+ caret cursor-offset)
                       (+ caret (count text-to-insert)))}))

;; ---------------------------------------------------------------------------
;; Line operations (pure helpers)
;; ---------------------------------------------------------------------------

(defn- leading-spaces
  "Count the number of leading space characters on a line."
  [^String line]
  (let [n (count line)]
    (loop [i 0]
      (if (and (< i n) (= \space (.charAt line i)))
        (recur (inc i))
        i))))

(defn- outline-children-end
  "Return the last line index (inclusive) of the outline subtree rooted at
  `root-line`. Children are subsequent lines with indent strictly greater
  than the root line's indent. Stops at the first line with indent <= root."
  [^StyledText st root-line]
  (let [line-count    (.getLineCount st)
        root-indent   (leading-spaces (.getLine st root-line))]
    (loop [i (inc root-line)]
      (if (>= i line-count)
        (dec line-count)
        (let [indent (leading-spaces (.getLine st i))]
          (if (<= indent root-indent)
            (dec i)
            (recur (inc i))))))))

(defn- replace-lines
  "Build a replacement edit for lines first-line..last-line using new-lines.
  Preserves cursor column relative to the caret's line."
  [^StyledText st first-line last-line lines new-lines]
  (let [caret      (.getCaretOffset st)
        caret-line (.getLineAtOffset st caret)
        caret-col  (- caret (.getOffsetAtLine st caret-line))
        caret-idx  (- caret-line first-line)
        start      (.getOffsetAtLine st first-line)
        end        (if (= last-line (dec (.getLineCount st)))
                     (count (.getText st))
                     (.getOffsetAtLine st (inc last-line)))
        text-in-range (subs (.getText st) start end)
        trailing-nl?  (str/ends-with? text-in-range "\n")
        replacement   (str (str/join "\n" new-lines)
                           (when trailing-nl? "\n"))
        delta      (- (count (get new-lines caret-idx))
                      (count (get lines caret-idx)))
        new-col    (max 0 (+ caret-col delta))
        new-caret-line-start (+ start (reduce + 0 (map #(inc (count %)) (take caret-idx new-lines))))]
    {:replace-start  start
     :replace-length (- end start)
     :replacement    replacement
     :caret          (min (+ new-caret-line-start new-col)
                          (+ start (count replacement)))}))

(defn indent-lines
  "Indent the selected lines (or caret line) plus all outline children.
  Only indents if the previous line's indent >= the first selected line's indent."
  [^StyledText st]
  (let [[first-line sel-last] (selected-lines-range st)
        ;; Extend range to include outline children of the last selected line
        last-line  (outline-children-end st sel-last)
        lines      (mapv #(.getLine st %) (range first-line (inc last-line)))
        prev-indent (if (pos? first-line)
                      (leading-spaces (.getLine st (dec first-line)))
                      Integer/MAX_VALUE)
        first-indent (leading-spaces (first lines))]
    (when (<= first-indent prev-indent)
      (let [new-lines (mapv #(str "  " %) lines)]
        (replace-lines st first-line last-line lines new-lines)))))

(defn- outdent-line
  "Remove up to two leading spaces (or one tab) from a line."
  [^String line]
  (cond
    (str/starts-with? line "  ") (subs line 2)
    (str/starts-with? line " ")  (subs line 1)
    (str/starts-with? line "\t") (subs line 1)
    :else line))

(defn outdent-lines
  "Remove up to two leading spaces from the selected lines plus all outline
  children. Preserves cursor column position."
  [^StyledText st]
  (let [[first-line sel-last] (selected-lines-range st)
        last-line  (outline-children-end st sel-last)
        lines      (mapv #(.getLine st %) (range first-line (inc last-line)))
        new-lines  (mapv outdent-line lines)]
    (replace-lines st first-line last-line lines new-lines)))

(defn move-line-up
  "Move the current line (or selected lines) up by one.
  Returns nil if already at the top."
  [^StyledText st]
  (let [[first-line last-line] (selected-lines-range st)
        line-count (.getLineCount st)]
    (when (pos? first-line)
      (let [above-line  (.getLine st (dec first-line))
            lines       (mapv #(.getLine st %) (range first-line (inc last-line)))
            ;; Range: from start of line above to end of last selected line
            range-start (.getOffsetAtLine st (dec first-line))
            range-end   (if (= last-line (dec line-count))
                          (count (.getText st))
                          (.getOffsetAtLine st (inc last-line)))
            text-in-range (subs (.getText st) range-start range-end)
            trailing-nl?  (str/ends-with? text-in-range "\n")
            new-text    (str (str/join "\n" (concat lines [above-line]))
                             (when trailing-nl? "\n"))]
        {:replace-start  range-start
         :replace-length (- range-end range-start)
         :replacement    new-text
         :caret          (.getOffsetAtLine st (dec first-line))}))))

(defn move-line-down
  "Move the current line (or selected lines) down by one.
  Returns nil if already at the bottom."
  [^StyledText st]
  (let [[first-line last-line] (selected-lines-range st)
        line-count (.getLineCount st)]
    (when (< last-line (dec line-count))
      (let [below-line  (.getLine st (inc last-line))
            lines       (mapv #(.getLine st %) (range first-line (inc last-line)))
            range-start (.getOffsetAtLine st first-line)
            range-end   (if (= (inc last-line) (dec line-count))
                          (count (.getText st))
                          (.getOffsetAtLine st (+ last-line 2)))
            text-in-range (subs (.getText st) range-start range-end)
            trailing-nl?  (str/ends-with? text-in-range "\n")
            new-text    (str (str/join "\n" (concat [below-line] lines))
                             (when trailing-nl? "\n"))]
        {:replace-start  range-start
         :replace-length (- range-end range-start)
         :replacement    new-text
         :caret          (+ (.getOffsetAtLine st first-line)
                            (count below-line)
                            1)}))))

(defn delete-line
  "Delete the current line. Returns {:replace-start :replace-length :replacement :caret}."
  [^StyledText st]
  (let [caret      (.getCaretOffset st)
        line       (.getLineAtOffset st caret)
        line-count (.getLineCount st)
        start      (.getOffsetAtLine st line)
        end        (if (= line (dec line-count))
                     (count (.getText st))
                     (.getOffsetAtLine st (inc line)))]
    {:replace-start  start
     :replace-length (- end start)
     :replacement    ""
     :caret          (min start (max 0 (- (count (.getText st))
                                          (- end start))))}))

(defn duplicate-line
  "Duplicate the current line below. Returns {:replace-start :replace-length :replacement :caret}."
  [^StyledText st]
  (let [caret      (.getCaretOffset st)
        line       (.getLineAtOffset st caret)
        line-text  (.getLine st line)
        line-count (.getLineCount st)
        start      (.getOffsetAtLine st line)
        end        (if (= line (dec line-count))
                     (count (.getText st))
                     (.getOffsetAtLine st (inc line)))
        original   (subs (.getText st) start end)
        has-nl?    (str/ends-with? original "\n")
        dup        (if has-nl?
                     (str original line-text "\n")
                     (str original "\n" line-text))]
    {:replace-start  start
     :replace-length (- end start)
     :replacement    dup
     :caret          (+ start (count original) (if has-nl? 0 1))}))

(defn select-line
  "Select the entire current line. Returns {:select-start :select-length}."
  [^StyledText st]
  (let [caret      (.getCaretOffset st)
        line       (.getLineAtOffset st caret)
        line-count (.getLineCount st)
        start      (.getOffsetAtLine st line)
        end        (if (= line (dec line-count))
                     (count (.getText st))
                     (.getOffsetAtLine st (inc line)))]
    {:select-start  start
     :select-length (- end start)}))

(defn select-all
  "Select all text. Returns {:select-start :select-length}."
  [^StyledText st]
  {:select-start  0
   :select-length (count (.getText st))})

(defn delete-visible-line
  "Delete the current visible (wrapped) line. If the document line is wrapped,
  deletes only the visual row the caret is on, leaving the rest of the line.
  If the line is not wrapped, deletes the entire document line."
  [^StyledText st]
  (let [caret   (.getCaretOffset st)
        text    (.getText st)
        tlen    (count text)
        ;; Get the pixel y of the caret
        loc     (.getLocationAtOffset st caret)
        caret-y (.y loc)
        line    (.getLineAtOffset st caret)
        line-start (.getOffsetAtLine st line)
        line-count (.getLineCount st)
        line-end   (if (= line (dec line-count))
                     tlen
                     (.getOffsetAtLine st (inc line)))
        ;; Find the start of the visual row: scan backward from caret
        ;; to find the first offset on this visual row (same pixel y)
        vis-start (loop [off caret]
                    (if (<= off line-start)
                      line-start
                      (let [prev-y (.y (.getLocationAtOffset st (dec off)))]
                        (if (< prev-y caret-y)
                          off
                          (recur (dec off))))))
        ;; Find the end of the visual row: scan forward to find the
        ;; first offset on the NEXT visual row (different pixel y)
        vis-end   (loop [off caret]
                    (if (>= off line-end)
                      line-end
                      (let [next-y (.y (.getLocationAtOffset st off))]
                        (if (> next-y caret-y)
                          off
                          (recur (inc off))))))]
    ;; If the visible line covers the entire document line, delete the newline too
    (if (and (= vis-start line-start) (= vis-end line-end))
      ;; Whole document line — delete including trailing newline
      (let [del-end (min tlen (if (and (< line-end tlen)
                                       (= \newline (.charAt text (dec line-end))))
                                line-end
                                line-end))]
        {:replace-start  vis-start
         :replace-length (- del-end vis-start)
         :replacement    ""
         :caret          (min vis-start (max 0 (- tlen (- del-end vis-start))))})
      ;; Partial visual row within a wrapped line
      {:replace-start  vis-start
       :replace-length (- vis-end vis-start)
       :replacement    ""
       :caret          (min vis-start (max 0 (- tlen (- vis-end vis-start))))})))

(defn continue-list
  "When Enter is pressed in the editor: if on a list line, inserts a new
  list item at the same indent level (incrementing numbers, unchecking
  checkboxes). If the current line is an empty list item (marker only),
  deletes the marker to end the list. On non-list lines, inserts a
  plain newline."
  [^StyledText st]
  (let [caret (.getCaretOffset st)
        line  (.getLineAtOffset st caret)
        text  (.getLine st line)]
    (if-let [li (md-theme/parse-list-item text)]
      (let [leading-spaces (subs text 0 (- (:marker-len li) (:bullet-len li)))
            ;; Check if line has content after the marker
            content (subs text (:marker-len li))
            empty-item? (str/blank? content)]
        (if empty-item?
          ;; Empty list item — delete the marker to end the list
          (let [start (.getOffsetAtLine st line)]
            {:replace-start  start
             :replace-length (count text)
             :replacement    ""
             :caret          start})
          ;; Has content — insert a new list item after the caret
          (let [next-marker (case (:type li)
                              :bullet-item
                              ;; Preserve the actual bullet character (- * +)
                              (let [[_ _ ch] (re-matches #"^( *)([-*+]) .*" text)]
                                (str leading-spaces (or ch "-") " "))
                              :numbered-item
                              (str leading-spaces (inc (:number li)) ". ")
                              :checkbox-unchecked
                              (let [[_ _ ch] (re-matches #"^( *)([-*+]) \[.\] .*" text)]
                                (str leading-spaces (or ch "-") " [ ] "))
                              :checkbox-checked
                              (let [[_ _ ch] (re-matches #"^( *)([-*+]) \[.\] .*" text)]
                                (str leading-spaces (or ch "-") " [ ] ")))]
            {:replace-start  caret
             :replace-length 0
             :replacement    (str "\n" next-marker)
             :caret          (+ caret 1 (count next-marker))})))
      ;; Not a list line — insert a plain newline
      {:replace-start  caret
       :replace-length 0
       :replacement    "\n"
       :caret          (inc caret)})))

(tests
 ;; select-all
 ;; (can't test on real StyledText in RCF but verify shape)

 ;; continue-list marker generation
 (let [text "- item"]
   (when-let [[_ spaces ch] (re-matches #"^( *)([-*+]) .*" text)]
     (str spaces ch " ")))
 := "- "

 (let [text "  * nested"]
   (when-let [[_ spaces ch] (re-matches #"^( *)([-*+]) .*" text)]
     (str spaces ch " ")))
 := "  * "

 :rcf)

;; ---------------------------------------------------------------------------
;; Apply edit result to StyledText
;; ---------------------------------------------------------------------------

(defn apply-edit!
  "Apply an edit result map to the StyledText widget.
  Supports three modes:
    - :select-start/:select-length — selection-only (no text change)
    - :replace-start/:replace-length/:replacement + :select-after [start len] — replace then select
    - :replace-start/:replace-length/:replacement + :caret — replace then position caret
  Must be called on the UI thread."
  [^StyledText st edit-result]
  (when edit-result
    (if (:select-start edit-result)
      ;; Selection-only operation
      (.setSelection st
                     (:select-start edit-result)
                     (+ (:select-start edit-result) (:select-length edit-result)))
      ;; Text replacement
      (do
        (.replaceTextRange st
                           (:replace-start edit-result)
                           (:replace-length edit-result)
                           (:replacement edit-result))
        (if-let [[sel-start sel-len] (:select-after edit-result)]
          ;; Restore selection on the inner text
          (let [max-pos (count (.getText st))]
            (.setSelection st
                           (min sel-start max-pos)
                           (min (+ sel-start sel-len) max-pos)))
          ;; Just position the caret
          (.setCaretOffset st (min (:caret edit-result)
                                   (count (.getText st)))))
        ;; Scroll the caret/selection into view. SWT's built-in key
        ;; actions do this automatically, but .replaceTextRange +
        ;; .setCaretOffset / .setSelection do not.
        (.showSelection st)))))

;; ---------------------------------------------------------------------------
;; Command action helper
;; ---------------------------------------------------------------------------

(defn- editor-action
  "Create an action fn that finds the active StyledText and applies `edit-fn`.
  `edit-fn` receives the StyledText and returns an edit result map (or nil).
  Automatically re-applies the theme after text changes."
  [edit-fn]
  (fn []
    (async-exec!
     (fn []
       (when-let [st (active-styled-text)]
         (let [result (edit-fn st)]
           (apply-edit! st result)
           ;; Re-apply theme after text modifications (not needed for select-only)
           (when (and result (not (:select-start result)))
             (md-editor/apply-theme! st (.getText st)))))))))

;; ---------------------------------------------------------------------------
;; Step 2: Inline formatting commands
;; ---------------------------------------------------------------------------

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn register-inline-formatting-commands!
  "Register inline formatting toggle commands."
  []
  (doseq [[id label delimiter]
          [[:editor/toggle-bold          "Toggle Bold"          "**"]
           [:editor/toggle-italic        "Toggle Italic"        "*"]
           [:editor/toggle-strikethrough "Toggle Strikethrough" "~~"]
           [:editor/toggle-inline-code   "Toggle Inline Code"   "`"]
           [:editor/toggle-highlight     "Toggle Highlight"     "=="]]]
    (commands/register!
     {:id       id
      :label    label
      :category :edit
      :action   (editor-action (fn [st] (toggle-inline-wrap st delimiter)))})))

;; ---------------------------------------------------------------------------
;; Step 3: Heading and list commands
;; ---------------------------------------------------------------------------

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn register-heading-list-commands!
  "Register heading-level and list-toggle commands."
  []
  ;; Headings 1-6
  (doseq [n (range 1 7)]
    (commands/register!
     {:id       (keyword "editor" (str "heading-" n))
      :label    (str "Heading " n)
      :category :edit
      :action   (editor-action (fn [st] (set-heading-level st n)))}))

  ;; List toggles — alt-prefixes are equivalent syntaxes to recognise when toggling off
  (doseq [[id label prefix alt-prefixes]
          [[:editor/toggle-bullet     "Toggle Bullet"     "- " ["* " "+ "]]
           [:editor/toggle-numbered   "Toggle Numbered"   "1. " nil]
           [:editor/toggle-checkbox   "Toggle Checkbox"   "- [ ] " ["* [ ] " "+ [ ] "]]
           [:editor/toggle-blockquote "Toggle Blockquote" "> "  nil]]]
    (commands/register!
     {:id       id
      :label    label
      :category :edit
      :action   (editor-action (fn [st] (toggle-line-prefix st prefix alt-prefixes)))})))

;; ---------------------------------------------------------------------------
;; Step 4: Line operations
;; ---------------------------------------------------------------------------

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn register-line-commands!
  "Register line manipulation commands."
  []
  (commands/register!
   {:id       :editor/indent
    :label    "Indent"
    :category :edit
    :action   (editor-action indent-lines)})

  (commands/register!
   {:id       :editor/outdent
    :label    "Outdent"
    :category :edit
    :action   (editor-action outdent-lines)})

  (commands/register!
   {:id       :editor/move-line-up
    :label    "Move Line Up"
    :category :edit
    :action   (editor-action move-line-up)})

  (commands/register!
   {:id       :editor/move-line-down
    :label    "Move Line Down"
    :category :edit
    :action   (editor-action move-line-down)})

  (commands/register!
   {:id       :editor/delete-line
    :label    "Delete Line"
    :category :edit
    :action   (editor-action delete-line)})

  (commands/register!
   {:id       :editor/duplicate-line
    :label    "Duplicate Line"
    :category :edit
    :action   (editor-action duplicate-line)})

  (commands/register!
   {:id       :editor/select-line
    :label    "Select Line"
    :category :edit
    :action   (editor-action select-line)})

  (commands/register!
   {:id       :editor/select-all
    :label    "Select All"
    :category :edit
    :action   (editor-action select-all)})

  (commands/register!
   {:id       :editor/delete-visible-line
    :label    "Delete Visible Line"
    :category :edit
    :action   (editor-action delete-visible-line)})

  (commands/register!
   {:id       :editor/continue-list
    :label    "Continue List"
    :category :edit
    :action   (editor-action continue-list)}))

;; ---------------------------------------------------------------------------
;; Step 5: Insert commands (palette-only, no hotkey)
;; ---------------------------------------------------------------------------

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn register-insert-commands!
  "Register insert commands (palette-only, no keybindings)."
  []
  (commands/register!
   {:id       :editor/insert-hr
    :label    "Insert Horizontal Rule"
    :category :edit
    :action   (editor-action
               (fn [st] (insert-at-cursor st "\n---\n")))})

  (commands/register!
   {:id       :editor/insert-code-block
    :label    "Insert Code Block"
    :category :edit
    :action   (editor-action
               (fn [st] (insert-at-cursor st "\n```\n\n```\n"
                                          :cursor-offset 5)))})

  (commands/register!
   {:id       :editor/insert-table
    :label    "Insert Table"
    :category :edit
    :action   (editor-action
               (fn [st] (insert-at-cursor st "\n| Header 1 | Header 2 |\n| -------- | -------- |\n|          |          |\n"
                                          :cursor-offset 3)))})

  (commands/register!
   {:id       :editor/insert-date
    :label    "Insert Current Date"
    :category :edit
    :action   (editor-action
               (fn [st] (insert-at-cursor st (.format (LocalDate/now)
                                                      DateTimeFormatter/ISO_LOCAL_DATE))))})

  (commands/register!
   {:id       :editor/insert-time
    :label    "Insert Current Time"
    :category :edit
    :action   (editor-action
               (fn [st] (insert-at-cursor st (.format (LocalTime/now)
                                                      (DateTimeFormatter/ofPattern "HH:mm")))))}))

;; ---------------------------------------------------------------------------
;; Register all editor commands
;; ---------------------------------------------------------------------------

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn register-link-commands!
  "Register link-related commands (Insert Link via content assist)."
  []
  (commands/register!
   {:id       :editor/insert-link
    :label    "Insert Link"
    :category :insert
    :action   (fn []
                (async-exec!
                 (fn []
                   (when-let [st (active-styled-text)]
                     (md-editor/handle-insert-link! st nil)))))}))

(defn- register-find-replace-commands!
  "Register find and find/replace commands."
  []
  (commands/register!
   {:id       :editor/find
    :label    "Find"
    :category :find
    :action   (fn []
                (async-exec!
                 (fn []
                   (let [active-browser-fn (requiring-resolve
                                            'llm-memory.ui.main-window/active-browser)]
                     (if-let [st (active-styled-text)]
                       (find-replace/open-find-bar!
                        {:styled-text st :show-replace? false})
                       (when-let [brow (active-browser-fn)]
                         (find-replace/open-find-bar!
                          {:browser brow})))))))})

  (commands/register!
   {:id       :editor/find-replace
    :label    "Find and Replace"
    :category :find
    :action   (fn []
                (async-exec!
                 (fn []
                   (when-let [st (active-styled-text)]
                     (find-replace/open-find-bar!
                      {:styled-text st :show-replace? true})))))})

  (commands/register!
   {:id       :workbench/dismiss-find-bar
    :label    "Dismiss Find Bar"
    :category :workbench
    :action   (fn [] (async-exec! find-replace/close-find-bar!))})

  (commands/register!
   {:id       :workbench/dismiss-link-preview
    :label    "Dismiss Link Preview"
    :category :workbench
    :action   (fn [] (async-exec! link-preview/close-link-preview!))}))

;; ---------------------------------------------------------------------------
;; Step 7: Spellcheck commands
;; ---------------------------------------------------------------------------

(defn- letter?
  "Predicate matching the spellcheck tokeniser: ASCII letter or
  apostrophe (straight or curly)."
  [^Character ch]
  (or (Character/isLetter ch)
      (= ch \')
      (= ch \u2019)))

(defn- word-at-caret
  "Return the word surrounding the current caret in `st`, or nil if
  the caret is not inside any word.  Prefers an already-identified
  misspelling (authoritative, includes contractions); otherwise walks
  left and right through letter/apostrophe characters."
  [^StyledText st]
  (let [off  (.getCaretOffset st)
        miss (first (filter (fn [{:keys [start length]}]
                              (and (<= start off) (< off (+ start length))))
                            (or (.getData st "spellcheck/miss") [])))]
    (if miss
      (:text miss)
      (let [text (.getText st)
            n    (.length text)]
        (when (and (pos? n) (<= off n))
          (let [left  (loop [i off]
                        (if (and (pos? i) (letter? (.charAt text (dec i))))
                          (recur (dec i))
                          i))
                right (loop [i off]
                        (if (and (< i n) (letter? (.charAt text i)))
                          (recur (inc i))
                          i))]
            (when (< left right)
              (subs text left right))))))))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn register-spellcheck-commands!
  "Register spellcheck-related commands."
  []
  (commands/register!
   {:id       :editor/spellcheck-suggest
    :label    "Show Spellcheck Suggestions"
    :category :edit
    :action   (fn []
                (async-exec!
                 (fn []
                   (when-let [st (active-styled-text)]
                     (spellcheck-menu/show-suggestion-menu-at-caret! st)))))})

  (commands/register!
   {:id       :editor/spellcheck-add-word
    :label    "Add Word to Dictionary"
    :category :edit
    :action   (fn []
                (async-exec!
                 (fn []
                   (when-let [st (active-styled-text)]
                     (when-let [w (word-at-caret st)]
                       (spellcheck/add-to-user-dict! w))))))})

  (commands/register!
   {:id       :editor/spellcheck-ignore-word
    :label    "Ignore Word This Session"
    :category :edit
    :action   (fn []
                (async-exec!
                 (fn []
                   (when-let [st (active-styled-text)]
                     (when-let [w (word-at-caret st)]
                       (spellcheck/ignore-this-session! w))))))}))

(defn register-all!
  "Register all editor commands. Call once at startup."
  []
  (register-inline-formatting-commands!)
  (register-heading-list-commands!)
  (register-line-commands!)
  (register-insert-commands!)
  (register-link-commands!)
  (register-find-replace-commands!)
  (register-spellcheck-commands!))

;; ---------------------------------------------------------------------------
;; RCF tests — pure text manipulation logic
;; ---------------------------------------------------------------------------

(tests
 ;; toggle-inline-wrap — these test the logic on string manipulation
 ;; We can't test on real StyledText in RCF, but we verify the shape

 ;; set-heading-level logic (manual test of heading prefix parsing)
 (let [text   "## Hello World"
       match  (re-find #"^(#{1,6})\s" text)
       level  (when match (count (second match)))]
   level := 2)

 ;; heading prefix generation
 (apply str (repeat 3 \#)) := "###"

 ;; toggle-line-prefix detection
 (str/starts-with? "- item" "- ") := true
 (str/starts-with? "item" "- ") := false

 :rcf)
