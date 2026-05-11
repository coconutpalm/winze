# Search Text Keybindings (Enter/Escape) — Plan

## Step 1: Add Search Style and Implement Handler (`main_window.clj`)

Add `(| SWT/SEARCH SWT/ICON_CANCEL)` style to the Text widget and implement the stubbed `widget-selected` handler:

```clojure
(text (| SWT/SEARCH SWT/ICON_CANCEL)
      (id! :ui/search)
      (grid/hgrab)
      (on e/modify-text [props parent event]
          (async-exec! #(search/results
                         (.getText (element :search))
                         (element :live-search-results))))
      (on e/widget-selected [props parent event]
          (if (= (.-detail event) SWT/CANCEL)
            (.setText (element :search) "")
            (let [q    (str/trim (.getText (element :search)))
                  html (.getText (element :live-search-results))]
              (when (>= (count q) 3)
                (open-tab! q html))))))
```

This follows SWT Snippet 258 exactly:
- `SWT/SEARCH` — native search field appearance (rounded corners, magnifying glass on macOS)
- `SWT/ICON_CANCEL` — cancel (X) button
- Single `widget-selected` handler dispatches on `event.detail`:
  - `SWT/CANCEL` → clear the text (Escape or X click). The `modify-text` handler cascades to reset the empty page automatically.
  - Otherwise → Enter pressed. Snapshot the current results into a new tab.

### Guard

Skip tab creation if query < 3 chars (browser is showing the empty page placeholder, nothing to snapshot).

## Step 2: Extract Shared Tab-Creation Helper (`main_window.clj`)

The file-viewer story also needs to create closable tabs. Define a shared helper:

```clojure
(defn- open-tab!
  "Create a new closable tab with a Browser widget displaying the given HTML."
  [title html]
  (let [folder  (element :main-folder)
        brow    (Browser. folder SWT/WEBKIT)
        tab     (CTabItem. folder SWT/CLOSE)]
    (.setJavascriptEnabled brow true)
    (.setText brow html)
    (.setText tab (if (> (count title) 40)
                    (str (subs title 0 37) "...")
                    title))
    (.setToolTipText tab title)
    (.setImage tab @statusbar-icon)
    (.setControl tab brow)
    (.setSelection folder tab)))
```

Truncate tab titles longer than 40 characters with ellipsis — full text goes in the tooltip.

## Step 3: Add Imports and Refers (`main_window.clj`)

Add `|` to the `ui.SWT` `:refer` list and `Browser` to `:import`:

```clojure
;; In :require
[ui.SWT :refer [... | ...]]

;; In :import
[org.eclipse.swt.browser Browser]
```

`Browser` is needed for constructing new tab Browser widgets. `CTabItem` is already imported.

## Step 4: Test

1. Verify the search field renders with native search styling (rounded, magnifying glass icon, X button)
2. Type a search query, wait for results to appear
3. Press Enter — verify a new tab opens with the search string as title
4. Verify the new tab has a close button and contains the same results HTML
5. Verify the live search tab is unchanged (still shows results, still responds to typing)
6. Press Escape — verify the search text clears and the empty page appears
7. Click the X cancel button — verify same behavior as Escape
8. Press Enter with an empty/short query — verify nothing happens
9. Open multiple snapshot tabs — verify they're independent and closable

## Files Modified

| File | Change |
|------|--------|
| `winze-server/src/llm_memory/ui/main_window.clj` | Add `(| SWT/SEARCH SWT/ICON_CANCEL)` style to Text widget; implement `widget-selected` handler with Enter/Cancel dispatch; add `open-tab!` helper; import `Browser`; add `|` to `ui.SWT` refers |

## Dependencies

- **Shared with file-viewer story**: `open-tab!` helper — whichever lands first defines it, the other reuses
- **No dependency on other stories** — this is self-contained
