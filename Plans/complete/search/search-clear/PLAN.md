---
created: 2026-03-30
related: [Plans/dev/SEARCH-CLEAR-CONTEXT.md]
tags: [swt, ui, search, regression]
---

# Search Field X Button Not Clearing — Plan

## Step 1: Add `widget-selected` Handler

In `winze-server/src/llm_memory/ui/main_window.clj`, find the `header` function
(around line 483) and add a `widget-selected` handler to the search Text widget:

```clojure
(text (| SWT/SEARCH SWT/ICON_CANCEL)
      (id! :ui/search)
      (grid/hgrab)

      (on e/modify-text [props parent event]
          (when-not (= 0 (.getSelection (element :main-folder)))
            (.setSelection (element :main-folder) 0))
          (search/results (.getText (element :search))
                          (element :live-search-results)))
      (on e/widget-selected [props parent event]       ; ← ADD THIS
          (when (= (.-detail event) SWT/CANCEL)
            (.setText (element :search) "")))
      (on e/widget-default-selected [props parent event]
          (let [q    (str/trim (.getText (element :search)))
                html (.getText (element :live-search-results))]
            (when (>= (count q) 3)
              (open-tab! @statusbar-icon q html)))))
```

The `widget-selected` handler:
- Fires when the X button is clicked (or Escape, as a fallback — but the Display-level
  global Esc handler fires first in practice)
- Checks `event.detail == SWT/CANCEL` before acting (guard against spurious firings)
- Calls `.setText ""` on the search widget
- The existing `modify-text` handler cascades automatically, calling `search/results`
  with an empty string and rendering the empty placeholder page

No other files require changes. `SWT/CANCEL` and `|` are already in scope.

## Step 2: Patch via REPL

Load the change into the running UI via REPL patch (do NOT use `:reload-all`).
Evaluate only the updated `header` function definition, then verify the widget
by interacting with it in the live UI.

## Step 3: Verify

1. Type a search query — the X button should appear
2. Click the X — search text clears, live search tab shows the empty placeholder
3. Type another query, then press Escape — text clears (global Esc handler; still works)
4. Type a query ≥ 3 chars, press Enter — snapshot tab opens (Enter path unaffected)

## Step 4: Screenshot

Take a screenshot confirming the search field is empty after clicking X. Required per
SWT visual-verification policy.

## Files Modified

| File | Change |
|------|--------|
| `winze-server/src/llm_memory/ui/main_window.clj` | Add `widget-selected` handler (4 lines) |
