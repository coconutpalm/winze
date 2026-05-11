---
created: 2026-03-30
related: [complete/search-keybindings/CONTEXT.md, complete/global-esc/CONTEXT.md]
tags: [swt, ui, search, regression]
---

# Search Field X Button Not Clearing — Context

## Problem

When the user types in the search box, an "X" (cancel) glyph appears at the far right
of the field. Clicking it does nothing — the search text is not cleared.

## Root Cause

The search `Text` widget at `main_window.clj:483` is created with style
`(| SWT/SEARCH SWT/ICON_CANCEL)`, which enables the native cancel button.
In SWT, clicking the cancel button fires a `widget-selected` event with
`event.detail == SWT/CANCEL`.

The current widget has two event handlers:

```clojure
(on e/modify-text ...)            ; fires on every keystroke
(on e/widget-default-selected ...) ; fires on Enter key
```

There is **no `widget-selected` handler**. Without it, the `SWT/CANCEL` event
is ignored and the text field is not cleared.

## History

The `search-keybindings` story (completed 2026-03-26) originally implemented a single
`widget-selected` handler that dispatched on `event.detail`:

```clojure
(on e/widget-selected [props parent event]
    (if (= (.-detail event) SWT/CANCEL)
      (.setText (element :search) "")
      ;; else: Enter → open tab
      (let [q    (str/trim (.getText (element :search)))
            html (.getText (element :live-search-results))]
        (when (>= (count q) 3)
          (open-tab! @statusbar-icon q html)))))
```

A subsequent edit (file-viewer-header work, ~2026-03-28) refactored Enter handling to
`widget-default-selected` — correct — but accidentally dropped the `widget-selected`
handler entirely, leaving the cancel button inert.

## SWT Event Model for Search Fields

For a `Text` widget created with `SWT/SEARCH | SWT/ICON_CANCEL`:

| Action            | Event                   | `event.detail` |
|-------------------|-------------------------|----------------|
| X button clicked  | `widget-selected`       | `SWT/CANCEL`   |
| Enter key pressed | `widget-default-selected` | (any)        |

These are two separate events. `widget-default-selected` does NOT fire for button clicks,
and `widget-selected` with detail `SWT/CANCEL` does NOT fire for Enter.

## Desired Behavior

Clicking the X button clears the search text. The existing `modify-text` handler
cascades automatically: it calls `search/results` with an empty string, which renders
the empty placeholder page. No explicit page reset is needed.

## Escape Key

The global Esc handler (completed 2026-03-26 in `global-esc` work) also clears the
search text, but it does so directly via a Display-level event filter. That path is
unaffected by this bug. Only the mouse click on the X glyph is broken.

## Files to Modify

| File | Change |
|------|--------|
| `winze-server/src/llm_memory/ui/main_window.clj` | Add `widget-selected` handler to search Text widget; clears text on `SWT/CANCEL` |
