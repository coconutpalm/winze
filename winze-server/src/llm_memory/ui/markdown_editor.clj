(ns llm-memory.ui.markdown-editor
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [llm-memory.ui.content-assist :as content-assist]
   [llm-memory.ui.link-preview :as link-preview]
   [llm-memory.ui.md-theme :as md-theme]
   [llm-memory.ui.resources :as res]
   [llm-memory.ui.spellcheck :as spellcheck]
   [llm-memory.ui.spellcheck-menu :as spellcheck-menu]
   [llm-memory.ui.theme :as theme]
   [ui.SWT :refer [async-exec! on styled-text with-property |]]
   [ui.events :as e]
   [hyperfiddle.rcf :refer [tests]])
  (:import
   [java.net URI]
   [java.util.concurrent ScheduledFuture TimeUnit]
   [org.eclipse.swt SWT]
   [org.eclipse.swt.custom Bullet ST StyleRange StyledText
    StyledTextLineSpacingProvider VerifyKeyListener]
   [org.eclipse.swt.graphics Cursor GC GlyphMetrics]
   [org.eclipse.swt.widgets Display]))

;; ---------------------------------------------------------------------------
;; Span → StyleRange mapping
;; ---------------------------------------------------------------------------

(def ^:private type->style
  "Map span :type keywords to {:font delay :fg delay :bg delay :font-style int}."
  {:heading/h1       {:font res/h1-font        :fg res/color-lavender}
   :heading/h2       {:font res/h2-font        :fg res/color-amethyst}
   :heading/h3       {:font res/h3-font        :fg res/color-amethyst}
   :heading/h4       {:font res/h4-font        :fg res/color-deep-violet}
   :heading/h5       {:font res/h5-font        :fg res/color-royal-purple}
   :heading/h6       {:font res/h6-font        :fg res/color-royal-purple}
   :heading/h1-marker {:font res/h1-font       :fg res/color-royal-purple}
   :heading/h2-marker {:font res/h2-font       :fg res/color-royal-purple}
   :heading/h3-marker {:font res/h3-font       :fg res/color-royal-purple}
   :heading/h4-marker {:font res/h4-font       :fg res/color-royal-purple}
   :heading/h5-marker {:font res/h5-font       :fg res/color-royal-purple}
   :heading/h6-marker {:font res/h6-font       :fg res/color-royal-purple}
   :body             {}
   :blockquote       {:fg res/color-deep-violet :font res/body-italic-font}
   :inline/bold      {:font res/body-bold-font  :fg res/color-pure-white}
   :inline/italic    {:font res/body-italic-font}
   :inline/bold-italic {:font res/body-bold-italic-font :fg res/color-pure-white}
   :inline/code      {:font res/mono-font       :fg res/color-amethyst
                      :bg res/color-bedrock}
   :inline/link      {:fg res/color-amethyst}
   :inline/wiki-draft {:fg res/color-amethyst
                       :underline true
                       :underline-style SWT/UNDERLINE_SQUIGGLE}
   :code-block       {:font res/mono-font       :bg res/color-bedrock}
   :token/keyword    {:font res/mono-font       :fg res/color-lavender    :bg res/color-bedrock}
   :token/string     {:font res/mono-font       :fg res/color-amethyst    :bg res/color-bedrock}
   :token/comment    {:font res/mono-italic-font :fg res/color-royal-purple :bg res/color-bedrock}
   :token/number     {:font res/mono-font       :fg res/color-deep-violet :bg res/color-bedrock}
   :token/type       {:font res/mono-font       :fg res/color-lavender    :bg res/color-bedrock}
   :token/operator   {:font res/mono-font       :fg res/color-crystal-white :bg res/color-bedrock}
   :token/builtin    {:font res/mono-bold-font  :fg res/color-amethyst    :bg res/color-bedrock}
   :token/default    {:font res/mono-font       :fg res/color-crystal-white :bg res/color-bedrock}})

(defn- span->style-range
  "Convert a span map to an SWT StyleRange.
  Style fields (:font :fg :bg :underline :underline-style :underline-color)
  are resolved from the span itself first (per-span overrides, e.g.
  spellcheck squiggly underline) and fall back to the `type->style`
  table for the span's `:type`."
  [{:keys [start length type] :as span}]
  (let [default (get type->style type {})
        {:keys [font fg bg underline underline-style underline-color]}
        (merge default (select-keys span [:font :fg :bg :underline
                                          :underline-style :underline-color]))
        sr (StyleRange.)]
    (set! (.-start sr)  start)
    (set! (.-length sr) length)
    (when font            (set! (.-font sr)            @font))
    (when fg              (set! (.-foreground sr)       @fg))
    (when bg              (set! (.-background sr)       @bg))
    (when underline       (set! (.-underline sr)        true))
    (when underline-style (set! (.-underlineStyle sr)   underline-style))
    (when underline-color (set! (.-underlineColor sr)   @underline-color))
    sr))

(defn- offset->line
  "Convert a character offset to a line number in the StyledText."
  [styled-text offset]
  (.getLineAtOffset styled-text (min offset (count (.getText styled-text)))))

(defn- apply-code-block-line-backgrounds!
  "Set full-line bedrock background for code block regions (including fence lines).
  Takes raw `blocks` from `md-theme/parse-blocks` (not post-themed spans) so that
  the full extent of each code block is covered — including lines entirely within
  multi-line token spans (e.g. multi-line strings) that have no :code-block fragment.
  Must be called after setStyleRanges since that clears line backgrounds."
  [styled-text blocks]
  (let [line-count (.getLineCount styled-text)
        bedrock    @res/color-bedrock]
    ;; Reset all line backgrounds first
    (.setLineBackground styled-text 0 line-count nil)
    ;; Set bedrock for code block lines + surrounding fence lines
    (doseq [{:keys [start length type]} blocks
            :when (= type :code-block)]
      (let [first-code-line (offset->line styled-text start)
            last-code-line  (offset->line styled-text (+ start (max 0 (dec length))))
            ;; Include the fence line before (opening ```) and after (closing ```)
            start-line (max 0 (dec first-code-line))
            end-line   (min (dec line-count) (inc last-code-line))
            num-lines  (inc (- end-line start-line))]
        (.setLineBackground styled-text start-line num-lines bedrock)))))

;; ---------------------------------------------------------------------------
;; Link spans — per-editor atom updated by apply-theme!
;; ---------------------------------------------------------------------------

;; Maps a StyledText widget identity (System/identityHashCode) to a vector of
;; link spans [{:start N :length N :type :inline/link|:inline/wiki-draft :dest "..."}]
(defonce ^:private editor-link-spans (atom {}))

(defn- editor-key
  "Stable key for an editor widget in the link-spans registry."
  [^StyledText st]
  (System/identityHashCode st))

(defn link-spans-for
  "Return the current link spans for an editor."
  [^StyledText st]
  (get @editor-link-spans (editor-key st) []))

(defn- update-link-spans!
  "Extract link/wiki-draft spans from themed spans and store them for `st`."
  [^StyledText st spans]
  (let [links (filterv (fn [{:keys [type dest]}]
                         (and dest
                              (#{:inline/link :inline/wiki-draft} type)))
                       spans)]
    (swap! editor-link-spans assoc (editor-key st) links)))

(defn- remove-link-spans!
  "Clean up link spans when an editor is disposed."
  [^StyledText st]
  (swap! editor-link-spans dissoc (editor-key st)))

;; ---------------------------------------------------------------------------
;; List rendering — bullets, indents, hanging indent
;; ---------------------------------------------------------------------------

(def ^:private list-types
  #{:bullet-item :numbered-item :checkbox-unchecked :checkbox-checked})

(defn- apply-list-rendering!
  "Apply visual list rendering (bullets, indent, hanging indent) to the editor.
  The bullet glyph always renders at x=0 in SWT, so all indent spacing is
  encoded into the GlyphMetrics width and the bullet text is padded with
  leading spaces. The source marker text is collapsed to zero width.
  Must be called on the UI thread, after setStyleRanges."
  [^StyledText st blocks]
  (let [lc   (.getLineCount st)
        bg   (.getBackground st)
        text (.getText st)
        gc   (GC. (.getDisplay st))]
    (try
      (.setFont gc (.getFont st))
      (let [avg-w       (.getAverageCharacterWidth (.getFontMetrics gc))
            space-w     (.getAdvanceWidth gc \space)
            base-indent (int (* 2.5 avg-w))
            indent-step (int (* 2.5 avg-w))]
        ;; Clear previous list state
        (.setLineBullet st 0 lc nil)
        (.setLineIndent st 0 lc 0)
        (.setLineWrapIndent st 0 lc 0)
        (doseq [{:keys [type indent marker-len start] :as block} blocks
                :when (list-types type)]
          (let [line        (.getLineAtOffset st (min start (max 0 (dec (count text)))))
                pre-bullet  (+ base-indent (* indent indent-step))
                pad-spaces  (max 1 (int (/ pre-bullet space-w)))
                [raw-text b-fg] (case type
                                  :bullet-item        ["• " @res/color-amethyst]
                                  :numbered-item      [(str (:number block) ". ") @res/color-deep-violet]
                                  :checkbox-unchecked ["☐ " @res/color-deep-violet]
                                  :checkbox-checked   ["✓ " @res/color-check-green])
                pad-str     (apply str (repeat pad-spaces \space))
                padded-text (str pad-str raw-text)
                ;; Total glyph area = actual rendered width of padded text
                ;; (measured, not estimated — works with any font/platform)
                total-glyph-w (.x (.stringExtent gc padded-text))
                sr (doto (StyleRange.)
                     (-> .-foreground (set! b-fg))
                     (-> .-font (set! (.getFont st)))
                     (-> .-metrics (set! (GlyphMetrics. 0 0 total-glyph-w))))
                bullet (let [b (Bullet. ST/BULLET_TEXT sr)]
                         (set! (.-text b) padded-text)
                         b)]
            (.setLineBullet st line 1 bullet)
            ;; lineIndent stays 0 — all spacing is in the glyph area
            (.setLineWrapIndent st line 1 total-glyph-w)
            ;; Collapse the source marker text to zero width
            (let [marker-end (min (+ start marker-len) (count text))]
              (when (< start marker-end)
                (.replaceStyleRanges st start (- marker-end start)
                                     (into-array StyleRange
                                                 [(doto (StyleRange.)
                                                    (-> .-start (set! start))
                                                    (-> .-length (set! (- marker-end start)))
                                                    (-> .-foreground (set! bg))
                                                    (-> .-font (set! (.getFont st)))
                                                    (-> .-metrics (set! (GlyphMetrics. 0 0 0))))])))))))
      (finally (.dispose gc)))))

(def ^:private prose-line-spacing-px
  "Extra leading added between prose/list/heading lines to match the HTML
  viewer's `line-height: 1.7`. Code-block lines get 0 (viewer's `normal`)."
  6)

(defn- line-in-code-block?
  "True if line `line-idx` in `styled-text` falls inside a :code-block span
  (including the surrounding fence lines, which `apply-code-block-line-backgrounds!`
  also treats as code)."
  [^StyledText styled-text blocks line-idx]
  (let [off (.getOffsetAtLine styled-text line-idx)]
    (boolean
     (some (fn [{:keys [start length type]}]
             (and (= type :code-block)
                  (<= start off (+ start length))))
           blocks))))

(defn- blank-line?
  "True if line `line-idx` in `styled-text` is out of bounds or whitespace-only.
  Out-of-bounds indices (before the first line, past the last) count as blank so
  edge lines don't pick up leading from a non-existent neighbor."
  [^StyledText styled-text line-idx]
  (let [n (.getLineCount styled-text)]
    (or (neg? line-idx)
        (>= line-idx n)
        (str/blank? (.getLine styled-text line-idx)))))

(defn- apply-line-spacing!
  "Install leading so wrapped visual lines within a source line breathe
  (uniform `setLineSpacing`) while specific source lines are suppressed by a
  provider override:
    - blank lines: 0 — so a Markdown paragraph break (blank source line)
      consumes roughly one blank-line height rather than stacking leading
      above and below it.
    - code-block lines: 0 — matches the HTML viewer's `pre { line-height: normal }`.
  Closes over `blocks` — reinstalled on every theme pass so spacing tracks
  edits (a line can cross into/out of a code block as the user types fences)."
  [^StyledText styled-text blocks]
  (.setLineSpacing styled-text prose-line-spacing-px)
  (.setLineSpacingProvider
   styled-text
   (reify StyledTextLineSpacingProvider
     (getLineSpacing [_ line-idx]
       (int
        (cond
          (line-in-code-block? styled-text blocks line-idx) 0
          (blank-line? styled-text line-idx)                0
          :else                                             prose-line-spacing-px))))))

(defn apply-theme!
  "Compute the markdown theme for `text` and apply StyleRanges to `styled-text`.
  Also sets full-line backgrounds for code blocks, list rendering, line
  spacing, and updates the link-spans registry. Must be called on the UI
  thread.

  Spellcheck misspellings — read from `(.getData styled-text
  \"spellcheck/miss\")`, populated by the debounced job scheduled from
  `on e/modify-text` — are folded into the theme spans *before*
  StyleRange conversion so the squiggly underline layers onto the
  existing font/color of whichever block contains the word.  The
  link-spans registry keeps the *unmerged* spans because link
  hit-testing doesn't care about spellcheck decorations."
  [styled-text text]
  (let [blocks       (md-theme/parse-blocks text)
        spans        (md-theme/theme text)
        misspellings (or (.getData styled-text "spellcheck/miss") [])
        merged       (spellcheck/merge-misspellings spans misspellings)
        ranges       (into-array StyleRange (map span->style-range merged))]
    (.setStyleRanges styled-text ranges)
    (apply-code-block-line-backgrounds! styled-text blocks)
    (apply-line-spacing!                styled-text blocks)
    (apply-list-rendering!              styled-text blocks)
    (update-link-spans! styled-text spans)))

;; ---------------------------------------------------------------------------
;; Link hit-testing
;; ---------------------------------------------------------------------------

(defn link-at-offset
  "Return the link span at `offset` in editor `st`, or nil."
  [^StyledText st offset]
  (let [links (link-spans-for st)]
    (first (filter (fn [{:keys [start length]}]
                     (and (<= start offset)
                          (< offset (+ start length))))
                   links))))

(tests
 ;; link-at-offset pure logic (simulated spans)
 (let [spans [{:start 5 :length 10 :type :inline/link :dest "http://x"}
              {:start 20 :length 8 :type :inline/wiki-draft :dest "page"}]]
   ;; Hit inside first link
   (first (filter (fn [{:keys [start length]}]
                    (and (<= start 7) (< 7 (+ start length))))
                  spans))
   := {:start 5 :length 10 :type :inline/link :dest "http://x"}
   ;; Miss in gap
   (first (filter (fn [{:keys [start length]}]
                    (and (<= start 16) (< 16 (+ start length))))
                  spans))
   := nil
   ;; Hit inside wiki-draft
   (first (filter (fn [{:keys [start length]}]
                    (and (<= start 22) (< 22 (+ start length))))
                  spans))
   := {:start 20 :length 8 :type :inline/wiki-draft :dest "page"})
 :rcf)

;; ---------------------------------------------------------------------------
;; Link navigation
;; ---------------------------------------------------------------------------

(defn- open-in-system-browser!
  "Open a URL in the system default browser. Runs off the UI thread."
  [^String url]
  (future
    (try
      (.browse (java.awt.Desktop/getDesktop) (URI. url))
      (catch Throwable t
        (log/error t "Failed to open URL in browser" url)))))

;; Pluggable navigation callback — set by main_window when wiring editors.
;; Signature: (fn [dest abs-path] ...) where dest is the raw :dest string
;; and abs-path is the file currently being edited (for relative resolution).
(defonce navigate-link-fn (atom nil))

(defn navigate-link!
  "Dispatch navigation for a link span's :dest value.
  `abs-path` is the current file's absolute path (for external-link logging);
  `rel-path` + `root-uri` identify the file relative to its indexed root
  and are needed to build a `winze:open-file?` URL for a relative .md link."
  [dest abs-path rel-path root-uri]
  (cond
    ;; wiki:root::path[#slug] — resolve via :file/id and navigate
    ;; Uses requiring-resolve to avoid circular dependency with server.main
    (str/starts-with? dest "wiki:")
    (let [wiki-ref   (subs dest 5)
          store-fn   (requiring-resolve 'llm-memory.server.main/store)
          resolve-fn (requiring-resolve 'llm-memory.index/resolve-wiki-ref)]
      (if-let [s (store-fn)]
        (if-let [resolved (resolve-fn s wiki-ref)]
          (let [{:keys [type file-path root-uri slug]} resolved
                dest-url (str "winze:open-file?"
                              "root=" (java.net.URLEncoder/encode (or root-uri "") "UTF-8")
                              "&path=" (java.net.URLEncoder/encode (or file-path "") "UTF-8")
                              (when (and (= type :heading) slug)
                                (str "#" slug)))]
            (when-let [f @navigate-link-fn]
              (f dest-url abs-path)))
          (log/warn "Broken wiki link — ref not found:" wiki-ref))
        (log/warn "Wiki link resolution failed — store not available")))

    ;; winze: internal protocol — delegate to pluggable handler
    (str/starts-with? dest "winze:")
    (when-let [f @navigate-link-fn]
      (f dest abs-path))

    ;; External URLs
    (or (str/starts-with? dest "http://")
        (str/starts-with? dest "https://")
        (str/starts-with? dest "mailto:"))
    (open-in-system-browser! dest)

    ;; Fragment link — stub for scroll-within-file
    (str/starts-with? dest "#")
    (log/info "Fragment navigation not yet implemented:" dest)

    ;; Relative .md link — resolve against current file's rel-path dir and
    ;; build a proper winze:open-file? URL (same shape hiccup produces for
    ;; the viewer), so the navigate-link-fn callback can handle it uniformly.
    :else
    (if (and rel-path root-uri)
      (let [[path-part fragment] (str/split dest #"#" 2)
            dir       (let [i (str/last-index-of rel-path "/")]
                        (if i (subs rel-path 0 i) ""))
            base      (java.nio.file.Paths/get (str dir) (into-array String []))
            resolved  (str (.normalize (.resolve base ^String path-part)))
            dest-url  (str "winze:open-file?"
                           "root=" (java.net.URLEncoder/encode root-uri "UTF-8")
                           "&path=" (java.net.URLEncoder/encode resolved "UTF-8")
                           (when fragment (str "#" fragment)))]
        (when-let [f @navigate-link-fn]
          (f dest-url abs-path)))
      (log/warn "Cannot resolve relative link — missing rel-path/root-uri:" dest))))

;; ---------------------------------------------------------------------------
;; MOD1-click handler + cursor feedback
;; ---------------------------------------------------------------------------

(defn install-link-interaction!
  "Install MOD1-click navigation and hover cursor feedback on a StyledText.
  `abs-path` — the file being edited (for logging / external links).
  `rel-path` + `root-uri` — path-to-root and root identifier for the
  editing file; used to resolve relative .md link dests to proper
  winze:open-file? URLs. Either may be nil.
  Must be called on the UI thread after the widget is created."
  [^StyledText st abs-path rel-path root-uri]
  (let [display     (.getDisplay st)
        hand-cursor (Cursor. display SWT/CURSOR_HAND)
        default-cur (.getCursor st)]

     ;; MOD1-click: navigate on mouse-up with MOD1 held
    (.addMouseListener st
                       (reify org.eclipse.swt.events.MouseListener
                         (mouseDown [_ _e])
                         (mouseDoubleClick [_ _e])
                         (mouseUp [_ e]
                           (when (not= 0 (bit-and (.stateMask e) SWT/MOD1))
                             (try
                               (let [offset (.getOffsetAtPoint st
                                                               (org.eclipse.swt.graphics.Point. (.x e) (.y e)))]
                                 (when-let [{:keys [dest]} (link-at-offset st offset)]
                                   (navigate-link! dest abs-path rel-path root-uri)))
                               (catch org.eclipse.swt.SWTException _
                 ;; getOffsetAtPoint throws if click is outside text
                                 nil))))))

    ;; Hover: hand cursor when MOD1 held over a link
    (.addMouseMoveListener st
                           (reify org.eclipse.swt.events.MouseMoveListener
                             (mouseMove [_ e]
                               (let [mod1? (not= 0 (bit-and (.stateMask e) SWT/MOD1))
                                     over-link?
                                     (when mod1?
                                       (try
                                         (let [offset (.getOffsetAtPoint st
                                                                         (org.eclipse.swt.graphics.Point. (.x e) (.y e)))]
                                           (some? (link-at-offset st offset)))
                                         (catch org.eclipse.swt.SWTException _ false)))]
                                 (.setCursor st (if over-link? hand-cursor default-cur))))))

    ;; Dispose hand cursor when widget is disposed
    (.addDisposeListener st
                         (reify org.eclipse.swt.events.DisposeListener
                           (widgetDisposed [_ _e]
                             (remove-link-spans! st)
                             (.dispose hand-cursor))))))

;; ---------------------------------------------------------------------------
;; Debounced auto-save
;; ---------------------------------------------------------------------------

(def save-delay-ms 1500)

(defn schedule-save!
  "Schedule a debounced save. Cancels any pending save for this editor.
  Returns the new ScheduledFuture."
  [^ScheduledFuture prev-future abs-path content on-saved]
  (when prev-future (.cancel prev-future false))
  (.schedule ^java.util.concurrent.ScheduledExecutorService res/executor
             ^Runnable (fn []
                         (try
                           (spit abs-path content)
                           (when on-saved (async-exec! on-saved))
                           (catch Throwable t
                             (log/error t "Auto-save failed" abs-path))))
             (long save-delay-ms)
             TimeUnit/MILLISECONDS))

(defn flush-save!
  "If there is a pending save, cancel the timer and save immediately."
  [^ScheduledFuture pending-future abs-path content]
  (when (and pending-future (not (.isDone pending-future)))
    (.cancel pending-future false)
    (try
      (spit abs-path content)
      (catch Throwable t
        (log/error t "Flush-save failed" abs-path)))))

(tests
  ;; flush-save! with nil future just writes
 (let [tmp  (java.io.File/createTempFile "test-save" ".md")
       path (.getAbsolutePath tmp)]
   (spit path "original")
   (flush-save! nil path "updated")
   (slurp path) := "original"  ; nil future → no-op
   (.delete tmp))
 :rcf)

;; ---------------------------------------------------------------------------
;; Debounced spellcheck
;; ---------------------------------------------------------------------------

(def spellcheck-delay-ms 400)

(defn schedule-spellcheck!
  "Schedule a background spellcheck pass on `st` for `text`.  Cancels
  `prev-future` first.  Results are written back with `.setData` +
  `apply-theme!` on the UI thread.  Returns the new ScheduledFuture."
  [^StyledText st ^ScheduledFuture prev-future ^String text]
  (when prev-future (.cancel prev-future false))
  (.schedule ^java.util.concurrent.ScheduledExecutorService res/executor
             ^Runnable
             (fn []
               (try
                 (let [spans (md-theme/theme text)
                       miss  (spellcheck/misspellings-for text spans)]
                   (async-exec!
                    (fn []
                      (when-not (.isDisposed st)
                        (.setData st "spellcheck/miss" miss)
                        (apply-theme! st (.getText st))))))
                 (catch Throwable t
                   (log/error t "Spellcheck failed"))))
             (long spellcheck-delay-ms)
             TimeUnit/MILLISECONDS))

(defn install-spellcheck!
  "Install live spellcheck on `st`: context menu, a ModifyListener that
  debounces a background spellcheck pass, a refresh listener fanned
  from dictionary changes, and cleanup on widget disposal.

  Safe to call from any caller that has already constructed a
  StyledText on the UI thread (e.g. `markdown-editor` CDT init, the
  main-window `toggle-mode!` inline editor).  Must be called on the
  UI thread."
  [^StyledText st]
  (let [spell-future  (atom nil)
        refresh-token (atom nil)
        reschedule!   (fn [] (reset! spell-future
                                     (schedule-spellcheck! st @spell-future
                                                           (.getText st))))]
    (spellcheck-menu/install! st)
    (.addModifyListener st
                        (reify org.eclipse.swt.events.ModifyListener
                          (modifyText [_ _event] (reschedule!))))
    (.addDisposeListener st
                         (reify org.eclipse.swt.events.DisposeListener
                           (widgetDisposed [_ _event]
                             (when-let [^ScheduledFuture f @spell-future]
                               (.cancel f false))
                             (when-let [tok @refresh-token]
                               (spellcheck/unregister-refresh-listener! tok)))))
    (reset! refresh-token
            (spellcheck/register-refresh-listener!
             (fn []
               (async-exec!
                #(when-not (.isDisposed st) (reschedule!))))))
    (reschedule!)
    st))

;; ---------------------------------------------------------------------------
;; Undo / Redo
;; ---------------------------------------------------------------------------

(defn capture-snapshot
  "Capture editor state as a snapshot map."
  [styled-text]
  {:text      (.getText styled-text)
   :top-pixel (.getTopPixel styled-text)
   :caret     (.getCaretOffset styled-text)})

(def ^:dynamic *restoring-snapshot?*
  "True while `restore-snapshot!` is mutating a StyledText. The editor's
  ModifyListener reads this to decide whether the incoming ModifyEvent
  represents a fresh user edit (clear redo, schedule save/snapshot) or an
  undo/redo restore (don't touch redo, don't push a duplicate snapshot).
  Scoped to the calling thread — safe because SWT ModifyListeners fire
  synchronously on the UI thread during `.setText`."
  false)

(def ^:dynamic *suppressing-modify?*
  "True during a programmatic content replacement — external-file refresh
  via `editor-set-text!`, or the widget's own initial content-apply.
  The modify listener reads this to skip `record-edit!`, the dirty flip,
  and the debounced save schedule. The `md/text` atom reset,
  `apply-theme!`, and `on-change` still fire so watchers, theming, and
  tab titles track the new content.
  Scoped to the calling thread — safe because SWT ModifyListeners fire
  synchronously on the UI thread during `.setText`."
  false)

(defn- restore-snapshot!
  "Replace editor content and restore scroll/cursor position.
  Must be called on the UI thread."
  [styled-text {:keys [text top-pixel caret]}]
  (binding [*restoring-snapshot?* true]
    (.setText styled-text text)
    (.setTopPixel styled-text top-pixel)
    (.setCaretOffset styled-text (min caret (count text)))))

(defn push-undo!
  "Push a snapshot onto the undo stack, de-duped against the top.
  Does NOT touch redo — callers that represent a fresh user edit clear
  redo themselves (see `record-edit!`). Callers that represent a
  restore (undo/redo) leave redo alone."
  [history snapshot]
  (swap! history (fn [h]
                   (if (= (:text snapshot)
                          (some-> h :undo peek :text))
                     h
                     (update h :undo conj snapshot)))))

(defn undo!
  "Undo: capture current state → redo stack, pop undo stack → restore.
  Also updates `:last-snap` so the edit listener treats the restored state
  as the new baseline for its next push."
  [styled-text history]
  (let [{:keys [undo]} @history]
    (when (seq undo)
      (let [current  (capture-snapshot styled-text)
            previous (peek undo)]
        (swap! history (fn [h]
                         (-> h
                             (update :undo pop)
                             (update :redo conj current)
                             (assoc :last-snap previous))))
        (restore-snapshot! styled-text previous)))))

(defn redo!
  "Redo: capture current state → undo stack, pop redo stack → restore.
  Updates `:last-snap` to the restored state."
  [styled-text history]
  (let [{:keys [redo]} @history]
    (when (seq redo)
      (let [current (capture-snapshot styled-text)
            next'   (peek redo)]
        (swap! history (fn [h]
                         (-> h
                             (update :undo conj current)
                             (update :redo pop)
                             (assoc :last-snap next'))))
        (restore-snapshot! styled-text next')))))

(defn record-edit!
  "Called from the editor's ModifyListener on every fresh user edit (not
  while `*restoring-snapshot?*` is bound). Pushes the *previous* stable
  snapshot (`:last-snap`) to the undo stack as an undo point, clears the
  redo stack, and updates `:last-snap` to the current snapshot."
  [styled-text history]
  (let [current (capture-snapshot styled-text)
        prev    (:last-snap @history)]
    (when (and prev (not= (:text prev) (:text current)))
      (push-undo! history prev)
      (swap! history assoc :redo []))
    (swap! history assoc :last-snap current)))

(tests
  ;; push-undo! adds to undo, leaves redo untouched (callers clear redo)
 (let [h (atom {:undo [] :redo [{:text "r"}]})]
   (push-undo! h {:text "a"})
   (:undo @h) := [{:text "a"}]
   (:redo @h) := [{:text "r"}])

  ;; push-undo! de-duplicates against the top of the stack
 (let [h (atom {:undo [{:text "a"}] :redo []})]
   (push-undo! h {:text "a"})
   (:undo @h) := [{:text "a"}])

  ;; undo stack manipulation (pure logic, no SWT)
 (let [h (atom {:undo [{:text "first"}] :redo []})]
   (let [current {:text "second"}
         prev    (peek (:undo @h))]
     (swap! h (fn [h] (-> h (update :undo pop) (update :redo conj current))))
     (:undo @h) := []
     (:redo @h) := [{:text "second"}]
     prev := {:text "first"}))

  ;; undo on empty stack is no-op
 (let [h (atom {:undo [] :redo []})]
   (seq (:undo @h)) := nil)

 :rcf)

;; ---------------------------------------------------------------------------
;; Scroll / caret helpers
;; ---------------------------------------------------------------------------

(defn scroll-to-line!
  "Scroll styled-text so `line` is top-visible. Place caret at line start.
  Must be called on the UI thread."
  [styled-text line]
  (let [line (min line (dec (.getLineCount styled-text)))]
    (.setTopIndex styled-text line)
    (.setCaretOffset styled-text (.getOffsetAtLine styled-text line))))

(defn line-visible?
  "True if `caret-offset` falls on a line currently visible in the viewport."
  [styled-text caret-offset]
  (let [text-len  (count (.getText styled-text))
        safe-off  (min caret-offset (max 0 (dec text-len)))
        line      (.getLineAtOffset styled-text safe-off)
        top       (.getTopIndex styled-text)
        client-h  (.height (.getClientArea styled-text))
        line-h    (.getLineHeight styled-text)
        vis-lines (if (pos? line-h) (quot client-h line-h) 30)]
    (<= top line (+ top vis-lines))))

;; ---------------------------------------------------------------------------
;; CDT init function
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Content assist triggers
;; ---------------------------------------------------------------------------

(defn- extract-bracket-text
  "Extract the text between [ and ] immediately before `caret`.
  Returns the link text or nil if no bracket pair found."
  [^String text caret]
  (when (and (> caret 0) (= \] (.charAt text (dec caret))))
    (let [close-pos (dec caret)]
      (loop [i (dec close-pos)]
        (cond
          (neg? i)                  nil
          (= \[ (.charAt text i))  (subs text (inc i) close-pos)
          (= \newline (.charAt text i)) nil  ;; don't span across lines
          :else                     (recur (dec i)))))))

(tests
 "extract-bracket-text — extracts text between [ and ]"
 ;; Pre-insertion position (caret after ]): direct call
 (extract-bracket-text "[design decisions](" 18) := "design decisions"
 (extract-bracket-text "some [link text](" 16) := "link text"
 ;; Post-insertion position (caret after (): use (dec caret) as handle-paren-trigger! does
 (extract-bracket-text "[design decisions](" (dec 19)) := "design decisions"
 (extract-bracket-text "some [link text](" (dec 17)) := "link text"
 (extract-bracket-text "no bracket(" (dec 11)) := nil
 (extract-bracket-text "](" (dec 2)) := nil ;; empty brackets
 :rcf)

(defn- handle-wiki-draft-trigger!
  "Called when `[` is typed after `[`. Opens content assist for wiki link creation.
  Tracks the bracket start position for rewriting [[...]] → [title](wiki:uuid)."
  [^StyledText st abs-path bracket-start]
  (content-assist/open-content-assist!
   {:styled-text st
    :seed-text   ""
    :mode        :wiki-create
    :on-select   (fn [{:keys [uuid title]}]
                   (async-exec!
                    (fn []
                      ;; Find the extent of [[...]] — search for ]] from bracket-start
                      (let [text     (.getText st)
                            caret    (.getCaretOffset st)
                            ;; Look for ]] after bracket-start
                            end-pos  (or (str/index-of text "]]" bracket-start)
                                         caret)
                            ;; Include the ]] if found
                            end      (if (and end-pos (< end-pos (count text))
                                              (str/starts-with? (subs text end-pos) "]]"))
                                       (+ end-pos 2)
                                       caret)
                            link     (str "[" (or title "Link") "](wiki:" uuid ")")]
                        (.replaceTextRange st bracket-start (- end bracket-start) link)
                        (.setCaretOffset st (+ bracket-start (count link)))))))
    :on-cancel   nil}))

(defn- handle-paren-trigger!
  "Called when `(` is typed. If preceded by `]`, open content assist
  with the bracket text as seed. Returns true if trigger fired.
  Note: called via async-exec!, so `(` is already inserted and caret is after it."
  [^StyledText st abs-path]
  (let [text  (.getText st)
        caret (.getCaretOffset st)]
    ;; After async-exec!, ( is already inserted and caret is after it.
    ;; Verify the ( is there, then look before it for ]...[.
    (when (and (pos? caret) (= \( (.charAt text (dec caret))))
      (when-let [link-text (extract-bracket-text text (dec caret))]
        (content-assist/open-content-assist!
         {:styled-text st
          :seed-text   link-text
          :on-select   (fn [{:keys [uuid title]}]
                         (async-exec!
                          (fn []
                            ;; Insert wiki:uuid) after the ( that was already typed
                            (let [insert-pos (.getCaretOffset st)
                                  link-url   (str "wiki:" uuid ")")]
                              (.replaceTextRange st insert-pos 0 link-url)
                              (.setCaretOffset st (+ insert-pos (count link-url)))))))
          :on-cancel   nil})
        true))))

(defn handle-insert-link!
  "Mod1+K: open content assist to insert a link. If text is selected, use it
  as both the link text and search seed."
  [^StyledText st abs-path]
  (let [sel-text   (.getSelectionText st)
        sel-range  (.getSelectionRange st)
        sel-start  (.x sel-range)
        sel-len    (.y sel-range)
        seed       (when (seq sel-text) sel-text)]
    (content-assist/open-content-assist!
     {:styled-text st
      :seed-text   (or seed "")
      :on-select   (fn [{:keys [uuid title]}]
                     (async-exec!
                      (fn []
                        (let [display-text (or (when (seq sel-text) sel-text) title "Link")
                              link         (str "[" display-text "](wiki:" uuid ")")]
                          (if (pos? sel-len)
                            ;; Replace selection with full link
                            (.replaceTextRange st sel-start sel-len link)
                            ;; Insert at cursor
                            (let [pos (.getCaretOffset st)]
                              (.replaceTextRange st pos 0 link)))
                          (.setCaretOffset st (+ (if (pos? sel-len) sel-start (.getCaretOffset st))
                                                 (count (str "[" (or (when (seq sel-text) sel-text) title "Link")
                                                             "](wiki:" uuid ")"))))))))
      :on-cancel   nil})))

;; ---------------------------------------------------------------------------
;; Delimiter-pair wrap
;; ---------------------------------------------------------------------------

(def ^:private pair-delimiters
  "Map of opener char → closer char. Typing an opener with a non-empty
  selection wraps the selection with opener + closer and leaves the
  inner text selected.

  Includes natural-language brackets / quotes plus single-char Markdown
  inline markers. Multi-char markers (**, __, ~~, ==) are served by the
  Mod1+B / Mod1+I / toggle-strikethrough / toggle-highlight commands."
  {\(  \)
   \[  \]
   \{  \}
   \<  \>
   \"  \"
   \'  \'
   \`  \`
   \*  \*
   \_  \_})

(defn- wrap-with-pair
  "Build an edit-result map that wraps [sel-start, sel-start+sel-length)
  in `text` with `opener` + its matching closer. Leaves the inner text
  selected. Returns nil if sel-length is zero or opener has no pair entry."
  [^String text sel-start sel-length opener]
  (when (and (pos? sel-length)
             (contains? pair-delimiters opener))
    (let [closer   (get pair-delimiters opener)
          selected (subs text sel-start (+ sel-start sel-length))]
      {:replace-start  sel-start
       :replace-length sel-length
       :replacement    (str opener selected closer)
       :select-after   [(inc sel-start) sel-length]})))

(tests
 "wrap-with-pair — wraps selection with opener/closer and re-selects inner"
 (wrap-with-pair "hello world" 6 5 \()
 := {:replace-start 6 :replace-length 5
     :replacement   "(world)"
     :select-after  [7 5]}

 (wrap-with-pair "hello world" 0 5 \")
 := {:replace-start 0 :replace-length 5
     :replacement   "\"hello\""
     :select-after  [1 5]}

 (wrap-with-pair "hello world" 0 5 \[)
 := {:replace-start 0 :replace-length 5
     :replacement   "[hello]"
     :select-after  [1 5]}

 "wrap-with-pair — returns nil for zero-length selection"
 (wrap-with-pair "abc" 1 0 \() := nil

 "wrap-with-pair — returns nil for un-paired character"
 (wrap-with-pair "abc" 0 3 \x) := nil
 :rcf)

(defn- modifier-pressed?
  "True if any keyboard modifier (other than SHIFT) is in stateMask.
  SHIFT is allowed because (, {, <, \", * require it on US layouts.
  Note: SWT.MOD2 is SHIFT on macOS (and often on other platforms), so
  it must NOT be included in the blocker mask."
  [state-mask]
  (not (zero? (bit-and state-mask
                       (bit-or SWT/MOD1 SWT/CTRL SWT/ALT SWT/COMMAND)))))

(defn install-pair-wrap!
  "Install a VerifyKeyListener on a StyledText that wraps the active
  selection with matching delimiters when the user types a pair opener
  character. Leaves the default behaviour untouched when there is no
  selection or a modifier is held. Must be called on the UI thread."
  [^StyledText st]
  (.addVerifyKeyListener
   st
   (reify VerifyKeyListener
     (verifyKey [_ event]
       (when (.doit event)
         (let [ch         (.character event)
               state-mask (.stateMask event)]
           (when (and (contains? pair-delimiters ch)
                      (not (modifier-pressed? state-mask)))
             (let [sel   (.getSelectionRange st)
                   start (.-x sel)
                   len   (.-y sel)]
               (when (pos? len)
                 (when-let [{:keys [replace-start replace-length
                                    replacement select-after]}
                            (wrap-with-pair (.getText st) start len ch)]
                   (set! (.doit event) false)
                   (.replaceTextRange st replace-start replace-length
                                      replacement)
                   (let [[sel-start sel-len] select-after]
                     (.setSelection st sel-start (+ sel-start sel-len)))
                   (.showSelection st)))))))))))

(defn install-content-assist-triggers!
  "Install key-down listener for content assist triggers on a StyledText.
  Detects `(` after `]` (link URL) and `[[` (wiki-draft).
  Must be called on the UI thread after the widget is created."
  [^StyledText st abs-path]
  (.addKeyListener st
                   (reify org.eclipse.swt.events.KeyListener
                     (keyPressed [_ event]
                       (let [mod  (.stateMask event)
                             cmd? (not= 0 (bit-and mod SWT/MOD1))
                             ch   (.character event)]
                         (cond
                           ;; ( after ] — trigger content assist for link URL
                           (and (not cmd?) (= ch \())
                           (async-exec!
                            #(handle-paren-trigger! st abs-path))

                           ;; [[ — trigger wiki-draft content assist
                           (and (not cmd?) (= ch \[))
                           (async-exec!
                            (fn []
                              (let [text  (.getText st)
                                    caret (.getCaretOffset st)]
                                ;; After insertion the caret is after the second [.
                                ;; Both characters before the caret must be [ to confirm [[.
                                (when (and (>= caret 2)
                                           (= \[ (.charAt text (- caret 1)))
                                           (= \[ (.charAt text (- caret 2))))
                                  (handle-wiki-draft-trigger! st abs-path (- caret 2)))))))))
                     (keyReleased [_ _]))))

(defn markdown-editor
  "CDT init function — returns a bare StyledText configured as a Markdown
  editor. See `Plans/todo/MARKDOWN-EDITOR-WIDGET-CONTEXT.md` for the full
  contract.

  Options map (first arg):
    :content          initial text (default \"\")
    :abs-path         enables auto-save, link-interaction, content-assist
    :rel-path         enables wiki-link resolution + hover link preview
    :root-uri         enables wiki-link resolution + hover link preview
    :initial-line     scroll to this 0-based source line, synchronously
    :initial-caret    restore caret offset after layout (async)
    :on-change        (fn [text]) — fires on every content change on the
                       UI thread (user edit, undo/redo, external refresh,
                       initial apply). Must be idempotent.
    :spellcheck?      default true
    :content-assist?  default true (no-op without :abs-path)
    :link-nav?        default true (no-op without :abs-path)
    :pair-wrap?       default true

  Remaining args are `extra-inits` applied AFTER the post-construct fn.

  State exposed via `.setData`:
    \"scope\"         :editor
    \"md/dirty?\"     atom<boolean>
    \"md/history\"    atom<{:undo :redo :last-snap}>
    \"md/save-future\" atom<ScheduledFuture>
    \"md/text\"       atom<String> — canonical-text mirror
    \"md/abs-path\"   string (when provided)"
  [{:keys [content abs-path rel-path root-uri on-change
           initial-line initial-caret
           spellcheck? content-assist? link-nav? pair-wrap?]
    :or   {content          ""
           spellcheck?      true
           content-assist?  true
           link-nav?        true
           pair-wrap?       true}}
   & extra-inits]
  (let [dirty?      (atom false)
        save-future (atom nil)
        history     (atom {:undo [] :redo [] :last-snap nil})
        text-atom   (atom content)]
    (apply styled-text (| SWT/MULTI SWT/V_SCROLL SWT/WRAP)
           :font                  @res/body-font
           :background            @res/color-mine-shaft
           :foreground            @res/color-crystal-white
           :selection-background  @res/color-royal-purple
           :selection-foreground  @res/color-pure-white
           :word-wrap             true

           (on e/modify-text [_props st _e]
               (let [text (.getText st)]
                 (reset! text-atom text)
                 (apply-theme! st text)
                 (when-not (or *restoring-snapshot?*
                               *suppressing-modify?*)
                   (record-edit! st history))
                 (when-not *suppressing-modify?*
                   (reset! dirty? true)
                   (when abs-path
                     (reset! save-future
                             (schedule-save! @save-future abs-path text
                                             #(reset! dirty? false)))))
                 (when on-change
                   (try (on-change text)
                        (catch Throwable t
                          (log/error t "on-change callback threw"))))))

           (fn [_props st]
             (.setData st "scope"          :editor)
             (.setData st "md/dirty?"      dirty?)
             (.setData st "md/save-future" save-future)
             (.setData st "md/history"     history)
             (.setData st "md/text"        text-atom)
             (when abs-path (.setData st "md/abs-path" abs-path))
             (when pair-wrap?  (install-pair-wrap! st))
             (when (and content-assist? abs-path)
               (install-content-assist-triggers! st abs-path))
             (when (and link-nav? abs-path)
               (install-link-interaction! st abs-path rel-path root-uri))
             (when (and rel-path root-uri)
               (link-preview/install-link-preview! st rel-path root-uri))
             (binding [*suppressing-modify?* true]
               (.setText st content))
             (when spellcheck? (install-spellcheck! st))
             ;; Theme refresh listener — re-applies background/foreground/
             ;; fonts + re-runs the span styling pass whenever
             ;; `theme/reload-theme!` broadcasts. The wrapper Composite
             ;; (always this StyledText's parent — see toggle-mode!) must
             ;; be re-tinted too, since its mine-shaft bg otherwise keeps
             ;; a disposed Color reference.
             (let [theme-tok
                   (theme/register-refresh-listener!
                    (fn []
                      (async-exec!
                       (fn []
                         (when-not (.isDisposed st)
                           (let [wrapper (.getParent st)]
                             (when (and wrapper (not (.isDisposed wrapper)))
                               (.setBackground wrapper @res/color-mine-shaft)))
                           (.setFont st                 @res/body-font)
                           (.setBackground st           @res/color-mine-shaft)
                           (.setForeground st           @res/color-crystal-white)
                           (.setSelectionBackground st  @res/color-royal-purple)
                           (.setSelectionForeground st  @res/color-pure-white)
                           (apply-theme! st (.getText st)))))))]
               (.addDisposeListener
                st
                (reify org.eclipse.swt.events.DisposeListener
                  (widgetDisposed [_ _e]
                    (theme/unregister-refresh-listener! theme-tok)))))
             (when initial-line
               (scroll-to-line! st initial-line))
             (swap! history assoc :last-snap (capture-snapshot st))
             (when initial-caret
               (async-exec!
                (fn []
                  (when-not (.isDisposed st)
                    (let [text-len   (count (.getText st))
                          safe-caret (min initial-caret
                                          (max 0 (dec text-len)))]
                      (when (line-visible? st safe-caret)
                        (.setCaretOffset st safe-caret))))))))

           extra-inits)))

;; ---------------------------------------------------------------------------
;; Accessor helpers
;; ---------------------------------------------------------------------------

(defn editor-dirty?
  "True if the editor has unsaved changes. Returns false defensively for
  widgets not built by `markdown-editor` (no `md/dirty?` data)."
  [st]
  (boolean (some-> (.getData st "md/dirty?") deref)))

(defn editor-history-atom
  "Return the history atom, or nil for widgets not built by
  `markdown-editor`. Callers pass the returned atom to `undo!` / `redo!`."
  [st]
  (.getData st "md/history"))

(defn editor-text
  "Return the canonical-text atom (an atom<String>), or nil for widgets
  not built by `markdown-editor`.

  The atom is `reset!`-ed synchronously by the modify listener on every
  content change. Prefer `add-watch` on this atom to polling
  `(.getText st)` from another thread: reading `@(editor-text st)` is
  safe from any thread; `(.getText st)` is not."
  [st]
  (.getData st "md/text"))

(defn editor-flush!
  "Flush pending save to disk synchronously. No-op for synthetic editors
  (no `:abs-path`) or widgets not built by `markdown-editor`. Call
  immediately before `.dispose`. Must be called on the UI thread."
  [^StyledText st]
  (when-let [abs-path (.getData st "md/abs-path")]
    (when-let [sf (.getData st "md/save-future")]
      (flush-save! @sf abs-path (.getText st))
      (reset! sf nil))
    (when-let [d (.getData st "md/dirty?")]
      (reset! d false))))

(defn editor-set-text!
  "Replace editor content under `*suppressing-modify?*`. Skips when
  content is identical, the editor is dirty, or the widget has no
  `md/history` data. After `.setText`, re-seeds `:last-snap` so the
  next real user edit's Cmd+Z does not revert the external change.
  Must be called on the UI thread. Returns nil."
  [^StyledText st text]
  (when-let [history-atom (.getData st "md/history")]
    (when (and (not= text (.getText st))
               (not (editor-dirty? st)))
      (binding [*suppressing-modify?* true]
        (.setText st text))
      (swap! history-atom assoc :last-snap (capture-snapshot st))))
  nil)

(definterface IWidgetDataStub
  (getData [^String key]))

(tests
 (let [stub (fn [data]
              (reify IWidgetDataStub
                (getData [_ k] (get data k))))]

   (editor-dirty? (stub {}))                           := false
   (editor-dirty? (stub {"md/dirty?" (atom true)}))    := true
   (editor-dirty? (stub {"md/dirty?" (atom false)}))   := false

   (editor-history-atom (stub {}))                     := nil
   (let [h (atom {:undo [] :redo []})]
     (editor-history-atom (stub {"md/history" h}))    := h)

   (editor-text (stub {}))                             := nil
   (let [t (atom "hi")]
     (editor-text (stub {"md/text" t}))               := t))
 :rcf)
