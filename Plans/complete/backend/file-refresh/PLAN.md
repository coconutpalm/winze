---
created: 2026-03-27
related: [file-viewer, datalevin-migration]
tags: [swt, beholder, file-watcher, tabs]
---

# File Tab Auto-Refresh on Change — Plan

Refresh file-viewer tab contents when the underlying file changes on disk.

## Step 1: Add Change Listener Hook to `watcher.clj`

**File**: `clj-llm-memory/src/llm_memory/watcher.clj`

Add a callback registry so external consumers can subscribe to file change events:

```clojure
(defonce ^:private change-listeners (atom []))

(defn add-change-listener!
  "Register a callback (fn [root-uri abs-path event-type]).
   Called after debounce, on the scheduler thread."
  [listener-fn]
  (swap! change-listeners conj listener-fn))

(defn remove-change-listener!
  "Remove a previously registered callback."
  [listener-fn]
  (swap! change-listeners (fn [ls] (vec (remove #{listener-fn} ls)))))
```

Add a private helper to fire listeners:

```clojure
(defn- notify-listeners!
  "Notify all registered change listeners. Catches and logs errors per listener."
  [root-name root-uri abs-path event-type & [extra]]
  (doseq [l @change-listeners]
    (try (if extra
           (l root-uri abs-path event-type extra)
           (l root-uri abs-path event-type))
         (catch Throwable t
           (log root-name "WARN" "change listener error:" (.getMessage t))))))
```

Call `notify-listeners!` in three places:

1. **`handle-create-or-modify!`** — after the index operation, call with `:create` or `:modify`.
2. **Delete retraction** (the scheduled cleanup in `handle-delete!`) — after `retract-file-by-id!`, call with `:delete`.
3. **Rename detection** (in `handle-create-or-modify!` when `match-rename` succeeds) — call with `:rename` and extra `{:old-path old-abs :new-path abs-path}`.

**RCF test**: Register a listener, create/modify/rename a file, assert the listener was called with correct args and event types.

**Verify**: `(require '[llm-memory.watcher] :reload)` — existing watcher tests still pass.

## Step 2: Track Open Files in `main_window.clj`

**File**: `winze-server/src/llm_memory/ui/main_window.clj`

Add an atom mapping absolute file paths to tab metadata:

```clojure
(defonce ^:private open-files (atom {}))
;; {"/abs/path/to/file.md" -> #{:ui/tab-browser-1}}
```

Use a set of tab-ids per path to support multiple tabs showing the same file.

## Step 3: Populate `open-files` on Tab Open/Close

**File**: `winze-server/src/llm_memory/ui/main_window.clj`

In the `winze:open-file?` handler within `custom-browser`:

- After `open-tab!` succeeds, add the `{abs-path -> tab-id}` entry to `open-files`
- In the `e/widget-disposed` handler (already exists), remove the tab-id from `open-files`

This requires `open-tab!` to accept and return the tab-id, or to accept the abs-path and register itself. Simplest: pass the abs-path into `open-tab!` and have it manage registration internally.

Update `open-tab!` signature:

```clojure
(defn open-tab!
  ([icon title html] (open-tab! icon title html title nil))
  ([icon title html tooltip] (open-tab! icon title html tooltip nil))
  ([icon title html tooltip abs-path]
   (let [folder (element :main-folder)
         tab-id (next-tab-id!)]
     ;; Register in open-files if this is a file-backed tab
     (when abs-path
       (swap! open-files update abs-path (fnil conj #{}) tab-id))
     (child-of folder app-props
               (defchildren
                 (custom-browser (id! tab-id)
                                 :text html
                                 (on e/widget-disposed [props parent event]
                                     (swap! app-props dissoc tab-id)
                                     (when abs-path
                                       (swap! open-files update abs-path disj tab-id)
                                       (swap! open-files (fn [m] (if (empty? (get m abs-path))
                                                                   (dissoc m abs-path) m))))))
                 (ctab-item SWT/CLOSE (word-wrap 30 title)
                            :image icon
                            :tool-tip-text tooltip
                            (control tab-id))))
     (.setSelection folder (dec (.getItemCount folder))))))
```

The `winze:open-file?` handler passes `abs-path` as the final argument.

**Verify**: Open a file tab, check `@open-files` in the REPL. Close the tab, verify the entry is removed.

## Step 4: Register UI Change Listener on Startup

**File**: `winze-server/src/llm_memory/ui/main_window.clj`

Register a watcher change listener that handles `:modify`, `:delete`, and `:rename` events:

```clojure
(defn- on-file-changed
  "Watcher callback: refresh, close, or rename open file tabs."
  ([root-uri abs-path event-type]
   (on-file-changed root-uri abs-path event-type nil))
  ([root-uri abs-path event-type extra]
   (case event-type
     :modify  (refresh-open-tabs! root-uri abs-path)
     :delete  (close-open-tabs! abs-path)
     :rename  (rename-open-tabs! (:old-path extra) (:new-path extra))
     nil)))
```

### `refresh-open-tabs!` — Modify handler

```clojure
(defn- refresh-open-tabs!
  "Re-read the file, render HTML, and update all tabs showing it."
  [root-uri abs-path]
  (when-let [tab-ids (seq (get @open-files abs-path))]
    (try
      (let [rel-path (derive-rel-path root-uri abs-path)
            content  (slurp abs-path)
            html     (search/file-page content rel-path)]
        (async-exec!
         (fn []
           (doseq [tid tab-ids]
             (when-let [brow (get @app-props tid)]
               (when-not (.isDisposed brow)
                 (refresh-browser-with-scroll! brow html)))))))
      (catch java.io.FileNotFoundException _ nil))))
```

### `close-open-tabs!` — Delete handler

```clojure
(defn- close-open-tabs!
  "Close all tabs displaying the deleted file."
  [abs-path]
  (when-let [tab-ids (seq (get @open-files abs-path))]
    (async-exec!
     (fn []
       (let [folder (element :main-folder)]
         (doseq [item (.getItems folder)]
           (let [ctrl (.getControl item)]
             (when (and ctrl (tab-ids (some (fn [[k v]] (when (= v ctrl) k)) @app-props)))
               (.dispose item)))))))))
```

Disposing the `CTabItem` triggers `widget-disposed` on the browser, which cleans up `app-props` and `open-files`.

### `rename-open-tabs!` — Rename handler

```clojure
(defn- rename-open-tabs!
  "Update open-files registry and tab titles/tooltips for a renamed file."
  [old-path new-path]
  (when-let [tab-ids (get @open-files old-path)]
    ;; Re-key in open-files: old-path -> new-path
    (swap! open-files (fn [m]
                        (-> m
                            (dissoc old-path)
                            (update new-path (fnil into #{}) tab-ids))))
    ;; Update tab titles and tooltips on UI thread
    (let [new-filename (last (str/split new-path #"/"))
          new-rel-path (derive-rel-path-from-abs new-path)]
      (async-exec!
       (fn []
         (let [folder (element :main-folder)]
           (doseq [item (.getItems folder)]
             (let [ctrl (.getControl item)]
               (when (and ctrl (tab-ids (some (fn [[k v]] (when (= v ctrl) k)) @app-props)))
                 (.setText item (word-wrap 30 new-filename))
                 (.setToolTipText item new-rel-path))))))))))
```

Register during `main-window` initialization:

```clojure
(watcher/add-change-listener! on-file-changed)
```

**Threading**: The callback fires on the Beholder scheduler thread. File I/O (`slurp`, markdown rendering) happens there. Only widget mutations go through `async-exec!`. This is correct per SWT-UI-GUIDE rules 1–2.

**Verify**: Open a file tab, edit the file externally, confirm the tab refreshes within ~1 second (500ms debounce + render time).

## Step 5: Scroll Position Preservation

**File**: `winze-server/src/llm_memory/ui/main_window.clj`

Add a helper that saves scroll position, replaces content, and restores scroll:

```clojure
(defn- refresh-browser-with-scroll!
  "Replace browser HTML while preserving scroll position.
   Must be called on the UI thread."
  [browser html]
  (let [scroll-top (or (.evaluate browser "return document.documentElement.scrollTop;") 0.0)]
    (.setText browser html)
    (.addProgressListener browser
      (proxy [org.eclipse.swt.browser.ProgressAdapter] []
        (completed [_event]
          (.removeProgressListener browser this)
          (.execute browser
            (str "window.scrollTo(0, Math.min("
                 (long scroll-top)
                 ", document.documentElement.scrollHeight - window.innerHeight));")))))))
```

**How it works:**

1. `Browser.evaluate()` executes JS synchronously and returns the current `scrollTop` as a `Double`.
2. `.setText` replaces the page content. WebKit renders asynchronously — layout is not yet done.
3. A one-shot `ProgressListener` fires when the new page finishes loading (`completed` event).
4. Inside `completed`, JavaScript scrolls to `Math.min(savedTop, maxScroll)` — clamping to the end if the new content is shorter than the saved position.
5. The listener removes itself immediately to avoid firing on subsequent navigations.

**Verify**: Open a long file, scroll partway down, edit the file externally. Confirm the tab refreshes and the scroll position is preserved. Then edit the file to be much shorter and confirm it scrolls to the end.

## Step 6: Guard Against Disposed Widgets

All `async-exec!` callbacks must check `.isDisposed` on both the Browser and any CTabItem before mutating them. The tab may have been closed between when the `async-exec!` was queued and when it executes.

## Step 7: Screenshot Verification

Open a file in a tab, modify the file externally, take a before/after screenshot to verify:
1. Tab content updates
2. Scroll position is preserved
3. Deleting the file closes the tab
4. Renaming the file updates the tab title

## Exit Criteria

- Modifying a `.md` file that is open in a tab causes the tab to re-render within ~1 second
- Scroll position is preserved across refreshes; clamped to end-of-document if content shortened
- Deleting a file closes all tabs displaying it
- Renaming a file updates tab titles/tooltips and continues tracking the new path
- Closing a tab removes it from the open-files registry (no leaks)
- Opening the same file in multiple tabs refreshes all of them
- The existing watcher behavior (search index updates) is unaffected
- All existing RCF tests pass (`make test` in `clj-llm-memory`)
