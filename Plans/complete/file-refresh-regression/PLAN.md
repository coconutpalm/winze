---
created: 2026-03-31
related: [file-refresh, styledtext-editor, view-edit-scroll-sync]
tags: [regression, swt-threading, watcher]
---

# File Refresh Watcher Regression — Plan

## Strategy

Move all SWT widget access in `refresh-open-tabs!` inside `async-exec!`. Keep
file I/O (`slurp`) and search/render work on the caller thread to avoid blocking
the UI. Apply the same fix to `refresh-live-search!` for correctness.

## Step 1: Fix `refresh-open-tabs!`

**File**: `winze-server/src/llm_memory/ui/main_window.clj`

Restructure so that `slurp` and metadata lookup happen on the caller thread,
then all widget inspection and mutation happens inside a single `async-exec!`:

```clojure
(defn- refresh-open-tabs!
  "Re-read the file and update all tabs showing abs-path.
  In edit mode: skip if content matches (own save) or editor is dirty.
  In view mode: re-render HTML."
  [abs-path]
  (when-let [{:keys [wrapper-id rel-path root-uri dirty?]}
             (get @open-files abs-path)]
    (try
      (let [new-content (slurp abs-path)
            metadata    (when root-uri
                          (search/file-metadata-by-path root-uri rel-path))
            html        (search/file-page new-content rel-path metadata)]
        (async-exec!
         (fn []
           (let [wrapper (get @app-props wrapper-id)
                 child   (wrapper-child wrapper)]
             (when (and child (not (.isDisposed child)))
               (cond
                 ;; Edit mode — skip if content matches or editor is dirty
                 (instance? StyledText child)
                 (when-not (or (= new-content (.getText child))
                               @dirty?)
                   (.setText child new-content)
                   (md-editor/apply-theme! child new-content))

                 ;; View mode — re-render HTML and update tab title
                 (instance? org.eclipse.swt.browser.Browser child)
                 (do (refresh-browser-with-scroll! child html)
                     (update-tab-title! abs-path new-content))))))))
      (catch java.io.FileNotFoundException _ nil))))
```

**Key changes**:
- `slurp`, `file-metadata-by-path`, and `file-page` run on the caller (scheduler)
  thread — no SWT access, just I/O and string rendering
- `wrapper-child`, `.isDisposed`, `.getText`, `.setText`,
  `refresh-browser-with-scroll!`, and `update-tab-title!` all move inside
  `async-exec!`
- HTML is always pre-rendered even in edit mode (wasted work for the edit-mode
  branch, but trivial cost vs. the alternative of two separate `async-exec!`
  paths)

## Step 2: Fix `refresh-live-search!`

**File**: `winze-server/src/llm_memory/ui/main_window.clj`

Move the `.isDisposed` guard inside `async-exec!`:

```clojure
(defn- refresh-live-search!
  "If the live search tab shows synthetic content (search results or multi-root home),
   re-run the query or re-build the home page to pick up file changes."
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
```

**Key change**: `(get @app-props browser-id)` and `.isDisposed` move inside
`async-exec!`. The atom deref is safe from any thread, but `.isDisposed` should
be on the UI thread for correctness.

## Step 3: REPL verification

1. Load the patched functions via `load-file` (NOT `:reload-all`)
2. Open a file tab in Winze
3. Edit the file externally (e.g. `echo "test" >> file.md`)
4. Verify the file tab updates within ~1s
5. Verify search result cards also update (if search results are showing)
6. Toggle to edit mode (Cmd+E), edit externally again — verify edit mode
   skips refresh when dirty, applies when clean

## Step 4: `make install` and restart

1. `make install` from `winze-server/` to rebuild the JAR
2. Restart the server to pick up changes
3. Re-verify end-to-end

## Exit Criteria

- External file modification refreshes open file tabs (view and edit mode)
- Search result cards refresh when files change
- No `SWTException` in watcher logs
- `:delete` and `:rename` continue to work as before
- All existing tests pass (`make test` in `clj-llm-memory/`)
