# Hover Preview & Content-Assist Card Regression — Context

## Symptom

Two related bugs, different code paths, same visual result:

1. **Hover preview popup** (editor and viewer) renders only the file
   header + H1 line — no first-section body — for any link to
   another `.md` file. The slug case (heading-anchor links) has the
   same symptom because both branches silently fall through to the
   same `(str "# " file-path)` fallback.

2. **Content-assist popup** — certain typed queries (e.g.
   "Winze Wishlist") render only the file header + H1 in each card,
   while other queries (e.g. "Actionability report") render full
   body content. The split is based on whether the query matches a
   `:file/title` substring: title-matches bypass semantic search and
   go through `title-search`, which deliberately stubbed
   `:chunk/text` to `(str "# " title)` regardless of what the file
   actually contains.

The two bugs share a visual signature but have different root
causes — documented separately below.

## Related prior work

[`complete/hover-preview-fixes/`](../hover-preview-fixes/) — earlier
work that fixed the same *user-visible* symptom by changing
`resolve-file-preview` to query the lowest-numbered chunk (section 0
holds H1 + first `## H2` body). That fix is still in place; the
regression is **not** that the intent was reverted but that the
datalog query it relies on has stopped returning rows on the current
Datalevin store.

If you are debugging the hover popup again, read the prior
CONTEXT/PLAN pair first — all of the architecture (shared Shell,
BrowserFunction wiring, caret/mouse trigger split, `parse-link-dest`
normalization) is still accurate.

## Root cause — bug 1 (hover preview)

`resolve-file-preview` (both the slug and no-slug branches) ran a
single multi-clause datalog query whose shape was:

```clojure
[:find ?section ?text
 :in $ ?path ?ruri
 :where
 [?r :root/uri ?ruri]
 [?f :file/root ?r]
 [?f :file/path ?path]
 [?c :chunk/file ?f]
 [?c :chunk/section ?section]
 [?c :chunk/text ?text]]          ;; ← always returns nothing
```

On the current Datalevin store this returns an empty set. Isolated in
the live REPL:

- `[:find ?c ?section :in $ ?f :where [?c :chunk/file ?f] [?c :chunk/section ?section]]` → 6 rows ✓
- Add `[?c :chunk/text ?text]` to the same query → **0 rows** ✗
- The same `[?c :chunk/text ?t]` clause **does** return a row when
  `?c` is bound via `:in` rather than produced from `:where`.

`:chunk/text` in
[`store/datalevin.clj:50`](../../../clj-llm-memory/src/llm_memory/store/datalevin.clj#L50)
is declared `:db.type/string` with no index. The observed behaviour
is consistent with Datalevin's query planner silently giving up on a
join when an output variable is bound to a long, unindexed string
column whose subject isn't pre-constrained. Whatever the precise
internal reason, the pattern "bind `:chunk/text` as a `:find` var in
a multi-join" is unreliable on this store.

The same shape is used in both branches, so heading-anchor hovers
(`slug` present) also silently returned nil and hit the
`(str "# " file-path)` fallback — visually indistinguishable from the
file-level case, which is why the bug looked file-level only.

## Root cause — bug 2 (content-assist title-search)

`title-search` in
[`content_assist.clj`](../../../winze-server/src/llm_memory/ui/content_assist.clj)
is the first-pass search for the content-assist popup; it
substring-matches `:file/title` and only falls through to
`wiki-search` (semantic) when it returns no hits. For each title
match it constructed a card map with a stubbed body:

```clojure
{:file/id    fid
 :file/path  path
 :file/title title
 :root/uri   root-uri
 :chunk/text (str "# " title)        ;; ← always just the H1
 :wiki/id    fid}
```

That shape came from the feature's original implementation and was
never updated to fetch real chunk content. Semantic hits (via
`llm-memory.core/search`) return a full chunk map, so those cards
rendered correctly — which is why only title-matching queries showed
the degenerate card. Users noticing the hover bug almost certainly
also saw this one but reported them together as "the same bug".

## Pre-existing workaround in the codebase

[`llm-memory.core/first-chunk-text`](../../../clj-llm-memory/src/llm_memory/core.clj#L339-L349)
already avoids the bad pattern by:

1. Querying chunk eids only (no `:chunk/text` binding):
   `[:find [?c ...] :in $ ?f :where [?c :chunk/file ?f]]`.
2. Calling `store/pull-entity` on each eid.
3. Picking the winner with `apply min-key :chunk/section` on the
   pulled entity maps.

This is the idiom `resolve-file-preview` and the content-assist
`title-search` now both use — one chunk-eids query plus a pull per
eid, with branching only in the "pick" step.

## Scope

- **Files changed**:
  - [`winze-server/src/llm_memory/ui/link_preview.clj`](../../../winze-server/src/llm_memory/ui/link_preview.clj)
    — `resolve-file-preview` only. Roughly 40 lines of query-shaped
    code replaced by ~25 lines of pull-based code.
  - [`winze-server/src/llm_memory/ui/content_assist.clj`](../../../winze-server/src/llm_memory/ui/content_assist.clj)
    — added a private `first-section-text` helper and rewrote
    `title-search` to populate `:chunk/text` from it (plus merged
    `:file/status`/`:file/type`/`:file/group` from the pulled file
    entity, which the prior code omitted — an empty-string group
    would have rendered an empty `#` pill).
- No schema change, no reindex, no data migration.
- Editor and viewer share `resolve-file-preview`, so one fix covers
  both. Content-assist is independent.

## Gotchas for future work

- **Do not bind `:chunk/text` as a datalog `:find` variable** in a
  query whose subject `?c` is produced from `:where` clauses. Pull
  the entity instead. Same caution applies to any future
  long-string, unindexed attributes.
- **Cards need a real body**. Any new code path that constructs a
  result map for `search/card-html` must populate `:chunk/text`
  with real chunk content. The fallback
  `(str "# " title)` is a degenerate render and should only appear
  when a file has no chunks indexed. The `first-section-text`
  helpers exist for this purpose.
- **Test both branches** when touching `resolve-file-preview`. The
  slug and file-level branches render the same fallback on failure;
  a slug-only bug will look like a file-level bug and vice versa.
- **Screenshot-verify** per
  [`winze/Plans/SWT-UI-GUIDE.md`](../../SWT-UI-GUIDE.md). The
  popup's content is opaque from the outside — only a screenshot
  distinguishes "shows the first section" from "shows only the H1".
  The content-assist popup is harder to trigger programmatically;
  render a single card via `link-preview/show-preview-at!` against
  the card map produced by `title-search` to verify the shape.
