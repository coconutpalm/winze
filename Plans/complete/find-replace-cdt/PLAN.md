---
doc_type: plan
status: active
group: find-replace-cdt
---

# Find-Replace CDT Regression — Plan

## Goal

Restore the find-replace bar. The source file is already correct (`:message`
removed from both `styled-text` fields). The fix needs to be installed and
verified. If the install reveals additional errors, fix them before shipping.

## Pre-Conditions

- Winze server is running. Find the nREPL port:
  ```bash
  clj-nrepl-eval --discover-ports
  ```
- The source fix is in `winze-server/src/llm_memory/ui/find_replace.clj`.
  **Do not re-read or re-edit** unless a new error is found below.

---

## Step 1 — Capture the actual exception (before installing)

Hot-load the current source into the running server and trigger the find bar.
This gives us the exception from the buggy installed code before we overwrite
it, so we can confirm `:message` is the only issue.

```bash
clj-nrepl-eval -p <port> << 'EOF'
(load-file "/Users/dorme/code/_finance/winze/winze-server/src/llm_memory/ui/find_replace.clj")
EOF
```

Then open the find bar (Cmd+F on a document) and immediately check the Winze
log for exceptions. Look for:

- `NoSuchMethodException` / `MethodNotFoundException` — confirms `:message` was
  the cause
- `NullPointerException` — suggests `@props` keys are not being populated (CDT
  `id!` issue inside `defchildren`)
- `ClassCastException` — an unexpected type hint issue

If the REPL load itself throws, the error is a compile-time issue in the source.

---

## Step 2 — Install the fix

If Step 1 shows only the expected `:message`-related error (or no error, meaning
the hot-load worked):

```bash
cd /Users/dorme/code/_finance/winze/winze-server && make install
```

`make install` builds the uberjar from current source and copies it to
`~/.local/share/winze/lib/`. It does **not** restart the server.

This also picks up the `command_palette.clj` CDT refactor (also uncommitted)
since both are now correct source.

---

## Step 3 — Restart the Winze server

Graceful shutdown via nREPL (never `pkill`):

```bash
clj-nrepl-eval -p <port> << 'EOF'
(llm-memory.ui.main-window/quit!)
EOF
```

The server will restart automatically on the next MCP call, OR start it manually:

```bash
make run
```

---

## Step 4 — Verify the find bar opens

1. Open the Winze window.
2. Open a document (select any note in the list).
3. Press **Cmd+F** — the find bar should appear at the top-right of the editor.
4. Press **Cmd+H** — find bar should appear with the replace row visible.
5. Type a few characters — verify incremental search highlights matches.
6. Press **Esc** — find bar should close.
7. Screenshot the result:
   ```clojure
   (llm-memory.ui.util/screenshot-widget! (llm-memory.ui.resources/element :main-window) "/tmp/find-bar-verify.png")
   ```

---

## Step 5 — If new errors appear after Step 1

### 5a. NPE / `@props` keys nil

Cause: `id!` inside `defchildren` may not register widgets into `props` the same
way as inside `shell`. The symptoms would be `NullPointerException` at:
```clojure
(.setFocus (:ui/find-text @props))
```

Fix: Inspect whether `@props` is populated after `child-of` returns. From REPL:
```clojure
;; After load-file, trigger and inspect — do not evaluate inside a running
;; open-find-bar! call; read state after it throws.
(deref llm-memory.ui.find-replace/find-state)
```

If `@props` is empty, the CDT `defchildren` + `id!` combination is broken.
Resolution: switch to the `shell`-inside-`child-of` pattern used in
`command_palette.clj`. The comment in `open-find-bar!` says:

> "Shell must be created manually — CDT's `shell` doesn't support child-shell
> parenting (SWT/TOOL requires a parent Shell)."

This may no longer be true — test whether `(child-of parent-sh props (shell ...))` works
when `parent-sh` is a `Shell` obtained from `.getShell`. If it works, migrate
`find_replace.clj` to the full CDT pattern.

### 5b. Additional unresolved property key

If CDT throws on a property key other than `:message`, identify it from the
stack trace, remove that property, and add the equivalent imperative call in a
`(fn [_props parent] ...)` init function in the widget body.

### 5c. `ClassCastException` on `toggle-replace-visibility!`

If the replace row fails to show/hide, the `^GridData` cast failed. The replace
widgets use `(fn [_props parent] (let [gd (GridData. ...)] (.setLayoutData parent gd) ...))`.
Verify the GridData is being set and returned by `.getLayoutData`.

---

## Non-Goals

- Do not change `find-matches` or any pure search logic.
- Do not change `apply-find-highlights!`, `browser-highlight-matches!`, or any
  editor/browser integration.
- Do not add placeholder text back — `StyledText` has no `.setMessage`.
  The field is self-evident from context (same as the command palette filter).
- Do not `:reload-all` — use `load-file` for targeted hot-loading.

---

## After Completion

Move these files to `Plans/complete/find-replace-cdt/`:
- `FIND-REPLACE-CDT-CONTEXT.md` → `CONTEXT.md`
- `FIND-REPLACE-CDT-PLAN.md` → `PLAN.md`

Commit `find_replace.clj` and `command_palette.clj` together (both modified in
the same session, both need the install).
