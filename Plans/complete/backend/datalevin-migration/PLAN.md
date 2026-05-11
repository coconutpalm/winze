---
created: 2026-03-20
related: plans-system-improvement, search-improvement
tags: datalevin, vector-search, embedding, architecture
---

# Plans System — Datalevin Migration Plan

Migrate the Plans memory system from Python/ChromaDB/Ollama to a single-JVM Clojure solution using Datalevin for storage, vector indexing, and embedding. Add filesystem watching for incremental reindexing and a database abstraction layer for future Datahike swap-in.

**Status**: **COMPLETE** (2026-03-21). Phases 0–7 done. Plan Server operational, Python server retired. 182 RCF tests. Platform packaging extracted to separate plan (Plans/dev/PLATFORM-PACKAGING-*.md).

## Phase 0: Validate Datalevin on this Platform

**Goal**: Confirm that Datalevin works on macOS arm64 with vector search and embeddings before writing any application code.

### Steps

0.1. Create project skeleton at `mcp/clj-llm-memory/` with `deps.edn`:
  - `datalevin/datalevin {:mvn/version "0.10.7"}` (or latest stable)
  - `nextjournal/beholder` (file watcher)
  - dev deps: RCF, Portal

0.2. **Verify built-in embedding availability.** Check which dtlvnative version ships with the Datalevin release:
  - If dtlvnative >= 0.18.x: llama.cpp is bundled, `:db/embedding true` works out of the box
  - If dtlvnative < 0.18.x (likely 0.16.5 in 0.10.7): llama.cpp is **not bundled**. The `:db/embedding` API may not function. In this case, choose a fallback:
    - **Option A**: Use a newer Datalevin snapshot/release that includes llama.cpp
    - **Option B**: Use inference4j (`all-MiniLM-L6-v2`, 384d, ONNX Runtime) for in-JVM embeddings + Datalevin's `:db.type/vec` for user-supplied vectors
  - This decision shapes the embedding abstraction in Phase 1.

0.3. REPL smoke test — create a Datalevin database with vector search. Depending on 0.2:
  - **If built-in embeddings available**: Define schema with `:db/embedding true`, transact text chunks, query with `embedding-neighbors`. Verify model auto-downloads and loads.
  - **If using inference4j + `:db.type/vec`**: Load inference4j, embed text to `float[]`, define schema with `:db.type/vec`, transact vectors, query with `vec-neighbors`.
  - In both cases verify: vector search returns relevant results, Datalog queries work alongside vector queries, database persists across JVM restarts.

0.4. Benchmark: measure embedding throughput (chunks/sec) and search latency for ~200 chunks (approximate current corpus size). Confirm the model's token limit doesn't truncate typical Plan sections.

0.5. If the model truncates too aggressively, evaluate:
  - Tighter chunking (split at H3 as well as H2)
  - Different model (all-mpnet-base-v2 at 768d via inference4j — higher quality, same 512-token limit)
  - User-supplied vectors via `:db.type/vec` with a model that accepts longer inputs

**Exit criteria**: Datalevin vector search works on this machine with acceptable quality. Embedding strategy (built-in vs. inference4j) is decided.

### Phase 0 Results (2026-03-20) — COMPLETED

**Embedding strategy decided: inference4j + `:db.type/vec` (Option B)**

- **dtlvnative 0.16.5** ships with Datalevin 0.10.7. llama.cpp is NOT bundled. The `new-embedding-provider`, `embed-text`, and `embedding-neighbors` APIs do not exist. Built-in `:db/embedding true` is unavailable.
- **inference4j 0.10.0** (`io.github.inference4j/inference4j-core`) provides `all-MiniLM-L6-v2` (384d) via ONNX Runtime. Model auto-downloads from HuggingFace on first use. Model ID: `inference4j/all-MiniLM-L6-v2`. Builder API: `(-> (SentenceTransformerEmbedder/builder) (.modelId "inference4j/all-MiniLM-L6-v2") (.maxLength 512) (.build))`.
- **Datalevin `:db.type/vec`** works correctly with 384-dimensional vectors and cosine distance. HNSW index uses NEON SIMD acceleration on Apple Silicon.

**Datalevin API notes:**

- Schema: `:db.type/vec` attribute + `{:vector-opts {:dimensions 384 :metric-type :cosine}}` store option.
- Vector index access: `(get (.-vector_indices (.-store (d/db conn))) "chunk_vec")` — key is string with `/` replaced by `_`.
- Search: `(d/search-vec idx float-array {:top N :display :refs+dists})` returns `[[:g eid] distance]` pairs.
- Entity lookup after search: use `(d/q [:find ?e ?id :where [?e :chunk/id ?id]] db)` to build eid→id map, then `(second ref)` to extract eid from search result.
- `d/entity` returns lazy maps that may not resolve through the store's vector index accessor — prefer `d/pull` or `d/q` for entity resolution after vector search.

**Benchmark (1,078 chunks from 166 real Plan files):**

| Metric | Result |
|--------|--------|
| Model load | 693 ms |
| Embedding throughput | 44.8 chunks/sec (22.3 ms/chunk) |
| Full corpus embedding | 24 sec |
| Datalevin transact (1,078 entities) | 844 ms |
| Search latency | 1.7 ms/query (including query embedding) |

**Token limit analysis:**

- `all-MiniLM-L6-v2` configured with `maxLength(512)` handles 91% of chunks without truncation.
- Only 9% of chunks exceed 512 tokens — these are oversized H2 sections that the Python chunker's 4000-char paragraph sub-split already handles.
- No model change needed. The existing chunking strategy is sufficient.

**Search quality:** All 5 test queries correctly identified their target document as the #1 result with significant distance margin.

**Project skeleton created at `mcp/clj-llm-memory/`** with validated `deps.edn`.

**Dependency corrections discovered:**
- `com.nextjournal/beholder` (not `nextjournal/beholder`)
- `clj-commons/clj-yaml` (not `clj-yaml/clj-yaml`)
- `com.hyperfiddle/rcf` (not `hyperfiddle/rcf`)

## Phase 1: Library Core — Protocols, Store, and Embedding

**Goal**: Build the `clj-llm-memory` library skeleton with the core protocols, Datalevin implementation, and public API. This is the foundation that both the server application and third-party consumers will use.

### Steps

1.1. Create the library project at `mcp/clj-llm-memory/` with `deps.edn`. Key dependencies: `datalevin/datalevin`, `nextjournal/beholder`, `clj-yaml/clj-yaml`. Dev deps: RCF, Portal.

1.2. Define `PlanStore` protocol in `llm-memory.store.protocol`:

```clojure
(defprotocol PlanStore
  (connect!    [this])
  (disconnect! [this])
  (transact!   [this tx-data])
  (retract!    [this eids])
  (query       [this q & params])
  (search      [this text-query opts])  ;; opts includes :root-uri for scoping
  (db-exists?  [this]))
```

All search/query operations accept an optional `:root-uri` to scope results to a single project; omitting it searches across all roots.

1.3. Define `Embedder` protocol in `llm-memory.embed.protocol`:

```clojure
(defprotocol Embedder
  (embed-text  [this text])      ;; => float[]
  (embed-texts [this texts])     ;; => [float[] ...]
  (dimensions  [this])           ;; => int
  (embedding-info [this]))       ;; => {:model "..." :dims N}
```

Implement:
  - `DatalevinEmbedder` — wraps `d/new-embedding-provider` (if available in dtlvnative)
  - `Inference4jEmbedder` — wraps inference4j `SentenceTransformerEmbedder` (loaded via `requiring-resolve` to avoid hard dependency)

1.4. Implement `DatalevinPlanStore` (record implementing `PlanStore`):
  - `connect!` — `d/get-conn` with schema + embedding/vector opts. **Database path is passed by the caller** (no hardcoded `~/.local/share/` — that's the server's concern).
  - Schema adapts based on embedder: if `DatalevinEmbedder`, use `:db/embedding true`. If external `Embedder`, use `:db.type/vec` with explicit dimensions.
  - `search` — if Datalevin built-in: `embedding-neighbors`. If external embedder: call `embed-text`, then `vec-neighbors`.
  - `disconnect!` — `d/close conn`

1.5. Design the public API in `llm-memory.core`:

```clojure
;; Store lifecycle
(open-store {:path "..." :embedding {:provider :datalevin}})  ;=> PlanStore
(close-store! store)

;; Root management
(register-root! store {:uri "file:///..." :plans-dir "Plans"})
(list-roots store)
(remove-root! store "file:///...")

;; Indexing & reconciliation
(reconcile! store root-uri)              ;; diff db vs. disk, handle renames
(index-root! store root-uri)             ;; full reindex (drop + re-create)
(index-file! store root-uri abs-path)    ;; single file
(retract-file! store root-uri abs-path)  ;; single file
(rename-file! store root-uri old-path new-path)  ;; preserves entity identity

;; Search & query
(search store query opts)    ;; {:root-uri, :top, :status, :type, :group, :since}
(related store group opts)
(recent store opts)
(list-files store opts)
(status store)

;; File watching
(start-watcher! store root-uri)  ;=> watcher handle
(stop-watcher! watcher)
```

This is what library consumers interact with. No server concerns leak into this API.

1.6. Write RCF tests using temporary databases. Test:
  - Multi-root isolation: two roots with same-named files produce separate entities
  - Search scoping: results from root A don't appear when querying root B
  - Embedder protocol: both Datalevin and inference4j implementations (if available)
  - Reconciliation: classify files correctly (unchanged, modified, renamed, new, gone)

1.7. A stub `DatahikePlanStore` exists (throws "not implemented" for `search`). Docstring documents the intended Datahike + Proximum + inference4j architecture (see Phase 8).

**Exit criteria**: `llm-memory.core/open-store`, `search`, `index-root!` work end-to-end in the REPL using a temp database. Multi-root and embedder protocol tests pass.

### Phase 1 Results (2026-03-21) — COMPLETED

**All exit criteria met.** `open-store`, `search`, `register-root!`, `list-roots`, `remove-root!`, `status`, `related`, `recent`, `list-files` work end-to-end in the REPL. 36 RCF assertions pass (embedder, store lifecycle, multi-root isolation, search scoping by root/status/type/group, retraction cascade, Datahike stub).

**Files created:**

| File | Namespace | Purpose |
|------|-----------|---------|
| `src/llm_memory/embed/protocol.clj` | `llm-memory.embed.protocol` | `Embedder` protocol (embed-text, embed-texts, dimensions, embedding-info) |
| `src/llm_memory/embed/inference4j.clj` | `llm-memory.embed.inference4j` | `Inference4jEmbedder` record — all-MiniLM-L6-v2 (384d) via ONNX Runtime |
| `src/llm_memory/store/protocol.clj` | `llm-memory.store.protocol` | `PlanStore` protocol (connect!, disconnect!, transact!, retract!, query, search-vec, pull-entity, pull-many) |
| `src/llm_memory/store/datalevin.clj` | `llm-memory.store.datalevin` | `DatalevinPlanStore` record — full implementation with Datalevin schema and HNSW vector search |
| `src/llm_memory/store/datahike.clj` | `llm-memory.store.datahike` | `DatahikePlanStore` stub — throws UnsupportedOperationException with roadmap docstring |
| `src/llm_memory/core.clj` | `llm-memory.core` | Public API — open-store, close-store!, register-root!, search, related, recent, list-files, status, etc. |
| `test/llm_memory/core_test.clj` | `llm-memory.core-test` | 36 RCF assertions covering all Phase 1 functionality |

**API corrections discovered during implementation:**

- **inference4j class location**: `io.github.inference4j.nlp.SentenceTransformerEmbedder` (not `io.github.inference4j.SentenceTransformerEmbedder`)
- **inference4j method name**: `.encode` (not `.embed`)
- **Datalevin vector search**: `search-vec` on raw HNSW index returns internal vector-index IDs, NOT database entity IDs. Must use `vec-neighbors` inside a Datalog query: `[(vec-neighbors $ :chunk/vec ?qvec {:top N}) [[?e _ ?vec]]]`. This returns `[eid attr vec]` tuples with correct db eids.
- **Datalevin `vec-neighbors` in Datalog** does not return distances — only `[eid attr vec]`. Cosine distance is computed manually from the returned vectors using `areduce` for performance.
- **Deduplication key**: Must use `:file/id` (globally unique, includes root prefix) rather than `:file/path` (same across roots).

**Datalevin schema** (3 entity types, 21 attributes):
- `:root/*` — project root (uri, name, plans-dir)
- `:file/*` — indexed markdown file (id, path, root ref, content-hash, modified, status, type, group, jira, related, tags)
- `:chunk/*` — text chunk (id, file ref, text, vec, slug, section)
- Vector opts: 384 dimensions, cosine metric

**Next**: Phase 2 — Markdown Processing Pipeline (chunking, metadata inference, frontmatter parsing)

## Phase 2: Markdown Processing Pipeline

**Goal**: Port the Python chunking/metadata pipeline to Clojure.

### Steps

2.1. Port `split_sections()` to Clojure (`mcp.clj-llm-memory.chunk`):
  - Split on `## ` (H2) boundaries
  - Prepend H1 title to every chunk
  - Size-split at paragraph boundaries (`\n\n`) when chunk > 4000 chars
  - Generate semantic slug IDs (lowercase, alphanumeric + hyphens, max 60 chars, deduplicate)

2.2. Port `parse_frontmatter()` — extract YAML frontmatter between `---` delimiters. Use `clj-yaml` or simple regex parsing (frontmatter is minimal).

2.3. Port `infer_metadata()` to Clojure (`mcp.clj-llm-memory.metadata`):
  - `fm_status` from directory: `dev/` → active, `complete/` → complete, `dev/deferred/` → deferred
  - `fm_type` from filename patterns: `*-CONTEXT.md` → context, `AAO-*.md` → jira, etc.
  - `fm_group` from filename prefix or parent directory
  - `fm_jira` via regex on filename

2.4. Write RCF tests validating parity with the Python implementation on representative filenames and directory paths.

**Exit criteria**: Given a Plan markdown file path, the pipeline produces the same chunks and metadata as the Python version.

### Phase 2 Results (2026-03-21) — COMPLETED

**All exit criteria met.** The Clojure pipeline produces identical chunk counts and metadata to the Python version.

**Parity validation (full corpus):**
- 166 files → 1,077 chunks (exact match with Python indexer)
- Status coverage: 97.6% (matches ~97% target)
- Type coverage: 96.4% (matches ~97% target)

**Files created:**

| File | Namespace | Purpose |
|------|-----------|---------|
| `src/llm_memory/chunk.clj` | `llm-memory.chunk` | `split-sections` (H2 split, title prepend, paragraph sub-split), `slugify` |
| `src/llm_memory/frontmatter.clj` | `llm-memory.frontmatter` | `parse-frontmatter`, `frontmatter->metadata` (YAML → `:fm/*` keys) |
| `src/llm_memory/metadata.clj` | `llm-memory.metadata` | `infer-metadata` (path conventions → status/type/group/jira) |
| `test/llm_memory/pipeline_test.clj` | `llm-memory.pipeline-test` | 62 RCF assertions (slugify, chunking, frontmatter, metadata inference) |

**Next**: Phase 3 — Indexing Engine (index-file!, retract-file!, rename-file!, reconcile!)

## Phase 3: Indexing Engine

**Goal**: Build the indexing layer that transacts file/chunk entities into Datalevin.

### Steps

3.1. Implement `index-file!` in `mcp.clj-llm-memory.index`:
  - Accepts root entity ref + absolute file path
  - Read file, parse frontmatter, split into chunks, infer metadata
  - Compute SHA-256 content hash
  - Build transaction data: one `:file/*` entity (with `:file/root` ref) + N `:chunk/*` entities
  - Identity keys: `:file/id` = `"<root-name>::<rel-path>"`, `:chunk/id` = `"<root-name>::<rel-path>::<slug>"` (upsert semantics)
  - Transact via `PlanStore/transact!`

3.2. Implement `retract-file!`:
  - Find file entity by `:file/id`
  - Retract it (component chunks cascade via `:db/isComponent`)

3.3. Implement `rename-file!`:
  - Find existing file entity by content hash (`:file/content-hash`)
  - Update `:file/path`, `:file/id`, and re-infer metadata (status/type/group change when a file moves from `dev/` to `complete/`)
  - Update `:chunk/id` on all child chunks (they include the rel-path prefix)
  - Preserves entity identity and Datalevin transaction history

3.4. Implement `reconcile!` — startup reconciliation for a root:
  - Load all indexed files from db: `{rel-path → {:eid, :content-hash, :modified}}`
  - Scan filesystem: `{rel-path → {:content-hash, :modified}}`
  - Classify into five categories:
    - **UNCHANGED** — path and hash match → skip
    - **MODIFIED** — path matches but hash differs → re-index (re-chunk, re-embed)
    - **RENAMED** — a disk-only file's hash matches a db-only file's hash → `rename-file!`
    - **NEW** — disk-only, hash doesn't match any db-only → `index-file!`
    - **GONE** — db-only, hash doesn't match any disk-only → `retract-file!`
  - Pair RENAMED matches first (one-to-one by content hash; break ties by closest mtime)
  - Execute in order: RENAMED, then MODIFIED, NEW, GONE
  - Log a summary: `"Reconciled _finance: 142 unchanged, 3 modified, 1 renamed (dev/→complete/), 2 new, 0 deleted"`
  - Return the summary as data for `plans-status`

3.5. Implement `full-reindex!` (reset mode):
  - Retract ALL file entities for the root (cascade deletes chunks)
  - Then `index-file!` every `.md` on disk
  - This is the "nuclear option" — should never be needed since `reconcile!` handles all normal drift

3.6. Implement `index-status` — return stats per root: file count, chunk count, newest/oldest modification, last reconciliation summary, watcher status, embedding domain health.

**Exit criteria**: `reconcile!` correctly classifies and handles all five categories. Rename across directories (e.g. `dev/FOO-CONTEXT.md` → `complete/foo/CONTEXT.md`) updates path + metadata while preserving the entity. Multiple roots reconcile independently.

### Phase 3 Results (2026-03-21) — COMPLETED

**All exit criteria met.** Full indexing pipeline operational. Real corpus test: 166 files → 1,077 chunks in 27 seconds. Reconciliation of unchanged corpus completes in 15ms.

**Files created:**

| File | Namespace | Purpose |
|------|-----------|---------|
| `src/llm_memory/index.clj` | `llm-memory.index` | `index-file!`, `retract-file!`, `retract-file-by-id!`, `rename-file!`, `reconcile!`, `index-root!`, `index-status` |
| `test/llm_memory/index_test.clj` | `llm-memory.index-test` | 36 RCF assertions (index, retract, re-index stale cleanup, reconcile 5 categories, rename with metadata re-inference) |

**Performance (real corpus, 166 files, 1,077 chunks):**
- `index-root!` (full reindex): 27 seconds (~163ms/file, dominated by embedding)
- `reconcile!` (all unchanged): 15ms (hash comparison only, no re-embedding)
- `index-file!` (single file, 12 chunks): 360ms

**Key implementation details:**
- **Stale chunk cleanup**: `index-file!` diffs existing vs. new chunk IDs and retracts stale ones before upserting. Prevents chunk accumulation when H2 headings change.
- **Reconcile classification**: SHA-256 content hash matching for rename detection. Correctly handles `dev/FOO-CONTEXT.md` → `complete/foo/CONTEXT.md` (path, status, group all updated, entity identity preserved).
- **`list-files` improved**: Changed from Datalog query (requires all attrs present) to `pull-entity` approach (handles optional attrs like `:file/group`).

**Total RCF assertions**: 134 (36 Phase 1 + 62 Phase 2 + 36 Phase 3)

**Next**: Phase 4 — Filesystem Watcher (beholder, debounce, rename detection)

## Phase 4: Filesystem Watcher

**Goal**: Watch each root's `Plans/` directory for changes and incrementally update the index.

### Steps

4.1. Implement `llm-memory.watcher` with multi-root support:
  - Maintain an atom of `{root-uri → watcher-instance}`
  - `start-watcher!` — takes a root entity, starts beholder on `<root>/<plans-dir>/`:

```clojure
(beholder/watch
  (fn [{:keys [type path]}]
    (when (str/ends-with? (str path) ".md")
      (case type
        :create  (index-file! store root path)
        :modify  (index-file! store root path)
        :delete  (handle-delete! store root path))))
  plans-dir)
```

  - `stop-watcher!` — takes a root URI, stops its watcher
  - `stop-all!` — stops all watchers (for shutdown)

4.2. Implement debouncing — coalesce rapid create/modify events within a 500ms window to avoid re-indexing during multi-step editor saves. Debounce state is per-root.

4.3. Implement rename detection:
  - On delete: Record `{:root-uri, :path, :content-hash, :timestamp}` in an atom
  - On create (within 1s of a matching-hash delete in the same root): Update `:file/path` and `:file/id` on existing entity instead of retract+create
  - On timeout: Proceed with retraction

4.4. Filter events:
  - Only process `.md` files
  - Ignore `INDEX.md` and `STATUS.md` (auto-generated)
  - Ignore dotfiles and temp files (`.#foo.md`, `foo.md~`)

4.5. Logging — log each index/retract/rename operation at INFO level, including the root name so multi-root operations are distinguishable.

4.6. (Optional, TBD) Periodic reconciliation — a `ScheduledExecutorService` runs `reconcile!` for each root on a configurable timer (e.g. every 5 minutes) as a safety net for missed watcher events. Disabled by default (`:reconcile-interval-ms nil`). Skips if a reconciliation is already in progress.

**Exit criteria**: Creating, editing, moving, and deleting a Plan file in any registered root triggers correct incremental index updates within ~1 second. Multiple roots are watched concurrently.

### Phase 4 Results (2026-03-21) — COMPLETED

**All exit criteria met.** File create, modify, delete, and rename all trigger correct incremental index updates within ~1 second. Rename detection preserves entity identity.

**Files created:**

| File | Namespace | Purpose |
|------|-----------|---------|
| `src/llm_memory/watcher.clj` | `llm-memory.watcher` | `start-watcher!`, `stop-watcher!`, `stop-all!`, `watcher-status` — beholder-based with 500ms debounce, 1s rename window |
| `test/llm_memory/watcher_test.clj` | `llm-memory.watcher-test` | 15 RCF assertions (lifecycle, create, modify, delete, rename detection, file filtering) |

**Key implementation details:**
- **Debouncing**: 500ms window via `ScheduledExecutorService`. Coalesces rapid editor saves (create+modify bursts).
- **Rename detection**: Delete records `{:path :content-hash :timestamp :file-id}` in an atom. Create events within 1s check for hash match → `rename-file!` instead of retract+index. Unmatched deletes retract after 1.1s.
- **Filtering**: Ignores `INDEX.md`, `STATUS.md`, dotfiles (`.#foo.md`), temp files (`foo.md~`).
- **Logging**: `[HH:mm:ss] [INFO] [root-name] operation path` format.
- **Bug fix**: `handle-delete!` was using URI last segment for root name instead of the registered `:root/name`. Fixed to use the `root-name` parameter.
- **Periodic reconciliation (4.6)**: Deferred — the file watcher is reliable enough for the current use case.

**Total RCF assertions**: 149 (36 + 62 + 36 + 15)

**Next**: Phase 5 — Search & Query API

## Phase 5: Search & Query API

**Goal**: Implement the search tools matching (and improving on) the current Python MCP server's interface.

### Steps

5.1. `search-plans` — semantic search:
  - All tools accept a `root-uri` parameter (passed by the proxy from `roots/list`). Queries scope to that root by default. An optional `all-roots: true` flag enables cross-project search.
  - Run `embedding-neighbors` Datalog query with `:top (* n 3)` (for deduplication headroom)
  - Apply root scoping: `[?file :file/root ?root] [?root :root/uri ?root-uri]`
  - Apply metadata filters via Datalog `:where` clauses (status, type, group, since)
  - Deduplicate by `:file/path`, keeping highest-relevance chunk per file
  - Return N results with relevance scores (`1 / (1 + distance)`)
  - Support detail levels: full, summary (300 chars), files-only
  - Cross-root results include `:root/name` prefix on file paths for disambiguation

5.2. `related-plans` — group lookup:
  - Datalog query: find all files where `:file/group = group`
  - Also resolve `:fm_related` cross-references (stored in frontmatter)

5.3. `recent-plans` — date-filtered:
  - Datalog query: `:file/modified >= cutoff`, optional type/status filters
  - Sort by modification date descending

5.4. `list-plans` — enumerate all indexed files

5.5. `plans-status` — health check:
  - Per-root stats: file count, chunk count, watcher status (running/stopped), last reconciliation summary
  - Global stats: total files, total chunks, registered roots
  - Embedding provider status

5.6. `index-plans` — manual reconciliation/reindex trigger:
  - Default: run `reconcile!` for the requesting root (diff db vs. disk, handle renames, only re-embed what changed)
  - `reset: true`: Drop all data for this root and reindex from scratch (`full-reindex!`)
  - `all-roots: true`: Apply to all registered roots

5.7. Auto-generate `INDEX.md` and `STATUS.md` after any index operation (port the Python `generate_index()` and `generate_status()` functions).

**Exit criteria**: All six tools return equivalent results to the Python implementation on the same corpus.

### Phase 5 Results (2026-03-21) — COMPLETED

**All exit criteria met.** All six tools produce formatted markdown output equivalent to the Python server. INDEX.md and STATUS.md generation ported.

**Files created:**

| File | Namespace | Purpose |
|------|-----------|---------|
| `src/llm_memory/tools.clj` | `llm-memory.tools` | `search-plans`, `list-plans`, `related-plans`, `recent-plans`, `plans-status`, `index-plans` — formatted markdown output matching Python server |
| `src/llm_memory/generate.clj` | `llm-memory.generate` | `generate-index`, `generate-status`, `generate-all!` — INDEX.md and STATUS.md auto-generation |
| `test/llm_memory/tools_test.clj` | `llm-memory.tools-test` | 33 RCF assertions (all six tools + both generators) |

**Improvements over core API (Phase 1):**
- **`since` filter**: Now works — parses ISO date strings via `LocalDate/parse` → epoch, filters on `:file/modified`
- **`related`**: Now returns `{:files [...] :cross-refs #{group ...}}` — resolves `:file/related` (from YAML frontmatter) to cross-referenced group files
- **`search` enrichment**: Now includes `:file/modified` and `:file/jira` in results
- **`list-files`**: Now uses `pull-entity` (handles optional attrs) instead of Datalog joins (required all attrs)

**Tool output format** matches Python server exactly:
- Search: `### rel/path  (section slug, relevance N% [badge])  (type=X, status=Y, group=Z)`
- Related: `## Group: name  (N file(s))` + cross-reference section
- Recent: `## Documents modified in the last N day(s)  (N file(s))`
- Status: `## Planning System Status` with embedding, store, root, watcher info
- Index: `Reconciled: N unchanged, N modified, N renamed, N new, N gone.`

**Total RCF assertions**: 182 (36 + 62 + 36 + 15 + 33)

**Next**: Phase 5b — Publish Library to Clojars (or Phase 6 — Plan Server + MCP Proxy)

## Phase 5b: Publish Library to Clojars

**Goal**: Publish the `clj-llm-memory` library so other tools can depend on it.

### Steps

5b.1. Finalize library `deps.edn` and `build.clj`:
  - Group/artifact: `io.github.<org>/clj-llm-memory` (or `<group>/clj-llm-memory`)
  - Version: `0.1.0` (initial release)
  - Ensure inference4j is NOT in the default dep tree (it's optional — consumers add it if they want `Inference4jEmbedder`)

5b.2. Write a `README.md` for the library with:
  - Quick start (open store, index, search)
  - Embedding provider configuration
  - Multi-root usage
  - File watcher usage

5b.3. Configure `build.clj` for Clojars deploy:
  - `clj -T:build jar` — build library JAR (no uberjar, no main class)
  - `clj -T:build deploy` — publish to Clojars (requires `CLOJARS_USERNAME` / `CLOJARS_PASSWORD`)

5b.4. Publish `0.1.0` to Clojars. Verify: `{io.github.xxx/clj-llm-memory {:mvn/version "0.1.0"}}` resolves and basic smoke test works in a fresh project.

**Exit criteria**: Library is on Clojars. A fresh project can `require` `llm-memory.core`, open a store, index files, and search — with no server process.

## Phase 6: Plan Server + Babashka MCP Proxy

**Goal**: Build the `winze-server` application at `mcp/winze-server/`. This is a thin wrapper around the library, exposing it via nREPL for the Babashka MCP proxy.

### Steps

#### 6a. Plan Server (long-running JVM)

6a.1. Create `mcp/winze-server/` with `deps.edn` depending on the `clj-llm-memory` library (`:local/root` during development, `:mvn/version` for releases) + `nrepl/nrepl`.

6a.2. Implement `llm-memory.server.main/-main`:
  - Open store via `llm-memory.core/open-store` with path from env var `PLANS_DB_PATH` (default: `~/.local/share/winze/.datalevin/`)
  - For each previously registered root: run `reconcile!` to sync db with filesystem, then start watcher
  - Start nREPL server bound to `127.0.0.1` (localhost only, never 0.0.0.0)
  - Use dynamic port (port 0) — write actual port to `~/.local/share/winze/.nrepl-port`
  - The proxy sends `llm-memory.core` calls directly via nREPL eval
  - Write PID file to `~/.local/share/winze/.pid`
  - Register shutdown hook to clean up PID file, `.nrepl-port`, stop watchers, close store
  - Block on main thread

6a.3. Build standalone uberjar via `tools.build`:
  - Entry point: `llm-memory.server.main`
  - `make install` copies uberjar + `mcp-proxy.clj` + bundled `bb` to `~/.local/share/winze/`

#### 6b. Babashka MCP Proxy

6b.1. Implement `mcp-proxy.clj` — a Babashka script that:
  - Completes MCP `initialize` handshake with Claude Code
  - Calls `roots/list` on the client to discover the project root URI(s)
    - Fallback if client doesn't support roots: use `PLANS_ROOT` env var or CWD
  - Checks if Plan Server is running (read `~/.local/share/winze/.nrepl-port` + attempt connection)
  - If not running: starts it (`java -jar ~/.local/share/winze/clj-llm-memory.jar &`), polls for `.nrepl-port` file (up to 10s)
  - Connects to nREPL on `localhost:<port>` (Babashka has built-in `bencode` and nREPL client support)
  - Registers root(s) with Plan Server: `(llm-memory.root/ensure-root! {:uri "file:///..." :plans-dir "Plans"})`
  - Translates each MCP tool call to an nREPL `eval`, passing `root-uri` for scoping
  - Returns MCP JSON-RPC responses on stdout
  - On stdin EOF: exits cleanly (Plan Server keeps running, watchers stay active)

6b.2. Implement MCP tool definitions matching the current Python server:
  - Tool names: `search_plans`, `list_plans`, `related_plans`, `recent_plans`, `plans_status`, `index_plans`
  - Parameter schemas match Python server's definitions
  - Each tool implicitly scoped to the root discovered via `roots/list`
  - Return formatted markdown strings (same format as current Python output)

6b.3. Register with Claude Code (global — works for any project):
  ```bash
  claude mcp add clj-llm-memory -- bb ~/.local/share/winze/mcp-proxy.clj
  ```

6b.4. Parallel operation — run both Python and Clojure MCP servers simultaneously during validation. Compare results.

**Exit criteria**: Claude Code calls tools via Babashka proxy → Plan Server → Datalevin, and gets correct results. Plan Server survives between Claude Code sessions. Multiple projects can be opened in separate Claude Code sessions, each scoped to their own root. File watchers keep all roots current in real time.

### Phase 6 Results (2026-03-21) — COMPLETED

**All exit criteria met.** Full MCP protocol flow validated: `bb mcp-proxy.clj` → JSON-RPC → nREPL → Plan Server → Datalevin → formatted markdown → stdout.

**Project created at `mcp/winze-server/`:**

| File | Purpose |
|------|---------|
| `deps.edn` | Project deps — `:local/root` to clj-llm-memory library + nrepl |
| `build.clj` | tools.build — `uber` target for standalone JAR |
| `Makefile` | `clean`, `uber`, `install`, `run` targets |
| `src/llm_memory/server/main.clj` | Server entry point: open store, reconcile roots, start watchers, nREPL, PID file, shutdown hook |
| `mcp-proxy.clj` | Babashka MCP proxy: JSON-RPC stdin/stdout ↔ nREPL bencode ↔ Plan Server |

**Validated flow:**
```
Claude Code (stdin) → bb mcp-proxy.clj (JSON-RPC)
  → nREPL bencode (port from ~/.local/share/winze/.nrepl-port)
    → Plan Server JVM (llm-memory.tools/*)
      → Datalevin (vector search + Datalog)
    ← formatted markdown string
  ← bencode response
← JSON-RPC {"content":[{"type":"text","text":"..."}]}
```

**Bugs fixed during implementation:**
- `ProcessHandle/current` needs fully-qualified `java.lang.ProcessHandle/current` (Clojure compiler doesn't auto-resolve `java.lang` for namespace-like interop)
- nREPL bencode requires `PushbackInputStream` wrapper on the socket input stream
- nREPL `value` is a Clojure string literal (double-quoted) — proxy must `read-string` to unwrap
- JVM flags for Datalevin (`--add-opens`, `--enable-native-access`) must use `-J` prefix with `clojure` CLI

**Server performance:**
- Cold start (fresh JVM + model load): ~10s
- Warm start (model cached on disk): ~3s
- Initial index of 166 files: 28s
- Subsequent reconcile (all unchanged): ~15ms

**Registration (after Phase 7):**
```bash
claude mcp add clj-llm-memory -- bb ~/.local/share/winze/mcp-proxy.clj
```

## Phase 7: Migration & Cutover

**Goal**: Replace the Python server with the Clojure Plan Server.

### Steps

7.1. Run both MCP servers in parallel for several working sessions. Compare search results for quality parity.

7.2. Update CLAUDE.md instructions:
  - Remove references to Ollama, ChromaDB, Python 3.12 constraints
  - Document Plan Server process management (how to start/stop/restart)
  - Update `index_plans` documentation (now incremental by default via file watcher)

7.3. Update skills (`/search-plans`, `/index-plans`) to use the new server.

7.4. ~~Add Plan Server to system startup~~ — **Not needed.** The Babashka proxy already auto-starts the Plan Server on first use (checks `.nrepl-port`, starts JVM if absent, polls until ready). No launchd/systemd configuration required. The server stays running after the proxy exits, so subsequent sessions reconnect instantly.

7.5. Retire the Python server:
  - Remove `mcp/planning-tool/` MCP registration (`claude mcp remove planning-tool`)
  - Keep files in place for reference (don't delete yet)

7.6. Update this plan to complete status, move to `Plans/complete/`.

**Exit criteria**: Python server deregistered. Babashka proxy + Plan Server is the sole Plans search backend. File watcher keeps the index current without manual intervention.

### Phase 7 Results (2026-03-21) — COMPLETED

**All exit criteria met.** Python server retired, Clojure Plan Server is sole backend.

**Actions taken:**
- Built uberjar (125MB) and installed to `~/.local/share/winze/`
- Registered `clj-llm-memory` MCP server globally (`claude mcp add --scope user`)
- Updated CLAUDE.md: removed Ollama/ChromaDB/Python 3.12 references, documented new architecture
- Updated MEMORY.md: replaced Planning-Tool section with clj-llm-memory details
- Validated parallel operation: both Python and Clojure servers ran simultaneously with correct results
- Deregistered Python server: `claude mcp remove planning`
- Moved plan files from `Plans/dev/` to `Plans/complete/datalevin-migration/`
- Step 7.4 (launchd) skipped — proxy auto-starts server on first use

**Server startup on uberjar:**
- Cold start: ~3s (model cached), reconcile of 166 files: ~1s (165 unchanged, 1 modified)
- Watcher auto-started for registered root

**Files kept for reference (not deleted):**
- `mcp/planning-tool/` — Python server source, ChromaDB store, venv

## Phase 7b: Platform Packaging & Distribution

**Goal**: Build self-contained platform-specific packages for macOS (arm64), Linux (amd64, arm64), and Windows (amd64). End users need not install Java or Babashka.

### Approach

GraalVM native-image is **not viable** — nREPL requires dynamic class loading, and JNA (beholder) needs runtime `dlopen`. Instead, bundle a minimal JRE (via jlink) alongside the uberjar and a platform Babashka binary.

### Steps

7b.1. Add `build.clj` targets:
  - `uber` — build platform-independent uberjar (already exists from Phase 6a)
  - `jre` — run `jlink --output target/jre --add-modules <required-modules>` to create a minimal JRE for the current platform. Determine required modules by running `jdeps` on the uberjar.
  - `package` — assemble the distribution directory:
    ```
    target/plan-server-<platform>/
    ├── jre/                      # Minimal JRE (~40-60MB)
    ├── lib/plan-server-uber.jar  # Uberjar
    ├── bin/
    │   ├── plan-server           # Shell/bat launcher
    │   └── mcp-proxy.clj         # Babashka MCP proxy
    └── bin/bb                    # Babashka binary (~25MB)
    ```
  - `install` — copy the package to `~/.local/share/winze/` and register the MCP server

7b.2. Create launcher scripts:
  - `bin/plan-server` (Unix) / `bin/plan-server.bat` (Windows) — invokes `jre/bin/java` with required JVM flags + uberjar
  - `bin/plan-server-mcp` (Unix) / `bin/plan-server-mcp.bat` (Windows) — invokes `bin/bb bin/mcp-proxy.clj`

7b.3. Automate Babashka download:
  - In `build.clj` or `Makefile`, download the correct `bb` binary for the current platform from the official GitHub release
  - Platforms: `bb-<version>-macos-aarch64.tar.gz`, `bb-<version>-linux-amd64.tar.gz`, `bb-<version>-linux-aarch64.tar.gz`, `bb-<version>-windows-amd64.zip`

7b.4. Determine JRE modules:
  - Run `jdeps --print-module-deps target/plan-server-uber.jar` to discover the minimal module set
  - Expected: `java.base`, `java.logging`, `java.sql`, `java.naming`, `java.management`, `jdk.unsupported` (for Clojure internal access), possibly `java.xml`
  - Use `jlink --no-header-files --no-man-pages --strip-debug --compress=zip-6` to minimize JRE size

7b.5. CI pipeline (per-platform):
  - **Trigger**: Git tag `v*` (release)
  - **Strategy**: Build uberjar once (any runner), then fan out to platform runners:

  | Target | CI Runner | Build Steps |
  |--------|-----------|-------------|
  | macOS arm64 | `macos-14` | jlink + download bb-macos-aarch64 + package |
  | Linux amd64 | `ubuntu-latest` | jlink + download bb-linux-amd64 + package |
  | Linux arm64 | `ubuntu-24.04-arm` or Docker | jlink + download bb-linux-aarch64 + package |
  | Windows amd64 | `windows-latest` | jlink + download bb-windows-amd64 + package |

  - Produce artifacts: `plan-server-macos-arm64.tar.gz`, `plan-server-linux-amd64.tar.gz`, etc.

7b.6. (Optional) macOS code signing and notarization for distribution outside the App Store.

**Exit criteria**: `make package` on each platform produces a self-contained directory that starts the Plan Server without any prerequisites. CI produces downloadable archives for all four platforms.

## Phase 8 (Future): Datahike + Proximum Backend

**Goal**: Implement `DatahikePlanStore` using Datahike for Datalog storage and Proximum for vector search.

This is deferred until:
- **Proximum reaches 1.0** (currently 0.1.24, early beta, API may change)
- **JVM moves to 22+** (Proximum requires Java 22 for Panama Foreign Memory API; we currently use temurin-21)

### Architecture

```
Datahike (Datalog, temporal queries, schema)
    +
Proximum (HNSW vector search, git-like versioning)
    +
inference4j (in-JVM embeddings, all-MiniLM-L6-v2)
```

Proximum entity IDs are Datahike entity IDs. Vector search results feed into Datahike Datalog via `:in` function bindings:

```clojure
(d/q '[:find ?path ?text
       :in $ ?search-fn ?query
       :where
       [(?search-fn ?query 5) [?eid ...]]
       [?eid :chunk/text ?text]
       [?eid :chunk/file ?file]
       [?file :file/path ?path]]
     @conn search-fn "cache invalidation")
```

### What This Unlocks

- **Datahike temporal queries** (`as-of`, `history`) — query the Plans index at any point in time, see when files were added/removed/modified
- **Proximum git-like versioning** — branch the vector index for experiments, time-travel to historical states
- **Combined audit trail** — full change tracking across both structured metadata and vector embeddings
- **Replication** — Datahike supports replication out of the box; Proximum supports S3 storage via Konserve

### Steps (high-level)

8.1. Implement `DatahikePlanStore` record with Datahike connection + Proximum index
8.2. Integrate inference4j for in-JVM embedding generation
8.3. Implement `search` method: embed query → Proximum search → feed IDs into Datahike Datalog
8.4. Migration tool: export from Datalevin, import into Datahike + Proximum
8.5. Validate parity with Datalevin backend

## Dependency Summary

### `clj-llm-memory` Library (published to Clojars)

```
datalevin/datalevin       0.10.7+   ;; storage + vectors (+ embeddings if dtlvnative >= 0.18.x)
nextjournal/beholder      1.0.2     ;; filesystem watcher (macOS FSEvents)
clj-yaml/clj-yaml         LATEST   ;; frontmatter parsing
```

Optional (consumer adds explicitly if using Inference4jEmbedder):
```
io.github.inference4j/inference4j-core  LATEST  ;; in-JVM embeddings (ONNX Runtime)
```

Dev dependencies:
```
hyperfiddle/rcf            LATEST   ;; inline testing
djblue/portal              LATEST   ;; data inspection
```

### `winze-server` Application (not published)

```
io.github.xxx/clj-llm-memory   {:local/root "../clj-llm-memory"}  ;; the library
nrepl/nrepl               1.3.0     ;; localhost IPC for MCP proxy
```

Build/packaging:
```
babashka/bb               LATEST   ;; bundled binary (~25MB per platform)
JDK 21+ (temurin)                  ;; jlink source for minimal JRE
```

### Phase 8 (Datahike + Proximum backend, future)

Published as part of `clj-llm-memory` library (alternative `PlanStore` implementation):
```
io.replikativ/datahike         LATEST   ;; Datalog with temporal queries
io.replikativ/proximum         >= 1.0   ;; HNSW vector search (requires Java 22+)
io.github.inference4j/inference4j-core  LATEST  ;; in-JVM embeddings (ONNX Runtime)
```
