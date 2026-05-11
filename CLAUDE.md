# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Workspace Overview

This is the source repository for the **Winze Plans Search MCP server** — a semantic search system for markdown planning documents. It is written in Clojure and consists of two projects:

- `clj-llm-memory/` — Core library: chunking, embedding, metadata inference, vector store, filesystem watcher
- `winze-server/` — Server application: nREPL server exposing search tools, Babashka MCP proxy, Claude Code skills

Each subproject is its own Git repository. Manage Git separately for each.

## Projects

### `clj-llm-memory/` — Core Library

Provides the semantic search engine: Datalevin vector store, inference4j embeddings (all-MiniLM-L12-v2, 384d), markdown chunking, YAML frontmatter parsing, and metadata inference from file naming conventions.

- **Build tool**: deps.edn + tools.build
- **Commands** (from `clj-llm-memory/`):
  - `make repl` — Start REPL (interactive use only; use the start-nrepl skill for headless nREPL)
  - `make test` — Run tests
  - `make jar` — Build JAR
  - `make install` — Install to local Maven repo
  - `make deploy` — Publish to Clojars (requires `CLOJARS_USERNAME` / `CLOJARS_PASSWORD`)
- **Testing**: RCF (Rich Comment Forms) — inline tests via `(tests ... := ... :rcf)`
- **Key modules**:
  - `src/llm_memory/core.clj` — Top-level API: index, search, watcher lifecycle
  - `src/llm_memory/chunk.clj` — Markdown → text chunks
  - `src/llm_memory/embed/` — Embedding protocol + inference4j implementation
  - `src/llm_memory/frontmatter.clj` — YAML frontmatter parsing
  - `src/llm_memory/metadata.clj` — Metadata inference from path/filename conventions
  - `src/llm_memory/index.clj` — Index/reconcile documents into the store
  - `src/llm_memory/tools.clj` — MCP tool implementations (search, list, status, etc.)
  - `src/llm_memory/watcher.clj` — Filesystem watcher (auto re-indexes on file changes)
  - `src/llm_memory/store/` — Store protocol, Datalevin and Datahike implementations
  - `src/llm_memory/generate.clj` — INDEX.md / STATUS.md generation

### `winze-server/` — Server Application

Runs as a long-lived JVM nREPL server. The Babashka MCP proxy (`mcp-proxy.clj`) translates MCP JSON-RPC into nREPL calls. Claude Code skills are installed globally.

- **Build tool**: deps.edn + tools.build
- **Install location**: `~/.local/share/winze/`
- **Commands** (from `winze-server/`):
  - `make repl` — Start REPL (interactive use; use start-nrepl skill for headless)
  - `make uber` — Build uberjar (`target/winze-server.jar`)
  - `make install` — Build jar, copy to `~/.local/share/winze/`, stop running server
  - `make run` — Start the installed server directly (normally auto-started by proxy)
  - `make install-winze` — Full install: build + install + register MCP server + install skills
  - `make uninstall-winze` — Remove MCP registration, skills, and stop server
- **Key files**:
  - `src/llm_memory/server/main.clj` — nREPL server entry point
  - `mcp-proxy.clj` — Babashka MCP proxy (copied to `~/.local/share/winze/` on install)
  - `skills/` — Claude Code skill definitions, installed to `~/.claude/skills/` globally
  - `resources/` — Server configuration / classpath resources

## Architecture

```
Planning documents (markdown)
        ↓
clj-llm-memory
  chunk.clj          — split markdown into overlapping text chunks
  frontmatter.clj    — parse YAML frontmatter
  metadata.clj       — infer doc_type/status/group from path conventions
  embed/             — inference4j (all-MiniLM-L12-v2) → float[] vectors
  store/datalevin    — Datalevin KV + HNSW index (primary store)
  watcher.clj        — FSEvents/inotify → auto re-index on file changes
  tools.clj          — search, list, status, register, index MCP tools
        ↓
winze-server
  main.clj           — nREPL server (port in ~/.local/share/winze/.nrepl-port)
  mcp-proxy.clj      — Babashka script: MCP JSON-RPC → nREPL calls
        ↓
Claude Code (MCP client)
  /search-plans, /index-plans, /recent-plans, /related-plans, etc.
```

**Data location**: `~/.local/share/winze/` — JAR, proxy script, `.datalevin/` store, `.nrepl-port`, `.pid`.

**Auto-start**: The proxy starts the server on first MCP call if not running.

**Multi-root**: Projects register their plans directories via `register_plans`. Each root gets its own filesystem watcher.

## Metadata System

Metadata is inferred from file naming and directory conventions (~97% coverage for type/status, ~94% for group). Optional YAML frontmatter adds fields that can't be inferred: `created`, `related`, `tags`, `supersedes`. Frontmatter overrides inferred values.

Supported metadata fields: `status` (active/complete/deferred), `doc_type` (context/plan/story/report/etc.), `group` (work-item group name).

## Clojure Conventions

- **REPL-first development**: Develop and test in the REPL before modifying files.
- **Data-oriented design**: Flat data structures with namespaced keywords. Prefer destructuring.
- **Formatting**: Align multi-line elements vertically in vectors, maps, and lists.
- **Naming**: `kebab-case` for functions/vars, `snake_case` for filenames. Don't shadow core built-ins (`name`, `type`, `map`, `filter`, `count`, `str`, `key`, `set`, etc.).
- **Testing**: RCF inline tests via `(tests ... := ... :rcf)`. Eftest as the runner.
- **Error handling**: Return error maps (`{:error ...}`), don't throw.
- **Docstrings**: Place immediately after `defn` name, before the argument vector.
- **Small functions**: Prefer small, composable functions. Rarely exceed 10 lines. Extract sub-tasks into their own top-level functions.
- **Define before use**: Prefer function ordering over `declare`.
- **List test dependencies last**: Order `:require` clauses alphabetically; test deps last.
- **Reference Clojure functions through aliases or `:refer`s**: e.g. `str/join` not `clojure.string/join`.
- **Reference Java classes through imports**: `(:import [java.util Date])` then use `Date` directly.

## Development Guidelines

- Validate understanding of the task before implementing
- Use the REPL to iterate until the approach meets quality criteria
- Never implement workarounds — fix root causes
- `(tests ... :rcf)` blocks document usage patterns AND serve as tests
- `(comment ... :rcf)` blocks document usage patterns (not evaluated on load)
- For new code, prefer `(tests ... :rcf)` blocks for testing and usage illustration
- Inline `def` for debugging is preferred over println
- **RCF tests require a dev-mode REPL**: The running Winze server (auto-started by the MCP proxy) does **not** have RCF testing enabled — it runs production code. To verify RCF tests, always launch a **separate headless nREPL** with the `:dev` alias from the relevant project directory (e.g. `winze-server/` or `clj-llm-memory/`). Use the `start-nrepl` skill, which includes `-M:dev`. Without `:dev`, `(tests ...)` blocks are no-ops and failures are silent.

## Server Lifecycle

### Graceful Shutdown

Never use `pkill` or `kill` to stop the running Winze server. Use the `quit!` function via nREPL:

```bash
clj-nrepl-eval -p <port> "(llm-memory.ui.main-window/quit!)"
```

Force-killing the JVM while Datalevin has an open write transaction corrupts the LMDB store, causing SIGSEGV crashes on next startup. Recovery requires deleting and rebuilding the entire store (~2 min for re-embedding).

### Datalevin Store Recovery

If the store becomes corrupted or locked (e.g. after a forced kill or nREPL deadlock):

1. Kill the JVM: `kill -9 <pid>`
2. Check for built-in snapshots: `~/.local/share/winze/.datalevin/snapshots/current/`
3. Restore from snapshot:
   ```bash
   mv ~/.local/share/winze/.datalevin ~/.local/share/winze/.datalevin-backup-$(date +%s)
   mkdir -p ~/.local/share/winze/.datalevin/snapshots
   cp <backup>/snapshots/current/data.mdb ~/.local/share/winze/.datalevin/data.mdb
   cp <backup>/snapshots/current/VERSION  ~/.local/share/winze/.datalevin/VERSION
   ```
4. If no snapshot exists, delete the store entirely — it will be rebuilt from scratch on next startup (re-indexes all files, ~60s).

### Concurrent nREPL Access (Subagents)

**Do NOT run multiple subagents that `load-file` or `require :reload` against the same nREPL server concurrently.** SWT's single-UI-thread model deadlocks when multiple nREPL threads simultaneously try `sync-exec!` / `async-exec!` while the main thread is blocked on a Datalevin read lock. This exhausts the nREPL thread pool and makes the server unrecoverable.

**Safe pattern**: Subagents should **write files only** (no REPL interaction). After all subagents complete, do a single `make install` + restart to pick up all changes.

## Classpath Resource Access (JAR-safe)

When reading resources from the classpath, **never use `(io/file (io/resource "path"))`** — this fails inside uberjars because `io/resource` returns a `jar:` URL that `io/file` cannot convert to a `java.io.File`.

**Instead**:
- To **read** a resource: `(io/reader (io/resource "path"))` — works with both `file:` and `jar:` URLs
- To **enumerate** resources in a directory: use `classpath-lang-urls` pattern from `highlight/loader.clj` — check URL protocol, use `File.listFiles` for `file:` or `JarFile.entries` for `jar:`
