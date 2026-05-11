# Markdown Editor Widget — Plan

Small, verifiable steps. Each step ends with a concrete check before the
next step starts. All editing happens at the REPL first (per winze
CLAUDE.md); files are written only when the REPL version works.

Read `MARKDOWN-EDITOR-WIDGET-CONTEXT.md` first.

## Step 0 — Read-only survey (no edits)

Purpose: confirm the context doc matches current code; find anything the
context missed.

Tasks:

1. Grep for usages of every field read off `open-files` entries:
   - `:dirty?` — expect: only `toggle-mode!` and possibly the modify listener.
   - `:history` — expect: only `toggle-mode!` and `editor/undo|redo` command
     handlers in `register-workbench-commands!`.
   - `:save-future` — expect: only `toggle-mode!`.
   - `:scroll-state` — expect: only `toggle-mode!`.
   If any other site reads these, list it — each becomes a migration
   sub-task in Step 6.
2. Confirm `register-workbench-commands!` undo/redo handlers
   ([main_window.clj:1026-1049](../../winze-server/src/llm_memory/ui/main_window.clj#L1026-L1049)) pull `history` from
   `active-file-entry`. Those need to read from the widget after the
   refactor.
3. Confirm the stale `markdown-editor` at [markdown_editor.clj:929](../../winze-server/src/llm_memory/ui/markdown_editor.clj#L929)
   truly has no call sites (grep across `winze/`, not just `winze-server`).
4. Read `ui.markdown-editor` top-to-bottom; note the `declare`s (if any)
   and the order of `install-*` function definitions so the widget's
   init-order helper can be placed correctly in the file.
5. Grep `editor_commands.clj` (and every other `ui.*` namespace) for
   `:history`, `:dirty?`, `:save-future` readers. Expected finding: only
   `main_window.clj` and `markdown_editor.clj` read these. `editor_commands`
   obtains the active StyledText via its own `active-styled-text-fn`
   registry (see [editor_commands.clj:26-37](../../winze-server/src/llm_memory/ui/editor_commands.clj#L26-L37)) — widget state, not tab-entry
   state — so nothing there should change. Run the grep in this step
   and list any additional readers as migration sub-tasks in Step 6.
6. Grep **scoped to `winze-server/src/`** for the `"md/text"` setData
   key — expect **no** hits yet (new this task). The `Plans/` tree
   mentions the key in design docs; limit the audit to code to avoid
   noise. If `src/` has a hit, that's a prior half-landed change and
   this plan needs to reconcile.
7. Check `main_window.clj`'s `ns` form: is `with-property` already
   `:refer`'d from `ui.SWT`? If not, Step 4's code will fail to
   resolve; add it to the `:refer` vector when landing Step 4. (The
   symbol IS exported by CDT — see
   [ui.SWT/with-property](file:///Users/dorme/code/ui/clojure-desktop-toolkit/src/ui/SWT.clj#L112).)
8. Check `find_replace.clj` — it uses `requiring-resolve` for
   `llm-memory.ui.markdown-editor/apply-theme!`
   ([find_replace.clj:98](../../winze-server/src/llm_memory/ui/find_replace.clj#L98)).
   Confirm that keeps working after the refactor (i.e. `apply-theme!`
   is still a public defn in `markdown_editor.clj`). If a future
   cleanup removes the `requiring-resolve`, this would become a static
   require cycle with `find_replace.clj` → `markdown_editor.clj` →
   `find_replace.clj` — so the runtime resolution is load-bearing; do
   not "clean it up" as part of this task.
9. Audit `install-link-interaction!` arities
   ([markdown_editor.clj:404-405](../../winze-server/src/llm_memory/ui/markdown_editor.clj#L404-L405)).
   The shorter arity `[^StyledText st abs-path]` delegates to the
   wider `[^StyledText st abs-path rel-path root-uri]` arity with
   `nil nil`. The widget always calls the wider arity. Grep
   `winze-server/src/` for callers of the shorter arity: **expect
   exactly one hit at [markdown_editor.clj:974](../../winze-server/src/llm_memory/ui/markdown_editor.clj#L974)**
   (inside the stale `markdown-editor` fn that Step 1 replaces). That
   single caller disappears in Step 1, so Step 8's deletion of the
   shorter arity is safe. If the grep finds any other caller, record
   it here as an extra migration sub-task.

Deliverable: a short note (in this PLAN as a checklist update, or a scratch
buffer) confirming expectations or listing extra call sites that need
migration.

Verification: none — read-only.

## Step 1 — Replace the stale `markdown-editor` init with a minimal shell

Edit `src/llm_memory/ui/markdown_editor.clj` at line 929. Replace the
existing `markdown-editor` defn with this shell only:

```clojure
(defn markdown-editor
  "See MARKDOWN-EDITOR-WIDGET-CONTEXT.md for the full contract."
  [opts & extra-inits]
  (apply styled-text (| SWT/MULTI SWT/V_SCROLL SWT/WRAP)
         :font                  @res/body-font
         :background            @res/color-mine-shaft
         :foreground            @res/color-crystal-white
         :selection-background  @res/color-royal-purple
         :selection-foreground  @res/color-pure-white
         :word-wrap             true
         :text                  (:content opts "")
         extra-inits))
```

No feature stack yet — just the look-and-feel setup.

Verification:

1. At the REPL (headless `start-nrepl` with `:dev`, connected via
   `clj-nrepl-eval`): `(load-file "src/llm_memory/ui/markdown_editor.clj")`
   — expect no errors. Do **not** `require :reload` / `:reload-all` —
   SWT-UI-GUIDE §22 prefers `load-file` / targeted redefinition so running
   UI state survives the edit.
2. Use `llm-memory.ui.util/show` (SWT-UI-GUIDE §3) to display a test shell
   containing just `(markdown-editor {:content "# Hello"} (id! :ui/test))`.
3. Screenshot: confirm the StyledText renders with the dark theme, the
   purple-alchemy selection colour, word-wrap on.
4. Dispose the test shell.

## Step 2 — Add the full feature stack (CDT-idiomatic)

Replace Step 1's theming shell with the full init. **All event handling
uses CDT `(on ...)` forms — no `(reify ModifyListener ...)` or raw
`.addModifyListener`** (§4). State atoms live in a `let` at the top of
the function; the modify listener closes over them.

**Prerequisite ns changes.** In `ui.markdown-editor`'s `ns` form:

1. Add `with-property` to the existing `ui.SWT` `:refer` vector:
   ```clojure
   [ui.SWT :refer [async-exec! on styled-text with-property |]]
   ```
2. Add a static `:require` for link-preview:
   ```clojure
   (:require
    ...
    [llm-memory.ui.link-preview :as link-preview]
    ...)
   ```

The link-preview `:require` is cycle-free: `link_preview.clj` does not
`:require` `markdown-editor` statically — it uses `requiring-resolve`
at runtime for one symbol
([link_preview.clj:353](../../winze-server/src/llm_memory/ui/link_preview.clj#L353)).
`with-property` is only used in `main_window.clj`'s migration (Step 4),
but referring it from `markdown-editor` keeps both ns forms tidy if a
future widget helper here needs it.

**Deliberate design choice: defer the initial `.setText` into the
post-construct `fn`.** Not a correctness necessity — CDT applies inits
in argument order per [ui/inits.clj:74-88](file:///Users/dorme/code/ui/clojure-desktop-toolkit/src/ui/inits.clj#L74-L88),
so placing `:text content` AFTER the `(on e/modify-text ...)` init
would fire the listener with the correct content. The driver is
**symmetry with `editor-set-text!`**: both code paths apply content
via the same `binding [*suppressing-modify?* true]` block, so there
is exactly one place where "programmatic content replacement" is
defined. Inline `:text content` would require a separate suppression
strategy for the initial load, doubling the number of ways the
listener is told "skip record-edit! / dirty / save."

```clojure
(defn markdown-editor
  "See MARKDOWN-EDITOR-WIDGET-CONTEXT.md for the full contract."
  [{:keys [content abs-path rel-path root-uri on-change
           initial-line initial-caret
           spellcheck? content-assist? link-nav? pair-wrap?]
    :or   {content          ""
           spellcheck?      true
           content-assist?  true
           link-nav?        true
           pair-wrap?       true}}
   & extra-inits]
  (let [dirty?      (atom false)
        save-future (atom nil)
        history     (atom {:undo [] :redo [] :last-snap nil})
        ;; Canonical-text mirror. Initialised eagerly so `@(editor-text
        ;; st)` returns the starting value before the widget has
        ;; processed its own initial .setText. `add-watch` does NOT
        ;; fire on registration — only on subsequent reset!/swap! —
        ;; so consumers that want a projected-state mirror should seed
        ;; from `@(editor-text st)` at registration time AND register
        ;; the watch for subsequent changes. The modify listener
        ;; reset!s the atom again during the initial .setText under
        ;; *suppressing-modify?* (identical value, harmless re-fire)
        ;; so a consumer that registered a watch BEFORE the post-
        ;; construct fn runs will see one synthetic fire with the
        ;; seed value.
        text-atom   (atom content)]
    (apply styled-text (| SWT/MULTI SWT/V_SCROLL SWT/WRAP)
           :font                  @res/body-font
           :background            @res/color-mine-shaft
           :foreground            @res/color-crystal-white
           :selection-background  @res/color-royal-purple
           :selection-foreground  @res/color-pure-white
           :word-wrap             true
           ;; NB: :text is NOT set here. Initial content is applied in
           ;; the post-construct fn under *suppressing-modify?* so the
           ;; same "programmatic content replacement" path is used for
           ;; the initial load AND editor-set-text! external refreshes.

           (on e/modify-text [_props st _e]
               (let [text (.getText st)]
                 (reset! text-atom text)
                 (apply-theme! st text)
                 (when-not (or *restoring-snapshot?*
                               *suppressing-modify?*)
                   (record-edit! st history))
                 (when-not *suppressing-modify?*
                   (reset! dirty? true)
                   (when abs-path
                     (reset! save-future
                             (schedule-save! @save-future abs-path text
                                             #(reset! dirty? false)))))
                 (when on-change
                   (try (on-change text)
                        (catch Throwable t
                          (log/error t "on-change callback threw"))))))

           ;; Post-construction setup. Listeners are already attached
           ;; here, so the modify listener will see the binding below
           ;; when we apply the initial content.
           (fn [_props st]
             (.setData st "scope"          :editor)
             (.setData st "md/dirty?"      dirty?)
             (.setData st "md/save-future" save-future)
             (.setData st "md/history"     history)
             (.setData st "md/text"        text-atom)
             (when abs-path (.setData st "md/abs-path" abs-path))
             (when pair-wrap?  (install-pair-wrap! st))
             (when (and content-assist? abs-path)
               (install-content-assist-triggers! st abs-path))
             (when (and link-nav? abs-path)
               (install-link-interaction! st abs-path rel-path root-uri))
             ;; link-preview is NOT gated on abs-path — it only needs
             ;; rel-path + root-uri. Synthetic callers with a rel-path
             ;; context (e.g. work-item tabs anchored to a dir) get
             ;; hover previews for free.
             (when (and rel-path root-uri)
               (link-preview/install-link-preview! st rel-path root-uri))
             ;; Apply initial content under suppression. Modify
             ;; listener resets text-atom, applies theme, fires
             ;; on-change — skips record-edit!, dirty, save.
             (binding [*suppressing-modify?* true]
               (.setText st content))
             ;; Install spellcheck AFTER the initial .setText so its
             ;; internal `(reschedule!)` (see markdown_editor.clj:548)
             ;; schedules the first pass on the real content, not on
             ;; "". The CDT modify listener already themed the content
             ;; during .setText above; spellcheck's own ModifyListener
             ;; rescheduling on future edits works identically whether
             ;; it was installed before or after the initial setText.
             (when spellcheck? (install-spellcheck! st))
             ;; Scroll sync — setTopIndex / setCaretOffset operate on
             ;; the logical text model; no layout required. Running
             ;; this synchronously (not inside async-exec!) lets us
             ;; seed :last-snap with the post-scroll snapshot below,
             ;; so the first user edit's undo target is the restored
             ;; viewport. Deterministic, no race with typing.
             (when initial-line
               (scroll-to-line! st initial-line))
             ;; Single :last-snap seed — post-scroll, pre-caret. The
             ;; first user edit's record-edit! pushes this snapshot
             ;; onto undo; Cmd+Z restores to this restored viewport.
             (swap! history assoc :last-snap (capture-snapshot st))
             ;; Caret restore DOES need layout (line-visible? reads
             ;; getClientArea), so defer into async-exec!. If the user
             ;; types before this fires, record-edit! has already
             ;; pushed the post-scroll :last-snap onto undo and
             ;; updated :last-snap to the keystroke snapshot — undo
             ;; consistency is preserved. The caret async-exec does
             ;; not touch text, so it doesn't invalidate :last-snap.
             (when initial-caret
               (async-exec!
                (fn []
                  ;; Guard against rapid toggle — the shell may have
                  ;; disposed the widget between construction and
                  ;; this async firing (Cmd+E × 2, tab-close, etc).
                  (when-not (.isDisposed st)
                    (let [text-len   (count (.getText st))
                          safe-caret (min initial-caret
                                          (max 0 (dec text-len)))]
                      (when (line-visible? st safe-caret)
                        (.setCaretOffset st safe-caret))))))))

           extra-inits)))
```

Notes:

- **No `on e/widget-disposed` listener.** See **Disposal semantics** in
  the CONTEXT doc. The current inline code in `toggle-mode!` has none;
  every keystroke schedules a save, so a tab closed immediately after
  a keystroke leaves the scheduled task holding the final text. The
  `#(reset! dirty? false)` callback touches only Clojure state, safe
  after widget disposal.
- **No `editor-id`.** Callers retrieve the StyledText via
  `(wrapper-child wrapper)` — the pattern used elsewhere in
  `main_window.clj`. Introducing `(id! editor-id)` would leak an
  `app-props` entry per view↔edit toggle.
- **Scroll is sync; caret is async.** `scroll-to-line!` touches only
  the logical text model (`setTopIndex`, `setCaretOffset`,
  `getOffsetAtLine`) — no layout required. `line-visible?` reads
  `getClientArea` + `getLineHeight`, which need layout; that's the
  only reason the caret-restore lives in `async-exec!`. Keeping
  `:last-snap` seeded inside the sync block eliminates the undo-race
  that existed when scroll + re-seed shared the async batch.
- **Text atom is reset first in the listener.** `add-watch`
  subscribers see the new text before `apply-theme!` mutates styling.
  Watchers that inspect both text and styling (future outline panel,
  live TOC) see them in construction order. `reset!` fires watchers
  **synchronously** — watchers MUST NOT mutate `st` from their
  callback (no `.setText`, `.replaceTextRange`, `editor-set-text!`);
  defer widget mutation via `async-exec!` instead, or the listener
  re-enters during the first `reset!`.
- **`on-change` runs outside `*suppressing-modify?*` and is wrapped
  in try/catch.** Outside suppression is the correctness fix for
  tab-title updates on external refresh — the current inline listener
  calls `update-tab-title!` unconditionally on every modify, and any
  consumer of `:on-change` expects the same. The try/catch guards
  against a throwing third-party callback (future chat panel /
  work-item tab) breaking the modify dispatch chain for subsequent
  keystrokes.
- `async-exec!` and `with-property` are `:refer`'d in the ns form (see
  **Prerequisite ns changes** above) — no qualified
  `ui.SWT/async-exec!` needed.

Verification (REPL):

1. Targeted redefinition (§22): `(in-ns 'llm-memory.ui.markdown-editor)`
   then paste the new `defn`. Do **not** `:reload-all`.
2. `show` a test shell:
   ```clojure
   (ui (llm-memory.ui.util/show
         (shell SWT/SHELL_TRIM "test" :layout (FillLayout.)
                (markdown-editor {:content "# Hi\n\nteh quick"}))))
   ```
3. Screenshot: Markdown heading styling + wavy underline on "teh".
4. **Initial-load invariants** (critical — these confirm the suppressed
   initial setText worked):
   - `@(.getData st "md/dirty?")` → `false` immediately after creation.
   - `(:undo @(.getData st "md/history"))` → `[]` immediately after
     creation (no spurious undo frame from the initial content apply).
   - `(:last-snap @(.getData st "md/history"))` → a snapshot whose
     `:text` equals the initial content (seeded synchronously
     post-`.setText`, post-`scroll-to-line!`).
   - `@(.getData st "md/text")` → equals the initial content. Also
     equals `(.getText st)`.
5. **Atom-watch baseline**: before editing, register an `add-watch`
   on `(.getData st "md/text")` that `swap!`s a sink atom:
   ```clojure
   (let [sink (atom [])]
     (add-watch (.getData st "md/text") :sink
                (fn [_ _ _ new-v] (swap! sink conj new-v)))
     ;; ... drive edits below ...
     sink)
   ```
6. Type into the widget (visual). Confirm:
   - `(.getData st "scope")` → `:editor`
   - `@(.getData st "md/dirty?")` → `true` after an edit
   - `(:undo @(.getData st "md/history"))` grows by one entry per edit
   - `@sink` contains the post-edit text (one entry per keystroke)
   - `@(.getData st "md/text")` equals `(.getText st)` at all times
7. Dispose the test shell.

## Step 3 — Add `*suppressing-modify?*` + accessor helpers

In `ui.markdown-editor`, place `*suppressing-modify?*` next to the
existing `*restoring-snapshot?*` (around line 562). The accessor
helpers go colocated with the widget.

```clojure
(def ^:dynamic *suppressing-modify?*
  "True while a programmatic content replacement is in progress (e.g.
  external-file refresh via `editor-set-text!`, or the widget's own
  initial content-apply). The modify listener reads this to decide
  whether to treat the incoming ModifyEvent as a fresh user edit (push
  undo, flip dirty, schedule save) or as a synthetic replacement (skip
  those). The `md/text` atom reset, `apply-theme!`, and `on-change`
  still fire regardless — see CONTEXT §\"Dynamic var suppression\".

  Scoped to the calling thread — safe because SWT ModifyListeners fire
  synchronously on the UI thread during `.setText`."
  false)

(defn editor-dirty?
  "True if the editor has unsaved changes. Returns false defensively for
  widgets not built by `markdown-editor` (no `md/dirty?` data) — the
  `some->` guard keeps callers like `editor_commands.clj` safe if they
  ever point at a non-editor StyledText.

  No `^StyledText` type hint so tests can pass a `reify`-based stub;
  the only method called is `.getData`, which is dispatched by name."
  [st]
  (boolean (some-> (.getData st "md/dirty?") deref)))

(defn editor-history-atom
  "Return the history atom, or nil for widgets not built by
  `markdown-editor`. Callers pass the returned atom to `undo!` / `redo!`."
  [st]
  (.getData st "md/history"))

(defn editor-text
  "Return the canonical-text atom (an atom<String>), or nil for widgets
  not built by `markdown-editor`.

  The atom is `reset!`-ed synchronously by the modify listener on every
  content change — user edits, undo/redo restores, external-file
  refreshes via `editor-set-text!`, and the widget's own initial
  content-apply. `(reset!)` fires on the UI thread, so any `add-watch`
  callbacks run on the UI thread.

  Prefer `add-watch` on this atom to polling `(.getText st)` across
  threads: `@(editor-text st)` is safe from any thread, but
  `(.getText st)` is not. See CONTEXT §\"Canonical text atom\"."
  [st]
  (.getData st "md/text"))

(defn editor-flush!
  "Flush pending save to disk synchronously. No-op for synthetic editors
  (no `:abs-path`) or widgets not built by `markdown-editor`.

  Call this ONCE, immediately before `.dispose` (see Step 5). Do not
  call from a hot path: a keystroke arriving between flush and dispose
  would schedule a new save on a soon-to-be-disposed widget.

  Clears `dirty?` unconditionally — acceptable because this is only
  called at teardown, so no observer sees the transient mismatch.

  Must be called on the UI thread."
  [^StyledText st]
  (when-let [abs-path (.getData st "md/abs-path")]
    (when-let [sf (.getData st "md/save-future")]
      (flush-save! @sf abs-path (.getText st))
      (reset! sf nil))
    (when-let [d (.getData st "md/dirty?")]
      (reset! d false))))

(defn editor-set-text!
  "Replace editor content. Skips when identical, when the editor is
  dirty, or when the widget has no `md/history` data (not a
  markdown-editor widget).

  Binds `*suppressing-modify?*` around `.setText` so the modify listener
  does NOT push an undo frame, flip dirty, or schedule a save on the
  synthetic change. The `md/text` atom reset, `apply-theme!`, and
  `on-change` still fire — the atom mirrors the new content so
  watchers see it, the theme reapplies to match the new content, and
  callers like `update-tab-title!` see the external change.

  After `.setText`, re-seeds `:last-snap` to the NEW content. Without
  this, `:last-snap` would still hold the pre-refresh snapshot (since
  `record-edit!` was skipped by the suppression). The next real user
  edit would then push the stale pre-refresh snapshot onto undo; Cmd+Z
  would silently revert the external change. The re-seed is the only
  reason this helper needs to be a function instead of a `.setText`
  call-site.

  Must be called on the UI thread. `.setText` requires it, and the
  `binding` only reaches the listener because SWT fires modify events
  synchronously during `.setText`.

  Returns nil. Effectful helper; do not chain off the return."
  [^StyledText st text]
  (when-let [history-atom (.getData st "md/history")]
    (when (and (not= text (.getText st))
               (not (editor-dirty? st)))
      (binding [*suppressing-modify?* true]
        (.setText st text))
      (swap! history-atom assoc :last-snap (capture-snapshot st))))
  nil)
```

**Note the guard change.** The original draft checked
`(and (not= text (.getText st)) (not (editor-dirty? st)))`. With no
`md/dirty?` data, `(editor-dirty? st)` returns `false` — so a non-editor
StyledText would have its text replaced. That's wrong: the helper is a
no-op on non-editor widgets. The revised guard requires
`(.getData st "md/history")` to be present before doing anything,
which is a stronger signal that the widget was built by
`markdown-editor`.

### RCF tests for accessor guards

Add to `ui.markdown-editor`, colocated with the other `(tests ... :rcf)`
blocks. These are pure-logic tests — the accessors are declared
without `^StyledText` type hints, so Clojure dispatches `.getData` by
name via reflection. That lets us use a minimal `reify`-based stub
that exposes a `getData` method without ever constructing an SWT
widget (a `(proxy [StyledText] [nil 0] ...)` would throw at
construction because SWT's `Composite` super-constructor rejects a
null parent).

`editor-flush!` and `editor-set-text!` retain `^StyledText` hints (they
call `.getText`/`.setText`) and are NOT in the RCF block — test them
via `show` in the Verification section.

```clojure
(definterface IWidgetDataStub
  (getData [^String key]))

(tests
 (let [stub (fn [data]
              (reify IWidgetDataStub
                (getData [_ k] (get data k))))]

   ;; editor-dirty? returns false for non-editor widget (no md/dirty? data)
   (editor-dirty? (stub {}))                           := false
   ;; editor-dirty? derefs the atom when present
   (editor-dirty? (stub {"md/dirty?" (atom true)}))    := true
   (editor-dirty? (stub {"md/dirty?" (atom false)}))   := false

   ;; editor-history-atom returns nil for non-editor widget
   (editor-history-atom (stub {}))                     := nil
   ;; editor-history-atom returns the atom as-is
   (let [h (atom {:undo [] :redo []})]
     (editor-history-atom (stub {"md/history" h}))    := h)

   ;; editor-text returns nil for non-editor widget
   (editor-text (stub {}))                             := nil
   ;; editor-text returns the atom as-is
   (let [t (atom "hi")]
     (editor-text (stub {"md/text" t}))               := t))
 :rcf)
```

The `definterface` form lives at the top of the file alongside other
type declarations (or in a nearby `^:private` position — it exists
only to satisfy the reflective `.getData` call from the stub). If the
`definterface` feels heavyweight, an alternative is to test the
accessors against a real widget inside a `show`-displayed shell —
slightly more ceremony but no stub machinery.

Verification:

1. Load-file; run the RCF tests (via dev-mode REPL — production server
   does not have RCF enabled, per [winze/CLAUDE.md](../../CLAUDE.md)).
2. Test each helper against a `show`-displayed widget.
3. `editor-set-text!` skips when dirty: `(reset! (.getData st "md/dirty?") true)`,
   then call `editor-set-text!` with new content; text unchanged.
4. `editor-set-text!` does **not** push undo:
   ```clojure
   (let [h (editor-history-atom st)
         before (count (:undo @h))]
     (editor-set-text! st "new content from disk")
     (count (:undo @(editor-history-atom st))) ;=> still `before`
     )
   ```
5. `editor-set-text!` does **not** flip dirty: after call, `(editor-dirty? st)` is still false.
6. **`editor-set-text!` re-seeds `:last-snap`**:
   ```clojure
   (editor-set-text! st "external edit v1")
   (:text (:last-snap @(editor-history-atom st))) ;=> "external edit v1"
   ;; simulate a user keystroke — manually type, or fire a synthetic modify
   ;; then Cmd+Z — should revert only the keystroke, NOT "external edit v1":
   (md-editor/undo! st (editor-history-atom st))
   (.getText st) ;=> "external edit v1" (NOT the pre-refresh text)
   ```
7. **`on-change` and text-atom fire under suppression**: set an
   `on-change` capture atom via extra-inits and an `add-watch` sink on
   `(editor-text st)`, call `editor-set-text!`, confirm both observe
   the new text. This is the fix that keeps tab titles + projected
   state in sync with external file changes.
8. **`editor-set-text!` is a no-op on non-editor StyledTexts**: on a
   plain StyledText with no `md/history` data, `editor-set-text!`
   leaves the text unchanged. Paired with the RCF tests above, this
   confirms the new guard.

## Step 4 — Migrate `toggle-mode!` view→edit branch

> **Co-dependency: land Step 6 atomically with this step.** Step 4 moves
> the undo/redo history atom onto the widget via `.setData`, but the
> `:editor/undo` / `:editor/redo` command handlers in
> `register-workbench-commands!` still read `:history` from
> `active-file-entry` (the stale open-files atom). Between Steps 4 and
> 6, Cmd+Z and Cmd+Shift+Z silently do nothing. Apply Step 6 in the
> same commit / REPL session as Step 4, and run the combined
> verification below.

Edit `main_window.clj`. Replace the view→edit branch of `toggle-mode!`
([main_window.clj:522-580](../../winze-server/src/llm_memory/ui/main_window.clj#L522-L580)) with:

```clojure
((with-property :layout (FillLayout.)
   :margin-width  12
   :margin-height 12)
 app-props wrapper)
(.setBackground wrapper @llm-memory.ui.resources/color-mine-shaft)
(child-of wrapper app-props
  (md-editor/markdown-editor
    {:content       (slurp abs-path)
     :abs-path      abs-path
     :rel-path      rel-path
     :root-uri      root-uri
     :initial-line  from-line
     :initial-caret (:caret @scroll-state)
     :on-change     #(update-tab-title! abs-path %)}))
(.setFocus (wrapper-child wrapper))
(swap! open-files update abs-path assoc :mode :edit)
```

`with-property` must be `:refer`'d from `ui.SWT` in `main_window.clj`'s
`ns` form — check the existing `:refer` vector and add it there if
absent.

The tail of `toggle-mode!` — `(.layout wrapper)` at
[main_window.clj:599](../../winze-server/src/llm_memory/ui/main_window.clj#L599)
and the subsequent `(update-edit-button!)` — is unchanged. Both sit
outside the view/edit branches and run after either transition.

Changes from the earlier draft:

- **`with-property` replaces `doto` + Java field mutation** (SWT-UI-GUIDE
  §33). `with-property` is a CDT init returning `(fn [props parent] ...)`;
  we invoke it imperatively by passing `app-props` and `wrapper`. It
  constructs a fresh `FillLayout` with `:margin-width` / `:margin-height`
  applied and calls `.setLayout` on the wrapper in one form.
  `.setBackground` stays a separate line — colour is not a sub-object
  with its own properties, so `with-property` adds no value there.
- **No `editor-id`, no `(id! editor-id)`.** `wrapper-child` retrieves
  the StyledText. Prevents an `app-props` leak that accumulates one
  entry per toggle.
- **No `:editor-id` field** in the `open-files` update. The edit-mode
  StyledText is always `(wrapper-child wrapper)`.
- **Caret restoration moves into the widget via `:initial-caret`.** The
  earlier draft restored the caret synchronously in `toggle-mode!` after
  the widget returned — but the widget's caret restore now lives in
  its own `async-exec!` (scroll is sync; only caret needs async because
  `line-visible?` needs layout). Routing caret restoration through
  `:initial-caret` keeps the widget's async ordering authoritative.
  `nil` for `:initial-caret` is a no-op — callers with no `scroll-state`
  don't need special handling.

Verification (visual, live server):

1. Screenshot current edit-mode view of a file (pre-refactor baseline).
2. Targeted redefine `toggle-mode!` via `in-ns` + `defn`.
3. Open a file, Cmd+E to enter edit mode.
4. Screenshot — compare to baseline. Theme, font, padding, focus, caret
   must all match.
5. Run edit-only test matrix rows: syntax highlighting, spellcheck,
   pair-wrap, content-assist trigger, wiki-link Cmd+click, hover
   preview, auto-save, dirty tracking, tab-title update.
6. Toggle stability: Cmd+E 5× quickly; `(count (.getChildren wrapper))`
   stays at 1; `(count @app-props)` is stable across toggles.
7. Scope wiring: `(.getData (wrapper-child wrapper) "scope") := :editor`
   and `(keybindings/active-scope) := :editor` with focus on the editor.
8. **Apply Step 6 now** (undo/redo handler migration — it must land
   with this step). Then: edit; Cmd+Z reverts; Cmd+Shift+Z redoes.
   Without Step 6 applied, both keys are silent no-ops because the
   handlers still read the stale `open-files :history` atom.
9. Do **not** Cmd+E back to view yet — next step.

## Step 5 — Migrate `toggle-mode!` edit→view branch

Replace the edit→view branch. Retrieve the StyledText via `wrapper-child`
(matching Step 4). Flush via `editor-flush!` instead of the inline
`flush-save!` + `reset! dirty?` pair.

```clojure
(let [st (wrapper-child wrapper)]
  (md-editor/editor-flush! st)
  (reset! scroll-state {:line  (.getTopIndex st)
                        :caret (.getCaretOffset st)})
  (.dispose st)
  (doto wrapper
    (.setLayout (FillLayout.))
    (.setBackground nil))
  (let [content  (slurp abs-path)
        metadata (when root-uri
                   (search/file-metadata-by-path root-uri rel-path))
        html     (search/file-page content rel-path metadata root-uri)
        brow-id  (next-tab-id!)]
    (child-of wrapper app-props
              (custom-browser (id! brow-id) :text html))
    (let [brow (get @app-props brow-id)]
      (scroll-browser-to-line! brow (or (:line @scroll-state) 0))
      (.setFocus brow)
      (when (= wrapper-id (:wrapper-id @live-search-state))
        (swap! app-props assoc :ui/live-search-browser brow))))
  (swap! open-files update abs-path assoc :mode :view))
```

No `:editor-id` read/write — same as Step 4, StyledText lives only
as `wrapper-child`.

Verification:

1. Cmd+E back to view; screenshot; compare to pre-refactor view.
2. Confirm view renders latest edits (auto-save flushed by
   `editor-flush!`).
3. Round-trip view↔edit 5×; `(count (.getChildren wrapper))` stays 1;
   `(count @app-props)` stays stable (modulo the fresh `brow-id`
   allocated per transition — that one is cleaned up by
   `cleanup-tab-id!` on tab close).
4. Undo/redo (**requires Step 6 already landed with Step 4**): edit,
   Cmd+Z, Cmd+Shift+Z — confirm both work. If Cmd+Z is a silent no-op,
   Step 6's handler migration was skipped.

## Step 6 — Migrate undo/redo command handlers

> **Land this together with Step 4.** Step 4's widget switch moves the
> active history atom to widget data; this step retargets the handlers
> to read from the widget. Between them, Cmd+Z is a silent no-op.
> Step 4's verification cannot complete without this step also applied.

In `register-workbench-commands!` ([main_window.clj:1026-1049](../../winze-server/src/llm_memory/ui/main_window.clj#L1026-L1049)),
the `:editor/undo` and `:editor/redo` handlers currently pull `:history`
from `active-file-entry`. Change them to pull from the widget:

```clojure
:action (fn []
          (async-exec!
           (fn []
             (when-let [st (active-styled-text)]
               (when-let [h (md-editor/editor-history-atom st)]
                 (md-editor/undo! st h)
                 (md-editor/apply-theme! st (.getText st)))))))
```

(Same pattern for redo — `when-let [h ...]` guards the handler body.)

**Why the nested `when-let`.** `editor-history-atom` returns `nil` for
StyledTexts not built by `markdown-editor` (see Step 3's defensive
guards). Passing `nil` to `undo!` / `redo!` hits `(let [{:keys [undo]}
@history] ...)` at
[markdown_editor.clj:597](../../winze-server/src/llm_memory/ui/markdown_editor.clj#L597) /
[markdown_editor.clj:612](../../winze-server/src/llm_memory/ui/markdown_editor.clj#L612) —
`deref` on `nil` → NullPointerException. Today `active-styled-text`
only returns the edit-mode file-tab widget, so in practice the atom is
always present — but mirroring the accessors' defensive contract here
means future consumers (synthetic-editor shells, chat panels, work-item
tabs) can use the same handler plumbing without a latent crash.

`editor_commands.clj` is **not** touched — Step 0's audit confirmed it
reads the active StyledText from its own function registry
([editor_commands.clj:26-37](../../winze-server/src/llm_memory/ui/editor_commands.clj#L26-L37)),
never from `open-files` / `active-file-entry`, so none of its commands
depend on the fields being removed in Step 8.

Verification:

1. Edit, Cmd+Z, Cmd+Shift+Z work as before (happy path).
2. **Nil-history guard**: at the REPL, simulate the `editor-history-atom
   returns nil` branch by temporarily rebinding the accessor via
   `with-redefs` and confirm Cmd+Z is a silent no-op instead of an
   NPE. Example:
   ```clojure
   (with-redefs [md-editor/editor-history-atom (constantly nil)]
     ((:action (commands/get-command :editor/undo))))
   ;=> nil, no exception in the log
   ```
   Restore the real accessor after the check.

## Step 7 — Migrate `refresh-open-tabs!`

In `main_window.clj`, the edit-mode branch of `refresh-open-tabs!`
([main_window.clj:336-340](../../winze-server/src/llm_memory/ui/main_window.clj#L336-L340)) reaches into the StyledText
directly:

```clojure
(instance? StyledText child)
(when-not (or (= new-content (.getText child))
              @dirty?)
  (.setText child new-content)
  (md-editor/apply-theme! child new-content))
```

Replace with:

```clojure
(instance? StyledText child)
(md-editor/editor-set-text! child new-content)
```

Two pieces of the current inline refresh survive — `editor-set-text!`
preserves them internally:

1. The `@dirty?` guard — now reads widget state (`editor-dirty?`).
2. The `apply-theme!` call — now runs inside the widget's own modify
   listener on `.setText`.

Three pieces are NEW correctness fixes introduced by this refactor
(the current inline code has these bugs silently):

3. **The `*suppressing-modify?*` binding** — without this, `.setText`
   would fire the modify listener which would push a spurious undo
   frame, flip dirty→true, and schedule a redundant self-save. (The
   `md/text` atom, `apply-theme!`, and `on-change` are deliberately
   NOT suppressed — watchers, theme, and tab titles all need to flow
   through on external refresh; see Step 2 notes.)
4. **The `:last-snap` re-seed** — without this, `record-edit!` stays
   skipped during the suppressed `.setText`, which leaves `:last-snap`
   pointing at the pre-refresh snapshot. The next real user edit would
   then push the stale snapshot onto undo, and Cmd+Z would silently
   revert the external change. The re-seed is why `editor-set-text!`
   must be a function, not a `.setText` call-site.
5. **The `md/history`-present guard** added in Step 3 means
   `editor-set-text!` is a no-op on non-editor StyledTexts. The
   current inline code always `.setText`s; the new version only
   operates on widgets built by `markdown-editor`. In practice
   `refresh-open-tabs!` only ever gets editor StyledTexts (edit-mode
   tabs) so this is a correctness tightening, not a behavioural
   change.

Remove the `dirty?` destructure from the outer `let` in
`refresh-open-tabs!` since nothing else reads it there.

Verification:

1. Open a file in edit mode; make it dirty (type a character).
2. Externally modify the file on disk.
3. Editor does NOT refresh (dirty skip).
4. Wait for auto-save (1.5 s); editor now clean.
5. Externally modify again; editor refreshes to new content **and the
   tab title updates to match the new content** (confirms `on-change`
   fires outside `*suppressing-modify?*`).
6. Type one character, then Cmd+Z.
7. **Cmd+Z reverts only the typed character, NOT to the pre-refresh
   text.** (If Cmd+Z does revert to pre-refresh, `editor-set-text!`
   failed to re-seed `:last-snap` — recheck Step 3.)
8. **Multi-keystroke variant**: type three characters, Cmd+Z three times
   — reverts each keystroke in order, landing on the externally-refreshed
   content. The Cmd+Z immediately following the third undo may traverse
   prior history beyond the refresh; that is accepted scope (see
   CONTEXT §Out of scope).
9. **Tab title + text atom during refresh**: register an `add-watch`
   on `(md-editor/editor-text st)`; externally edit the file; confirm
   the watch fires with the new content **on the UI thread**. The tab
   title is also updated (from `on-change`).
10. **File `mtime` after step 5 matches the external edit time**, not
    some moment ~1.5 s later from a redundant self-save.
11. **find_replace.clj smoke test** — `find_replace.clj:98` calls
    `(requiring-resolve 'llm-memory.ui.markdown-editor/apply-theme!)`
    to re-theme after a replace. Confirm it still works post-refactor:
    open a file, trigger find-and-replace (Cmd+F / Cmd+Opt+F), perform
    a replace; editor re-themes correctly. This guards against an
    accidental rename of `apply-theme!` during the widget refactor and
    against the require cycle the plan explicitly avoids (see Step 0
    task 8).

## Step 8 — Clean up stale `open-files` entry fields

`open-files` entry creation ([main_window.clj:233-243](../../winze-server/src/llm_memory/ui/main_window.clj#L233-L243) and
[main_window.clj:794-804](../../winze-server/src/llm_memory/ui/main_window.clj#L794-L804)) initialises:

```clojure
:dirty?       (atom false)
:history      (atom {:undo [] :redo []})
:save-future  (atom nil)
:scroll-state (atom nil)
```

`dirty?`, `history`, `save-future` now live on the widget in edit mode.
They have no meaning when the tab is in view mode. Remove these three
keys from **both** entry-creation sites.

`scroll-state` stays — it's cross-widget coordination owned by the tab,
not the widget.

After removing the fields, update `toggle-mode!`'s outer destructure
([main_window.clj:505-506](../../winze-server/src/llm_memory/ui/main_window.clj#L505-L506)):

```clojure
;; Before
(when-let [{:keys [wrapper-id mode history save-future rel-path root-uri
                   dirty? scroll-state]}
           (get @open-files abs-path)]

;; After
(when-let [{:keys [wrapper-id mode rel-path root-uri scroll-state]}
           (get @open-files abs-path)]
```

The pre-switch flush block at lines 510–513:

```clojure
(when (= mode :edit)
  (md-editor/flush-save! @save-future abs-path (.getText child))
  (reset! dirty? false))
```

becomes dead — Step 5's inline `editor-flush!` call inside the
edit→view branch replaces it. Delete it.

Grep once more for `:dirty?`, `:history`, `:save-future` to confirm no
orphan readers anywhere in `main_window.clj`.

### Delete dead 1-arg `install-link-interaction!`

Step 0 task 9 confirmed the single-arg arity at
[markdown_editor.clj:404-405](../../winze-server/src/llm_memory/ui/markdown_editor.clj#L404-L405)
has no callers. Delete it — the 3-arg arity is the only live form
after this refactor. Before deleting, re-grep `winze/` one last time:

```bash
grep -rn 'install-link-interaction!' winze/
```

Expect only the 3-arg call site in the widget's post-construct `fn`.
If a 1-arg call site appears (unlikely), add it to this step's migration
list before deleting the arity.

Verification: run the full test matrix once more. Nothing should
regress.

## Step 9 — Final verification sweep + install

The CONTEXT test matrix tags each row `[V]` (visual) or `[R]` (REPL /
data-state). They need different evidence:

1. **`[V]` rows** — screenshot before and after. Save to a scratch
   location (e.g. `/tmp/md-editor-<row>.png`) for review in this task;
   delete after sign-off. Screenshots are **not** committed to the
   repo — they're confidence artefacts, not part of the plan archive.
2. **`[R]` rows** — capture the REPL form and its result in a
   verification scratch buffer or inline in this plan under the
   relevant step. Each row has a concrete assertion in the CONTEXT doc
   (`:= ...` form); paste the actual result beside it.
3. `cd winze/winze-server && make install`.
4. Graceful shutdown of the currently running winze via nREPL:
   `(llm-memory.ui.main-window/quit!)`.
5. Let the MCP proxy auto-restart on the next search call, or manually
   trigger via `/search-plans test`.
6. Verify the restarted server edit-mode works identically to the
   live-REPL version captured above.

## Step 10 — Archive

Move the plan + context + any scratch notes to
`Plans/complete/markdown-editor-widget/` per winze's convention. Rename to
drop the `MARKDOWN-EDITOR-WIDGET-` prefix.

## Rollback

Every step except 4, 5, 7, 8 is a pure addition — revertible by deleting
the new code. Steps 4, 5, 7, 8 edit `toggle-mode!` / `refresh-open-tabs!`
/ `open-files` creation. Keep a branch; if a step fails, `git restore` the
file and re-run the REPL verification from the last known-good step.

The `ns`-form edits (Step 2's `:require link-preview`, Step 4's
`:refer [... with-property]` in `main_window.clj`) are inert on
rollback — an unused refer or require is a lint warning at worst, not
a compile error. Leave them if partial progress has landed; they
become live again when the corresponding step is re-attempted.

## Out of scope — do not do in this task

- **Multi-Cmd+Z past an external refresh.** The immediate Cmd+Z after
  one post-refresh keystroke is guaranteed not to undo the refresh. A
  user who keeps pressing Cmd+Z through subsequent edits can reach
  pre-refresh history — that is inherited behaviour. If needed later,
  add a `:clear-undo-on-refresh?` option or a sentinel in `:undo`.
- Namespace split of `main_window.clj` into `ui.browser-factory`,
  `ui.watcher-bridge`, `ui.navigation`, `ui.toolbar`, `ui.shell`. This is
  a follow-up.
- Work-item tab / AI-chat panel. These are downstream consumers of the
  widget; building them is separate.
- Preserving undo history across view↔edit toggles (accepted regression).
- Any change to `custom-browser` or view-mode rendering.
