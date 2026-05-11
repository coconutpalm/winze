---
created: 2026-03-27
related: [file-viewer, datalevin-migration]
tags: [swt, beholder, file-watcher, tabs]
---

# File Tab Auto-Refresh on Change — Context

## Implementation Status (2026-03-27)

**All code is written, tested, and installed.** End-to-end verification confirmed auto-refresh works.

### What was implemented

**`clj-llm-memory/src/llm_memory/watcher.clj`** — Change listener hook:
- `(defonce ^:private change-listeners (atom []))` — registry atom
- `add-change-listener! [listener-fn]` — public API, appends to registry
- `remove-change-listener! [listener-fn]` — public API, removes from registry
- `notify-listeners! [root-name root-uri abs-path event-type extra]` — private, fires all listeners
- `handle-create-or-modify!` now calls `notify-listeners!` for `:create`, `:modify`, and `:rename` (with `{:old-path, :new-path}` extra)
- Delete retraction in `handle-delete!` calls `notify-listeners!` for `:delete`
- Listener signature: `(fn [root-uri abs-path event-type extra])` — always 4 args; `extra` is nil except for `:rename`
- New RCF test: `"watcher — change listeners notified on create and modify"` — passes

**`winze-server/src/llm_memory/ui/main_window.clj`** — Tab tracking and refresh:
- `(defonce ^:private open-files (atom {}))` — tracks `{abs-path -> {:tab-ids #{kw}, :rel-path str}}`
- `open-tab!` updated: new 6-arity `[icon title html tooltip abs-path rel-path]`; manages `open-files` on create and `e/widget-disposed`
- `winze:open-file?` handler now calls `(open-tab! icon filename html rel-path abs-path rel-path)`
- `refresh-browser-with-scroll! [browser html]` — saves scrollTop, calls `.setText`, restores via `ProgressAdapter.completed`
- `refresh-open-tabs! [abs-path]` — re-slurps and re-renders on `:modify`
- `tab-id-for-ctrl [ctrl]` — helper to look up tab-id keyword from a Browser widget
- `close-open-tabs! [abs-path]` — disposes CTabItems on `:delete`
- `rename-open-tabs! [old-path new-path]` — re-keys `open-files`, updates tab titles/tooltips on `:rename`; derives new rel-path from stored old rel-path + plans prefix
- `on-file-changed [_root-uri abs-path event-type extra]` — dispatches to above; registered via `(watcher/add-change-listener! on-file-changed)` in `defmain`

### Design decisions made

- **Listener signature**: Always 4 args `[root-uri abs-path event-type extra]`; `extra` is nil for non-rename events. Simpler than variadic.
- **Registry structure**: `{abs-path -> {:tab-ids #{}, :rel-path str}}` — storing `rel-path` avoids needing a store query to derive it during refresh.
- **New rel-path on rename**: Derived from stored old rel-path: `(str/replace new-path (str plans-prefix "/") "")` where `plans-prefix = (str/replace old-path (str "/" old-rel-path) "")`. No store query needed.
- **Tab lookup**: `tab-id-for-ctrl` iterates `@app-props` to find the tab-id for a given Browser control.

### Build/deploy notes

- `make test` in `clj-llm-memory/` — all 68 tests pass including new listener test
- `make install` in `clj-llm-memory/` — installed as `io.github.dorme/clj-llm-memory 0.1.37`
- `make install` in `winze-server/` — built and installed new JAR to `~/.local/share/winze/lib/winze-server.jar`
- Server restarted and running on nREPL port 51145

### Verified working

- File tab opened, registered in `open-files`: `{abs-path -> {:tab-ids #{:ui/tab-browser-1}, :rel-path "dev/..."}}`
- Modified `FILE-REFRESH-CONTEXT.md` externally; tab refreshed within ~1s showing updated content including new "AUTO-REFRESH TEST" heading at the bottom
- Scroll position preservation: `refresh-browser-with-scroll!` captures `scrollTop` before `.setText`, restores via `ProgressAdapter.completed` one-shot listener

### Delete verification ✓

- Created `REFRESH-TEST-DELETE.md`, opened as tab `:ui/tab-browser-2`
- Deleted file → tab closed within ~2s, `open-files` cleaned to `{}`
- **Bug found and fixed**: initial `close-open-tabs!` called `.dispose item` (CTabItem) but NOT the Browser control. SWT does not cascade disposal from CTabItem to its control when called programmatically (only via user X-click). Fixed by calling `(.dispose ctrl)` before `(.dispose item)` — this fires `e/widget-disposed` on the Browser, which cleans up `app-props` and `open-files`.

### Rename verification ✓

- Created `RENAME-VERIFY-BEFORE.md`, opened as tab
- Renamed to `RENAME-VERIFY-AFTER.md` → tab title updated instantly (before: `RENAME-VERIFY-BEFORE.md`, after: `RENAME-VERIFY-AFTER.md`)
- Content correctly NOT re-rendered (file contents unchanged, per spec)
- `open-files` re-keyed from old path to new path

### All exit criteria met ✓

- Modify → tab re-renders within ~1s ✓
- Scroll position preserved across refreshes ✓
- Delete → tab closes, no resource leak ✓
- Rename → tab title/tooltip updates, content preserved ✓
- Closing tab removes from registry ✓
- All watcher tests pass (`make test` in `clj-llm-memory`) ✓

## Problem

When a file is open in a Winze file-viewer tab and an external editor modifies the file on disk, the tab continues displaying stale content. The user must close and reopen the tab to see updated content.

## Current Architecture

### File Viewer Tabs

File viewer tabs are created by `open-tab!` in `main_window.clj`. The flow:

1. User clicks a file link in search results (`winze:open-file?root=...&path=...`)
2. `custom-browser`'s `e/changing` handler intercepts the navigation
3. A `future` resolves the absolute path, `slurp`s the file, renders markdown to HTML
4. `async-exec!` calls `open-tab!` with the rendered HTML

**No state tracks which file is displayed in which tab.** The absolute path is used transiently during tab creation and discarded. The tab title is the filename; the tooltip is the relative path. The `app-props` atom maps `tab-id` keywords (`:ui/tab-browser-1`, etc.) to Browser widgets, but nothing maps tab-ids to file paths.

### Beholder Filesystem Watcher (`llm-memory.watcher`)

The existing watcher (Beholder 1.0.2) already monitors each registered root's Plans directory for `.md` file changes:

- **Events**: `:create`, `:modify`, `:delete`
- **Debounce**: 500ms coalescing window via `ScheduledExecutorService`
- **Filtering**: `.md` files only; ignores `INDEX.md`, `STATUS.md`, dotfiles, temp files
- **Handler**: Dispatches to `index-file!` / `retract-file!` / `rename-file!` for search index updates
- **State**: `watchers` atom `{root-uri -> {:watcher handle :root-name str}}`

The watcher runs in the same JVM as the UI (winze-server). It currently has no callback/notification mechanism for other consumers — it directly calls indexing functions.

### Key Files

| File | Role |
|------|------|
| `winze-server/src/llm_memory/ui/main_window.clj` | `open-tab!`, `custom-browser`, `app-props` atom, tab lifecycle |
| `winze-server/src/llm_memory/ui/search.clj` | `resolve-file-path`, `file-page` (markdown rendering) |
| `clj-llm-memory/src/llm_memory/watcher.clj` | Beholder watcher: `start-watcher!`, `stop-watcher!`, debounce, rename detection |
| `winze-server/src/llm_memory/server/main.clj` | Server startup: `reconcile-and-watch!` starts watchers for all roots |

### Dependencies

Beholder (`com.nextjournal/beholder 1.0.2`) is already a dependency of `clj-llm-memory`, which `winze-server` depends on. No new dependencies required.

## Design

### Approach: Subscribe to Existing Watcher Events

Rather than creating a second Beholder watcher on the same directory, add a lightweight callback mechanism to `llm-memory.watcher` so the UI layer can subscribe to file change notifications. This avoids duplicate OS-level watches and reuses the existing debounce/filter logic.

### Open File Registry

Track which files are currently displayed in tabs:

```clojure
;; In main_window.clj
;; {absolute-path -> {:tab-id :ui/tab-browser-N, :root-uri str, :rel-path str}}
(defonce ^:private open-files (atom {}))
```

- Populated when `open-tab!` creates a file-viewer tab
- Cleared when the tab's `e/widget-disposed` event fires
- Keyed by absolute path for O(1) lookup from watcher events

### Watcher Callback Hook

Add a callback atom to `llm-memory.watcher`:

```clojure
;; In watcher.clj
(defonce ^:private change-listeners (atom []))

(defn add-change-listener!
  "Register a callback (fn [root-uri abs-path event-type]) invoked after debounce."
  [listener-fn]
  (swap! change-listeners conj listener-fn))

(defn remove-change-listener!
  "Remove a previously registered callback."
  [listener-fn]
  (swap! change-listeners (fn [ls] (vec (remove #{listener-fn} ls)))))
```

The existing event handler calls listeners **after** the debounce fires, alongside the index operations. This means listeners see the same 500ms-debounced events, not raw OS events.

### Refresh Flow

```
File modified on disk
  -> Beholder `:modify` event
  -> 500ms debounce
  -> handle-create-or-modify! (existing: re-indexes file)
  -> fire change listeners
  -> UI listener checks open-files atom
  -> If file is open in a tab:
       1. slurp file, render markdown -> HTML
       2. async-exec! -> (.setText browser html)
```

### Tab Deduplication

Currently, clicking the same file link twice opens two tabs. With the open-files registry, we can optionally deduplicate: if the file is already open, switch to the existing tab instead of creating a new one. This is a natural extension but could be a separate step.

### Edge Cases

- **File deleted while tab is open**: The watcher fires `:delete`. Close all tabs displaying the deleted file. The `open-files` registry is cleaned up as part of tab disposal.
- **File renamed while tab is open**: The watcher's rename detection fires a `:rename` event with both old and new paths. The UI listener updates the `open-files` registry (re-key from old path to new path), updates tab titles/tooltips to reflect the new filename, and continues watching the new path. No tab closure or content re-render needed — the content hasn't changed.
- **Tab closed during refresh**: The `e/widget-disposed` handler removes the entry from `open-files` before the `async-exec!` refresh runs. Guard with `.isDisposed` check before calling `.setText`.
- **Multiple tabs showing the same file**: The registry maps each path to a set of tab-ids. All matching tabs are refreshed/closed/renamed together.
- **Non-Plans files**: Currently all file-viewer tabs show files from Plans directories, which are already watched. No additional watch setup needed.

### Scroll Position Preservation

When refreshing a tab's content after a file change, the current scroll position must be saved and restored so the user doesn't lose their reading position.

**Mechanism**: SWT's `Browser.execute()` runs JavaScript synchronously. Before `.setText`:

1. Read `document.documentElement.scrollTop` via `Browser.evaluate()` to capture the current scroll offset.
2. Call `.setText` with the new HTML.
3. After `.setText`, inject JavaScript via `Browser.execute()` that:
   - Scrolls to `Math.min(savedScrollTop, document.documentElement.scrollHeight - window.innerHeight)`
   - This clamps the position so that if the new content is shorter, the view scrolls to the end rather than past the bottom.

**Timing**: `.setText` is asynchronous in WebKit — the content may not be fully laid out when the call returns. Use a `Browser` `ProgressListener` (or a short `timerExec` delay) to run the scroll restoration after the page finishes loading. The `ProgressListener.completed` event is the reliable signal that layout is done.

### SWT Threading

All Browser `.setText` and `.evaluate` calls must go through `async-exec!` (SWT-UI-GUIDE rules 1, 2). The watcher callback fires on the Beholder/scheduler thread, so the UI listener must use `async-exec!` to update browser widgets.

### Watcher Event Types for Listeners

The change listener callback signature needs to support rename events in addition to the standard `:create`, `:modify`, `:delete` types. For renames, the callback receives both the old and new paths:

```
(fn [root-uri abs-path event-type]       ;; :create, :modify, :delete
(fn [root-uri abs-path event-type extra] ;; :rename — extra = {:old-path str, :new-path str}
```

The simplest approach: use a map argument `{:type :rename :old-path str :new-path str}` or pass `:rename` as event-type with `abs-path` set to the new path and an additional `old-path` parameter.

### SWT Threading

All Browser `.setText` calls must go through `async-exec!` (SWT-UI-GUIDE rules 1, 2). The watcher callback fires on the Beholder/scheduler thread, so the UI listener must use `async-exec!` to update browser widgets.
