---
doc_type: plan
status: active
group: palette-cdt-refactor
---

# Command Palette CDT Refactor — Plan

## Scope

1. Refactor `open-palette!` to use CDT init functions throughout.
2. Replace the `Text` filter field with `StyledText` — required because
   `Text.setSelectionBackground/Foreground` is a no-op on macOS Cocoa;
   `StyledText` manages its own paint and honours these calls on all platforms.
3. Set `color-royal-purple` as the selection background on the `StyledText`.

One file changes: `winze-server/src/llm_memory/ui/command_palette.clj`.

## Steps

### 1. Update `ns` requires and imports

**Add to `[ui.SWT :refer ...]`:**
`shell`, `styled-text`, `table`, `table-column`, `composite`, `defchildren`,
`child-of`, `id!`, `on`

**Add `ui.gridlayout` require:**
```clojure
[ui.gridlayout :as grid :refer [grid-layout]]
```

**Add `ui.events` require:**
```clojure
[ui.events :as e]
```

**Remove from `(:import ...)`:**
- `[org.eclipse.swt.events DisposeListener KeyAdapter KeyEvent ModifyListener ShellAdapter]`
- `[org.eclipse.swt.layout GridData GridLayout]`
- `TableColumn` from `org.eclipse.swt.widgets` (replaced by CDT `table-column`)

**Keep in `(:import ...)`:**
- `[org.eclipse.swt SWT]`
- `[org.eclipse.swt.graphics Color Font]`
- `[org.eclipse.swt.widgets Shell Table TableItem]`
  (`Shell` for type hint, `Table` for type hints in helpers, `TableItem` in
  `populate-table!`)

### 2. Rewrite `open-palette!` widget tree via `child-of`

Replace the imperative block (`(let [sh (Shell. ...) ...]`) with:

```clojure
(child-of (element :main-window) props
          (shell (| SWT/TOOL SWT/ON_TOP SWT/NO_TRIM)
                 (id! :palette/shell)
                 :background bg
                 (grid-layout :num-columns    1
                              :margin-width   4
                              :margin-height  4
                              :vertical-spacing 2)

                 ;; Filter field — StyledText required: Text.setSelectionBackground
                 ;; is a no-op on macOS Cocoa; StyledText owns its own paint.
                 (styled-text SWT/SINGLE
                              (id! :palette/filter)
                              :background bg
                              :foreground fg
                              :font font
                              :selection-background ^Color @resources/color-royal-purple
                              :selection-foreground fg
                              (grid/grid-data :horizontal-alignment        SWT/FILL
                                             :grab-excess-horizontal-space true
                                             :height-hint                 28)
                              (fn [_props parent]
                                (.setData parent "scope" :command-palette))
                              (on e/modify-text [_props parent _event]
                                  (populate-table! ...)
                                  (resize-to-fit! ...))
                              (on e/key-pressed [_props parent event]
                                  (let [kc (.keyCode event)]
                                    (cond
                                      (= kc SWT/ARROW_DOWN)  ...
                                      (= kc SWT/ARROW_UP)    ...
                                      (= kc (int SWT/CR))    ...
                                      (= kc (int SWT/ESC))   ...))))

                 ;; Results table
                 (table (| SWT/SINGLE SWT/FULL_SELECTION)
                        (id! :palette/table)
                        :background bg
                        :foreground fg
                        :font font
                        :header-visible false
                        :lines-visible  false
                        (grid/grab-both)

                        (table-column :width 310)
                        (table-column :width 70)
                        (table-column :width 88)

                        (on e/widget-selected [_props parent _event]
                            (execute-and-close! parent sh)))

                 ;; Deactivate → close
                 (on e/shell-deactivated [_props parent _event]
                     (async-exec! #(when (and (not (.isDisposed parent))
                                             (.isVisible parent))
                                     (.close parent))))

                 ;; Dispose → clear state
                 (on e/widget-disposed [_props _parent _event]
                     (reset! palette-shell nil)
                     (keybindings/clear-active-popup!))))
```

The `populate-table!` call inside `on e/modify-text` needs to close over `hints`
and `all-cmds` from the enclosing `let`.  `resize-to-fit!` needs the shell,
filter, and table — these are closured from `@props` after `child-of` completes,
or forward-referenced via `@props` inside the lambda.

**Note on the `on e/modify-text` closure**: `populate-table!` and `resize-to-fit!`
reference `sh` (shell) and `tbl` (table).  Since the shell and table are
available as `(:palette/shell @props)` and `(:palette/table @props)` after
`child-of` returns, the listener body can dereference `props` at call time.

### 3. Extract widgets from props after `child-of`

```clojure
sh          (:palette/shell  @props)
filter-txt  (:palette/filter @props)
tbl         (:palette/table  @props)
```

### 4. Update the post-build imperative block

Keep verbatim (no change needed):
```clojure
(populate-table! tbl "" hints all-cmds)
(position-palette! sh parent-sh)
(.open sh)
(.setFocus filter-txt)
(resize-to-fit! sh filter-txt tbl)
(reset! palette-shell sh)
(keybindings/set-active-popup! :command-palette)
```

### 5. Update `resize-to-fit!` and `execute-and-close!` signatures

No signature changes required — both already accept `^Shell sh`, `^Table table`,
etc., which are now simply the CDT-constructed widgets retrieved from `@props`.

If `resize-to-fit!` is closed over inside the `on e/modify-text` listener,
confirm it references `sh` and `tbl` via the `props` atom (deref at call time),
not captured before `child-of` runs.

### 6. Verify RCF tests still pass

Only `fuzzy-match?` has inline RCF tests.  These are pure functions with no
widget dependencies and will pass without a running REPL.

Launch a dev nREPL (`start-nrepl` skill from `winze-server/`) and load:
```clojure
(require '[llm-memory.ui.command-palette] :reload)
```

Confirm no compile errors and RCF tests pass.

### 7. Visual verification

With the Winze server running, open the command palette (`Cmd+Shift+P`) and:

1. Confirm the palette opens at the correct position and size.
2. Type a few characters — verify filtering works and table updates.
3. Select text in the filter field — confirm selection background is
   `color-royal-purple` (mid purple, visibly lighter than mine-shaft,
   readable crystal-white text).
4. Press `↓`/`↑` — confirm table selection moves.
5. Press `Enter` — confirm command executes and palette closes.
6. Press `Esc` — confirm palette closes.
7. Click outside — confirm palette closes.
8. Take a screenshot with `llm-memory.ui.util/screenshot-widget!` on
   the main window to record the final state.

## `StyledText` vs `Text`: `.setMessage` unavailable

`StyledText` is used instead of `Text` because `Text.setSelectionBackground/
Foreground` is a no-op on macOS Cocoa (see CONTEXT §StyledText vs Text).

The trade-off: `Text` exposes `.setMessage(String)` for platform-native
placeholder text; `StyledText` does not have this method.

The current palette filter field uses `.setMessage "Type to filter commands..."`.
After switching to `StyledText` this must be handled differently.  Three options:

1. **Drop the placeholder** (recommended): The palette opens with focus already
   on the filter field, and its purpose is self-evident from context.  The
   simplest correct solution.

2. **Static label above the field**: Add a CDT `label` widget as the first child
   of the shell with the hint text, positioned above the `styled-text`.  Visible
   at all times, not a placeholder.

3. **`PaintListener` placeholder**: Attach a `PaintListener` that draws ghost
   text when the field is empty.  This faithfully replicates `Text.setMessage`
   but adds non-trivial stateful listener code.

The plan assumes **option 1** unless the user specifies otherwise.

## Non-Goals

- No change to `populate-table!` internals or `TableItem.` creation.
- No change to `resize-to-fit!` or `position-palette!` geometry logic.
- No change to `register-palette-commands!` or the keybinding registrations.
- No additional theme vars — `color-royal-purple` is already defined in
  `resources.clj` and populated by `theme/apply-theme-startup!`.
