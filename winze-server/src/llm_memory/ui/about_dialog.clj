(ns llm-memory.ui.about-dialog
  "Modal 'About Winze' dialog — shown from the macOS application menu."
  (:require [llm-memory.ui.util :as ui-util]
            [ui.SWT :refer [| child-of display id! label shell]]
            [ui.gridlayout :as grid :refer [grid-layout]])
  (:import [org.eclipse.swt SWT]
           [org.eclipse.swt.graphics Image]
           [org.eclipse.swt.widgets Shell]))

(defn- load-image
  "Load an image from classpath resources. Caller must dispose."
  [^String path]
  (let [stream (.getResourceAsStream (clojure.lang.RT/baseLoader) path)]
    (Image. @display stream)))

(defn- center-dialog!
  "Position `dlg` at the center of `parent-shell`."
  [dlg parent-shell]
  (let [pb (.getBounds parent-shell)
        sz (.getSize dlg)
        x  (int (+ (.x pb) (/ (- (.width pb) (.width sz)) 2)))
        y  (int (+ (.y pb) (/ (- (.height pb) (.height sz)) 2)))]
    (.setLocation dlg x y)))

(defn show-about-dialog!
  "Open a modal About dialog parented to `parent-shell`.
  Blocks until the user closes it (nested modal event loop)."
  [^Shell parent-shell]
  (let [icon  (load-image "branding/icons/png/winze-icon-64.png")
        props (atom {})]
    (try
      (child-of parent-shell props
                (shell (| SWT/DIALOG_TRIM SWT/APPLICATION_MODAL)
                       (id! :ui/about-dialog)
                       "About Winze"
                       :image icon
                       (grid-layout :num-columns      1
                                    :margin-width     24
                                    :margin-height    24
                                    :vertical-spacing 12)
                       (label SWT/CENTER
                              :image icon
                              (grid/align-center))
                       (label SWT/CENTER
                              "Winze"
                              (grid/align-center-hgrab))
                       (label (| SWT/CENTER SWT/WRAP)
                              "Knowledgebase search server\nSemantic search for markdown notes and planning documents"
                              (grid/align-center-hgrab))
                       (label (| SWT/CENTER SWT/WRAP)
                              :text (str "Clojure " (clojure-version)
                                         "  •  Datalevin + inference4j\n"
                                         "all-MiniLM-L12-v2 (384d embeddings)\n"
                                         (System/getProperty "sun.java.command"))
                              (grid/align-center-hgrab))))

      (let [dlg (:ui/about-dialog @props)]
        (.pack dlg)
        (.setMinimumSize dlg (.computeSize dlg SWT/DEFAULT SWT/DEFAULT))
        (center-dialog! dlg parent-shell)
        (.open dlg)
        (.layout dlg)
        (ui-util/modal-event-loop! dlg))

      (finally
        (.dispose icon)))))
