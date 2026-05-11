---
created: 2026-04-17
group: swt-audit-fixes
doc_type: context
status: complete
tags: [swt, ui, audit, compliance, cleanup]
related: [SWT-UI-GUIDE.md, SWT-REFERENCE.md]
---
# SWT UI Compliance Audit — Context

## Origin

A rule-by-rule audit of `winze-server/src/llm_memory/ui/` against
[`Plans/SWT-UI-GUIDE.md`](../SWT-UI-GUIDE.md) (15 files, ~7,200 lines).  The
high-risk rules — threading, `application` discipline, `Browser.evaluate`
return, `LocationAdapter` for Browser `changed`, `setAppName` ordering,
`mw/element` vs. init-function access — are all satisfied.  Violations are
concentrated in surface-style rules plus one material resource-lifecycle gap.

## Findings

### 1. Resource disposal gap (§11 / §29 / §30) — material

`resources.clj` holds a lazy registry of native SWT resources:

- 12 `defonce` `Color` delays at lines 228–238
- 10 `defonce` `Font` delays at lines 205–222
- Icon / image `defonce`s elsewhere in the same file

The shell-closed handler at `main_window.clj:1099-1106` only calls
`(.dispose @display)` — **the registry is never iterated and its entries are
never disposed**.  Guide §11/§29/§30 require explicit disposal before display
disposal.

Practical impact is small on normal JVM exit (Display teardown cleans up at
the OS level), but the rule exists for two reasons: (a) it fails if the UI is
ever restarted in-process, and (b) it catches missing disposals via SWT's
`Device` tracker in debug builds.

Already-audited `new Image/GC/Color/Font` sites are safe:

| Site | Disposal |
|------|----------|
| `about_dialog.clj:14` | finally block at ~65 |
| `util.clj:24,38` | try/finally in screenshot helpers |
| `resources.clj:91-94,159-160` | transient `Color`s disposed before return |
| `content_assist.clj:172-177` | scaled-screenshot `Image` + `GC` disposed |
| `markdown_editor.clj:145` | transient `GC` disposed in same form |

### 2. Surface-style violations — mechanical

#### §28 — `bit-or` should be `|`

| File | Line | Snippet |
|------|------|---------|
| `command_palette.clj` | 137 | `(Shell. ... (bit-or SWT/TOOL SWT/ON_TOP SWT/NO_TRIM))` |
| `command_palette.clj` | 162 | `(Table. sh (bit-or SWT/SINGLE SWT/FULL_SELECTION))` |
| `link_preview.clj`    | 73  | `(Shell. ... (bit-or SWT/TOOL SWT/ON_TOP SWT/NO_TRIM))` |
| `main_window.clj`     | 512 | `(bit-or SWT/MULTI SWT/V_SCROLL SWT/WRAP)` |
| `main_window.clj`     | 876 | `(bit-or SWT/ICON_WARNING SWT/OK)` (MessageBox) |
| `resources.clj`       | 209 | `(bit-or SWT/BOLD SWT/ITALIC)` — body-bold-italic font |
| `resources.clj`       | 216 | `(bit-or SWT/BOLD SWT/ITALIC)` — h5 font |

`content_assist.clj:661` — `(bit-not (bit-or SWT/FOREGROUND SWT/BACKGROUND))`
is **not** a violation: it's numeric masking of event-detail bits, not SWT
style composition for a constructor.

#### §27 — Don't pass `SWT/NONE`

Rule applies **only** when calling CDT sugar.  Raw Java interop
constructors (`TableItem.`, `Composite.`, `Table.`, …) require an `int`
style arg — `SWT/NONE` is the correct literal there.

Actual violation:

| File | Line | Snippet |
|------|------|---------|
| `content_assist.clj` | 622 | `(table-column SWT/NONE …)` — CDT sugar; omit the arg |

Not violations (raw Java interop — style arg is required):

| File | Line | Snippet |
|------|------|---------|
| `command_palette.clj` | 73  | `(TableItem. table SWT/NONE)` |
| `content_assist.clj`  | 114 | `(Composite. sh SWT/NONE)` |
| `content_assist.clj`  | 237 | `(TableItem. table SWT/NONE)` |

These sit inside popup/offscreen shell escape hatches (guide §6) that are
already out of scope per this plan.  Leave them.

#### §26 — Bare strings, not `:text`

| File | Line | Snippet |
|------|------|---------|
| `about_dialog.clj` | 35 | `:text "About Winze"` (shell title) |
| `about_dialog.clj` | 45 | `:text "Winze"` (label) |
| `about_dialog.clj` | 48 | `:text "Knowledgebase search server\n…"` (label) |

#### §4 — Raw Java interop that needs a CDT-sugar pass

Guide §4 / §6 are categorical: **use CDT sugar; drop to interop only when
CDT genuinely doesn't express what's needed.**  "Popup shell," "offscreen
shell," or "imperative creation at runtime" are not exemptions — CDT ships
`shell`, `composite`, `table`, `table-item`, `table-column`, `on`, etc. and
provides helpers for applying init functions outside the main
`application` startup path (see `llm-memory.ui.util/show`).

Raw interop sites in the audited files that need CDT-sugar evaluation:

| File | Line | Snippet | Likely CDT sugar |
|------|------|---------|------------------|
| `command_palette.clj` | 137 | `(Shell. parent-sh (bit-or SWT/TOOL SWT/ON_TOP SWT/NO_TRIM))` | `shell` |
| `command_palette.clj` | 162 | `(Table. sh (bit-or SWT/SINGLE SWT/FULL_SELECTION))` | `table` |
| `command_palette.clj` | 73  | `(TableItem. table SWT/NONE)` | `table-item` (dynamic population may need `child-of`) |
| `command_palette.clj` | 222 | `(.addListener tbl SWT/Selection …)` | `(on e/widget-selected …)` |
| `link_preview.clj`    | 73  | `(Shell. parent (bit-or SWT/TOOL SWT/ON_TOP SWT/NO_TRIM))` | `shell` |
| `content_assist.clj`  | 110 | `(Shell. (Display/getDefault) SWT/NO_TRIM)` | `shell` |
| `content_assist.clj`  | 114 | `(Composite. sh SWT/NONE)` | `composite` |
| `content_assist.clj`  | 237 | `(TableItem. table SWT/NONE)` | `table-item` via `child-of` |

Each site needs the same investigation pass:

1. Does `swtdoc` list a matching CDT init function?  (Almost certainly
   yes for `shell`/`composite`/`table`/`table-item`.)
2. Can the site be expressed declaratively via a CDT init tree, applied
   imperatively with `show` or `child-of`?
3. If CDT really cannot express it (platform-specific API, undetected
   widget, event-loop-level control), retain the raw call **with a comment
   stating the exact reason** — not a generic "escape hatch" wave.

The `MeasureItem` / `EraseItem` / `PaintItem` listeners at
`content_assist.clj:642-685` are a legitimate retention: those are untyped
SWT event codes with no listener interface (documented by the comment at
line 640).  They stay raw.

### 3. Unnecessary `declare`s — style, low severity

`main_window.clj:65-71` declares seven functions:

```clojure
(declare open-tab!)
(declare update-edit-button!)
(declare set-live-search-content!)
(declare navigate-back!)
(declare wrapper-child)
(declare navigate-forward!)
(declare update-nav-buttons!)
```

Project CLAUDE.md: *"Define before use: Prefer function ordering over
`declare`."*  Only declarations that participate in genuine mutual recursion
(see SWT-UI-GUIDE §14) should remain; simple forward references should be
removed by reordering function definitions.

Classification needed per `declare`:

| Declared var | Genuine mutual recursion? |
|---|---|
| `open-tab!` | **Yes** — `custom-browser`'s `LocationListener` calls `open-tab!`, which in turn calls `custom-browser` (SWT-UI-GUIDE §14 worked example). Keep. |
| `update-edit-button!` | Needs verification — if only called from later code, reorder. |
| `set-live-search-content!` | Needs verification. |
| `navigate-back!` | Needs verification. |
| `wrapper-child` | Needs verification. |
| `navigate-forward!` | Needs verification. |
| `update-nav-buttons!` | Needs verification. |

## Scope

**In scope:**
- Registry disposal hook on shell close (rule §11).
- `bit-or` → `|`, `:text` → bare string (mechanical, §28 / §26).
- CDT-sugar conversion pass over the eight raw-interop sites above.  For
  any site that genuinely cannot be expressed with CDT sugar, retain the
  raw call with a specific rationale comment — not a generic "escape
  hatch" wave.
- `SWT/NONE` removal at every CDT-sugar call site exposed by the
  conversion pass (including `content_assist.clj:622` and any new ones
  introduced by §4 fixes).
- Prune unnecessary `declare`s in `main_window.clj` by reordering.

**Out of scope:**
- Any behavioural change.  This is cleanup against rule violations; the UI
  must look and act identically afterwards.
- `Display/setAppName`, `application`, `LocationAdapter`, `Browser.evaluate`
  patterns — all verified clean, no action needed.
- The `MeasureItem`/`EraseItem`/`PaintItem` listeners at
  `content_assist.clj:642-685` — no SWT listener interface exists for
  these untyped event codes, so `.addListener` is genuinely required.

## Verified Clean (do not re-audit)

| Rule | Verified | Notes |
|------|----------|-------|
| §2 threading | ✓ | All public mutations in `async-exec!`/`(ui …)` or event handlers. |
| §3 `application` | ✓ | Only called from the startup entry point `main_window.clj:1077`. |
| §8 multi-method listeners | ✓ | No `CTabFolder2Listener` usage. |
| §10 `defchildren` | ✓ | Used correctly at `main_window.clj:220,242`, `find_replace.clj:427`. |
| §16 `setAppName` | ✓ | `server/main.clj:508`, before any `Display/getDefault`. |
| §18 `mw/element` | ✓ | No stray init-fn calls outside startup. |
| §20 `Browser.evaluate` returns | ✓ | All 6 sites (`main_window.clj:262,453`, `find_replace.clj:226`, `link_preview.clj:187`, `content_assist.clj:154,224`) have explicit `return`. |
| §37 Browser `LocationAdapter` | ✓ | `main_window.clj:158` uses explicit `proxy`. |

## Affected files

| File | Rule(s) | Change type |
|------|---------|-------------|
| `resources.clj`       | §11/§29/§30 | Add `dispose-registry!` + shell-closed hook |
| `main_window.clj`     | §11, §28, §14 | Wire disposal hook; `bit-or` → `|`; prune `declare`s |
| `command_palette.clj` | §4, §28, §27 | `Shell.`/`Table.`/`TableItem.` → CDT sugar; `bit-or` → `|`; `.addListener` → `on`; drop `SWT/NONE` where sugar is adopted |
| `content_assist.clj`  | §4, §27 | `Shell.`/`Composite.`/`TableItem.` → CDT sugar; drop `SWT/NONE` at `table-column` call + at any sugar sites introduced |
| `link_preview.clj`    | §4, §28 | `Shell.` → `shell`; `bit-or` → `|` |
| `about_dialog.clj`    | §26 | `:text` → bare string (3 sites) |

## Risk

Medium.  Two sources of behavioural risk:

1. **Registry disposal** — runs only on the `:closing = true` shutdown
   path, but a bug here surfaces only at the end of a session.  Mitigated
   by the `realized?` + `not isDisposed` guards.
2. **CDT-sugar conversion of popup / offscreen shells** — subtly changes
   widget construction timing (init-function tree instead of imperative
   constructor + setter calls).  Popup sizing, focus, show/hide
   behaviour must be re-verified manually.  Command palette, content
   assist, and link preview all need dedicated smoke tests.

The remaining work (`bit-or` → `|`, `:text` → bare string, `declare`
pruning) is a syntactic rewrite with no runtime effect.
