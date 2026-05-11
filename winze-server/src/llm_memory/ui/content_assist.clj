(ns llm-memory.ui.content-assist
  "Content assist popup — floating result-card browser for link target selection.

  Two modes:
    :wiki   — semantic search over indexed headings/files (default)
    :google — web search when user types http:// or https://

  Triggered by:
    - `[text](` in the editor (semantic prepopulation from link text)
    - Mod1+K (Insert Link command)
    - `[[...]]` (wiki-draft trigger)

  Returns a result map on selection or nil on dismiss:
    Wiki:   {:type :wiki :uuid str :file-path str :slug str :title str}
    Google: {:type :external :url str :title str}"
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [llm-memory.ui.keybindings :as keybindings]
   [llm-memory.ui.resources :as resources :refer [element]]
   [llm-memory.ui.search :as search]
   [ui.events :as e]
   [ui.gridlayout :as grid :refer [grid-layout]]
   [ui.SWT :refer [async-exec! child-of composite id! on shell styled-text table
                   table-column |]])
  (:import
   [org.eclipse.swt SWT]
   [org.eclipse.swt.browser Browser ProgressAdapter]
   [org.eclipse.swt.custom StyledText]
   [org.eclipse.swt.graphics Color Font GC Image Point]
   [org.eclipse.swt.layout FillLayout]
   [org.eclipse.swt.widgets Composite Display Listener Shell Table TableItem]
   [java.util.concurrent
    Executors ScheduledExecutorService ScheduledFuture TimeUnit]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private popup-margin 40)
(def ^:private popup-min-width 500)
(def ^:private popup-max-height 800)
(def ^:private max-visible-rows 8)
(def ^:private row-height 80)
(def ^:private max-row-height 400)

(defn- popup-width
  "Compute popup width: main window width minus margin, clamped to min."
  []
  (let [main-shell (element :main-window)]
    (if (and main-shell (not (.isDisposed main-shell)))
      (max popup-min-width (- (.width (.getBounds main-shell)) popup-margin))
      popup-min-width)))
(def ^:private wiki-debounce-ms 150)
(def ^:private google-debounce-ms 500)

;; ---------------------------------------------------------------------------
;; Popup state
;; ---------------------------------------------------------------------------

(defonce ^:private popup-state (atom nil))
;; {:shell Shell :filter-text Text :table Table :results [map...]
;;  :selected int :mode :wiki|:google :on-select fn :on-cancel fn
;;  :search-future ScheduledFuture :executor ScheduledExecutorService}

(defonce ^:private offscreen-state (atom nil))
;; {:shell Shell :browser Browser} — persistent across popup openings

(defonce ^:private scrollbar-gutter (atom nil))
;; Cached scrollbar gutter width in pixels (measured once per session)

(defonce ^:private render-generation (atom 0))
;; Incremented on each new search; render loops abort when they see a stale value

(defn popup-open?
  "Return true if the content assist popup is currently visible."
  []
  (when-let [{:keys [shell]} @popup-state]
    (and shell (not (.isDisposed shell)) (.isVisible shell))))

;; ---------------------------------------------------------------------------
;; Offscreen Browser (persistent, shared across popup openings)
;; ---------------------------------------------------------------------------

(defn- offscreen-position
  "Return [x y] coordinates guaranteed to be beyond all connected monitors."
  []
  (let [monitors (.getMonitors (Display/getDefault))
        max-x    (reduce (fn [mx m]
                           (let [b (.getBounds m)]
                             (max mx (+ (.x b) (.width b)))))
                         0 monitors)]
    [(+ max-x 100) 0]))

(defn- ensure-offscreen-browser!
  "Create the shared offscreen Shell+Browser if not already alive.
  The Shell is positioned beyond all monitors so it is never visible.
  A disabled Composite wraps the Browser to prevent focus via alt-tab.
  Returns the Browser."
  []
  (let [s @offscreen-state]
    (if (and s (:browser s) (not (.isDisposed ^Browser (:browser s))))
      (do
        ;; Re-show if hidden by a prior popup close
        (when-let [{:keys [^Shell shell]} s]
          (when (and shell (not (.isDisposed shell)) (not (.isVisible shell)))
            (.setVisible shell true)))
        (:browser s))
      (let [[ox oy] (offscreen-position)
            ;; Raw Shell. + Composite. — this is the offscreen Browser used
            ;; to rasterise result cards into Images for the popup table.
            ;; The shell is managed as a singleton via @offscreen-state and
            ;; shown with .setVisible (not .open) to avoid stealing focus.
            ;; CDT's `shell` + `util/show` don't expose the no-activate path;
            ;; converting requires a non-activating variant of `show` plus
            ;; lifting the singleton lifecycle out of `get-or-create-…`.
            sh      (Shell. (Display/getDefault) SWT/NO_TRIM)
            _       (.setLayout sh (FillLayout.))
            ;; Disabled Composite: setEnabled(false) propagates to all children,
            ;; preventing the Shell from ever receiving input focus.
            ;; Raw Composite. because the enclosing Shell is raw.
            guard   (doto (Composite. sh SWT/NONE)
                      (.setLayout (FillLayout.))
                      (.setEnabled false))
            brow    (Browser. guard SWT/WEBKIT)
            new-s   {:shell sh :browser brow}]
        ;; Initial size: popup-width + gutter estimate by max-row-height.
        ;; Starting at max-row-height means every subsequent resize is a
        ;; shrink (or equal); shrinks do not expose unpainted pixels.
        ;; Growing from a smaller size causes .print to capture the
        ;; newly-exposed region before WebKit has painted it — visible as
        ;; a white band on the first popup of a session.
        (.setBounds sh ox oy (+ (popup-width) 20) max-row-height)
        ;; setVisible (not .open) — show without activating, so it never
        ;; steals focus from the content-assist popup Shell.
        (.setVisible sh true)
        (reset! offscreen-state new-s)
        brow))))

(defn- hide-offscreen!
  "Hide the offscreen Shell (keeps it alive for reuse)."
  []
  (when-let [{:keys [^Shell shell]} @offscreen-state]
    (when (and shell (not (.isDisposed shell)))
      (.setVisible shell false))))

(defn dispose-offscreen!
  "Dispose the offscreen Shell. Call on application shutdown."
  []
  (when-let [{:keys [^Shell shell]} @offscreen-state]
    (when (and shell (not (.isDisposed shell)))
      (.dispose shell))
    (reset! offscreen-state nil)))

;; ---------------------------------------------------------------------------
;; Screenshot pipeline
;; ---------------------------------------------------------------------------

(defn- measure-scrollbar-gutter!
  "Measure the scrollbar gutter width by comparing the Browser widget
  width to the HTML body's rendered scroll width. Caches the result.
  Resizes the offscreen Shell so the body renders at exactly popup-width.
  Must be called on the UI thread after the Browser has finished rendering."
  [^Browser browser]
  (when-not @scrollbar-gutter
    (let [widget-w (.width (.getBounds browser))
          body-w-d (.evaluate browser "return document.body.scrollWidth;")
          body-w   (if (number? body-w-d) (long body-w-d) widget-w)
          gutter   (max 0 (- widget-w body-w))]
      (when-let [{:keys [^Shell shell]} @offscreen-state]
        (when (not (.isDisposed shell))
          ;; Keep at max-row-height to avoid shrinking before the
          ;; per-iteration content-h resize; otherwise the subsequent
          ;; grow captures an unpainted viewport region as a white band.
          (.setSize shell (+ (popup-width) gutter) max-row-height)))
      (reset! scrollbar-gutter gutter)))
  @scrollbar-gutter)

(defn- screenshot-browser
  "Take a screenshot of the offscreen Browser, trimming the scrollbar gutter
  horizontally and to content-h vertically (defaulting to full height).
  Returns a new Image; caller is responsible for disposing it."
  ([^Browser browser]
   (screenshot-browser browser nil))
  ([^Browser browser content-h]
   (let [bounds  (.getBounds browser)
         gutter  (or @scrollbar-gutter 0)
         full-w  (.width bounds)
         full-h  (.height bounds)
         trim-w  (max 1 (- full-w gutter))
         trim-h  (max 1 (if content-h (min (int content-h) full-h) full-h))
         display (.getDisplay browser)
         src-img (Image. display full-w full-h)
         dst-img (Image. display trim-w trim-h)]
     (try
       (let [gc (GC. src-img)]
         (try (.print browser gc) (finally (.dispose gc))))
       (let [gc (GC. dst-img)]
         (try
           (.drawImage gc src-img 0 0 trim-w trim-h 0 0 trim-w trim-h)
           (finally (.dispose gc))))
       dst-img
       (finally (.dispose src-img))))))

;; ---------------------------------------------------------------------------
;; Screenshot render loop
;; ---------------------------------------------------------------------------

(defn- dispose-table-images!
  "Dispose all Images currently held by a Table's items (stored via .setData)."
  [^Table table]
  (doseq [item (.getItems table)]
    (when-let [img (.getData item "image")]
      (when (instance? Image img)
        (when-not (.isDisposed ^Image img)
          (.dispose ^Image img))))))

(declare resize-popup!)

(defn- render-row-images!
  "Render result cards as Images via the offscreen Browser.
  Appends each Image to the Table progressively as it becomes available.
  Each card is screenshotted at its natural content height (up to max-row-height).
  Aborts if `render-generation` changes (a newer search superseded this one)."
  [^Browser browser results ^Table table gen]
  (let [idx   (atom 0)
        total (count results)]
    (letfn [(render-next []
              (cond
                (not= gen @render-generation) nil   ;; stale — abort
                (>= @idx total)               nil   ;; done
                :else
                (let [html (search/card-html (nth results @idx))]
                  (.addProgressListener browser
                                        (proxy [ProgressAdapter] []
                                          (completed [_event]
                                            (.removeProgressListener browser this)
                                            (when (and (= gen @render-generation)
                                                       (not (.isDisposed table))
                                                       (not (.isDisposed browser)))
                                              ;; Measure gutter on first render
                                              (when (zero? @idx)
                                                (measure-scrollbar-gutter! browser))
                                              ;; Measure content height, then trim the screenshot
                                              ;; to it. We do NOT resize the Shell per iteration —
                                              ;; resizing mid-render causes WebKit paint races
                                              ;; (blank bands, duplicated content) because .print
                                              ;; can capture the viewport before WebKit has
                                              ;; repainted for the new size. Keeping the Shell
                                              ;; at max-row-height and trimming via drawImage
                                              ;; sidesteps the race entirely.
                                              (let [h-d       (.evaluate browser "return document.body.scrollHeight;")
                                                    content-h (int (min max-row-height
                                                                        (if (number? h-d)
                                                                          (long h-d)
                                                                          row-height)))
                                                    img       (screenshot-browser browser content-h)
                                                    i         @idx]
                                                ;; Raw TableItem. — this fires from a
                                                ;; ProgressListener callback, outside any
                                                ;; CDT init tree.  `(table-item …)` returns
                                                ;; an init fn expecting (props, parent);
                                                ;; slotting it in here would require a
                                                ;; parallel init-function dispatch path.
                                                (let [item (TableItem. table SWT/NONE)]
                                                  (.setData item "image" img)
                                                  (.setData item "result-idx" (int i)))
                                                (when (zero? i) (.select table 0))
                                                (swap! idx inc)
                                                (resize-popup!)
                                                (async-exec! render-next))))))
                  (.setText browser html))))]
      (render-next))))

(defn- clamp-to-screen
  "Clamp shell position + size so it stays fully on the monitor that
  contains most of the shell. Adjusts position first, then shrinks if needed."
  [^Shell shell w h]
  (let [monitor (.getMonitor shell)
        ca      (.getClientArea monitor)
        max-w   (.width ca)
        max-h   (.height ca)
        bounds  (.getBounds shell)
        x       (.x bounds)
        y       (.y bounds)
        ;; Clamp width/height to monitor
        cw      (min w max-w)
        ch      (min h max-h)
        ;; Shift left/up if extending past right/bottom edge
        cx      (if (> (+ x cw) (+ (.x ca) max-w))
                  (max (.x ca) (- (+ (.x ca) max-w) cw))
                  x)
        cy      (if (> (+ y ch) (+ (.y ca) max-h))
                  (max (.y ca) (- (+ (.y ca) max-h) ch))
                  y)]
    (.setBounds shell cx cy cw ch)))

(defn- resize-popup!
  "Resize the popup Shell to fit the filter field + current Table content.
  Capped at popup-max-height, clamped to screen bounds."
  []
  (when-let [{:keys [^Shell shell ^Table table ^StyledText filter-text]} @popup-state]
    (when (and shell (not (.isDisposed shell)))
      (let [filter-h (+ (.y (.computeSize filter-text SWT/DEFAULT SWT/DEFAULT)) 8)
            table-h  (reduce (fn [h item]
                               (if-let [img (.getData item "image")]
                                 (if (instance? Image img)
                                   (+ h (.height (.getBounds ^Image img)))
                                   h)
                                 h))
                             0 (.getItems table))
            total    (min popup-max-height (+ filter-h table-h 20))]
        (clamp-to-screen shell (popup-width) (int total))))))

;; ---------------------------------------------------------------------------
;; Wiki search
;; ---------------------------------------------------------------------------

(defn- get-store
  "Resolve and call server/store lazily to avoid circular requires."
  []
  ((requiring-resolve 'llm-memory.server.main/store)))

(defn- search-store
  "Resolve and call core/search lazily."
  [s query-text opts]
  ((requiring-resolve 'llm-memory.core/search) s query-text opts))

(defn- query-store
  "Resolve and call store/query lazily."
  ([s q] ((requiring-resolve 'llm-memory.store.protocol/query) s q))
  ([s q params] ((requiring-resolve 'llm-memory.store.protocol/query) s q params)))

(defn- wiki-search
  "Search for wiki link targets. Returns a vector of result maps suitable for
  card rendering, enriched with :wiki/id (= :file/id) for link insertion."
  [query-text]
  (when-let [s (get-store)]
    (let [results (search-store s query-text {:top max-visible-rows :dedupe true})]
      (mapv (fn [r] (assoc r :wiki/id (:file/id r))) results))))

(defn- first-section-text
  "Return the :chunk/text of the lowest-section chunk for a file eid,
  or nil if the file has no indexed chunks.

  Uses pull-entity rather than datalog-binding :chunk/text, mirroring
  `llm-memory.core/first-chunk-text` and
  `llm-memory.ui.link-preview/resolve-file-preview`. See
  `Plans/complete/hover-preview-regression/` for why."
  [s feid]
  (let [pull-fn    (requiring-resolve 'llm-memory.store.protocol/pull-entity)
        chunk-eids (query-store s
                                '[:find [?c ...]
                                  :in $ ?f
                                  :where [?c :chunk/file ?f]]
                                {:f feid})
        chunks     (seq (map #(pull-fn s %) chunk-eids))]
    (when chunks
      (:chunk/text (apply min-key #(or (:chunk/section %) 0) chunks)))))

(defn- title-search
  "Search by page title substring match. Used when user types in the search field.

  Matches each hit to its first-section chunk text so the content-assist
  cards show real body content, not a bare H1. Falls back to
  `(str \"# \" title)` only when a file has no chunks indexed."
  [query-text]
  (when-let [s (get-store)]
    (let [q-lower (str/lower-case (str/trim query-text))
          pull-fn (requiring-resolve 'llm-memory.store.protocol/pull-entity)
          files   (query-store s
                               '[:find ?feid ?fid ?path ?title ?root-uri
                                 :where
                                 [?f :file/id ?fid]
                                 [?f :file/path ?path]
                                 [?f :file/title ?title]
                                 [?f :file/root ?r]
                                 [?r :root/uri ?root-uri]
                                 [(identity ?f) ?feid]])]
      (->> files
           (filter (fn [[_feid _fid _path title _root]]
                     (str/includes? (str/lower-case title) q-lower)))
           (take max-visible-rows)
           (mapv (fn [[feid fid path title root-uri]]
                   (let [file-ent (pull-fn s feid)
                         body     (first-section-text s feid)]
                     (merge (select-keys file-ent
                                         [:file/status :file/type :file/group])
                            {:file/id    fid
                             :file/path  path
                             :file/title title
                             :root/uri   root-uri
                             :chunk/text (or body (str "# " title))
                             :wiki/id    fid}))))))))

;; ---------------------------------------------------------------------------
;; Mode detection
;; ---------------------------------------------------------------------------

(defn- detect-mode
  "Determine search mode from the query text."
  [text]
  (if (or (str/starts-with? text "http://")
          (str/starts-with? text "https://"))
    :google
    :wiki))

;; ---------------------------------------------------------------------------
;; Debounced search
;; ---------------------------------------------------------------------------

(defn- schedule-search!
  "Schedule a debounced search. Cancels any pending search."
  [query-text]
  (when-let [{:keys [^ScheduledExecutorService executor
                     ^ScheduledFuture search-future
                     table shell]} @popup-state]
    (when search-future (.cancel search-future false))
    (let [mode      (detect-mode query-text)
          delay-ms  (if (= mode :google) google-debounce-ms wiki-debounce-ms)
          new-future
          (.schedule executor
                     ^Runnable
                     (fn []
                       (try
                         (let [results (case mode
                                         :wiki (if (str/blank? query-text)
                                                 []
                                                 ;; Try title search first for typed queries,
                                                 ;; fall back to semantic if no matches
                                                 (let [title-results (title-search query-text)]
                                                   (if (seq title-results)
                                                     title-results
                                                     (wiki-search query-text))))
                                         :google [])] ;; Google mode placeholder
                           (async-exec!
                            (fn []
                              (when (and (not (.isDisposed shell))
                                         (.isVisible shell))
                                (when-let [{:keys [table]} @popup-state]
                                  (when (and table (not (.isDisposed table)))
                                    (let [gen (swap! render-generation inc)]
                                      (swap! popup-state assoc :results (vec results) :selected 0)
                                      (dispose-table-images! table)
                                      (.removeAll table)
                                      (render-row-images! (ensure-offscreen-browser!)
                                                          (vec results) table gen))))))))
                         (catch Throwable t
                           (log/warn t "Content assist search failed"))))
                     (long delay-ms)
                     TimeUnit/MILLISECONDS)]
      (swap! popup-state assoc
             :search-future new-future
             :mode mode))))

;; ---------------------------------------------------------------------------
;; Selection handling
;; ---------------------------------------------------------------------------

(defn- selected-result
  "Return the currently selected result map, or nil."
  []
  (when-let [{:keys [results selected]} @popup-state]
    (get results selected)))

(defn- select-result!
  "Accept the selected result and close the popup."
  []
  (when-let [{:keys [shell on-select]} @popup-state]
    (let [result (selected-result)]
      (.close shell)
      (when (and on-select result)
        (let [{:keys [wiki/id file/path chunk/slug file/title]} result]
          (on-select {:type      :wiki
                      :uuid      id
                      :file-path path
                      :slug      slug
                      :title     (or title path)}))))))

(defn- cancel!
  "Dismiss the popup without selecting."
  []
  (when-let [{:keys [shell on-cancel]} @popup-state]
    (.close shell)
    (when on-cancel (on-cancel))))

;; ---------------------------------------------------------------------------
;; Navigation
;; ---------------------------------------------------------------------------

(defn- move-selection!
  "Move the selection up or down."
  [direction]
  (when-let [{:keys [selected table]} @popup-state]
    (when (and table (not (.isDisposed table)))
      (let [n       (.getItemCount table)
            new-idx (case direction
                      :up   (max 0 (dec selected))
                      :down (min (dec n) (inc selected)))]
        (when (and (pos? n) (not= selected new-idx))
          (swap! popup-state assoc :selected new-idx)
          (.select table new-idx)
          (.showSelection table)
          (.redraw table))))))

;; ---------------------------------------------------------------------------
;; Build the popup shell
;; ---------------------------------------------------------------------------

(defn- position-below-caret
  "Calculate popup position below the caret in the given StyledText."
  [^StyledText st]
  (let [caret-offset (.getCaretOffset st)
        loc          (.getLocationAtOffset st caret-offset)
        line-h       (.getLineHeight st)
        ;; Convert to display coordinates
        display-pt   (.toDisplay st (.x loc) (+ (.y loc) line-h))]
    (Point. (.x display-pt) (.y display-pt))))

(defn open-content-assist!
  "Open the content assist popup below the caret.

  Options:
    :styled-text — the editor StyledText widget
    :seed-text   — initial search query (e.g. link text from [text]( )
    :on-select   — callback (fn [result-map] ...) when user selects
    :on-cancel   — callback (fn [] ...) on dismiss
    :mode        — :wiki-create for [[...]] trigger (adds 'Create new page' option)

  Must be called on the UI thread."
  [{^StyledText editor-st :styled-text
    :keys                  [seed-text on-select on-cancel mode]
    :or                    {seed-text "" mode :wiki}}]

  ;; Close any existing popup
  (when (popup-open?)
    (cancel!))

  (let [pos      (position-below-caret editor-st)
        bg       ^Color @resources/color-mine-shaft
        fg       ^Color @resources/color-crystal-white
        sel-bg   ^Color @resources/color-royal-purple
        sel-fg   ^Color @resources/color-bedrock
        font     ^Font @resources/body-font
        pw       (popup-width)
        executor (Executors/newSingleThreadScheduledExecutor)
        props    (atom {})

        ;; Build the widget tree via child-of + CDT init functions
        _
        (child-of (element :main-window) props
                  (shell (| SWT/TOOL SWT/ON_TOP SWT/NO_TRIM)
                         (id! :ca/shell)
                         :background bg
                         (grid-layout :num-columns    1
                                      :margin-width   4
                                      :margin-height  4
                                      :vertical-spacing 2)

                         (on e/shell-deactivated [props parent event]
                             (async-exec! #(when (popup-open?) (cancel!))))

                         (on e/widget-disposed [props parent event]
                             (swap! render-generation inc)
                             (.shutdown executor)
                             (keybindings/clear-active-popup!)
                             (hide-offscreen!)
                             (reset! popup-state nil))

                         ;; Search field.
                         ;; Do NOT setText here — it fires modifyText before
                         ;; popup-state exists. Pre-populate AFTER state init.
                         (styled-text SWT/SINGLE
                                      (id! :ca/filter)
                                      :background           bg
                                      :foreground           fg
                                      :selection-background sel-bg
                                      :selection-foreground sel-fg
                                      :font                 font
                                      (grid/grid-data :horizontal-alignment        SWT/FILL
                                                      :grab-excess-horizontal-space true
                                                      :height-hint                 24)

                                      (fn [_props parent]
                                        (.setData parent "scope" :content-assist))

                                      (on e/key-pressed [props parent event]
                                          (let [kc (.keyCode event)]
                                            (cond
                                              (= kc SWT/ARROW_DOWN)
                                              (do (move-selection! :down)
                                                  (set! (.-doit event) false))

                                              (= kc SWT/ARROW_UP)
                                              (do (move-selection! :up)
                                                  (set! (.-doit event) false))

                                              (= kc (int SWT/CR))
                                              (do (select-result!)
                                                  (set! (.-doit event) false))

                                              (= kc (int SWT/ESC))
                                              (do (cancel!)
                                                  (set! (.-doit event) false)))))

                                      (on e/modify-text [props parent event]
                                          (schedule-search! (.getText parent))))

                         ;; Results area
                         (composite
                          (id! :ca/results-area)
                          :background bg
                          (grid-layout :num-columns     1
                                       :margin-width    0
                                       :margin-height   0
                                       :vertical-spacing 0)
                          (grid/grab-both)

                          ;; Results Table — owner-draw, single column, image rows
                          (table (| SWT/SINGLE SWT/FULL_SELECTION SWT/NO_BACKGROUND)
                                 (id! :ca/table)
                                 :background bg
                                 :header-visible false
                                 :lines-visible  false
                                 (grid/grab-both)

                                 (table-column
                                  :width (- pw 8))

                                 (on e/widget-selected [props parent event]
                                     (let [items (.getSelection parent)]
                                       (when (seq items)
                                         (when-let [idx (.getData (first items) "result-idx")]
                                           (swap! popup-state assoc :selected (int idx))
                                           (select-result!)))))

                                 (on e/widget-disposed [props parent event]
                                     (dispose-table-images! parent))))))

        sh         (:ca/shell @props)
        filter-txt (:ca/filter @props)
        tbl        (:ca/table @props)]

    ;; Owner-draw listeners for variable-height rows.
    ;; These use untyped SWT event codes (MeasureItem, EraseItem, PaintItem)
    ;; which are not listener interfaces — they require .addListener.
    ;; NOTE: Do NOT call `.redraw tbl` inside this handler. Doing so
    ;; schedules a paint that fires MeasureItem again on the next cycle —
    ;; a self-triggering storm. On macOS Cocoa the storm eventually
    ;; produced a stale repaint of the first row ~1s after the initial
    ;; render, replacing it with a duplicate of another row's image.
    (.addListener tbl SWT/MeasureItem
                  (reify Listener
                    (handleEvent [_ event]
                      (when-let [item (.item event)]
                        (when-let [img (.getData item "image")]
                          (when (instance? Image img)
                            (let [bounds (.getBounds ^Image img)]
                              (set! (.-width event) (.width bounds))
                              (set! (.-height event) (.height bounds)))))))))
    (.addListener tbl SWT/EraseItem
                  (reify Listener
                    (handleEvent [_ event]
                      ;; Suppress both default foreground (text) and background
                      ;; (selection highlight) drawing — we paint everything in PaintItem.
                      ;; Without clearing BACKGROUND, the first selected row shows
                      ;; the OS selection color behind uncovered pixels.
                      (set! (.-detail event)
                            (bit-and (.-detail event)
                                     (bit-not (bit-or SWT/FOREGROUND SWT/BACKGROUND)))))))
    (.addListener tbl SWT/PaintItem
                  (reify Listener
                    (handleEvent [_ event]
                      (when-let [item (.item event)]
                        ;; Fill entire row with the dark background first.
                        ;; On macOS Cocoa, the first selected row can show
                        ;; COLOR_WIDGET_BACKGROUND from a native paint path
                        ;; that bypasses EraseItem. This fill covers it.
                        (let [gc (.-gc event)]
                          (.setBackground gc ^Color @resources/color-obsidian)
                          (.setForeground gc ^Color @resources/color-obsidian)
                          (.fillRectangle gc
                                          (.-x event) (.-y event)
                                          (.-width event) (.-height event)))
                        (when-let [img (.getData item "image")]
                          (when (and (instance? Image img)
                                     (not (.isDisposed ^Image img)))
                            (.drawImage (.-gc event) ^Image img
                                        (.-x event) (.-y event))))))))
    ;; Selection accent bar — separate PaintItem listener so the bar
    ;; composites on top of the card image on macOS Cocoa.
    ;; Uses popup-state :selected rather than SWT/SELECTED because
    ;; macOS omits that bit when the Table is unfocused.
    (.addListener tbl SWT/PaintItem
                  (reify Listener
                    (handleEvent [_ event]
                      (when-let [item (.item event)]
                        (when-let [idx (.getData item "result-idx")]
                          (when (= (int idx) (:selected @popup-state))
                            (let [gc     (.-gc event)
                                  old-bg (.getBackground gc)]
                              (.setBackground gc ^Color @resources/color-check-green)
                              (.fillRectangle gc
                                              (.-x event) (.-y event)
                                              5 (.-height event))
                              (.setBackground gc old-bg))))))))

    ;; Initialize state
    (reset! popup-state
            {:shell         sh
             :filter-text   filter-txt
             :table         tbl
             :results       []
             :selected      0
             :mode          mode
             :on-select     on-select
             :on-cancel     on-cancel
             :search-future nil
             :executor      executor})

    ;; Pre-populate the filter field now that state exists.
    ;; setText triggers modifyText → schedule-search! automatically.
    (when (seq seed-text)
      (.setText ^StyledText filter-txt seed-text)
      (.setCaretOffset filter-txt (.getCharCount filter-txt)))

    ;; Position and show — start at estimated size, clamped to screen
    (let [initial-h (min popup-max-height (+ 30 24 (* 3 row-height) 12))]
      (.setBounds sh (.x pos) (.y pos) pw (int initial-h))
      (clamp-to-screen sh pw (int initial-h)))
    (.open sh)
    (.setFocus filter-txt)

    ;; Register popup scope
    (keybindings/set-active-popup! :content-assist)

    sh))

(defn close-content-assist!
  "Close the content assist popup if open."
  []
  (when (popup-open?)
    (cancel!)))
