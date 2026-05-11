# Clickable Metadata Tag Search — Context

## Goal

Redesign search result card metadata to show useful, clickable cross-references. Replace the current low-signal metadata pills (status, type, jira) with high-value pills (group, related, tags) that function as clickable search filters via a `#tag` query syntax.

## Current State

### Metadata Pills

`search.clj:metadata-pills` renders a row of `<span class="pill">` elements for each result's `:file/status`, `:file/type`, `:file/group`, and `:file/jira`:

```clojure
(defn- metadata-pills [result]
  (let [pills (cond-> []
                (:file/status result) (conj (:file/status result))
                (:file/type result)   (conj (:file/type result))
                (:file/group result)  (conj (:file/group result))
                (:file/jira result)   (conj (:file/jira result)))]
    (when (seq pills)
      [:div.pills
       (for [p pills] [:span.pill p])])))
```

Pills display raw values: `"active"`, `"context"`, `"gpu-report"`, `"AAO-68"`. They are not clickable.

**Problems with current display:**
- `:file/status` is low-signal — obvious from the file path (`dev/` = active, `complete/` = complete)
- `:file/type` is obvious from the filename (CONTEXT.md, PLAN.md, STORY.md)
- `:file/jira` takes up pill space but isn't useful as a visual cross-reference
- `:file/related` and `:file/tags` are stored in Datalevin but **not surfaced** in search results at all — these are the most useful cross-reference metadata

### Metadata Stored in Datalevin but Not in Search Results

- `:file/tags` — comma-separated string (e.g. `"datalevin, vector-search, embedding, architecture"`)
- `:file/related` — comma-separated string (e.g. `"plans-system-improvement, search-improvement"`)
- Both are populated from YAML frontmatter during indexing (`index.clj` maps `:fm/tags` → `:file/tags`, `:fm/related` → `:file/related`)

### Metadata Not Stored at All

- `created` — parsed from YAML frontmatter as `:fm/created` in `frontmatter.clj` but **discarded during indexing** — never written to a `:file/created` attribute. The Datalevin schema has no `:file/created` attribute.

### Search Pipeline

`search.clj:results` passes the raw query string directly to `core/search`:

```clojure
(core/search store q {:top 10 :dedupe true})
```

`core/search` accepts metadata filter opts that are currently unused by the UI:

```clojure
(defn search [store query-text opts]
  ;; opts: :status, :type, :group, :root-uri, :since, :dedupe, :top
  ...)
```

These filters are applied as post-hoc predicates on the enriched result set (not in the Datalog query or vector search). This means:
- `:status "active"` — keeps only results where `(:file/status r)` equals `"active"`
- `:type "context"` — keeps only results where `(:file/type r)` equals `"context"`
- `:group "gpu-report"` — keeps only results where `(:file/group r)` equals `"gpu-report"`

There is no `:jira` filter in `core/search` today.

### What Metadata Values Exist

From the Datalevin schema and metadata inference:
- **status**: `"active"`, `"complete"`, `"deferred"` (inferred from directory)
- **type**: `"context"`, `"plan"`, `"story"`, `"report"`, `"codemap"`, `"results"`, `"info"`, `"jira"`, `"index"`, `"tracker"` (inferred from filename)
- **group**: arbitrary kebab-case names like `"gpu-report"`, `"cache-gap-detect"`, `"rate-limiting"` (inferred from directory)
- **jira**: keys like `"AAO-68"`, `"AAO-30"` (inferred from filename)

## Desired Card Layout

```
☐ dev/WORD-CLOUD-PLAN.md (2026-03-24)                    72%
[#gpu-report] [#plans-system-improvement] [#datalevin] [#vector-search]
```

Line 1:
- **Status indicator**: ☐ (U+2610, unchecked) for active, ☑ (U+2611, checked) for complete. No indicator for deferred or missing status.
- **File path**: Clickable link to open the file (already implemented via file-viewer feature).
- **Created date**: In parentheses after the file path, if `:file/created` exists.
- **Relevance badge**: Right-aligned percentage (unchanged).

Line 2 (pills row):
- **`:file/group`** first (if present) — clickable, triggers `#group` search
- **`:file/related`** items next (split on `","`, trimmed) — clickable
- **`:file/tags`** items last (split on `","`, trimmed) — clickable
- All pills display with a `#` prefix (e.g., `#gpu-report`) as a UI affordance showing users the search syntax they can type themselves.
- All pills are clickable — clicking triggers a search for that term via `winze:search?q=#term` URL

### Required Changes Across Layers

1. **`clj-llm-memory/store/datalevin.clj`** — Add `:file/created {:db/valueType :db.type/string}` to schema
2. **`clj-llm-memory/index.clj`** — Map `:fm/created` → `:file/created` during indexing (same pattern as `:fm/tags` → `:file/tags`)
3. **`clj-llm-memory/core.clj`** — Surface `:file/tags`, `:file/related`, and `:file/created` in search results (add `when` clauses alongside the existing ones); add `:jira` filter; add `metadata-query` for tag-only searches
4. **`winze-server/src/llm_memory/ui/search.clj`** — Redesign `result-card` and `metadata-pills`; add `#tag` query parsing; route through parser
5. **`winze-server/src/llm_memory/ui/main_window.clj`** — Add `winze:search?q=` handler branch in `custom-browser`
6. **Re-index** — After schema change, `index_plans` with `reset: true` to populate `:file/created`

### Metadata–Filter Mapping

Each pill value maps to exactly one filter key in `core/search`:

| Metadata field | Example values | `core/search` opt |
|---------------|---------------|-------------------|
| `:file/status` | `active`, `complete`, `deferred` | `:status` |
| `:file/type` | `context`, `plan`, `story` | `:type` |
| `:file/group` | `gpu-report`, `cache-gap-detect` | `:group` |
| `:file/jira` | `AAO-68`, `AAO-30` | — (not yet supported) |

Status and type values are drawn from fixed sets. Group and jira values are open-ended but follow naming conventions (kebab-case groups, `AAO-\d+` jira keys).

## Design

### Query Syntax

The search text box accepts a mix of free text and `#tag` tokens:

```
deployment strategy #active #context
#gpu-report
kubernetes #complete
```

Parsing rules:
- Tokens starting with `#` are metadata tags
- Everything else is the semantic query string
- Multiple tags combine as AND filters (all must match)
- Tags are case-insensitive

### Tag Resolution

A `#tag` must be mapped to the correct filter key. Since status/type values come from small fixed sets, we can use set membership:

```
#active    → :status "active"
#context   → :type "context"
#gpu-report → :group "gpu-report"
#AAO-68    → :jira "AAO-68"
```

Resolution order:
1. If the tag value is in the status set → `:status`
2. If the tag value is in the type set → `:type`
3. If it matches `AAO-\d+` → `:jira`
4. Otherwise → `:group`

This is unambiguous because the three fixed sets (status, type, jira pattern) don't overlap with each other or with group names.

### Jira Filter

`core/search` doesn't currently support a `:jira` filter. Add it as one more predicate in the existing filter chain — trivial one-line addition.

### Pill Click Interaction

Uses the same `custom-browser` factory's `on e/changing` handler (SWT-UI-GUIDE §4). A pill click navigates to:

```
winze:search?q=%23{tag-value}
```

The handler (a new branch in `custom-browser`'s `on e/changing`):
1. Cancels navigation (`(set! (.-doit event) false)`) — must be synchronous, before handler returns
2. Parses the `q` parameter to get the search text
3. Sets the search text via `async-exec!` + `(.setText (element :search) ...)` — this fires `modify-text`, which triggers `search/results` automatically
4. Switches to the live search tab (index 0) if not already selected

**Important**: Do NOT explicitly call `search/results` after `.setText` — the `modify-text` event handler already does this. Calling it explicitly would cause a double search.

Widget access uses the `(element :key)` accessor pattern (backed by `app-props` atom). The search Text widget is `(element :search)`, the tab folder is `(element :main-folder)`.

### Search Text ↔ Filter Roundtrip

The query string in the search box is the single source of truth:

```
"deployment strategy #active #context"
```

On every search (inside `search.clj:results`):
1. Parse out `#tag` tokens → metadata filters
2. Remaining text → semantic query string
3. Pass both to `core/search`

If the semantic portion is empty (only tags), use a pure Datalog metadata query instead of vector search — `core/search` requires a non-empty query for embedding.

### Pure Metadata Query (Tag-Only Search)

When the search text contains only `#tags` with no free text, skip vector search and use a Datalog query filtered by metadata, sorted by modification date. Returns results in the same shape as `search` so the UI renders them identically.

## Shared `custom-browser` Event Handler

This adds a `winze:search?` URL scheme alongside `winze:open-file?` (file viewer). Both dispatch through the same `on e/changing` handler in the `custom-browser` factory function (SWT-UI-GUIDE §4). Since `custom-browser` is shared across all browser instances (live search + file viewer tabs), the handler fires everywhere — this is correct because pill links only appear in search result HTML.

The existing `when` in `custom-browser` needs to become a `cond` to handle both URL schemes.

## Files to Modify

| File | Change |
|------|--------|
| `winze/clj-llm-memory/src/llm_memory/store/datalevin.clj` | Add `:file/created` to schema |
| `winze/clj-llm-memory/src/llm_memory/index.clj` | Map `:fm/created` → `:file/created` |
| `winze/clj-llm-memory/src/llm_memory/core.clj` | Surface `:file/tags`, `:file/related`, `:file/created` in search results; add `:jira` filter; add `metadata-query` function |
| `winze-server/src/llm_memory/ui/search.clj` | Parse `#tag` tokens from query; redesign `result-card` (status emoji, created date, clickable pills for group/related/tags); pure-metadata query path; CSS for clickable pills and active highlighting |
| `winze-server/src/llm_memory/ui/main_window.clj` | Add `winze:search?q=` branch to `custom-browser`'s `on e/changing` handler (convert `when` → `cond`) |
