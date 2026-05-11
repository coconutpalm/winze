---
created: 2026-03-27
related: [file-refresh]
tags: [swt, beholder, file-watcher, tabs]
---

# File Viewer Header Doesn't Update on Rename — Plan

## Step 1: Add JS header update to `rename-open-tabs!`

**File**: `winze-server/src/llm_memory/ui/main_window.clj`

Inside the `async-exec!` doseq in `rename-open-tabs!`, after `.setToolTipText`,
add a `Browser.execute()` call that patches the `.header` div in the live page:

```clojure
(defn- rename-open-tabs!
  "Update open-files registry and tab titles/tooltips for a renamed file."
  [old-path new-path]
  (when-let [{:keys [tab-ids rel-path]} (get @open-files old-path)]
    (let [plans-prefix (str/replace old-path (str "/" rel-path) "")
          new-rel-path (str/replace new-path (str plans-prefix "/") "")
          new-filename (last (str/split new-rel-path #"/"))
          ;; Escape path for JS single-quoted string literal
          js-path      (str/replace new-rel-path "'" "\\'")
          header-js    (str "var h=document.querySelector('.header');"
                            "if(h)h.textContent='" js-path "';")]
      (swap! open-files ...)
      (async-exec!
       (fn []
         (let [folder (element :main-folder)]
           (doseq [item (.getItems folder)]
             (let [ctrl (.getControl item)]
               (when (and ctrl
                          (not (.isDisposed ctrl))
                          (tab-ids (tab-id-for-ctrl ctrl)))
                 (.setText item (word-wrap 30 new-filename))
                 (.setToolTipText item new-rel-path)
                 (.execute ctrl header-js))))))))))  ; <-- new line
```

**Verify in REPL**:
1. Open a file tab, note the header path
2. Rename the file on disk
3. Confirm tab title updates AND header text updates to the new path
4. Take before/after screenshots

## Step 2: Screenshot verification

Capture before (old path in header) and after (new path in header) screenshots
per SWT-UI-GUIDE rule 14.

## Exit criteria

- Tab title updated to new filename ✓ (already working)
- Tab tooltip updated to new rel-path ✓ (already working)
- Page header `<div class="header">` updated to new rel-path ✓ (this fix)
- No page reload, scroll position unchanged
- Paths containing `'` are safely escaped (no JS injection)
