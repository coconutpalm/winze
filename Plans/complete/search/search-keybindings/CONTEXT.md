# Search Text Keybindings (Enter/Escape) — Context

## Goal

Add two keyboard shortcuts to the search text widget:
- **Enter** — snapshot the current live search results into a new closable tab
- **Escape** (or cancel button click) — clear the search text and reset to the empty page

## Current State

### Search Text Widget

`main_window.clj:header` creates the search `Text` widget (`:ui/search`) with two event handlers:

```clojure
(text (id! :ui/search)
      (grid/hgrab)
      (on e/modify-text [props parent event]
          (async-exec! #(search/results
                         (.getText (element :search))
                         (element :live-search-results))))
      (on e/widget-selected [props parent event]
          #_(comment "if event.detail != SWT.CANCEL, open a search result tab
                      with the current live search results")))
```

The `widget-selected` handler is already stubbed out with a comment describing the Enter-to-new-tab behavior. The stub follows the SWT Snippet 258 pattern (see below).

### SWT Snippet 258 — Search Text Control

The stub is based on [SWT Snippet 258](https://github.com/eclipse-platform/eclipse.platform.swt/blob/master/examples/org.eclipse.swt.snippets/src/org/eclipse/swt/snippets/Snippet258.java), the canonical SWT pattern for a search field:

```java
final Text text = new Text(shell, SWT.SEARCH | SWT.ICON_CANCEL);
text.addSelectionListener(widgetSelectedAdapter(e -> {
    if (e.detail == SWT.CANCEL) {
        System.out.println("Search cancelled");
    } else {
        System.out.println("Searching for: " + text.getText() + "...");
    }
}));
```

Key points:
- `SWT.SEARCH` gives a native platform search field (rounded corners, magnifying glass on macOS)
- `SWT.ICON_CANCEL` adds a clickable cancel (X) button
- A single `widgetSelected` event handler dispatches on `event.detail`:
  - `event.detail == SWT.CANCEL` → Escape pressed or cancel button clicked
  - Otherwise → Enter pressed

### What's Missing

The Text widget is currently created **without** `SWT.SEARCH` or `SWT.ICON_CANCEL` styles. Adding `(| SWT/SEARCH SWT/ICON_CANCEL)` enables the Snippet 258 pattern and the existing `widget-selected` handler becomes correct.

CDT's `|` function (from `ui.SWT`) is a synonym for `bit-or`, used idiomatically for combining SWT style constants.

### Browser HTML Access

SWT `Browser.getText()` returns the current page HTML as a `String`. This is how we copy the live search results to a new tab:

```clojure
(let [html (.getText (element :live-search-results))]
  ;; html contains the full <html>...</html> string
  )
```

### Tab Infrastructure

From the file-viewer story context: `CTabFolder` (`:ui/main-folder`) supports dynamic tab creation via:

```clojure
(let [folder  (element :main-folder)
      browser (Browser. folder SWT/WEBKIT)
      tab     (CTabItem. folder SWT/CLOSE)]
  (.setJavascriptEnabled browser true)
  (.setText browser html-content)
  (.setText tab "tab title")
  (.setControl tab browser)
  (.setSelection folder tab))
```

This is shared infrastructure with the file-viewer story — both create new closable tabs with Browser widgets.

### Live Search Tab (Permanent)

The live search tab is the first (index 0) tab, created without `SWT/CLOSE`:

```clojure
(ctab-item #_SWT/CLOSE "Live search"
           :image @statusbar-icon
           (control :ui/live-search-results))
```

After snapshotting to a new tab, the live search tab should remain selected and continue working — the snapshot is a copy, not a move.

## Design

### Enter: Snapshot Results to New Tab

When the user presses Enter (`event.detail != SWT/CANCEL`):
1. Read the current HTML from the live search Browser via `.getText()`
2. Read the current search string from the Text widget
3. If the search string is empty or the browser shows the empty page, do nothing
4. Create a new `Browser` + `CTabItem` in the main folder
5. Set the tab title to the search string (truncated if long)
6. Set the new Browser's HTML to the snapshot
7. Select the new tab

The snapshot is static HTML — it does not update if the index changes. This is intentional: it preserves a point-in-time view of results, like pinning a search.

### Escape / Cancel: Clear Search

When the user presses Escape or clicks the cancel button (`event.detail == SWT/CANCEL`):
1. Clear the Text widget's content (`.setText ""`)
2. The `modify-text` handler fires automatically, which calls `search/results` with an empty string
3. `search/results` sees `(< (count q) 3)` and renders the empty page

No explicit empty-page rendering needed — the existing `modify-text` handler chain handles it.

### Single Handler, Two Branches

The Snippet 258 approach uses one `widget-selected` handler with an `if` on `event.detail`. This is simpler than two separate event types and matches the SWT idiom for search fields.

### Shared `open-tab!`

The file-viewer story defines `open-file-tab!` for creating new tabs. The Enter-to-snapshot behavior is nearly identical — both create a Browser + CTabItem. We can generalize to a shared helper or just reuse `open-file-tab!` directly (it takes a title string and HTML content).

## Files to Modify

| File | Change |
|------|--------|
| `winze-server/src/llm_memory/ui/main_window.clj` | Add `(| SWT/SEARCH SWT/ICON_CANCEL)` style to Text widget; implement the `widget-selected` handler; add `open-tab!` helper; import `Browser`; add `|` to the `ui.SWT` `:refer` list |
