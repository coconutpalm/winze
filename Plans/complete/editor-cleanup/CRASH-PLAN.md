# Editor Cleanup — Content Assist Crash Fix Plan

**Context**: [_EDITOR-CLEANUP-CRASH-CONTEXT.md](_EDITOR-CLEANUP-CRASH-CONTEXT.md)
**Parent plan**: [_EDITOR-CLEANUP-PLAN.md](_EDITOR-CLEANUP-PLAN.md) (Fix 1)

All changes are in `winze-server/src/llm_memory/ui/content_assist.clj`.

---

## Fix 1 — Replace `.open` with `.setVisible` on offscreen Shell

**Line**: 107

Replace:
```clojure
(.open sh)
```

With:
```clojure
(.setVisible sh true)
```

`.setVisible(true)` makes the Shell visible (required for Browser rendering)
without activating it or stealing focus. This prevents the offscreen Shell from
triggering `shellDeactivated` on the popup.

---

## Fix 2 — Add disposal guards in `render-row-images!`

**Lines**: 188-201 (the `completed` callback)

The existing `(when (= gen @render-generation) ...)` guard must be expanded to
also check that neither the Table nor the Browser are disposed:

```clojure
(completed [_event]
  (.removeProgressListener browser this)
  (when (and (= gen @render-generation)
             (not (.isDisposed table))
             (not (.isDisposed browser)))
    ;; ... existing body: measure gutter, screenshot, create TableItem ...
    ))
```

This makes the render loop self-terminate gracefully if the popup is dismissed
mid-render, regardless of how the dismissal happened.

---

## Fix 3 — Increment `render-generation` on popup close

**Lines**: 564-569 (Shell DisposeListener in `open-content-assist!`)

Add `(swap! render-generation inc)` as the first action in the Shell's
`widgetDisposed` handler:

```clojure
(widgetDisposed [_ _e]
  (swap! render-generation inc)   ;; cancel in-flight renders
  (.shutdown executor)
  (keybindings/clear-active-popup!)
  (reset! popup-state nil))
```

This ensures any in-progress render loop sees a stale generation and aborts
at its next iteration check, even if the popup was closed by deactivation
rather than by a new search.

---

## Verification

After applying all three fixes:

1. **No-crash test**: Open content assist (`[[` or `Cmd+K`), type a query,
   then immediately click outside to dismiss. Repeat several times. The app
   must not crash.

2. **Image rendering test**: Open content assist with a search query. Confirm
   whether images appear in the Table rows. If images are blank, the
   `.print(GC)` offscreen rendering issue remains (separate from this crash
   fix — tracked in the parent plan).

3. **Normal operation**: Open content assist, wait for results to render,
   navigate with arrow keys, select with Enter. Confirm selection works and
   the link is inserted correctly.
