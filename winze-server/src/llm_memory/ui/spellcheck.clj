(ns llm-memory.ui.spellcheck
  "Live spellcheck for the Markdown editor.

  Pure computation only — no SWT widget access happens here beyond the
  compile-time `SWT/UNDERLINE_SQUIGGLE` integer constant.  The SWT glue
  (context menu, caret translation) lives in `spellcheck_menu.clj`.

  Public surface:
    * `misspellings-for`    — compute misspelling spans from a document
    * `merge-misspellings`  — fold squiggly-underline style onto theme spans
    * `suggestions`         — up-to-10 alphabetically-sorted corrections
    * `add-to-user-dict!`   — persist a word to the user dictionary
    * `ignore-this-session!` — in-memory ignore for the current session
    * `register-refresh-listener!` — fan dictionary changes out to editors"
  (:require
   [clojure.java.io :as io]
   [clojure.string  :as str]
   [llm-memory.ui.resources :as res]
   [hyperfiddle.rcf :refer [tests]])
  (:import
   [org.eclipse.swt SWT]
   [java.util.regex Matcher Pattern]))

;; ---------------------------------------------------------------------------
;; Tokenisation
;; ---------------------------------------------------------------------------

(def ^:private word-pattern
  "A word is letters plus optional inner apostrophes for contractions:
  matches `hello`, `don't`, `it's`, `you're`.  Unicode apostrophe \u2019
  is accepted alongside ASCII `'`."
  (Pattern/compile "[A-Za-z]+(?:['\u2019][A-Za-z]+)*"))

(defn- identifier-like?
  "True for tokens that look like code/identifier fragments rather than
  prose words — we skip these to keep spurious underlines off code-ish
  content that survives as plain text in a prose block.

  Ordinary capitalised prose words (`Hello`, `Sidebar`, `Extrnealize`)
  are *not* identifier-like; only mid-word case transitions are.  A
  camelCase token has a lowercase-to-uppercase transition; a
  PascalCase token has *two or more* uppercase letters (the initial
  plus at least one more)."
  [^String w]
  (let [n (.length w)]
    (or (< n 2)
        ;; ALL-CAPS (acronyms like HTTP, GPU, AAO)
        (every? #(Character/isUpperCase ^char %) w)
        ;; camelCase: lowercase followed by uppercase anywhere
        (boolean (re-find #"[a-z][A-Z]" w))
        ;; PascalCase: uppercase initial AND another uppercase later
        (boolean (re-find #"^[A-Z].*[A-Z]" w)))))

(defn tokens-in
  "Return a vector of {:start :length :text} for every plain-prose word
  inside [region-start, region-start+region-length) of `text`.
  Absolute offsets (into `text`, not into the region)."
  [^String text region-start region-length]
  (let [end     (+ region-start region-length)
        clamped (subs text region-start end)
        m       (.matcher ^Pattern word-pattern clamped)]
    (loop [out (transient [])]
      (if (.find m)
        (let [s (.start m)
              e (.end m)
              w (.group m)]
          (if (identifier-like? w)
            (recur out)
            (recur (conj! out {:start  (+ region-start s)
                               :length (- e s)
                               :text   w}))))
        (persistent! out)))))

(tests
 "tokens-in — basic prose"
 (tokens-in "hello world" 0 11)
 := [{:start 0 :length 5 :text "hello"}
     {:start 6 :length 5 :text "world"}]

 "tokens-in — contractions are single tokens"
 (tokens-in "don't stop" 0 10)
 := [{:start 0 :length 5 :text "don't"}
     {:start 6 :length 4 :text "stop"}]

 "tokens-in — all-caps dropped"
 (mapv :text (tokens-in "HTTP is good" 0 12))
 := ["is" "good"]

 "tokens-in — camelCase dropped"
 (mapv :text (tokens-in "call getCwd now" 0 15))
 := ["call" "now"]

 "tokens-in — single-letter dropped"
 (mapv :text (tokens-in "a big I said" 0 12))
 := ["big" "said"]

 "tokens-in — offsets are absolute into `text`"
 (tokens-in "prefix hello" 7 5)
 := [{:start 7 :length 5 :text "hello"}]
 :rcf)

;; ---------------------------------------------------------------------------
;; Prose-span filter — decides which theme spans are eligible for checking
;; ---------------------------------------------------------------------------

(def ^:private prose-types
  "Span :type keywords whose text is user prose and should be checked.
  Everything else (code, URLs, wiki targets, list markers) is skipped."
  #{:body
    :blockquote
    :inline/bold :inline/italic :inline/bold-italic
    :heading/h1 :heading/h2 :heading/h3
    :heading/h4 :heading/h5 :heading/h6
    :bullet-item :numbered-item
    :checkbox-checked :checkbox-unchecked})

(defn prose-span? [span] (contains? prose-types (:type span)))

(defn text-tokens
  "All tokens from prose-bearing spans in `spans`, preserving document order."
  [^String text spans]
  (into []
        (mapcat (fn [{:keys [start length]}]
                  (tokens-in text start length)))
        (filter prose-span? spans)))

;; ---------------------------------------------------------------------------
;; Main dictionary
;; ---------------------------------------------------------------------------

(def ^:private main-dict-resource "dictionaries/en_US.txt")

(defn- load-wordset
  "Read a wordlist resource from the classpath into a lowercase set.
  Returns an empty set if the resource is missing."
  [resource-path]
  (if-let [url (io/resource resource-path)]
    (with-open [r (io/reader url)]
      (into #{}
            (comp (map str/trim)
                  (remove str/blank?)
                  (map str/lower-case))
            (doall (line-seq r))))
    #{}))

(defonce ^:private main-dict-delay
  (delay (load-wordset main-dict-resource)))

;; ---------------------------------------------------------------------------
;; User dictionary — plain one-word-per-line UTF-8 at ~/.winze/spellcheck/
;; ---------------------------------------------------------------------------

(def ^:private user-dict-file
  (io/file (System/getProperty "user.home")
           ".winze" "spellcheck" "user-dictionary.txt"))

(defn- load-user-dict
  "Read the plain-text user dictionary into a lowercase-folded set.
  One word per line; blank lines and surrounding whitespace are
  ignored.  Missing file → empty set."
  []
  (try
    (when (.exists user-dict-file)
      (with-open [r (io/reader user-dict-file)]
        (into #{}
              (comp (map str/trim)
                    (remove str/blank?)
                    (map str/lower-case))
              (doall (line-seq r)))))
    (catch Throwable _ #{})))

(defonce user-dict (atom nil))

(defn- user-dict* []
  (or @user-dict
      (reset! user-dict (or (load-user-dict) #{}))))

(defonce session-ignores (atom #{}))

;; ---------------------------------------------------------------------------
;; known? — honours main dict, user dict, session ignores, and contractions
;; ---------------------------------------------------------------------------

(defn- known-simple?
  "Raw dictionary check against a *pre-lowercased* word."
  [w]
  (or (contains? @main-dict-delay w)
      (contains? (user-dict*) w)
      (contains? @session-ignores w)))

(defn known?
  "True if `word` is accepted by any dictionary layer.
  Contractions (`don't`, `we're`) are accepted if every segment
  separated by an apostrophe is itself known or too short to be
  checked (≤ 1 char — `I'd`, `it's`)."
  [word]
  (let [w (str/lower-case word)]
    (or (known-simple? w)
        (and (boolean (re-find #"['\u2019]" w))
             (every? #(or (< (.length ^String %) 2)
                          (known-simple? %))
                     (str/split w #"['\u2019]"))))))

(defn misspellings-for
  "Return a vector of {:start :length :text} for every prose token that
  is not accepted by any dictionary.  `spans` is the output of
  `md-theme/theme` for the same `text`."
  [^String text spans]
  (into [] (remove #(known? (:text %))) (text-tokens text spans)))

(tests
 "known? + misspellings-for — fake dict, fake spans"
 (with-redefs [main-dict-delay (delay #{"hello" "world" "the"
                                        "do" "we" "can" "are"})]
   (reset! user-dict #{})
   (reset! session-ignores #{})

   "known? — hit, miss, case-insensitive"
   (known? "hello") := true
   (known? "Hello") := true
   (known? "xyzzy") := false

   "known? — contraction with missing 2-char segment still misses"
   ;; "we're" splits to ["we" "re"]; "we" is known but "re" (2 chars,
   ;; not in this minimal dict) is not → overall miss.
   (known? "we're") := false)
 (with-redefs [main-dict-delay (delay #{"we" "re" "do" "not"})]
   (reset! user-dict #{})
   (reset! session-ignores #{})
   (known? "we're") := true
   (known? "do")    := true)

 "misspellings-for — skips code-block tokens, flags body tokens"
 (with-redefs [main-dict-delay (delay #{"hello" "world"})]
   (reset! user-dict #{})
   (reset! session-ignores #{})
   (let [text  "hello xyzzy world"
         spans [{:start 0 :length 17 :type :body}]]
     (mapv :text (misspellings-for text spans))
     := ["xyzzy"])
   (let [text  "hello xyzzy world"
         spans [{:start 0 :length 17 :type :code-block}]]
     (misspellings-for text spans) := []))
 :rcf)

;; ---------------------------------------------------------------------------
;; merge-misspellings — fold underline style onto theme spans
;; ---------------------------------------------------------------------------

(defn- underline-fields
  "Extra span fields that cause `span->style-range` to draw a squiggly."
  []
  {:underline       true
   :underline-style SWT/UNDERLINE_SQUIGGLE
   :underline-color res/color-spellcheck-error})

(defn- split-span-at
  "Split a single theme `span` so that the sub-range [ms, me) (absolute
  offsets, guaranteed to intersect the span) is emitted as a separate
  underlined piece.  Returns a vector of 1–3 non-overlapping spans
  covering the original span's extent."
  [span ms me]
  (let [s  (:start span)
        l  (:length span)
        e  (+ s l)
        ov-start (max s ms)
        ov-end   (min e me)
        under    (underline-fields)]
    (cond-> []
      (< s ov-start)  (conj (assoc span :start s :length (- ov-start s)))
      true            (conj (merge span {:start ov-start
                                         :length (- ov-end ov-start)}
                                   under))
      (< ov-end e)    (conj (assoc span :start ov-end :length (- e ov-end))))))

(defn merge-misspellings
  "Return a new vector of theme spans where every misspelling region is
  split out and carries `:underline true`, `:underline-style
  SWT/UNDERLINE_SQUIGGLE`, `:underline-color …` on top of whatever
  `:type` the underlying span already has.

  Input `spans` must be non-overlapping and sorted by `:start`; this
  invariant is preserved on output.  `misspellings` must likewise be
  sorted by `:start` and non-overlapping."
  [spans misspellings]
  (if (empty? misspellings)
    (vec spans)
    (loop [spans (seq spans)
           miss  (seq misspellings)
           out   (transient [])]
      (cond
        (empty? spans) (persistent! out)

        (empty? miss)
        (persistent! (reduce conj! out spans))

        :else
        (let [span  (first spans)
              s     (:start span)
              e     (+ s (:length span))
              m     (first miss)
              ms    (:start m)
              me    (+ ms (:length m))]
          (cond
            ;; Misspelling entirely before this span — drop it (tokens
            ;; should never land outside the spans that produced them,
            ;; but be defensive).
            (<= me s) (recur spans (next miss) out)

            ;; Misspelling entirely after this span — emit span, advance.
            (<= e ms) (recur (next spans) miss (conj! out span))

            ;; Overlap — split the span, keep pre/over in `out`, push
            ;; `post` (if any) back onto the span queue so subsequent
            ;; misspellings in the same original span are handled.
            :else
            (let [pieces (split-span-at span ms me)
                  post   (when (< me e) (last pieces))
                  kept   (if post (butlast pieces) pieces)]
              (recur (if post (cons post (next spans)) (next spans))
                     (next miss)
                     (reduce conj! out kept)))))))))

(tests
 "merge-misspellings — empty misspellings is identity"
 (merge-misspellings [{:start 0 :length 5 :type :body}] [])
 := [{:start 0 :length 5 :type :body}]

 "merge-misspellings — misspelling exactly covering span"
 (let [out (merge-misspellings
            [{:start 0 :length 5 :type :body}]
            [{:start 0 :length 5 :text "xyzzy"}])]
   (count out) := 1
   (:start (first out))  := 0
   (:length (first out)) := 5
   (:underline (first out)) := true)

 "merge-misspellings — misspelling inside span splits 3 ways"
 (let [out (merge-misspellings
            [{:start 0 :length 10 :type :body}]
            [{:start 3 :length 4 :text "xxxx"}])]
   (count out) := 3
   (mapv (juxt :start :length) out) := [[0 3] [3 4] [7 3]]
   (:underline (nth out 1)) := true
   (nil? (:underline (first out))) := true
   (nil? (:underline (last  out))) := true)

 "merge-misspellings — two misspellings in same span stay sorted"
 (let [out (merge-misspellings
            [{:start 0 :length 20 :type :body}]
            [{:start 2 :length 3 :text "aaa"}
             {:start 10 :length 4 :text "bbbb"}])]
   (mapv (juxt :start :length :underline) out)
   := [[0 2 nil] [2 3 true] [5 5 nil] [10 4 true] [14 6 nil]])

 "merge-misspellings — misspelling inside a :heading span retains :type"
 (let [out (merge-misspellings
            [{:start 0 :length 10 :type :heading/h1}]
            [{:start 2 :length 3 :text "xxx"}])]
   (mapv :type out) := [:heading/h1 :heading/h1 :heading/h1]
   (mapv (comp boolean :underline) out) := [false true false])
 :rcf)

;; ---------------------------------------------------------------------------
;; Suggestions — linear scan with edit-distance ceiling
;; ---------------------------------------------------------------------------
;;
;; We dropped a SymSpell deletes-index: the index for ~234 000 words
;; measured at ~11 M entries and ~2 GB heap for sub-millisecond
;; lookups.  A linear scan of the whole dict with an edit-distance
;; ceiling finishes every suggestion query in ~30 ms (single query,
;; one-shot — right-click is already an interactive pause), uses zero
;; extra heap, and is dramatically simpler.

(defn- edit-distance
  "Optimal string alignment (Damerau–Levenshtein with adjacent-swap
  counted as 1) between `a` and `b`.  Caps at `ceiling`: returns
  `(inc ceiling)` as soon as the running minimum can exceed it."
  ^long [^String a ^String b ^long ceiling]
  (let [la (.length a)
        lb (.length b)]
    (cond
      (zero? la) lb
      (zero? lb) la
      (> (Math/abs (- la lb)) ceiling) (inc ceiling)
      :else
      (let [prev2 (int-array (inc lb))
            prev  (int-array (inc lb))
            curr  (int-array (inc lb))]
        (dotimes [j (inc lb)] (aset prev j j))
        (loop [i 1]
          (if (> i la)
            (aget prev lb)
            (do
              (aset curr 0 i)
              (let [ca (.charAt a (dec i))]
                (loop [j 1 row-min i]
                  (if (> j lb)
                    (if (> row-min ceiling)
                      (inc ceiling)
                      nil)
                    (let [cb   (.charAt b (dec j))
                          cost (if (= ca cb) 0 1)
                          del  (inc (aget prev j))
                          ins  (inc (aget curr (dec j)))
                          sub  (+ (aget prev (dec j)) cost)
                          d0   (min del ins sub)
                          d1   (if (and (> i 1) (> j 1)
                                        (= ca (.charAt b (- j 2)))
                                        (= (.charAt a (- i 2)) cb))
                                 (min d0 (+ (aget prev2 (- j 2)) cost))
                                 d0)]
                      (aset curr j d1)
                      (recur (inc j) (min row-min d1))))))
              (System/arraycopy prev  0 prev2 0 (inc lb))
              (System/arraycopy curr  0 prev  0 (inc lb))
              (recur (inc i)))))))))

(defn suggestions
  "Up to 10 dictionary words within edit distance ≤ 2 of `word`,
  sorted alphabetically.  Scans the main dictionary with the
  ceiling-aware `edit-distance` (most words reject in O(1) thanks to
  the length-delta guard); when more than 10 hit, trims to the 10
  closest by distance before the final alphabetical sort."
  [^String word]
  (let [w       (str/lower-case word)
        scored  (into []
                      (keep (fn [^String c]
                              (let [d (edit-distance w c 2)]
                                (when (<= d 2) [c d]))))
                      @main-dict-delay)
        trimmed (->> scored
                     (sort (fn [[a da] [b db]]
                             (if (= da db) (compare a b) (compare da db))))
                     (take 10)
                     (map first))]
    (sort trimmed)))

(tests
 "edit-distance — known corrections"
 (edit-distance "teh"     "the"     2) := 1
 (edit-distance "recieve" "receive" 2) := 1
 (edit-distance "ab"      "xy"      2) := 2
 (edit-distance "ab"      "xyz"     2) := 3
 :rcf)

;; ---------------------------------------------------------------------------
;; User-dictionary mutation & session ignores
;; ---------------------------------------------------------------------------

(defn add-to-user-dict!
  "Add `word` (lowercase-folded) to the user dictionary and persist
  the whole dictionary to disk as a sorted, one-word-per-line UTF-8
  text file.  Atomic: writes to `<path>.tmp` then renames."
  [word]
  (let [w    (str/lower-case word)
        next (conj (user-dict*) w)]
    (.mkdirs (.getParentFile user-dict-file))
    (let [tmp (io/file (str (.getAbsolutePath user-dict-file) ".tmp"))]
      (spit tmp (str (str/join "\n" (sort next)) "\n"))
      (.renameTo tmp user-dict-file))
    (reset! user-dict next)
    word))

(defn ignore-this-session!
  "Add `word` (lowercase-folded) to the in-memory session ignores.
  Not persisted — cleared on JVM restart."
  [word]
  (swap! session-ignores conj (str/lower-case word))
  word)

;; ---------------------------------------------------------------------------
;; Refresh broadcast — dictionary changes re-theme every open editor
;; ---------------------------------------------------------------------------

(defonce refresh-listeners (atom #{}))

(defn register-refresh-listener!
  "Register a 0-arg callback to be invoked whenever the user dictionary
  or session ignores change.  Returns a token (the function) that can
  be passed to `unregister-refresh-listener!`."
  [f]
  (swap! refresh-listeners conj f)
  f)

(defn unregister-refresh-listener! [f]
  (swap! refresh-listeners disj f))

(defn- broadcast-refresh! []
  (doseq [f @refresh-listeners]
    (try (f)
         (catch Throwable _))))

(add-watch user-dict ::refresh-on-add
           (fn [_ _ old new] (when (not= old new) (broadcast-refresh!))))

(add-watch session-ignores ::refresh-on-ignore
           (fn [_ _ old new] (when (not= old new) (broadcast-refresh!))))
