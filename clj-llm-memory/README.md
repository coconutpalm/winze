# clj-llm-memory

Semantic search over markdown document collections, powered by [Datalevin](https://github.com/juji-io/datalevin) and in-JVM embeddings via [inference4j](https://github.com/inference4j/inference4j).

A reusable Clojure library. No server, no external processes, no API keys — everything runs in a single JVM.

## Features

- **Semantic vector search** — HNSW index with cosine distance (all-MiniLM-L12-v2, 384 dimensions)
- **Markdown-aware chunking** — splits at H2 boundaries, prepends H1 title, paragraph sub-splits for oversized sections
- **Metadata inference** — derives status, type, group, and Jira key from directory/filename conventions (~97% coverage)
- **YAML frontmatter** — optional overrides for fields that can't be inferred
- **Incremental indexing** — SHA-256 content hashing, six-category reconciliation (unchanged/modified/renamed/renamed+edited/new/gone)
- **Fuzzy rename detection** — two-pass algorithm: exact content hash for pure renames, then embedding centroid similarity (cosine ≥ 0.6) for rename+edit
- **Filesystem watcher** — beholder-based, 500ms debounce, rename detection via content hash correlation
- **Multi-root support** — index multiple project directories in one store, scoped search per root
- **Pluggable storage** — `PlanStore` protocol with Datalevin implementation (Datahike stub for future use)
- **Pluggable embeddings** — `Embedder` protocol with inference4j implementation

## Quick Start

```clojure
;; deps.edn
{:deps {io.github.dorme/clj-llm-memory {:mvn/version "0.1.156"}}}
```

```clojure
(require '[llm-memory.core :as mem])

;; Open a store (Datalevin + inference4j, model auto-downloads on first use)
(def store (mem/open-store {:path "/tmp/my-plans-db"
                            :embedding :inference4j}))

;; Register a project root
(mem/register-root! store {:uri "file:///path/to/project"
                           :name "my-project"
                           :plans-dir "Plans"})

;; Index all markdown files
(mem/index-root! store "file:///path/to/project")

;; Search
(mem/search store "cache invalidation strategy" {:top 5})
;; => [{:chunk/id "my-project::dev/CACHE-CONTEXT.md::overview"
;;      :chunk/text "..."
;;      :relevance 0.83
;;      :file/path "dev/CACHE-CONTEXT.md"
;;      :file/status "active"
;;      :file/group "cache"
;;      ...}]

;; Close when done
(mem/close-store! store)
```

## API Reference

### Store Lifecycle

```clojure
(mem/open-store {:path "..." :embedding :inference4j})  ;=> PlanStore
(mem/close-store! store)
```

The `:embedding` option accepts:
- `:inference4j` — use all-MiniLM-L12-v2 with defaults (384d, 512 token limit)
- `{:provider :inference4j :model-id "..." :max-length N :dims N}` — custom config
- An `Embedder` protocol instance — bring your own

### Root Management

```clojure
(mem/register-root! store {:uri "file:///..." :name "my-project" :plans-dir "Plans"})
(mem/list-roots store)         ;=> [{:root/uri "..." :root/name "..." ...}]
```

### Indexing

```clojure
(mem/index-file! store root-uri abs-path)    ;; single file
(mem/retract-file! store root-uri abs-path)  ;; remove file + chunks
(mem/rename-file! store root-uri old new)    ;; preserves entity identity
(mem/reconcile! store root-uri)              ;; diff db vs. disk
(mem/index-root! store root-uri)             ;; full reindex (drop + recreate)
```

`reconcile!` classifies files into six categories:
- **UNCHANGED** — path and hash match, skip
- **MODIFIED** — path matches, hash differs, re-index
- **RENAMED** — content hash matches a deleted file, update path + metadata
- **RENAMED+EDITED** — embedding centroid similarity ≥ 0.6 between a deleted and new file, retract old + index new
- **NEW** — no match in db, index
- **GONE** — no match on disk, retract

### Search

```clojure
(mem/search store "query text" {:top 5
                                :root-uri "file:///..."  ;; scope to one root
                                :status "active"         ;; metadata filters
                                :type "context"
                                :group "cache"
                                :since "2026-03-01"      ;; ISO date
                                :dedupe true})           ;; one result per file
```

### Other Queries

```clojure
(mem/related store "gpu-report")           ;=> {:files [...] :cross-refs #{...}}
(mem/recent store {:days 7})               ;=> [{:file/path "..." ...}]
(mem/list-files store {:root-uri "..."})   ;=> [{:file/id "..." ...}]
(mem/status store)                         ;=> {:files N :chunks N :roots N :embedding {...}}
(mem/hnsw-health store)                    ;=> {:total N :indexed N :missing N}
```

### File Watching

```clojure
(def watcher (mem/start-watcher! store root-uri))  ;; watches Plans/ dir for changes
(mem/stop-watcher! watcher)
```

The watcher automatically handles create, modify, delete, and rename events with 500ms debounce and 1-second rename detection window.

### Formatted Tool Output

`llm-memory.tools` provides formatted markdown output matching the MCP tool interface:

```clojure
(require '[llm-memory.tools :as tools])

(tools/search-plans store "query" {:n-results 5 :detail :files})
(tools/list-plans store)
(tools/related-plans store "group-name")
(tools/recent-plans store {:days 7})
(tools/plans-status store)
(tools/index-plans store root-uri {:reset false})
```

### INDEX.md / STATUS.md Generation

```clojure
(require '[llm-memory.generate :as gen])

(gen/generate-index plans-dir)    ;; writes INDEX.md
(gen/generate-status plans-dir)   ;; writes STATUS.md
(gen/generate-all! store root-uri)
```

## Metadata Inference

File metadata is derived from directory paths and filenames:

| Convention | Example | Inferred |
|---|---|---|
| Top-level directory | `dev/...` | status=active |
| Top-level directory | `complete/...` | status=complete |
| Subdirectory | `dev/deferred/...` | status=deferred |
| Bare type name | `complete/gpu-report/CONTEXT.md` | type=context, group=gpu-report |
| Prefixed type | `dev/CACHE-GAP-DETECT-CONTEXT.md` | type=context, group=cache-gap-detect |
| Jira filename | `dev/jira/AAO-66.md` | type=jira, jira=AAO-66 |

Optional YAML frontmatter overrides inferred values:

```yaml
---
created: 2026-03-20
related: plans-system-improvement, search-improvement
tags: datalevin, vector-search
---
```

## Architecture

```
llm-memory.core          — public API (data-oriented)
llm-memory.tools         — formatted markdown output (MCP tool layer)
llm-memory.chunk         — H2 splitting, slugify, paragraph sub-split
llm-memory.frontmatter   — YAML frontmatter parsing
llm-memory.metadata      — path-based metadata inference
llm-memory.index         — indexing engine (index/retract/rename/reconcile)
llm-memory.watcher       — filesystem watcher (beholder + debounce)
llm-memory.generate      — INDEX.md / STATUS.md generation
llm-memory.embed.*       — Embedder protocol + inference4j implementation
llm-memory.store.*       — PlanStore protocol + Datalevin implementation
```

## Building

```bash
make jar      # build library JAR
make install  # install to local Maven repo
make deploy   # publish to Clojars (requires CLOJARS_USERNAME/CLOJARS_PASSWORD)
make test     # run tests
make clean    # clean build artifacts
```

## Requirements

- **JDK 21+** (temurin recommended)
- JVM flags: `--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --enable-native-access=ALL-UNNAMED`

## License

Copyright 2026. All rights reserved.
