---
created: 2026-03-31
related: [file-refresh, styledtext-editor, view-edit-scroll-sync]
tags: [regression, swt-threading, watcher]
---

# File Refresh Watcher Regression — Context

## Symptom

When a watched `.md` file changes on disk, neither open file tabs nor live search
result cards update. The watcher indexes the file correctly (search picks up
changes), but the UI refresh silently fails.

## Root Cause

`refresh-open-tabs!` calls `wrapper-child` which calls `.getChildren` on a
`Composite` widget. This is a thread-guarded SWT operation that **must** run on
the UI thread. The watcher callback (`on-file-changed`) runs on a
`ScheduledExecutorService` thread, so `.getChildren` throws:

```
SWTException: Invalid thread access
```

The exception propagates through `on-file-changed`, which is structured as:

```clojure
:modify (do (refresh-open-tabs! abs-path)
            (refresh-live-search!))
```

Because `refresh-open-tabs!` throws before `refresh-live-search!` executes,
**both** file tab refresh **and** search result card refresh are broken for
`:modify` events on files that have open tabs.

### Why `:delete` and `:rename` still work

`close-open-tabs!` and `rename-open-tabs!` correctly wrap all SWT access inside
`async-exec!`. Only `refresh-open-tabs!` accesses widgets outside the UI thread.

### Why `:modify` works when no tabs are open

`refresh-open-tabs!` returns `nil` immediately (the `when-let` on `open-files`
fails), so `refresh-live-search!` runs normally. This explains why search results
refresh fine until a file tab is opened.

## When It Broke

The regression was introduced by commit `f092d12` ("Refactor file tabs to wrapper
Composite; add toolbar edit button"). This refactor changed file tabs from a
direct Browser widget to a `Composite` wrapper containing either a Browser (view
mode) or StyledText (edit mode).

### Before (working)

```clojure
;; open-files: {abs-path -> {:tab-ids #{:ui/tab-browser-N}, :rel-path str}}
;; tab-ids point directly to Browser widgets in app-props

(defn- refresh-open-tabs! [abs-path]
  (when-let [{:keys [tab-ids rel-path]} (get @open-files abs-path)]
    (let [content (slurp abs-path)           ;; OK: I/O on scheduler thread
          html    (search/file-page ...)]
      (async-exec!                            ;; All SWT access on UI thread
       (fn []
         (doseq [tid tab-ids]
           (when-let [brow (get @app-props tid)]  ;; atom deref — safe
             (when-not (.isDisposed brow)
               (refresh-browser-with-scroll! brow html)))))))))
```

### After (broken)

```clojure
;; open-files: {abs-path -> {:wrapper-id :ui/tab-N, ...}}
;; wrapper-id points to a Composite in app-props

(defn- refresh-open-tabs! [abs-path]
  (when-let [{:keys [wrapper-id ...]} (get @open-files abs-path)]
    (let [wrapper (get @app-props wrapper-id)     ;; atom deref — safe
          child   (wrapper-child wrapper)]         ;; ← CALLS .getChildren — THROWS
      (when (and child (not (.isDisposed child)))
        (let [new-content (slurp abs-path)]
          (cond
            (instance? StyledText child) ...
            (instance? Browser child)
            (async-exec! ...)))))))                ;; SWT mutation is inside async-exec!
```

The key difference: the old code only accessed SWT widgets inside `async-exec!`.
The new code calls `wrapper-child` (which calls `.getChildren`) **before**
`async-exec!`, on the scheduler thread.

## Secondary Issue: `refresh-live-search!`

`refresh-live-search!` also calls `.isDisposed` on a Browser from the scheduler
thread (line 303). Although this incidentally works on macOS, **`.isDisposed` is
an SWT widget method and must follow the UI thread rule on all platforms**. This
is a real bug, not just a style issue — it will break on other SWT platforms
(Linux/GTK, Windows) that enforce thread access more strictly.

## Verified via REPL

```clojure
;; From nREPL (non-UI thread):
(refresh-open-tabs! "/Users/dorme/code/_finance/winze/Plans/dev/DAVE.md")
;; => SWTException: Invalid thread access

;; Pinpointed:
(.isDisposed wrapper)   ;; => false (works from any thread)
(.getChildren wrapper)  ;; => SWTException: Invalid thread access
```

## Affected Functions

| Function | Issue | Severity |
|---|---|---|
| `refresh-open-tabs!` | `wrapper-child` calls `.getChildren` off UI thread | **Blocking** — breaks all `:modify` refresh |
| `refresh-live-search!` | `.isDisposed` off UI thread | **Bug** — works on macOS incidentally, breaks on GTK/Win32 |
| `close-open-tabs!` | All SWT in `async-exec!` | **OK** |
| `rename-open-tabs!` | All SWT in `async-exec!` | **OK** |
