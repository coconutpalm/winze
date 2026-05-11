---
doc_type: context
status: complete
group: ca-filter-styled-text
---

# Content Assist Filter: Text → StyledText Migration

## Goal

Replace the `text` widget used as the filter/search field in the content assist
popup (`open-content-assist!`) with a `styled-text` widget, and add
`:selection-background`/`:selection-foreground` assignments using
`@resources/color-royal-purple` and `@resources/color-bedrock` to match the
visual style of the find bar and command palette.

## Affected File

`winze-server/src/llm_memory/ui/content_assist.clj`

The filter field is built inside `open-content-assist!` at the point where the
CDT widget tree is constructed.  It is a `text SWT/SINGLE` with `:message`,
`:background`, `:foreground`, a custom `:font`, `grid/grid-data`, a `setData`
init fn, and two event handlers (`e/key-pressed`, `e/modify-text`).

The assembled widget reference is stored as `(:ca/filter @props)` and
subsequently in `@popup-state` under `:filter-text`.

## Reference Pattern

`find_replace.clj` (`open-find-bar!`) already uses `styled-text` for its search
field and sets selection colors identically:

```clojure
(styled-text (| SWT/SINGLE SWT/BORDER) (id! :ui/find-text)
             :background field-bg
             :foreground fg
             :selection-background sel-bg    ; @resources/color-royal-purple
             :selection-foreground sel-fg    ; @resources/color-bedrock
             :font font
             (grid/grid-data :horizontal-alignment        SWT/FILL
                             :grab-excess-horizontal-space true
                             :vertical-alignment          SWT/CENTER)
             (fn [_props parent] (.setData parent "scope" :find-bar))
             (on e/modify-text  [...] ...)
             (on e/key-pressed  [...] ...))
```

The content assist field needs an identical structure, preserving its existing
height hint (`:height-hint 24`).

## Key Constraints and Gotchas

### 1. `StyledText` has no `.setMessage` / `:message`
`Text.setMessage(String)` draws placeholder hint text when the widget is empty.
`StyledText` does not have this method.  The current content assist filter uses:
```clojure
:message "Search pages..."
```
This property must be **dropped** when migrating to `styled-text`.  CDT will
pass `:message` as a keyword property to `StyledText`, which has no such setter,
and the init will fail silently or throw at construction time.

If a placeholder is needed in the future it requires a `PaintListener`-based
implementation — a deferred concern.

### 2. `StyledText` already imported
`StyledText` is already in the namespace `:import` (used by
`position-below-caret`'s type hint).  No new import is needed.

### 3. `Text` import and type hint in `resize-popup!`
`Text` appears in two places:
- `:import [...Text]` at the top of the namespace
- `^Text filter-text` destructuring type hint in `resize-popup!`

After migration `Text` is no longer needed.  The `^Text` hint must become
`^StyledText`.  The `Text` import can be removed if nothing else in the file
references it.

### 4. `:require` changes
`text` (CDT init function) is used **only** for the filter field.
After migration:
- Remove `text` from `[ui.SWT :refer [...]]`
- Add `styled-text` to the same `:refer` list

### 5. `computeSize` call compatibility
`resize-popup!` calls `.computeSize filter-text SWT/DEFAULT SWT/DEFAULT`.
`StyledText` implements `Control.computeSize` — this call is compatible
with no change other than updating the type hint.

### 6. Event handler compatibility
Both `e/modify-text` and `e/key-pressed` are supported on `StyledText` via
the same listener interfaces (`ModifyListener`, `KeyListener`) as `Text`.
No changes to the event handler bodies are required.

### 7. `getText` compatibility
`schedule-search!` receives text via `(.getText parent)` inside `e/modify-text`.
`StyledText.getText()` is identical to `Text.getText()`.  No change required.

## Color Resources

Both colors are already defined in `resources.clj` and available via the
`:require [llm-memory.ui.resources :as resources :refer [element]]` alias:

- `@resources/color-royal-purple` — selection background
- `@resources/color-bedrock`      — selection foreground

The `open-content-assist!` function currently binds `bg` and `fg` locals for
the existing colors.  The selection colors should be added as local bindings
named `sel-bg` and `sel-fg` for symmetry with `find_replace.clj`.
