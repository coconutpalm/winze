(ns llm-memory.ui.find-replace
  "Find/replace floating bar — overlays the top of the editor.

  Features:
    - Incremental search with match highlighting
    - Match count display (e.g. '3/17')
    - Navigate with Enter / Shift+Enter / ↑↓ buttons
    - Replace current / Replace All
    - Case-sensitive toggle
    - Esc dismisses, restores focus to editor

  Triggered by:
    - Mod1+F — find only
    - Mod1+H — find and replace"
  (:require
   [clojure.string :as str]
   [llm-memory.ui.keybindings :as keybindings]
   [llm-memory.ui.resources :as resources]
   [llm-memory.ui.theme :as theme]
   [ui.events :as e]
   [ui.gridlayout :as grid :refer [grid-layout]]
   [ui.SWT :refer [button child-of composite defchildren id! label
                   on styled-text with-property |]]
   [hyperfiddle.rcf :refer [tests]])
  (:import
   [org.eclipse.swt SWT]
   [org.eclipse.swt.browser Browser]
   [org.eclipse.swt.custom StyleRange StyledText]
   [org.eclipse.swt.graphics Color]
   [org.eclipse.swt.layout GridData RowLayout]
   [org.eclipse.swt.widgets Shell]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private bar-width 520)

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce ^:private find-state (atom nil))

(defn find-bar-open?
  "Return true if the find bar is currently visible."
  []
  (when-let [{:keys [^Shell shell]} @find-state]
    (and shell (not (.isDisposed shell)) (.isVisible shell))))

(defn find-bar-shell
  "Return the find bar Shell, or nil if not open."
  ^Shell []
  (when-let [{:keys [^Shell shell]} @find-state]
    (when (and shell (not (.isDisposed shell)))
      shell)))

;; ---------------------------------------------------------------------------
;; Search logic (pure)
;; ---------------------------------------------------------------------------

(defn find-matches
  "Find all occurrences of `query` in `text`. Returns [{:start int :length int} ...].
  Case-sensitive when `case-sensitive?` is true."
  [text query case-sensitive?]
  (when (and (seq text) (seq query))
    (let [t (if case-sensitive? text (str/lower-case text))
          q (if case-sensitive? query (str/lower-case query))
          qlen (count q)]
      (loop [from 0, acc []]
        (let [idx (str/index-of t q from)]
          (if idx
            (recur (+ idx 1) (conj acc {:start idx :length qlen}))
            acc))))))

(tests
 "find-matches — case-insensitive by default"
 (find-matches "Hello hello HELLO" "hello" false)
 := [{:start 0 :length 5} {:start 6 :length 5} {:start 12 :length 5}]

 "find-matches — case-sensitive"
 (find-matches "Hello hello HELLO" "hello" true)
 := [{:start 6 :length 5}]

 "find-matches — empty query returns nil"
 (find-matches "some text" "" false) := nil
 (find-matches "" "query" false) := nil
 :rcf)

;; ---------------------------------------------------------------------------
;; Highlight management
;; ---------------------------------------------------------------------------

(defn- clear-find-highlights!
  "Remove all find-related StyleRange objects from the StyledText.
  Reapplies the markdown theme to restore original styling."
  [^StyledText st]
  (when (and st (not (.isDisposed st)))
    (let [apply-theme (requiring-resolve 'llm-memory.ui.markdown-editor/apply-theme!)]
      (apply-theme st (.getText st)))))

(defn- apply-find-highlights!
  "Apply find-match background highlighting on the StyledText, preserving
  existing font, size, and foreground from the markdown theme.
  Reads the current StyleRange at each match position and overlays only
  the background color, so headings keep their heading font size, etc."
  [^StyledText st matches current-idx]
  (when (and st (not (.isDisposed st)) (seq matches))
    (let [^Color hi-bg  @resources/color-royal-purple
          ^Color cur-bg @resources/color-deep-violet]
      (doseq [[i {:keys [start length]}] (map-indexed vector matches)]
        (let [existing (.getStyleRanges st start length)
              bg       (if (= i current-idx) cur-bg hi-bg)]
          (if (and existing (pos? (alength existing)))
            (doseq [^StyleRange sr existing]
              (let [clone (.clone sr)]
                (set! (.-background ^StyleRange clone) bg)
                (.setStyleRange st ^StyleRange clone)))
            (let [sr (StyleRange.)]
              (set! (.-start sr) start)
              (set! (.-length sr) length)
              (set! (.-background sr) bg)
              (.setStyleRange st sr))))))))

;; ---------------------------------------------------------------------------
;; Navigation — StyledText (editor mode)
;; ---------------------------------------------------------------------------

(defn- navigate-to-match!
  "Scroll to and select the match at `idx`."
  [^StyledText st matches idx]
  (when-let [{:keys [start length]} (get matches idx)]
    (.setSelection st start (+ start length))
    (.showSelection st)))

(defn- update-count-label!
  "Update the match count label (e.g. '3/17' or 'No results')."
  [count-label match-count current-idx]
  (when (and count-label (not (.isDisposed count-label)))
    (.setText count-label
              (if (pos? match-count)
                (str (inc current-idx) "/" match-count)
                "No results"))
    (.requestLayout (.getParent count-label))))

;; ---------------------------------------------------------------------------
;; Browser find (viewer mode) — custom mark-based highlighting
;; ---------------------------------------------------------------------------

(defn- escape-js-string
  "Escape a string for embedding in a JavaScript single-quoted string literal."
  [s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "'" "\\'")
      (str/replace "\n" "\\n")
      (str/replace "\r" "")))

(defn- browser-highlight-js
  "Build the JavaScript that finds all text matches in the DOM, wraps
  them in <mark> elements with theme-driven styling, and scrolls to
  the current match.

  Rebuilt on every call so `theme/reload-theme!` propagates — next
  search emits JS with fresh hex. Uses `str` for the hex
  interpolation so the `%s`/`%s`/`%d` placeholders survive for the
  outer `(format …)` to fill with query / case-sensitive / current
  index."
  []
  (str "return (function(q, cs, currentIdx) {
     // Remove previous marks
     document.querySelectorAll('mark.wz-find').forEach(function(m) {
       var parent = m.parentNode;
       parent.replaceChild(document.createTextNode(m.textContent), m);
       parent.normalize();
     });
     if (!q) return 0;

     // Walk text nodes and find matches
     var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
     var matches = [];
     while (walker.nextNode()) {
       var node = walker.currentNode;
       var text = cs ? node.textContent : node.textContent.toLowerCase();
       var query = cs ? q : q.toLowerCase();
       var pos = 0;
       while ((pos = text.indexOf(query, pos)) !== -1) {
         matches.push({node: node, offset: pos, length: query.length});
         pos += query.length;
       }
     }

     // Wrap matches in <mark> elements (reverse order to preserve offsets)
     for (var i = matches.length - 1; i >= 0; i--) {
       var m = matches[i];
       var range = document.createRange();
       range.setStart(m.node, m.offset);
       range.setEnd(m.node, m.offset + m.length);
       var mark = document.createElement('mark');
       mark.className = 'wz-find';
       mark.style.background = (i === currentIdx) ? '" (theme/hex :lavender) "' : '" (theme/hex :royal-purple) "';
       mark.style.color      = (i === currentIdx) ? '" (theme/hex :bedrock)   "' : '" (theme/hex :crystal-white)  "';
       mark.style.borderRadius = '2px';
       mark.style.padding = '0 1px';
       range.surroundContents(mark);
     }

     // Scroll current match into view
     var current = document.querySelectorAll('mark.wz-find')[currentIdx];
     if (current) current.scrollIntoView({block: 'center', behavior: 'smooth'});

     return matches.length;
   })('%s', %s, %d);"))

(def ^:private browser-clear-marks-js
  "JavaScript to remove all find highlight marks."
  "document.querySelectorAll('mark.wz-find').forEach(function(m) {
     var parent = m.parentNode;
     parent.replaceChild(document.createTextNode(m.textContent), m);
     parent.normalize();
   });")

(defn- browser-highlight-matches!
  "Find all matches in the Browser, highlight them with <mark> elements,
  and scroll to the current match. Returns the total match count."
  [^Browser browser query case-sensitive? current-idx]
  (when (and browser (not (.isDisposed browser)))
    (if (seq query)
      (let [js     (format (browser-highlight-js)
                           (escape-js-string query)
                           (str (boolean case-sensitive?))
                           (int (max 0 current-idx)))
            result (.evaluate browser js)]
        (if (number? result) (long result) 0))
      (do (.execute browser browser-clear-marks-js)
          0))))

(defn- browser-clear-highlights!
  "Remove all find highlight marks from the Browser."
  [^Browser browser]
  (when (and browser (not (.isDisposed browser)))
    (.execute browser browser-clear-marks-js)))

;; ---------------------------------------------------------------------------
;; Mode-aware search + navigation
;; ---------------------------------------------------------------------------

(defn- run-search!
  "Execute a search and update highlights + navigation.
  Dispatches to StyledText or Browser depending on the find-state mode."
  []
  (when-let [{:keys [find-text count-label case-sensitive? mode]
              :as   state} @find-state]
    (when (and find-text (not (.isDisposed find-text)))
      (let [query (.getText find-text)]
        (case mode
          :editor
          (let [^StyledText st (:styled-text state)]
            (when (and st (not (.isDisposed st)))
              (let [matches (vec (or (find-matches (.getText st) query case-sensitive?) []))
                    idx     (if (seq matches) 0 -1)]
                (swap! find-state assoc :matches matches :current-idx idx)
                (clear-find-highlights! st)
                (apply-find-highlights! st matches idx)
                (when (pos? (count matches))
                  (navigate-to-match! st matches idx))
                (update-count-label! count-label (count matches) idx))))

          :viewer
          (let [^Browser brow (:browser state)]
            (when (and brow (not (.isDisposed brow)))
              (let [idx 0
                    n   (browser-highlight-matches! brow query case-sensitive? idx)]
                (swap! find-state assoc :match-count n :current-idx (if (pos? n) idx -1))
                (update-count-label! count-label n (if (pos? n) idx -1))))))))))

(defn- navigate-next! []
  (let [{:keys [mode count-label case-sensitive? find-text] :as state} @find-state]
    (case mode
      :editor
      (let [{:keys [matches current-idx ^StyledText styled-text]} state]
        (when (seq matches)
          (let [new-idx (mod (inc current-idx) (count matches))]
            (swap! find-state assoc :current-idx new-idx)
            (clear-find-highlights! styled-text)
            (apply-find-highlights! styled-text matches new-idx)
            (navigate-to-match! styled-text matches new-idx)
            (update-count-label! count-label (count matches) new-idx))))

      :viewer
      (let [{:keys [^Browser browser match-count current-idx]} state
            query (.getText find-text)]
        (when (and (pos? match-count) browser (not (.isDisposed browser)))
          (let [new-idx (mod (inc current-idx) match-count)]
            (browser-highlight-matches! browser query case-sensitive? new-idx)
            (swap! find-state assoc :current-idx new-idx)
            (update-count-label! count-label match-count new-idx))))

      nil)))

(defn- navigate-prev! []
  (let [{:keys [mode count-label case-sensitive? find-text] :as state} @find-state]
    (case mode
      :editor
      (let [{:keys [matches current-idx ^StyledText styled-text]} state]
        (when (seq matches)
          (let [new-idx (mod (dec current-idx) (count matches))]
            (swap! find-state assoc :current-idx new-idx)
            (clear-find-highlights! styled-text)
            (apply-find-highlights! styled-text matches new-idx)
            (navigate-to-match! styled-text matches new-idx)
            (update-count-label! count-label (count matches) new-idx))))

      :viewer
      (let [{:keys [^Browser browser match-count current-idx]} state
            query (.getText find-text)]
        (when (and (pos? match-count) browser (not (.isDisposed browser)))
          (let [new-idx (mod (dec current-idx) match-count)]
            (browser-highlight-matches! browser query case-sensitive? new-idx)
            (swap! find-state assoc :current-idx new-idx)
            (update-count-label! count-label match-count new-idx))))

      nil)))

;; ---------------------------------------------------------------------------
;; Replace (editor mode only)
;; ---------------------------------------------------------------------------

(defn- replace-current! []
  (when-let [{:keys [matches current-idx styled-text
                     replace-text mode]} @find-state]
    (when (and (= mode :editor)
               (seq matches) replace-text (not (.isDisposed replace-text)))
      (let [{:keys [start length]} (nth matches current-idx)
            replacement (.getText replace-text)]
        (.replaceTextRange ^StyledText styled-text start length replacement)
        (run-search!)))))

(defn- replace-all! []
  (when-let [{:keys [matches styled-text replace-text mode]} @find-state]
    (when (and (= mode :editor)
               (seq matches) replace-text (not (.isDisposed replace-text)))
      (let [replacement (.getText replace-text)]
        (doseq [{:keys [start length]} (reverse matches)]
          (.replaceTextRange ^StyledText styled-text start length replacement))
        (run-search!)))))

;; ---------------------------------------------------------------------------
;; Show / hide replace row
;; ---------------------------------------------------------------------------

(defn- toggle-replace-visibility!
  "Show or hide the replace row (label + text + buttons — 3 grid cells)."
  [show?]
  (when-let [{:keys [^Shell shell replace-label replace-text
                     replace-buttons]} @find-state]
    (when (and shell (not (.isDisposed shell)))
      (doseq [w [replace-label replace-text replace-buttons]]
        (when (and w (not (.isDisposed w)))
          (let [^GridData gd (.getLayoutData w)]
            (set! (.-exclude gd) (not show?))
            (.setVisible w show?))))
      (.pack shell false)
      (let [packed (.getBounds shell)]
        (.setSize shell bar-width (.height packed)))
      (swap! find-state assoc :replace-visible? show?))))

;; ---------------------------------------------------------------------------
;; Open / close
;; ---------------------------------------------------------------------------

(defn close-find-bar!
  "Close the find bar and clean up."
  []
  (when-let [{:keys [^Shell shell mode styled-text browser]} @find-state]
    (case mode
      :editor (when (and styled-text (not (.isDisposed ^StyledText styled-text)))
                (clear-find-highlights! styled-text)
                (.setFocus ^StyledText styled-text))
      :viewer (when (and browser (not (.isDisposed ^Browser browser)))
                (browser-clear-highlights! browser)
                (.setFocus ^Browser browser))
      nil)
    (when (and shell (not (.isDisposed shell)))
      (.close shell))))

(defn open-find-bar!
  "Open the find bar floating above the given widget.

  Layout: 3-column grid so 'Find:' and 'Replace:' labels align in column 1,
  text fields align in column 2, and button composites sit in column 3.

  Options (provide exactly one of :styled-text or :browser):
    :styled-text   — editor StyledText widget (enables replace)
    :browser       — viewer Browser widget (find only, no replace)
    :show-replace? — if true, show the replace row (editor mode only)

  Must be called on the UI thread."
  [{:keys [^Browser browser show-replace?]
    ^StyledText editor-st :styled-text
    :or {show-replace? false}}]

  (when (find-bar-open?)
    (close-find-bar!))

  (let [mode           (if browser :viewer :editor)
        ;; In viewer mode, never show replace
        show-replace?  (and (= mode :editor) show-replace?)
        target-widget  (or browser editor-st)
        ^Color bg       @resources/color-find-bar
        ^Color fg       @resources/color-crystal-white
        ^Color sel-bg   @resources/color-royal-purple
        ^Color sel-fg   @resources/color-bedrock
        ^Color field-bg @resources/color-mine-shaft
        font            @resources/body-font
        props           (atom {})

        ;; Position: top-right of the target widget
        tgt-bounds (.getBounds target-widget)
        tgt-display (.toDisplay target-widget (- (.width tgt-bounds) bar-width 16) 4)

        ;; Shell must be created manually — CDT's `shell` doesn't support
        ;; child-shell parenting (SWT/TOOL requires a parent Shell).
        parent-sh (.getShell target-widget)
        sh        (Shell. ^Shell parent-sh (| SWT/TOOL SWT/ON_TOP SWT/NO_TRIM))

        ;; Pre-populate from StyledText selection (editor mode only)
        seed-text (when (= mode :editor)
                    (let [sel (.getSelectionText editor-st)]
                      (when (and (seq sel) (not (str/includes? sel "\n")))
                        sel)))]

    ;; 3-column grid: [label] [text field (grab)] [buttons composite]
    (child-of sh props
              (defchildren
                (grid-layout :num-columns 3
                             :margin-width 8
                             :margin-height 6
                             :horizontal-spacing 6
                             :vertical-spacing 4)
                :background bg

                ;; --- Find row: col 1 — label ---
                (label "Find:"
                       :foreground fg
                       :background bg
                       :font font
                       (grid/grid-data :vertical-alignment SWT/CENTER))

                ;; --- Find row: col 2 — text field (grabs horizontal) ---
                (styled-text (| SWT/SINGLE SWT/BORDER) (id! :ui/find-text)
                             :background field-bg
                             :foreground fg
                             :selection-background sel-bg
                             :selection-foreground sel-fg
                             :font font
                             (grid/grid-data :horizontal-alignment        SWT/FILL
                                             :grab-excess-horizontal-space true
                                             :vertical-alignment          SWT/CENTER)
                      ;; Set scope for keybinding dispatch
                             (fn [_props parent] (.setData parent "scope" :find-bar))
                      ;; Pre-populate AFTER state is initialized (see below).
                      ;; Do NOT setText here — it fires modifyText before find-state exists.
                             (on e/modify-text [_props _parent _event]
                                 (when @find-state (run-search!)))
                             (on e/key-pressed [_props _parent event]
                                 (let [kc (.keyCode event)]
                                   (cond
                                     (= kc (int SWT/CR))
                                     (if (not= 0 (bit-and (.stateMask event) SWT/SHIFT))
                                       (navigate-prev!)
                                       (navigate-next!))
                                     (= kc (int SWT/ESC))
                                     (close-find-bar!)))))

                ;; --- Find row: col 3 — buttons composite ---
                ;; RowLayout with :fill true forces all children to the same height,
                ;; ensuring the toggle button and label share the push-button baseline.
                (composite
                 :background bg
                 (with-property :layout (RowLayout. SWT/HORIZONTAL)
                   :spacing 2
                   :fill true
                   :center true
                   :margin-top 0 :margin-bottom 0
                   :margin-left 0 :margin-right 0)
                 (grid/grid-data :vertical-alignment SWT/CENTER)

                 (composite
                  (grid-layout
                   :num-columns 5
                   :margin-top 0 :margin-bottom 0
                   :margin-left 0 :margin-right 0)

                  :background bg

                  (button SWT/PUSH "↑"
                          :font font
                          (grid/grid-data :vertical-alignment SWT/CENTER)
                          (on e/widget-selected [_props _parent _event] (navigate-prev!)))
                  (button SWT/PUSH "↓"
                          :font font
                          (grid/grid-data :vertical-alignment SWT/CENTER)
                          (on e/widget-selected [_props _parent _event] (navigate-next!)))
                  (button (| SWT/TOGGLE SWT/FLAT) "Aa"
                          :font font
                          :tool-tip-text "Case sensitive"
                          (grid/grid-data :vertical-alignment SWT/CENTER)
                          (on e/widget-selected [_props parent _event]
                              (swap! find-state assoc :case-sensitive? (.getSelection parent))
                              (run-search!)))
                  (label (id! :ui/count-label)
                         "No results"
                         (grid/grid-data :vertical-alignment SWT/CENTER)
                         :foreground @resources/color-lavender
                         :background bg
                         :font font)
                  (button SWT/PUSH "×"
                          :font font
                          (grid/grid-data :vertical-alignment SWT/CENTER)
                          (on e/widget-selected [_props _parent _event] (close-find-bar!)))))

                ;; --- Replace row: col 1 — label (with exclude for show/hide) ---
                (label (id! :ui/replace-label) "Replace:"
                       :foreground fg
                       :background bg
                       :font font
                       (fn [_props parent]
                         (let [gd (GridData. SWT/BEGINNING SWT/CENTER false false)]
                           (set! (.-exclude gd) (not show-replace?))
                           (.setLayoutData parent gd)
                           (.setVisible parent show-replace?))))

                ;; --- Replace row: col 2 — text field (with exclude for show/hide) ---
                (styled-text (| SWT/SINGLE SWT/BORDER) (id! :ui/replace-text)
                             :background field-bg
                             :foreground fg
                             :selection-background sel-bg
                             :selection-foreground sel-fg
                             :font font
                             (fn [_props parent]
                               (let [gd (GridData. SWT/FILL SWT/CENTER true false)]
                                 (set! (.-exclude gd) (not show-replace?))
                                 (.setLayoutData parent gd)
                                 (.setVisible parent show-replace?)))
                             (on e/key-pressed [_props _parent event]
                                 (when (= (.keyCode event) (int SWT/ESC))
                                   (close-find-bar!))))

                ;; --- Replace row: col 3 — buttons (with exclude for show/hide) ---
                (composite (id! :ui/replace-buttons)
                           :background bg
                           (with-property :layout (RowLayout. SWT/HORIZONTAL)
                             :spacing 4
                             :margin-top 0 :margin-bottom 0
                             :margin-left 0 :margin-right 0)
                           (fn [_props parent]
                             (let [gd (GridData. SWT/BEGINNING SWT/CENTER false false)]
                               (set! (.-exclude gd) (not show-replace?))
                               (.setLayoutData parent gd)
                               (.setVisible parent show-replace?)))

                           (button SWT/PUSH "Replace"
                                   :font font
                                   (on e/widget-selected [_props _parent _event] (replace-current!)))
                           (button SWT/PUSH "All"
                                   :font font
                                   (on e/widget-selected [_props _parent _event] (replace-all!))))

                ;; Dispose handler
                (on e/shell-closed [_props _parent _event]
                    (keybindings/clear-active-popup!)
                    (reset! find-state nil))))

    ;; Initialize state from props
    (reset! find-state
            {:shell            sh
             :mode             mode
             :find-text        (:ui/find-text @props)
             :replace-text     (:ui/replace-text @props)
             :replace-label    (:ui/replace-label @props)
             :replace-buttons  (:ui/replace-buttons @props)
             :styled-text      editor-st
             :browser          browser
             :count-label      (:ui/count-label @props)
             :matches          []
             :match-count      0
             :current-idx      -1
             :case-sensitive?  false
             :replace-visible? show-replace?})

    ;; Pre-populate find field now that state exists (triggers modifyText → run-search!)
    (when seed-text
      (.setText (:ui/find-text @props) seed-text))

    ;; Position and show — set width, then pack to content height
    (.setSize sh bar-width 0)
    (.setLocation sh (.x tgt-display) (.y tgt-display))
    (.pack sh false)
    ;; Preserve the bar-width (pack may shrink it); keep packed height
    (let [packed (.getBounds sh)]
      (.setSize sh bar-width (.height packed)))
    (.open sh)
    (.setFocus (:ui/find-text @props))

    ;; Register popup scope
    (keybindings/set-active-popup! :find-bar)

    sh))
