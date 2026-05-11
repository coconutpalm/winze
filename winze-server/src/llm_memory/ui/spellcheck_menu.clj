(ns llm-memory.ui.spellcheck-menu
  "Right-click and keyboard-driven spellcheck suggestion menu for the
  Markdown editor.  All widget work lives here; pure logic lives in
  `llm-memory.ui.spellcheck`.

  Public surface:
    * `install!` — attach the right-click handler to a StyledText
    * `show-suggestion-menu-at-caret!` — keyboard entry point, used by
      the `:editor/spellcheck-suggest` command"
  (:require
   [llm-memory.ui.spellcheck :as spellcheck]
   [ui.SWT :refer [async-exec!]])
  (:import
   [org.eclipse.swt SWT]
   [org.eclipse.swt.custom StyledText]
   [org.eclipse.swt.events MenuDetectListener MenuListener]
   [org.eclipse.swt.graphics Point]
   [org.eclipse.swt.widgets Listener Menu MenuItem]))

(defn- misspelling-at
  "Return the {:start :length :text} misspelling record covering
  `offset` in `st`, or nil."
  [^StyledText st ^long offset]
  (first (filter (fn [{:keys [start length]}]
                   (and (<= start offset)
                        (< offset (+ start length))))
                 (or (.getData st "spellcheck/miss") []))))

(defn- replace-word!
  "Replace the misspelling region `miss` in `st` with `replacement`
  and park the caret at the end of the replacement."
  [^StyledText st {:keys [start length]} ^String replacement]
  (async-exec!
   (fn []
     (when-not (.isDisposed st)
       (.replaceTextRange st start length replacement)
       (.setSelection st (int (+ start (.length replacement))))))))

(defn- add-push-item!
  [^Menu menu ^String label on-select]
  (doto (MenuItem. menu SWT/PUSH)
    (.setText label)
    (.addListener SWT/Selection
                  (reify Listener
                    (handleEvent [_ _] (on-select))))))

(defn- show-menu!
  "Build and show a spellcheck suggestion menu for `miss` at display
  coordinates (screen-x, screen-y).  Must be called on the UI thread."
  [^StyledText st miss screen-x screen-y]
  (let [menu   (Menu. (.getShell st) SWT/POP_UP)
        sugs   (spellcheck/suggestions (:text miss))]  ; ≤ 10, alphabetical
    (doseq [s sugs]
      (add-push-item! menu s #(replace-word! st miss s)))
    (when (seq sugs)
      (MenuItem. menu SWT/SEPARATOR))
    (add-push-item! menu "Add to Dictionary"
                    #(spellcheck/add-to-user-dict! (:text miss)))
    (add-push-item! menu "Ignore"
                    #(spellcheck/ignore-this-session! (:text miss)))
    (.addMenuListener menu
                      (reify MenuListener
                        (menuShown  [_ _])
                        (menuHidden [_ _]
                          (async-exec! #(.dispose menu)))))
    (.setLocation menu (int screen-x) (int screen-y))
    (.setVisible menu true)))

(defn install!
  "Install a MenuDetectListener on `st` that opens the spellcheck
  suggestion menu when the user right-clicks on a misspelled word,
  and otherwise allows SWT's default context menu."
  [^StyledText st]
  (.addMenuDetectListener
   st
   (reify MenuDetectListener
     (menuDetected [_ event]
       (let [pt   (Point. (.-x event) (.-y event))
             loc  (.toControl st pt)
             off  (try (.getOffsetAtPoint st loc)
                       (catch Throwable _ -1))
             miss (when (and off (>= off 0)) (misspelling-at st off))]
         (when miss
           (set! (.-doit event) false)
           (async-exec!
            #(show-menu! st miss (.-x event) (.-y event)))))))))

(defn show-suggestion-menu-at-caret!
  "Open the spellcheck suggestion menu anchored just below the caret
  if the caret is inside a misspelled word.  Silent no-op otherwise.
  Called from the `:editor/spellcheck-suggest` command."
  [^StyledText st]
  (async-exec!
   (fn []
     (when-not (.isDisposed st)
       (let [off  (.getCaretOffset st)
             miss (misspelling-at st off)]
         (when miss
           (let [^Point ctrl-pt (.getLocationAtOffset st off)
                 line-height    (.getLineHeight st)
                 anchor         (Point. (.-x ctrl-pt)
                                        (+ (.-y ctrl-pt) line-height))
                 ^Point screen  (.toDisplay st anchor)]
             (show-menu! st miss (.-x screen) (.-y screen)))))))))
