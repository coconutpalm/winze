(ns llm-memory.ui.main-window
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [llm-memory.core :as core]
   [llm-memory.highlight.loader :as lang-loader]
   [llm-memory.index :as index]
   [llm-memory.server.main :as server]
   [llm-memory.ui.about-dialog :refer [show-about-dialog!]]
   [llm-memory.ui.command-palette :as palette]
   [llm-memory.ui.commands :as commands]
   [llm-memory.ui.editor-commands :as editor-commands]
   [llm-memory.ui.find-replace :as find-replace]
   [llm-memory.ui.keybindings :as keybindings]
   [llm-memory.ui.link-preview :as link-preview]
   [llm-memory.ui.markdown-editor :as md-editor]
   [llm-memory.ui.md-theme :as md-theme]
   [llm-memory.ui.resources :as resources
    :refer [app-icon app-props back-icon edit-icon element
            forward-icon header-image live-search-state
            next-tab-id! open-files
            statusbar-icon tab-document-icon]]
   [llm-memory.ui.sample-kb :as sample-kb]
   [llm-memory.ui.search :as search]
   [llm-memory.ui.theme :as theme]
   [llm-memory.ui.util :refer [control]]
   [llm-memory.watcher :as watcher]
   [hyperfiddle.rcf :refer [tests]]
   [righttypes.nothing :refer [nothing->identity]]
   [ui.events :as e]
   [ui.gridlayout :as grid :refer [grid-layout]]
   [ui.inits :as i]
   [ui.SWT :refer [application async-exec! browser child-of composite
                   ctab-folder ctab-item defchildren defmain display id! label
                   menu menu-item on scrolled-composite shell text
                   with-property |]])
  (:import
   [java.net URLDecoder]
   [org.eclipse.swt SWT]
   [org.eclipse.swt.browser LocationAdapter ProgressAdapter]
   [org.eclipse.swt.custom StyledText]
   [org.eclipse.swt.events SelectionAdapter]
   [org.eclipse.swt.layout FillLayout]
   [org.eclipse.swt.widgets Composite DirectoryDialog Display Listener MessageBox ToolBar ToolItem TrayItem]))

(def app-name "winze")

(defn- word-wrap
  "Wrap s at width characters on word boundaries."
  [width s]
  (loop [words (str/split s #"\s+"), line "", lines []]
    (if (empty? words)
      (str/join "\n" (if (seq line) (conj lines line) lines))
      (let [word    (first words)
            joined  (if (empty? line) word (str line " " word))]
        (if (<= (count joined) width)
          (recur (rest words) joined lines)
          (recur (rest words) word (conj lines line)))))))

(defn- parse-query-string
  "Parse a URL query string into a map of decoded key-value pairs."
  [qs]
  (into {}
        (for [pair (str/split qs #"&")
              :let [[k v] (str/split pair #"=" 2)]]
          [(URLDecoder/decode k "UTF-8")
           (URLDecoder/decode (or v "") "UTF-8")])))

;; Forward declarations.  File is organised by widget-tree construction order
;; (header → body → tray), which places several helpers after their first use.
;; Reordering every case cascades through transitive dependencies on
;; `active-file-entry`, `active-history-key`, `restore-entry!` and would
;; sprawl across the file; the helpers below are declared rather than moved.
(declare open-tab!)              ; mutual recursion with `custom-browser` (guide §14)
(declare open-welcome-tab!)      ; called from `custom-browser` URL handler; defined after helper fns
(declare register-root-via-dialog!) ; called from `custom-browser` URL handler
(declare install-sample-kb-async!)  ; called from `custom-browser` URL handler
(declare update-edit-button!)    ; called from `open-file-in-tab!`; depends on `active-file-entry`
(declare set-live-search-content!) ; called from `header`; depends on `wrapper-child`
(declare navigate-back!)         ; called from `setup-edit-toolbar!`; depends on `restore-entry!`
(declare navigate-forward!)      ; called from `setup-edit-toolbar!`; depends on `restore-entry!`
(declare update-nav-buttons!)    ; called from `custom-browser`; depends on `active-history-key`

(defn- wrapper-child
  "Return the first child of a wrapper Composite, or nil."
  [^Composite wrapper]
  (when (and wrapper (not (.isDisposed wrapper)))
    (let [children (.getChildren wrapper)]
      (when (pos? (alength children))
        (aget children 0)))))

(defn- find-existing-tab
  "Return the CTabItem for abs-path if already open, nil otherwise."
  [abs-path]
  (when-let [{:keys [wrapper-id]} (get @open-files abs-path)]
    (let [folder  (element :main-folder)
          wrapper (get @app-props wrapper-id)]
      (when (and wrapper (not (.isDisposed wrapper)))
        (some (fn [item]
                (when (and (not (.isDisposed item))
                           (= (.getControl item) wrapper))
                  item))
              (.getItems folder))))))

(defn- focus-selected-tab-content!
  "Move focus to the content widget of the currently-selected tab.
   On macOS, focus left on a non-key-receiving widget (ToolBar) causes
   Cocoa to silently swallow Display-filter key events — Cmd+E and Esc
   vanish. Call this after any programmatic tab selection or creation."
  []
  (when-let [folder (element :main-folder)]
    (when-let [sel (.getSelection folder)]
      (when-let [ctrl (.getControl sel)]
        (let [child (or (wrapper-child ctrl) ctrl)]
          (when (and child (not (.isDisposed child)))
            (.setFocus child)))))))

(defn- browser-history-key
  "Find the history key (wrapper-id) for a Browser widget by checking open-files."
  [browser]
  (or
   ;; Check if this is the live search browser
   (when (= browser (get @app-props (:browser-id @live-search-state)))
     :live-search)
   ;; Check file tabs — find the entry whose wrapper contains this browser
   (some (fn [[_path {:keys [wrapper-id]}]]
           (let [wrapper (get @app-props wrapper-id)
                 child   (wrapper-child wrapper)]
             (when (= child browser)
               wrapper-id)))
         @open-files)))

(defn open-file-in-tab!
  "Open a file in a new tab (or focus existing tab).
  `root-uri` and `rel-path` identify the file. Resolves abs-path via search.
  Safe to call from any thread — UI work is dispatched via async-exec!."
  [root-uri rel-path]
  (let [abs-path (search/resolve-file-path root-uri rel-path)]
    (if-let [tab (find-existing-tab abs-path)]
      (async-exec!
       (fn []
         (.setSelection (element :main-folder) tab)
         (update-edit-button!)
         (focus-selected-tab-content!)))
      (future
        (try
          (let [content  (slurp abs-path)
                metadata (search/file-metadata-by-path root-uri rel-path)
                html     (search/file-page content rel-path metadata root-uri)
                filename (last (str/split rel-path #"/"))
                title    (search/tab-title filename content)]
            (async-exec! #(open-tab! @tab-document-icon title html rel-path abs-path rel-path root-uri)))
          (catch Throwable t
            (log/error t "Failed to open file" rel-path)))))))

(defn- custom-browser [& extra-inits]
  (apply browser SWT/WEBKIT
         :javascript-enabled true
         (fn [_props parent] (.setData parent "scope" :viewer))

         (on e/changing [props parent event]
             (let [loc (.location event)]
               (cond
                 (str/starts-with? loc "winze:open-file?")
                 (do (set! (.-doit event) false)
                     (let [params   (parse-query-string (subs loc (count "winze:open-file?")))
                           root-uri (get params "root")
                           rel-path (get params "path")]
                       (open-file-in-tab! root-uri rel-path)))

                 (str/starts-with? loc "winze:search?")
                 (do (set! (.-doit event) false)
                     (let [params (parse-query-string (subs loc (count "winze:search?")))
                           q      (get params "q")]
                       (async-exec!
                        (fn []
                          (.setSelection (element :main-folder) 0)
                          (.setText (element :search) q)
                          (focus-selected-tab-content!)))))

                 ;; wiki:root::path[#slug] — resolve via :file/id and navigate to file
                 (str/starts-with? loc "wiki:")
                 (do (set! (.-doit event) false)
                     (let [wiki-ref (subs loc 5)]
                       (if-let [s (server/store)]
                         (if-let [resolved (index/resolve-wiki-ref s wiki-ref)]
                           (let [{:keys [file-path root-uri]} resolved]
                             (open-file-in-tab! root-uri file-path))
                           (log/warn "wiki: broken link — ref not found:" wiki-ref))
                         (log/warn "wiki: link clicked but store not available"))))

                 (= loc "winze:open-welcome")
                 (do (set! (.-doit event) false)
                     (open-welcome-tab!))

                 (= loc "winze:register-root")
                 (do (set! (.-doit event) false)
                     (async-exec! register-root-via-dialog!))

                 (= loc "winze:install-sample-kb")
                 (do (set! (.-doit event) false)
                     (install-sample-kb-async!)))))
         ;; Track HTTP link navigation in the tab's history.
         ;; NOTE: Must use explicit LocationAdapter — CDT's (on e/changed ...) is
         ;; ambiguous for Browser (4 listeners have a `changed` method) and may
         ;; bind to StatusTextListener, crashing on (.location event).
         (fn [_props ^org.eclipse.swt.browser.Browser parent]
           (.addLocationListener
            parent
            (proxy [LocationAdapter] []
              (changed [^org.eclipse.swt.browser.LocationEvent event]
                (let [loc (.location event)]
                  (when (and (not @resources/restoring-history?)
                             (str/starts-with? loc "http"))
                    (when-let [hk (browser-history-key parent)]
                      (resources/tab-push! hk {:type    :url
                                               :url     loc
                                               :browser parent})
                      (async-exec! update-nav-buttons!))))))))

         ;; Link preview on hover for wiki: links in view mode
         (fn [_props ^org.eclipse.swt.browser.Browser parent]
           (link-preview/install-browser-link-preview! parent))

         extra-inits))

(defn- cleanup-tab-id!
  "Remove tab-id from app-props and open-files for abs-path."
  [tab-id abs-path]
  (swap! app-props dissoc tab-id)
  (when abs-path
    (swap! open-files
           (fn [m]
             (let [updated (update m abs-path
                                   (fn [e] (update e :tab-ids disj tab-id)))]
               (if (empty? (get-in updated [abs-path :tab-ids]))
                 (dissoc updated abs-path)
                 updated))))))

(defn open-tab!
  "Create a new closable tab. File tabs (with abs-path) use a wrapper Composite
   that holds a Browser in view mode; the wrapper enables toggling to edit mode.
   Non-file tabs (search results) use Browser directly."
  ([icon title html] (open-tab! icon title html title nil nil nil))
  ([icon title html tooltip] (open-tab! icon title html tooltip nil nil nil))
  ([icon title html tooltip abs-path rel-path] (open-tab! icon title html tooltip abs-path rel-path nil))
  ([icon title html tooltip abs-path rel-path root-uri]
   (let [folder     (element :main-folder)
         tab-id     (next-tab-id!)
         wrapper-id (when abs-path (next-tab-id!))]
     (when abs-path
       (swap! open-files assoc abs-path
              {:tab-ids      #{tab-id}
               :rel-path     rel-path
               :abs-path     abs-path
               :root-uri     root-uri
               :wrapper-id   wrapper-id
               :mode         :view
               :scroll-state (atom nil)}))
     (if abs-path
       ;; File tab — wrapper Composite holding a Browser
       (do (child-of folder app-props
                     (defchildren
                       (composite (id! wrapper-id)
                                  :layout (FillLayout.)
                                  (custom-browser (id! tab-id) :text html))
                       (ctab-item SWT/CLOSE (word-wrap 30 title)
                                  :image          icon
                                  :tool-tip-text  tooltip
                                  (control wrapper-id)
                                  (on e/widget-disposed [props parent event]
                                      (cleanup-tab-id! tab-id abs-path)
                                      (resources/tab-clear-history! wrapper-id)
                                      (when wrapper-id
                                        (swap! app-props dissoc wrapper-id))))))
           ;; Push initial content entry for this tab's history
           (resources/tab-push! wrapper-id
                                {:type     :content
                                 :html     html
                                 :browser  (get @app-props tab-id)
                                 :title    title
                                 :abs-path abs-path}))
       ;; Non-file tab — Browser directly
       (child-of folder app-props
                 (defchildren
                   (custom-browser (id! tab-id)
                                   :text html
                                   (on e/widget-disposed [props parent event]
                                       (swap! app-props dissoc tab-id)))
                   (ctab-item SWT/CLOSE (word-wrap 30 title)
                              :image          icon
                              :tool-tip-text  tooltip
                              (control tab-id)))))
     (.setSelection folder (dec (.getItemCount folder)))
     (update-edit-button!)
     (focus-selected-tab-content!))))

;; ---------------------------------------------------------------------------
;; File tab auto-refresh
;; ---------------------------------------------------------------------------

(defn- refresh-browser-with-scroll!
  "Replace browser HTML while preserving scroll position.
   Must be called on the UI thread."
  [browser html]
  (let [scroll-top (or (.evaluate browser "return document.documentElement.scrollTop;") 0.0)]
    (.setText browser html)
    (.addProgressListener browser
                          (proxy [ProgressAdapter] []
                            (completed [_event]
                              (.removeProgressListener browser this)
                              (.execute browser
                                        (str "window.scrollTo(0, Math.min("
                                             (long scroll-top)
                                             ", document.documentElement.scrollHeight"
                                             " - window.innerHeight));")))))))

(defn- update-tab-title!
  "Update the CTabItem title for abs-path based on current content.
   Skips the live search tab (its title is fixed).
   Must be called on the UI thread."
  [abs-path content]
  (when-not (= abs-path (:abs-path @live-search-state))
    (when-let [tab-item (find-existing-tab abs-path)]
      (let [rel-path (:rel-path (get @open-files abs-path))
            filename (last (str/split (or rel-path abs-path) #"/"))
            new-title (word-wrap 30 (search/tab-title filename content))]
        (when (not= (.getText tab-item) new-title)
          (.setText tab-item new-title))))))

(defn- refresh-open-tabs!
  "Re-read the file and update all tabs showing abs-path.
  In edit mode: delegated to `editor-set-text!` (dirty/identity guards,
  `*suppressing-modify?*`, `:last-snap` re-seed, `on-change` fire).
  In view mode: re-render HTML.
  File I/O and rendering run on the caller thread; all SWT widget access
  is inside async-exec! to satisfy the UI thread rule."
  [abs-path]
  (when-let [{:keys [wrapper-id rel-path root-uri]}
             (get @open-files abs-path)]
    (try
      (let [new-content (slurp abs-path)
            metadata    (when root-uri
                          (search/file-metadata-by-path root-uri rel-path))
            html        (search/file-page new-content rel-path metadata root-uri)]
        (async-exec!
         (fn []
           (let [wrapper (get @app-props wrapper-id)
                 child   (wrapper-child wrapper)]
             (when (and child (not (.isDisposed child)))
               (cond
                 (instance? StyledText child)
                 (md-editor/editor-set-text! child new-content)

                 (instance? org.eclipse.swt.browser.Browser child)
                 (do (refresh-browser-with-scroll! child html)
                     (update-tab-title! abs-path new-content))))))))
      (catch java.io.FileNotFoundException _ nil))))

(defn- close-open-tabs!
  "Close all tabs displaying the deleted file."
  [abs-path]
  (when-let [{:keys [wrapper-id]} (get @open-files abs-path)]
    (async-exec!
     (fn []
       (let [folder  (element :main-folder)
             wrapper (get @app-props wrapper-id)]
         (when (and wrapper (not (.isDisposed wrapper)))
           (doseq [item (.getItems folder)]
             (when (= (.getControl item) wrapper)
               (.dispose wrapper)
               (.dispose item)))))))))

(defn- rename-open-tabs!
  "Update open-files registry and tab titles/tooltips for a renamed file."
  [old-path new-path]
  (when-let [{:keys [wrapper-id rel-path root-uri] :as entry} (get @open-files old-path)]
    (let [plans-prefix (str/replace old-path (str "/" rel-path) "")
          new-rel-path (str/replace new-path (str plans-prefix "/") "")
          new-filename (last (str/split new-rel-path #"/"))]
      (swap! open-files (fn [m]
                          (-> m
                              (dissoc old-path)
                              (assoc new-path (assoc entry
                                                     :abs-path new-path
                                                     :rel-path new-rel-path)))))
      (async-exec!
       (fn []
         (let [folder  (element :main-folder)
               wrapper (get @app-props wrapper-id)]
           (when wrapper
             (doseq [item (.getItems folder)]
               (when (= (.getControl item) wrapper)
                 (let [content (try (slurp new-path) (catch Exception _ nil))
                       title   (search/tab-title new-filename (or content ""))]
                   (.setText item (word-wrap 30 title))
                   (.setToolTipText item new-rel-path))
                 ;; Re-render full page to update structured header
                 (let [child (wrapper-child wrapper)]
                   (when (instance? org.eclipse.swt.browser.Browser child)
                     (try
                       (let [content  (slurp new-path)
                             metadata (when root-uri
                                        (search/file-metadata-by-path root-uri new-rel-path))
                             html     (search/file-page content new-rel-path metadata root-uri)]
                         (refresh-browser-with-scroll! child html))
                       (catch java.io.FileNotFoundException _ nil)))))))))))))

(defn- refresh-live-search!
  "If the live search tab shows synthetic content (search results or multi-root home),
   re-run the query or re-build the home page to pick up file changes.
   All SWT widget access is inside async-exec! to satisfy the UI thread rule."
  []
  (when (= :synthetic (:mode @live-search-state))
    (let [{:keys [browser-id]} @live-search-state]
      (if @llm-memory.ui.resources/last-search-query
        ;; Active search — re-run it
        (future
          (try
            (when-let [html (search/refresh-last-search)]
              (async-exec!
               (fn []
                 (let [browser (get @app-props browser-id)]
                   (when (and browser (not (.isDisposed browser)))
                     (refresh-browser-with-scroll! browser html))))))
            (catch Throwable _ nil)))
        ;; No query — multi-root home cards
        (future
          (try
            (let [home (search/home-page)]
              (when (and home (= :synthetic (:mode home)))
                (async-exec!
                 (fn []
                   (let [browser (get @app-props browser-id)]
                     (when (and browser (not (.isDisposed browser)))
                       (refresh-browser-with-scroll! browser (:html home))))))))
            (catch Throwable _ nil)))))))

(defn- derive-root-args
  "Derive register-root! args from a selected filesystem path.
   Returns {:uri :name :plans-dir}."
  [selected-path]
  (let [f         (clojure.java.io/file selected-path)
        leaf      (.getName f)
        plans-sub (clojure.java.io/file f "Plans")
        plans-dir (cond
                    (= leaf "Plans")           ""
                    (.isDirectory plans-sub)   "Plans"
                    :else                      "")]
    {:uri       (str "file://" selected-path)
     :name      leaf
     :plans-dir plans-dir}))

(tests
 "derive-root-args — selected folder named Plans"
 (:plans-dir (derive-root-args "/some/project/Plans")) := ""
 (:name      (derive-root-args "/some/project/Plans")) := "Plans"
 "derive-root-args — selected folder is a bare docs dir"
 (:plans-dir (derive-root-args "/some/my-docs"))       := ""
 (:name      (derive-root-args "/some/my-docs"))       := "my-docs"
 (:uri       (derive-root-args "/some/my-docs"))       := "file:///some/my-docs"
 :rcf)

(defn open-welcome-tab!
  "Open the Welcome tab, or focus it if it already exists.
   Safe to call from any thread."
  []
  (async-exec!
   (fn []
     (if-let [existing (element :welcome-tab)]
       (when-not (.isDisposed existing)
         (.setSelection (element :main-folder) existing)
         (update-edit-button!)
         (focus-selected-tab-content!))
       (let [roots  (core/list-roots (server/store))
             html   (search/welcome-page roots)
             folder (element :main-folder)]
         (child-of folder app-props
                   (defchildren
                     (custom-browser (id! :ui/welcome-browser)
                                     :text html)
                     (ctab-item SWT/CLOSE
                                "Welcome"
                                :image @statusbar-icon
                                (id! :ui/welcome-tab)
                                (control :ui/welcome-browser)
                                (on e/widget-disposed [props parent event]
                                    (swap! app-props dissoc
                                           :ui/welcome-tab
                                           :ui/welcome-browser)))))
         (.setSelection folder (dec (.getItemCount folder)))
         (update-edit-button!)
         (focus-selected-tab-content!))))))

(defn- register-root-via-dialog!
  "Open a DirectoryDialog; register the chosen folder as a root.
   Must be called on the UI thread (dispatched via async-exec! from the URL handler)."
  []
  (let [dialog (doto (DirectoryDialog. (element :main-window))
                 (.setText "Choose a folder of markdown documents"))]
    (when-let [selected (.open dialog)]
      (let [args  (derive-root-args selected)
            store (server/store)
            uri   (:uri args)
            existing-uris (set (map :root/uri (core/list-roots store)))]
        (if (existing-uris uri)
          (when-let [b (element :welcome-browser)]
            (when-not (.isDisposed b)
              (.setText b (str "<html><body style='font-family:sans-serif;padding:20px;color:#ccc'>"
                               "<p>&#x2139;&#xfe0f; <em>" (:name args) "</em> is already registered.</p>"
                               "</body></html>"))))
          (try
            (core/register-root! store args)
            (server/write-roots-config! store)
            (future
              (index/reconcile! store uri)
              (watcher/start-watcher! store uri))
            (catch Throwable t
              (log/error t "Failed to register root" selected)
              (when-let [b (element :welcome-browser)]
                (when-not (.isDisposed b)
                  (.setText b (str "<html><body style='font-family:sans-serif;padding:20px;color:#f88'>"
                                   "<p>Error registering folder: " (.getMessage t) "</p>"
                                   "</body></html>")))))))))))

(defn- refresh-welcome-tab!
  "Re-render the Welcome tab's Browser with the current roots list.
   No-op when the tab is closed."
  []
  (async-exec!
   (fn []
     (when-let [b (element :welcome-browser)]
       (when-not (.isDisposed b)
         (.setText b (search/welcome-page
                      (core/list-roots (server/store)))))))))

(defn- install-sample-kb-async!
  "Install the bundled sample knowledge base and register it as a root.
   Updates the Welcome tab browser with progress/result messages."
  []
  (when-let [b (element :welcome-browser)]
    (when-not (.isDisposed b)
      (.setText b (str "<html><body style='font-family:sans-serif;padding:20px;color:#ccc'>"
                       "<p>Installing sample knowledge base&#x2026;</p>"
                       "</body></html>"))))
  (future
    (try
      (let [{:keys [status path]} (sample-kb/install!)
            store (server/store)
            uri   (str "file://" path)
            existing-uris (set (map :root/uri (core/list-roots store)))]
        (when-not (existing-uris uri)
          (core/register-root! store {:uri uri :name "Sample" :plans-dir ""})
          (server/write-roots-config! store)
          (index/reconcile! store uri)
          (watcher/start-watcher! store uri))
        (async-exec!
         (fn []
           (refresh-welcome-tab!)
           (refresh-live-search!)))
        (log/info "Sample KB" (name status) "at" path))
      (catch Throwable t
        (log/error t "Failed to install sample KB")
        (async-exec!
         (fn []
           (when-let [b (element :welcome-browser)]
             (when-not (.isDisposed b)
               (.setText b (str "<html><body style='font-family:sans-serif;padding:20px;color:#f88'>"
                                "<p>Error installing sample knowledge base: " (.getMessage t) "</p>"
                                "</body></html>"))))))))))

(defn- on-file-changed
  "Watcher callback: refresh, close, or rename open file tabs.
   Also refreshes the live search tab if it shows synthetic content."
  [_root-uri abs-path event-type extra]
  (case event-type
    :modify (do (refresh-open-tabs! abs-path)
                (refresh-live-search!))
    :delete (do (close-open-tabs! abs-path)
                (refresh-live-search!))
    :rename (do (rename-open-tabs! (:old-path extra) (:new-path extra))
                (refresh-live-search!))
    nil))

(defn- on-root-changed
  "Root listener callback: refresh UI tabs after a root is registered or removed."
  [_event-type _root-map]
  (refresh-welcome-tab!)
  (refresh-live-search!))

;; ---------------------------------------------------------------------------
;; View / Edit toggle
;; ---------------------------------------------------------------------------

(defn- active-file-entry
  "Return the open-files entry for the currently selected tab, or nil
  if the tab is not a file tab."
  []
  (let [folder (element :main-folder)
        sel    (.getSelection folder)]
    (when sel
      (let [ctrl (.getControl sel)]
        (some (fn [[_path entry]]
                (when (= (get @app-props (:wrapper-id entry)) ctrl)
                  entry))
              @open-files)))))

(defn- update-edit-button!
  "Update the edit toolbar button state for the current tab."
  []
  (when-let [btn (element :edit-button)]
    (if-let [entry (active-file-entry)]
      (do (.setEnabled btn true)
          (.setToolTipText btn
                           (if (= :edit (:mode entry))
                             (llm-memory.ui.resources/accel-label "View" "E")
                             (llm-memory.ui.resources/accel-label "Edit" "E"))))
      (.setEnabled btn false))))

(defn- browser-top-line
  "Return the 0-based source line at the top of the Browser viewport.
  Returns 0 if no data-line elements exist (graceful fallback)."
  [browser]
  (let [result (.evaluate browser
                          "return (function() {
                              var els = document.querySelectorAll('[data-line]');
                              for (var i = els.length - 1; i >= 0; i--) {
                                if (els[i].getBoundingClientRect().top <= 0) {
                                  return parseInt(els[i].getAttribute('data-line'), 10);
                                }
                              }
                              return 0;
                            })()")]
    (if (number? result) (long result) 0)))

(defn- scroll-browser-to-line!
  "After page load, scroll so the nearest `data-line` element at or before
  `line` is at the top of the viewport."
  [browser line]
  (.addProgressListener browser
                        (proxy [ProgressAdapter] []
                          (completed [_event]
                            (.removeProgressListener browser this)
                            (.execute browser
                                      (str "var els = document.querySelectorAll('[data-line]'),"
                                           "    best = null, target = " line ";"
                                           "for (var i = 0; i < els.length; i++) {"
                                           "  if (parseInt(els[i].getAttribute('data-line'),10) <= target)"
                                           "    best = els[i];"
                                           "}"
                                           "if (best) best.scrollIntoView({block:'start'});"))))))

(defn toggle-mode!
  "Switch the active file tab between view and edit mode."
  [abs-path]
  (when-let [{:keys [wrapper-id mode rel-path root-uri scroll-state]}
             (get @open-files abs-path)]
    (let [wrapper (get @app-props wrapper-id)
          child   (wrapper-child wrapper)]
      (when child
        (let [from-line (when (= mode :view) (browser-top-line child))]
          (if (= mode :view)
            ;; view → edit: wrap padding + markdown-editor widget
            (do ((with-property :layout (FillLayout.)
                   :margin-width  12
                   :margin-height 12)
                 app-props wrapper)
                (.setBackground wrapper @llm-memory.ui.resources/color-mine-shaft)
                (.dispose child)
                (child-of wrapper app-props
                          (md-editor/markdown-editor
                           {:content       (slurp abs-path)
                            :abs-path      abs-path
                            :rel-path      rel-path
                            :root-uri      root-uri
                            :initial-line  from-line
                            :initial-caret (:caret @scroll-state)
                            :on-change     #(update-tab-title! abs-path %)}))
                (.setFocus (wrapper-child wrapper))
                (swap! open-files assoc-in [abs-path :mode] :edit))
            ;; edit → view: flush, capture scroll, dispose, install browser
            (let [st       child
                  _        (md-editor/editor-flush! st)
                  _        (reset! scroll-state {:line  (.getTopIndex st)
                                                 :caret (.getCaretOffset st)})
                  _        (.dispose st)
                  _        (doto wrapper
                             (.setLayout (FillLayout.))
                             (.setBackground nil))
                  content  (slurp abs-path)
                  metadata (when root-uri
                             (search/file-metadata-by-path root-uri rel-path))
                  html     (search/file-page content rel-path metadata root-uri)
                  brow-id  (next-tab-id!)]
              (child-of wrapper app-props
                        (custom-browser (id! brow-id) :text html))
              (let [brow (get @app-props brow-id)]
                (scroll-browser-to-line! brow (or (:line @scroll-state) 0))
                (.setFocus brow)
                (when (= wrapper-id (:wrapper-id @live-search-state))
                  (swap! app-props assoc :ui/live-search-browser brow)))
              (swap! open-files assoc-in [abs-path :mode] :view))))
        (.layout wrapper)
        (update-edit-button!)))))

;; ---------------------------------------------------------------------------
;; Toolbar
;; ---------------------------------------------------------------------------

(defn- setup-edit-toolbar!
  "Create back/forward nav + view/edit toggle in the CTabFolder's top-right area.
  Must be called after the folder exists."
  []
  (let [folder      (element :main-folder)
        toolbar     (ToolBar. ^Composite folder SWT/FLAT)
        back-btn    (ToolItem. toolbar SWT/PUSH)
        forward-btn (ToolItem. toolbar SWT/PUSH)
        _separator  (ToolItem. toolbar SWT/SEPARATOR)
        edit-btn    (ToolItem. toolbar SWT/PUSH)]
    ;; Back button
    (.setImage back-btn @back-icon)
    (.setToolTipText back-btn
                     (if resources/macos? "Back (⌘[)" "Back (Alt+Left)"))
    (.setEnabled back-btn false)
    (.addSelectionListener back-btn
                           (proxy [SelectionAdapter] []
                             (widgetSelected [_e] (navigate-back!))))
    ;; Forward button
    (.setImage forward-btn @forward-icon)
    (.setToolTipText forward-btn
                     (if resources/macos? "Forward (⌘])" "Forward (Alt+Right)"))
    (.setEnabled forward-btn false)
    (.addSelectionListener forward-btn
                           (proxy [SelectionAdapter] []
                             (widgetSelected [_e] (navigate-forward!))))
    ;; Edit button
    (.setImage edit-btn @edit-icon)
    (.setToolTipText edit-btn (resources/accel-label "Edit" "E"))
    (.setEnabled edit-btn false)

    (.setTopRight folder toolbar)
    (swap! app-props assoc
           :ui/back-button    back-btn
           :ui/forward-button forward-btn
           :ui/edit-button    edit-btn)
    (.addListener folder SWT/Selection
                  (reify Listener
                    (handleEvent [_ _e]
                      (async-exec!
                       (fn []
                         (update-edit-button!)
                         (update-nav-buttons!)
                         (focus-selected-tab-content!))))))
    ;; Edit toggle on click
    (.addSelectionListener edit-btn
                           (proxy [SelectionAdapter] []
                             (widgetSelected [_e]
                               (when-let [entry (active-file-entry)]
                                 (toggle-mode! (:abs-path entry))))))))

(defn- active-history-key
  "Return the history key for the currently selected tab.
   :live-search for the live search tab, or the wrapper-id for file tabs."
  []
  (let [folder (element :main-folder)]
    (when folder
      (if (= 0 (.getSelectionIndex folder))
        :live-search
        (when-let [entry (active-file-entry)]
          (:wrapper-id entry))))))

(defn- update-nav-buttons!
  "Enable/disable back and forward buttons based on the active tab's history."
  []
  (if-let [hk (active-history-key)]
    (do (when-let [back (element :back-button)]
          (.setEnabled back (resources/tab-can-go-back? hk)))
        (when-let [fwd (element :forward-button)]
          (.setEnabled fwd (resources/tab-can-go-forward? hk))))
    (do (when-let [back (element :back-button)]
          (.setEnabled back false))
        (when-let [fwd (element :forward-button)]
          (.setEnabled fwd false)))))

(defn- restore-entry!
  "Restore a history entry in the UI. Dispatches on :type."
  [entry]
  (reset! resources/restoring-history? true)
  (try
    (case (:type entry)
      :search
      (do (.setSelection (element :main-folder) 0)
          (.setText (element :search) (:query entry)))

      :content
      (when-let [browser (:browser entry)]
        (when-not (.isDisposed browser)
          (.setText browser (:html entry))))

      :url
      (when-let [browser (:browser entry)]
        (when-not (.isDisposed browser)
          (.setUrl browser (:url entry))))

      nil)
    (finally
      (reset! resources/restoring-history? false)
      (focus-selected-tab-content!))))

(defn- navigate-back! []
  (when-let [hk (active-history-key)]
    (when-let [entry (resources/tab-back! hk)]
      (restore-entry! entry)
      (update-nav-buttons!))))

(defn- navigate-forward! []
  (when-let [hk (active-history-key)]
    (when-let [entry (resources/tab-forward! hk)]
      (restore-entry! entry)
      (update-nav-buttons!))))

(defn header []
  (composite (id! :ui/header-holder)
             (grid/hgrab)
             (grid-layout :num-columns 2)
             (label (id! :ui/header-logo)
                    :image @header-image)

             (composite
              (grid/hgrab)
              (grid-layout)

              (text (| SWT/SEARCH SWT/ICON_CANCEL)
                    (id! :ui/search)
                    (grid/hgrab)
                    (fn [_props parent] (.setData parent "scope" :search-box))

                    (on e/modify-text [props parent event]
                        ;; Force live search tab to view mode if in edit mode
                        (when-let [abs (:abs-path @live-search-state)]
                          (when (= :edit (:mode (get @open-files abs)))
                            (toggle-mode! abs)))
                        (when-not (= 0 (.getSelection (element :main-folder)))
                          (.setSelection (element :main-folder) 0))
                        (let [q (str/trim (.getText (element :search)))]
                          (search/results q
                                          (element :live-search-browser)
                                          set-live-search-content!)
                          ;; Record in history (unless restoring from back/forward)
                          (when (and (not @resources/restoring-history?)
                                     (>= (count q) 3))
                            (resources/tab-push! :live-search {:type :search :query q})
                            (update-nav-buttons!))))
                    (on e/widget-default-selected [props parent event]
                        (let [q    (str/trim (.getText (element :search)))
                              html (.getText (element :live-search-browser))]
                          (when (>= (count q) 3)
                            (open-tab! @statusbar-icon q html))))))))

(defn sidebar []
  (composite SWT/BORDER
             (id! :ui/sidebar-holder)
             (grid/vgrab)
             :layout (FillLayout.)
             (scrolled-composite SWT/VERTICAL
                                 (id! :ui/sidebar)
                                 (grid-layout)
                                 (label "Roots..."))))

(comment
  (:ui/sidebar-holder @app-props)
  (element :sidebar-holder)
  (element :sidebar)
  @statusbar-icon
  :rcf)

(defn- set-live-search-content!
  "Transition the live search tab to new content.
   `home` is a map from `search/home-page` or nil (falls back to empty-page).
   Manages the open-files registry and wrapper child for file vs synthetic mode.
   No-ops when the tab already shows the correct content (prevents flash)."
  [home]
  (let [{:keys [wrapper-id browser-id mode] current-path :abs-path} @live-search-state
        wrapper (get @app-props wrapper-id)
        child   (wrapper-child wrapper)]
    (when (and child (not (.isDisposed child)))
      (cond
        ;; Already showing this file in view mode — nothing to do
        (and (= :file (:mode home))
             (= :file mode)
             (= (:abs-path home) current-path)
             (instance? org.eclipse.swt.browser.Browser child))
        nil

        ;; File mode — register in open-files for edit/refresh support
        (and home (= :file (:mode home)))
        (let [{:keys [abs-path root-uri rel-path html]} home]
          (when current-path (swap! open-files dissoc current-path))
          (swap! open-files assoc abs-path
                 {:tab-ids      #{browser-id}
                  :rel-path     rel-path
                  :abs-path     abs-path
                  :root-uri     root-uri
                  :wrapper-id   wrapper-id
                  :mode         :view
                  :scroll-state (atom nil)})
          (reset! live-search-state
                  {:mode :file :abs-path abs-path :wrapper-id wrapper-id :browser-id browser-id})
          (when (instance? org.eclipse.swt.browser.Browser child)
            (.setText child html)))

        ;; Already in synthetic mode with no home — nothing to do
        (and (nil? home) (= :synthetic mode)
             (instance? org.eclipse.swt.browser.Browser child))
        nil

        ;; Synthetic mode — deregister file and set HTML
        :else
        (let [html (if home (:html home) (search/empty-page))]
          (when current-path (swap! open-files dissoc current-path))
          (reset! live-search-state
                  {:mode :synthetic :wrapper-id wrapper-id :browser-id browser-id})
          (when (instance? org.eclipse.swt.browser.Browser child)
            (.setText child html)))))
    (update-edit-button!)))

(defn body []
  (reset! live-search-state {:mode       :synthetic
                             :wrapper-id :ui/live-search-wrapper
                             :browser-id :ui/live-search-browser})
  (composite SWT/BORDER
             (id! :ui/body-holder)
             (grid/grab-both)
             :layout (FillLayout.)
             (ctab-folder (id! :ui/main-folder)
                          :simple                    false
                          :unselected-close-visible  false

                          (composite (id! :ui/live-search-wrapper)
                                     :layout (FillLayout.)
                                     (custom-browser (id! :ui/live-search-browser)
                                                     :text (search/empty-page)))
                          (ctab-item "Live search"
                                     :image @statusbar-icon
                                     (control :ui/live-search-wrapper))

                          :selection 0)))

(defn tray-item2
  "Define a system tray item.  Must be a child of the application node.  The :image
  and :highlight-image should be 16x16 SWT Image objects.  `on-widget-selected` is
  fired on clicks and `on-menu-detected` to request the right-click menu be displayed."
  [& inits]
  (let [[style
         inits] (i/extract-style-from-args inits)
        style   (nothing->identity SWT/NULL style)]
    (fn [props display]
      (when-let [tray (.getSystemTray display)]
        (let [tray-item (TrayItem. tray style)]
          (doto tray-item
            (. setImage @statusbar-icon)
            (. setHighlightImage @statusbar-icon))

          ;; Expose the TrayItem so the theme-refresh listener can
          ;; update its image on reload. `swap!` into both props (CDT
          ;; local atom) and the global app-props (in case reload fires
          ;; before defmain copies props across).
          (swap! props     assoc :ui/tray-item tray-item)
          (swap! app-props assoc :ui/tray-item tray-item)

          (i/run-inits props tray-item (or inits []))

          (.addListener display SWT/Dispose
                        (reify Listener
                          (handleEvent [_this _event] (.dispose tray-item))))
          tray-item)))))

(defn- register-about-handler!
  "Hook the macOS application 'About Winze' menu item via the system menu."
  []
  (when-let [sys-menu (.getSystemMenu (Display/getDefault))]
    (doseq [item (.getItems sys-menu)]
      (when (= (.getID item) SWT/ID_ABOUT)
        (.addListener item SWT/Selection
                      (reify Listener
                        (handleEvent [_ _event]
                          (try
                            (show-about-dialog! (element :main-window))
                            (catch Throwable t
                              (log/error t "About dialog error"))))))))))

(defn- toggle-visibility! []
  (let [shell (element :main-window)]
    (.setVisible shell (not (.isVisible shell)))
    (when (.isVisible shell)
      (.forceActive shell))))

(defn quit!
  "Initiate a clean application shutdown.
   Safe to call from any thread (e.g. nREPL)."
  []
  (async-exec!
   (fn []
     (swap! app-props assoc :closing true)
     (.close (element :main-window)))))

(defn- show-lang-errors!
  "Show a MessageBox listing .lang validation errors, if any."
  []
  (let [errors @lang-loader/startup-errors]
    (when (seq errors)
      (let [mb (MessageBox. (element :main-window)
                            (| SWT/ICON_WARNING SWT/OK))]
        (.setText mb "Language File Errors")
        (.setMessage mb
                     (str "Some syntax highlighting languages failed to load:\n\n"
                          (str/join "\n" errors)
                          "\n\nAffected languages will use plain text highlighting."))
        (.open mb)))))

(defn- show-theme-errors!
  "Show a MessageBox listing theme.edn validation errors, if any.
  Parented on (element :main-window) — caller must have populated
  @app-props first (PLAN Step 10 invariant 2)."
  [errors]
  (when (seq errors)
    (let [mb (MessageBox. (element :main-window)
                          (| SWT/ICON_WARNING SWT/OK))]
      (.setText mb "Theme File Errors")
      (.setMessage mb
                   (str "Some entries in theme.edn failed to load:\n\n"
                        (str/join "\n" errors)
                        "\n\nAffected entries will use bundled defaults."))
      (.open mb))))

(defn- register-theme-refresh-listeners!
  "Register theme refresh listeners for widgets whose theme-driven
  images or colors need re-applying on `theme/reload-theme!`:

    - main application Shell icon (Dock/taskbar)
    - header logo Label (above the search field)
    - system-tray TrayItem (image + highlight image)
    - open CTabItem icons — file tabs use tab-document-icon,
      live-search + search-result tabs use statusbar-icon. The
      live-search tab is identified by its wrapper Composite being
      the :ui/live-search-wrapper registered at body-build time,
      NOT by presence in open-files (which transiently records the
      live-search wrapper during file-view mode)."
  []
  ;; Main shell icon
  (theme/register-refresh-listener!
   (fn []
     (async-exec!
      (fn []
        (when-let [sh (element :main-window)]
          (when-not (.isDisposed sh)
            (.setImage sh @app-icon)))))))

  ;; Header logo
  (theme/register-refresh-listener!
   (fn []
     (async-exec!
      (fn []
        (when-let [lbl (element :header-logo)]
          (when-not (.isDisposed lbl)
            (.setImage lbl @header-image)))))))

  ;; Tray item
  (theme/register-refresh-listener!
   (fn []
     (async-exec!
      (fn []
        (when-let [ti (element :tray-item)]
          (.setImage          ti @statusbar-icon)
          (.setHighlightImage ti @statusbar-icon))))))

  ;; CTabItem icons
  (theme/register-refresh-listener!
   (fn []
     (async-exec!
      (fn []
        (when-let [folder (element :main-folder)]
          (let [live-wrapper (get @app-props :ui/live-search-wrapper)
                file-tab-wrappers
                (->> (vals @open-files)
                     (keep :wrapper-id)
                     (map #(get @app-props %))
                     (remove #(identical? % live-wrapper))
                     set)]
            (doseq [item (.getItems folder)]
              (when-not (.isDisposed item)
                (let [ctrl (.getControl item)
                      icon (if (contains? file-tab-wrappers ctrl)
                             @tab-document-icon
                             @statusbar-icon)]
                  (.setImage item icon))))))))))

  ;; Live-search Browser — re-render HTML so new palette hex hits CSS.
  (theme/register-refresh-listener!
   (fn []
     (async-exec!
      (fn []
        (when-let [browser (element :live-search-browser)]
          (when-not (.isDisposed browser)
            (let [{:keys [mode abs-path]} @live-search-state
                  {:keys [query]}         (or @resources/last-search-query {})]
              (cond
                (and (= mode :synthetic) (seq query))
                (search/results query browser set-live-search-content!)

                ;; Bypass `set-live-search-content!` here — its same-file
                ;; / same-mode guard would no-op. Push HTML straight to
                ;; the Browser so the fresh palette lands.
                (= mode :synthetic)
                (when-let [home (search/home-page)]
                  (.setText browser (:html home)))

                (and (= mode :file) abs-path)
                (when-let [{:keys [rel-path root-uri]} (get @open-files abs-path)]
                  (try
                    (let [content  (slurp abs-path)
                          metadata (when root-uri
                                     (search/file-metadata-by-path root-uri rel-path))
                          html     (search/file-page content rel-path metadata root-uri)]
                      (.setText browser html))
                    (catch java.io.FileNotFoundException _ nil)))))))))))

  ;; File-view Browsers in other tabs — re-render HTML so palette hex hits CSS.
  (theme/register-refresh-listener!
   (fn []
     (async-exec!
      (fn []
        (doseq [[abs-path {:keys [mode rel-path root-uri wrapper-id]}] @open-files
                :when (= mode :view)
                :let  [wrapper (get @app-props wrapper-id)
                       browser (wrapper-child wrapper)]
                :when (and browser
                           (instance? org.eclipse.swt.browser.Browser browser)
                           (not (.isDisposed browser))
                           ;; Skip live-search wrapper (handled above)
                           (not= wrapper (get @app-props :ui/live-search-wrapper)))]
          (try
            (let [content  (slurp abs-path)
                  metadata (when root-uri
                             (search/file-metadata-by-path root-uri rel-path))
                  html     (search/file-page content rel-path metadata root-uri)]
              (.setText browser html))
            (catch java.io.FileNotFoundException _ nil))))))))

;; ---------------------------------------------------------------------------
;; Command registration
;; ---------------------------------------------------------------------------

(defn active-styled-text
  "Return the StyledText widget for the active file tab in edit mode, or nil."
  []
  (when-let [{:keys [wrapper-id mode]} (active-file-entry)]
    (when (= :edit mode)
      (let [wrapper (get @app-props wrapper-id)
            child   (wrapper-child wrapper)]
        (when (instance? StyledText child) child)))))

(defn active-browser
  "Return the Browser widget for the active tab in view mode, or nil.
  Covers file tabs in view mode AND the live search tab."
  []
  (let [folder (element :main-folder)
        sel    (.getSelection folder)]
    (when sel
      (let [ctrl (.getControl sel)]
        ;; File tab in view mode?
        (or (when-let [{:keys [wrapper-id mode]} (active-file-entry)]
              (when (= :view mode)
                (let [wrapper (get @app-props wrapper-id)
                      child   (wrapper-child wrapper)]
                  (when (instance? org.eclipse.swt.browser.Browser child) child))))
            ;; Live search tab?
            (let [{:keys [wrapper-id]} @live-search-state
                  wrapper (get @app-props wrapper-id)]
              (when (= ctrl wrapper)
                (let [child (wrapper-child wrapper)]
                  (when (instance? org.eclipse.swt.browser.Browser child)
                    child)))))))))

(defn- register-workbench-commands!
  "Register all workbench commands with the command registry."
  []
  ;; Esc — editor scope: switch to view mode
  (commands/register!
   {:id       :workbench/escape-editor
    :label    "Switch to View Mode"
    :category :workbench
    :action   (fn []
                (async-exec!
                 (fn []
                   (when-let [entry (active-file-entry)]
                     (toggle-mode! (:abs-path entry))))))})

  ;; Esc — viewer scope: go to search
  (commands/register!
   {:id       :workbench/escape-viewer
    :label    "Go to Search"
    :category :workbench
    :action   (fn []
                (async-exec!
                 (fn []
                   (.setSelection (element :main-folder) 0)
                   (.setText (element :search) "")
                   (.setFocus (element :search))
                   (resources/tab-clear-history! :live-search)
                   (update-nav-buttons!))))})

  ;; Esc — search-box scope: clear and reset
  (commands/register!
   {:id       :workbench/escape-search
    :label    "Clear Search"
    :category :workbench
    :action   (fn []
                (async-exec!
                 (fn []
                   (.setText (element :search) "")
                   (.setFocus (element :search))
                   (resources/tab-clear-history! :live-search)
                   (update-nav-buttons!))))})

  ;; Esc — global fallback (no specific scope)
  (commands/register!
   {:id       :workbench/escape-global
    :label    "Escape"
    :category :workbench
    :action   (fn []
                (async-exec!
                 (fn []
                   (.setSelection (element :main-folder) 0)
                   (.setText (element :search) "")
                   (.setFocus (element :search))
                   (resources/tab-clear-history! :live-search)
                   (update-nav-buttons!))))})

  ;; Cmd+E — toggle view/edit mode
  (commands/register!
   {:id       :workbench/toggle-mode
    :label    "Toggle View/Edit Mode"
    :category :workbench
    :action   (fn []
                (async-exec!
                 (fn []
                   (when-let [entry (active-file-entry)]
                     (toggle-mode! (:abs-path entry))))))})

  ;; Search history navigation
  (commands/register!
   {:id       :workbench/navigate-back
    :label    "Navigate Back"
    :category :workbench
    :action   (fn [] (async-exec! navigate-back!))})

  (commands/register!
   {:id       :workbench/navigate-forward
    :label    "Navigate Forward"
    :category :workbench
    :action   (fn [] (async-exec! navigate-forward!))})

  ;; Editor undo/redo
  (commands/register!
   {:id       :editor/undo
    :label    "Undo"
    :category :edit
    :action   (fn []
                (async-exec!
                 (fn []
                   (when-let [st (active-styled-text)]
                     (when-let [h (md-editor/editor-history-atom st)]
                       (md-editor/undo! st h)
                       (md-editor/apply-theme! st (.getText st)))))))})

  (commands/register!
   {:id       :editor/redo
    :label    "Redo"
    :category :edit
    :action   (fn []
                (async-exec!
                 (fn []
                   (when-let [st (active-styled-text)]
                     (when-let [h (md-editor/editor-history-atom st)]
                       (md-editor/redo! st h)
                       (md-editor/apply-theme! st (.getText st)))))))})

  ;; Theme management — reload / validate / reset.
  (commands/register!
   {:id       :workbench/reload-theme
    :label    "Reload Theme"
    :category :workbench
    :action   (fn []
                (async-exec!
                 (fn []
                   (let [errors (theme/reload-theme!)]
                     (when (seq errors)
                       (show-theme-errors! errors))))))})

  (commands/register!
   {:id       :workbench/validate-theme
    :label    "Validate Theme"
    :category :workbench
    :action   (fn []
                (async-exec!
                 (fn []
                   (let [{:keys [errors]} (theme/validate-user-file)]
                     (if (seq errors)
                       (show-theme-errors! errors)
                       (let [mb (MessageBox. (element :main-window)
                                             (| SWT/ICON_INFORMATION SWT/OK))]
                         (.setText mb "Theme OK")
                         (.setMessage mb "~/.winze/theme.edn parsed successfully.")
                         (.open mb)))))))})

  (commands/register!
   {:id       :workbench/reset-theme
    :label    "Reset Theme to Default"
    :category :workbench
    :action   (fn []
                (async-exec!
                 (fn []
                   (let [mb (MessageBox. (element :main-window)
                                         (| SWT/ICON_QUESTION SWT/YES SWT/NO))]
                     (.setText mb "Reset Theme?")
                     (.setMessage mb
                                  (str "This deletes ~/.winze/theme.edn and "
                                       "reverts to the bundled default.\n\n"
                                       "Your current theme.edn is NOT backed up. "
                                       "Proceed?"))
                     (when (= SWT/YES (.open mb))
                       (.delete (theme/user-theme-file))
                       (let [errors (theme/reload-theme!)]
                         (when (seq errors)
                           (show-theme-errors! errors))))))))}))

(defn- ensure-user-dirs!
  "Create user customization directories if they don't exist."
  []
  (doseq [subdir ["keybindings" "languages"]]
    (let [d (io/file (System/getProperty "user.home") ".winze" subdir)]
      (when-not (.exists d) (.mkdirs d)))))

(defn- load-and-install-keybindings!
  "Load keybinding files from resources and user directory, install the Display filter."
  []
  (ensure-user-dirs!)
  (let [res-url  (io/resource "keybindings")
        user-dir (str (io/file (System/getProperty "user.home") ".winze" "keybindings"))]
    (if (and res-url (= "file" (.getProtocol res-url)))
      ;; Dev: both dirs in one pass (resources first, user overrides second)
      (keybindings/load-keybindings! (.getPath res-url) user-dir)
      ;; JAR: load known classpath files, then user overrides
      (do (keybindings/load-classpath-keybinding! "keybindings/default.keybinding")
          (keybindings/load-classpath-keybinding! "keybindings/editor.keybinding")
          (keybindings/load-keybindings! user-dir))))
  ;; Intercept Traverse events for Tab/Shift+Tab so they reach the keybinding
  ;; system instead of being consumed by SWT's focus traversal.
  ;; Setting doit=false on the Traverse event also suppresses the subsequent
  ;; KeyDown event for the same physical keypress on macOS/Cocoa.
  (.addFilter @display SWT/Traverse
              (reify Listener
                (handleEvent [_ event]
                  (when (and (= (.keyCode event) (int SWT/TAB))
                             (keybindings/dispatch-key! event))
                    (set! (.-detail event) SWT/TRAVERSE_NONE)
                    (set! (.-doit event) false)))))
  ;; Main keybinding dispatch for non-traverse keys.
  ;; Tab/Shift+Tab are dispatched by the Traverse filter above; the KeyDown
  ;; filter suppresses the Tab KeyDown (prevents character insertion) but
  ;; does not re-dispatch.
  (.addFilter @display SWT/KeyDown
              (reify Listener
                (handleEvent [_ event]
                  (if (= (.keyCode event) (int SWT/TAB))
                    ;; Suppress Tab KeyDown only in editor scope (Traverse filter
                    ;; already dispatched indent/outdent). In other scopes, let
                    ;; Tab through for normal focus traversal.
                    (when (= :editor (keybindings/active-scope))
                      (set! (.-doit event) false))
                    (when (keybindings/dispatch-key! event)
                      (set! (.-doit event) false)))))))

(defn main-window
  "Main application window.
  Returns nil on normal shutdown. CDT's `application` silently catches all
  Throwables and returns them — we log here so UI init failures aren't lost."
  []
  (register-about-handler!)

  ;; *** Populate theme registry BEFORE `(application …)` is evaluated. ***
  ;; `(shell :image @app-icon …)` and `(label :image @header-image)` force
  ;; their derefs as eager args — waiting until a first-init-form is too
  ;; late (theme-externalize-edn CONTEXT §"Consumers" #3).
  ;;
  ;; INVARIANT — DO NOT MOVE `apply-theme-startup!` AFTER `(application …)`.
  ;; @display is populated by server/main.clj before main-window runs.
  ;; If a future edit breaks that order and @display is nil, the
  ;; Color. construction throws — that loud crash is the intended signal
  ;; to fix the @display init in server/main.clj, NOT to defer this call.
  (let [{startup-theme-errors :errors} (theme/apply-theme-startup! @display)
        result (application
                (tray-item2
                ;; System tray right-click handler
                 (on e/menu-detected [props parent event] (.setVisible (element :tray-menu) true))

                ;; System tray click handler toggles visibility
                 (on e/widget-selected [props parent event] (toggle-visibility!)))

                (shell SWT/SHELL_TRIM (id! :ui/main-window)
                       :text app-name
                       :image @app-icon

                       (grid-layout :num-columns 1)

                       (header)
                       (body)
                       #_(sash-form SWT/BORDER
                                    (grid/grab-both)
                                    (sidebar)
                                    (body)
                                    :weights [30 70])

                       (on e/shell-closed [props parent event]
                           ;; Check the global app-props — quit! writes :closing there,
                           ;; not to CDT's local props (they're separate atoms).
                           (if (:closing @app-props)
                             (async-exec! (fn []
                                            (resources/dispose-registry!)
                                            (.dispose @display)))
                             (do
                               (set! (. event doit) false)
                               (.setVisible parent false))))

                       (menu SWT/POP_UP (id! :ui/tray-menu)
                             (menu-item SWT/PUSH "&Toggle visibility"
                                        (on e/widget-selected [props parent event]
                                            (toggle-visibility!)))
                             (menu-item SWT/PUSH "Open &welcome page"
                                        (on e/widget-selected [props parent event]
                                            (open-welcome-tab!)))
                             (menu-item SWT/PUSH "&About..."
                                        (on e/widget-selected [props parent event]
                                            (try
                                              (show-about-dialog! (element :main-window))
                                              (catch Throwable t
                                                (log/error t "About dialog error")))))
                             (menu-item SWT/SEPARATOR)
                             (menu-item SWT/PUSH "&Quit"
                                        (on e/widget-selected [props parent event]
                                            (quit!)))))
                (defmain [props parent]
                  (reset! app-props @props)
                  (setup-edit-toolbar!)
                  (watcher/add-change-listener! on-file-changed)
                  (core/add-root-listener! on-root-changed)
                 ;; Load home page on background thread, push to UI when ready.
                 ;; On first launch with zero roots, also auto-open the Welcome tab.
                  (future
                    (try
                      (let [home  (search/home-page)
                            roots (core/list-roots (server/store))]
                        (async-exec!
                         (fn []
                           (set-live-search-content! home)
                           (when (empty? roots)
                             (open-welcome-tab!)
                             ;; open-welcome-tab! dispatches async-exec! internally;
                             ;; a second frame lets the tab register before selecting.
                             (async-exec!
                              (fn []
                                (when-let [tab (element :welcome-tab)]
                                  (.setSelection (element :main-folder) tab))))))))
                      (catch Throwable t
                        (log/error t "Failed to load home page"))))
                 ;; Register commands and install scoped keybinding dispatch
                  (register-workbench-commands!)
                  (editor-commands/set-active-styled-text-fn! active-styled-text)
                  (editor-commands/register-all!)
                  (palette/register-palette-commands!)
                  (register-theme-refresh-listeners!)
                  (load-and-install-keybindings!)
                 ;; Wire editor link navigation for MOD1-click
                  (reset! md-editor/navigate-link-fn
                          (fn [dest _abs-path]
                            (let [params (parse-query-string
                                          (if (str/starts-with? dest "winze:open-file?")
                                            (subs dest (count "winze:open-file?"))
                                            dest))]
                              (when-let [root-uri (get params "root")]
                                (when-let [rel-path (get params "path")]
                                  (open-file-in-tab! root-uri rel-path))))))
                 ;; Show language file validation errors (non-blocking)
                  (show-lang-errors!)
                 ;; Show theme validation errors collected at startup
                 ;; (MessageBox parents on :main-window, which reads from
                 ;; @app-props — populated above).
                  (show-theme-errors! startup-theme-errors)
                 ;; Close find bar when the app truly loses focus (not just to the find bar itself).
                 ;; Deferred via async-exec! so the find bar Shell has time to receive focus.
                  (.addShellListener (element :main-window)
                                     (proxy [org.eclipse.swt.events.ShellAdapter] []
                                       (shellDeactivated [_e]
                                         (async-exec!
                                          (fn []
                                            (when (find-replace/find-bar-open?)
                                              (let [focus (.getFocusControl (Display/getCurrent))
                                                    fb-sh (find-replace/find-bar-shell)]
                                                (when-not (and focus fb-sh
                                                               (= (.getShell focus) fb-sh))
                                                  (find-replace/close-find-bar!)))))))))))]
    (when (instance? Throwable result)
      (log/error ^Throwable result "UI crashed during initialization"))))

(comment
  #_(ui (screenshot-widget! sh-371 "/tmp/snippet-371.png"))

  :rcf)
