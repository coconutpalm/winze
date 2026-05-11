---
created: 2026-03-27
doc_type: plan
group: global-esc
tags: [swt, ui, keyboard, event-filter]
---

# Global Esc Key Handler — Plan

## Steps

### 1. Add Display event filter in `defmain`

In `main_window.clj`, expand the `defmain` block to install a global KeyDown
filter on the Display (`parent`):

```clojure
(defmain [props parent]
  (reset! app-props @props)
  (.addFilter parent SWT/KeyDown
              (reify Listener
                (handleEvent [_ event]
                  (when (= (.keyCode event) SWT/ESC)
                    (set! (.-doit event) false)
                    (async-exec!
                     (fn []
                       (.setSelection (element :main-folder) 0)
                       (.setText (element :search) "")
                       (.setFocus (element :search)))))))))
```

Key points:
- `SWT/KeyDown` catches the key before Traverse processing
- `(set! (.-doit event) false)` consumes the event immediately
- `async-exec!` defers mutations until after all pending keypress events complete
- Switch to Live Search tab (index 0) before clearing — mirrors the existing
  `modify-text` handler behavior

### 2. REPL-test with synthetic Esc event

```clojure
(require '[llm-memory.ui.main-window :as mw] :reload)

;; Post a synthetic Esc keypress to the Display
(ui (let [event (org.eclipse.swt.widgets.Event.)]
      (set! (.-type event) SWT/KeyDown)
      (set! (.-keyCode event) SWT/ESC)
      (.post @display event)))
```

Verify:
- Search text is cleared
- Focus returns to the search field
- Works regardless of which widget had focus before

### 3. Remove the search-box Esc handler

Delete the `(on e/widget-selected ...)` handler (lines 157–158) that checks
`SWT/CANCEL`:

```clojure
;; REMOVE this:
(on e/widget-selected [props parent event]
    (when (= (.-detail event) SWT/CANCEL)
      (.setText (element :search) "")))
```

This is now redundant — the global filter handles Esc from any context.

### 4. REPL-test again after removal

Re-run the synthetic Esc test to confirm behavior is unchanged.

### 5. Screenshot-verify

Take a screenshot before and after Esc to confirm the search field is cleared
and focused.
