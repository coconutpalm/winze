---
created: 2026-03-20
related: plans-system-improvement, search-improvement
tags: datalevin, vector-search, embedding, architecture
---

# Plans System — Datalevin Migration Context

## Motivation

The current Plans memory system is a Python MCP server backed by ChromaDB + Ollama (`nomic-embed-text`). It works, but has structural limitations:

1. **External process dependency** — Requires Ollama running for embeddings, Python 3.12 for ChromaDB (Pydantic V1 incompatibility with 3.13+), and a separate MCP server process.
2. **No change tracking** — ChromaDB has no history. A full `reset=True` reindex is required for deletions/renames. Stale chunks accumulate otherwise.
3. **No file watching** — Indexing is triggered manually (or by AI discipline via CLAUDE.md instructions). Files can drift out of sync.
4. **No rename/move tracking** — When a file is moved from `dev/` to `complete/`, the old chunks persist until a full reset. The system cannot correlate a file's identity across renames.

Datalevin 0.10.7 resolves all of these by providing Datalog storage, HNSW vector indexing, and built-in text embedding in a single JVM library — eliminating all external dependencies.

## Current System Architecture

```
Plans/*.md files
    ↓  (rglob *.md)
index.py — split at H2 headings, max 4000 chars, semantic chunk IDs
    ↓  (upsert)
ChromaDB PersistentClient (.vector-db/)
    ↓  (nomic-embed-text via Ollama)
server.py — FastMCP, stdio transport
    ↓  (tool calls)
Claude Code
```

### Key Design Decisions to Preserve

- **Semantic chunk IDs** — `rel/path.md::heading-slug` — stable across edits within a section
- **Two-stage chunking** — H2 headings first, then paragraph splits at 4000 chars
- **H1 title prepended** to every chunk for search relevance
- **Metadata inference** from directory paths and filenames (~95% coverage): `fm_status`, `fm_type`, `fm_group`, `fm_jira`
- **Optional YAML frontmatter** overrides inferred metadata
- **Deduplication** — one result per file by default
- **Detail levels** — full, summary, files-only

### What Changes

| Aspect | Current (Python/ChromaDB) | Target (Clojure/Datalevin) |
|---|---|---|
| Embedding | Ollama `nomic-embed-text` (external) | Datalevin built-in llama.cpp (`multilingual-e5-small`, 384d) |
| Vector store | ChromaDB HNSW | Datalevin usearch HNSW (LMDB-backed) |
| Datalog | None | Datalevin (Datomic-compatible) |
| History | None | Datalevin tracks all transactions with timestamps |
| File watching | None (manual `index_plans`) | JVM `WatchService` / beholder / hawk |
| Rename tracking | None | Entity identity via content hash + Datalog history |
| MCP transport | stdio (Python subprocess) | stdio (Clojure subprocess) or in-process |
| Runtime | Python 3.12 + venv | JVM (same as arty) |

## Datalevin Vector Capabilities (v0.10.7)

### Automatic Embedding (`:db/embedding`) — NOT AVAILABLE in 0.10.7

**Phase 0 finding (2026-03-20):** dtlvnative 0.16.5 ships with Datalevin 0.10.7. llama.cpp is NOT bundled. The `new-embedding-provider`, `embed-text`, and `embedding-neighbors` APIs do not exist in this version. **The `:db/embedding true` schema flag will not work.** Use inference4j (`all-MiniLM-L6-v2`, 384d, ONNX Runtime) for in-JVM embeddings + Datalevin's `:db.type/vec` for user-supplied vectors instead.

Datalevin 0.10.1+ introduced the `:db/embedding` API. The built-in llama.cpp embedding provider (`multilingual-e5-small-Q8_0.gguf`, 384 dimensions, 512-token max) ships in dtlvnative 0.18.x. If the built-in provider is not yet available, use inference4j or another in-JVM embedding library and the `:db.type/vec` user-supplied vector path instead.

When available, string datoms marked with `:db/embedding true` are embedded automatically during transaction.

```clojure
;; Schema
{:chunk/text {:db/valueType         :db.type/string
              :db/embedding          true
              :db.embedding/domains  ["docs"]}}

;; Query — text in, neighbors out (no manual vector handling)
(d/q '[:find [?id ...]
       :in $ ?query
       :where
       [(embedding-neighbors $ ?query {:domains ["docs"] :top 5}) [[?e _ _]]]
       [?e :chunk/id ?id]]
     (d/db conn) "cache invalidation strategy")
```

### User-Supplied Vectors (`:db.type/vec`)

For higher-quality or custom models, vectors can be supplied manually:

```clojure
{:chunk/embedding {:db/valueType :db.type/vec}}

;; Store options
{:vector-opts {:dimensions 384 :metric-type :cosine}}

;; Query
(d/q '[:find [?id ...]
       :in $ ?qvec
       :where
       [(vec-neighbors $ :chunk/embedding ?qvec {:top 5}) [[?e _ _]]]
       [?e :chunk/id ?id]]
     (d/db conn) query-vector)
```

### Embedding Provider API

```clojure
(def provider (d/new-embedding-provider {:provider :default :metric-type :cosine}))
(d/embed-text provider "some text")   ;=> float[]
(d/embedding-dimensions provider)     ;=> 384
```

### Distance Metrics

`:cosine` (recommended for text), `:euclidean`, `:dot-product`, plus `:haversine`, `:pearson`, `:jaccard`, `:hamming`, `:tanimoto`, `:sorensen`.

### HNSW Tuning

- `:connectivity` (M parameter, default 16)
- `:expansion-add` (efConstruction, default 128)
- `:expansion-search` (ef, default 64)

## In-JVM Embedding Alternatives

If the built-in `multilingual-e5-small` proves insufficient:

| Option | Model | Dims | Notes |
|---|---|---|---|
| Datalevin built-in | multilingual-e5-small (GGUF) | 384 | Default, zero-config, CPU-only |
| inference4j | all-MiniLM-L6-v2, all-mpnet-base-v2 | 384/768 | ONNX Runtime, auto-downloads |
| Spring AI Transformers | any HuggingFace ONNX model | varies | DJL + ONNX under the hood |

**Recommendation**: Start with Datalevin's built-in provider. It's zero-config and sufficient for ~170 markdown documents. Upgrade to inference4j only if recall quality is measurably poor.

## Library vs. Application Architecture

The system is split into two artifacts:

### 1. `clj-llm-memory` Library (Clojars)

A reusable Clojure library providing semantic search over markdown document collections. Published to Clojars. **No hardcoded paths, no main entry point, no nREPL, no MCP awareness.** Pure library — consumers bring their own application shell.

**Public API** (`llm-memory.core`):

```clojure
(require '[llm-memory.core :as mem])

;; Open/close a plan store (Datalevin-backed)
(def store (mem/open-store {:path "/tmp/my-plans-db"
                               :embedding {:provider :datalevin}}))
(mem/close-store! store)

;; Register and manage roots (project directories)
(mem/register-root! store {:uri "file:///path/to/project" :plans-dir "Plans"})
(mem/list-roots store)
(mem/remove-root! store "file:///path/to/project")

;; Indexing & reconciliation
(mem/reconcile! store "file:///path/to/project")     ;; diff db vs. disk, handle renames
(mem/index-root! store "file:///path/to/project")    ;; full reindex (drop + re-create)
(mem/index-file! store root-uri "/abs/path/to/file.md")
(mem/retract-file! store root-uri "/abs/path/to/file.md")

;; Search (root-scoped by default)
(mem/search store "cache invalidation" {:root-uri "file:///..." :top 5})
(mem/search store "cache invalidation" {:top 10})    ;; cross-root

;; Other query tools
(mem/related store "gpu-report")
(mem/recent store {:days 7 :root-uri "file:///..."})
(mem/list-files store {:root-uri "file:///..."})
(mem/status store)

;; File watching
(def watcher (mem/start-watcher! store "file:///path/to/project"))
(mem/stop-watcher! watcher)
```

**What the library provides:**
- `PlanStore` protocol + Datalevin implementation (+ future Datahike stub)
- `Embedder` protocol + implementations (Datalevin built-in, inference4j)
- Markdown chunking (H2 splitting, paragraph sub-splitting, semantic slug IDs)
- Metadata inference from file paths/names (status, type, group, jira key)
- YAML frontmatter parsing
- Indexing engine (single file, full root, incremental)
- Search engine (semantic search, metadata filtering, deduplication, root scoping)
- File watcher (beholder, multi-root, debounce, rename detection)
- INDEX.md and STATUS.md generation

**What the library does NOT provide:**
- nREPL / HTTP / any server
- MCP protocol handling
- Process management (PID files, startup, shutdown)
- Platform packaging
- CLI entry point

### 2. `winze-server` Application (this repo)

A thin application layer that wraps the library for use with Claude Code. Lives at `mcp/winze-server/` in this repo. **Not published to Clojars** — it's specific to our MCP + nREPL + Babashka architecture.

**What it adds on top of the library:**
- `llm-memory.server.main/-main` — opens the store, starts watchers for registered roots, starts nREPL on localhost
- Process management (PID file, `.nrepl-port`, shutdown hook)
- `mcp-proxy.clj` — Babashka MCP proxy (stdio JSON-RPC → nREPL)
- Platform packaging (jlink + uberjar + bundled bb)

### Why This Split Matters

1. **Other tools can embed semantic search.** A Clojure application (e.g., a documentation site generator, a CI/CD pipeline, a different AI agent framework) can `require` `llm-memory.core` and get full search without running a server.
2. **No coupling to MCP or nREPL.** The library works in any context — REPL, test harness, web app, CLI tool.
3. **Clean dependency tree.** The library depends on Datalevin + beholder + clj-yaml. It does NOT depend on nREPL, Babashka, or any MCP library. Consumers only pull in what they use.
4. **Testable in isolation.** RCF tests in the library use temp databases — no server process needed.

### Embedding as a Pluggable Protocol

Since library consumers may have different embedding needs, the embedding layer is a protocol:

```clojure
(defprotocol Embedder
  (embed-text [this text])          ;; => float[]
  (embed-texts [this texts])        ;; => [float[] ...]
  (dimensions [this])               ;; => int
  (embedding-info [this]))          ;; => {:model "..." :dims N ...}
```

**Implementations shipped with the library:**
- `DatalevinEmbedder` — delegates to Datalevin's built-in llama.cpp provider (zero-config, if available in dtlvnative)
- `Inference4jEmbedder` — uses inference4j for `all-MiniLM-L6-v2` or `all-mpnet-base-v2` (ONNX Runtime)

**Configuration:**
```clojure
;; Use Datalevin built-in (if available)
(mem/open-store {:path "..." :embedding {:provider :datalevin}})

;; Use inference4j
(mem/open-store {:path "..." :embedding {:provider :inference4j
                                            :model "all-MiniLM-L6-v2"}})

;; BYO embedder (custom implementation)
(mem/open-store {:path "..." :embedding {:provider my-custom-embedder}})
```

When `:provider` is a keyword, the library resolves it to a built-in implementation. When it's an object satisfying `Embedder`, it's used directly. This lets consumers plug in OpenAI, Cohere, or any other embedding source.

**Impact on Datalevin schema:** If the consumer uses `:db/embedding true` (Datalevin built-in), Datalevin handles embedding + vector indexing automatically. If using an external `Embedder`, the schema uses `:db.type/vec` and the library calls `embed-text` before transacting, then uses `vec-neighbors` for search. The `PlanStore` abstraction hides this difference.

## Datahike Abstraction Layer

The goal is to define a protocol that both Datalevin and Datahike can implement, so the system can switch backends when Datahike's vector ecosystem matures.

### Why Datahike is Preferred Long-Term

Datahike is an immutable, append-only Datalog database with full temporal queries (`as-of`, `history`). This makes it ideal for an agentic memory system:

- **Every transaction is preserved** — you can query the state of the Plans index at any point in time
- **Retractions are tracked** — deleted files leave an audit trail
- **Temporal queries** — "what did the index look like on March 1?" or "when was this file last indexed?"

Datahike does not have native vector search, but the replikativ team (same authors) has built **Proximum** — a standalone vector database for the JVM that is designed to work alongside Datahike.

### Proximum (replikativ/proximum) — Datahike-Ecosystem Vector Search

[Proximum](https://github.com/replikativ/proximum) is a standalone, embeddable HNSW vector database built by the Datahike team. It is **not integrated into Datahike's Datalog engine** — it is a separate store that you bridge via entity IDs in Datalog `:in` bindings.

**Key characteristics:**

| Aspect | Details |
|---|---|
| Algorithm | HNSW (pure Java, SIMD-accelerated via Panama Vector API) |
| Distance metrics | Euclidean, Cosine, Inner Product |
| Maturity | Early beta — v0.1.24, released 2026-03-10, 342 Clojars downloads |
| Java requirement | **Java 22+** (Foreign Memory API) + JVM flags: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED` |
| Embeddings | BYO — no built-in embedding; demo (Einbetten) uses Python FastEmbed via `libpython-clj` |
| Storage | Pluggable via Konserve (memory, file, S3) |
| Versioning | Git-like: branches, commits, time travel (`as-of`) |
| Clojure API | Collection protocols — `assoc`, `into`, `seq` on the index |
| Dependencies | Pure JVM (no native libs), Konserve, Malli, persistent-sorted-set |

**Datahike integration pattern** (from the Einbetten demo):

```clojure
;; Proximum search feeds entity IDs into Datahike Datalog
(d/q '[:find ?title ?text
       :in $ ?search-fn ?query
       :where
       [(?search-fn ?query 5) [?eid ...]]
       [?eid :chunk/text ?text]
       [?eid :chunk/article ?article]
       [?article :article/title ?title]]
     @conn
     #(search-similar-chunks prox-idx embedder %1 %2)
     "machine learning algorithms")
```

**Implications for the abstraction layer:**
- The Datahike backend path is more concrete than "wait for native support" — Proximum + Datahike is a viable architecture today
- However, Java 22+ requirement is a blocker (our CI and containers use temurin-21)
- The alpha quality (0.1.x, 2 months old, API may change) means it's not production-ready
- Embedding remains BYO — would need inference4j or similar alongside Proximum

**Verdict**: Proximum validates the abstraction layer design. When it stabilizes (1.0+) and our JVM moves to 22+, the `DatahikePlanStore` implementation becomes: Datahike for Datalog + Proximum for vectors + inference4j for embeddings. For now, Datalevin's integrated solution is simpler and production-ready.

### What the Abstraction Must Cover

The planning system uses a narrow slice of database functionality:

1. **Schema management** — Create/ensure schema for chunks, files, metadata
2. **Transact** — Upsert chunks with metadata and embeddings
3. **Retract** — Remove entities (deleted files, stale chunks)
4. **Datalog query** — `q` with `pull` patterns
5. **Vector search** — Nearest-neighbor by text query or vector
6. **Connection lifecycle** — Open, close, check existence

Both Datahike and Datalevin share Datomic-compatible Datalog syntax, so queries are portable. The divergence is in:
- Vector search API (Datalevin-specific `embedding-neighbors` / `vec-neighbors`)
- Connection/database creation API (different config maps)
- Schema declaration syntax (minor differences)

### Protocol Shape

```clojure
(defprotocol PlanStore
  (connect [this config])
  (transact! [this tx-data])
  (retract! [this entity-ids])
  (query [this datalog-query & params])
  (search [this text-query opts])    ;; semantic search
  (close [this]))
```

The `search` method is the key abstraction point:
- **Datalevin backend**: Delegates to `embedding-neighbors` (built-in embeddings + vector search in one Datalog query)
- **Datahike + Proximum backend** (future): Generates vectors via inference4j, searches Proximum for entity IDs, then runs Datahike Datalog query with those IDs. Proximum's git-like versioning and Datahike's temporal queries (`as-of`, `history`) combine for full change tracking.

## File Watching

### Requirements

- Watch `Plans/` directory recursively
- On **create/modify**: Re-chunk the file, upsert chunks (Datalevin handles embedding automatically)
- On **delete**: Retract all chunks for that file
- On **rename/move**: Detect via delete+create pair (same content hash) and update file path while preserving entity identity
- Debounce rapid changes (e.g. editor save → format → save)

### JVM Options

| Library | Approach | Notes |
|---|---|---|
| `java.nio.file.WatchService` | JDK built-in | macOS uses polling (no native kqueue), ~2s latency |
| [beholder](https://github.com/nextjournal/beholder) | Wraps JNA filesystem events | Native macOS FSEvents, low latency, Clojure-native |
| [hawk](https://github.com/wkf/hawk) | Wraps JDK WatchService + barbary-watchservice | macOS native via barbary, mature |
| [directory-watcher](https://github.com/gmethvin/directory-watcher) | JNA + native APIs | Java library, well-maintained |

**Recommendation**: `beholder` — Clojure-native, uses macOS FSEvents directly (no polling), simple API:

```clojure
(require '[nextjournal.beholder :as beholder])

(def watcher
  (beholder/watch
    (fn [{:keys [type path]}]
      (case type
        :create  (index-file! path)
        :modify  (index-file! path)
        :delete  (retract-file! path)))
    "Plans/"))

;; Stop: (beholder/stop watcher)
```

### Rename Detection

JVM file watchers report renames as a `delete` followed by a `create`. To preserve identity:

1. On **delete**: Don't retract immediately. Instead, record the content hash of the last-indexed version and start a short timer (~500ms).
2. On **create** (within timer window): Compute content hash. If it matches a recent delete, treat as a rename — update the file path on existing entities rather than retract+re-insert.
3. On **timer expiry** (no matching create): Retract as a true deletion.

Content hash is stored as a Datalevin attribute (`:file/content-hash`), enabling this correlation.

### Startup Reconciliation

The file watcher only captures events while it's running. Between server shutdowns and restarts, files may have been created, modified, moved, renamed, or deleted. On startup, a reconciliation pass brings the database into sync with the current filesystem state.

**The key insight:** Content hashes make rename/move detection possible even across restarts. If a file disappeared from path A and a file with the same content hash appeared at path B, that's a rename — regardless of how much time elapsed.

**Algorithm:**

```
1. Load all indexed files for this root from the database:
   db-files = {rel-path → {:entity-id, :content-hash, :modified}}

2. Scan the filesystem for all .md files:
   disk-files = {rel-path → {:content-hash, :modified}}

3. Classify each file into one of five categories:

   UNCHANGED: path exists in both, content-hash matches
     → skip (no work needed)

   MODIFIED: path exists in both, content-hash differs
     → re-index (re-chunk, re-embed, update hash + mtime)

   NEW: path exists on disk but not in db, AND hash doesn't match any GONE file
     → index as new file

   GONE: path exists in db but not on disk, AND hash doesn't match any NEW file
     → retract file + chunks

   RENAMED: a NEW file's content-hash matches a GONE file's content-hash
     → update :file/path and :file/id on existing entity (preserves history)
       also re-infer metadata (status/type/group change when moving dev/ → complete/)

4. Execute in order: RENAMED first (pairs off matches), then MODIFIED, NEW, GONE.
```

**Rename detection details:**

A single content hash may appear multiple times if the same file was copied. Pairing rules:
- Match GONE→NEW by content hash, one-to-one
- If multiple GONEs share a hash, pick the one with the closest mtime to the NEW file
- Any unmatched GONE files are true deletions; unmatched NEW files are true creations
- A file that was both renamed AND modified (content changed) will appear as a GONE + NEW with different hashes — treated as delete + create, which is correct (new content = new embeddings anyway)

**Performance:**

- Scanning ~170 .md files and computing SHA-256 hashes takes <100ms
- Only MODIFIED and NEW files trigger re-embedding (the expensive operation)
- UNCHANGED files (the common case) are a hash comparison — O(1) per file
- The reconciliation pass runs once on startup before the file watcher begins, so there's no race condition

**When reconciliation subsumes full reindex:**

With reconciliation, `full-reindex!` becomes `reconcile!` with no special-casing. The only difference from incremental watcher updates is that reconciliation processes all changes at once rather than one-at-a-time. A `reset: true` option still exists to drop the database and reindex from scratch, but it should never be needed in normal operation.

**Periodic reconciliation (optional, TBD):**

File watchers can miss events — macOS FSEvents has a coalescing window, the OS may drop events under heavy load, or the watcher thread may fall behind. As a safety net, the server can optionally run `reconcile!` on a configurable timer (e.g. every 5 minutes). Since reconciliation is cheap for an unchanged corpus (~100ms for ~170 files) and idempotent (UNCHANGED files are a no-op), this adds negligible overhead while guaranteeing eventual consistency even if the watcher misses events. Configuration:

```clojure
;; In open-store opts or server config
{:reconcile-interval-ms 300000}  ;; 5 minutes; nil or 0 = disabled (default)
```

Implementation: a `ScheduledExecutorService` (single thread) runs `reconcile!` for each registered root at the configured interval. The timer is stopped on shutdown. If a reconciliation is already in progress (from a manual trigger or the previous tick), the scheduled run is skipped.

## Data Model

The database is shared across all projects on the machine. A **root** entity anchors each project; files and chunks are scoped to their root.

### Entities

**Root entity** — one per project/workspace:
```clojure
{:root/uri          "file:///Users/dorme/code/_finance"  ;; from MCP roots/list
 :root/name         "_finance"                            ;; human label
 :root/plans-dir    "Plans"                               ;; relative to root (configurable)
 :root/abs-plans    "/Users/dorme/code/_finance/Plans"    ;; resolved absolute path (derived)
 :root/registered   #inst "2026-03-20T..."                ;; when first seen
 :root/files        [ref ref ref]}                        ;; component refs to files
```

**File entity** — one per markdown file, scoped to a root:
```clojure
{:file/path          "dev/FOO-CONTEXT.md"      ;; relative to Plans/ dir
 :file/root          ref                        ;; back-ref to root entity
 :file/content-hash  "sha256:abc123..."         ;; for rename detection
 :file/modified      #inst "2026-03-20T..."     ;; filesystem mtime
 :file/status        "active"                   ;; inferred: active/complete/deferred
 :file/type          "context"                  ;; inferred: context/plan/story/...
 :file/group         "foo"                      ;; inferred work-item group
 :file/jira          "AAO-99"                   ;; extracted Jira key (optional)
 :file/title         "Foo Feature — Context"    ;; H1 heading
 :file/chunks        [ref ref ref]}             ;; component refs to chunks
```

**Chunk entity** — one per semantic section:
```clojure
{:chunk/id           "_finance::dev/FOO-CONTEXT.md::overview"  ;; globally unique
 :chunk/slug         "overview"                                 ;; heading slug
 :chunk/section      0                                          ;; ordinal within file
 :chunk/text         "# Foo Feature — Context\n\n..."           ;; full text (with H1 prepended)
 :chunk/file         ref}                                       ;; back-ref to file entity
```

The `:chunk/text` attribute is marked with `:db/embedding true`, so Datalevin automatically generates and indexes the embedding vector on transaction.

**Identity keys:**
- `:root/uri` — unique per project (from MCP `roots/list`)
- `:file/path` is only unique *within* a root. The composite identity is `[:file/root + :file/path]`. Since Datalevin doesn't support composite unique keys directly, we use a synthetic `:file/id` (`"<root-name>::<rel-path>"`) as the identity attribute.
- `:chunk/id` — globally unique: `"<root-name>::<rel-path>::<slug>"`

### Schema

```clojure
[;; Root entity
 {:db/ident :root/uri
  :db/valueType :db.type/string
  :db/unique :db.unique/identity}
 {:db/ident :root/name
  :db/valueType :db.type/string
  :db/index true}
 {:db/ident :root/plans-dir
  :db/valueType :db.type/string}
 {:db/ident :root/registered
  :db/valueType :db.type/instant}
 {:db/ident :root/files
  :db/valueType :db.type/ref
  :db/cardinality :db.cardinality/many
  :db/isComponent true}

 ;; File entity
 {:db/ident :file/id
  :db/valueType :db.type/string
  :db/unique :db.unique/identity}
 {:db/ident :file/path
  :db/valueType :db.type/string
  :db/index true}
 {:db/ident :file/root
  :db/valueType :db.type/ref
  :db/index true}
 {:db/ident :file/content-hash
  :db/valueType :db.type/string}
 {:db/ident :file/modified
  :db/valueType :db.type/instant
  :db/index true}
 {:db/ident :file/status
  :db/valueType :db.type/string
  :db/index true}
 {:db/ident :file/type
  :db/valueType :db.type/string
  :db/index true}
 {:db/ident :file/group
  :db/valueType :db.type/string
  :db/index true}
 {:db/ident :file/jira
  :db/valueType :db.type/string
  :db/index true}
 {:db/ident :file/title
  :db/valueType :db.type/string}
 {:db/ident :file/chunks
  :db/valueType :db.type/ref
  :db/cardinality :db.cardinality/many
  :db/isComponent true}

 ;; Chunk entity
 {:db/ident :chunk/id
  :db/valueType :db.type/string
  :db/unique :db.unique/identity}
 {:db/ident :chunk/slug
  :db/valueType :db.type/string}
 {:db/ident :chunk/section
  :db/valueType :db.type/long}
 {:db/ident :chunk/text
  :db/valueType :db.type/string
  :db/embedding true
  :db.embedding/domains ["plans"]}
 {:db/ident :chunk/file
  :db/valueType :db.type/ref}]
```

### Store Options

```clojure
{:embedding-opts   {:provider :default :metric-type :cosine}
 :embedding-domains {"docs" {:provider :default :metric-type :cosine}}}
```

### Multi-Root Design Rationale

A single shared database (rather than one database per project) provides:

- **Cross-project search** — "find all GPU-related context across all my projects"
- **Shared embedding model** — loaded once, reused across all roots
- **Single file watcher process** — watches all registered roots concurrently
- **Unified HNSW index** — one vector index, better recall than N small indices

Search is **scoped to the requesting root by default** (the proxy passes the root URI from `roots/list`). An explicit `all-roots: true` option enables cross-project queries.

The database lives at `~/.local/share/winze/.datalevin/` (XDG convention), not inside any project directory.

## Process Architecture

The system runs as two processes:

### 1. Plan Server (long-running JVM)

A persistent JVM process that owns the Datalevin database, file watcher, and search engine. It runs continuously in the background — started on demand, never killed between Claude Code sessions.

**Responsibilities:**
- Datalevin database lifecycle (open on start, close on shutdown)
- Filesystem watchers (beholder) — one per registered root, watches `<root>/<plans-dir>/` recursively
- Root registration — when a new root URI arrives (via proxy), register it: create root entity, start watcher, trigger initial index
- Search/query engine — handles all tool operations, scoped to requesting root by default
- Exposes an nREPL server on `localhost` only (127.0.0.1, never 0.0.0.0) for the MCP proxy

**Lifecycle:**
- Started by the Babashka proxy on first MCP request (if not already running)
- Stays alive indefinitely (file watchers keep running between sessions)
- Database at `~/.local/share/winze/.datalevin/` (shared across all projects)
- PID + nREPL port files at `~/.local/share/winze/`
- Graceful shutdown via signal or API call

### 2. Babashka MCP Proxy (lightweight, ephemeral)

A small Babashka script that Claude Code launches as its MCP subprocess (stdio transport). It translates MCP JSON-RPC requests into calls to the long-running Plan Server.

```
Claude Code
    ↓  (stdio JSON-RPC)
bb mcp-proxy.clj          ← ephemeral, ~100ms startup
    ↓  (nREPL on localhost)
Plan Server JVM            ← long-running, owns Datalevin + file watcher
    ↓
Datalevin (.datalevin/)
```

**Why this split:**
- **JVM cold-start** — A full JVM with Datalevin + usearch + llama.cpp takes seconds to start. Claude Code launches a new MCP subprocess per session. Babashka starts in ~100ms.
- **File watcher persistence** — The watcher must survive between Claude Code sessions to keep the index current. A long-running JVM process does this naturally.
- **Resource sharing** — One Datalevin instance, one embedding model loaded, one HNSW index in memory — regardless of how many Claude Code sessions connect.

**Babashka proxy logic:**
1. Complete MCP `initialize` handshake with Claude Code
2. Call `roots/list` on the client to discover the project root URI(s)
3. Check if Plan Server is running (PID file at `~/.local/share/winze/.pid` + nREPL health check)
4. If not running: Start it (`java -jar clj-llm-memory.jar &`), poll for `.nrepl-port` file (up to 10s)
5. Connect to Plan Server via nREPL, register the root(s): `(llm-memory.root/ensure-root! "file:///Users/dorme/code/_finance")`
6. Forward each MCP tool call to the Plan Server, passing the root URI for scoping
7. Return the response to Claude Code via stdio
8. On stdin EOF (Claude Code disconnects): Exit (Plan Server keeps running, watchers stay active)

**Root discovery fallback:** If the MCP client does not support the `roots` capability (not declared in `initialize`), fall back to `PLANS_ROOT` environment variable or CWD.

**Registration** (global — works for any project):
```bash
claude mcp add clj-llm-memory -- bb ~/.local/share/winze/mcp-proxy.clj
```

The proxy script and uberjar are installed to `~/.local/share/winze/`, not inside any project. The MCP registration is global — the same proxy/server pair serves every project on the machine.

## Project Layout

### `mcp/clj-llm-memory/` — Library (published to Clojars)

The reusable library. Has its own git repo (or is split out when ready to publish).

```
mcp/clj-llm-memory/
├── deps.edn              ;; library deps (datalevin, beholder, clj-yaml)
├── build.clj             ;; tools.build: jar + deploy to Clojars
├── src/
│   └── llm_memory/
│       ├── core.clj       ;; public API (open-store, search, index, etc.)
│       ├── store/
│       │   ├── protocol.clj  ;; PlanStore protocol
│       │   └── datalevin.clj ;; Datalevin implementation
│       ├── embed/
│       │   ├── protocol.clj  ;; Embedder protocol
│       │   ├── datalevin.clj ;; Datalevin built-in embedder
│       │   └── inference4j.clj ;; inference4j embedder
│       ├── chunk.clj      ;; markdown chunking
│       ├── metadata.clj   ;; metadata inference from paths/names
│       ├── frontmatter.clj ;; YAML frontmatter parsing
│       ├── index.clj      ;; indexing engine (file + root)
│       ├── search.clj     ;; search/query API (root-scoped)
│       ├── watcher.clj    ;; beholder filesystem watcher (multi-root)
│       └── generate.clj   ;; INDEX.md + STATUS.md generation
└── test/
    └── plans/
        └── ...            ;; RCF tests (temp databases)
```

### `mcp/winze-server/` — Application (this repo only)

Thin wrapper. Depends on the `clj-llm-memory` library.

```
mcp/winze-server/
├── deps.edn              ;; depends on clj-llm-memory + nrepl
├── build.clj             ;; tools.build: uberjar + jlink + package
├── Makefile              ;; build, package, install targets
├── src/
│   └── llm_memory/
│       └── server/
│           └── main.clj   ;; -main: open store, start nREPL + watchers
├── mcp-proxy.clj          ;; Babashka MCP proxy script
└── bb/                    ;; platform Babashka binaries (downloaded by build)
```

### Runtime (installed): `~/.local/share/winze/`

Shared across all projects on the machine.

```
~/.local/share/winze/
├── winze-server.jar   ;; uberjar (library + server)
├── mcp-proxy.clj          ;; proxy script
├── bb                     ;; platform Babashka binary
├── .datalevin/            ;; Datalevin database (all roots)
├── .pid                   ;; Plan Server PID
└── .nrepl-port            ;; nREPL port for proxy discovery
```

## Build & Distribution

### Two Build Artifacts

| Artifact | Published | Contents | Consumers |
|----------|-----------|----------|-----------|
| `clj-llm-memory` library JAR | **Clojars** | Library only — no server, no main | Clojure developers embedding search in their tools |
| `winze-server` package | **GitHub Releases** (per-platform tarballs) | Uberjar + JRE + Babashka + proxy | End users running Claude Code |

### Library: Standard Clojars JAR

Built with `tools.build`. Published as `io.github.<org>/clj-llm-memory` (or `<group>/clj-llm-memory`) on Clojars.

**Dependency implications for consumers:**
- Datalevin is a transitive dependency. It pulls in dtlvnative platform JARs (~2MB total across all platforms). JavaCPP auto-selects the correct one at runtime — no consumer action needed.
- Beholder (file watcher) is a transitive dependency. Uses JNA — works on macOS, Linux, Windows without extra configuration.
- inference4j is an **optional dependency** (not in the default dep tree). Consumers who want the inference4j embedder add it explicitly. The `Inference4jEmbedder` implementation uses `requiring-resolve` to avoid hard-coupling.

**Clojars publishing:**
```bash
cd mcp/clj-llm-memory && make deploy   # clj -T:build deploy
```

**Consumer usage:**
```clojure
;; deps.edn
{:deps {io.github.xxx/clj-llm-memory {:mvn/version "0.1.0"}
        ;; Optional: only if using inference4j embedder
        io.github.inference4j/inference4j-core {:mvn/version "LATEST"}}}
```

### Application: jlink + Uberjar + Bundled Babashka

GraalVM native-image is **not viable** for the Plan Server because nREPL requires dynamic class loading and JNA (beholder) uses runtime `dlopen`. Datalevin's own docs recommend the uberjar for long-running servers.

**Package structure:**

```
winze-server-<platform>/
├── jre/                              # Minimal JRE via jlink (~40-60MB)
├── lib/
│   └── winze-server-uber.jar   # Uberjar (library + nREPL + main)
├── bin/
│   ├── plan-server                  # Shell/bat launcher
│   ├── plan-server-mcp              # Shell/bat: invokes bb mcp-proxy.clj
│   ├── bb                           # Bundled Babashka binary (~25MB)
│   └── mcp-proxy.clj               # Babashka MCP proxy script
```

**Babashka is bundled** (~25MB per platform). Downloaded from official GitHub releases during build. This makes the package fully self-contained — zero prerequisites for end users.

**Launcher script** (`bin/plan-server`):
```bash
#!/bin/bash
DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec "$DIR/jre/bin/java" \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  -jar "$DIR/lib/winze-server-uber.jar" "$@"
```

**MCP registration** uses the bundled bb:
```bash
claude mcp add clj-llm-memory -- ~/.local/share/winze/bin/bb \
  ~/.local/share/winze/bin/mcp-proxy.clj
```

**JVM flags required by Datalevin:**
- `--add-opens=java.base/java.nio=ALL-UNNAMED` (LMDB memory-mapped I/O)
- `--add-opens=java.base/sun.nio.ch=ALL-UNNAMED` (LMDB direct buffers)
- `--enable-native-access=ALL-UNNAMED` (JavaCPP native library loading)

### Datalevin Native Library Coverage

Datalevin publishes platform-specific native JARs on Clojars (via dtlvnative):

| Platform | Artifact | Status |
|----------|----------|--------|
| macOS arm64 | `dtlvnative-macosx-arm64` | Supported |
| Linux amd64 | `dtlvnative-linux-x86_64` | Supported |
| Linux arm64 | `dtlvnative-linux-arm64` | Supported |
| Windows amd64 | `dtlvnative-windows-x86_64` | Supported |
| macOS Intel | — | **Not supported** (no published native JAR) |

Native libs included: LMDB (dlmdb fork), usearch (HNSW), OpenMP runtime. The llama.cpp embedding library is in dtlvnative 0.18.x (future).

### Cross-Compilation

**Cannot cross-compile.** Each platform's package must be built on that platform (or in an equivalent environment):

| Target | Build Environment |
|--------|-------------------|
| macOS arm64 | macOS arm64 runner (GitHub Actions `macos-14`, or local machine) |
| Linux amd64 | Linux amd64 runner, or Docker from macOS |
| Linux arm64 | Linux arm64 runner, or Docker `--platform linux/arm64` from macOS (slow, emulated) |
| Windows amd64 | Windows runner (GitHub Actions `windows-latest`) |

**jlink constraint:** jlink can only build a JRE for the platform whose JDK you're running. To build a Linux JRE, you need to run jlink from a Linux JDK. (Workaround: download the target platform's JDK and use its jlink binary, but this is fragile.)

**Practical CI strategy:**
1. Build the uberjar once (platform-independent, any CI runner)
2. On each platform runner: download the uberjar artifact, run jlink to create the platform JRE, download the platform Babashka binary, package the whole thing as a tarball/zip

### Build Targets (Makefile)

```makefile
uber:           # Build platform-independent uberjar
package-local:  # Package for the current platform (jlink + bb + uber)
install:        # Copy package to ~/.local/share/winze/
```

CI adds per-platform `package-macos-arm64`, `package-linux-amd64`, etc.

## Key Risks

1. **Datalevin macOS arm64 support** — usearch native library is listed as supported but should be verified early.
2. **Built-in embedding availability** — dtlvnative 0.16.5 (shipped with Datalevin 0.10.7) may not include llama.cpp. The built-in embedding provider may require a newer Datalevin version (0.10.12+, dtlvnative 0.18.x). Phase 0 must verify this. Fallback: use inference4j for in-JVM embeddings + `:db.type/vec` for user-supplied vectors.
3. **512-token embedding limit** — If the built-in `multilingual-e5-small` is available, it has a 512-token context window (vs. nomic-embed-text's 2048). May need tighter chunking or a different model.
4. **Datalevin API stability** — v0.10.x is relatively new. The embedding API may evolve.
5. **nREPL security** — The Plan Server's nREPL listens on `localhost` only (127.0.0.1, never 0.0.0.0). nREPL has no authentication — binding to localhost ensures only local processes can connect. The port file (`.nrepl-port`) is written to `~/.local/share/winze/`.
6. **No macOS Intel support** — Datalevin does not publish a dtlvnative JAR for macOS x86_64. Only macOS arm64 is supported. This is unlikely to matter (Apple Silicon is standard since 2020).
7. **Cross-compilation not possible** — Each platform's package must be built on that platform. Requires CI runners per target OS/arch. Linux builds can be done from macOS via Docker; macOS and Windows cannot.
8. **Proximum Java 22 requirement** — The future Datahike backend path depends on Proximum, which requires Java 22+ (Panama Foreign Memory API). Our current stack uses temurin-21. This is not a blocker for the Datalevin-first approach but constrains when the Datahike swap can happen.
9. **Proximum maturity** — v0.1.24 (early beta, 2 months old, ~340 downloads). API may change before 1.0. The abstraction layer insulates us from this risk.
