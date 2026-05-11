---
doc_type: plan
status: complete
group: ca-filter-styled-text
---

# Plan: Content Assist Filter Text → StyledText

See `CA-FILTER-STYLED-TEXT-CONTEXT.md` for full background and constraints.

## Steps

### 1. Update `:require` in `content_assist.clj`

In the `[ui.SWT :refer [...]]` clause:
- Remove `text`
- Add `styled-text`

```clojure
;; Before
[ui.SWT :refer [async-exec! child-of composite id! on shell table
                table-column text |]]

;; After
[ui.SWT :refer [async-exec! child-of composite id! on shell styled-text table
                table-column |]]
```

### 2. Update `:import` — remove `Text`, verify `StyledText` present

`StyledText` is already imported.  Remove `Text` from:
```clojure
[org.eclipse.swt.widgets Composite Display Listener Shell Table TableItem Text]
```
→
```clojure
[org.eclipse.swt.widgets Composite Display Listener Shell Table TableItem]
```

### 3. Update `resize-popup!` type hint

```clojure
;; Before
(when-let [{:keys [^Shell shell ^Table table ^Text filter-text]} @popup-state]

;; After
(when-let [{:keys [^Shell shell ^Table table ^StyledText filter-text]} @popup-state]
```

### 4. Replace the filter field widget in `open-content-assist!`

Add `sel-bg` and `sel-fg` local bindings alongside the existing `bg`/`fg`
bindings:
```clojure
sel-bg   ^Color @resources/color-royal-purple
sel-fg   ^Color @resources/color-bedrock
```

Replace the `text` init block:
```clojure
;; Before
(text SWT/SINGLE
      (id! :ca/filter)
      :background bg
      :foreground fg
      :font font
      :message "Search pages..."
      (grid/grid-data :horizontal-alignment        SWT/FILL
                      :grab-excess-horizontal-space true
                      :height-hint                 24)
      (fn [_props parent]
        (.setData parent "scope" :content-assist))
      (on e/key-pressed  [...] ...)
      (on e/modify-text  [...] ...))

;; After
(styled-text (| SWT/SINGLE SWT/BORDER)
             (id! :ca/filter)
             :background bg
             :foreground fg
             :selection-background sel-bg
             :selection-foreground sel-fg
             :font font
             (grid/grid-data :horizontal-alignment        SWT/FILL
                             :grab-excess-horizontal-space true
                             :height-hint                 24)
             (fn [_props parent]
               (.setData parent "scope" :content-assist))
             (on e/key-pressed  [...] ...)
             (on e/modify-text  [...] ...))
```

Note: `:message "Search pages..."` is **removed** — `StyledText` has no
`.setMessage` method.  See CONTEXT §1.

### 5. Verify via REPL

Load the namespace and open the content assist popup:
```clojure
(require '[llm-memory.ui.content-assist :as ca] :reload)
```

Then trigger the popup in the running UI (via `Mod1+K` or `[text](` in the
editor) and confirm:
- The filter field renders without error
- Typing in the field triggers search
- Text selection shows `color-royal-purple` background
- Navigation keys (↑↓, Enter, Esc) work as before

### 6. Screenshot-verify

```clojure
(ui (llm-memory.ui.util/screenshot-widget! (mw/element :main-window) "/tmp/ca-styled-text.png"))
```

Inspect the screenshot to confirm the popup renders correctly with a visible
selection highlight when text is selected in the filter field.
