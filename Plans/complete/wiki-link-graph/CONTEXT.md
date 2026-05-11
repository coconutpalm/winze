# Wiki Link Graph — Context

## Background

The UUID-based `wiki:uuid` permalink system was removed (see
`complete/remove-wiki-uuid/`). Links now use the simpler format:

```
wiki:root-name::path/to/file.md            — file link
wiki:root-name::path/to/file.md#slug       — heading anchor link
```

These are stored in standard markdown link syntax:
```markdown
[Display Text](wiki:root-name::path/to/file.md#slug)
```

When a file or heading is renamed, existing `wiki:` links break. This work
adds the infrastructure to detect renames and propagate them to inbound links.

## Current Datalevin Schema (Relevant Entities)

```
:root/*    — project root (uri, name, plans-dir)
:file/*    — indexed markdown file (id, path, root, hash, metadata)
:chunk/*   — text chunk within a file (id, file ref, text, vec, slug, section)
```

There is currently **no link graph** — no record of which files link to which
other files. Heading slugs are available indirectly via `:chunk/slug` (one chunk
per H2 section), which is the source of truth for heading rename detection.

## What We're Adding

### Outbound wiki-link entities `:link/*`

```clojure
:link/id    {:db/valueType :db.type/string :db/unique :db.unique/identity}
              ;; Composite: "<from-file-id>@@<to-file-id>@@<slug>"
              ;; slug = "" for file-only links
:link/from  {:db/valueType :db.type/ref}    ;; ref to :file/id entity
:link/to-id {:db/valueType :db.type/string} ;; target file-id without anchor
:link/slug  {:db/valueType :db.type/string} ;; "" or heading anchor
```

One entity per distinct (from-file, to-file, slug) triple. Multiple occurrences
of the same link in one file are collapsed — we replace all occurrences anyway.

**Inbound link query** (find all files linking to `target-file-id`). Uses
Datalog's **collection-find** form (`:find [?x ...]`) so the result is a
flat de-duplicated list of file-id strings, not tuples. (A file with
multiple links to the same target — e.g. a file-level link and several
anchor links — would otherwise appear as multiple tuples.)

```clojure
[:find [?from-id ...]
 :in $ ?tid
 :where
 [?l :link/to-id ?tid]
 [?l :link/from ?f]
 [?f :file/id ?from-id]]
```

## File Rename Propagation

When a file is renamed `old-file-id` → `new-file-id`:

1. Query all `:link/*` with `:link/to-id = old-file-id`
2. For each linking file: rewrite `wiki:old-file-id` → `wiki:new-file-id`
   (preserving any `#slug` suffix)
3. Re-index each modified file (re-extracts links + headings)
4. Update `:link/to-id` in the link entities (handled by re-indexing the
   linking file — old link entities are retracted and new ones transacted)

### Two-Pass Reconcile

`reconcile!` must separate **DB mutations** (pass 1) from **propagation**
(pass 2). In pass 1, every rename and re-index commits to the DB; in pass 2,
all accumulated propagations fire against a fully-settled DB. This matters
when two files are renamed in the same reconcile cycle and link to each
other:

- Naïve per-rename propagation: rename A → query inbound-links to `old-fid-A`
  → finds B → query B's path from DB → gets B's **old** path (B's own rename
  hasn't fired yet) → `rewrite-links-in-file!` targets a path that no longer
  exists on disk → `:error`.
- 2-pass propagation: after pass 1, every file's `:file/path` matches its
  current disk location. Pass 2's path resolution is always correct.

Pass 1 captures, for each mutation, records for pass 2:
- Exact rename → `{:old-fid :new-fid}` (from `rename-file!`'s return value)
- Rename-modified → `{:old-fid :new-fid :old-slugs :new-slugs}` (slugs
  snapshotted *before* retract; see Heading Rename Propagation below)
- Modified → `{:file-id :old-slugs :new-slugs}` (for heading-rename detection)

Pass 2 iterates the records:
- For each rename record: `propagate-file-rename! store old-fid new-fid`
  (no `root-uri` — each affected file resolves its own root URI from the DB).
- For each record with slugs: run `match-heading-renames old-slugs new-slugs`
  and fire `propagate-heading-rename! store file-id old-slug new-slug` for
  each `:renamed` pair.

No reconcile-time migration pass runs between pass 1 and pass 2. Backfill
of `:link/*` entities for pre-schema files is a one-time operator-run
`index-root!` — see "One-Time Initial Migration" below.

### Per-linker Error Isolation in `propagate-*!`

Both `propagate-file-rename!` and `propagate-heading-rename!` iterate over
inbound linkers returned by the graph query. Each linker's rewrite +
re-index work must be wrapped in a per-file try/catch so that one bad
linker (DB corruption, missing file on disk, unexpected exception from
`index-file!`) does not abort propagation for subsequent linkers. The
outer `reconcile!` try/catch still exists but operates at whole-propagate
granularity — insufficient for per-linker error reporting.

The per-linker try/catch also handles the **empty cross-root resolution**
case: when `inbound-links` returns a `from-id` whose root metadata query
comes back empty (DB corruption, retracted root mid-cycle), the
propagation function must log WARN and record an error entry rather than
silently skipping. A silent skip would hide a real store problem behind
the appearance of successful propagation.

**Error-record shape consistency.** Every error record — reconcile's own
per-category errors AND the per-linker errors returned by `propagate-*!`
— must carry an `:op` key so callers reading the merged `:errors` list
see homogeneous maps. Reconcile uses `:op :rename`, `:op :modify`, etc.;
propagate functions emit `:op :propagate-rename` (in
`propagate-file-rename!`) or `:op :propagate-heading` (in
`propagate-heading-rename!`).

Canonical per-linker record shape:

```clojure
{:op            :propagate-rename      ;; or :propagate-heading
 :from-id       "<root>::<path>"       ;; always present
 :abs-path      "/abs/path/to/file.md" ;; present once path resolution succeeded;
                                       ;; absent on :error "no-root-metadata"
 :expected-root "<root>"               ;; present ONLY on :error "no-root-metadata",
                                       ;; parsed from the from-id prefix
 :error         "rewrite-failed"       ;; or the .getMessage of an exception
 :old-slug "..." :new-slug "..."}      ;; :propagate-heading only
```

`:abs-path` is included whenever path resolution has already succeeded —
it is the load-bearing value the operator needs for manual repair. The
`no-root-metadata` branch fires *before* path resolution, so it omits
`:abs-path` and substitutes `:expected-root` (parsed out of the
`<root-name>::<path>` prefix of the from-id) so the operator sees the
root registration the query expected to find without having to reparse
the file-id manually. The reconcile merge (`swap! errors into
(:errors summary)`) then yields a single list where every entry answers
"which operation failed, on what, with what message." Without a uniform
`:op` key, an operator reading the summary has to guess from the present
keys.

The watcher handles single-file events one at a time, so it has no
multi-rename race. It calls the `index-file-with-heading-diff!` wrapper
(Step 10) for `:modify` events and invokes `propagate-file-rename!` inline
after `rename-file!` on detected renames.

### Rename-modified: double `index-file!` is accepted

In the `renamed-modified` path, `index-file!` can run twice on the new file:
once as the primary indexing step (pass 1), and again inside
`propagate-file-rename!` (pass 2) if the file contains a self-referential
link to its old path (which the rewrite-and-reindex cycle updates). The
second call produces an idempotent *outcome* (same final chunks, same final
HNSW state) but does perform one extra retract-then-insert cycle against
HNSW — `index-file!` always retracts chunk-vecs before reinserting (required
for HNSW correctness; see the HNSW desync comment in `index-file!`). Only
fires when a self-ref actually exists. Accepted cost — not worth the
complexity of a "skip self if already rewritten" short-circuit.

### `:link/id` Composite Updates on Exact Rename (Deferred)

**Status**: Deferred to **PLAN Phase 9 (Optional)**. Not required for
correctness — included here only as design rationale in case the work is
revisited later.

`rename-file!` updates `:file/id`, `:file/path`, and `:chunk/id` values.
Because `:link/id` embeds the from-file-id as a prefix
(`"<from-fid>@@<to-fid>@@<slug>"`), a pure-consistency argument would say
that a rename should also rewrite each outgoing `:link/id`. In practice:

- Ref-join queries (`inbound-links`, `heading-inbound-links`) traverse
  `:link/from`, not `:link/id`, so they remain correct regardless of the
  composite's staleness.
- Pass-2 propagation re-indexes every linker via `index-file!`, which
  retracts and re-emits `:link/*` entities. Outgoing links from the
  renamed file heal within the same reconcile cycle.
- The observable window for a stale `:link/id` composite is microseconds
  inside a single transaction batch.

The Phase 9 update therefore exists only to protect hypothetical future
code paths that read or scan `:link/id` prefixes directly (e.g. a
diagnostic REPL tool or a prefix-bucketed cache). Implement only when
such a caller is added.

**`str/replace` semantics in the self-reference case** (design note for
Phase 9). `str/replace` is global, not prefix-only. In a self-referencing
link (a file's outbound link points at its own heading) the composite is
`<old-fid>@@<old-fid>@@<slug>` and the replacement fires **twice** —
rewriting both the `from-fid` prefix and the `to-id` middle segment. The
outcome is correct because pass-2 `propagate-file-rename!` rewrites the
file on disk, re-indexes it, and the subsequent `index-file!` re-emits
link entities with `:link/id = <new-fid>@@<new-fid>@@<slug>` and
`:link/to-id = <new-fid>` — which upserts cleanly over the pre-rewritten
row. The `@@` separator is **assumed absent** from file-ids: root-names
come from operator-controlled configuration, and typical relative paths do
not contain `@@`. POSIX technically permits `@` in filenames, so a
hand-crafted path like `docs/a@@b.md` would produce an ambiguous composite
where the `str/replace` could match in the wrong position.

**Substring-prefix corruption risk** (Phase 9 only). Global `str/replace`
also corrupts the `to-id` segment whenever `old-fid` appears as a strict
substring of a cross-linked `to-id`. Example: `old-fid = "r::a.md"` and
the file has a link whose `to-id = "r::a.md.bak"` (a different real file
whose name happens to extend the old file-id). The composite is
`r::a.md@@r::a.md.bak@@slug`, and `(str/replace old-fid new-fid)` fires
three times — rewriting the `from-fid` prefix (correct), the `a.md`
inside `a.md.bak` (wrong — corrupts the `:link/id` of a link pointing at
an unrelated file), and the `a.md` inside any slug that contains it.
In practice file-ids end with `.md` and it is uncommon for one relative
path to extend another by trailing characters, but the case exists.
Phase 9 is optional and deferred precisely so that callers relying on
`:link/id` prefix scans have to weigh this cost — for the default
ref-join queries (`inbound-links`, `heading-inbound-links`) the
composite's staleness is irrelevant. If Phase 9 is implemented:

1. Switch the separator to an unambiguous unit like `\u001f` (ASCII Unit
   Separator — prohibited in paths and root-names) BEFORE enabling the
   prefix rewrite, OR
2. Replace the `str/replace` call with a precise segment rewrite that
   splits on `@@`, rewrites only segment 0, and rejoins, OR
3. Reject Phase 9 as unimplementable for any workspace where file-ids
   can nest.

The RCF tests for Phase 9 Step 6a must exercise **both** the
self-reference case and a co-located-prefix case
(`to-id = <old-fid>.something`) — the latter is the regression test for
this bug, and passing it is a prerequisite for Phase 9 shipping.

## Heading Rename Propagation

When a file's content changes, some headings may have been renamed. We detect
this via embedding similarity (same algorithm as `match-fuzzy-renames`):

1. **Before** `index-file!` retracts chunks: snapshot `{slug → chunk-vec}` for
   the file being re-indexed.
2. **After** `index-file!` completes: snapshot `{slug → chunk-vec}` for new state.
3. Apply greedy cosine-similarity matching (threshold 0.6) to old vs. new slugs.
4. Classify: `:renamed` (old-slug → new-slug), `:added`, `:removed`, `:unchanged`.
5. For each renamed pair: query inbound links with `to-id = this-file` and
   `slug = old-slug`. Rewrite those files.

The chunk vectors for the pre-retract snapshot must be queried **before**
`retract-chunk-vecs!` is called inside `index-file!`. Two contexts snapshot
the pre-retract state:

- **Watcher modify events** — `index-file-with-heading-diff!` wraps
  `index-file!` and snapshots before delegating. Single-file, no race.
- **Reconcile `:modified` and `:renamed-modified`** — reconcile captures the
  snapshot inline (pass 1) and defers propagation to pass 2. Using an inline
  snapshot (instead of the wrapper) is what enables the rename-modified path
  to preserve heading-rename detection: the old slugs must be captured
  *before* `retract-file-by-id!(old-fid)` discards the old chunks, and then
  the new slugs are captured after `index-file!` creates them under the new
  file-id. Without this, heading renames hidden inside a rename+modify are
  silently invisible.

The order of pass-2 operations matters when both file and heading renames
apply to the same target: **file-rename propagation runs first, then
heading-rename propagation**. Pass 1 (file rename) rewrites linking files'
destinations from `wiki:old-fid#slug` → `wiki:new-fid#slug` and re-indexes
them, so their `:link/to-id` points at `new-fid` with `:link/slug = old-slug`.
Pass 2 (heading rename) then queries `heading-inbound-links(new-fid, old-slug)`
and rewrites those links to `wiki:new-fid#new-slug`.

## Cross-Root Path Resolution in Propagation

`inbound-links` and `heading-inbound-links` return `from-id` strings from *any*
root in the store. A file in root A may link to a file in root B via
`wiki:rootB::path.md`; when that target is renamed, the from-file belongs to
root A.

Propagation functions must resolve `from-id` → absolute path using the
file's *own* root, not the root of the renamed target. For each `from-id`,
query both path and root metadata:

```clojure
[:find ?path ?ruri ?rdir
 :in $ ?fid
 :where
 [?f :file/id ?fid]
 [?f :file/path ?path]
 [?f :file/root ?r]
 [?r :root/uri ?ruri]
 [?r :root/plans-dir ?rdir]]
```

The abs-path is `(io/file (str/replace ruri #"^file://" "") rdir path)`.
Pass the resolved `ruri` to any subsequent `index-file!` calls on that file.
Because every affected from-file resolves its own root URI from the DB, the
`propagate-*!` functions take no propagating-root parameter at all — there
is no "propagating root" that is correct for all affected files. In a
cross-root case, the linking file is in a different root from the renamed
target — its own root's watcher is the one that would fire, and its own
root's `plans-dir` is the one required for absolute-path resolution. The
content-hash guard (Step 11) handles propagation-induced reentrancy for
whichever watcher sees the event.

## Retracting Files and Their Link Entities

`retract-file-by-id!` (called for deleted and rename-modified files) must also
retract `:link/*` entities whose `:link/from` ref points to the file being
deleted. Without this, those link entities become orphans with a dangling ref to
a non-existent file entity. Orphan links do not appear in `inbound-links` query
results (the join on `?f :file/id ?from-id` fails), but they accumulate as
store waste. More critically, if the same path is re-created later, `index-file!`
uses `link/from → file-entity` to find and retract old link entities — but the
file entity was fully retracted, so the orphan link entities would never be
cleaned up.

The fix is the same pattern as chunk retraction — query all `:link/*` eids
where `:link/from` points to the file entity, then call `store/retract!`. No
HNSW concern: `:link/*` has no `:db.type/vec` attributes.

**Query-then-retract ordering invariant.** All three cascade-retraction
sites (`retract-file-by-id!`, `index-root!`, `remove-root!`) follow the
same shape: query **all** affected eid sets (chunks, links, files, and in
`remove-root!` also roots) **up front in a single `let`-binding**, then
run retractions in order chunks → links → files (→ root). The
link-eids query joins through `[?f :file/id ?fid] [?l :link/from ?f]`
(or through `:file/root` in the bulk cases), so it MUST run while the
file entity still exists — placing it in a separate `let`-block that
runs after the file retract would return empty, silently leaving the
orphan link entities that this invariant exists to prevent. A reader
auditing any one of the three sites should see the same layout in the
others.

## File-ID Formula — Single Source of Truth

The `root-name::rel-path` file-id formula is computed in one place:
`index/compute-file-id`. Every caller — `index-file!`, `rename-file!`,
`retract-file!`, `watcher/handle-delete!`, and the new `watcher/stored-hash`
helper — delegates to it. Historically the formula was duplicated inline in
each call site; a subtle change to path normalization would have had to be
repeated four times to stay consistent. The helper eliminates that risk.

### Assumption: Root names are unique across registered roots

Cross-root propagation resolves a link's target via the embedded root-name in
the file-id (`<root-name>::<rel-path>`). If two registered roots shared a
`:root/name`, their file-ids would collide and propagation could rewrite
links in the wrong root. The scheme has always depended on this implicitly,
but the blast radius grew with this work — propagation now writes to files
across roots on the basis of the embedded root-name.

`register-root!` must therefore reject both directions of a name/URI
collision:

1. A new registration whose `:root/name` already belongs to a **different**
   `:root/uri` — would make file-ids ambiguous across roots.
2. A re-registration of an existing `:root/uri` under a **different**
   `:root/name` — would silently rename a root in place and invalidate
   every `old-name::*` file-id (all previously-indexed chunks, all stored
   `:link/to-id` values referencing this root). **Root renames are
   unsupported**; operators who need to rename a root must remove it
   (cascading delete of files/chunks/links) and re-register under the new
   name, then re-index from disk.

Re-registering the same `(uri, name)` pair remains idempotent (existing
upsert behavior via `:root/uri` identity). Step 1a covers the enforcement.

`remove-root!` (in `core.clj`) performs a similar bulk retraction when an
entire root is unregistered. It currently retracts chunks → files → root.
After this schema lands it must also retract `:link/*` entities — same
orphan-prevention argument as `retract-file-by-id!` and `index-root!`.
The link-eids query joins through `:file/root`, so it must run while file
entities still exist; capture all four eid sets (chunks, links, files,
roots) up front in the `let` binding, then retract in order chunks →
links → files → root. No HNSW concern — `:link/*` has no `:db.type/vec`
attributes.

`index-root!` also performs a bulk retraction of all files for a root without
going through `retract-file-by-id!`. The `:link/*` eids must be **queried**
while the file entities still exist (the query joins through `:file/root`),
and **retracted before** the file entities are retracted. Compute all three
eid sets (chunks, links, files) up front in the `let` binding, then run the
retractions in the order: chunk-vecs → chunks → links → files.

```clojure
;; Retract all :link/* entities for this root (index-root! only)
(let [link-eids (store/query store
                              '[:find [?l ...]
                                :in $ ?ruri
                                :where
                                [?r :root/uri ?ruri]
                                [?f :file/root ?r]
                                [?l :link/from ?f]]
                              {:ruri root-uri})]
  (when (seq link-eids)
    (store/retract! store (vec link-eids))))
```

## AST-Based Markdown Rewriting

### Why AST, not regex

A naive `str/replace` on `](wiki:old-id)` could match:
- Markdown in fenced code blocks documenting the link format
- Inline code spans like `` `[text](wiki:old-id)` ``

The commonmark-java `Parser` already handles these distinctions. A `Link` node
only appears in real links — code fences and inline code produce `Code` and
`FencedCodeBlock` nodes. The AST approach leverages this guarantee.

### Source span strategy

The parser configured with `IncludeSourceSpans/BLOCKS_AND_INLINES` annotates
every inline node (including `Link`) with `SourceSpan` objects carrying
`lineIndex` and `columnIndex`.

For a `Link` node with a `wiki:` destination:

1. Get the `SourceSpan` from the `Link` node.
2. Convert (lineIndex, columnIndex, inputLength) to a character offset in the
   raw markdown text (precompute line-start offsets once).
3. The raw link syntax is `[display](destination)`. Within the span, find `](`
   to locate the start of the destination field, then **verify by exact
   substring equality** that the parser-returned `.getDestination` value
   matches the text at that offset. Never scan for a terminating `)` —
   that fails on links with titles (`[x](wiki:foo "bar")`) or display
   text containing escaped parens.
4. Replace just that exact-length destination substring.

Replacements are applied **descending by character offset** so earlier
replacements don't shift later positions.

### Fallback safety rule

If a source span is unavailable (e.g. the `Link` node is synthetic or the
parser config is wrong):

> **Skip the file and log a warning.** Do not fall back to regex.

Better to leave a broken link than to corrupt content with an ambiguous
replacement.

### Atomic write

All file modifications use the pattern:
```
write to <file>.tmp → fsync (FileChannel.force true) → Files/move ATOMIC_MOVE
```

The explicit fsync on the `.tmp` handle before the rename is load-bearing:
`Files/move` with `ATOMIC_MOVE` is atomic at the directory-entry level, but
without an fsync of the tmp's contents a crash between write and rename can
publish an empty or truncated tmp. Step 3e calls `FileChannel.force(true)`
on the tmp before invoking `Files/move`. This is belt-and-suspenders — on
most filesystems ATOMIC_MOVE implies a durable state transition, but the
explicit fsync makes the guarantee portable.

### Extraction vs. rewrite input asymmetry

`extract-outbound-wiki-links` (called by `index-file!`) receives `body`
(post-frontmatter), so YAML content is not scanned. `rewrite-links-in-file!`
slurps the full file (frontmatter + body) and runs the AST parser over it.

In practice YAML frontmatter never contains `[text](wiki:...)` inline-link
syntax, so the asymmetry is benign. If it ever did, the rewrite pass could
modify a "wiki link" that was never extracted as a `:link/*` entity — harmless
(the file still parses) but confusing. Not worth unifying the two inputs.

## Similarity Algorithm Reference

`match-fuzzy-renames` in `index.clj` (lines 132–180):
- Cosine similarity on centroid embeddings
- Greedy best-match (sort for determinism)
- Threshold: `rename-similarity-threshold` = 0.6

Heading-level matching uses the same algorithm, but on per-heading chunk
vectors instead of per-file centroids. A heading's "vector" is the
`:chunk/vec` for the chunk whose `:chunk/slug` matches the heading slug.

## Reconcile vs. Watcher Lifecycle

`reconcile!` and the filesystem watcher are both drivers of indexing, but
they must not run concurrently against the same root. The standard
startup sequence is:

1. Open store + register roots.
2. Run `reconcile!` once per root (pass 1 + migration + pass 2).
3. **Then** start watchers.

Datalevin serializes transactions, so a concurrent watcher event
during reconcile would not corrupt the store — but it can produce
ordering anomalies (e.g. a `:modify` event firing mid-pass-1 observes
partial state for a rename cycle that hasn't completed pass 2 yet). No
new synchronization is introduced here; the plan assumes the existing
startup discipline is preserved by callers of `reconcile!`.

## Watcher Reentrancy During Propagation

When propagation rewrites files B, C, D on disk, the filesystem watcher picks
up those writes as `:modify` events. Without a guard, the watcher fires
`index-file!` (or `index-file-with-heading-diff!`) on those files a second
time — redundant at best, semantically incorrect at worst if the diff wrapper
runs and compares stale chunk vectors against the newly-written content.

### Content-hash guard in the watcher

Add a Datalevin hash lookup to `handle-create-or-modify!` **before** calling
`index-file!`. If the disk content hash already matches the stored hash for
this path's file-id, the file is already indexed — skip silently.

```
watcher fires for file B (written by propagation)
    ↓
read B from disk → compute SHA-256
    ↓
query Datalevin: stored hash for B's file-id?
    ↓
disk hash == stored hash → already indexed → skip
disk hash ≠ stored hash → genuinely new content → index-file!
```

By the time the 500ms debounce fires, propagation's `index-file!` call has
almost certainly completed (propagation write + DB commit is fast). The hash
guard finds a matching hash and returns immediately — no HNSW retract-reinsert,
no heading diff.

**The guard still calls `notify-listeners!` for the event.** The reindex
is skipped; the listener fan-out is not. UI listeners, downstream
integrations, and any other consumer that tracks `:modify` / `:create`
events continue to see one event per disk change — whether that change
came from a user edit (guard falls through, index runs, listeners fire)
or from propagation (guard short-circuits, listeners fire on the same
event). Without this, a file rewritten by propagation would show stale
content in any listener-driven view until the next independent event.

**The guard keys by the event's own file-id (via `compute-file-id`), not by
content hash.** For a `:create` event following a rename, the new path has
no DB entity yet, so `stored-hash` returns `nil` and the guard never
short-circuits — rename detection (`match-rename` against `pending-deletes`)
runs unaffected. The guard only fires for path-stable re-entries (same file
at same path, same content).

**`:create` event with matching hash — listener-type accuracy.** Beholder
occasionally emits a spurious `:create` for an already-indexed file on
macOS FSEvents (e.g. after atomic-move rewrites where the directory entry
for an existing path is replaced rather than modified). In that case
`stored-hash` returns the current hash, it matches disk, and the guard
skip-branch runs — which forwards the original event-type `:create` to
listeners. Downstream consumers that distinguish `:create` from `:modify`
will see a nominal `:create` for a file whose entity already existed.
Accepted: the file's DB state is correct (same entity, same hash, same
content), and no listener in tree today keys off first-sighting semantics
strongly enough for this to produce a wrong outcome. If a future listener
needs strict first-sighting, the guard's skip-branch can downgrade
`:create` → `:modify` when `stored-hash` is non-nil; flagging here so that
change is a one-line adjustment rather than a rediscovery.

**Edge case**: watcher beats propagation's `index-file!` (debounce fires before
propagation commits). Hash guard sees old DB hash → calls `index-file!`. Then
propagation's `index-file!` runs on the same content → same chunks, redundant
but harmless. This race window is typically <100ms and is a no-op in outcome.

### No secondary suppress mechanism

An earlier draft of this design added a per-path suppress set in `watcher.clj`
that propagation functions could call via a `:suppress-fn` argument to drop
events before the debounce was even scheduled. It was dropped. Every race
the content-hash guard leaves open ends in an **idempotent outcome** (same
chunks, same HNSW state) — the suppress mechanism only saved one Datalevin
hash query per dropped event. The costs (a new public API across the
watcher/index boundary, scheduler entanglement, a cleanup race when the same
path is suppressed twice in rapid succession) outweighed that savings.

If profiling later shows the hash-guard lookup is a hot path, revisit.

### Constraint: no circular dependency

`watcher.clj` already depends on `index.clj`. Propagation code lives in
`index.clj`. Therefore `index.clj` **cannot** import `watcher.clj`. The
content-hash guard keeps propagation free of any watcher dependency — it
runs entirely inside `watcher.clj` and inspects DB state only.

## Self-Referential Links

A file may contain wiki links that point to its own headings:

```markdown
[See the design section](wiki:root::same-file.md#design)
```

These appear in the `:link/*` graph as a (from-file, to-file, slug) triple
where `from-file = to-file`. They are handled **correctly by the existing
design**: `heading-inbound-links` returns the file itself, and
`propagate-heading-rename!` rewrites and re-indexes it. No special case needed.

**No cascade is possible.** `propagate-heading-rename!` calls `index-file!`
(not `index-file-with-heading-diff!`) on files it rewrites. So rewriting a
file due to its own heading rename does not trigger another round of heading
comparison — only the initial wrapper call does that comparison.

**Bare fragment links** (`[text](#slug)`, no `wiki:` prefix) are standard
markdown and are NOT tracked in `:link/*` entities. When a heading is renamed,
bare fragment links within the *same file* are not updated. This is a known
limitation — these links are invisible to the indexer. Bare fragments across
files are already broken links in any other renderer, so updating them is out
of scope.

## `:link/to-id` Stores Bare File-IDs (No `wiki:` Prefix)

Wiki link destinations have the form `wiki:root::path.md` or
`wiki:root::path.md#slug`. The `:link/to-id` field stores only the bare
**file-id** (the portion after `wiki:`), e.g. `"root::path.md"`. The `wiki:`
prefix is stripped during extraction (Step 4b).

This is required because `inbound-links` and `heading-inbound-links` take a
plain file-id as input — the same format produced by `reconcile!` and
`index-file!`. Storing the `wiki:` prefix would cause all inbound-link queries
to return empty results and propagation would silently never fire.

Correspondingly, `propagate-file-rename!` constructs
`old-dest = (str "wiki:" old-fid)` (re-adding the prefix) for use as a
prefix-match target in `rewrite-links-in-text`. The two sides are symmetric.

## Heading Rename Detection — H2-Only Limitation

`snapshot-chunk-slugs` queries `:chunk/slug` and `:chunk/vec`. Chunks are
produced by `split-sections`, which splits on `## ` (H2) boundaries only. H3
and deeper headings are not chunk boundaries and have no associated chunk
vector.

Consequence: `match-heading-renames` (Step 8) can only detect renames of H2
headings. If an H3 heading is renamed, inbound links pointing to its slug
(`wiki:root::file.md#h3-slug`) will silently break — no propagation fires.

Links *to* H3 headings are stored correctly in `:link/*` entities (Step 4b
extracts all anchors regardless of heading level). Only the rename-detection
and propagation path is limited to H2.

This is an accepted limitation of the similarity-based approach. A future
improvement could store per-heading vectors separately, but that is out of
scope for this work item.

## Prefix Match Must Respect Wiki-Link Boundaries

`rewrite-links-in-text` with `:exact? false` performs prefix matching on the
destination so that `wiki:root::old.md` rewrites both the bare file link and
`wiki:root::old.md#slug`. A naive `starts-with?` check would also match
pathological cases like `wiki:root::old.md.backup` (one file-id is a prefix
of another) and corrupt the second file's destination.

The match must be prefix **plus a valid delimiter** at the boundary — the
next character after the prefix must be `#` or end-of-destination. Because
matching runs against `.getDestination` (the parser's cleaned destination
string, with surrounding markdown syntax stripped), `)`, `"`, and `'` cannot
appear in this position. Implementations may accept the wider set
(`#`, `)`, `"`, `'`, end-of-destination) as belt-and-suspenders against
hypothetical future extraction changes, but `#` / end-of-destination is the
strictly-sufficient set. Anything else means the prefix ended mid-identifier
and must be skipped.

## Reference-Style Markdown Links — Unsupported

commonmark-java represents reference-style links as two distinct AST nodes:

```markdown
[display text][ref]     ← inline Link node whose destination is resolved

[ref]: wiki:root::file.md  ← LinkReferenceDefinition node (block-level)
```

The `Link` node's `SourceSpan` covers the inline `[display text][ref]`, **not**
the `[ref]: wiki:...` definition below. When source-span exact-match
verification runs against the text at the span location, it finds `][ref]`,
not `wiki:root::file.md` — verification fails.

**Extraction and storage behavior — consistent with rewriting.**
Both `extract-wiki-links` (the rewriter's extractor) and
`extract-outbound-wiki-links` (the indexer's extractor) filter out link
occurrences that fail source-span verification. A helper
`verified-wiki-link?` encapsulates the check; both extractors use it.

Concretely:

- The indexer never creates a `:link/*` entity for a reference-style link.
  The `:link/*` graph stays accurate and free of orphans that could never be
  rewritten.
- The rewriter's `rewrite-destination` also skips any link whose span does
  not verify (defense in depth — the extractor already filtered these, but
  any synthetic or otherwise span-less `Link` node surfaces through the
  rewriter too, and the verification is cheap).

**User-visible consequence.** Reference-style `wiki:` links are silently
ignored end-to-end: they are not in the link graph, they are not rewritten
on rename, and they become broken on the first rename of their target —
with no warning beyond a DEBUG-level log line at extraction time. Treat as
an **accepted limitation**: inline `[display](wiki:...)` is the only
supported form. A future enhancement could walk `LinkReferenceDefinition`
nodes (which carry their own `SourceSpan` pointing at the definition line),
but this is out of scope.

An RCF test in `link_rewriter` asserts the per-occurrence safe-skip behavior
in a mixed-content file (reference-style occurrence filtered out of
extraction; inline occurrence in the same file rewritten normally).

## Single-Transact Invariant for `index-file!`

The **final transact** that writes `[file-entity] + chunk-entities + link-entities`
must be a single `store/transact!` call. Splitting it into two (e.g.
"transact file+chunks, then transact links") would allow a crash mid-way to
leave a file whose chunks exist but whose link graph is stale (or vice versa),
producing silently-broken inbound-link queries until the next modification.
Datalevin commits a vector of tx-data atomically, so concatenating
`[file-entity] + chunk-entities + link-entities` keeps the invariant.

## Atomicity Gap: `rename-file!` + `propagate-file-rename!`

The watcher's rename path (Step 12) calls `rename-file!` then
`propagate-file-rename!` as separate operations. No transaction spans both.
A JVM crash between them leaves the DB consistent (the renamed file's
`:file/id` is updated) but the on-disk markdown of linking files unchanged —
their inbound links still point at the old path.

**Why reconcile won't repair this**: linking files' content hashes are
unchanged, so they classify as `:unchanged`. No propagation fires on the
next startup. The links remain broken until the linking file is edited.

**Mitigation (deferred, specified in PLAN.md as Step 9b)**:
`link-integrity-report` — a startup `not-join` sweep over `:link/to-id`
values with no corresponding `:file/id`, logged as a WARN after
reconcile. Operator fixes via REPL. Making propagation synchronous
inside `rename-file!` would couple `index.clj` to the rewriter and is
worse. Accepted — the window is tens of milliseconds and mirrors the
existing crash-between-chunk-retract-and-transact gap. The sweep is
specified but deferred because the gap has not been observed; implement
when it becomes operator-visible.

**Reconcile has the crash-atomicity gap too** (but not the ordering gap).
The 2-pass design eliminates the *ordering* bug (see "Two-Pass Reconcile")
because pass 2 always runs against a fully-settled DB *within one reconcile
cycle*. But a JVM death between pass 1 commit and pass 2 propagation leaves
the same stale-inbound-links state as a watcher crash — arguably worse,
because the T2−T1 window scales with the size of the change-set (pass 2
starts only after all of pass 1 has run). And the next reconcile cannot
repair it: the linking files' content hashes are unchanged, so they classify
as `:unchanged` and no propagation fires. The recommended startup
`:link/to-id` → `:file/id` join sweep (PLAN.md Step 9b,
`link-integrity-report`) is the single mitigation that covers both drivers.

**Pre-transact retractions are deliberately separate** (one `retract-chunk-vecs!`
for HNSW hygiene, one `store/retract!` on chunk eids, one `store/retract!` on
link eids). These are idempotent — on a crash between retract and final
transact, a subsequent `index-file!` re-runs the same sequence with no harm.
The only residual gap: if the crash happens *after* link-entities are
retracted but *before* the final transact commits, the file's `:file/*`
entity remains in the store with zero associated link entities until the
next content edit triggers another `index-file!`. This mirrors the existing
chunk-retract gap and is accepted — reconcile on the next startup will pick
up any `:modified` file; for any file whose content hash did not change, a
manual `index-root!` or a targeted `index-file!` call from the REPL re-arms
it. In practice the crash window is microseconds.

## Retracting Old Link Entities Before Re-Transacting

Because `:link/id` uses the composite `"<from-fid>@@<to-fid>@@<slug>"` as an
identity attribute, re-transacting `:link/*` entities on re-index upserts
matching entities in place. But removed links (present in the previous indexing
of the file, absent in the current one) persist as orphans unless retracted.

`index-file!` queries existing link entity ids via `:link/from → :file/id = file-id`
and retracts them, then transacts fresh ones. This mirrors the
retract-chunks-then-insert pattern already in place for chunks.

## One-Time Initial Migration

The schema change adds `:link/*` attributes to Datalevin. Datalevin schema
is additive, so existing stores upgrade in place — but existing `:file/*`
entities have **no associated `:link/*` entities** until their file content
is re-processed through `index-file!`. Subsequent `reconcile!` cycles
classify those files as `:unchanged` (content hashes match on-disk) and
will not re-index them.

**Procedure**: after installing the new JAR, run `index-root!` once per
registered root. `index-root!` bulk-retracts all `:chunk/*`, `:link/*`
(after Step 4d), and `:file/*` entities for the root and re-indexes
every file, which emits fresh `:link/*` entities via the Step 4c changes.

**Why not an automatic reconcile-time migration?** A previous plan draft
added a `:file/links-indexed` boolean flag and a NOT-clause query in
`reconcile!` to detect and re-embed unmigrated files automatically. That
approach was rejected:

- For ~200 documents and one operator, a documented one-liner is cheaper
  than shipping a disposable migration pass, a schema attribute, and a
  follow-up cleanup phase to delete them.
- `index-root!` is the idiomatic reset-and-rebuild operation and already
  runs every file through `index-file!`; no new code path needed.
- Rejecting the marker avoids a per-index write of a semantically-empty
  flag that would outlive its purpose.

**Operator discipline is load-bearing.** If a watcher-detected file rename
fires **before** `index-root!` completes on the first post-deploy
startup, the link graph is partially populated and pass-2 propagation
will silently skip linkers whose `:link/*` entities do not yet exist.
Release notes must name the sequencing requirement: *install JAR →
`index-root!` each registered root → resume editing.*

On subsequent sessions the link graph is fully populated and propagation
runs without the operator needing to think about this.

## Heading-Slug Snapshot Hygiene

`snapshot-chunk-slugs` (Step 7) returns a `{slug → chunk-vec}` map used by
`match-heading-renames`. The map **must filter out chunks with nil or blank
slugs** — kept as defensive hygiene, not because the current code produces
such chunks.

**Current behavior of `split-sections`**: `build-slug-text-pairs` in
`chunk.clj` assigns `(str "section-" idx)` to any chunk whose heading is
nil (i.e. the leading pre-H2 chunk). So the pre-H2 chunk stores slug
`"section-0"` — neither nil nor blank. The filter therefore never fires
against today's indexer output. It remains in place so that:

- A future schema revision that admits chunks with no `:chunk/slug`
  attribute stays safe (Datalog simply omits rows missing `?slug`).
- A future regression that introduces empty-string slugs (bad data from a
  direct transaction, a `slugify` edge case) cannot cause cross-chunk
  collision in `match-heading-renames`.

**Side effect of `section-0` being stored**: a real pre-H2 intro chunk
*does* participate in heading-rename matching. If a user later adds a
`## Intro` heading that takes over the same content, the greedy matcher
may classify `section-0 → intro` as a rename and rewrite any inbound
`wiki:file.md#section-0` link to `#intro`. This is correct —
semantic content is preserved under a new slug — but worth noting so an
operator encountering such a rewrite understands it is intentional.

The filter lives at the query boundary in `snapshot-chunk-slugs`, not
inside `match-heading-renames`, so the similarity algorithm stays
oblivious to schema quirks. One invariant, one enforcement site.

## Multi-line Link Nodes — Safe-Skipped

commonmark-java emits one `SourceSpan` per source line for inline nodes
that wrap across lines. For a `Link` node whose display text contains a
newline (e.g. `[display\nwrapped](wiki:root::file.md)`), the Link has
multiple source spans: the first covers line 1 up to the newline, the
second covers line 2's continuation and the `](destination)` segment.

The plan's `verified-wiki-link?` uses only the Link's **first**
`SourceSpan`. Consequently, for multi-line display text the span
substring does not contain `](`, the exact-match verification fails,
and the link is safely skipped (DEBUG-logged, not rewritten).

This is an accepted limitation: multi-line display text in wiki links is
rare in practice, and the safe-skip policy aligns with the broader
"broken link beats corrupt content" invariant. A future enhancement could
join all Link spans into a composite range, but until a concrete need
arises, the simple first-span approach is preferred for its clarity.

An RCF test in `link_rewriter` pins the safe-skip behavior so that a
future parser upgrade that *does* produce multi-line support does not
silently change the contract without a test update.

## Safety Invariants

1. **Never corrupt content.** If any doubt about a replacement's safety, skip
   the file and log. A broken link is cheaper than corrupted content.
2. **Never modify the file being actively re-indexed.** Propagation runs after
   the triggering file is fully indexed and committed.
3. **Propagation is idempotent.** Re-running on an already-propagated file
   produces no changes (the old link text no longer exists).
4. **Circular loops cannot occur.** A rewritten file triggers `index-file!`
   (not `index-file-with-heading-diff!`), so heading comparison only happens
   once per event — on the originally-modified file.
5. **Only rewrite link destinations, never display text.** Slugs are derived
   from heading display text (visible to the reader), never from link URLs.
   Rewriting a link destination cannot change any heading's slug.

## UI Navigation Invariant — Focus Must Follow the Active Tab

When a wiki-link click opens or switches to a file tab, focus must land in
that tab's content widget (the `Browser` in view mode, or the `StyledText`
in edit mode). This is **not** cosmetic on macOS: SWT's
`Display.addFilter(SWT/KeyDown)` is not truly global on Cocoa. If focus is
left on a non-key-receiving widget (the top-right `ToolBar`, the hidden
Browser of the previously-active tab, or `nil`), Cocoa silently swallows
key events at the OS level — Cmd+E (toggle-mode) and Esc (escape hierarchy)
stop working until the user clicks into a focusable widget.

SWT's `CTabFolder.setSelection` is deliberately silent: programmatic
selection changes do **not** fire `SWT/Selection` and do **not**
auto-forward focus. Every programmatic tab-switch path in
`winze-server/src/llm_memory/ui/main_window.clj` therefore calls
`focus-selected-tab-content!` after `.setSelection`. As of 2026-04-20 that
helper is already called from `open-file-in-tab!` (the wiki: URL dispatch
entry point in `custom-browser`'s `e/changing` handler), so inline
`[text](wiki:root::file.md)` clicks inherit the fix for free.

**Applies to new code in this plan**: the Phase 8 smoke test must verify
that after clicking a wiki link, pressing Cmd+E toggles edit mode on the
newly-opened tab without requiring an intermediate click. If Phase 5/6 or
Phase 9 work introduces any **new** navigation path (e.g., a content-assist
jump, a "go to referencing file" cross-root action, or a rename-preview
fixup dialog that changes the active tab), that path must also call
`focus-selected-tab-content!` after its `.setSelection`.

## Files to Change

Phase 0 (`deps.edn`, `chunk.clj`, `hiccup.clj`) is **already complete** — see
existing code. Do not re-do those steps.

| File | Change |
|------|--------|
| `clj-llm-memory/src/llm_memory/store/datalevin.clj` | Add `:link/*` attributes to schema. No `:file/links-indexed` flag — backfill is one-time operator-run `index-root!`, see Phase 3 |
| `clj-llm-memory/src/llm_memory/core.clj` | Enforce root-name uniqueness in `register-root!` (Step 1a — query-then-transact check, non-atomic under concurrent callers, acceptable for single-operator use); retract `:link/*` entities in `remove-root!` and patch pre-existing HNSW-hygiene gap by calling `retract-chunk-vecs!` before the chunk retract (Step 1b — same orphan-prevention + HNSW-hygiene pattern as `retract-file-by-id!` and `index-root!`) |
| `clj-llm-memory/src/llm_memory/index.clj` | Update `retract-file-by-id!` and `index-root!` to also retract `:link/*` entities; add `extract-outbound-wiki-links` and `compute-file-id` helpers; wire link extraction into `index-file!` (single transact); add `index-file-with-heading-diff!` wrapper; hook pass-2 propagation into `reconcile!`, aggregating `:rewritten` into a `:propagated` count in the summary; **per-linker try/catch inside `propagate-*!`** for granular error reporting; **WARN log on empty cross-root resolution** |
| `clj-llm-memory/src/llm_memory/link_rewriter.clj` | **New** — AST-based rewriter: `verified-wiki-link?` (span exact-match helper), `extract-wiki-links` (returns only verified records), `rewrite-links-in-text` (with prefix-boundary check), `rewrite-links-in-file!` (FileChannel.force + ATOMIC_MOVE, no non-atomic fallback) |
| `clj-llm-memory/src/llm_memory/link_graph.clj` | **New** — Link graph queries: `inbound-links`, `heading-inbound-links` |
| `clj-llm-memory/src/llm_memory/watcher.clj` | Add content-hash guard (`stored-hash` via `idx/compute-file-id`, thread `watch-dir` through handler closure + `handle-delete!`); wire `index-file-with-heading-diff!` for modify events; call `propagate-file-rename!` on rename events using `rename-file!` return value, preserving the existing `notify-listeners!` call; nil-return edge case of `rename-file!` now falls back to `index-file!` + `:create` listener (intentional fix for latent bug) |

### Deferred (Phase 9, optional)

| File | Change |
|------|--------|
| `clj-llm-memory/src/llm_memory/index.clj` | Extend `rename-file!` to rewrite outgoing `:link/id` composites (Step 6a). **Not required for correctness** — ref-join queries traverse `:link/from`, not `:link/id`. Implement only if a future diagnostic needs `:link/id` prefix scans. Blocked on the substring-prefix bug until the `str/replace` is replaced with a precise segment rewrite or the `@@` separator is switched. |
| `clj-llm-memory/src/llm_memory/index.clj` + `winze-server/src/llm_memory/server/main.clj` | Add `link-integrity-report` (Step 9b) — `not-join` query over `:link/to-id` without matching `:file/id`, logged as a WARN after reconcile. Single mitigation for the rename-crash atomicity gap shared by the watcher and reconcile drivers. Implement when that gap becomes operator-visible. |
