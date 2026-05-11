# Hover Preview Fixes — Context

## Goal

Three related defects in the hover-preview feature, documented in
[active-issues.md](../active-issues.md):

1. **Editor**: the preview popup fires only for `wiki:root::...` links. It
   does not fire for ordinary relative-path links (`[text](other.md)`).
2. **Viewer**: the preview popup fires for **no** link type.
3. **Preview content**: when the hovered link targets a whole file (no
   heading anchor), the popup renders only the file header plus a bare
   `# <title>` line. It should render the file's first card — i.e. the
   H1 plus the first section of real content.

## Current Architecture

The popup lives in
[`link_preview.clj`](../../../winze-server/src/llm_memory/ui/link_preview.clj).
A single `Shell` is shared across the application
(`ensure-preview-shell!`), hosting a WebKit `Browser` that renders a
result card via `search/card-html`. Two installers attach the trigger
listeners:

- **Editor**: `install-link-preview!` adds a `MouseMoveListener` and a
  `CaretListener` to a `StyledText`, resolves the link span at the
  cursor via `markdown-editor/link-at-offset`, and schedules
  `show-preview-at!` after `hover-delay-ms` / `caret-delay-ms`.
- **Viewer**: `install-browser-link-preview!` injects `hover-js` on
  every `ProgressAdapter/completed` event. The injected script listens
  for `mouseover`/`mouseout` and calls two `BrowserFunction`
  callbacks (`wpreviewHover`, `wpreviewLeave`).

Both paths funnel through `resolve-wiki-preview`, which calls
`llm-memory.index/resolve-wiki-ref` to obtain `{:type :file-path
:root-uri :slug :title}` and assembles a result map for
`search/card-html`.

## Root Causes

### 1. Editor preview gated on `wiki:` prefix

In
[`link_preview.clj:289-298`](../../../winze-server/src/llm_memory/ui/link_preview.clj#L289-L298)
and [`:250-251`](../../../winze-server/src/llm_memory/ui/link_preview.clj#L250-L251)
both the `mouseMove` dispatcher and `schedule-editor-preview!` gate on
`(str/starts-with? dest "wiki:")`. Plain-relative `.md` destinations
(the `:dest` of an `:inline/link` span from `md-theme`) are ignored.
Nothing else in the pipeline is wiki-specific — it's a single-branch
condition that drops all other link types on the floor.

### 2. Viewer preview never fires

Two compounding issues, and only the first needs to be fixed to
resolve the reported symptom:

**2a — CSS selector mismatch.** `hover-js` uses
`a[href^="wiki:"]`
([`link_preview.clj:342-355`](../../../winze-server/src/llm_memory/ui/link_preview.clj#L342-L355)).
The viewer runs rendered HTML produced by `hiccup/md->hiccup`, whose
`render-link` calls `rewrite-local-link`
([`hiccup.clj:134-147`](../../../winze-server/src/llm_memory/ui/hiccup.clj#L134-L147)).
`local-md-link?`
([`hiccup.clj:121-132`](../../../winze-server/src/llm_memory/ui/hiccup.clj#L121-L132))
matches any dest ending in `.md` that is not already `#`, `winze:`,
`mailto:`, `file:`, or contains `://`. It does **not** exclude
`wiki:`, so destinations like `wiki:root::path.md` are rewritten to
`winze:open-file?root=…&path=…%2Fwiki%3Aroot%3A%3Apath.md`.

Verified in the running REPL (port 62643):

```clojure
(rewrite-local-link {:root-uri "file:///tmp" :file-dir "/tmp/sub"}
                    "wiki:root::foo.md")
;; => "winze:open-file?root=file%3A%2F%2F%2Ftmp&path=%2Ftmp%2Fsub%2Fwiki%3Aroot%3A%3Afoo.md"

(rewrite-local-link {:root-uri "file:///tmp" :file-dir "/tmp/sub"}
                    "bar/foo.md")
;; => "winze:open-file?root=file%3A%2F%2F%2Ftmp&path=%2Ftmp%2Fsub%2Fbar%2Ffoo.md"
```

In both cases the resulting `href` starts with `winze:`, so the
`a[href^="wiki:"]` selector never matches. The hover callbacks never
fire.

**2b — resolver only knows `wiki:` refs.** `resolve-wiki-preview`
([`link_preview.clj:120-168`](../../../winze-server/src/llm_memory/ui/link_preview.clj#L120-L168))
is written against `llm-memory.index/resolve-wiki-ref`, whose input
format is `root::path[#slug]`. It cannot resolve a `winze:open-file?`
URL directly. Once the selector is broadened we need a uniform
resolver that accepts either form.

(The wiki-link wrongly-rewritten-to-`winze:open-file?` observation is
also the root cause of the separate bug recorded in active-issues.md:
clicking a `wiki:` link in the viewer doesn't navigate. That is out of
scope here but worth capturing for the follow-up.)

### 3. File-level preview degenerates to an H1

In `resolve-wiki-preview`, `chunk-text` is queried only when
`(= type :heading)`
([`link_preview.clj:131-144`](../../../winze-server/src/llm_memory/ui/link_preview.clj#L131-L144)).
When the link targets a file (no slug) the fallback is
`(str "# " (or title file-path))`
([`link_preview.clj:164`](../../../winze-server/src/llm_memory/ui/link_preview.clj#L164)),
i.e. a bare H1. `search/result-card` then renders the file-header
+ H1 body, which matches the user's complaint exactly ("only the
relative path name of the file itself and the file's usual header
line").

The fix lives in the existing chunk schema. Chunks are stored with
`:chunk/section` (long; index into the sequence produced by
`split-sections`). Section 0's `:chunk/text` already contains the
file's H1 prelude **plus** the first `## H2` section's content —
which is exactly what the user wants. Verified against the live store:
section 0 of `complete/file-refresh-regression/CONTEXT.md` is
`"# File Refresh Watcher Regression — Context\n\n## Symptom\n\n…"`.

## Key Data Points

- **Link span shapes** (from `md_theme/theme` via
  `markdown_editor/update-link-spans!`):
  `{:start N :length N :type :inline/link|:inline/wiki-draft :dest "…"}`.
  `:dest` is the raw destination text as typed by the user — not
  rewritten.
- **View-mode href shapes** (after `rewrite-local-link`):
  `wiki:` (if the destination is anything other than a local `.md`
  path — e.g. `wiki:root::foo.md#slug` which gets mangled, see 2a
  above), `winze:open-file?…`, `http(s)://…`, `mailto:…`, `#anchor`.
- **Chunk schema fields** (from
  [`store/datalevin.clj:48-53`](../../../clj-llm-memory/src/llm_memory/store/datalevin.clj#L48-L53)):
  `:chunk/id`, `:chunk/file`, `:chunk/text`, `:chunk/vec`,
  `:chunk/slug`, `:chunk/section`. No chunk-level line/offset field.
- **Viewer wiring**:
  [`main_window.clj:202-204`](../../../winze-server/src/llm_memory/ui/main_window.clj#L202-L204)
  calls `install-browser-link-preview!` on every `Browser` built by
  `custom-browser`. This includes both file-view tabs and the live
  search results pane.
- **Editor wiring**:
  [`main_window.clj:544`](../../../winze-server/src/llm_memory/ui/main_window.clj#L544)
  calls `install-link-preview!` when switching a tab into edit mode.
  Each `StyledText` also has `install-link-interaction!` invoked with
  `abs-path` for MOD1-click navigation — the same `abs-path` needs to
  be available to the preview installer so relative-path links can be
  resolved against the editing file's parent directory.

## Related Work

- [editor-cleanup](../../complete/editor-cleanup/) — introduced the
  view-mode preview pipeline and documents why JS→Java callback
  (`BrowserFunction`) replaced `StatusTextListener`.
- [content-assist-sizing-v2](../../complete/content-assist-sizing-v2/) —
  the +4 fudge in `show-preview-at!` for sub-pixel `scrollHeight`
  under-reporting. Relevant because any new resolver that produces
  larger preview text must still obey `preview-max-height`.
- [home-wiki-links](../../complete/home-wiki-links/) — wiki: link
  resolution in the viewer, including `resolve-wiki-ref` contract.
- [remove-wiki-uuid](../../complete/remove-wiki-uuid/) — established
  the current `wiki:root::path[#slug]` format; the resolver accepts
  this exact string shape.

## Constraints / Gotchas

- **SWT threading**: both installers must run on the UI thread. All
  listener mutations go through `async-exec!`. See
  [`winze/Plans/SWT-UI-GUIDE.md`](../../SWT-UI-GUIDE.md).
- **Single shared preview shell**: `ensure-preview-shell!` is
  idempotent and parented to the main window. Don't create per-editor
  or per-browser shells — resource/lifecycle management assumes one
  shell globally.
- **Browser CSS selector**: test with `.closest()` because `e.target`
  can be a child element (e.g. the `<code>` inside an `<a>`).
- **BrowserFunction lifecycle**: registered per Browser; must be
  disposed on Browser disposal (already wired in
  `install-browser-link-preview!`). Don't re-register on every page
  load — the `ProgressAdapter/completed` callback should only
  `.execute` the JS.
- **Avoid cascading rewrites**: the fix for 2a is to broaden the
  viewer selector, not to narrow `rewrite-local-link`. Excluding
  `wiki:` from `local-md-link?` would change click-navigation
  behavior (a separate bug in active-issues.md); we handle that as a
  follow-up so this plan stays focused on preview.
- **Relative-path resolution in the viewer**: the view-mode DOM has
  already had its `href` rewritten to `winze:open-file?root=…&path=…`,
  so no additional `abs-path`/`root-uri` plumbing is needed for the
  viewer. The edit-mode installer, in contrast, sees the raw `:dest`
  and must resolve it against the editing file's directory.
