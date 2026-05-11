---
group: remove-wiki-uuid
doc_type: context
status: complete
---
# Remove Wiki UUID Permalinks — Context

## Decision

Remove the UUID-based `wiki:` heading permalink system entirely. The
file-ID-based `wiki:` format (`wiki:root::path/to/file.md`) remains and will
be extended to support optional heading anchors (`wiki:root::path.md#slug`).

Accept the trade-off: heading links become fragile across renames (same as
standard markdown `#fragment` links). This eliminates a large, poorly-working
subsystem in exchange for a simple, maintainable one.

---

## Two Forms of `wiki:` Links

Currently two formats co-exist:

| Format | Example | Resolution |
|--------|---------|------------|
| **File-ID** (keep) | `wiki:_finance::guides/ARGOCD-GUIDE.md` | `:file/id` Datalevin lookup |
| **UUID** (remove) | `wiki:d3dad24e-b2ec-348f-af5c-77cd335ca2a9` | `:wiki/id` Datalevin lookup |

The UUID form was designed for rename-stable heading anchors, using
`UUID/nameUUIDFromBytes(file-id + "#" + slug)`. In practice it is broken and
has never reliably worked in production:

- **Regressions A/B/C** (documented in `WIKI-LINK-REGRESSIONS-CONTEXT.md`)
  destroyed both the Datalevin `:wiki/*` entities and the `wiki-registry.edn`
  backup for the `_finance` root. The two UUID links in `Plans/home.md` are
  unresolvable.
- The viewer (`main_window.clj:custom-browser`) silently ignores broken
  `wiki:` links — no navigation, no warning.
- The `_WIKI-INDEX-GAP` fix (reconcile backfill for missing wiki entities) was
  planned but never implemented.

The file-ID form works partially — in the editor, `navigate-link!` calls
`resolve-wiki-uuid`, which first tries `:wiki/id` (fails for `root::path`
strings), then falls through to `:file/id` (succeeds). In the viewer, the
same code path is present but the fallback is not exercised due to the `when-let`
structure — so the viewer silently drops all `wiki:` navigation.

---

## What Is Being Removed

### `clj-llm-memory/src/llm_memory/store/datalevin.clj`

Six `:wiki/*` schema attributes (lines ~55–61):
`:wiki/id`, `:wiki/slug`, `:wiki/text`, `:wiki/file`, `:wiki/line`, `:wiki/level`

### `clj-llm-memory/src/llm_memory/index.clj`

| Function | Lines (approx) | Purpose |
|----------|----------------|---------|
| `wiki-uuid` | 240–254 | Deterministic UUID from `file-id#slug` |
| `retract-wiki-entities!` | 261–272 | Remove old `:wiki/*` txns |
| `build-wiki-entities` | 274–286 | Build `:wiki/*` maps from headings |
| `snapshot-wiki-state` | 292–322 | Capture old wiki+chunk vecs for rename detection |
| `match-heading-renames` | 324–356 | Cosine similarity rename matching |
| `build-wiki-entities-tracked` | 358–374 | Build entities preserving renamed UUIDs |
| `wiki-registry-path` | 427–430 | Path to `wiki-registry.edn` |
| `load-central-registry` | 432–439 | Load `wiki-registry.edn` |
| `query-all-wikis-for-root` | 441–459 | Query all `:wiki/*` for a root |
| `save-wiki-registry!` | 461–473 | Write root's UUID map to sidecar EDN |
| `load-wiki-registry` | 475–480 | Load UUID→info for one root |
| `resolve-wiki-uuid` | 482–515 | UUID/file-id resolution (REPLACED) |

Also: all call sites of the above in `index-file!` (~lines 547–640).

### `winze-server/src/llm_memory/server/main.clj`

- `migrate-per-root-registries!` (~lines 253–275) — one-time migration for
  consolidation work; now moot.
- Its call site in server startup.

### `winze-server/src/llm_memory/ui/content_assist.clj`

- `wiki-search` function (~lines 334–372): enriches search results with
  `:wiki/id` UUID by querying `:wiki/*` entities from Datalevin.
- `title-search` (~lines 374–422): enriches with UUID from the file's first
  wiki entity.
- `on-select` callback (~lines 499–506): returns `{:uuid id ...}`.

### `winze-server/src/llm_memory/ui/markdown_editor.clj`

- `navigate-link!` UUID branch (~lines 264–281): calls `resolve-wiki-uuid`
  for all `wiki:` prefixed links.
- `handle-wiki-draft-trigger!` (~line 555): inserts `(wiki:uuid)` link.
- `handle-paren-trigger!` (~lines 577–581): inserts `wiki:uuid)` after `(`.
- `handle-insert-link!` (~lines 597–610): inserts `[text](wiki:uuid)` link.

### `winze-server/src/llm_memory/ui/link_preview.clj`

- `resolve-wiki-preview` (~lines 120–168): resolves `wiki:uuid` to a file
  path via `resolve-wiki-uuid` for preview display.

### `winze-server/src/llm_memory/ui/main_window.clj`

- Viewer `wiki:` handler (~lines 159–168): calls `resolve-wiki-uuid` for all
  `wiki:` links. Currently silently drops navigation (the `when-let` short-
  circuits when the UUID is absent).

### Data / Plans Files

- `~/.local/share/winze/wiki-registry.edn` — the sidecar backup file.
- `Plans/todo/WIKI-LINK-REGRESSIONS-CONTEXT.md` + `PLAN.md` — moot once
  the UUID system is gone.
- `_finance/Plans/home.md` lines 30–31 — two UUID test links that are
  unresolvable and should be removed.

---

## What Stays and Changes

### `clj-llm-memory/src/llm_memory/chunk.clj`

`slugify`, `extract-headings`, `page-title` — **no change**. Still used for:
- HTML `id` attributes on headings in `hiccup.clj` (anchor navigation in viewer)
- `:file/title` extraction at index time (used in content assist `title-search`)
- On-demand heading enumeration when content assist needs heading slugs

### `clj-llm-memory/src/llm_memory/index.clj` — `resolve-wiki-uuid` → `resolve-wiki-ref`

`resolve-wiki-uuid` is replaced by `resolve-wiki-ref`, which accepts the
`root::path[#slug]` format and resolves via `:file/id` only:

```
Input:  "_finance::guides/ARGOCD-GUIDE.md#deployment-steps"
Parse:  root-name = "_finance", path = "guides/ARGOCD-GUIDE.md", slug = "deployment-steps"
Query:  [:find ?file-path ?root-uri
         :where [?f :file/id "_finance::guides/ARGOCD-GUIDE.md"]
                [?f :file/path ?file-path]
                [?f :file/root ?r]
                [?r :root/uri ?root-uri]]
Output: {:type :heading, :file-path "guides/ARGOCD-GUIDE.md",
         :root-uri "file:///...", :slug "deployment-steps"}
```

No embedding lookups. No `:wiki/*` entities. No registry.

### Content assist — heading slug derivation

Without `:wiki/*` entities, heading-level links from content assist require
reading the target file and calling `extract-headings`. The `on-select`
callback returns `{:wiki-ref "root::path#slug" :title ...}` instead of
`{:uuid ... :title ...}`.

For MVP: content assist inserts file-level links only (`wiki:root::path`).
Heading anchors can be appended manually or via a follow-on feature. The
`:file/title` attribute (H1 content) remains in the schema and in `title-search`.

### `wiki:` link format on disk

```
wiki:<root-name>::<relative-path>
wiki:<root-name>::<relative-path>#<heading-slug>
```

The heading slug is the `slugify` output of the heading text — same function
used by `hiccup.clj` for `id` attributes. This ensures that a link to
`#design-decisions` navigates to exactly the heading `<h2 id="design-decisions">`.

---

## What Is NOT Changed

- `hiccup.clj` — `render-heading` adds `:id` slugs to headings. No change.
- `hiccup.clj` — `rewrite-local-link` rewrites `.md` links to `winze:open-file?`.
  No change.
- The `[[...]]` trigger in the editor still fires content assist. Only what
  gets inserted changes (from `wiki:uuid` to `wiki:root::path`).
- `winze:` protocol handling in the viewer — no change.
- `:file/title` schema attribute — no change (used by `title-search`).

---

## Plans to Retire on Completion

| File | Reason |
|------|--------|
| `Plans/todo/WIKI-LINK-REGRESSIONS-CONTEXT.md` | Fixes A/B/C/D all moot — UUID system gone |
| `Plans/todo/WIKI-LINK-REGRESSIONS-PLAN.md` | Same |
| `Plans/todo/_WIKI-INDEX-GAP-CONTEXT.md` | If it exists — wiki-gap backfill was for UUID entities |
| `Plans/todo/_WIKI-INDEX-GAP-PLAN.md` | Same |
