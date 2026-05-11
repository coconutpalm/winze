---
doc_type: context
status: complete
group: find-replace-cdt
---

# Find-Replace CDT Regression — Context

## Summary

The find-replace bar stopped opening after `find_replace.clj` switched both
input widgets from `text` (SWT `Text`) to `styled-text` (SWT `StyledText`).
Two bugs were introduced together:

1. **Parameter shadowing** (root cause of the ClassCastException): The
   `open-find-bar!` function's destructured parameter was named `styled-text`,
   which shadowed the CDT init function `styled-text` referred from
   `[ui.SWT :refer [...styled-text...]]`. Inside the function body, every call
   to `(styled-text SWT/SINGLE ...)` was calling the editor widget (a
   `StyledText` instance), not the CDT init function, producing:
   ```
   ClassCastException: StyledText cannot be cast to IFn
   ```

2. **`.setMessage` not on `StyledText`** (secondary cause): The original `text`
   widgets used `:message "Search..."` / `:message "Replace with..."` for
   placeholder text. `StyledText` has no `.setMessage()` method. CDT's property
   dispatch would have thrown when processing this key.

## Fix Applied

In `open-find-bar!`:
- Changed `{:keys [^StyledText styled-text ...]}` to
  `{:keys [...] ^StyledText editor-st :styled-text ...}` — uses explicit
  key→binding syntax so the local name `editor-st` does not shadow the CDT
  `styled-text` init function.
- Updated the three internal uses of the old parameter name:
  `target-widget (or browser editor-st)`,
  `(.getSelectionText editor-st)`,
  `:styled-text editor-st` in the find-state map.
- Both `:message` calls (find field and replace field) had been removed in a
  prior session.

## File Architecture

`find_replace.clj` uses a hybrid construction pattern: the shell is created
manually (`Shell. parent-sh ...`) and CDT `child-of`/`defchildren` populates
it with children. This differs from the command palette's full CDT pattern
(`child-of parent (shell ...)`) because `open-find-bar!` receives any parent
widget (editor StyledText or Browser) and needs `.getShell` to find its parent
shell at call time.

## StyledText vs Text: Method Compatibility

All methods used on the find/replace input widgets exist on `StyledText`:
`.getText`, `.setText`, `.setFocus`, `.isDisposed`, `.getLayoutData`,
`.replaceTextRange`, `.getSelectionText`. The only incompatible method was
`.setMessage`, which was removed.

## Verification

- Hot-loaded fix onto running server; `open-find-bar! {:styled-text st}` →
  `:opened` (no exception).
- `find-bar-open?` → `true`; shell bounds `{581, 205, 520, 54}`.
- `make install` built and installed the uberjar (includes this fix AND the
  `command_palette.clj` CDT refactor from the same session).
- Browser-mode test on freshly installed JAR: `open-find-bar! {:browser brow}`
  → `:opened-browser-mode`; `find-bar-open?` → `true`.

## Lessons

- **Never use a CDT init function name as a destructuring parameter** — the
  local binding shadows the referred var and the error is a runtime
  ClassCastException, not a compile-time warning.
- The CLAUDE.md rule "Don't shadow core built-ins" applies equally to referred
  CDT init functions (`styled-text`, `text`, `label`, `button`, etc.).
- `screenshot-widget!` captures only the widget's own paint area — overlapping
  `SWT/TOOL | SWT/ON_TOP` shells paint separately and will not appear. To
  screenshot a floating shell, pass the shell itself to `screenshot-widget!`.
