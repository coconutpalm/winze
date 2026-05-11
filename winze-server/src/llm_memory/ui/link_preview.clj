(ns llm-memory.ui.link-preview
  "Link preview popup — shows a result card when hovering over or placing
  the cursor inside any local file link (wiki:, winze:open-file?, or
  plain relative .md path).

  Works in both edit mode (StyledText) and view mode (Browser).

  Edit mode triggers:
    - Mouse hover over a link span (300ms delay, no MOD1)
    - Caret enters a link span (200ms delay)

  View mode triggers:
    - Mouse hover over a local-file link (300ms delay, via injected
      JavaScript + BrowserFunction callbacks)

  Dismiss:
    - Mouse leaves both the link and the preview popup
    - Caret moves outside a link span (edit mode)
    - Esc key
    - Any edit action
    - Scroll / navigation

  The popup renders the linked content using `search/card-html` for visual
  consistency with search results. For file-level refs (no heading slug),
  the preview shows the first section chunk (H1 + first H2 body); for
  heading refs, the matching chunk. Auto-sizes height to fit content
  (Snippet 372 technique)."
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [llm-memory.ui.resources :as resources]
   [llm-memory.ui.search :as search]
   [ui.SWT :refer [async-exec! |]])
  (:import
   [java.net URLDecoder]
   [java.nio.file Paths]
   [java.util.concurrent
    Executors ScheduledExecutorService ScheduledFuture TimeUnit]
   [org.eclipse.swt SWT]
   [org.eclipse.swt.browser Browser BrowserFunction ProgressAdapter]
   [org.eclipse.swt.custom CaretEvent CaretListener StyledText]
   [org.eclipse.swt.events
    DisposeListener ModifyListener MouseMoveListener MouseTrackAdapter]
   [org.eclipse.swt.graphics Point]
   [org.eclipse.swt.layout FillLayout]
   [org.eclipse.swt.widgets Shell]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private preview-width 500)
(def ^:private preview-max-height 400)
(def ^:private preview-initial-height 50)
(def ^:private hover-delay-ms 300)
(def ^:private caret-delay-ms 200)

;; ---------------------------------------------------------------------------
;; Shared preview shell (lazy, one per application)
;; ---------------------------------------------------------------------------

(defonce ^:private preview-state (atom nil))
;; {:shell Shell :browser Browser :executor ScheduledExecutorService
;;  :hover-future ScheduledFuture :mouse-in-preview? atom
;;  :current-uuid str}

(declare preview-open? hide-preview!)

(defn- ensure-preview-shell!
  "Create the shared preview Shell if it doesn't exist or was disposed.
  Returns the current preview-state map."
  []
  (let [s @preview-state]
    (if (and s (:shell s) (not (.isDisposed ^Shell (:shell s))))
      s
      (let [parent   (resources/element :main-window)
            executor (or (when s (:executor s))
                         (Executors/newSingleThreadScheduledExecutor))
            ;; Raw Shell. — this popup is a sub-shell parented to the main
            ;; window (for z-order), managed as a singleton via @preview-state,
            ;; and hosts a MouseTrackListener whose multi-method interface
            ;; requires `proxy` (guide §8).  `util/show` targets Display-level
            ;; shells without a parent; converting requires both a
            ;; parent-aware `show` helper and restructuring the singleton
            ;; lifecycle.
            sh       (Shell. ^Shell parent (| SWT/TOOL SWT/ON_TOP SWT/NO_TRIM))
            _        (.setLayout sh (let [l (FillLayout.)]
                                      (set! (.-marginWidth l) 0)
                                      (set! (.-marginHeight l) 0)
                                      l))
            _        (.setBackground sh @resources/color-mine-shaft)
            brow     (Browser. sh SWT/WEBKIT)
            mouse-in (atom false)
            new-state {:shell             sh
                       :browser           brow
                       :executor          executor
                       :hover-future      nil
                       :mouse-in-preview? mouse-in
                       :current-uuid      nil}]
        ;; Mouse enters/leaves preview shell — track for dismiss logic
        (.addMouseTrackListener sh
                                (proxy [MouseTrackAdapter] []
                                  (mouseEnter [_e] (reset! mouse-in true))
                                  (mouseExit [_e]
                                    (reset! mouse-in false)
                                    (async-exec!
                                     #(when (and (preview-open?) (not @mouse-in))
                                        (hide-preview!))))))
        (.addDisposeListener sh
                             (reify DisposeListener
                               (widgetDisposed [_ _e]
                                 (reset! preview-state nil))))
        (reset! preview-state new-state)
        new-state))))

(defn preview-open?
  "Return true if the link preview popup is currently visible."
  []
  (when-let [{:keys [^Shell shell]} @preview-state]
    (and shell (not (.isDisposed shell)) (.isVisible shell))))

;; ---------------------------------------------------------------------------
;; Link destination parsing — normalize any link string to a file reference
;; ---------------------------------------------------------------------------

(defn- parse-query-string
  "Parse a URL query string into a map of URL-decoded key/value pairs."
  [qs]
  (into {}
        (for [pair (str/split qs #"&")
              :let [[k v] (str/split pair #"=" 2)]]
          [(URLDecoder/decode k "UTF-8")
           (URLDecoder/decode (or v "") "UTF-8")])))

(defn- local-md-dest?
  "True when `dest` looks like a relative path to a .md file — nothing
  absolute, no scheme, no fragment-only."
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

(defn- resolve-wiki-root-uri
  "Map a wiki root-name (e.g. \"winze\") to its registered root-uri, or
  nil if unknown."
  [root-name]
  (try
    (when-let [list-roots-fn (requiring-resolve 'llm-memory.core/list-roots)]
      (when-let [store-fn (requiring-resolve 'llm-memory.server.main/store)]
        (when-let [s (store-fn)]
          (->> (list-roots-fn s)
               (filter #(= root-name (:root/name %)))
               first
               :root/uri))))
    (catch Throwable _ nil)))

(defn- parse-link-dest
  "Normalize a link destination (edit-mode :dest or view-mode href) to
  {:file-path :root-uri :slug} or nil.

  `ctx` describes the file containing the link:
    :rel-path  — path relative to its root's plans-dir (for resolving
                 relative .md links in edit mode)
    :root-uri  — root URI of the containing file

  Handles three forms:
    - wiki:root-name::path[#slug]
    - winze:open-file?root=<uri>&path=<rel>[#slug]
    - relative .md path (requires :rel-path + :root-uri in ctx)

  Anything else (http(s):, mailto:, fragment-only, non-.md file) → nil."
  [^String dest {:keys [rel-path root-uri]}]
  (cond
    (str/blank? dest) nil

    (str/starts-with? dest "wiki:")
    (let [body           (subs dest 5)
          [file-part slug] (str/split body #"#" 2)
          [root-name path] (str/split file-part #"::" 2)
          ruri           (resolve-wiki-root-uri root-name)]
      (when (and ruri path)
        {:file-path path :root-uri ruri :slug slug}))

    (str/starts-with? dest "winze:open-file?")
    (let [qs        (subs dest (count "winze:open-file?"))
          [qs-only frag] (str/split qs #"#" 2)
          params    (parse-query-string qs-only)
          ruri      (get params "root")
          path      (get params "path")]
      (when (and ruri path)
        {:file-path path :root-uri ruri :slug frag}))

    (and rel-path root-uri (local-md-dest? dest))
    (let [[path-part slug] (str/split dest #"#" 2)
          dir  (let [i (str/last-index-of rel-path "/")]
                 (if i (subs rel-path 0 i) ""))
          base (Paths/get (str dir) (into-array String []))
          resolved (str (.normalize (.resolve base ^String path-part)))]
      {:file-path resolved :root-uri root-uri :slug slug})

    :else nil))

;; ---------------------------------------------------------------------------
;; Data resolution
;; ---------------------------------------------------------------------------

(defn- resolve-file-preview
  "Given a normalized file reference {:file-path :root-uri :slug}, look
  up the target file and return a result map suitable for
  `search/card-html`, or nil if not found.

  For heading refs (slug present), uses the matching chunk's text.
  For file refs (no slug), uses the chunk with the minimum
  :chunk/section — which contains the H1 plus the first section's body.
  Falls back to a bare \"# <path>\" only if the file has no chunks.

  Chunk text is fetched via `pull-entity` rather than bound as a datalog
  :find variable: Datalevin's query planner silently returns zero rows
  when `:chunk/text` (a long unindexed string) is output-bound in a
  multi-join. `llm-memory.core/first-chunk-text` uses the same pattern."
  [{:keys [file-path root-uri slug]}]
  (try
    (let [store-fn (requiring-resolve 'llm-memory.server.main/store)
          query-fn (requiring-resolve 'llm-memory.store.protocol/query)
          pull-fn  (requiring-resolve 'llm-memory.store.protocol/pull-entity)]
      (when-let [s (store-fn)]
        (when-let [fid (ffirst
                        (query-fn s
                                  '[:find ?f
                                    :in $ ?path ?ruri
                                    :where
                                    [?r :root/uri ?ruri]
                                    [?f :file/root ?r]
                                    [?f :file/path ?path]]
                                  {:path file-path :ruri root-uri}))]
          (let [file-ent   (pull-fn s fid)
                chunk-eids (query-fn s
                                     '[:find [?c ...]
                                       :in $ ?f
                                       :where [?c :chunk/file ?f]]
                                     {:f fid})
                chunks     (seq (map #(pull-fn s %) chunk-eids))
                picked     (when chunks
                             (if (seq slug)
                               (first (filter #(= slug (:chunk/slug %)) chunks))
                               (apply min-key #(or (:chunk/section %) 0) chunks)))]
            {:file/path   file-path
             :root/uri    root-uri
             :file/status (or (:file/status file-ent) "active")
             :file/type   (or (:file/type file-ent) "context")
             :file/group  (or (:file/group file-ent) "")
             :chunk/text  (or (:chunk/text picked) (str "# " file-path))
             :chunk/slug  (or slug "")}))))
    (catch Throwable t
      (log/warn t "Link preview resolution failed")
      nil)))

;; ---------------------------------------------------------------------------
;; Show / hide (shared, coordinate-based)
;; ---------------------------------------------------------------------------

(defn- show-preview-at!
  "Display the preview popup anchored to a link.

  `below-top-y`   display-coord Y where popup TOP sits when placed below.
  `above-bottom-y` display-coord Y where popup BOTTOM sits when placed
                  above (used only if placing below would overflow the
                  monitor). Callers bake any desired breathing room
                  (padding above/below the link, extra rows of
                  clearance) into these two values — this function
                  treats them as final pixel targets.

  Auto-sizes height to fit content using Snippet 372 technique."
  [uuid result display-x below-top-y above-bottom-y ^Shell monitor-source]
  (let [{:keys [^Shell shell ^Browser browser]} (ensure-preview-shell!)]
    (when (and shell (not (.isDisposed shell))
               browser (not (.isDisposed browser)))
      (let [html           (search/card-html result)
            display-bounds (.getBounds (.getMonitor monitor-source))
            x              (min display-x
                                (- (+ (.x display-bounds) (.width display-bounds))
                                   preview-width 10))]
        ;; Show at initial small height while content loads
        (.setBounds shell x below-top-y preview-width preview-initial-height)
        (.setVisible shell true)
        ;; Set content and resize on load completion (Snippet 372)
        (.addProgressListener browser
                              (proxy [ProgressAdapter] []
                                (completed [_event]
                                  (.removeProgressListener browser this)
                                  (let [height-d (.evaluate browser
                                                            "return document.body.scrollHeight;")
                                        height   (int (min preview-max-height
                                                           (+ 4 (if (number? height-d)
                                                                  (long height-d)
                                                                  preview-initial-height))))
                                        y (if (> (+ below-top-y height)
                                                 (+ (.y display-bounds)
                                                    (.height display-bounds)))
                                            (max (.y display-bounds)
                                                 (- above-bottom-y height))
                                            below-top-y)]
                                    (.setBounds shell x y preview-width height)
                                    (.layout shell true)))))
        (.setText browser html)
        (swap! preview-state assoc :current-uuid uuid)))))

(defn hide-preview!
  "Hide the preview popup."
  []
  (when-let [{:keys [^Shell shell]} @preview-state]
    (when (and shell (not (.isDisposed shell)))
      (.setVisible shell false)
      (swap! preview-state assoc :current-uuid nil))))

;; ---------------------------------------------------------------------------
;; Scheduling (shared)
;; ---------------------------------------------------------------------------

(defn- cancel-pending-preview!
  "Cancel any scheduled preview."
  []
  (when-let [{:keys [^ScheduledFuture hover-future]} @preview-state]
    (when hover-future (.cancel hover-future false))))

;; ---------------------------------------------------------------------------
;; StyledText triggers (edit mode)
;; ---------------------------------------------------------------------------

(defn- link-dest-at-offset
  "Return the link span at offset, or nil."
  [^StyledText st offset]
  (let [link-at (requiring-resolve 'llm-memory.ui.markdown-editor/link-at-offset)]
    (link-at st offset)))

(defn- ref-key
  "Stable dedupe key for a normalized ref."
  [ref]
  (when ref
    (str (:root-uri ref) "|" (:file-path ref) "#" (or (:slug ref) ""))))

(defn- schedule-editor-preview!
  "Schedule a delayed preview for a resolved file ref at a given offset."
  [^StyledText st offset ref delay-ms]
  (let [{:keys [^ScheduledExecutorService executor]} (ensure-preview-shell!)]
    (cancel-pending-preview!)
    (let [new-future
          (.schedule executor
                     ^Runnable
                     (fn []
                       (try
                         (when-let [result (resolve-file-preview ref)]
                           (async-exec!
                            #(when (not (.isDisposed st))
                               (let [loc     (.getLocationAtOffset st offset)
                                     line-h  (.getLineHeight st)
                                     ;; popup-top when placed BELOW the row (4 px below row bottom)
                                     below-pt (.toDisplay st (.x loc)
                                                          (+ (.y loc) line-h 4))
                                     ;; popup-bottom when placed ABOVE the row — 2 rows of
                                     ;; breathing space above the row top so the user can
                                     ;; still see/type on the current line.
                                     above-pt (.toDisplay st (.x loc)
                                                          (- (.y loc) (* 2 line-h) 4))]
                                 (show-preview-at! (ref-key ref) result
                                                   (.x below-pt)
                                                   (.y below-pt)
                                                   (.y above-pt)
                                                   (.getShell st))))))
                         (catch Throwable t
                           (log/warn t "Editor link preview failed"))))
                     (long delay-ms)
                     TimeUnit/MILLISECONDS)]
      (swap! preview-state assoc :hover-future new-future))))

(defn install-link-preview!
  "Install hover and caret-based link preview on a StyledText (edit mode).
  `rel-path` and `root-uri` identify the file being edited (needed to
  resolve relative .md link destinations). Either may be nil — then only
  wiki: and winze:open-file? destinations will produce previews.
  Must be called on the UI thread after the widget is created.

  Single-arg arity is retained for backward compatibility with the
  installed build (which passes no file context); it disables relative-
  path previews but still handles wiki: and winze:open-file?."
  ([^StyledText st] (install-link-preview! st nil nil))
  ([^StyledText st rel-path root-uri]
   (ensure-preview-shell!)
   (let [mouse-in (:mouse-in-preview? @preview-state)
         ctx      {:rel-path rel-path :root-uri root-uri}
         span->ref (fn [span] (parse-link-dest (:dest span) ctx))]

    ;; Mouse hover: schedule preview (only when MOD1 is NOT held)
     (.addMouseMoveListener st
                            (reify MouseMoveListener
                              (mouseMove [_ e]
                                (let [mod1? (not= 0 (bit-and (.stateMask e) SWT/MOD1))]
                                  (if mod1?
                                    (do (cancel-pending-preview!)
                                        (when (preview-open?) (hide-preview!)))
                                    (try
                                      (let [offset (.getOffsetAtPoint st (Point. (.x e) (.y e)))
                                            span   (link-dest-at-offset st offset)
                                            ref    (when span (span->ref span))]
                                        (cond
                                          ref
                                          (let [k (ref-key ref)]
                                            (when (not= k (:current-uuid @preview-state))
                                              (schedule-editor-preview! st offset ref hover-delay-ms)))

                                          span
                                          (do (cancel-pending-preview!)
                                              (when (preview-open?) (hide-preview!)))

                                          :else
                                          (do (cancel-pending-preview!)
                                              (when (and (preview-open?) (not @mouse-in))
                                                (hide-preview!)))))
                                      (catch org.eclipse.swt.SWTException _
                                        (cancel-pending-preview!)
                                        (when (and (preview-open?) (not @mouse-in))
                                          (hide-preview!)))))))))

    ;; Caret listener: show preview when caret enters a link
     (.addCaretListener st
                        (proxy [CaretListener] []
                          (caretMoved [^CaretEvent e]
                            (let [offset (.-caretOffset e)
                                  span   (link-dest-at-offset st offset)
                                  ref    (when span (span->ref span))]
                              (if ref
                                (let [k (ref-key ref)]
                                  (when (not= k (:current-uuid @preview-state))
                                    (schedule-editor-preview! st offset ref caret-delay-ms)))
                                (do (cancel-pending-preview!)
                                    (when (preview-open?)
                                      (hide-preview!))))))))

    ;; Modify listener: dismiss on any edit
     (.addModifyListener st
                         (reify ModifyListener
                           (modifyText [_ _event]
                             (cancel-pending-preview!)
                             (when (preview-open?)
                               (hide-preview!)))))

    ;; Dispose: clean up editor-specific state (shared shell survives)
     (.addDisposeListener st
                          (reify DisposeListener
                            (widgetDisposed [_ _e]
                              (cancel-pending-preview!)
                              (when (preview-open?) (hide-preview!))))))))

;; ---------------------------------------------------------------------------
;; Browser triggers (view mode)
;; ---------------------------------------------------------------------------

(def ^:private hover-js
  "JavaScript injected into view-mode pages to detect mouse hover over
  local-file links (wiki: or winze:open-file?). Calls wpreviewHover/
  wpreviewLeave BrowserFunctions with the href plus the link's
  viewport-relative bounding box; Clojure translates to display coords."
  "document.addEventListener('mouseover', function(e) {
     var a = e.target.closest('a[href^=\"wiki:\"], a[href^=\"winze:open-file?\"]');
     if (a) {
       var r = a.getBoundingClientRect();
       wpreviewHover(a.getAttribute('href'),
                     Math.round(r.left),
                     Math.round(r.top),
                     Math.round(r.bottom));
     }
   });
   document.addEventListener('mouseout', function(e) {
     var a = e.target.closest('a[href^=\"wiki:\"], a[href^=\"winze:open-file?\"]');
     if (a) { wpreviewLeave(); }
   });")

(defn install-browser-link-preview!
  "Install link preview on hover for a Browser widget (view mode).
  Uses BrowserFunctions (JavaScript→Java callbacks) for reliable
  communication across all SWT WebKit backends.
  Must be called on the UI thread."
  [^Browser view-browser]
  (ensure-preview-shell!)
  (let [mouse-in (:mouse-in-preview? @preview-state)

        ;; BrowserFunction: called from JS on hover enter.
        ;; `href` is the full rewritten href — already absolute in the
        ;; sense that wiki: carries root-name, and winze:open-file? URLs
        ;; carry both root-uri and rel-path. No file-context needed.
        hover-fn
        (proxy [BrowserFunction] [view-browser "wpreviewHover"]
          (function [args]
            (let [href     (str (aget args 0))
                  x        (int (double (aget args 1)))
                  link-top (int (double (aget args 2)))
                  link-bot (int (double (aget args 3)))
                  ref      (parse-link-dest href {})
                  k        (ref-key ref)]
              (when (and ref (not= k (:current-uuid @preview-state)))
                (cancel-pending-preview!)
                (let [{:keys [^ScheduledExecutorService executor]}
                      (ensure-preview-shell!)
                      new-future
                      (.schedule executor
                                 ^Runnable
                                 (fn []
                                   (try
                                     (when-let [result (resolve-file-preview ref)]
                                       (async-exec!
                                        #(when (not (.isDisposed view-browser))
                                           ;; popup-top when below the link: link-bottom + 4 px gap
                                           (let [below-pt (.toDisplay view-browser x (+ link-bot 4))
                                                 ;; popup-bottom when above the link: link-top - 4 px gap
                                                 above-pt (.toDisplay view-browser x (- link-top 4))]
                                             (show-preview-at! k result
                                                               (.x below-pt)
                                                               (.y below-pt)
                                                               (.y above-pt)
                                                               (.getShell view-browser))))))
                                     (catch Throwable t
                                       (log/warn t "Browser link preview failed"))))
                                 (long hover-delay-ms)
                                 TimeUnit/MILLISECONDS)]
                  (swap! preview-state assoc :hover-future new-future))))
            nil))

        ;; BrowserFunction: called from JS on hover leave
        leave-fn
        (proxy [BrowserFunction] [view-browser "wpreviewLeave"]
          (function [_args]
            (cancel-pending-preview!)
            (when (and (preview-open?) (not @mouse-in))
              (async-exec! hide-preview!))
            nil))]

    ;; Inject hover JS after every page load
    (.addProgressListener view-browser
                          (proxy [ProgressAdapter] []
                            (completed [_event]
                              (try (.execute view-browser hover-js)
                                   (catch Throwable t
                                     (log/debug t "Failed to inject hover JS"))))))

    ;; Dismiss on navigation
    (.addLocationListener view-browser
                          (proxy [org.eclipse.swt.browser.LocationAdapter] []
                            (changing [_event]
                              (cancel-pending-preview!)
                              (when (preview-open?) (hide-preview!)))))

    ;; Dispose BrowserFunctions when Browser is disposed
    (.addDisposeListener view-browser
                         (reify DisposeListener
                           (widgetDisposed [_ _e]
                             (.dispose hover-fn)
                             (.dispose leave-fn))))))

(defn close-link-preview!
  "Close the link preview if open. Registered as a command for Esc binding."
  []
  (when (preview-open?)
    (hide-preview!)))
