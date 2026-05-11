---
created: 2026-03-30
related: [TAB-REUSE-CONTEXT.md]
tags: [swt, ui, tabs, file-viewer, search]
---

# Tab Reuse on File Open — Plan

## Step 1 — Add `find-existing-tab` helper

**File**: `main_window.clj`

Add a function that looks up an `open-files` entry by `abs-path` and returns the
corresponding CTabItem, or nil if the file is not open (or its widgets are disposed):

```clojure
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
```

## Step 2 — Guard the `winze:open-file?` handler

**File**: `main_window.clj`

In the `winze:open-file?` branch of `custom-browser` (lines 63–76), resolve
`abs-path` first, then check for an existing tab before doing any file I/O:

```clojure
(str/starts-with? loc "winze:open-file?")
(do (set! (.-doit event) false)
    (let [params   (parse-query-string (subs loc (count "winze:open-file?")))
          root-uri (get params "root")
          rel-path (get params "path")
          abs-path (search/resolve-file-path root-uri rel-path)]
      (if-let [tab (find-existing-tab abs-path)]
        ;; Switch to existing tab
        (async-exec!
         (fn []
           (.setSelection (element :main-folder) tab)
           (update-edit-button!)))
        ;; Open new tab
        (future
          (try
            (let [content  (slurp abs-path)
                  metadata (search/file-metadata-by-path root-uri rel-path)
                  html     (search/file-page content rel-path metadata)
                  filename (last (str/split rel-path #"/"))]
              (async-exec! #(open-tab! @tab-document-icon filename html rel-path abs-path rel-path root-uri)))
            (catch Throwable t
              (log/error t "Failed to open file" rel-path)))))))
```

Key changes:
- `resolve-file-path` moves outside the `future` (it's a lightweight string operation)
- `find-existing-tab` runs on the UI thread (before `future` dispatch)
- Only the "new tab" path does file I/O in a `future`

Note: `find-existing-tab` calls `.getItems`/`.getControl`/`.isDisposed` which are
SWT widget methods — they must run on the UI thread. The `custom-browser` location
change handler already runs on the UI thread, so this is safe.

## Step 3 — Verify

1. Open a file from search results → new tab opens (existing behavior)
2. Click the same file path in another search result → switches to existing tab
3. Close the tab, click the file again → opens a new tab
4. Open a file, edit it (Cmd+E), click it from search → switches to the edit-mode tab
5. Verify `update-edit-button!` updates correctly after switching
