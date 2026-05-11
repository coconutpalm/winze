# Wiki Link Graph — Plan

## Phase 0 — Dependency ✓ COMPLETE

### Step 0 — ~~Add `commonmark-java` to `clj-llm-memory/deps.edn`~~ (done)

Already complete. `clj-llm-memory/deps.edn` has
`org.commonmark/commonmark {:mvn/version "0.24.0"}` (plus the GFM extension
deps). `clj-llm-memory/src/llm_memory/chunk.clj` has `text-content`
(with RCF tests). `winze-server/src/llm_memory/ui/hiccup.clj` (which
depends on `clj-llm-memory` via the `:local/root` coordinate in
`winze-server/deps.edn`) already requires `[llm-memory.chunk :as chunk]`
and calls `chunk/text-content`. Do not repeat these steps.

Note the cross-project direction: `link_rewriter.clj` lives in
`clj-llm-memory`, so it shares the parser deps and `text-content` already
exercised by `hiccup.clj`. No duplication.

---

## Phase 1 — Schema and Extraction

### Step 1 — Add `:link/*` to schema

**File**: `clj-llm-memory/src/llm_memory/store/datalevin.clj`

Add to `schema`:

```clojure
;; Outbound wiki-link entity
:link/id    {:db/valueType :db.type/string :db/unique :db.unique/identity}
:link/from  {:db/valueType :db.type/ref}
:link/to-id {:db/valueType :db.type/string}
:link/slug  {:db/valueType :db.type/string}
```

Note: heading-slug tracking uses the existing `:chunk/slug` attribute (one
chunk per H2 section). A separate `:file/heading-slugs` attribute was
considered and rejected — it would duplicate `:chunk/slug` for H2s and only
add H3+ slugs, which the rename-detection algorithm cannot use (no per-heading
vectors for H3+). See CONTEXT.md "Heading Rename Detection — H2-Only Limitation".

No `:file/links-indexed` flag. Backfill for pre-schema files is handled by
running `index-root!` once post-deploy — see Phase 3. Keeping the schema free
of a disposable marker means no follow-up cleanup step and no per-index write
of a semantically-meaningless boolean.

**Verify**: REPL — `(d/schema conn)` confirms new attributes present. Existing
store upgrades in place (Datalevin schema is additive).

### Step 1a — Enforce root-name uniqueness in `register-root!`

**File**: `clj-llm-memory/src/llm_memory/core.clj` (or wherever
`register-root!` lives).

Cross-root propagation (Steps 6 and 9) rewrites files across root
boundaries using the root-name embedded in each file-id. Two roots sharing
a `:root/name` would let propagation write to the wrong root. And
silently renaming an existing root would invalidate every stored
`old-name::*` file-id and the `:link/to-id` strings that reference it.
The file-id scheme has always assumed stable, unique root names; this
work makes the assumption load-bearing, so enforce it at registration
time.

Add two checks inside `register-root!`:

1. Reject if `:root/name` is already bound to a **different** `:root/uri`
   (cross-root name collision).
2. Reject if `:root/uri` is already bound to a **different** `:root/name`
   (root rename — **unsupported**; see CONTEXT.md).

Re-registering the same `(uri, name)` pair remains idempotent (existing
upsert behavior via `:root/uri` identity).

```clojure
(let [name-collisions (->> (store/query store
                                        '[:find ?uri
                                          :in $ ?nm
                                          :where
                                          [?r :root/name ?nm]
                                          [?r :root/uri ?uri]]
                                        {:nm name})
                           (map first)
                           (remove #{uri}))
      uri-rename      (->> (store/query store
                                        '[:find ?nm
                                          :in $ ?u
                                          :where
                                          [?r :root/uri ?u]
                                          [?r :root/name ?nm]]
                                        {:u uri})
                           (map first)
                           (remove #{name})
                           first)]
  (when (seq name-collisions)
    (throw (ex-info (str "Root name already in use by a different URI: " name)
                    {:name name :existing-uri (first name-collisions) :new-uri uri})))
  (when uri-rename
    (throw (ex-info (str "Root rename is not supported — URI " uri
                         " is already registered as '" uri-rename "'."
                         " Remove the root and re-register under the new name.")
                    {:uri uri :existing-name uri-rename :new-name name}))))
```

**Verify**: RCF tests —
1. Register `(uri-A, "foo")` succeeds.
2. Register `(uri-B, "foo")` throws — name collision across URIs.
3. Register `(uri-A, "bar")` throws — root-rename attempt.
4. Register `(uri-A, "foo")` again is idempotent (no throw, no state change).

**Non-atomicity caveat**: the collision checks are query-then-transact, not
a single CAS assertion. Two concurrent `register-root!` calls could both
observe "no collision" and both commit. Single-operator use today (CLI
invocation from a human, one MCP server per workspace) makes this a
non-issue in practice. If `register-root!` is ever called concurrently —
e.g. from parallel startup paths or a future multi-user wrapper — promote
the check to a `:db.fn/cas`-style transactor assertion. Flagged here so a
future reader does not mistake the absence of a lock for an oversight.

### Step 1b — Retract `:link/*` entities in `remove-root!`

**File**: `clj-llm-memory/src/llm_memory/core.clj`

`remove-root!` (lines 109–135) currently retracts chunks → files → root.
Two gaps to close in the same edit:

1. **Link entities**: once `:link/*` entities exist, a root removal that
   skips them leaves orphan links with a dangling `:link/from` ref to a
   retracted file entity — the exact orphan class `retract-file-by-id!`
   (Step 4c) and `index-root!` (Step 4d) are careful to prevent.
2. **HNSW hygiene (pre-existing latent bug)**: the current `remove-root!`
   retracts `:chunk/*` entities via `store/retract!` **without** first
   calling `retract-chunk-vecs!`. `retract-file-by-id!` and `index-root!`
   both call it; `remove-root!` alone skips it, leaving stale HNSW nodes
   whose eventual traversal throws NPE in `vec-neighbors` (same class of
   bug as the HNSW desync regression test in `index.clj`). This fix
   belongs here because we are already touching the cascade — leaving a
   site inconsistent with the other two is the root cause of bugs like
   this.

Symmetric fix: add a `link-eids` query to the same let-binding (joined
through `:file/root`, so it must run while file entities still exist),
then retract in order chunk-vecs → chunks → links → files → root.

```clojure
link-eids (store/query store
                        '[:find [?l ...]
                          :in $ ?uri
                          :where
                          [?r :root/uri ?uri]
                          [?f :file/root ?r]
                          [?l :link/from ?f]]
                        {:uri uri})
```

```clojure
(when (seq chunk-eids)
  (retract-chunk-vecs! store chunk-eids)   ;; HNSW hygiene — new in this step
  (store/retract! store (vec chunk-eids)))
(when (seq link-eids)  (store/retract! store (vec link-eids)))
(when (seq file-eids)  (store/retract! store (vec file-eids)))
(when (seq root-eids)  (store/retract! store (vec root-eids)))
```

`retract-chunk-vecs!` currently lives in `index.clj` as a private helper.
Promote it to a public `idx/retract-chunk-vecs!` (or move to a shared
namespace) so `core.clj` can call it without a circular require. No
`retract-chunk-vecs!` for link eids — `:link/*` has no `:db.type/vec`
attributes, so HNSW hygiene is a non-issue for the link retract (mirrors
the `index-root!` argument in Step 4d).

**Verify**: RCF test — register a root, index a file with wiki links,
`remove-root!`, then:
1. Query `[:find [?l ...] :where [?l :link/id _]]` → confirm zero results.
2. Assert `(:missing (hnsw-health s))` is `0` (same check as the existing
   HNSW desync regression test in `index.clj`) — locks in the
   `retract-chunk-vecs!` fix so a future simplification can't silently
   re-introduce the stale-node bug.

Combined with the Step 4c / 4d tests this closes the three places a
root's data can disappear: per-file (`retract-file-by-id!`), bulk
per-root (`index-root!`), and whole-root (`remove-root!`).

### Step 2 — New `link_graph.clj` — query helpers

**File**: `clj-llm-memory/src/llm_memory/link_graph.clj` (new)

```clojure
(defn inbound-links
  "All files that have a wiki link pointing at `target-file-id`.
  Returns a vector of distinct file-id strings."
  [store target-file-id]
  (vec (store/query store
                    '[:find [?from-id ...]
                      :in $ ?tid
                      :where
                      [?l :link/to-id ?tid]
                      [?l :link/from ?f]
                      [?f :file/id ?from-id]]
                    {:tid target-file-id})))

(defn heading-inbound-links
  "Files that link to a specific heading (file-id + slug pair).
  Returns a vector of distinct file-id strings."
  [store target-file-id slug]
  (vec (store/query store
                    '[:find [?from-id ...]
                      :in $ ?tid ?slug
                      :where
                      [?l :link/to-id ?tid]
                      [?l :link/slug ?slug]
                      [?l :link/from ?f]
                      [?f :file/id ?from-id]]
                    {:tid target-file-id :slug slug})))
```

Both use Datalog's **collection-find** (`:find [?x ...]`) so the query
returns a flat de-duplicated seq, not tuples. Callers get distinct file-id
strings directly.

Write RCF tests using a fresh store with test fixtures.

**Verify**: REPL — insert dummy `:link/*` entities, confirm queries return
expected results.

### Step 3 — New `link_rewriter.clj` — AST-based rewriter

**File**: `clj-llm-memory/src/llm_memory/link_rewriter.clj` (new)

#### Sub-step 3a — Parser with `BLOCKS_AND_INLINES` spans

```clojure
(def ^:private ^Parser rewrite-parser
  (.. (Parser/builder)
      (includeSourceSpans IncludeSourceSpans/BLOCKS_AND_INLINES)
      (extensions [(TablesExtension/create)
                   (StrikethroughExtension/create)
                   (TaskListItemsExtension/create)])
      build))
```

Use the same extension set as `hiccup.clj` (Tables, Strikethrough, TaskListItems)
so `wiki:` links inside table cells and other extension nodes are correctly
parsed.

This parser uses `BLOCKS_AND_INLINES` — distinct from the one in `hiccup.clj`
(which uses `BLOCKS` only, sufficient for block-level source lines but not
inline-level SourceSpans needed by the link rewriter).

`text-content` is **not** defined here. It lives in `llm-memory.chunk/text-content`
(added in Step 0 — see `chunk.clj`). `hiccup.clj` also calls `chunk/text-content`;
the implementation is shared, not duplicated.

#### Sub-step 3b — `verified-wiki-link?` and `extract-wiki-links`

Two helpers. `verified-wiki-link?` runs the source-span exact-match check
used by both the rewriter and the indexer; `extract-wiki-links` returns
only verified links.

```clojure
(defn- verified-wiki-link?
  "True if the raw text at the link record's span contains `](<destination>`
  as a bounded substring, where <destination> exactly equals the
  parser-returned destination. False for reference-style links (whose span
  covers `[text][ref]`, not the definition), synthetic links without
  source spans, and any other ambiguous case."
  [text {:keys [destination input-index span-len]}]
  (when (and input-index span-len)
    (let [span-start input-index
          span-end   (+ span-start span-len)
          span       (subs text span-start span-end)
          sep        (str/index-of span "](")]
      (when sep
        (let [dest-start (+ span-start sep 2)
              dest-end   (+ dest-start (count destination))]
          (and (<= dest-end (count text))
               (= destination (subs text dest-start dest-end))))))))

(defn extract-wiki-links
  "Parse markdown text, return all verified inline Link nodes with wiki:
  destinations. Reference-style links, synthetic links, and any node whose
  span cannot be exact-match-verified are filtered out (and logged at
  DEBUG).

  Returns [{:destination str :input-index int :span-len int}]."
  [text] ...)
```

Walk the AST, collect `Link` nodes where `(.getDestination node)` starts with
`"wiki:"`. Extract `SourceSpan` → `{:input-index (.getInputIndex span)
:span-len (.getLength span)}`. `getInputIndex` is the absolute character
offset in the source text (commonmark-java ≥0.23.0; deps.edn pins 0.24.0) —
no line-start precompute required.

Filter the collected records through `verified-wiki-link?` before
returning. Unverified records are dropped here once — no caller needs to
re-check, and the indexer is protected from persisting `:link/*` entities
it could never rewrite. `rewrite-destination` still runs its own
verification as defense in depth against any link that bypasses extraction
(synthetic nodes constructed in tests, etc.).

**Verify**: RCF tests —
1. `[text](wiki:root::file.md)` and `[text](wiki:root::file.md#slug)` both
   return expected records.
2. A wiki link inside a fenced code block is NOT returned.
3. A **reference-style link** `[text][ref]\n\n[ref]: wiki:root::file.md` is
   NOT returned (span covers `[text][ref]`, verification fails, filtered).
4. A mixed-content file with one reference-style wiki link AND one inline
   wiki link returns only the inline one. Confirm this via
   `extract-wiki-links` directly, then confirm end-to-end via
   `extract-outbound-wiki-links` (Step 4b) that the `:link/*` graph
   contains only the inline entity.
5. **Multi-line Link**: `[display text\nwrapped](wiki:root::file.md)` where
   the display text contains a newline. commonmark-java emits one
   `SourceSpan` per source line for inline nodes that wrap, so the
   Link's first span covers only line 1 and `](` lives on line 2. The
   plan's `verified-wiki-link?` uses the Link's first span only, so this
   link fails verification and is **safely skipped** (logged at DEBUG,
   not rewritten). Pin this as the accepted behavior — multi-line
   display text is rare, and the safe-skip policy matches the broader
   "broken link beats corrupt content" invariant.

See CONTEXT.md "Reference-Style Markdown Links — Unsupported" and
"Multi-line Link Nodes — Safe-Skipped".

#### Sub-step 3c — `rewrite-destination`

```clojure
(defn- rewrite-destination
  "Given the raw markdown text, a wiki link record (input-index + span-len),
  its current destination string, and a replacement destination, return the
  modified text. Returns nil if the destination cannot be located unambiguously
  within the span."
  [text link-record new-dest] ...)
```

Algorithm (bounded + exact match — **do not scan for `)`**; it fails on
links with titles like `[x](wiki:foo "bar")` or on display text containing
escaped parens):

1. `span-start = (:input-index link-record)`,
   `span-end = (+ span-start (:span-len link-record))`.
2. Within the substring `(subs text span-start span-end)`, find the first
   occurrence of `](` — this is the display-text / destination boundary.
3. Let `dest-start = span-start + (index-of "](" within span) + 2`.
4. **Verify exact match** via `verified-wiki-link?` (or inline equivalent):
   `(subs text dest-start (+ dest-start (count current-dest)))` must equal
   `current-dest` (the parser-returned `.getDestination`). If not, return
   `nil` (skip + log). Extraction already filters these, so in practice
   this guard fires only for synthetic link records — belt-and-suspenders.
5. Replace that exact range with `new-dest`:
   `(str (subs text 0 dest-start) new-dest (subs text (+ dest-start (count current-dest))))`.

Using the parser's destination string as ground truth means we never need to
guess where the destination ends — no `)`-scan, no title-handling, no escape
handling. Any link where the verification fails is treated as ambiguous and
skipped.

**Verify**: RCF tests —
1. Modify destination in known text, confirm only the URL changes, display
   text is intact.
2. **Escaped-paren display text**: `[\](](wiki:root::file.md)` where the
   display text contains `](` as escaped literal characters. The "first
   `](` in span" search lands inside the display text, not at the
   separator; the exact-match verification (step 4) catches the
   mispositioned offset and returns `nil` (safe-skipped, DEBUG-logged).
   This pins the guard so a future "optimization" that replaces the exact
   match with a naive slice cannot silently corrupt such links. Parallel
   to the reference-style and multi-line cases in 3b — same invariant
   ("broken link beats corrupt content"), different span pathology.

#### Sub-step 3d — `rewrite-links-in-text`

```clojure
(defn rewrite-links-in-text
  "Replace wiki links in `text` whose destination starts with `old-dest`
  (or exactly equals `old-dest` when :exact? true) with `new-dest`.
  On prefix matches, preserves any suffix after `old-dest` (e.g. `#slug`).
  Applies changes in descending position order.
  Returns nil if no changes were made, or the modified string."
  [text old-dest new-dest & {:keys [exact?] :or {exact? false}}] ...)
```

1. Call `extract-wiki-links` — filter links:
   - Default (`:exact? false`): `:destination` starts with `old-dest` **AND**
     the character immediately after the prefix is `#` or end-of-destination.
     (The wider set `#`, `)`, `"`, `'`, end-of-destination is acceptable as
     belt-and-suspenders — `)`/`"`/`'` are unreachable because matching runs
     against `.getDestination`, not raw text.) This prevents false positives
     like `wiki:old.md` matching `wiki:old.md.backup`. See CONTEXT.md "Prefix
     Match Must Respect Wiki-Link Boundaries".
   - With `:exact? true`: `:destination` exactly equals `old-dest`. Required for
     heading rename propagation — prevents `#step-1` from matching `#step-10`,
     `#step-11`, etc.
2. If empty → return `nil`.
3. Sort matches descending by `:input-index`.
4. For each match: compute the full replacement destination by appending the
   suffix that follows `old-dest` in the original:
   ```clojure
   (str new-dest (subs (:destination match) (count old-dest)))
   ```
   For `:exact?` matches the suffix is always `""`. Call `rewrite-destination`
   with this computed full replacement. If `nil` returned → log warning, skip
   this occurrence only (subsequent occurrences in the same file are still
   processed).
5. Return final string, or `nil` if every occurrence was skipped (or no
   matches existed). Note: if at least one occurrence rewrote successfully
   and at least one failed verification, return the partially-rewritten
   string — failures do not abort the file.

#### Sub-step 3e — `rewrite-links-in-file!`

```clojure
(defn rewrite-links-in-file!
  "Rewrite wiki links in `abs-path`. Returns :modified, :no-change, or :error.
  Passes :exact? through to rewrite-links-in-text."
  [abs-path old-dest new-dest & {:keys [exact?] :or {exact? false}}] ...)
```

1. `slurp abs-path`.
2. Call `rewrite-links-in-text` with the `exact?` option.
3. If `nil` → `:no-change`.
4. Write atomically:
   - Open `<abs-path>.tmp` via `FileChannel/open` with `CREATE`, `WRITE`,
     `TRUNCATE_EXISTING`.
   - Write the rewritten bytes.
   - `(.force channel true)` — fsync contents **and** metadata before
     close, so a crash after rename cannot publish a truncated tmp.
   - Close the channel.
   - `Files/move <tmp> <target> ATOMIC_MOVE`.
5. On any exception (including `AtomicMoveNotSupportedException` on
   filesystems that lack atomic rename) → log + return `:error`. Do **not**
   fall back to a non-atomic move.

**Verify**: RCF tests — write test file, call rewriter, confirm file updated.
Confirm idempotency (second call returns `:no-change`). Confirm that a
write failure mid-sequence leaves the target file unchanged (the tmp may
linger but never replaces the target).

---

## Phase 2 — Integration into `index-file!`

### Step 4 — Extract wiki links during indexing

**File**: `clj-llm-memory/src/llm_memory/index.clj`

Add `[llm-memory.link-rewriter :as link-rewriter]` to `index.clj`'s `:require`.
No new Java imports in `index.clj` — all commonmark AST machinery stays in
`link_rewriter.clj`.

#### Sub-step 4a — `compute-file-id` helper

Extract a shared helper so watcher and index.clj do not duplicate the file-id
formula (currently duplicated in `index-file!`, `rename-file!`, `retract-file!`,
and `watcher.clj`'s `handle-delete!`):

```clojure
(defn compute-file-id
  "Compute file-id = root-name::rel-path from root-name, plans-abs-dir (File),
   and abs-path.

   Public — called from watcher.clj. The `rel-path` helper it delegates to
   stays private to index.clj; external callers go through this wrapper."
  [^String root-name ^File plans-abs-dir ^String abs-path]
  (str root-name "::" (rel-path plans-abs-dir (io/file abs-path))))
```

Note `defn`, not `defn-` — this is intentionally public.

Callers — **every** site that builds a `root-name::rel-path` string must go
through this helper. No inline duplication anywhere:
- `index.clj`: `index-file!`, `rename-file!`, `retract-file!`
- `watcher.clj`: `handle-delete!` (replaces its inline `(str root-name "::" rp)`
  construction), `stored-hash` (Step 11).

Migrating `handle-delete!` requires threading `plans-abs-dir` (the watcher's
`watch-dir` File) into `handle-delete!`'s signature — symmetric with Step 11's
changes to `handle-create-or-modify!`. Do both in the same edit.

#### Sub-step 4b — `extract-outbound-wiki-links`

```clojure
(defn- extract-outbound-wiki-links
  "Parse markdown body text (post-frontmatter), return distinct outbound wiki
  link maps. Each map: {:to-id str :slug str} where slug = \"\" for file-only links."
  [body] ...)
```

Takes `body` (post-frontmatter), not `text` — avoids spurious matches in YAML.
Delegate to `link-rewriter/extract-wiki-links`, which **already filters
unverifiable links** (reference-style, synthetic, anything the rewriter
could not safely round-trip — see Step 3b). This keeps the `:link/*` graph
free of entities that could never be rewritten on a target rename, so
inbound-link queries never return stale orphans that no propagation can
repair.

For each returned verified record, parse `wiki:X` destinations:
- Split on `#` → `[file-part slug-part]`
- `:to-id = (subs file-part (count "wiki:"))` — strip the `wiki:` prefix so the
  stored value is a bare file-id (e.g. `"root::path.md"`). The `inbound-links`
  query uses bare file-ids as its lookup key; storing the `wiki:` prefix would
  cause all queries to return empty results.
- `:slug = (or slug-part "")`
- Deduplicate by `[to-id slug]`.

#### Sub-step 4c — Update `retract-file-by-id!` and wire into `index-file!`

**First, update `retract-file-by-id!`** to also retract outgoing `:link/*`
entities for the file being deleted. Without this, deleted files leave orphan
link entities in the store (see CONTEXT.md "Retracting Files").

**Ordering is load-bearing.** The link-eids query joins through
`[?f :file/id ?fid] [?l :link/from ?f]`, so it MUST run while the file
entity still exists. Add `link-eids` to the same `let`-binding that
already captures `chunk-eids` and `file-eids`, then retract in the order
chunks → links → files. The same pattern is mandatory in Step 4d for
`index-root!` and in Step 1b for `remove-root!` — this keeps the three
cascade-retraction sites identical so a reader can audit the invariant
("query all joined eids up front, retract files last") in one place.

```clojure
(let [chunk-eids (store/query store ...)                ;; existing
      link-eids  (store/query store
                              '[:find [?l ...]
                                :in $ ?fid
                                :where
                                [?f :file/id ?fid]
                                [?l :link/from ?f]]
                              {:fid file-id})
      file-eids  (store/query store ...)]               ;; existing
  (when (seq chunk-eids)
    (retract-chunk-vecs! store chunk-eids)              ;; existing HNSW hygiene
    (store/retract! store (vec chunk-eids)))
  (when (seq link-eids)  (store/retract! store (vec link-eids)))
  (when (seq file-eids)  (store/retract! store (vec file-eids))))
```

Do NOT place the link-eids query in a separate `let`-block that runs after
the file-entity retract — the join would return empty, leaving orphan link
entities in the store (the exact bug this step exists to prevent).

**Then, in `index-file!`**:

1. Add a private `query-link-eids` helper (also used by `retract-file-by-id!`).
2. Before the existing chunk-retract block, retract stale `:link/*` entities
   for this file-id.
3. Build `link-entities` from `(link-rewriter/extract-wiki-links body)` via
   `extract-outbound-wiki-links`.
4. Transact **all** data in a single call: `(concat [file-entity] chunk-entities link-entities)`.

The `file-entity` map is unchanged from today — no `:file/links-indexed` flag
is written. The one-time `index-root!` backfill (Phase 3) covers pre-schema
files without needing a per-file marker.

```clojure
(defn- query-link-eids
  "Return all Datalevin entity IDs for :link/* entities whose :link/from points
  to file-id. Used to retract stale link entities before re-indexing."
  [store file-id]
  (store/query store
               '[:find [?l ...]
                 :in $ ?fid
                 :where
                 [?f :file/id ?fid]
                 [?l :link/from ?f]]
               {:fid file-id}))

;; --- inside index-file!, alongside the existing chunk retract ---
(let [existing-link-eids (query-link-eids store file-id)]
  (when (seq existing-link-eids)
    (store/retract! store (vec existing-link-eids))))

;; link-entities built from extract-outbound-wiki-links body

;; Single atomic transact — see CONTEXT.md "Single-Transact Invariant"
(store/transact! store (concat [file-entity] chunk-entities link-entities))
```

The link-entity shape:

```clojure
(map (fn [{:keys [to-id slug]}]
       {:link/id    (str file-id "@@" to-id "@@" slug)
        :link/from  [:file/id file-id]
        :link/to-id to-id
        :link/slug  slug})
     (extract-outbound-wiki-links body))
```

Use `body` (post-frontmatter), not `text`, to avoid spurious matches inside
YAML frontmatter.

**Verify**: RCF tests —
1. Index a file with known wiki links, query `:link/*` entities, confirm
   correct count and `:link/to-id` / `:link/slug` values.
2. Index a file with zero wiki links, confirm the transact succeeds
   (single atomic transact of `[file-entity] + chunks` — no link entities
   emitted; the file's `:file/*` entity is otherwise identical to today).
3. **Orphan target**: index a file that contains `wiki:root::missing.md`
   (the target is never indexed in this test). Confirm the `:link/*`
   entity is created with `:link/to-id = "root::missing.md"`, confirm
   `link-graph/inbound-links` on that file-id returns `[<from-fid>]`, and
   confirm `inbound-links` on an entirely-unknown file-id returns `[]`.
   This locks in the fact that `:link/to-id` is a bare string, not a ref,
   so unresolved targets are a valid graph state.

#### Sub-step 4d — Update `index-root!` to retract `:link/*` entities

`index-root!` bulk-retracts chunks and files for a root without calling
`retract-file-by-id!`. After this schema is live, it must also retract
`:link/*` entities.

The existing let-binding already queries `chunk-eids` and `file-eids`
before any retract runs. Add a `link-eids` query to the same let-binding —
all three queries run while file entities still exist, which is required
because the `link-eids` query joins through `:file/root`:

```clojure
;; Retract all :link/* entities for this root
link-eids (store/query store
                        '[:find [?l ...]
                          :in $ ?ruri
                          :where
                          [?r :root/uri ?ruri]
                          [?f :file/root ?r]
                          [?l :link/from ?f]]
                        {:ruri root-uri})
```

Run the retracts in this order: `chunk-vecs` → `chunks` → `links` →
`files`. The `links`-before-`files` order is technically not required once
all eids are captured (the retract calls only take eid vectors, no joins),
but it mirrors the intent of the query ordering and keeps the control
flow readable.

**Verify**: RCF test — index a file with wiki links, call `index-root!`, confirm
all `:link/*` entities are gone (no orphan links remain after a full reindex).

---

## Phase 3 — Initial Migration: One-Time `index-root!`

### Step 5 — Backfill `:link/*` entities for existing files via `index-root!`

When the schema change ships, every already-indexed file lacks `:link/*`
entities. Subsequent reconciles classify those files as `:unchanged` (content
hashes have not changed), so `reconcile!` alone will not backfill them.

**Procedure**: run `index-root!` once per registered root after the new JAR is
installed and before relying on rename propagation. `index-root!` retracts
every file/chunk/link entity for the root (see Step 4d) and re-indexes each
file, which emits fresh `:link/*` entities via the Step 4c changes.

```
# Install new JAR
cd winze-server && make install

# Connect to the running server and backfill each root
clj-nrepl-eval -p <port> "(llm-memory.core/index-root! (llm-memory.server/store) \"file:///Users/you/code/_finance\")"
clj-nrepl-eval -p <port> "(llm-memory.core/index-root! (llm-memory.server/store) \"file:///Users/you/.local/share/winze\")"
```

This is one-time. After backfill, every file has its link entities, and
subsequent reconciles/watcher events maintain them incrementally via
`index-file!`.

**Why not an automatic reconcile-time migration?**

A previous draft added a `:file/links-indexed` boolean flag and a NOT-clause
query in `reconcile!` to detect and re-embed unmigrated files automatically.
That approach was rejected:

- For ~200 documents and one operator, a documented one-liner is cheaper
  than shipping a disposable migration pass, a schema attribute, and a
  follow-up cleanup step to delete them.
- `index-root!` is the idiomatic Datalevin reset-and-rebuild operation and
  already runs every file through `index-file!`; no new code path is needed.
- Rejecting the marker avoids a per-index write of a semantically-empty flag
  that would outlive its purpose.

**Consequence — operator discipline is load-bearing.** If a watcher-detected
file rename fires **before** `index-root!` completes on the first
post-deploy startup, the link graph is partially populated and pass 2
propagation will silently skip linkers whose `:link/*` entities do not yet
exist. Release notes must name this sequencing requirement: *install →
`index-root!` each registered root → resume editing.* Reconcile-time
propagation on later sessions has no such issue because the graph is
fully populated once the one-time backfill is done.

**No `query-files-without-links` helper. No NOT-clause query. No
`:file/links-indexed` attribute.** Those artifacts are deliberately absent
from the codebase.

**Verify**: REPL against the live store, post-`index-root!` — confirm every
indexed file has at least one transacted `:link/*` entity OR has no outbound
wiki links in its body (spot-check two or three files). Confirm `plans_status`
reports clean reconcile on next watcher-driven modification.

---

## Phase 4 — File Rename Propagation

> **Note**: A previously-listed Step 6a (extend `rename-file!` to rewrite
> `:link/id` composites on rename) has been moved to **Phase 9 (Optional)**
> at the end of this document. It is not required for correctness — ref-join
> queries (`inbound-links`, `heading-inbound-links`) traverse `:link/from`,
> not `:link/id`, and pass-2 propagation retracts and re-emits the link
> entities with fresh composites anyway. Implement only if a future
> debugging path needs to scan `:link/id` prefixes.

### Step 6 — `propagate-file-rename!`

**File**: `clj-llm-memory/src/llm_memory/index.clj`

```clojure
(defn propagate-file-rename!
  "After a file has been renamed old-fid → new-fid, find all files that
  link to old-fid and rewrite their wiki links. Re-indexes modified files.
  Each affected file resolves its own root URI via the DB (linking files
  may live in a different root than the renamed target), so the
  propagating root is not a parameter — it would be unused.
  Returns {:rewritten [str] :errors [str]}."
  [store old-fid new-fid] ...)
```

1. `(link-graph/inbound-links store old-fid)` → `[from-id ...]`
2. Compute `old-dest = (str "wiki:" old-fid)` and `new-dest = (str "wiki:" new-fid)`.
   (The `#slug` suffix, if any, is preserved automatically since we replace the
   file-portion prefix: `wiki:root::old-path#slug` → `wiki:root::new-path#slug`.
   This works because we replace on `old-fid` as a prefix.)
3. For each `from-id`, wrap per-file work in try/catch so one bad linker does
   not abort the propagation for siblings (the reconcile outer loop also
   try/catches the whole call, but that loses per-linker granularity):
   - Resolve abs-path using the **from-file's own root** (not the propagating
     root — the linking file may be in a different root than the renamed file).
     If the query returns empty (DB corruption, root retracted mid-cycle),
     **log WARN** and skip — do not silently drop:
     ```clojure
     (let [row (first (store/query store
                                   '[:find ?path ?ruri ?rdir
                                     :in $ ?fid
                                     :where
                                     [?f :file/id ?fid]
                                     [?f :file/path ?path]
                                     [?f :file/root ?r]
                                     [?r :root/uri ?ruri]
                                     [?r :root/plans-dir ?rdir]]
                                   {:fid from-id}))]
       (if-not row
         ;; Parse the expected root-name out of from-id ("<root-name>::<path>")
         ;; so the error record names the root the query expected to find.
         ;; Without this, an operator inspecting :errors sees only the file-id
         ;; and has to reparse it manually to know which root registration went
         ;; missing.
         (let [expected-root (first (str/split from-id #"::" 2))]
           (log/warn "propagate: no root metadata for from-id" from-id
                     "(expected root:" expected-root ")")
           (swap! errors conj {:op :propagate-rename :from-id from-id
                               :expected-root expected-root
                               :error "no-root-metadata"}))
         (let [[fpath fruri frdir] row
               abs-path            (io/file (str/replace fruri #"^file://" "")
                                            frdir fpath)
               result (link-rewriter/rewrite-links-in-file! abs-path old-dest new-dest)]
           (cond
             (= result :modified) (try
                                    (index-file! store fruri abs-path)
                                    (swap! rewritten conj from-id)
                                    (catch Exception e
                                      (swap! errors conj {:op :propagate-rename
                                                          :from-id from-id
                                                          :abs-path (str abs-path)
                                                          :error (.getMessage e)})))
             (= result :error)    (swap! errors conj {:op :propagate-rename
                                                      :from-id from-id
                                                      :abs-path (str abs-path)
                                                      :error "rewrite-failed"})
             :else                nil))))            ;; :no-change → no work
     ```
   - **Only on `:modified`** → `(index-file! store fruri abs-path)` to update
     link entities (pass `fruri`, the from-file's root URI, not the propagating
     `root-uri`). Skipping `index-file!` on `:no-change` avoids a wasted
     HNSW retract-reinsert cycle; skipping on `:error` avoids re-indexing
     content that was never successfully rewritten (which would re-emit the
     stale `:link/*` entities and mask the error).
4. Return summary.

**Self-referential link handling**: If the renamed file links to its own old
path (e.g. a table of contents), it will appear in `inbound-links` and be
rewritten and re-indexed. `propagate-file-rename!` calls plain `index-file!`
(not the diff wrapper) for re-indexed files, so no heading-diff comparison
is triggered — no cascade.

**Hook into `reconcile!` — two-pass structure**. See CONTEXT.md
"Two-Pass Reconcile" for why per-rename propagation is incorrect when two
files are renamed in the same cycle (path resolution reads stale DB paths).

Pass 1 (DB mutations + snapshot capture) runs the existing six-category
dispatch, but collects records for pass 2 instead of firing propagation inline.

Every pass-1 branch MUST preserve the existing per-iteration try/catch that
populates the `:errors` list (see current reconcile at index.clj:704–745) —
a single bad file must not abort the whole reconcile. Pass 2 also collects
into the same `:errors` list.

```clojure
(let [rename-records  (atom [])   ;; [{:old-fid :new-fid}]
      heading-records (atom [])   ;; [{:file-id :renames [{:old-slug :new-slug} ...]}]
      rewritten       (atom [])]  ;; [str] — from propagation, for summary reporting

  ;; :renamed — use rename-file!'s return value for both ids
  (doseq [{:keys [old-path new-path]} (:renamed classified)]
    (try
      (let [old-abs (.getAbsolutePath (io/file pdir old-path))
            new-abs (.getAbsolutePath (io/file pdir new-path))]
        (when-let [{:keys [old-id new-id]} (rename-file! store root-uri old-abs new-abs)]
          (swap! rename-records conj {:old-fid old-id :new-fid new-id})))
      (catch Exception e
        (swap! errors conj {:op :rename :path new-path :error (.getMessage e)}))))

  ;; :renamed-modified — snapshot old slugs BEFORE retract (heading-rename
  ;; detection would be impossible after retract-file-by-id! discards chunks)
  (doseq [{:keys [old-path new-path]} (:renamed-modified classified)]
    (try
      (let [old-fid   (get-in db-state [old-path :file-id])
            old-slugs (snapshot-chunk-slugs store old-fid)
            new-abs   (.getAbsolutePath (io/file pdir new-path))]
        (retract-file-by-id! store old-fid)
        (let [{:keys [file-id]} (index-file! store root-uri new-abs)
              new-slugs (snapshot-chunk-slugs store file-id)
              renames   (:renamed (match-heading-renames old-slugs new-slugs))]
          (swap! rename-records conj {:old-fid old-fid :new-fid file-id})
          (when (seq renames)
            (swap! heading-records conj {:file-id file-id :renames renames}))))
      (catch Exception e
        (swap! errors conj {:op :rename-modified :path new-path :error (.getMessage e)}))))

  ;; :modified — snapshot, re-index, compare (inline version of
  ;; index-file-with-heading-diff! for the 2-pass deferred-propagation flow)
  (doseq [path (:modified classified)]
    (try
      (let [abs       (.getAbsolutePath (io/file pdir path))
            file-id   (get-in db-state [path :file-id])
            old-slugs (snapshot-chunk-slugs store file-id)]
        (index-file! store root-uri abs)
        (let [new-slugs (snapshot-chunk-slugs store file-id)
              renames   (:renamed (match-heading-renames old-slugs new-slugs))]
          (when (seq renames)
            (swap! heading-records conj {:file-id file-id :renames renames}))))
      (catch Exception e
        (swap! errors conj {:op :modify :path path :error (.getMessage e)}))))

  ;; :new and :gone — unchanged from today (no propagation needed)
  ...

  ;; No migration pass in reconcile!. Pre-schema files are backfilled once
  ;; via operator-run `index-root!` — see Phase 3.

  ;; Pass 2: propagation (DB is fully settled; path resolution and
  ;; inbound-link queries are both correct).
  ;; File renames first, then heading renames — see CONTEXT.md
  ;; "Heading Rename Propagation" for ordering rationale.
  ;; Each propagate-* call internally wraps per-file work in try/catch and
  ;; appends to its own errors list, merged into the reconcile summary.
  ;; :rewritten counts are accumulated for observability.
  (doseq [{:keys [old-fid new-fid]} @rename-records]
    (try
      (let [summary (propagate-file-rename! store old-fid new-fid)]
        (swap! errors   into (:errors summary))
        (swap! rewritten into (:rewritten summary)))
      (catch Exception e
        (swap! errors conj {:op :propagate-rename :old-fid old-fid :new-fid new-fid
                            :error (.getMessage e)}))))
  (doseq [{:keys [file-id renames]} @heading-records]
    (doseq [{:keys [old-slug new-slug]} renames]
      (try
        (let [summary (propagate-heading-rename! store file-id old-slug new-slug)]
          (swap! errors   into (:errors summary))
          (swap! rewritten into (:rewritten summary)))
        (catch Exception e
          (swap! errors conj {:op :propagate-heading :file-id file-id
                              :old-slug old-slug :new-slug new-slug
                              :error (.getMessage e)}))))))
```

The outer `reconcile!` summary gains a `:propagated` key so operators can
confirm propagation did work after a rename:

```clojure
{:unchanged N :modified N :renamed N :renamed-modified N
 :new N :gone N
 :propagated (count (distinct @rewritten))
 :errors @errors}
```

**Boot-log update in `winze-server`.** The startup `reconcile-and-watch!`
helper in [winze-server/src/llm_memory/server/main.clj](../../winze-server/src/llm_memory/server/main.clj)
prints a summary line per root (`"N unchanged, N modified, N renamed, N new, N gone"`).
Extend that line to include `(:propagated summary) "propagated"` so
operators can see at a glance whether rename propagation actually fired
during boot reconcile. Without this, the new `:propagated` signal is
invisible in the normal operational path — the only observer is a test
or a REPL caller who prints the return value.

**Rename-modified double `index-file!` is accepted** — see CONTEXT.md
"Rename-modified: double `index-file!` is accepted" for the full rationale.

**Verify**: Four RCF tests:
1. File B links to file A; rename A → confirm B's content is updated and
   B's `:link/*` entities reflect the new path.
2. File A links to its own heading; rename A → confirm the self-link in A is
   rewritten too (plain rename case via `rename-file!`).
3. **Cross-root**: file in root β links to a file in root α; rename the
   α-root file → confirm the β-root file is rewritten on disk using its own
   (β) root's plans-dir resolution, and that `index-file!` on that β file is
   called with `β-uri`, not `α-uri`.
4. **Multi-rename**: in a single reconcile cycle, rename both A and B where
   B contains a `wiki:` link to A. Confirm B's link is rewritten to the new
   A path (verifies that path resolution in pass 2 sees A's new DB path, not
   the pre-rename one). **Also assert the reconcile summary's `:propagated`
   count** — this is a new observability signal for operators, so at least
   one RCF test must lock in its behavior. Expected: `1` (B was rewritten
   once as a result of A's rename; A's own rename has no inbound linkers
   other than B itself).

---

## Phase 5 — Heading Rename Propagation

### Step 7 — `snapshot-chunk-slugs`

```clojure
(defn- snapshot-chunk-slugs
  "Query existing chunk slug → vector map for a file, before re-indexing.
  Filters out chunks with nil/blank slugs — defensive against schema
  evolution where `:chunk/slug` may be absent or empty."
  [store file-id]
  (into {}
        (filter (fn [[slug _]] (not (str/blank? slug))))
        (store/query store
                     '[:find ?slug ?vec
                       :in $ ?fid
                       :where
                       [?f :file/id ?fid]
                       [?c :chunk/file ?f]
                       [?c :chunk/slug ?slug]
                       [?c :chunk/vec ?vec]]
                     {:fid file-id})))
```

The nil/blank filter is defensive. In the current `chunk.clj`,
`build-slug-text-pairs` always assigns a non-blank slug — pre-H2 content
gets `"section-0"` (via `(str "section-" idx)`), not nil or `""`. So the
filter never fires today. It is kept for two forward-looking reasons:

1. If a future schema revision admits chunks with no `:chunk/slug`
   attribute, Datalog omits the row entirely (no match for
   `[?c :chunk/slug ?slug]`) — still safe.
2. If a future `slugify` tweak or direct transaction ever produces an
   empty string slug, `["" vec]` would enter the map and collide across
   chunks in `match-heading-renames`. The filter catches that case
   at the query boundary so the similarity algorithm stays oblivious to
   schema quirks.

**Practical consequence of `section-0`**: a real pre-H2 intro chunk
carries slug `section-0` and *does* enter the map. If a user later turns
that intro into a real `## Intro` heading, `match-heading-renames` may
classify `section-0 → intro` as a rename (similarity permitting) and
propagate any inbound `wiki:file.md#section-0` links to `#intro`. This is
correct behavior — the chunk's semantic content really was preserved
under a new slug — but worth naming so a test that sees the
classification does not appear mysterious.

### Step 8 — `match-heading-renames`

```clojure
(defn- match-heading-renames
  "Given old and new {slug → vec} maps, return heading rename pairs.
  Same greedy cosine-similarity algorithm as match-fuzzy-renames.
  Returns {:renamed [{:old-slug str :new-slug str :similarity double}]
           :added [str] :removed [str] :unchanged [str]}."
  [old-slugs new-slugs] ...)
```

Algorithm mirrors `match-fuzzy-renames` lines 132–180 but operates on
per-heading slug→vector maps instead of per-file path→centroid maps.
Uses the same `rename-similarity-threshold` (0.6).

**Verify**: RCF tests — known old/new slug maps with a clear rename, confirm
correct classification.

### Step 9 — `propagate-heading-rename!`

```clojure
(defn propagate-heading-rename!
  "After detecting that a heading was renamed within `file-id` from old-slug
  to new-slug, rewrite inbound links that reference the old heading.
  Each affected file resolves its own root URI via the DB (linking files
  may live in a different root than the file whose heading was renamed),
  so the propagating root is not a parameter.
  Returns {:rewritten [str] :errors [str]}."
  [store file-id old-slug new-slug] ...)
```

1. `(link-graph/heading-inbound-links store file-id old-slug)` → `[from-id ...]`
2. Compute `old-dest = (str "wiki:" file-id "#" old-slug)`,
   `new-dest = (str "wiki:" file-id "#" new-slug)`.
3. For each `from-id`: rewrite + re-index following the same pattern as step 6,
   including the cross-root-aware path resolution query (the from-file may be in
   a different root than the file whose heading was renamed). Pass the from-file's
   own `fruri` to `index-file!`.
   **Call `rewrite-links-in-file!` with `:exact? true`** — `old-dest` already
   includes the `#slug` anchor, so the default prefix match would incorrectly
   modify sibling headings (e.g. `#step-1` as a prefix matches `#step-10`,
   `#step-11`).
4. **Only on `:modified`** → `(index-file! store fruri abs-path)`. On
   `:no-change` or `:error`, skip re-indexing for the same reasons described
   in Step 6.

No `root-uri` parameter: both `propagate-*!` functions drive entirely off
the from-file's own root (resolved per-file from the DB). Log lines can
include `file-id`, which embeds the root-name, for operator context.

### Step 10 — `index-file-with-heading-diff!` wrapper

```clojure
(defn index-file-with-heading-diff!
  "Like index-file!, but also detects heading renames and propagates them.
  Used by the watcher for single-file :modify events."
  [store root-uri abs-path] ...)
```

1. Compute `file-id` from `abs-path`: call `(resolve-root store root-uri)`
   to get `:root/name` and `:root/plans-dir`, derive the absolute Plans/
   directory as `(plans-dir-path root-uri plans-dir)`, then
   `(compute-file-id root-name plans-abs-dir abs-path)`. Do NOT reconstruct
   the `root-name::rel-path` formula inline — go through the Step 4a helper.
2. If file already indexed: `(snapshot-chunk-slugs store file-id)` → `old-slugs`.
   If not yet indexed (no entity at `file-id`): skip the snapshot, just
   `index-file!` — there are no prior headings to diff against.
3. `(index-file! store root-uri abs-path)` (retracts chunks, re-indexes).
4. `(snapshot-chunk-slugs store file-id)` → `new-slugs`.
5. `(match-heading-renames old-slugs new-slugs)` → `{:renamed [...] ...}`.
6. For each `{:old-slug :new-slug}` in `:renamed`:
   `(propagate-heading-rename! store file-id old-slug new-slug)`.
7. Return combined summary.

**Callers**: `index-file-with-heading-diff!` is used by the **watcher** for
single-file `:modify` events (no multi-file race). It is **not** used by
`reconcile!` — reconcile inlines the snapshot/index/snapshot/compare logic
to defer propagation to pass 2 (see Step 6). Factoring out a shared helper
would require either a `:defer?` flag or returning records instead of firing
propagation; the inline path in reconcile is clearer and the snapshot logic
is a few lines.

**Verify**: RCF end-to-end — index file A (linked-to) and file B (links to A's
heading). Rename the heading in A. Call `index-file-with-heading-diff!` on A.
Confirm B's content is updated and `:link/slug` in B's link entity reflects the
new slug.

---

## Phase 6 — Watcher Reentrancy Guard

### Step 11 — Content-hash guard in `handle-create-or-modify!`

**File**: `clj-llm-memory/src/llm_memory/watcher.clj`

Add a helper to look up the stored content hash for a file:

```clojure
(defn- stored-hash
  "Return the DB content hash for abs-path under root-uri, or nil.
   `plans-abs-dir` is a File pointing to the absolute Plans/ directory
   for this root (the watcher's watch-dir)."
  [store root-name plans-abs-dir abs-path]
  (let [fid (idx/compute-file-id root-name plans-abs-dir abs-path)]
    (ffirst (store/query store
                         '[:find ?hash
                           :in $ ?fid
                           :where
                           [?f :file/id ?fid]
                           [?f :file/content-hash ?hash]]
                         {:fid fid}))))
```

Uses the shared `idx/compute-file-id` helper introduced in Step 4a — the
single source of truth for the `root-name::rel-path` formula. Every watcher
call site that constructed this string inline (`stored-hash`,
`handle-delete!`) must now delegate to `idx/compute-file-id`.

**Naming note**: the param is called `plans-abs-dir` (a `File` pointing at the
absolute Plans/ directory). The existing watcher closure already has
`plans-dir` as a **String** (the relative `:root/plans-dir` value from the
root entity) and `watch-dir` as the File (= base-path + plans-dir). Pass
`watch-dir` as `plans-abs-dir` — do NOT shadow the existing `plans-dir`
string with a different-typed value.

Update the handler closure and `handle-create-or-modify!` signature:

```clojure
;; in start-watcher! — watch-dir is already the absolute Plans/ File
handler (fn [{:keys [type path]}]
          (when (md-file? path)
            (let [abs-path (.toString (.toAbsolutePath path))
                  key      [root-uri abs-path]]
              (case type
                :create (debounce! key debounce-delay-ms
                                   #(handle-create-or-modify!
                                     store root-uri root-name watch-dir abs-path :create))
                :modify (debounce! key debounce-delay-ms
                                   #(handle-create-or-modify!
                                     store root-uri root-name watch-dir abs-path :modify))
                :delete (handle-delete! store root-uri root-name watch-dir abs-path)
                nil))))
```

`handle-delete!`'s signature grows `plans-abs-dir` so it can call
`idx/compute-file-id` instead of reconstructing the file-id inline
(eliminates the last duplication of the formula in this namespace).

Modify `handle-create-or-modify!` to guard before indexing:

```clojure
(defn- handle-create-or-modify!
  [store root-uri root-name plans-abs-dir abs-path event-type]
  (let [file      (io/file abs-path)
        text      (slurp file)
        disk-hash (sha256 text)
        db-hash   (stored-hash store root-name plans-abs-dir abs-path)]
    (if (= disk-hash db-hash)
      ;; Skip indexing (already current) but STILL notify listeners, so
      ;; propagation-rewritten files don't leave UI/integrations showing
      ;; stale content. See CONTEXT.md "Content-hash guard in the watcher".
      (do
        (log root-name "DEBUG" "skip reindex (hash match); notifying listeners" abs-path)
        (notify-listeners! root-name root-uri abs-path event-type nil))
      (if (= event-type :create)
        ...rename-check then index...
        (do
          (log root-name "INFO" "index (modified)" abs-path)
          (idx/index-file! store root-uri abs-path)
          (notify-listeners! root-name root-uri abs-path :modify nil))))))
```

The guard keys by **the event's own file-id** (derived from `abs-path` via
`compute-file-id`), not by raw content hash. For a `:create` event that is
actually a rename, the new path has no DB entity yet → `stored-hash` returns
`nil` → guard does not short-circuit → rename detection runs normally. The
guard only fires on path-stable re-entries (same file at same path, same
content) — exactly the propagation-induced reindex case it exists to prevent.
See CONTEXT.md "Content-hash guard in the watcher".

**Verify**: RCF tests —
1. Index a file, spit the same content again (identical hash). Confirm the
   watcher calls `stored-hash`, skips `index-file!`, **and still calls
   `notify-listeners!`** with the original event type — register a listener
   and assert it received exactly one event.
2. Modify a file's content. Confirm `index-file!` runs AND the listener
   fires once — unchanged from today's behavior.

Propagation functions (`propagate-file-rename!`, `propagate-heading-rename!`)
take no `:suppress-fn` argument. Watcher reentrancy is handled entirely by
the content-hash guard in Step 11 — see CONTEXT.md "No secondary suppress
mechanism".

The propagation body is:

```clojure
;; propagate-file-rename! per-from-file
;; (no root-uri parameter — fruri is resolved from the DB for each from-file):
(doseq [{:keys [abs-path fruri from-id]} affected-files]
  (let [result (link-rewriter/rewrite-links-in-file! abs-path old-dest new-dest)]
    (cond
      (= result :modified) (index-file! store fruri abs-path)
      (= result :error)    (swap! errors conj {:op :propagate-rename
                                                :from-id from-id
                                                :abs-path abs-path
                                                :error "rewrite-failed"})
      :else                nil)))                ;; :no-change → no work

;; propagate-heading-rename! per-from-file: same pattern, with :exact? true.
;; Errors carry :op :propagate-heading so reconcile's merged :errors list
;; stays homogeneous — see CONTEXT.md "Error-record shape consistency".
(doseq [{:keys [abs-path fruri from-id]} affected-files]
  (let [result (link-rewriter/rewrite-links-in-file! abs-path old-dest new-dest
                                                      :exact? true)]
    (cond
      (= result :modified) (index-file! store fruri abs-path)
      (= result :error)    (swap! errors conj {:op :propagate-heading
                                                :from-id from-id
                                                :abs-path abs-path
                                                :error "rewrite-failed"})
      :else                nil)))
```

---

## Phase 7 — Watcher Integration

### Step 12 — Wire `index-file-with-heading-diff!` into the watcher

**File**: `clj-llm-memory/src/llm_memory/watcher.clj`

The watcher currently calls `index-file!` on modify events. Replace with
`index-file-with-heading-diff!` — but only after the content-hash guard
(Step 11) confirms this is genuinely new content:

```clojure
(idx/index-file-with-heading-diff! store root-uri abs-path)
```

For rename events that the watcher detects (delete+create within the rename
window — the `:create` branch of `handle-create-or-modify!` when `match-rename`
returns a match), call `propagate-file-rename!` immediately after `rename-file!`
using its return value for `old-fid`/`new-fid`. **The existing
`notify-listeners!` call must be preserved** — external listeners (UI,
integrations) still need the `:rename` event. Only the indexing work is
augmented, not the listener protocol:

```clojure
(if-let [{:keys [old-id new-id]} (idx/rename-file! store root-uri (:path match) abs-path)]
  (do
    (idx/propagate-file-rename! store old-id new-id)
    (notify-listeners! root-name root-uri abs-path :rename
                       {:old-path (:path match) :new-path abs-path}))
  ;; rename-file! returned nil — old file entity was not indexed.
  ;; INTENTIONAL BEHAVIOR CHANGE vs. current watcher.clj:180-182:
  ;;   today the watcher calls rename-file! unconditionally, ignores the nil
  ;;   return, and fires a :rename listener event for a file that is NOT in
  ;;   the index — leaving the file unindexed while downstream listeners
  ;;   believe it moved. That's a latent bug.
  ;;   NEW behavior: index the new path as a fresh :create. File ends up
  ;;   correctly indexed; listeners see :create (not :rename), which is
  ;;   accurate — there was no "from" entity to rename.
  (do (idx/index-file! store root-uri abs-path)
      (notify-listeners! root-name root-uri abs-path :create nil)))
```

`rename-file!` returns `nil` when the old entity is not found (file was not
indexed before the rename window). `old-id` is also available as
`(:file-id match)` from the pending-deletes record, but using the return
value avoids duplication and is the authoritative source.

**Downstream consumer note**: any UI/integration that distinguishes `:rename`
from `:create` will see this edge case as `:create` instead of `:rename`.
Acceptable — in this state there is no "old path" that the listener could
act on (the old entity did not exist).

**Atomicity note**: `rename-file!` + `propagate-file-rename!` are not
transactional — a JVM crash between them leaves inbound links on disk
pointing at the old path. See CONTEXT.md "Atomicity Gap" — accepted.

**Verify**: REPL — start watcher, rename a heading in a linked-to file, confirm
the linking file is updated on disk and re-indexed. Confirm no second round of
propagation fires (content-hash guard catches the self-triggered watcher event).

---

## Phase 8 — Smoke Test

### Step 13 — winze-server UI smoke test

The schema change lives in `clj-llm-memory`, but `winze-server` depends on
it via `:local/root` and consumes `:file/*`/`:chunk/*` attributes throughout
`ui/hiccup.clj`, `ui/main_window.clj`, and `ui/link_preview.clj`. Before
declaring the work complete, verify the UI still renders correctly and wiki
links in the rendered viewer still navigate — the schema is additive so no
breakage is expected, but an explicit smoke check catches missed edits in
consumer code.

```
# 1. Build and install the new JAR
cd winze/clj-llm-memory && make test
cd winze/winze-server    && make install-winze

# 2. Restart the Winze server via the MCP auto-start path
clj-nrepl-eval --discover-ports    # confirm port published
clj-nrepl-eval -p <port> "(llm-memory.core/plans-status (llm-memory.server/store))"

# 3. Backfill link graph for each registered root (Phase 3)
clj-nrepl-eval -p <port> "(llm-memory.core/index-root! (llm-memory.server/store) \"file:///Users/you/code/_finance\")"

# 4. Open the main UI and screenshot-verify
clj-nrepl-eval -p <port> "(llm-memory.ui.main-window/show-main-window)"
#   — Click through a few files, confirm rendered links still work
#   — Trigger an in-editor rename of a linked-to file, confirm linker updates
#   — Use search to confirm results render identically to pre-change
```

**Verify**: UI opens without stack traces; `plans_status` reports clean
reconcile; search-plans returns identical result counts to a pre-change
baseline (±1 for any file legitimately modified during the test).
Screenshot-verify per [winze/Plans/SWT-UI-GUIDE.md](../SWT-UI-GUIDE.md)
conventions.

**Focus-invariant check** (per CONTEXT.md "UI Navigation Invariant — Focus
Must Follow the Active Tab"): after clicking an inline
`[text](wiki:root::file.md)` link in the rendered viewer, press **Cmd+E**
*without* an intermediate click. It must toggle the newly-opened tab into
edit mode. Press **Esc** — it must return the tab to view mode. If either
key is a no-op, focus did not land in the new tab's content widget and the
`open-file-in-tab!` → `focus-selected-tab-content!` wiring has regressed.
On macOS, Cocoa swallows key events when focus is on a non-key-receiving
widget (ToolBar, hidden Browser, or `nil`), so the bug will manifest as
"keys simply do nothing" rather than a stack trace.

No source-code changes in `winze-server` are planned. If the smoke test
reveals a consumer-code issue (e.g. a UI namespace touches a retired
attribute, or a new navigation path bypasses `focus-selected-tab-content!`),
that is a scope surprise — stop and amend the plan rather than patching
around it.

---

## Phase 9 — Optional: `:link/id` Composite Consistency on Rename

This phase is **not required for correctness**. Implement only if a future
debugging path, diagnostic dashboard, or REPL tool needs to scan `:link/id`
prefixes directly. Ref-join queries (`inbound-links`,
`heading-inbound-links`) traverse `:link/from`, not `:link/id`, and are
unaffected by a stale composite. Pass-2 propagation retracts and re-emits
link entities on every affected file, so the stale composite on an outgoing
link from the renamed file also self-heals within the same reconcile cycle
(or within one watcher event cycle for the rename path). The window where
the stale composite is observable externally is microseconds inside one
transaction batch.

### Step 6a (deferred) — Extend `rename-file!` to update `:link/id` composites

**File**: `clj-llm-memory/src/llm_memory/index.clj`

`rename-file!` already rewrites `:chunk/id` values (which embed the
file-id prefix) in the same transaction as the file-entity update. Add a
parallel block that rewrites `:link/id` on outgoing link entities — they
embed the from-file-id in the composite key `<from-fid>@@<to-fid>@@<slug>`.

```clojure
;; Alongside the existing chunk-id updates
link-updates (mapv (fn [[leid old-lid]]
                     {:db/id   leid
                      :link/id (str/replace old-lid old-fid new-fid)})
                   (store/query store
                                '[:find ?l ?lid
                                  :in $ ?fid
                                  :where
                                  [?f :file/id ?fid]
                                  [?l :link/from ?f]
                                  [?l :link/id ?lid]]
                                {:fid old-fid}))
```

Include `link-updates` in the same transact as `file-update` and
`chunk-updates`: `(into [file-update] (concat chunk-updates link-updates))`.

The `str/replace` is global — it updates every occurrence of `old-fid`
in each `:link/id` composite. For ordinary outbound links only the
`from-fid` prefix matches. For **self-referential links** (a file
linking to its own heading), the composite is
`<old-fid>@@<old-fid>@@<slug>` and the replacement fires twice — both
occurrences become `new-fid`, which is the correct terminal state
(pass 2's propagation then rewrites the file on disk and the subsequent
`index-file!` upserts a fresh row over it).

**Substring-prefix corruption.** Global `str/replace` also matches
`old-fid` occurrences *inside* a `to-id` segment when one file-id is a
strict substring of another (e.g. `old-fid = "r::a.md"` linking to
`to-id = "r::a.md.bak"`). That fires a third substitution that corrupts
the `:link/id` of a link pointing at an unrelated file. Before Phase 9
can ship, EITHER the separator must change to `\u001f` (prohibited in
paths and root-names), OR the `str/replace` must be replaced by a
precise segment rewrite (split on `@@`, rewrite segment 0 only, rejoin),
OR Phase 9 must be rejected for workspaces where file-ids can nest.
See CONTEXT.md "Substring-prefix corruption risk".

**RCF tests for Step 6a** (all three required if implemented):
1. Non-self-referential: a renamed file's outgoing `:link/id` values all
   start with `new-fid@@`.
2. **Self-referential**: a file links to its own heading, is renamed, and
   its one outgoing `:link/id` transitions through
   `<new-fid>@@<new-fid>@@<slug>` — asserted by pulling the entity after
   `rename-file!` (pre-propagation, pass 1 state) and again after pass
   2 propagation completes (post-rewrite, post-re-index). Both snapshots
   must have `:link/to-id` consistent with the current `:file/id`.
3. **Co-located prefix**: a file with `old-fid = "r::a.md"` and an
   outgoing link to `to-id = "r::a.md.bak"` (which also exists and is
   indexed). After `rename-file!` rewrites the file to `new-fid`, the
   `:link/to-id` segment of the composite MUST still read
   `r::a.md.bak`, not `<new-fid>.bak`. This is the regression test for
   the substring-prefix bug — a failing assertion here blocks Phase 9.

---

### Step 9b (deferred) — Startup `:link/to-id` → `:file/id` integrity sweep

**File**: `clj-llm-memory/src/llm_memory/index.clj` (new function) and
`winze-server/src/llm_memory/server/main.clj` (invocation on server start).

**Not required for correctness.** This is the single mitigation that
covers the "rename-then-crash before propagation completes" atomicity
gap that both drivers (watcher and reconcile) share — see CONTEXT.md
"Atomicity Gap: `rename-file!` + `propagate-file-rename!`". Reconcile
on the next startup cannot repair the gap because the linking files'
content hashes are unchanged (`:unchanged` classification, no
propagation). Implement when operator-facing incidents attribute broken
inbound links to crashes — for single-operator use today, the window is
narrow enough that manual REPL recovery is cheaper than the sweep
infrastructure.

```clojure
(defn link-integrity-report
  "Find all :link/to-id values that do not resolve to any :file/id.
  Returns a seq of {:from-id str :to-id str :slug str} — one entry per
  unresolved link, for operator review.

  A non-empty result after a clean startup indicates one of:
    (a) inbound links that survived a rename-crash and need manual repair
        (rewrite the linker file, re-index), OR
    (b) intentional forward references to files that have not yet been
        created (benign — the link will resolve once the target exists).

  The caller distinguishes (a) vs (b) by context; this function only
  reports."
  [store]
  (store/query
   store
   '[:find ?from-id ?to-id ?slug
     :where
     [?l :link/to-id ?to-id]
     [?l :link/slug ?slug]
     [?l :link/from ?f]
     [?f :file/id ?from-id]
     (not-join [?to-id]
       [?target :file/id ?to-id])]))
```

Wire into the server boot sequence as a post-reconcile diagnostic:

```clojure
;; In winze-server/src/llm_memory/server/main.clj after reconcile! completes
(let [orphans (idx/link-integrity-report store)]
  (when (seq orphans)
    (log/warn (str "Link integrity: " (count orphans)
                   " :link/to-id value(s) do not resolve to any indexed file."
                   " Review with (llm-memory.index/link-integrity-report store)."))))
```

**Verify**: RCF test — index two files A and B where B links to A.
Retract A (simulating a crash where the linker was not propagated).
`link-integrity-report` returns `[{:from-id "...B" :to-id "...A" :slug ""}]`.
Re-index A from disk. `link-integrity-report` returns `[]`.

**Why deferred**:
- The underlying gap is <100ms wide in the happy case (rename-file! +
  propagate-file-rename! committing back-to-back).
- A `not-join` against `:file/id` runs a full `:link/to-id` scan; this
  is fine at current store size (~200 files, ~500 links) but is an O(L)
  boot-time cost that isn't justified until the gap becomes observable.
- Implementing now would require threading a logging channel into the
  server's boot path that does not yet exist.

## Completion Criteria

- [x] Step 0: `commonmark-java` dep added; `chunk/text-content` in `chunk.clj` with RCF tests; `hiccup.clj` calls `chunk/text-content`; `make test` passes in both projects
- [ ] Step 1: `:link/*` in schema (no `:file/links-indexed` — migration is one-time operator-run `index-root!`, see Phase 3); `make test` passes
- [ ] Step 1a: `register-root!` rejects (a) a second registration using the same `:root/name` under a different `:root/uri`, and (b) a re-registration of the same `:root/uri` under a different `:root/name` (root-rename is unsupported); re-registering the same `[uri name]` remains idempotent; RCF tests cover all three outcomes. Non-atomicity of the query-then-transact check is explicitly documented — not a blocker for single-operator use.
- [ ] Step 1b: `remove-root!` retracts `:link/*` entities in the same let-binding that collects chunk/file/root eids (joined via `:file/root`), with retract order chunk-vecs → chunks → links → files → root; **also patches the pre-existing HNSW-hygiene latent bug in `remove-root!` by calling `retract-chunk-vecs!` before the chunk retract (today's code skips this, leaving stale HNSW nodes — the same class of bug the `index-file!` desync regression test locks in)**; RCF test confirms (i) zero `:link/*` entities remain after a root is removed and (ii) `(:missing (hnsw-health s))` is `0` after `remove-root!` on a store that had indexed content.
- [ ] Step 2: `link_graph.clj` with `inbound-links` and `heading-inbound-links` queries + RCF tests
- [ ] Step 3: `link_rewriter.clj` with AST-based rewriter (3a–3e); uses `SourceSpan.getInputIndex` directly (no `line-start-offsets` helper); `verified-wiki-link?` helper is the single source of span validation (used by both `extract-wiki-links` and `rewrite-destination`); atomic write uses `FileChannel.force(true)` before `Files/move ATOMIC_MOVE` (no non-atomic fallback); RCF tests include code-block safety case, `:exact?` mode (prefix does not match `#step-10` when `old-dest = "...#step-1"`), prefix-boundary case (`wiki:old.md` does not match `wiki:old.md.backup`), **reference-style link filtered at extraction** (mixed file with both `[text][ref]` reference-style link AND one inline `[text](wiki:...)` link; `extract-wiki-links` returns only the inline record; after indexing, `:link/*` graph contains only the inline entity), and **multi-line Link display text** (`[display\nwrapped](wiki:...)` fails span verification and is safe-skipped with DEBUG log)
- [ ] Step 4: `compute-file-id` helper extracted (4a) and is the **single** source of the `root-name::rel-path` formula — no inline duplication anywhere (`index.clj`, `watcher.clj:handle-delete!`, `watcher.clj:stored-hash` all delegate); `extract-outbound-wiki-links` (4b) takes `body` not `text` **and relies on `extract-wiki-links`'s built-in verification filter** (no unverified links enter `:link/*`); `retract-file-by-id!` retracts `:link/*` entities (4c) — **`chunk-eids`, `link-eids`, and `file-eids` all queried in the same let-binding before any retract fires**, then retracted in order chunks → links → files (the link-eids query joins through `:file/id`, so it MUST run while the file entity still exists); **RCF tests confirm (i) deleting a file with outgoing wiki links removes its `:link/*` entities (no outgoing orphans) AND (ii) deleting a file that has inbound wiki links leaves the linking files' `:link/*` entities intact with `:link/to-id` pointing at the now-absent file-id, and `inbound-links` on that file-id returns `[]`** (the ref join on `:link/from → :file/id = from-id` still works because linking files still exist); `index-root!` queries `link-eids` alongside `chunk-eids` and `file-eids` before any retract fires, then retracts in order chunk-vecs → chunks → links → files (4d); `index-file!` does a single atomic final transact of `[file-entity] + chunks + links` **with no `:file/links-indexed` flag**; RCF tests confirm
- [ ] Step 5 (Phase 3): Operator runs `index-root!` once per registered root after installing the new JAR, **before** relying on rename propagation. Release notes document the sequencing requirement. Verify: post-backfill, every indexed file has transacted `:link/*` entities (or provably has no outbound wiki links). No reconcile-time migration pass in the code.
- [ ] Step 6: File rename propagation works end-to-end under 2-pass reconcile; **every pass-1 branch preserves try/catch around the new propagation-record capture so a single bad file does not abort reconcile**; **`propagate-*!` wraps each from-file in its own try/catch** (per-linker granularity — one bad linker does not abort propagation for siblings); **empty cross-root resolution logs WARN + records error with `:expected-root` parsed from the from-id** (not silent drop); **`index-file!` is called only on `:modified` return from `rewrite-links-in-file!`** (skipped on `:no-change` / `:error`); **every propagation error record follows the canonical shape documented in CONTEXT.md "Error-record shape consistency"** — `:op` (`:propagate-rename` or `:propagate-heading`), `:from-id`, and `:error` always; `:abs-path` included once path resolution has succeeded; `:expected-root` substituted for `:abs-path` only on the `no-root-metadata` pre-resolution branch; reconcile summary gains a `:propagated` count derived from aggregated `:rewritten` lists; **`winze-server/src/llm_memory/server/main.clj` boot log adds `(:propagated summary) "propagated"`** to the per-root reconcile summary line (otherwise the new signal is invisible in normal operations); RCF tests include (a) cross-file rename, (b) self-referential link case, (c) **cross-root** case (linking file in a different root from the renamed target), (d) **multi-rename** case (two files renamed in one reconcile cycle, one linking to the other — confirms pass-2 path resolution reads settled DB), (e) **error-shape conformance** — assert a `:propagate-rename` error carries both `:from-id` and `:abs-path`, and a `no-root-metadata` error carries `:expected-root` but not `:abs-path`
- [ ] Steps 7–10: Heading rename propagation works end-to-end; `snapshot-chunk-slugs` **filters nil/blank slugs** (the leading pre-H2 chunk has no slug); `index-file-with-heading-diff!` wraps `index-file!` + `propagate-heading-rename!` (no `:suppress-fn`); reconcile uses inline pass-1 snapshot / pass-2 propagation; `propagate-heading-rename!` also guards `index-file!` on `:modified` and wraps each linker in try/catch; **heading renames inside a rename-modified file are detected and propagated** (pre-retract snapshot); RCF test passes
- [ ] Step 11: Content-hash guard in `handle-create-or-modify!` uses `idx/compute-file-id`; guard keys by file-id (not raw content hash), so rename detection is unaffected; **skip branch still calls `notify-listeners!`** so propagation-rewritten files don't leave downstream listeners displaying stale content; `watch-dir` threaded through handler closure; `handle-delete!` also migrated to `compute-file-id`; RCF tests confirm (a) skip-plus-notify on matching hash (listener fires exactly once) and (b) normal index-plus-notify on mismatch
- [ ] Step 12: Watcher calls `index-file-with-heading-diff!` on `:modify` events; on detected watcher renames, calls `propagate-file-rename!` with `rename-file!`'s return value **and preserves the existing `notify-listeners!` call on the `:rename` event**; **nil-return edge case falls back to `index-file!` + `:create` listener** (intentional behavior change from current `:rename`-for-unindexed-file bug — documented in Step 12); no `:suppress-fn` parameter anywhere; propagation verified live in REPL; no duplicate index calls observed (content-hash guard handles self-triggered events)
- [ ] Step 13 (Phase 8, smoke test): `make test` clean in `clj-llm-memory/`; `make install-winze` succeeds; Winze server restarts cleanly; `plans_status` reports clean reconcile; `index-root!` run on each registered root completes without error; main-window UI opens and search-plans returns sensible results on screenshot-verify; in-editor rename of a linked-to file updates the linker file on disk
- [ ] `make test` clean in `clj-llm-memory/`
- [ ] `make install` + restart; `plans_status` reports clean reconcile
- [ ] Phase 9 (Step 6a) **deferred — only implement if a future diagnostic needs `:link/id` prefix scans**. Not required for correctness. If revived, the co-located-prefix RCF test (Step 6a test 3) is a ship-blocker — failing it means the substring-prefix bug is live.
- [ ] Phase 9b (Step 9b, `link-integrity-report`) **deferred — only implement when rename-crash atomicity gaps become operator-visible**. Single mitigation covering both drivers. Not required for correctness.

## Key Risks

| Risk | Mitigation |
|------|------------|
| Source spans absent for some `Link` nodes | Skip + warn; never fall back to regex |
| Propagation cascade (A renames → B rewritten → B's headings trigger C) | `propagate-*` functions call `index-file!`, not `index-file-with-heading-diff!`. Heading comparison only occurs at the top of the call chain, never recursively. |
| Watcher fires on propagation-written files — redundant reindex | Content-hash guard in `handle-create-or-modify!` skips `index-file!` on hash match (but still calls `notify-listeners!` so UI/integrations see the event exactly once per disk change). Every race this leaves open ends in an idempotent outcome (same chunks, same HNSW state). See CONTEXT.md "Content-hash guard in the watcher" and "No secondary suppress mechanism" for why the suppress-set was rejected. |
| Watcher rename detection requires exact content-hash match — a runtime rename-with-edit (delete + create where content also changed) is seen as `gone` + `new`, not as a rename, so `propagate-file-rename!` does NOT fire via the watcher | Pre-existing limitation of `match-rename` ([watcher.clj:149-163](../../clj-llm-memory/src/llm_memory/watcher.clj#L149-L163)). Reconcile on the next cycle catches this case via `match-fuzzy-renames` (embedding-similarity matching in `classify-files`) and fires pass-2 propagation then. Accepted — the inbound-link window is one session. |
| Watcher beats propagation's `index-file!` (debounce fires before commit) | Watcher calls `index-file!` first (cache miss); propagation's call is then redundant on identical content (no-op outcome). Short race window (<100ms). |
| Circular dep: `index.clj` propagation cannot call `watcher.clj` | Not required. Content-hash guard is watcher-internal — `index.clj` has no knowledge of it. |
| `rename-file!` + `propagate-file-rename!` not transactional (crash leaves inbound links dangling) | Accepted narrow window. See CONTEXT.md "Atomicity Gap". Future startup sweep could detect `:link/to-id` values with no matching `:file/id`. |
| Self-referential link during file rename | File appears in its own inbound-links query; rewritten and re-indexed via plain `index-file!`. No special case; no cascade. In the rename-modified path this produces a redundant second `index-file!` on the same file — idempotent *outcome*, but one extra HNSW retract-then-insert cycle. Accepted. |
| Self-referential bare `#fragment` links not updated on heading rename | Known limitation. Bare fragment links are not tracked in `:link/*`. Only `wiki:` links are propagated. |
| Prefix match corrupts `wiki:old.md.backup` when rewriting `wiki:old.md` | `rewrite-links-in-text` requires a valid delimiter (`#`, `)`, `"`, `'`, end-of-dest) after the prefix. RCF test enforces. |
| Heading slug prefix ambiguity (`#step-1` prefix-matches `#step-10`) | `propagate-heading-rename!` calls `rewrite-links-in-file!` with `:exact? true`. |
| Two transacts in `index-file!` could leave partial state on crash | Single atomic **final** transact: `(concat [file-entity] chunk-entities link-entities)`. Pre-transact retracts are idempotent. See CONTEXT.md "Single-Transact Invariant". |
| Pre-schema files have no `:link/*` entities; rename propagation misses them silently until backfill | Operator runs `index-root!` once per registered root post-deploy (Phase 3). Release notes must document the sequencing: *install → `index-root!` → resume editing*. No reconcile-time migration flag, no Phase 8 cleanup — trading one operator step for simpler code. |
| Multiple renames in one reconcile cycle cause stale-path propagation (A and B both renamed; B links to A; per-rename propagation reads B's old DB path) | 2-pass reconcile: all DB mutations in pass 1, all propagations in pass 2. Pass 2 reads a settled DB where every file's `:file/path` matches disk. See CONTEXT.md "Two-Pass Reconcile". |
| Heading renames hidden inside a rename-modified change become invisible (old chunks discarded before diff can snapshot) | Pass 1 snapshots old chunk slugs/vectors *before* `retract-file-by-id!`, snapshots new ones after `index-file!`, and records `:renamed` pairs for pass 2 heading propagation. |
| Reference-style `wiki:` links (`[text][ref]\n\n[ref]: wiki:...`) are not extracted or rewritten | Accepted limitation, enforced end-to-end. `verified-wiki-link?` (Step 3b) filters them at extraction time, so they never enter the `:link/*` graph and never produce orphan entities pointing at renamed targets. The rewriter also skips them defensively. Inline `[display](wiki:...)` is the supported form. See CONTEXT.md "Reference-Style Markdown Links — Unsupported". |
| Crash between link-entity retract and final transact leaves file with zero link entities until the next content edit | Accepted (microseconds-wide window, mirrors existing chunk-retract gap). Next `index-file!` call (from a user edit, `index-root!`, or reconcile `:modified` classification if content hash changes) will re-emit the link entities. |
| Content-hash guard short-circuits rename detection | False alarm: the guard keys by the event's own file-id (via `compute-file-id`). A renamed file's new path has no DB entity → guard returns nil → rename detection runs. The guard only fires for path-stable re-entries. |
| Multi-line `Link` display text (`[display\nwrapped](wiki:...)`) fails span verification | Accepted — safe-skipped, DEBUG-logged. commonmark-java emits per-line `SourceSpan`s and the plan uses only the Link's first span. Rare in practice; "broken link beats corrupt content" invariant applies. Pin behavior in Step 3b's RCF tests. |
| Silent drop of `inbound-links` result when `from-id`'s root metadata is missing | Explicit WARN + error record in propagation summary (Step 6). DB-corruption indicator surfaces to operator instead of disappearing. |
| Watcher rename edge case where `rename-file!` returns nil (old entity not indexed) | Today: unconditional `:rename` notify for an unindexed file — latent bug. New behavior (Step 12): `:create` listener + fresh `index-file!`. Intentional correction, documented inline. |
| `DatalevinPlanStore/query` positional binding relies on `(vals params)` map iteration order — a maintainer who adds a third `:in` param via `hash-map`/`assoc` past the 8-entry array-map threshold could silently break positional matching | All new queries in this plan use ≤2-entry maps (array-map, insertion-ordered). `heading-inbound-links` is the widest at `{:tid ... :slug ...}` — safe today, but this is fragile. Flagged so that any future query growth (e.g. adding a `:root-uri` param to propagation path lookup) prompts migration to a positional-input variant of `store/query` rather than silently crossing the threshold. Not in scope for this plan — existing code already depends on the same invariant. |
