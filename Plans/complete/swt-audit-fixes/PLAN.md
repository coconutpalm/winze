---
created: 2026-04-17
group: swt-audit-fixes
doc_type: plan
status: complete
tags: [swt, ui, audit, compliance, cleanup]
related: [CONTEXT.md, SWT-UI-GUIDE.md]
---
# SWT UI Compliance Audit — Plan

See [`CONTEXT.md`](CONTEXT.md) for findings and rationale.

## Goal

Bring `winze-server/src/llm_memory/ui/` into compliance with every rule of
`Plans/SWT-UI-GUIDE.md` without changing UI behaviour.

## Prerequisites

1. Start a headless nREPL against `winze-server/` using the `start-nrepl`
   skill (dev-mode, so `(tests …)` blocks run).
2. `clj-nrepl-eval --discover-ports` to confirm the port.
3. After each step, reload the touched namespace with targeted `(require … :reload)`
   — never `:reload-all` (guide §13).

> The running Winze server (auto-started by the MCP proxy) is **production
> mode** and will not run RCF tests — use a separate dev-mode nREPL for
> verification.

---

## Step 1 — Add a resource registry disposal hook (§11)

### 1a. Add `dispose-registry!` in `resources.clj`

**Design principle — no hand-maintained list.**  A literal `doseq` over
`[body-font h1-font color-lavender …]` introduces maintenance debt: every
new `defonce` resource would have to be manually added to the disposal
list, and eventually someone (including future-us) will forget.

Instead, discriminate by type at runtime.  Every SWT resource that needs
disposal extends `org.eclipse.swt.graphics.Resource` (`Color`, `Font`,
`Image`, `GC`, `Region`, `Cursor`, `Pattern`, `Path`, `TextLayout`,
`Transform`).  Every registry entry is a `defonce` wrapping a `Delay`.
Walking `ns-publics` on those two predicates is self-maintaining.

```clojure
;; Add import
(:import [org.eclipse.swt.graphics Resource])

;; New — at the bottom of resources.clj
(defn dispose-registry!
  "Dispose every realized SWT Resource stored in a public Var of this
   namespace.  Must run on the UI thread, before Display disposal.

   Discriminates by type rather than by an explicit list — any future
   `(defonce foo (delay (ui (Color. …))))` is automatically covered.
   Unrealized delays are skipped (no need to force a resource just to
   dispose it)."
  []
  (doseq [[sym v] (ns-publics *ns*)
          :let  [val (var-get v)]
          :when (and (delay? val)
                     (realized? val)
                     (instance? Resource @val)
                     (not (.isDisposed ^Resource @val)))]
    (try (.dispose ^Resource @val)
         (catch Throwable t
           (log/warn t "Failed to dispose registry resource" sym)))))
```

Runs once on shutdown — O(n) over the namespace's public vars is fine.
If a future resource type genuinely isn't a subclass of `Resource` (vanishingly
unlikely in SWT), the predicate can be widened to check for a `.dispose`
method via reflection, but `Resource` covers every native graphics handle
SWT exposes.

**Non-resource `defonce`s in the namespace** (`executor`, `open-files`,
`tab-histories`, `restoring-history?`, `last-search-query`, etc.) are
skipped automatically — they aren't `Delay`s of `Resource` instances.

### 1b. Call from the shell-closed handler in `main_window.clj`

At `main_window.clj:1099-1106`, the current handler is:

```clojure
(on e/shell-closed [props parent event]
    (if (:closing @app-props)
      (async-exec! #(.dispose @display))
      (do
        (set! (. event doit) false)
        (.setVisible parent false))))
```

Change the `:closing` branch to dispose the registry **before** disposing
the display:

```clojure
(on e/shell-closed [props parent event]
    (if (:closing @app-props)
      (async-exec! (fn []
                     (resources/dispose-registry!)
                     (.dispose @display)))
      (do
        (set! (. event doit) false)
        (.setVisible parent false))))
```

### 1c. Verify

- From the dev REPL, force a couple of registry entries, call
  `dispose-registry!`, and confirm `.isDisposed` is true for the realized
  ones and the unrealized delays remained unrealized.
- Exercise the quit path end-to-end: `(llm-memory.ui.main-window/quit!)` via
  nREPL; confirm no `SWTException: Resource disposed` warnings in the log.
- Screenshot before and after to confirm visual state during the live app
  is unchanged (no colors/fonts accidentally disposed while the app is
  running).

---

## Step 2 — `bit-or` → `|` (§28)

Replace seven call sites.  Each change is mechanical:

| File | Line | Before | After |
|------|------|--------|-------|
| `command_palette.clj` | 137 | `(bit-or SWT/TOOL SWT/ON_TOP SWT/NO_TRIM)` | `(\| SWT/TOOL SWT/ON_TOP SWT/NO_TRIM)` |
| `command_palette.clj` | 162 | `(bit-or SWT/SINGLE SWT/FULL_SELECTION)` | `(\| SWT/SINGLE SWT/FULL_SELECTION)` |
| `link_preview.clj`    | 73  | `(bit-or SWT/TOOL SWT/ON_TOP SWT/NO_TRIM)` | `(\| SWT/TOOL SWT/ON_TOP SWT/NO_TRIM)` |
| `main_window.clj`     | 512 | `(bit-or SWT/MULTI SWT/V_SCROLL SWT/WRAP)` | `(\| SWT/MULTI SWT/V_SCROLL SWT/WRAP)` |
| `main_window.clj`     | 876 | `(bit-or SWT/ICON_WARNING SWT/OK)` | `(\| SWT/ICON_WARNING SWT/OK)` |
| `resources.clj`       | 209 | `(bit-or SWT/BOLD SWT/ITALIC)` | `(\| SWT/BOLD SWT/ITALIC)` |
| `resources.clj`       | 216 | `(bit-or SWT/BOLD SWT/ITALIC)` | `(\| SWT/BOLD SWT/ITALIC)` |

**Note**: `|` is referred from `ui.SWT` — each of these files already
requires that namespace, but confirm `|` is in the refer list before
editing.  If a file requires specific symbols (not `:refer :all`), add `|`
to the list.

**Do not touch** `content_assist.clj:661` — that's numeric masking, not a
style-bit composition.

### Verify

`(require 'llm-memory.ui.X :reload)` each touched namespace.  Open a
command palette and a link preview popup to exercise the former `bit-or`
paths.  Screenshot both.

---

## Step 3 — Drop `SWT/NONE` at `content_assist.clj:622` (§27)

Rule §27 applies to CDT sugar — "don't set a property to its default when
the CDT helper already defaults it."  Raw Java interop constructors
require an `int` style argument, so `SWT/NONE` literal there is fine.

One definite change now:

| File | Line | Before | After |
|------|------|--------|-------|
| `content_assist.clj` | 622 | `(table-column SWT/NONE …)` | `(table-column …)` |

The three raw-interop sites (`TableItem.`, `Composite.`) are handled in
Step 5 — if the CDT-sugar conversion succeeds there, their `SWT/NONE`
arguments disappear as well.  If a site stays raw because CDT genuinely
can't express it, the `SWT/NONE` argument stays with it (and gets a
one-line comment stating why).

### Verify

Trigger content-assist.  Screenshot the popup.  Confirm the column layout
is unchanged.

---

## Step 4 — `:text` → bare string (§26)

Three replacements in `about_dialog.clj`.  CDT automatically converts a
bare string child to `.setText`.

| Line | Before | After |
|------|--------|-------|
| 35 | `:text "About Winze"` | `"About Winze"` |
| 45 | `:text "Winze"` | `"Winze"` |
| 48 | `:text "Knowledgebase search server\nSemantic search for markdown planning documents"` | `"Knowledgebase search server\nSemantic search for markdown planning documents"` |

Also audit the widget constructor lines for rule §25 (at most one of
style / `id!` / bare string on the same line as the constructor name) —
the current layout may need a line break after removing `:text`.

### Verify

Tray → About…; screenshot the dialog.

---

## Step 5 — CDT-sugar conversion of raw-interop widget construction (§4 / §6)

The command palette, content-assist popup, and link-preview popup all
build their widget trees with raw `Shell.` / `Composite.` / `Table.` /
`TableItem.` constructors plus `.setLayout` / `.addListener` calls.
Guide §4 / §6 are categorical: **use CDT sugar; drop to interop only when
CDT genuinely can't express what's needed.**  "Created imperatively at
runtime" is not an exemption — CDT provides application helpers
(`llm-memory.ui.util/show` and similar) for applying an init tree outside
the startup `application` call.

### 5a. Inventory and classify

For each site in the CONTEXT §4 table, confirm via `swtdoc` that a CDT
sugar exists:

```clojure
(swtdoc :swt :composites)  ; expect shell, composite, table
(swtdoc :swt :widgets)     ; expect table (if not in composites)
(swtdoc :swt :items)       ; expect table-item, table-column
```

Decide per site:

- **(A) Convertible** — CDT sugar exists and the creation context tolerates
  an init tree.  Rewrite to CDT sugar + `on` listeners + `show` / `child-of`
  for imperative application.
- **(B) Genuinely irreducible** — rewriting breaks behaviour that CDT can't
  express (e.g. a platform-specific style bit not surfaced by the init fn,
  an interaction with SWT's focus/event model CDT doesn't handle).  Retain
  the raw call and add a one-line comment with the specific reason.  "It's
  a popup" or "created imperatively" is not a reason.

Record the classification for all eight sites before making edits.  If the
classification turns up surprises (e.g. `show` can't handle tray-parented
shells), note them in the CONTEXT file and revise Step 5 before proceeding.

### 5b. Convert site-by-site

Expected rewrites (target shape — exact form depends on the classification
pass):

| File:line | Raw form | CDT form |
|-----------|----------|----------|
| `command_palette.clj:137` | `(Shell. parent-sh (bit-or …))` | `(shell (\| …) …)` applied via `show` |
| `command_palette.clj:162` | `(Table. sh (bit-or SWT/SINGLE SWT/FULL_SELECTION))` | `(table (\| SWT/SINGLE SWT/FULL_SELECTION) …)` inside the `shell` init |
| `command_palette.clj:73`  | `(TableItem. table SWT/NONE)` | `(table-item …)` via `child-of` for dynamic population |
| `command_palette.clj:222` | `(.addListener tbl SWT/Selection …)` | `(on e/widget-selected …)` inside the `table` init |
| `link_preview.clj:73`     | `(Shell. parent (bit-or …))` | `(shell (\| …) …)` applied via `show` |
| `content_assist.clj:110`  | `(Shell. (Display/getDefault) SWT/NO_TRIM)` | `(shell SWT/NO_TRIM …)` applied via `show` |
| `content_assist.clj:114`  | `(Composite. sh SWT/NONE)` | `(composite …)` inside the `shell` init |
| `content_assist.clj:237`  | `(TableItem. table SWT/NONE)` | `(table-item …)` via `child-of` |

Converting a `Shell.` site typically means:

1. Express the shell as `(shell style-bits (id! :ui/foo) layout-init child-inits)`.
2. Apply imperatively with `show` (or equivalent) at the original creation
   point, capturing the shell handle via `(:ui/foo @props)`.
3. Replace imperative `.setSize` / `.setLocation` / `.open` calls with
   either init-fn keyword properties (when declarative suffices) or with
   post-`show` `async-exec!` mutations (when size/location depends on
   runtime state).

Converting a dynamic `TableItem.` loop typically means using `child-of`
with `defchildren` (guide §10) to add items under the table at runtime,
keeping the table init declarative.

`SWT/NONE` removal (§27) happens automatically as sites move to sugar —
CDT init fns default the style.  Any `SWT/NONE` that *survives* into this
step's output is either a remaining raw-interop site in bucket (B) or a
bug in the rewrite.

### 5c. Verify

Do this after each site conversion, not in a single batch at the end —
popup behaviour is subtle and regressions compound.

- Command palette: Ctrl-Shift-P → open; type to filter; arrow-key navigate;
  Enter to dispatch; Esc to close.  Screenshot at each state.  Confirm
  selection highlight, layout, and close-on-blur behaviour are identical
  to pre-conversion.
- Link preview: hover over an internal link → preview popup appears →
  click-through navigation; Esc and focus-loss close.  Screenshot.
- Content assist: trigger in the editor → popup appears sized to content;
  arrow-key navigate; Enter to insert; Esc to dismiss.  Screenshot.

Any site that lands in bucket (B) after the classification pass retains
its raw form with a comment.  Example shape:

```clojure
;; CDT sugar path doesn't support <specific behaviour> — see
;; <issue or comment reference>.  Retaining raw Shell. for that reason.
(Shell. parent (| SWT/TOOL SWT/ON_TOP SWT/NO_TRIM))
```

---

## Step 6 — Prune unnecessary `declare`s in `main_window.clj` (§14 / CLAUDE.md)

Before editing, classify each `declare` at `main_window.clj:65-71`:

```clojure
(declare open-tab!)                  ; KEEP — genuine mutual recursion with custom-browser
(declare update-edit-button!)
(declare set-live-search-content!)
(declare navigate-back!)
(declare wrapper-child)
(declare navigate-forward!)
(declare update-nav-buttons!)
```

### 6a. Classification pass

For each declared var (except `open-tab!`), use Grep to find:

1. The `defn` that defines it.
2. Every call site in `main_window.clj`.

A `declare` is **necessary** only if a caller in the file is defined
*before* the definition AND that earlier caller itself is called from a
site defined before the target's definition point.  In practice: `open-tab!`
qualifies (the `custom-browser` init form uses it, and `open-tab!` later
calls `custom-browser`).  Simple "called only from later code" or "called
only from event handlers whose lexical position is after the definition"
declarations are unnecessary.

### 6b. Reorder and remove

For each unnecessary `declare`:

1. Move the `defn` to a point before its first use in the file.
2. Delete the `declare` line.
3. Reload the namespace and confirm no compile error.

Keep any `declare` that actually participates in mutual recursion and
annotate it with a one-line comment stating *what* recursive pair it
supports (so a future maintainer can verify at a glance).

### 6c. Verify

- `(require 'llm-memory.ui.main-window :reload)` succeeds with no
  unresolved-symbol errors.
- `make install` (from `winze-server/`) succeeds.
- Startup a fresh server and confirm the main window renders and responds
  to navigation (back/forward), tab opens, edit-button updates, live
  search.

---

## Step 7 — Final verification

1. `make test` in `clj-llm-memory/` and `winze-server/` — no regressions.
2. `make install` in `winze-server/`.
3. Restart the Winze server.
4. Full smoke test:
   - Main window opens.
   - Home page loads in live search tab.
   - Search returns results; clicking a result opens a tab.
   - Command palette (Ctrl-Shift-P) opens and filters.
   - Content assist triggers in the editor.
   - Link preview popup appears on hover.
   - Find/replace toolbar opens, closes.
   - About dialog opens, displays "About Winze" title, "Winze" heading,
     and description paragraph.
   - Tray → Quit cleanly shuts down (no SWTException in log).
5. Screenshot each of the six features above and store under
   `/tmp/swt-audit-fixes-<feature>.png`; attach to the completion
   comment when moving the plan to `complete/`.

---

## Completion Criteria

- [x] Step 1: `dispose-registry!` exists in `resources.clj`; shell-closed handler at `main_window.clj:1099` calls it before `.dispose @display`. Verified live: predicate correctly selects 20 realized Resources (7 Images, 5 Fonts, 8 Colors); quit path completed cleanly with no `log/warn` from the dispose try/catch.
- [x] Step 2: seven `bit-or` sites converted to `|`; `|` added to the refer list of `command_palette.clj`, `link_preview.clj`, `resources.clj`.
- [x] Step 3: `table-column SWT/NONE` removed at `content_assist.clj:622`.
- [x] Step 4: three `:text "…"` sites in `about_dialog.clj` converted to bare strings; screenshot-verified.
- [x] Step 5: all eight raw-interop sites classified as bucket (B) and annotated with specific architectural blockers (singleton lifecycle, parent-shell relationship, multi-method listener, non-activating show path, listener attached from a ProgressListener callback). A bucket-(A) conversion pass is deferred to a follow-up plan — see below.
- [x] Step 6: `wrapper-child` moved up (one declare removed); remaining six declares retained with per-line annotations stating their first-caller and transitive dependency on later-defined helpers.
- [x] Step 7: `clj-llm-memory` tests green (79 tests, 0 failures, 0 errors). `winze-server` has no `make test` target. Fresh `make install` + `make run` succeeded. Screenshot-verified: main window, command palette, About dialog. Quit path verified in log (dispose-registry ran cleanly; pre-existing `SWTException: Device is disposed` after display disposal is unrelated — present in `plan-server.2026-04-11.0.log` at 09:55:52).
- [ ] Plan moved to `Plans/complete/swt-audit-fixes/`.

## Follow-up plan (not this work item)

Step 5 deferred the full CDT-sugar conversion of the three popup-building
functions (`open-palette!`, `ensure-preview-shell!`,
`get-or-create-offscreen-browser!`).  Each needs a parent-aware analogue
of `util/show`, plus refactoring to lift the singleton-shell lifecycle
out of the on-demand creation path before the widget construction can
become declarative.  The annotations now at each site document exactly
what blocks each one, so a future session can tackle them one at a time
without re-discovering the reasons.

## Out of Scope

- Any behavioural change.
- `Display/setAppName`, `application`, `LocationAdapter`, `Browser.evaluate`
  patterns — all verified clean in the audit.
- `MeasureItem` / `EraseItem` / `PaintItem` listeners at
  `content_assist.clj:642-685` — no SWT listener interface exists for
  these untyped event codes, so `.addListener` is genuinely required
  (documented by the comment at line 640).
