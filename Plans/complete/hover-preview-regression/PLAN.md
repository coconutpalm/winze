# Hover Preview & Content-Assist Card Regression — Plan

Retrospective record of the steps taken to diagnose and fix the two
bugs described in [CONTEXT.md](CONTEXT.md). Steps 1–6 cover the
hover-preview bug; Steps 7–9 cover the content-assist bug.

## Step 1 — Locate prior work

`search_plans "hover popup displays only file header not first content result" all_roots=true`
surfaced [`complete/hover-preview-fixes/`](../hover-preview-fixes/).
Its Issue 3 matches the current symptom exactly ("popup renders only
the file header plus a bare `# <title>` line"). Reading its
CONTEXT/PLAN established that:

- The popup renders via `search/card-html` in
  [`link_preview.clj`](../../../winze-server/src/llm_memory/ui/link_preview.clj).
- `resolve-file-preview` is the single funnel for both the editor
  (caret/mouse listeners on `StyledText`) and the viewer (JS hover +
  `BrowserFunction`).
- The prior fix added a min-section datalog query so the no-slug
  branch would return H1 + first `## H2` body rather than a bare H1.

## Step 2 — Confirm the live symptom

Read the current
[`link_preview.clj`](../../../winze-server/src/llm_memory/ui/link_preview.clj).
The prior fix's query is still present. So the *intent* is correct —
the regression is in query *behaviour*.

Discovered the live Winze nREPL port via
`clj-nrepl-eval --discover-ports` (and `~/.local/share/winze/.nrepl-port`).
Ran the query from `resolve-file-preview` against the live store with
a known-good file (`complete/hover-preview-fixes/CONTEXT.md`):

```clojure
(p/query s '[:find ?section ?text
             :in $ ?path ?ruri
             :where
             [?r :root/uri ?ruri]
             [?f :file/root ?r]
             [?f :file/path ?path]
             [?c :chunk/file ?f]
             [?c :chunk/section ?section]
             [?c :chunk/text ?text]]
         {...})
;; → #{}  (empty!)
```

But the file has six chunks and non-empty `:chunk/text` values —
verified with `[?c ?a ?v]`-style enumeration and `pull-entity`.

## Step 3 — Isolate the failing clause

Bisected the where-clauses in the REPL:

| Query shape | Rows |
|---|---|
| `[?c :chunk/file ?f]` | 6 |
| `[?c :chunk/file ?f] [?c :chunk/section ?section]` | 6 |
| `[?c :chunk/file ?f] [?c :chunk/text ?text]` | **0** |
| `[?c :chunk/text ?t]` with `?c` bound via `:in` | 1 |

Conclusion: binding `:chunk/text` as a `:find` output variable fails
when `?c` is itself produced from `:where` clauses — even when the
subject set is tiny and the values are plain strings. A `pull-entity`
call on the same `?c` returns the text normally.

Also re-ran the slug branch's query (same multi-join shape, adds
`[?c :chunk/slug ?slug]`) — also returned an empty set. So both
branches were broken; the user only reported the file-level symptom
because both failures produce the same `(str "# " file-path)`
fallback render.

## Step 4 — Apply the fix

Noticed that [`core.clj:339-349`](../../../clj-llm-memory/src/llm_memory/core.clj#L339-L349)
already solves this by: query chunk eids only, `pull-entity` each,
pick with `apply min-key :chunk/section`.

Refactored `resolve-file-preview` to the same idiom — one eids
query + a pull per eid, branching only at the pick step:

```clojure
(let [chunks (seq (map #(pull-fn s %) chunk-eids))
      picked (when chunks
               (if (seq slug)
                 (first (filter #(= slug (:chunk/slug %)) chunks))
                 (apply min-key #(or (:chunk/section %) 0) chunks)))])
```

File metadata (status/type/group) comes from a single `pull-entity`
on the file eid; drops the separate `:find ?status ?type ?group`
query.

## Step 5 — Hot-patch + verify

1. `(load-file ".../link_preview.clj")` on the live server (port per
   `.nrepl-port`). Not `:reload-all` — see
   [`winze/Plans/SWT-UI-GUIDE.md`](../../SWT-UI-GUIDE.md).
2. Ran `resolve-file-preview` directly against both a file-level ref
   and a slug-level ref (`complete/hover-preview-fixes/CONTEXT.md`,
   slug `"root-causes"`). Both returned the correct H1 + section
   body.
3. Triggered the popup programmatically via
   `async-exec!` → `show-preview-at!`, captured the shell with
   `llm-memory.ui.util/screenshot-widget!`. Screenshot shows the
   H1 ("Hover Preview Fixes — Context") followed by the **Goal**
   section body — exactly what the prior fix intended.

## Step 6 — Persist

Hot patch holds until restart. Persist with `make install` from
[`winze-server/`](../../../winze-server/) followed by a graceful
`(llm-memory.ui.main-window/quit!)` — never `pkill` (see CLAUDE.md
"Graceful Shutdown").

## Step 7 — Locate the content-assist bug

User reported that content-assist cards for some queries
("Winze Wishlist") render only the file header + H1 while others
("Actionability report") render full body content.

`grep -n ':chunk/text|card-html' content_assist.clj` pointed to two
sites:

- Line 233 — rendering a result via `search/card-html` (fine, uses
  whatever the result map contains).
- Line 365 — `:chunk/text (str "# " title)` inside `title-search`.

That's the stub. `title-search` runs *before* the semantic
`wiki-search` (only falling through if it returns zero hits), so any
query whose text is a substring of a file title produces the
degenerate card.

Confirmed on the live store:

- Title "Winze Wishlist" exists (`todo/wishlist.md`) → title-search
  matches → bare H1 card.
- No title contains "Actionability report" → title-search returns
  empty → semantic `wiki-search` runs → full card.

## Step 8 — Apply the fix

Added a private `first-section-text` helper in `content_assist.clj`
using the same pull-based idiom as `resolve-file-preview`. Rewrote
`title-search` to:

1. Also return the file eid (`?feid`) alongside the existing
   columns via `[(identity ?f) ?feid]`.
2. `pull-entity` the file to get `:file/status`, `:file/type`,
   `:file/group` (merged in only when present, to avoid rendering
   empty pills).
3. Call `first-section-text` for the body.

Kept the `(str "# " title)` fallback for files with no indexed
chunks.

## Step 9 — Verify

1. `(load-file ".../content_assist.clj")` on the live server.
2. Called `title-search` directly with "Winze Wishlist" — returned a
   card map whose `:chunk/text` starts with `"# Winze Wishlist\n\n
   ## Additional desired features or changes\n..."`. ✓
3. Rendered the card HTML via `search/card-html`; confirmed it
   contains "Additional desired features" (body) and the file
   status. ✓
4. Programmatically showed the card in the preview shell via
   `show-preview-at!` and screenshot-verified. First render showed
   an empty `#` pill because `:file/group ""` was being explicitly
   set; tightened the merge to only pull present keys and
   re-screenshot confirmed a clean card.

## Verification Checkpoints

| Step | Verification |
|------|--------------|
| 1 | Prior fix's CONTEXT/PLAN read; current symptom matches Issue 3 |
| 2 | Live query reproduces the empty result on `complete/hover-preview-fixes/CONTEXT.md` |
| 3 | REPL bisection pinpoints `[?c :chunk/text ?text]` clause as the one that empties the result |
| 4 | Refactored `resolve-file-preview` loads cleanly; private helpers unchanged |
| 5 | Direct call returns H1 + first-section text for both file-level and slug refs; popup screenshot shows correct content |
| 6 | `make install` + restart pick up the fix (deferred to user) |
| 7 | Line 365 stub in `title-search` identified as content-assist root cause; title-match hypothesis confirmed on the live store |
| 8 | Refactored `title-search` loads cleanly; `first-section-text` helper added |
| 9 | Direct call returns full first-section body; rendered card HTML contains body text and status; screenshot confirms clean card without empty pills |

## Out of Scope

- No changes to `parse-link-dest`, `install-link-preview!`,
  `install-browser-link-preview!`, `hover-js`, or
  `show-preview-at!`. Those remained correct.
- No changes to `wiki-search`, `detect-mode`, the content-assist
  popup Shell wiring, row images, or the search-store/search-chunks
  plumbing in `llm-memory.core`. The only content-assist change is
  in `title-search` and a new private helper.
- No investigation of *why* Datalevin's query planner misbehaves on
  the multi-join `:chunk/text` pattern. The pull-based workaround
  is the same one `llm-memory.core/first-chunk-text` already uses,
  so the new code is consistent with existing practice rather than
  a one-off.
