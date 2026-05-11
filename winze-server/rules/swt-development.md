---
paths:
  - "**/llm_memory/ui/**/*.clj"
  - "**/llm_memory/server/ui/**/*.clj"
---

# SWT User Interface Development

**CRITICAL: Before editing any SWT UI code, read the project's
`Plans/SWT-UI-GUIDE.md` in full.**
For worked examples and architecture details, also read
`Plans/SWT-REFERENCE.md`.

## Key rules (see guide for details)

1. **Threading**: All widget construction and mutation must happen on the UI
   thread. Use `(ui ...)` / `sync-exec!` to read, `async-exec!` to mutate.
   Never mutate UI state directly — always queue via `async-exec!`.

2. **Never call `application` against a running server** — it disposes the
   Display and crashes the JVM. Use `show` from `llm-memory.ui.util` for REPL
   testing.

3. **CDT idioms over raw SWT Java interop** — use CDT init functions, `id!`
   bindings, and `(mw/element :key)` to access existing widgets. Never call CDT
   init functions from the REPL; use `(mw/element :key)` instead.

4. **Resource disposal**: If you created it, dispose it in `try`/`finally`.

5. **REPL patching**: Never `:reload-all` SWT UI namespaces — it destroys
   running UI state. Use `load-file` or targeted `(defn ...)` evals.

6. **Visual verification**: Screenshot-verify every visual change — never
   report UI work done without a screenshot. Use fully-qualified
   `llm-memory.ui.util/screenshot-widget!` (aliases fail intermittently).

7. **`swtdoc` for API discovery**: Use `(swtdoc ClassName)` to explore the SWT
   API rather than guessing method signatures.
