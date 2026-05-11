(ns llm-memory.ui.resources
  (:require
   [clojure.tools.logging :as log]
   [hyperfiddle.rcf :refer [tests]]
   [ui.SWT :refer [display ui with-gc-on]])
  (:import
   [org.eclipse.swt SWT]
   [org.eclipse.swt.graphics Color Image ImageDataProvider Resource]))

(defonce app-props (atom {}))

;; ---------------------------------------------------------------------------
;; Platform detection
;; ---------------------------------------------------------------------------

(def macos? (= "cocoa" (SWT/getPlatform)))

(defn accel-label
  "Return a label like 'Edit (⌘E)' on macOS or 'Edit (Ctrl+E)' elsewhere."
  [action key]
  (str action " (" (if macos? "⌘" "Ctrl+") key ")"))

(defn element
  "Look up a UI widget by keyword in app-props."
  [ui-kw]
  (get @app-props
       (keyword "ui" (name ui-kw))))

;; ---------------------------------------------------------------------------
;; Theme resources (colors, fonts, file-based icons)
;;
;; Each var here is an IAtom holding the current SWT resource
;; (Color / Font / Image) for one themable slot. Call sites use
;; `@color-foo` unchanged — atom deref is identical to delay deref at
;; the `@` site.
;;
;; WHY ATOMS (IAtom), NOT DELAYS: `theme/reload-theme!` enumerates
;; these vars via `ns-publics` and filters on
;; `(instance? clojure.lang.IAtom v)`. Every Clojure atom implements
;; IAtom; delays, promises, futures, refs, and agents do NOT. That
;; type check — not a naming convention — is what separates
;; "reload-swappable theme value" from everything else in this
;; namespace (procedural-icon delays, state atoms like `open-files`,
;; executors, etc.). Do NOT change these `defonce`s to delays, refs,
;; or reify-IDeref values: `reload-theme!` will silently skip
;; anything that isn't an IAtom.
;;
;; Initial value is `nil`; populated by `theme/apply-theme-startup!`
;; before any widget dereferences them (see PLAN Step 10 timing
;; invariant — derefs fire at widget-construction time, before
;; `defmain` runs).
;; ---------------------------------------------------------------------------

;; Colors
(defonce color-lavender         (atom nil))
(defonce color-amethyst         (atom nil))
(defonce color-deep-violet      (atom nil))
(defonce color-royal-purple     (atom nil))
(defonce color-indigo           (atom nil))   ; NEW — was hex-only in search.clj
(defonce color-deep-amethyst    (atom nil))   ; NEW — was hex-only in search.clj
(defonce color-crystal-white    (atom nil))
(defonce color-mine-shaft       (atom nil))
(defonce color-obsidian         (atom nil))
(defonce color-find-bar         (atom nil))
(defonce color-bedrock          (atom nil))
(defonce color-pure-white       (atom nil))
(defonce color-check-green      (atom nil))
(defonce color-spellcheck-error (atom nil))

;; Fonts — body
(defonce body-font             (atom nil))
(defonce body-bold-font        (atom nil))
(defonce body-italic-font      (atom nil))
(defonce body-bold-italic-font (atom nil))

;; Fonts — headings
(defonce h1-font (atom nil))
(defonce h2-font (atom nil))
(defonce h3-font (atom nil))
(defonce h4-font (atom nil))
(defonce h5-font (atom nil))
(defonce h6-font (atom nil))

;; Fonts — mono
(defonce mono-font        (atom nil))
(defonce mono-bold-font   (atom nil))
(defonce mono-italic-font (atom nil))

;; File-based icons (themable via `~/.winze/icons/` overrides)
(defonce app-icon          (atom nil))
(defonce statusbar-icon    (atom nil))
(defonce tab-document-icon (atom nil))
(defonce header-image      (atom nil))

;; ---------------------------------------------------------------------------
;; Procedural icons — NOT theme resources.
;;
;; These share the `-icon` suffix with themable icons (app-icon,
;; statusbar-icon, etc.) but are deliberately delays, not atoms. The
;; IAtom type check in `theme/reload-theme!` and `theme-atoms`
;; excludes them — a themable icon would be `(atom nil)`, a procedural
;; icon is `(delay …)`. Do not "fix" the apparent inconsistency by
;; making these atoms: their pixels are drawn from raw RGB literals
;; inside `draw-quill-icon` / `draw-chevron-image` and so cannot
;; participate in the theme-reload cycle without also teaching those
;; drawers to read from the palette atoms + re-render via
;; ImageDataProvider (deferred — see theme-externalize-edn
;; CONTEXT §Non-goals).
;;
;; Shutdown disposal IS handled by `dispose-registry!`'s unified
;; walk below, which branches by type — IAtom for themable
;; resources, realized Delay for these three procedural icons.
;; ---------------------------------------------------------------------------

(defn- draw-quill-icon
  "Draw a quill pen icon at the given size using SWT GC.
  Avoids PNG alpha transparency issues on macOS (see SWT-UI-GUIDE §12)."
  [size]
  (ui
   (let [image    (Image. @display size size)
         amethyst (Color. @display 0x9B 0x8F 0xE0)
         violet   (Color. @display 0x7B 0x6F 0xC0)
         lavender (Color. @display 0xC4 0xB8 0xFF)
         s        (fn [v] (int (* (/ size 16.0) v)))]
     (with-gc-on image
       (fn [gc]
         ;; Fill with widget background so the icon blends with the toolbar
         (.setBackground gc (.getSystemColor @display SWT/COLOR_WIDGET_BACKGROUND))
         (.fillRectangle gc 0 0 size size)
         (.setAntialias gc SWT/ON)
         ;; Feather body — filled polygon approximating the curved SVG path
         (.setBackground gc amethyst)
         (.fillPolygon gc (int-array [(s 14) (s 1)
                                      (s 11) (s 1)
                                      (s 8)  (s 4)
                                      (s 6)  (s 7)
                                      (s 5)  (s 9)
                                      (s 4)  (s 10)
                                      (s 3)  (s 14)
                                      (s 7)  (s 13)
                                      (s 9)  (s 11)
                                      (s 11) (s 9)
                                      (s 14) (s 5)
                                      (s 15) (s 2)]))
         ;; Nib tip — triangle
         (.setBackground gc violet)
         (.fillPolygon gc (int-array [(s 3.5) (s 11)
                                      (s 2.5) (s 14.5)
                                      (s 6)   (s 13.5)]))
         ;; Spine / rachis
         (.setForeground gc violet)
         (.setLineWidth gc (max 1 (int (* (/ size 16.0) 0.8))))
         (.setLineCap gc SWT/CAP_ROUND)
         (.drawLine gc (s 13) (s 2) (s 4) (s 11))
         ;; Feather barbs
         (.setForeground gc lavender)
         (.setLineWidth gc (max 1 (int (* (/ size 16.0) 0.5))))
         (.drawLine gc (s 11) (s 3) (s 8) (s 5))
         (.drawLine gc (s 10) (s 5) (s 7) (s 7))
         (.drawLine gc (s 9)  (s 7) (s 6) (s 9))))
     (.dispose amethyst)
     (.dispose violet)
     (.dispose lavender)
     image)))

(defn- quill-hidpi-image
  "Create a HiDPI-aware quill icon using ImageDataProvider."
  []
  (ui (let [img-1x  (draw-quill-icon 16)
            img-2x  (draw-quill-icon 32)
            data-1x (.getImageData img-1x)
            data-2x (.getImageData img-2x)]
        (.dispose img-1x)
        (.dispose img-2x)
        (Image. @display
                (reify ImageDataProvider
                  (getImageData [_ zoom]
                    (if (>= zoom 200) data-2x data-1x)))))))

(defonce edit-icon (delay (quill-hidpi-image)))

(defn- draw-chevron-image
  "Draw a chevron arrow icon at the given size.
   `direction` is :left or :right. Returns an Image (caller owns lifecycle)."
  [size direction]
  (ui
   (let [image (Image. @display size size)
         color (Color. @display 0x9B 0x8F 0xE0)]
     (with-gc-on image
       (fn [gc]
         (.setBackground gc (.getSystemColor @display SWT/COLOR_WIDGET_BACKGROUND))
         (.fillRectangle gc 0 0 size size)
         (.setAntialias gc SWT/ON)
         (.setLineCap gc SWT/CAP_ROUND)
         (.setLineJoin gc SWT/JOIN_ROUND)
         (.setForeground gc color)
         (.setLineWidth gc (if (>= size 32) 4 2))
         (let [margin (int (* size 0.3))
               mid    (int (/ size 2))
               tip-x  (if (= direction :left) margin (- size margin))
               top-x  (if (= direction :left) (- size margin) margin)
               bot-x  top-x]
           (.drawLine gc top-x margin tip-x mid)
           (.drawLine gc tip-x mid bot-x (- size margin)))))
     (.dispose color)
     image)))

(defn- chevron-hidpi-image
  "Create a HiDPI-aware chevron arrow image using ImageDataProvider."
  [direction]
  (ui (let [img-1x  (draw-chevron-image 16 direction)
            img-2x  (draw-chevron-image 32 direction)
            data-1x (.getImageData img-1x)
            data-2x (.getImageData img-2x)]
        (.dispose img-1x)
        (.dispose img-2x)
        (Image. @display
                (reify ImageDataProvider
                  (getImageData [_ zoom]
                    (if (>= zoom 200) data-2x data-1x)))))))

(defonce back-icon    (delay (chevron-hidpi-image :left)))
(defonce forward-icon (delay (chevron-hidpi-image :right)))

;; ---------------------------------------------------------------------------
;; Shared background executor
;; ---------------------------------------------------------------------------

(defonce executor
  (java.util.concurrent.Executors/newSingleThreadScheduledExecutor
   (reify java.util.concurrent.ThreadFactory
     (newThread [_ r]
       (doto (Thread. r "winze-background")
         (.setDaemon true))))))

;; ---------------------------------------------------------------------------
;; Tab tracking
;; ---------------------------------------------------------------------------

(let [tab-counter (atom 0)]
  (defn next-tab-id!
    "Generate a unique keyword for a tab widget."
    []
    (keyword "ui" (str "tab-" (swap! tab-counter inc)))))

;; Tracks files currently displayed in tabs.
;; {abs-path -> {:tab-ids #{tab-id-kw ...}, :rel-path str}}
(defonce open-files (atom {}))

;; Live search tab state: {:mode :synthetic/:file, :abs-path str?, :wrapper-id kw, :browser-id kw}
(defonce live-search-state (atom {:mode :synthetic}))

;; Last search query for live-search refresh: {:query "..." :filters {...}} or nil
(defonce last-search-query (atom nil))

;; ---------------------------------------------------------------------------
;; Per-tab navigation history
;; ---------------------------------------------------------------------------

;; Per-tab history stacks.
;; Key   = tab identifier (:live-search or a tab-id keyword from next-tab-id!)
;; Value = {:entries [entry ...] :position N}
;;
;; Entry types (maps with :type key):
;;   {:type :search  :query "..."}                     — live search query
;;   {:type :content :html "..." :title "..." ...}     — rendered content
;;   {:type :url     :url "https://..."}               — HTTP link navigation
;;
;; The history functions below are generic — they operate on entries via = for
;; dedup and don't inspect entry internals.
(defonce tab-histories (atom {}))

;; Flag: true while programmatically restoring a history entry (suppresses push)
(defonce restoring-history? (atom false))

(def empty-history {:entries [] :position -1})

(defn history-push
  "Push a query onto the history stack, truncating any forward entries.
   Deduplicates against the current entry. Returns the new history state."
  [{:keys [entries position] :as state} query]
  (if (and (>= position 0) (= query (entries position)))
    state
    (let [truncated   (if (neg? position)
                        []
                        (subvec entries 0 (inc position)))
          new-entries (conj truncated query)]
      {:entries new-entries :position (dec (count new-entries))})))

(defn history-back
  "Move back in history. Returns [new-state query-at-new-position] or nil if at start."
  [{:keys [entries position] :as state}]
  (when (pos? position)
    (let [new-pos (dec position)]
      [(assoc state :position new-pos) (entries new-pos)])))

(defn history-forward
  "Move forward in history. Returns [new-state query-at-new-position] or nil if at end."
  [{:keys [entries position] :as state}]
  (when (< position (dec (count entries)))
    (let [new-pos (inc position)]
      [(assoc state :position new-pos) (entries new-pos)])))

(defn can-go-back? [{:keys [position]}] (pos? position))

(defn can-go-forward? [{:keys [entries position]}]
  (< position (dec (count entries))))

(defn tab-history
  "Return the history state for a tab, or empty-history if none."
  [tab-key]
  (get @tab-histories tab-key empty-history))

(defn tab-push!
  "Push an entry onto a tab's history stack."
  [tab-key entry]
  (swap! tab-histories update tab-key
         (fn [h] (history-push (or h empty-history) entry))))

(defn tab-back!
  "Navigate back in a tab's history. Returns the entry at the new position, or nil."
  [tab-key]
  (let [result (atom nil)]
    (swap! tab-histories
           (fn [m]
             (if-let [[new-state entry] (history-back (get m tab-key empty-history))]
               (do (reset! result entry)
                   (assoc m tab-key new-state))
               m)))
    @result))

(defn tab-forward!
  "Navigate forward in a tab's history. Returns the entry at the new position, or nil."
  [tab-key]
  (let [result (atom nil)]
    (swap! tab-histories
           (fn [m]
             (if-let [[new-state entry] (history-forward (get m tab-key empty-history))]
               (do (reset! result entry)
                   (assoc m tab-key new-state))
               m)))
    @result))

(defn tab-can-go-back?
  "True if the tab has history to go back to."
  [tab-key]
  (can-go-back? (tab-history tab-key)))

(defn tab-can-go-forward?
  "True if the tab has forward history."
  [tab-key]
  (can-go-forward? (tab-history tab-key)))

(defn tab-clear-history!
  "Clear a tab's history."
  [tab-key]
  (swap! tab-histories dissoc tab-key))

(tests
 ;; Push onto empty history
 (history-push {:entries [] :position -1} "foo")
 := {:entries ["foo"] :position 0}

 ;; Push a second query
 (history-push {:entries ["foo"] :position 0} "bar")
 := {:entries ["foo" "bar"] :position 1}

 ;; Duplicate of current entry is a no-op
 (history-push {:entries ["foo" "bar"] :position 1} "bar")
 := {:entries ["foo" "bar"] :position 1}

 ;; Back from position 1
 (history-back {:entries ["foo" "bar"] :position 1})
 := [{:entries ["foo" "bar"] :position 0} "foo"]

 ;; Back at position 0 — nil (can't go further)
 (history-back {:entries ["foo"] :position 0})
 := nil

 ;; Back on empty history — nil
 (history-back {:entries [] :position -1})
 := nil

 ;; Forward from position 0 with 2 entries
 (history-forward {:entries ["foo" "bar"] :position 0})
 := [{:entries ["foo" "bar"] :position 1} "bar"]

 ;; Forward at end — nil
 (history-forward {:entries ["foo" "bar"] :position 1})
 := nil

 ;; Truncation: push while mid-history discards forward entries
 (history-push {:entries ["a" "b" "c"] :position 1} "d")
 := {:entries ["a" "b" "d"] :position 2}

 ;; can-go-back? / can-go-forward?
 (can-go-back? {:entries ["a" "b"] :position 0})  := false
 (can-go-back? {:entries ["a" "b"] :position 1})  := true
 (can-go-forward? {:entries ["a" "b"] :position 0}) := true
 (can-go-forward? {:entries ["a" "b"] :position 1}) := false
 (can-go-forward? {:entries [] :position -1})        := false

 ;; Map entries work the same — dedup via =
 (let [e1 {:type :search :query "foo"}
       e2 {:type :search :query "bar"}]
   (-> empty-history
       (history-push e1)
       (history-push e2)
       (history-push e2))  ; duplicate — no-op
   := {:entries [e1 e2] :position 1})

 ;; Per-tab helpers
 (do (reset! tab-histories {})
     (tab-push! :test-tab {:type :search :query "a"})
     (tab-push! :test-tab {:type :search :query "b"})
     (tab-can-go-back? :test-tab))    := true
 (tab-can-go-forward? :test-tab)      := false
 (tab-back! :test-tab)                := {:type :search :query "a"}
 (tab-can-go-forward? :test-tab)      := true
 (tab-forward! :test-tab)             := {:type :search :query "b"}
 (do (tab-clear-history! :test-tab)
     (tab-can-go-back? :test-tab))    := false
 :rcf)

;; ---------------------------------------------------------------------------
;; Registry disposal
;; ---------------------------------------------------------------------------

(defn dispose-registry!
  "Dispose every SWT Resource owned by this namespace. Must run on the
  UI thread, before Display disposal.

  One unified walk over ns-publics. Two resource storage kinds live
  here and each has its own type-level marker:

    - IAtom  → theme-swappable resources (colors, fonts, file-based
               icons). @atom is always safe — no realization.
               `theme/reload-theme!` keys on the same IAtom check, so
               disposal and reload see exactly the same set of vars.
    - Delay  → procedural icons (edit-icon, back-icon, forward-icon).
               Deref ONLY when realized — forcing an unrealized delay
               would construct a Resource just to dispose it.
    - anything else → skip. Non-IDeref public vars and non-resource
               atoms (e.g. `open-files`, `live-search-state`) pass
               through untouched.

  Why IAtom (not the concrete `Atom` class): `IAtom` is the Clojure
  interface every atom implements. Delays, promises, futures, refs,
  and agents do NOT implement it. Using the interface keeps the walk
  robust against any future atom-like type without broadening the
  match to things we don't want."
  []
  (doseq [[sym v] (ns-publics 'llm-memory.ui.resources)
          :let  [val  (var-get v)
                 held (cond
                        (instance? clojure.lang.IAtom val)  @val
                        (and (delay? val) (realized? val))  @val)]
          :when (and held
                     (instance? Resource held)
                     (not (.isDisposed ^Resource held)))]
    (try (.dispose ^Resource held)
         (catch Throwable t
           (log/warn t "Failed to dispose registry resource" sym)))))
