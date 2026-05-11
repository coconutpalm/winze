(ns llm-memory.ui.command-palette
  "Command palette UI — fuzzy-filtered popup listing all available commands.
  Opens as a tool shell parented to the main window."
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [hyperfiddle.rcf :refer [tests]]
   [llm-memory.ui.commands :as commands]
   [llm-memory.ui.keybindings :as keybindings]
   [llm-memory.ui.resources :as resources :refer [element]]
   [ui.events :as e]
   [ui.gridlayout :as grid :refer [grid-layout]]
   [ui.SWT :refer [async-exec! child-of id! on shell styled-text table table-column |]])
  (:import
   [org.eclipse.swt SWT]
   [org.eclipse.swt.custom StyledText]
   [org.eclipse.swt.graphics Color Font]
   [org.eclipse.swt.layout GridLayout]
   [org.eclipse.swt.widgets Shell Table TableItem]))

;; ---------------------------------------------------------------------------
;; Fuzzy filter
;; ---------------------------------------------------------------------------

(defn fuzzy-match?
  "Return true if every space-separated token in `query` appears as a
  case-insensitive substring of `label`."
  [query label]
  (let [lc (str/lower-case label)]
    (every? #(str/includes? lc %)
            (str/split (str/lower-case (str/trim query)) #"\s+"))))

;; ---------------------------------------------------------------------------
;; Palette state
;; ---------------------------------------------------------------------------

(defonce ^:private palette-shell (atom nil))

(defn palette-open?
  "Return true if the palette is currently visible."
  []
  (when-let [sh @palette-shell]
    (and (not (.isDisposed sh)) (.isVisible sh))))

;; ---------------------------------------------------------------------------
;; Palette actions
;; ---------------------------------------------------------------------------

(defn- execute-and-close!
  "Execute the command for the selected (or topmost) table row and close the palette."
  [^Table table ^Shell sh]
  (let [sel-idx (.getSelectionIndex table)
        item    (if (>= sel-idx 0)
                  (.getItem table sel-idx)
                  (when (pos? (.getItemCount table))
                    (.getItem table 0)))]
    (when-let [cmd-id (some-> item (.getData "command-id"))]
      (log/debug "Command palette: executing" cmd-id)
      (.close sh)
      (commands/execute! cmd-id))))

;; ---------------------------------------------------------------------------
;; Populate / filter table
;; ---------------------------------------------------------------------------

(defn- populate-table!
  "Fill the table with commands matching `query`. Clears existing items first."
  [^Table table query hints all-commands]
  (.removeAll table)
  (let [matches (if (str/blank? query)
                  all-commands
                  (filter #(fuzzy-match? query (:label %)) all-commands))]
    (doseq [{:keys [id label]} matches]
      ;; TableItem. is dynamic row creation, not widget construction — stays raw.
      (let [item  (TableItem. table SWT/NONE)
            entry (get hints id)]
        (.setText item 0 label)
        (.setText item 1 (if entry (:scope entry) ""))
        (.setText item 2 (if entry (:hint entry) ""))
        (.setData item "command-id" id))))
  (when (pos? (.getItemCount table))
    (.select table 0)))

;; ---------------------------------------------------------------------------
;; Palette geometry
;; ---------------------------------------------------------------------------

(def ^:private palette-width 500)
(def ^:private palette-max-height 400)

(defn- position-palette!
  "Center the palette horizontally on the main window, ~20% from top."
  [^Shell palette-sh ^Shell parent-sh]
  (let [pb (.getBounds parent-sh)
        x  (int (+ (.x pb) (/ (- (.width pb) palette-width) 2)))
        y  (int (+ (.y pb) (* (.height pb) 0.2)))]
    (.setBounds palette-sh x y palette-width palette-max-height)))

;; ---------------------------------------------------------------------------
;; Resize palette to fit content
;; ---------------------------------------------------------------------------

(defn- resize-to-fit!
  "Resize the palette height to fit the filter field + visible table rows,
  capped at palette-max-height."
  [^Shell sh ^StyledText filter-text ^Table table]
  (let [filter-h (.. filter-text (computeSize SWT/DEFAULT SWT/DEFAULT) y)
        row-count (.getItemCount table)
        item-h    (.getItemHeight table)
        header-h  (if (.getHeaderVisible table) (.getHeaderHeight table) 0)
        table-h   (+ header-h (* (min row-count 15) item-h) item-h) ;; extra row height for padding
        layout    (.getLayout sh)
        spacing   (if (instance? GridLayout layout)
                    (.verticalSpacing ^GridLayout layout)
                    4)
        margins   (if (instance? GridLayout layout)
                    (* 2 (.marginHeight ^GridLayout layout))
                    8)
        total     (min palette-max-height
                       (+ filter-h spacing table-h margins))
        bounds    (.getBounds sh)]
    (.setSize sh (.width bounds) (int total))))

;; ---------------------------------------------------------------------------
;; Build the palette shell
;; ---------------------------------------------------------------------------

(defn open-palette!
  "Open the command palette popup. Must be called on the UI thread."
  []
  (when (palette-open?)
    (.close @palette-shell))

  (let [parent-sh (element :main-window)
        all-cmds  (sort-by :label (commands/list-commands))
        hints     (keybindings/hint-index)
        bg        ^Color @resources/color-mine-shaft
        fg        ^Color @resources/color-crystal-white
        font      ^Font  @resources/body-font
        sel-bg    ^Color @resources/color-royal-purple
        props     (atom {})
        _         (child-of parent-sh props
                            (shell (| SWT/TOOL SWT/ON_TOP SWT/NO_TRIM)
                                   (id! :palette/shell)
                                   :background bg
                                   (grid-layout :num-columns     1
                                                :margin-width    4
                                                :margin-height   4
                                                :vertical-spacing 2)

                                    ;; Filter field — StyledText required: Text.setSelectionBackground
                                    ;; is a no-op on macOS Cocoa; StyledText owns its own paint.
                                   (styled-text SWT/SINGLE
                                                (id! :palette/filter)
                                                :background           bg
                                                :foreground           fg
                                                :font                 font
                                                :selection-background sel-bg
                                                :selection-foreground fg
                                                (grid/grid-data :horizontal-alignment        SWT/FILL
                                                                :grab-excess-horizontal-space true
                                                                :height-hint                 28)
                                                (fn [_props parent]
                                                  (.setData parent "scope" :command-palette))
                                                (on e/modify-text [_props parent _event]
                                                    (let [sh  (:palette/shell @props)
                                                          tbl (:palette/table @props)]
                                                      (populate-table! tbl (.getText parent) hints all-cmds)
                                                      (resize-to-fit! sh parent tbl)))
                                                (on e/key-pressed [_props _parent event]
                                                    (let [kc  (.keyCode event)
                                                          sh  (:palette/shell @props)
                                                          tbl (:palette/table @props)]
                                                      (cond
                                                        (= kc SWT/ARROW_DOWN)
                                                        (let [idx (.getSelectionIndex tbl)
                                                              cnt (.getItemCount tbl)]
                                                          (when (< (inc idx) cnt)
                                                            (.select tbl (inc idx))
                                                            (.showSelection tbl))
                                                          (set! (.-doit event) false))

                                                        (= kc SWT/ARROW_UP)
                                                        (let [idx (.getSelectionIndex tbl)]
                                                          (when (pos? idx)
                                                            (.select tbl (dec idx))
                                                            (.showSelection tbl))
                                                          (set! (.-doit event) false))

                                                        (= kc (int SWT/CR))
                                                        (do (execute-and-close! tbl sh)
                                                            (set! (.-doit event) false))

                                                        (= kc (int SWT/ESC))
                                                        (do (.close sh)
                                                            (set! (.-doit event) false))))))

                                    ;; Results table
                                   (table (| SWT/SINGLE SWT/FULL_SELECTION)
                                          (id! :palette/table)
                                          :background     bg
                                          :foreground     fg
                                          :font           font
                                          :header-visible false
                                          :lines-visible  false
                                          (grid/grab-both)

                                          (table-column :width 310)
                                          (table-column :width 70)
                                          (table-column :width 88)

                                          (on e/widget-selected [_props parent _event]
                                              (execute-and-close! parent (:palette/shell @props))))

                                    ;; Deactivate → close
                                   (on e/shell-deactivated [_props parent _event]
                                       (async-exec! #(when (and (not (.isDisposed parent))
                                                                (.isVisible parent))
                                                       (.close parent))))

                                    ;; Dispose → clear state
                                   (on e/widget-disposed [_props _parent _event]
                                       (reset! palette-shell nil)
                                       (keybindings/clear-active-popup!))))
        sh         (:palette/shell  @props)
        filter-txt (:palette/filter @props)
        tbl        (:palette/table  @props)]
    (populate-table! tbl "" hints all-cmds)
    (position-palette! sh parent-sh)
    (.open sh)
    (.setFocus filter-txt)
    (resize-to-fit! sh filter-txt tbl)
    (reset! palette-shell sh)
    (keybindings/set-active-popup! :command-palette)
    sh))

(defn close-palette!
  "Close the command palette if open."
  []
  (when-let [sh @palette-shell]
    (when (and (not (.isDisposed sh)) (.isVisible sh))
      (.close sh))))

;; ---------------------------------------------------------------------------
;; Command registration
;; ---------------------------------------------------------------------------

(defn register-palette-commands!
  "Register the open/dismiss commands and keybindings for the command palette."
  []
  (commands/register!
   {:id       :workbench/open-command-palette
    :label    "Open Command Palette"
    :category :workbench
    :action   (fn [] (async-exec! open-palette!))})

  (commands/register!
   {:id       :workbench/dismiss-command-palette
    :label    "Dismiss Command Palette"
    :category :workbench
    :action   (fn [] (async-exec! close-palette!))})

  ;; Register keybindings: Cmd+Shift+P and Esc-to-dismiss
  (keybindings/register-bindings!
   [{:key \p :mod #{:mod1 :shift}
     :command :workbench/open-command-palette
     :comment "Open command palette"}
    {:key :esc :when {:active-popup :command-palette}
     :command :workbench/dismiss-command-palette
     :comment "Dismiss command palette"}]))

;; ---------------------------------------------------------------------------
;; RCF tests (fuzzy filter only — UI tests require REPL)
;; ---------------------------------------------------------------------------

(tests
 ;; Single token match
 (fuzzy-match? "bold" "Toggle Bold")      := true
 (fuzzy-match? "BOLD" "Toggle Bold")      := true
 (fuzzy-match? "xyz"  "Toggle Bold")      := false

 ;; Multi-token match (any order)
 (fuzzy-match? "tog bold" "Toggle Bold")  := true
 (fuzzy-match? "bold tog" "Toggle Bold")  := true

 ;; Empty query matches everything
 (fuzzy-match? ""  "Toggle Bold")         := true
 (fuzzy-match? " " "Toggle Bold")         := true

 ;; Partial substring
 (fuzzy-match? "og" "Toggle Bold")        := true
 (fuzzy-match? "h1" "Heading 1")          := false  ;; "h1" is not a substring of "Heading 1"
 (fuzzy-match? "head 1" "Heading 1")      := true

 :rcf)
