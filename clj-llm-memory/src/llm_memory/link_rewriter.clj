(ns llm-memory.link-rewriter
  "AST-based rewriter for `wiki:` markdown links.

  Uses commonmark-java with `IncludeSourceSpans/BLOCKS_AND_INLINES` so every
  `Link` node carries a `SourceSpan` pointing at the raw `[text](dest)` range.
  We never regex over the raw source — that would corrupt links inside
  fenced code blocks and inline code spans.

  Exports:
    verified-wiki-link?    — span-exact-match check (used both here and
                             by index.clj's extractor for symmetry)
    extract-wiki-links     — returns only verified wiki Link records
    rewrite-links-in-text  — in-memory rewrite; returns new text or nil
    rewrite-links-in-file! — on-disk rewrite; atomic tmp-then-move

  Invariants:
    - If a link's span cannot be verified (reference-style link,
      multi-line display text, escaped-paren display), it is **skipped**
      with a DEBUG log. A broken link is cheaper than corrupted content.
    - Replacements are applied descending by offset so earlier rewrites
      do not shift later positions.
    - File writes use FileChannel.force(true) before Files/move ATOMIC_MOVE
      — no non-atomic fallback."
  (:require [clojure.string :as str]
            [hyperfiddle.rcf :refer [tests]])
  (:import
   [java.io File]
   [java.nio.channels FileChannel]
   [java.nio.file Files Path StandardCopyOption StandardOpenOption]
   [org.commonmark.ext.gfm.strikethrough StrikethroughExtension]
   [org.commonmark.ext.gfm.tables TablesExtension]
   [org.commonmark.ext.task.list.items TaskListItemsExtension]
   [org.commonmark.node AbstractVisitor Link]
   [org.commonmark.parser IncludeSourceSpans Parser]))

(defn- warn [& args] (println (apply str "[WARN] link-rewriter: " (interpose " " args))))
(defn- debug [& _]) ;; DEBUG messages are silent in production; flip to println for diagnostics.

;; ---------------------------------------------------------------------------
;; Parser
;; ---------------------------------------------------------------------------

(def ^:private ^Parser rewrite-parser
  "commonmark-java parser with BLOCKS_AND_INLINES spans.
   Same GFM extension set as hiccup.clj so wiki links inside table cells
   are still reachable. Distinct from hiccup.clj's parser (which uses
   BLOCKS only — sufficient for per-block data-line annotations, but
   does not emit per-inline SourceSpans)."
  (.. (Parser/builder)
      (includeSourceSpans IncludeSourceSpans/BLOCKS_AND_INLINES)
      (extensions [(TablesExtension/create)
                   (StrikethroughExtension/create)
                   (TaskListItemsExtension/create)])
      build))

;; ---------------------------------------------------------------------------
;; AST walk — collect Link nodes with wiki: destinations
;; ---------------------------------------------------------------------------

(defn- collect-wiki-link-nodes
  "Walk the AST and return [{:destination :input-index :span-len} ...] for
  every Link node whose destination starts with `wiki:` AND has at least
  one source span. Uses the node's FIRST span only; multi-line display
  text is filtered out downstream via verified-wiki-link?."
  [document]
  (let [out (volatile! [])]
    (.accept document
             (proxy [AbstractVisitor] []
               (visit [^org.commonmark.node.Node node]
                 (when (instance? Link node)
                   (let [link ^Link node
                         dest (.getDestination link)
                         spans (.getSourceSpans link)]
                     (when (and (string? dest)
                                (str/starts-with? dest "wiki:")
                                (seq spans))
                       (let [span (first spans)]
                         (vswap! out conj
                                 {:destination dest
                                  :input-index (.getInputIndex span)
                                  :span-len    (.getLength span)})))))
                 (proxy-super visitChildren node))))
    @out))

(defn- verified-wiki-link?
  "True if the raw source text at `record`'s span contains `](<destination>`
  as a bounded substring whose destination segment exactly equals the
  parser-returned destination.

  Rejects:
    - reference-style links (span covers `[text][ref]`, not the definition)
    - multi-line Link nodes (first span ends before `](`)
    - escaped-paren display text (first `](` in span is inside the text)
    - any synthetic node without a span

  Public-ish — used by both rewriter and indexer extractor for symmetry."
  [text {:keys [destination input-index span-len]}]
  (and input-index span-len
       (let [span-start input-index
             span-end   (+ span-start span-len)
             text-len   (count text)]
         (and (<= 0 span-start)
              (<= span-end text-len)
              (let [span (subs text span-start span-end)
                    sep  (str/index-of span "](")]
                (when sep
                  (let [dest-start (+ span-start sep 2)
                        dest-end   (+ dest-start (count destination))]
                    (and (<= dest-end text-len)
                         (= destination (subs text dest-start dest-end))))))))))

(defn extract-wiki-links
  "Parse markdown text, return all verified inline Link nodes with wiki:
  destinations.

  Reference-style links, synthetic links, and any node whose span cannot
  be exact-match-verified are filtered out (logged at DEBUG). Returns
  `[{:destination str :input-index int :span-len int} ...]`."
  [text]
  (if (str/blank? text)
    []
    (let [doc      (.parse rewrite-parser text)
          raw      (collect-wiki-link-nodes doc)
          verified (filterv #(verified-wiki-link? text %) raw)]
      (when (< (count verified) (count raw))
        (debug "dropped"
               (- (count raw) (count verified))
               "unverified wiki-link record(s)"))
      verified)))

;; ---------------------------------------------------------------------------
;; rewrite-destination — exact-length substring replacement
;; ---------------------------------------------------------------------------

(defn- rewrite-destination
  "Return `text` with the destination at `link-record` replaced by `new-dest`,
  or nil if the destination cannot be located unambiguously within the span.

  Uses the parser's returned destination as ground truth — no `)`-scan,
  no title handling, no escape handling. If the text at the computed
  dest-offset does not exactly equal the parser's destination, returns
  nil (safe-skip)."
  [text {:keys [destination input-index span-len] :as link-record} new-dest]
  (when (verified-wiki-link? text link-record)
    (let [span-start input-index
          span-end   (+ span-start span-len)
          span       (subs text span-start span-end)
          sep        (str/index-of span "](")
          dest-start (+ span-start sep 2)
          dest-end   (+ dest-start (count destination))]
      (str (subs text 0 dest-start)
           new-dest
           (subs text dest-end)))))

;; ---------------------------------------------------------------------------
;; rewrite-links-in-text — public in-memory rewrite
;; ---------------------------------------------------------------------------

(defn- boundary-ok?
  "True if the character immediately following `prefix` inside `destination`
  is `#` (heading anchor) or end-of-destination. Prevents
  `wiki:root::old.md` from prefix-matching `wiki:root::old.md.backup`.

  The wider set `#`, `)`, `\"`, `'` is unreachable against
  `.getDestination` (which strips markdown syntax) — but accepting the
  wider set is cheap belt-and-suspenders."
  [destination prefix]
  (let [pc (count prefix)
        dc (count destination)]
    (or (= pc dc)
        (contains? #{\# \) \" \'} (.charAt destination pc)))))

(defn rewrite-links-in-text
  "Replace wiki links in `text` whose destination starts with `old-dest`
  (or exactly equals `old-dest` when :exact? true) with `new-dest`.

  Default (prefix mode) preserves any `#slug` suffix after `old-dest` —
  a file-level rename of `wiki:root::old.md` also fixes
  `wiki:root::old.md#foo` links. Exact mode is required for heading
  renames: `wiki:root::X.md#step-1` must NOT match
  `wiki:root::X.md#step-10`.

  Replacements are applied in descending offset order so earlier
  rewrites never shift later positions. Returns the modified string,
  or nil if no verified link matched."
  [text old-dest new-dest & {:keys [exact?] :or {exact? false}}]
  (let [links (extract-wiki-links text)
        matching
        (filter (fn [{:keys [destination]}]
                  (if exact?
                    (= destination old-dest)
                    (and (str/starts-with? destination old-dest)
                         (boundary-ok? destination old-dest))))
                links)
        sorted (sort-by :input-index > matching)]
    (when (seq sorted)
      (reduce (fn [acc {:keys [destination] :as rec}]
                (let [suffix    (if exact? "" (subs destination (count old-dest)))
                      full-new  (str new-dest suffix)
                      rewritten (rewrite-destination acc rec full-new)]
                  (if rewritten
                    rewritten
                    (do (warn "skipped unverifiable rewrite at offset"
                              (:input-index rec) "destination" destination)
                        acc))))
              text
              sorted))))

;; ---------------------------------------------------------------------------
;; rewrite-links-in-file! — atomic on-disk rewrite
;; ---------------------------------------------------------------------------

(defn rewrite-links-in-file!
  "Rewrite wiki links in `abs-path`. Returns :modified, :no-change, or :error.

  Atomic write procedure (belt-and-suspenders):
    1. Write bytes to <abs-path>.tmp via FileChannel (CREATE+WRITE+TRUNCATE).
    2. FileChannel.force(true) — fsync contents AND metadata.
    3. Close the channel.
    4. Files/move tmp → target with ATOMIC_MOVE.

  No non-atomic fallback — an AtomicMoveNotSupportedException is logged
  and returned as :error. Corrupt-content is strictly worse than a
  broken link on a niche filesystem."
  [^String abs-path old-dest new-dest & {:keys [exact?] :or {exact? false}}]
  (let [original  (slurp abs-path)
        rewritten (rewrite-links-in-text original old-dest new-dest :exact? exact?)]
    (if (nil? rewritten)
      :no-change
      (try
        (let [target   ^File (File. abs-path)
              tmp      ^File (File. (str abs-path ".tmp"))
              tmp-path ^Path (.toPath tmp)
              tgt-path ^Path (.toPath target)
              bytes    (.getBytes ^String rewritten "UTF-8")]
          (with-open [ch (FileChannel/open tmp-path
                                           (into-array StandardOpenOption
                                                       [StandardOpenOption/CREATE
                                                        StandardOpenOption/WRITE
                                                        StandardOpenOption/TRUNCATE_EXISTING]))]
            (.write ch (java.nio.ByteBuffer/wrap bytes))
            (.force ch true))
          (Files/move tmp-path tgt-path
                      (into-array java.nio.file.CopyOption
                                  [StandardCopyOption/ATOMIC_MOVE
                                   StandardCopyOption/REPLACE_EXISTING]))
          :modified)
        (catch Exception e
          (warn "atomic write failed for" abs-path (.getMessage e))
          :error)))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(tests
 "extract-wiki-links — basic file link"
 (let [recs (extract-wiki-links "See [the plan](wiki:root::PLAN.md) for details.")]
   (count recs) := 1
   (:destination (first recs)) := "wiki:root::PLAN.md")
 :rcf)

(tests
 "extract-wiki-links — anchor link"
 (let [recs (extract-wiki-links "See [design](wiki:root::PLAN.md#design).")]
   (count recs) := 1
   (:destination (first recs)) := "wiki:root::PLAN.md#design")
 :rcf)

(tests
 "extract-wiki-links — fenced code block links are ignored"
 (let [text "See [a](wiki:root::A.md).\n\n```\n[b](wiki:root::B.md)\n```\n"
       recs (extract-wiki-links text)]
   (count recs) := 1
   (:destination (first recs)) := "wiki:root::A.md")
 :rcf)

(tests
 "extract-wiki-links — reference-style link is filtered (span doesn't verify)"
 (let [text "See [the plan][ref] for details.\n\n[ref]: wiki:root::PLAN.md"
       recs (extract-wiki-links text)]
   ;; Reference-style links are emitted as Link nodes but their span covers
   ;; `[the plan][ref]`, not the definition line. Verification fails → dropped.
   (count recs) := 0)
 :rcf)

(tests
 "extract-wiki-links — mixed inline + reference-style returns only inline"
 (let [text (str "Inline: [a](wiki:root::A.md)\n\n"
                 "Ref: [b][bref]\n\n"
                 "[bref]: wiki:root::B.md")
       recs (extract-wiki-links text)
       dests (set (map :destination recs))]
   (count recs) := 1
   (contains? dests "wiki:root::A.md") := true
   (contains? dests "wiki:root::B.md") := false)
 :rcf)

(tests
 "rewrite-links-in-text — file-link rewrite"
 (let [t  "Go to [plan](wiki:root::OLD.md) now."
       r  (rewrite-links-in-text t "wiki:root::OLD.md" "wiki:root::NEW.md")]
   r := "Go to [plan](wiki:root::NEW.md) now.")
 :rcf)

(tests
 "rewrite-links-in-text — prefix rewrite preserves #slug suffix"
 (let [t "See [design](wiki:root::OLD.md#design) and [raw](wiki:root::OLD.md)."
       r (rewrite-links-in-text t "wiki:root::OLD.md" "wiki:root::NEW.md")]
   r := "See [design](wiki:root::NEW.md#design) and [raw](wiki:root::NEW.md).")
 :rcf)

(tests
 "rewrite-links-in-text — :exact? true does NOT match #step-10 when rewriting #step-1"
 (let [t "First: [one](wiki:r::F.md#step-1). Tenth: [ten](wiki:r::F.md#step-10)."
       r (rewrite-links-in-text t
                                "wiki:r::F.md#step-1"
                                "wiki:r::F.md#first"
                                :exact? true)]
   r := "First: [one](wiki:r::F.md#first). Tenth: [ten](wiki:r::F.md#step-10).")
 :rcf)

(tests
 "rewrite-links-in-text — prefix boundary: wiki:root::old.md does NOT rewrite wiki:root::old.md.backup"
 (let [t (str "Real: [a](wiki:r::old.md)\n"
              "Co-located: [b](wiki:r::old.md.backup)")
       r (rewrite-links-in-text t "wiki:r::old.md" "wiki:r::new.md")]
   r := (str "Real: [a](wiki:r::new.md)\n"
             "Co-located: [b](wiki:r::old.md.backup)"))
 :rcf)

(tests
 "rewrite-links-in-text — no match returns nil"
 (let [t "Unrelated: [x](wiki:r::other.md)"
       r (rewrite-links-in-text t "wiki:r::nope.md" "wiki:r::new.md")]
   r := nil)
 :rcf)

(tests
 "rewrite-links-in-text — code-block links are not rewritten"
 (let [t "Real: [a](wiki:r::old.md)\n\n```\n[b](wiki:r::old.md)\n```\n"
       r (rewrite-links-in-text t "wiki:r::old.md" "wiki:r::new.md")]
   ;; Only the non-code-block occurrence is rewritten
   (.contains ^String r "Real: [a](wiki:r::new.md)") := true
   (.contains ^String r "[b](wiki:r::old.md)") := true)
 :rcf)

(tests
 "rewrite-links-in-file! — writes change atomically, idempotent on second call"
 (let [f   (java.io.File/createTempFile "link-rewrite-" ".md")
       _   (.deleteOnExit f)
       _   (spit f "Go to [plan](wiki:r::OLD.md) now.")
       abs (.getAbsolutePath f)]
   (rewrite-links-in-file! abs "wiki:r::OLD.md" "wiki:r::NEW.md") := :modified
   (slurp abs) := "Go to [plan](wiki:r::NEW.md) now."
   ;; Second call — no matching dest, returns :no-change
   (rewrite-links-in-file! abs "wiki:r::OLD.md" "wiki:r::NEW.md") := :no-change
   (.delete f))
 :rcf)
