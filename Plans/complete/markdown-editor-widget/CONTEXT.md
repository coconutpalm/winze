# Markdown Editor Widget — Context

## Goal

Produce a reusable CDT init function `markdown-editor` that returns a
**bare `StyledText`** fully configured for Markdown editing. Drop anywhere in
a CDT tree and you get syntax highlighting, spellcheck (with suggestion
context menu), pair-wrap, content assist, wiki-link navigation, hover
preview, auto-save, and undo/redo — no wrapper, no controller map, no
imperative setup.

The widget must be decoupled from tabs, `open-files`, and the main window. The
existing file-tab edit-mode code in `toggle-mode!` becomes a short call to this
init function.

## Why

`main_window.clj` is 1200 lines. `toggle-mode!` alone is ~98 lines mixing
widget construction, event wiring, state atom setup, scroll coordination, and
tab-title updates. The Markdown editor is reused conceptually (future chat
prompts, work-item tabs, anything else that wants a Markdown text surface) but
cannot be reused physically because its configuration is tangled into the
tab-toggle code path.

Extracting a proper init function:

- Shrinks `toggle-mode!` to ~25 lines.
- Lets future UI (e.g. an AI-chat prompt) reuse the editor with one call.
- Removes implicit coupling to `open-files` / `update-tab-title!`.
- Aligns with the existing CDT pattern used by `custom-browser`
  ([main_window.clj:149-206](../../winze-server/src/llm_memory/ui/main_window.clj#L149-L206)).

## Non-goals

- **No padding Composite wrapper.** If you ask for a Markdown editor, you get
  a `StyledText` — nothing else. Callers add padding if they want it.
- **No tab-type registry.** Earlier design explored a dispatch layer over
  tabs. Dropped in favour of direct composition via the widget.
- **No rewrite of `ui.markdown-editor` internals.** The `install-*`,
  `apply-theme!`, `record-edit!`, `schedule-save!`, etc. functions already
  work — they just need to be called from the new init fn.
- **No main-window namespace split.** Pure refactor for a follow-up task.
- **No change to view-mode (Browser) behaviour.** This task touches only the
  edit-mode widget.

## Starting state

### Files to read

1. [winze/Plans/SWT-UI-GUIDE.md](../SWT-UI-GUIDE.md) — **mandatory**. Rules
   §4, §5, §7, §11, §22, §25, §26, §27, §28, §33 are directly load-bearing.
2. [main_window.clj:149-206](../../winze-server/src/llm_memory/ui/main_window.clj#L149-L206) — `custom-browser`, the factory-pattern
   model to mirror.
3. [main_window.clj:502-600](../../winze-server/src/llm_memory/ui/main_window.clj#L502-L600) — `toggle-mode!`. The edit-mode branch
   (lines 522-580) is the source-of-truth for what the widget currently sets
   up. Port this into the widget.
4. [markdown_editor.clj:929-978](../../winze-server/src/llm_memory/ui/markdown_editor.clj#L929-L978) — an existing but stale
   `markdown-editor` init function. **It has no call sites anywhere in the
   codebase.** Treat it as a starting skeleton; evolve or replace.

### The stale init function

`markdown-editor` at [markdown_editor.clj:929](../../winze-server/src/llm_memory/ui/markdown_editor.clj#L929) is 50 lines
and close in spirit to what we want, but gaps prevent parity with
`toggle-mode!`'s inline version:

| Feature | Stale fn | Needs |
|---|---|---|
| `install-link-interaction!` | passes only `abs-path` — wiki-link root resolution broken | pass `abs-path`, `rel-path`, `root-uri` |
| `link-preview/install-link-preview!` | absent | install it |
| Modify listener | `(on e/modify-text ...)` ✓ | **keep the CDT form** — do not regress to `(reify ModifyListener ...)` + `.addModifyListener` (§4) |
| Modify suppression | none | `*suppressing-modify?*` dynamic var gates the refresh path (record-edit!, dirty, save). `apply-theme!`, the `md/text` atom reset, and `on-change` still fire so theming stays consistent, watchers see the new content, and tab titles track external refreshes. Bound around both `editor-set-text!` and the widget's own initial content-apply. See **Dynamic var suppression** below |
| Undo/redo | `push-undo!` on save (coarse) | use `record-edit!` per modify (per-edit granularity) — matches `toggle-mode!` |
| Initial scroll | absent | accept `:initial-line`; call `scroll-to-line!` **synchronously** in the post-construct `fn` (it uses `setTopIndex`/`setCaretOffset` on the logical model — no layout required) |
| Initial caret | absent | accept `:initial-caret`; restore inside an `async-exec!` because `line-visible?` reads `getClientArea` which is only valid after layout |
| `:last-snap` seed (sync, post-scroll) | absent | seed inside the post-construct `fn` **after** the sync `scroll-to-line!` call. Undo-target is the restored viewport, not line 0. Only one seed — no race window. NB: caret restore is async, so `:last-snap` captures scroll-only — first-undo returns to the restored scroll line but caret position 0, not the restored caret |
| `:last-snap` re-seed (external refresh) | absent | `editor-set-text!` must re-seed after its own suppressed `.setText` — otherwise Cmd+Z immediately after an external refresh would silently revert the external edit |
| Canonical text atom | absent | expose `(atom content)` via `md/text` setData; `reset!` in the modify listener. Consumers watch this atom instead of polling `(.getText st)` across threads — see **Canonical text atom** below |
| State exposure | `dirty?`, `history`, `save-future` captured in closure, unreachable | store via `.setData` so callers can query / flush / reset |
| Feature toggles | none | `:spellcheck?`, `:content-assist?`, `:link-nav?`, `:pair-wrap?` (all default true; `:content-assist?` / `:link-nav?` silently no-op when `:abs-path` is nil) |
| Callback shape | `:on-dirty`, `:on-saved` | single `:on-change (fn [text])` is enough for tab-title updates. Callers that need a projected state observable instead of a callback should `add-watch` the `md/text` atom via `editor-text` |
| Undo/redo command source (in `main_window.clj`) | `(:history (active-file-entry))` | migrate `:editor/undo` / `:editor/redo` handlers to `(editor-history-atom (active-styled-text))` — not in the widget itself, but a required co-change |

Evolve the existing fn in `ui.markdown-editor` rather than creating a second
namespace — the `install-*` helpers live there, callers already alias it as
`md-editor`, and splitting would force more imports with no payoff.

### Dependencies already wired

- `ui.SWT` exports `styled-text` (CDT-generated per §6), `|`,
  `on`, `with-property`, `apply ... extra-inits` pattern.
- `ui.resources` exports `body-font`, `color-mine-shaft`,
  `color-crystal-white`, `color-royal-purple`, `color-pure-white` — all
  delay-deref'd registry entries.
- `ui.markdown-editor` exports all `install-*` functions,
  `apply-theme!`, `schedule-save!`, `flush-save!`, `record-edit!`,
  `capture-snapshot`, `scroll-to-line!`, `line-visible?`,
  `*restoring-snapshot?*`, `navigate-link-fn`.
- `ui.link-preview` exports `install-link-preview!` (takes
  `rel-path` and `root-uri`).
- **This task adds** `*suppressing-modify?*` (dynamic var) and the
  accessor helpers (`editor-dirty?`, `editor-history-atom`,
  `editor-flush!`, `editor-set-text!`, `editor-text`) to
  `ui.markdown-editor`. See **Dynamic var suppression** and
  **Canonical text atom** below for the semantics.
- **This task also adds** to `ui.markdown-editor`'s `ns` form:
  - a static `:require [llm-memory.ui.link-preview :as link-preview]`
    (cycle-free: `link_preview.clj` does not `:require` `markdown-editor`
    statically — it uses `requiring-resolve` at runtime only).
  - `with-property` to the existing `:refer` vector from `ui.SWT` — used
    by `toggle-mode!`'s migration (see **Migration target** below).

## Target API

```clojure
(defn markdown-editor
  "CDT init function: a StyledText configured as a Markdown editor.
   Returns the bare StyledText — no wrapper, no padding. Callers provide
   the Composite parent and any padding Composite they want around the widget.

   Options map (first arg):
     :content          initial text (default \"\")
     :abs-path         enables auto-save, link-interaction, content-assist
     :rel-path         enables wiki-link resolution + hover link preview
     :root-uri         enables wiki-link resolution + hover link preview
     :initial-line     scroll to this 0-based source line. Applied
                        synchronously before :last-snap is seeded so the
                        first undo target is the restored viewport.
     :initial-caret    restore caret to this character offset. Applied
                        inside an async-exec! because the visibility
                        guard reads getClientArea, which is only valid
                        after layout.
     :on-change        (fn [text]) called on ALL content changes — user
                        edits, external-file refresh, and the initial
                        content-apply. Must be idempotent. Fires on the
                        UI thread.
     :spellcheck?      default true
     :content-assist?  default true  (silently no-op unless :abs-path set)
     :link-nav?        default true  (silently no-op unless :abs-path set);
                        hover previews install whenever rel-path +
                        root-uri are both present, independent of
                        :link-nav? and :abs-path
     :pair-wrap?       default true

   Remaining args are `extra-inits` (id!, on-handlers, keyword props) applied
   to the underlying StyledText — same convention as `custom-browser`.

   Canonical text access:
     The widget publishes its current text via an atom at
     .setData \"md/text\", exposed by `(editor-text st)`. The atom is
     reset! on every content change from the modify listener. Consumers
     that need to observe text across threads should add-watch this atom
     instead of polling (.getText st). See 'Canonical text atom' in
     MARKDOWN-EDITOR-WIDGET-CONTEXT.md."
  [opts & extra-inits]
  ...)
```

### `extra-inits` ordering

`extra-inits` are applied AFTER the post-construct `fn`, so the initial
content-apply has already fired by the time they run. A caller that passes
`(on e/modify-text ...)` via `extra-inits` will NOT see the initial
`.setText`. Use `:on-change` for anything that must observe the initial
content or subsequent refreshes — it is the supported path.

**Caret caveat**: the widget's caret-restore runs inside an `async-exec!`
from the post-construct `fn` (it needs layout for `line-visible?`). A
caller that supplies `:initial-caret` AND also sets `.setCaretOffset`
from `extra-inits` will have the widget's async caret-restore fire
**after** the caller's sync set, silently clobbering it. Pass `nil`
for `:initial-caret` if you want to own the caret yourself.

### Accessor helpers (colocated in `ui.markdown-editor`)

```clojure
(defn editor-dirty?       [^StyledText st] ...)  ; @(.getData st "md/dirty?")
(defn editor-history-atom [^StyledText st] ...)  ; (.getData st "md/history")
(defn editor-text         [^StyledText st] ...)  ; (.getData st "md/text") — atom<String>
(defn editor-flush!       [^StyledText st] ...)  ; flush pending save, reset dirty
(defn editor-set-text!    [^StyledText st text]) ; setText + :last-snap re-seed if not dirty
```

`editor-set-text!` encapsulates the file-watcher refresh policy
([main_window.clj:337-340](../../winze-server/src/llm_memory/ui/main_window.clj#L337-L340)): skip if content matches or
`dirty?` is true; otherwise `.setText` under `*suppressing-modify?*` and
re-seed `:last-snap` so subsequent user edits don't push the stale
pre-refresh snapshot onto undo.

Naming note: `editor-history-atom` returns the history **atom** (callers
need it for `swap!` inside `undo!`/`redo!`). `editor-dirty?` returns the
**deref'd** boolean. The `-atom` suffix is the disambiguator.
`editor-text` follows the same pattern as `editor-history-atom` — it
returns the atom, not `@the-atom`. Callers deref when they want a value
or `add-watch` when they want a change stream.

### Canonical text atom

`(.getText st)` is authoritative, but reading it requires the SWT UI
thread. Consumers that live on other threads (file watchers, background
indexers, future chat panels, test harnesses) either have to hop onto
the UI thread via `sync-exec!` (serialises and pays a round-trip per
read) or race the UI thread (unsafe).

The widget publishes its current text via an atom, stored at
`.setData "md/text"` and exposed by `(editor-text st)`. The atom is:

- **Initialised** to `:content` when the widget is constructed. This
  eager init matters because `add-watch` does NOT fire on registration
  — it only fires on subsequent `reset!`/`swap!`. A consumer that
  registers a watch AFTER the initial content-apply would otherwise
  see nothing until the user edits. Consumers should seed their
  projected state via `@(editor-text st)` at registration time AND
  register the watch for subsequent changes.
- **`reset!`-ed** inside the modify listener on every content change —
  user edits, undo/redo restores, external-file refreshes via
  `editor-set-text!`, and the widget's own initial content-apply. The
  `reset!` happens synchronously on the UI thread inside the listener,
  so any `add-watch` registered callback fires on the UI thread too.
- **Canonical with `(.getText st)`** at atom-fire-time — the listener
  reads `(.getText st)` once and stores that string. There is no window
  where the atom lags the widget.

Consumers that want to track the widget's canonical state should
`add-watch` the atom (plus seed from `@(editor-text st)` once) instead
of polling `(.getText st)` from another thread. Reading
`@(editor-text st)` is safe from any thread; reading `(.getText st)`
is not.

`on-change` and the atom co-exist and fire from the same listener on
every content change. Single-shot callback consumers (tab-title
updates, save notifications) stay on `:on-change`. Long-lived projected
state consumers (background indexers, across-widget mirrors) should use
`add-watch` so their subscription outlives the widget constructor
frame.

## Synthetic / no-abs-path usage

When `:abs-path` is nil (chat prompts, work-item buffers, scratch text):

- `install-link-interaction!` and `install-content-assist-triggers!` are
  skipped — they require `abs-path` to resolve relative `.md` link
  destinations and to anchor wiki-link creation.
- `install-link-preview!` is **not** gated on `abs-path` — it only needs
  `rel-path` + `root-uri` to look up link targets. Synthetic callers that
  supply those (e.g. work-item tabs anchored to a file's directory) get
  hover previews for free. Gate: `(when (and rel-path root-uri) ...)`.
- `schedule-save!` never fires — there is no file to write to.
- `editor-flush!` is a no-op (guards on `(.getData st "md/abs-path")`).
- `editor-set-text!` still works — the dirty-check, `*suppressing-modify?*`
  binding, theme reapply, and `:last-snap` re-seed all run off widget
  data, not `abs-path`.
- Spellcheck, pair-wrap, modify-listener theming, and undo/redo all work
  normally.

Synthetic callers get a themed, spellchecked, pair-wrapping, undo-capable
StyledText with their `:on-change` callback firing on every edit and a
`(editor-text st)` atom they can `add-watch` for projected state. That's
the minimum useful surface area for non-file editor use cases.

## Disposal semantics

The widget installs no `on e/widget-disposed` listener. The stale fn at
[markdown_editor.clj:968-969](../../winze-server/src/llm_memory/ui/markdown_editor.clj#L968-L969)
had one that called `flush-save!`; the current inline `toggle-mode!` code
has none (it flushes manually before `.dispose`). The refactored widget
matches the inline pattern: **callers are responsible for `editor-flush!`
before disposing**. Step 5 of the plan does this in `toggle-mode!`'s
edit→view branch.

There are **two** disposal paths and they behave differently:

1. **`toggle-mode!` edit→view (explicit flush)**. Step 5 calls
   `editor-flush!` before `.dispose`, so the file is written
   synchronously before the widget is torn down.
2. **Tab-close while in edit mode (implicit via debounce)**. The
   CTabItem's `widget-disposed` handler
   ([main_window.clj:255-259](../../winze-server/src/llm_memory/ui/main_window.clj#L255-L259))
   does NOT call `editor-flush!` — neither today nor after this
   refactor. The user's most recent keystroke has a `ScheduledFuture`
   in flight that captured the text; the executor completes it after
   the widget disposes. This matches current behaviour; the refactor
   preserves it intentionally (adding a flush to the tab-close path is
   out of scope).

Pending saves during tab-close (wrapper disposed while a `ScheduledFuture`
is still in flight) complete via the executor:

- The task already captured `text` and `abs-path` — no disposed-widget access.
- Its `on-saved` callback only touches a Clojure atom (`#(reset! dirty?
  false)`) — safe after disposal.
- Every keystroke in the modify listener calls `schedule-save!`, which
  cancels any prior future and schedules a new one. The latest edit is
  therefore always represented by an in-flight `ScheduledFuture`, so a
  tab closed immediately after a keystroke still has its text written
  to disk.
- `on-change` only fires from the modify listener during active editing
  (or via `editor-set-text!` on external-file refresh), never from the
  scheduled save. Tab-title updates cannot race with wrapper disposal.

## State ownership

Storage goes on the `StyledText` via `.setData`. Keys are strings
prefixed `"md/"`:

| Data key | Value | Lifetime | Owner |
|---|---|---|---|
| `"scope"` | `:editor` (kw) — used by keybinding dispatch | widget | this widget |
| `"md/dirty?"` | atom of boolean | widget | this widget |
| `"md/history"` | atom of `{:undo [] :redo [] :last-snap ...}` | widget | this widget |
| `"md/save-future"` | atom holding the pending ScheduledFuture | widget | this widget |
| `"md/text"` | atom of string (canonical text mirror) | widget | this widget |
| `"md/abs-path"` | string (when set) | widget | this widget |
| `"spellcheck/miss"` | misspelling span list — written by spellcheck pass, read by `apply-theme!` | widget | `install-spellcheck!` ([markdown_editor.clj:510](../../winze-server/src/llm_memory/ui/markdown_editor.clj#L510)) |

The `spellcheck/miss` key is already in the codebase; it's listed here so the
widget's data-key inventory is complete and future refactors don't collide.

When the widget is disposed, all data dies with it — Clojure atoms are GC'd,
SWT listeners auto-removed. **No manual teardown required** (SWT-UI-GUIDE §11).

### Accepted regression

Currently, `history` lives in the `open-files` entry and survives
view→edit→view toggles, preserving undo history across mode switches. With
state on the widget, that history is discarded when edit mode exits. This
regression is accepted for this task. If it matters later, add
`:history-atom` as an opt-in passthrough so callers can inject a long-lived
atom.

### Dynamic var suppression

The modify listener has to distinguish **three** kinds of content change,
each of which wants different side-effects:

| Change kind           | Trigger             | `md/text` reset | `apply-theme!` | `record-edit!`                                      | `dirty?` / `schedule-save!`       | `on-change`                                                      |
|-----------------------|---------------------|-----------------|----------------|-----------------------------------------------------|-----------------------------------|------------------------------------------------------------------|
| User edit             | keystroke           | fire            | fire           | push undo                                           | fire                              | fire                                                             |
| Undo/redo restore     | `restore-snapshot!` | fire            | fire           | skip — `undo!`/`redo!` already advanced the history | fire                              | fire (editor now differs from disk)                              |
| External-file refresh | `editor-set-text!`  | fire            | fire           | skip                                                | **skip** — content matches disk   | fire — `update-tab-title!` and `md/text` watchers see new content |

**Key policy**: the `md/text` atom reset, `apply-theme!`, and
`on-change` fire on **every** content change, regardless of trigger.
Consumers like `update-tab-title!` and any `add-watch` on `md/text`
must see external-file refreshes, or titles / projected state drift
stale when files are edited outside the widget. `record-edit!` and
`dirty?`/`schedule-save!` are the only side-effects the
external-refresh branch suppresses.

Two UI-thread-scoped dynamic vars gate the branches:

- **`*restoring-snapshot?*`** (exists) — bound true around
  `restore-snapshot!`. Modify listener skips `record-edit!` only.
- **`*suppressing-modify?*`** (**new this task**) — bound true around
  `editor-set-text!`'s `.setText`, AND around the widget's own initial
  `.setText` on construction. Modify listener skips `record-edit!` and
  the dirty/save block. `apply-theme!` and `on-change` still fire.

Both are safe to `binding`-scope because SWT ModifyListeners fire
synchronously on the UI thread during `.setText`. The dynamic var is
thread-local and stacks naturally under re-entrant `binding`, so nested
calls (e.g. a watcher of `md/text` that itself calls `editor-set-text!`)
do not leak the outer binding's state.

The modify listener body:

```clojure
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
```

The `reset!` of `text-atom` is the first side-effect so `add-watch`
subscribers see the new text before `apply-theme!` mutates styling.
`reset!` fires watchers **synchronously** on the UI thread — a
misbehaving watcher that mutates `st` during its callback re-enters
this listener. Watchers must not call `.replaceTextRange` /
`.setText` / `editor-set-text!` from their callback; defer any widget
mutation via `async-exec!`.

`on-change` is wrapped in a try/catch so a throwing callback (from a
future chat panel, work-item tab, or test harness) cannot break the
modify listener for subsequent keystrokes. `update-tab-title!` today
is safe, but the widget is advertised as a reusable surface for
third-party `on-change` consumers.

Without `*suppressing-modify?*`, an external-file refresh (via
`refresh-open-tabs!` calling `.setText`) would push a spurious undo
frame, flip dirty→true, and schedule a self-save of the content just
read from disk. The current inline code in `toggle-mode!` has this bug
silently; this refactor fixes it.

**Spellcheck caveat.** `install-spellcheck!` attaches its own
ModifyListener ([markdown_editor.clj:533-535](../../winze-server/src/llm_memory/ui/markdown_editor.clj#L533-L535))
that does NOT read `*suppressing-modify?*`. It reschedules a 400ms
spellcheck pass on every `.setText` — including the suppressed initial
content-apply and external refreshes via `editor-set-text!`. This is the
intended behaviour (new content gets spellchecked); no change needed.

**`:last-snap` re-seed is separate from the listener.** The listener
skips `record-edit!`, which is the only writer of `:last-snap`. If the
refresh path leaves `:last-snap` stale, the next real user edit pushes
the pre-refresh snapshot onto undo — Cmd+Z then reverts the external
change. `editor-set-text!` must re-seed `:last-snap` to the post-setText
snapshot itself (see **Accessor helpers** / PLAN Step 3).

## Critical SWT-UI-GUIDE rules for this work

Read the full guide, but these are directly invoked:

- **§4 CDT idioms not Java interop.** The current `toggle-mode!` code does
  exactly the anti-pattern the rule names (`new StyledText` + bulk `.setX`).
  The new widget must use CDT keyword properties and `apply styled-text ...`.
- **§5 Every widget is an init function.** Signature is `(fn [props parent])`.
- **§7 Custom control factory pattern.** `apply styled-text STYLE-BITS :kw
  value ... extra-inits` — verbatim the `custom-browser` shape.
- **§25 Widget constructor line hygiene.** Only one of {style bits, `id!`,
  bare string} on the constructor line.
- **§26/§27/§28.** Bare strings over `:text`; skip defaults; use `|` not
  `bit-or`.
- **§22 Targeted redefinition.** When iterating, `(in-ns
  'llm-memory.ui.markdown-editor) (defn markdown-editor ...)` — avoid full
  namespace reload to preserve UI state.
- **§15 Screenshot-verify.** Every visual change: screenshot before and after,
  compare. Use `llm-memory.ui.util/screenshot-widget!` fully-qualified.
- **§11 Dispose what you create.** The widget itself creates no new SWT
  resources — fonts/colors come from the registry. The `install-*` helpers
  it calls DO: `install-link-interaction!` allocates a `Cursor`
  ([markdown_editor.clj:408](../../winze-server/src/llm_memory/ui/markdown_editor.clj#L408)),
  and `install-spellcheck!` registers a refresh-token
  ([markdown_editor.clj:543-547](../../winze-server/src/llm_memory/ui/markdown_editor.clj#L543-L547)).
  Each one already owns its disposal via its own `DisposeListener`. Nothing
  to add here, but don't regress — do not remove those internal dispose hooks.

## Migration target

After this task, the view→edit branch of `toggle-mode!` should look like:

```clojure
;; Still in toggle-mode!, not in the widget
((with-property :layout (FillLayout.)
   :margin-width  12
   :margin-height 12)
 app-props wrapper)
(.setBackground wrapper @resources/color-mine-shaft)

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
```

`with-property` (SWT-UI-GUIDE §33) is a CDT init that expands to
`(fn [props parent] ...)` — we invoke it imperatively by passing
`app-props` and `wrapper` directly. It sets the new `FillLayout` with
its margin properties and calls `.setLayout` in one form.
`.setBackground` stays separate because the background colour is not a
sub-object with its own properties.

Caret restoration moves **into** the widget via `:initial-caret` — it
needs to run after the widget's sync `scroll-to-line!` so that
`line-visible?` reads the restored viewport, and after the widget has
been laid out (the `line-visible?` guard uses `getClientArea`). The
widget handles both constraints inside its post-construct `fn` and its
caret-restore `async-exec!`. Callers pass `nil` for `:initial-caret`
when there is no saved caret (first-time view→edit).

No `editor-id` and no `(id! ...)` for the StyledText — the widget is
retrieved via `(wrapper-child wrapper)`, the same pattern used
everywhere else in `main_window.clj` ([main_window.clj:77-83](../../winze-server/src/llm_memory/ui/main_window.clj#L77-L83)).
Adding an `id!` would leak an app-props entry per view↔edit toggle; the
current inline code doesn't have that leak and the refactor preserves
the invariant.

Everything else in the view→edit branch — the inline ModifyListener,
`install-*` calls, `.setText` / `setBackground` on the StyledText,
synchronous scroll + caret restore — goes. The padding `FillLayout`
with margin-width/height 12 stays on the **wrapper** Composite.

## Test matrix

All must pass after the refactor. Rows marked **[V]** are visual —
screenshot before and after. Rows marked **[R]** are REPL/data-state
assertions — no screenshot needed, capture the form+result.

### Core editor behaviour

| Behaviour | Kind | How to test |
|---|---|---|
| Syntax highlighting on open | [V] | Cmd+E; visually confirm Markdown theme |
| Incremental highlight on type | [V] | Type `**bold**`; bold styling appears |
| Spellcheck wavy underlines | [V] | Type `teh`; wavy underline appears |
| Pair-wrap | [V] | Select word, type `[`; becomes `[word]` |
| Content-assist trigger | [V] | Type `[[`; palette opens |
| Wiki-link Cmd+click | [V] | Cmd+click a `wiki:` link; opens target |
| Wiki-link hover preview | [V] | Hover over link with Cmd; preview popup |
| Cmd+E back to view | [V] | Edit mode, Cmd+E; view mode shows latest content |
| Auto-save (debounced) | [R] | Edit, wait ~1.5s, file on disk changes |
| Dirty-tracking | [R] | Edit; tab-title callback fires; after save, dirty clears |
| Undo/redo (Cmd+Z / Cmd+Shift+Z) | [R] | Edit, Cmd+Z reverts; Cmd+Shift+Z redoes |

### Scroll / caret / viewport

| Behaviour | Kind | How to test |
|---|---|---|
| Initial scroll from view | [V] | From view mode mid-scroll, Cmd+E; edit shows same region |
| **Initial caret restore** | [V] | From view mode mid-scroll (caret mid-file), Cmd+E; editor shows the same region **and** caret lands on the restored offset, not at start-of-line |
| **First-undo viewport** | [R] | From view mode mid-scroll, Cmd+E, type one character, Cmd+Z — viewport returns to the restored scroll position, **not** line 0 / caret 0. `scroll-to-line!` runs synchronously before `:last-snap` is seeded, so the seed captures the restored viewport deterministically (no async-exec race) |

**Inherited caret-race, not fixed by this refactor**: if the user starts
typing between widget construction and the caret-restore `async-exec!`
firing, `.setCaretOffset` may still move the caret away from the typing
position. The current inline code has the identical race
([main_window.clj:570-578](../../winze-server/src/llm_memory/ui/main_window.clj#L570-L578));
this task preserves it, it does not fix it. Making scroll synchronous
(above) improves `:last-snap` determinism, but the caret portion remains
deferred because `line-visible?` needs layout. If the race matters later,
add a "caret unchanged since construction" guard before the
`.setCaretOffset` call, or drop the caret restore entirely in favour of
scroll-only.

### External-file refresh

| Behaviour | Kind | How to test |
|---|---|---|
| External file change (view) | [R] | External edit; view-mode tab refreshes |
| External file change (edit, clean) | [R] | External edit while editor clean; refreshes |
| External file change (edit, dirty) | [R] | External edit while editor dirty; no refresh |
| Tab title updates on edit | [V] | Edit first line; tab title updates |
| **Tab title updates on external refresh** | [V] | Editor clean; externally change the H1 (or first line); editor refreshes; tab title updates to match new content WITHOUT typing in the editor. Confirms `on-change` fires outside the `*suppressing-modify?*` guard |
| **No spurious undo on external refresh (single edit)** | [R] | Editor clean; externally edit file; editor refreshes; type one character; Cmd+Z reverts only the typed character, NOT the external edit. Confirms `editor-set-text!` re-seeded `:last-snap` after the suppressed `.setText` |
| **No spurious undo on external refresh (multi edit)** | [R] | Editor clean; externally edit file; editor refreshes; type three characters A, B, C; Cmd+Z three times — reverts C, then B, then A, landing on the externally-refreshed content. A fourth Cmd+Z may traverse prior history (**not** protected — see "Out of scope") |
| **No self-save on external refresh** | [R] | Editor clean; externally edit file; editor refreshes; file `mtime` doesn't change from a redundant self-save |

### Canonical text atom

| Behaviour | Kind | How to test |
|---|---|---|
| **Initial atom value** | [R] | Open a file; `@(md-editor/editor-text st)` equals the file content after the initial `.setText` fires the listener |
| **Atom tracks user edits** | [R] | Type "X"; `@(md-editor/editor-text st)` equals `(.getText st)` — tested both from the UI thread and via a background future that sleeps 50ms then reads the atom |
| **Atom tracks external refresh** | [R] | `(let [a (md-editor/editor-text st) seen (atom [])] (add-watch a :t (fn [_ _ _ n] (swap! seen conj n))) ...)` — externally edit the file; the watch fires with the new content |
| **Atom tracks undo/redo** | [R] | Type, Cmd+Z; atom reflects the restored text |

### Widget state / data

| Behaviour | Kind | How to test |
|---|---|---|
| **No spurious dirty/undo on initial open** | [R] | Open a file; `@(.getData st "md/dirty?")` is `false`; `(:undo @(.getData st "md/history"))` is `[]`. Confirms the initial content-apply ran under `*suppressing-modify?*` |
| **Toggle stability** | [R] | Cmd+E 5× on the same tab; `(count (.getChildren wrapper))` stays at 1. `@app-props` grows by one entry per edit→view transition (fresh `brow-id`); those entries are reclaimed by `cleanup-tab-id!` on tab close. Assert monotonic growth bounded by `(* 2 (num-toggles))` |
| **Keybinding scope wiring** | [R] | In edit mode, `(.getData st "scope")` returns `:editor` and `(keybindings/active-scope)` returns `:editor` while the StyledText has focus |
| **Tab-close with pending save** | [R] | Edit, close tab within the 1.5 s debounce window; file is still saved (executor completes in background after wrapper dispose) |
| **macOS focus after Cmd+E** | [R] | After Cmd+E into edit mode, second Cmd+E fires — focus lands on StyledText, not ToolBar (Cocoa does not swallow the key) |
| **Accessors tolerate non-editor widgets** | [R] | `(editor-dirty? st)` on a plain StyledText (no `md/dirty?` data) returns `false` (not NPE); `(editor-history-atom st)` returns `nil`; `(editor-text st)` returns `nil`; `(editor-flush! st)` is a no-op. Confirms defensive guards on the accessors |
| **Rapid Cmd+E toggle safety** | [R] | Cmd+E × 5 in quick succession; no `Widget is disposed` SWTException in the log. Confirms the `.isDisposed` guard on the caret-restore `async-exec!` |

### Synthetic (no-abs-path) callers

| Behaviour | Kind | How to test |
|---|---|---|
| **Bare synthetic editor** | [R] | `(show (shell ... (markdown-editor {:content "# Hi"})))` — spellcheck, pair-wrap, modify-listener theming, undo/redo, `on-change`, and the `md/text` atom all work. No file writes. `(editor-flush! st)` is a no-op. `install-content-assist-triggers!` and `install-link-interaction!` are skipped (they need `abs-path`) |
| **Synthetic editor with rel-path + root-uri** | [V] | `(markdown-editor {:content "..." :rel-path r :root-uri u})` — hover previews install and resolve wiki-link targets. Still no file writes |

### External consumers of the widget's internals

`find_replace.clj` reaches into the editor through `requiring-resolve`
([find_replace.clj:98](../../winze-server/src/llm_memory/ui/find_replace.clj#L98))
to re-theme after a replace. `link_preview.clj` similarly calls
`link-at-offset` via `requiring-resolve`
([link_preview.clj:353](../../winze-server/src/llm_memory/ui/link_preview.clj#L353)).
Both must keep working — they depend on `apply-theme!` and
`link-at-offset` remaining public defns in `markdown_editor.clj`.

| Behaviour | Kind | How to test |
|---|---|---|
| **Find & Replace re-themes after replace** | [V] | Open a file, Cmd+E, Cmd+F (or Cmd+Opt+F). Enter a search + replacement, confirm replace; editor re-themes the replaced region. Confirms `find_replace.clj:98`'s `requiring-resolve` of `apply-theme!` still works |
| **Hover preview resolves link span** | [V] | In edit mode, hover over a wiki link; preview popup appears. Confirms `link_preview.clj:353`'s `requiring-resolve` of `link-at-offset` still works |

### Live-search home → edit path

The second `open-files` entry-creation site
([main_window.clj:794-804](../../winze-server/src/llm_memory/ui/main_window.clj#L794-L804))
is used when the live-search home shows a file (not a synthetic search
page). Step 8 cleans up this site's `:dirty?`/`:history`/`:save-future`
fields the same way as the primary path — it needs its own test row so
the cleanup doesn't break the live-search edit flow.

| Behaviour | Kind | How to test |
|---|---|---|
| **Live-search home Cmd+E round-trip** | [V] | Navigate the live-search tab to a file entry (live-search-state mode transitions to `:file`). Cmd+E — editor opens on the correct abs-path, auto-saves, dirty-tracks, undo/redo work. Cmd+E back — view mode restores. Confirms the widget works when invoked from the `main_window.clj:794-804` entry site, not just the primary `open-tab!` path |

## REPL workflow

Per [winze/CLAUDE.md](../../CLAUDE.md) and global CLAUDE.md:

- **Never** use `make repl` (interactive). Use the `start-nrepl` skill
  from `winze-server/` with the `:dev` alias.
- `clj-nrepl-eval --discover-ports`, then `-p <port>` for evals.
- **Never** `:reload-all`. Use `load-file` or `(in-ns ...) (defn ...)`
  targeted redefinition (§13, §22).
- Don't spawn concurrent REPL subagents that eval into the same server
  (SWT UI deadlock).
- Graceful shutdown only: `(llm-memory.ui.main-window/quit!)` — never
  `pkill`/`kill`, corrupts Datalevin.
- Deploy: `make install` from `winze-server/`, then quit the running
  server via nREPL; MCP proxy auto-restarts.

## Definition of done

1. `ui.markdown-editor/markdown-editor` is an init function matching the
   API above; the stale version is replaced.
2. Accessor helpers `editor-dirty?`, `editor-history-atom`,
   `editor-text`, `editor-flush!`, `editor-set-text!` exported from
   `ui.markdown-editor`. `editor-set-text!` re-seeds `:last-snap` after
   the suppressed `.setText` so a single Cmd+Z after an external refresh
   does not revert the external change. `editor-text` returns the
   canonical-text atom, populated synchronously by the modify listener.
3. `toggle-mode!` edit-mode branch is ≤25 lines, uses
   `markdown-editor`, and applies wrapper padding via `with-property`
   rather than raw `doto` + Java field mutation.
4. `refresh-open-tabs!` uses `editor-set-text!` instead of reaching into
   the StyledText directly.
5. `open-files` entry no longer initialises `:dirty?`, `:history`,
   `:save-future` for file tabs (they live on the widget). Other sites that
   previously read these fields migrate to widget-data accessors.
6. All rows in the test matrix pass. **[V]** rows have before/after
   screenshots; **[R]** rows have captured REPL forms + results.
7. New pure-logic RCF tests cover the accessor defensive-guard branches
   (see Plan Step 3). Existing RCF tests still pass.
8. `make install` + restart; confirm the live server behaves identically.

## Out of scope — do not do in this task

- **Multi-Cmd+Z past an external refresh.** The immediate Cmd+Z after
  one post-refresh keystroke is guaranteed not to undo the refresh (via
  the `:last-snap` re-seed). Cmd+Z'ing **through** a subsequent user edit
  and past the refreshed state reaches pre-refresh history. That is
  inherited behaviour — the widget does not install an undo-checkpoint
  mechanism on refresh. If needed later, add a `:clear-undo-on-refresh?`
  option or insert a sentinel into `:undo` at refresh time.
- Preserving undo history across view↔edit toggles (history atom dies
  with the widget — see **Accepted regression**).
- Namespace split of `main_window.clj`.
- Work-item tab / AI-chat panel consumers of the widget.
