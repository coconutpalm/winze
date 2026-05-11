(ns llm-memory.ui.util
  "Reusable SWT/CDT utility functions for screenshots, image I/O, visual verification,
  and CDT init function helpers."
  (:require [ui.SWT :refer [ui with-gc-on]]
            [ui.inits :as i])
  (:import [org.eclipse.swt SWT]
           [org.eclipse.swt.graphics Image ImageData ImageLoader]
           [org.eclipse.swt.widgets Display]))

(defn save-image!
  "Save an Image to a PNG file at `path`. Does NOT dispose the image —
  the caller owns the Image lifecycle (see SWT-UI-GUIDE §7)."
  [^Image image ^String path]
  (let [loader (ImageLoader.)]
    (set! (.-data loader) (into-array ImageData [(.getImageData image)]))
    (.save loader path SWT/IMAGE_PNG)))

(defn screenshot-widget!
  "Capture a screenshot of `widget` and save as PNG to `path`.
  Must be called on the UI thread (wrap in `ui` from nREPL).
  Creates and disposes its own Image; uses CDT's `with-gc-on` for the GC."
  [widget ^String path]
  (let [bounds (.getBounds widget)
        image  (Image. (.getDisplay widget) (.width bounds) (.height bounds))]
    (try
      (with-gc-on widget
        (fn [gc] (.copyArea gc image 0 0)))
      (save-image! image path)
      (finally
        (.dispose image)))))

(defn screenshot-display!
  "Capture the entire screen and save as PNG to `path`.
  Must be called on the UI thread."
  [^String path]
  (let [display (Display/getDefault)
        bounds  (.getBounds display)
        image   (Image. display (.width bounds) (.height bounds))]
    (try
      (with-gc-on display
        (fn [gc] (.copyArea gc image 0 0)))
      (save-image! image path)
      (finally
        (.dispose image)))))

(defn control
  "Returns a CDT init function that calls .setControl on the parent widget,
  wiring it to a previously constructed widget stored in props under `child-key`.
  Works with any SWT widget that has a .setControl method (CTabItem, ScrolledComposite,
  ViewForm, etc.). Use alongside `id!` from ui.SWT:
    (text SWT/MULTI (id! :ui/tab-content) \"Tab text\")
    (ctab-item SWT/CLOSE :text \"Tab\" (control :ui/tab-content))
  Throws if `child-key` is not found in props (guards against init-order bugs)."
  [child-key]
  (fn [props parent]
    (let [widget (get @props child-key)]
      (when-not widget
        (throw (ex-info (str "control: " child-key " not found in props")
                        {:key        child-key
                         :props-keys (keys @props)})))
      (.setControl parent widget))))

(defn modal-event-loop!
  "Run a nested SWT event loop that blocks until `dialog-shell` is closed.
  Use for modal dialogs that need their own readAndDispatch/sleep loop."
  [dialog-shell]
  (let [disp (.getDisplay dialog-shell)]
    (while (not (.isDisposed dialog-shell))
      (when-not (.readAndDispatch disp)
        (.sleep disp)))))

(defn show
  "Run a CDT init function against the running Display on the UI thread,
  using a fresh props atom. Returns [widget props] so callers can inspect
  values placed into props by `id!` and other init functions.
  For REPL testing without `application`:
    (let [[sh props] (show (shell SWT/SHELL_TRIM \"Title\" :layout (FillLayout.) ...))]
      ...)
  The existing main-thread event loop handles all events for the new window."
  ([init]
   (let [init-fn (first (i/args->inits [init]))]
     (ui
      (let [props  (atom {})
            widget (init-fn props (Display/getDefault))]
        [widget props]))))
  ([maybe-init & more]
   (let [props (atom {})
         more (cons maybe-init more)
         disp (Display/getDefault)]
     [(->> (i/args->inits more)
           (i/run-inits props disp))
      props])))
