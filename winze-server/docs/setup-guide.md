# Setup Guide — Replicate in Your Own Project

This guide explains how to install the planning tool in your project
and get it running. The entire system is self-contained: a Clojure
uberjar, a Babashka MCP proxy, and some markdown conventions.

## What You Get

A semantic search engine for your project's planning documents. Write
markdown files in a `Plans/` directory; the system indexes them with
vector embeddings and lets Claude Code search them by meaning, not just
keywords. A filesystem watcher keeps the index current in real time.

## Prerequisites

- **JDK 21+** on `PATH` — [Eclipse Temurin](https://adoptium.net/)
  recommended (`brew install temurin@21` on macOS)
- **[Claude Code](https://claude.ai/code)** — for MCP server integration

The Clojure CLI (`clj`) and Babashka (`bb`) are handled automatically
by the build: the Makefile offers to install Clojure if it's missing,
and downloads a platform-matched Babashka binary into the package.

No external embedding services needed — the embedding model
(all-MiniLM-L12-v2, 384 dimensions) runs in the JVM via
[inference4j](https://github.com/inference4j/inference4j).

## Install

From the `winze-server` directory:

```bash
cd winze-server
make install-winze
```

This single command:
1. Builds the uberjar (~125MB, includes Datalevin + ONNX Runtime + embedding model)
2. Creates a minimal JRE via jlink and downloads a platform-matched Babashka binary
3. Assembles a self-contained package with launcher scripts
4. Installs everything to `~/.local/share/winze/`
5. Registers `winze` as a global MCP server with Claude Code
6. Installs all seven slash command skills globally (`~/.claude/skills/`)
7. Installs rules globally (`~/.claude/rules/`)

**Restart VS Code** after installation for the MCP server to connect.

## Register Your Project

Each project must register its Plans/ directory before search works.
In a Claude Code session within your project:

```
/register-plans Plans
```

Or if your planning documents live in a different directory:

```
/register-plans docs/plans
```

The Plan Server starts automatically on first use — no manual process
management needed.

## Add CLAUDE.md Instructions

This is the most important step. Without CLAUDE.md instructions,
the AI won't know the search tools exist.

See [claude-md-guide.md](claude-md-guide.md) for the exact sections
to add to your project's CLAUDE.md.

## Verify

```
/list-plan-roots          — should show your project registered
/search-plans test query  — should return results from your Plans/
```

## How It Works

### Architecture

```
Claude Code (stdin/stdout)
    ↓ JSON-RPC (MCP protocol)
bb mcp-proxy.clj (Babashka)
    ↓ nREPL (bencode, localhost)
Plan Server JVM (long-lived)
    ↓ llm-memory library
Datalevin + inference4j
    ↓
~/.local/share/winze/.datalevin/
```

**Three processes:**

1. **Plan Server** (long-lived JVM) — owns the Datalevin store, runs
   filesystem watchers, exposes the library via nREPL on localhost,
   and runs an SWT desktop window with live search and a system tray
   icon. Persists between Claude Code sessions. Auto-started by the proxy.
2. **Babashka Proxy** (per Claude Code session) — translates MCP
   JSON-RPC to nREPL eval calls. Starts the Plan Server if not running.
3. **Claude Code** (client) — calls MCP tools via stdio to the proxy.

### Embedding pipeline

1. Files are read, YAML frontmatter is stripped, and the body is split
   at H2 (`##`) section boundaries. Sections larger than 4000 characters
   are further split at paragraph boundaries.

2. Each chunk gets a **semantic ID** (`project::file.md::heading-slug`),
   so inserting a new section doesn't invalidate downstream chunk IDs.

3. **Metadata is inferred automatically** from file paths and naming
   conventions — no manual frontmatter needed for most fields:
   - `status` from directory (`dev/` → active, `complete/` → complete)
   - `type` from filename suffix (`-CONTEXT.md` → context)
   - `group` from filename prefix (`CACHE-GAP-DETECT-CONTEXT.md` →
     `cache-gap-detect`)
   - `modified` from filesystem mtime

4. Optional **YAML frontmatter** can add or override metadata fields
   that can't be inferred (like `created`, `related`, `tags`).

5. Chunks are embedded using all-MiniLM-L12-v2 (384 dimensions) via
   inference4j — entirely in-JVM, no external process.

6. Embeddings are stored in Datalevin's HNSW vector index with cosine
   distance metric.

### Filesystem watcher

A [beholder](https://github.com/nextjournal/beholder)-based watcher
monitors each registered root's Plans/ directory. File creates,
modifications, deletes, and renames are detected with a 500ms debounce.
The six-category reconciler classifies changes:

- **Unchanged** — skip
- **Modified** — re-embed
- **Renamed** — update path and metadata (exact content hash match)
- **Renamed+Edited** — detected by embedding centroid similarity ≥ 0.6
- **New** — embed and index
- **Gone** — remove from store

### Multi-project support

The system supports multiple registered project roots in a single
Datalevin store. Each root has its own filesystem watcher. Search
queries are scoped to the current project by default; pass
`all_roots: true` to search across all projects.

## Customization

### Naming conventions

Metadata inference derives status, type, group, and Jira key from
directory paths and filenames. The conventions:

| Convention | Example | Inferred |
|---|---|---|
| Top-level directory | `dev/...` | status=active |
| Top-level directory | `complete/...` | status=complete |
| Subdirectory | `dev/deferred/...` | status=deferred |
| Bare type name | `complete/gpu-report/CONTEXT.md` | type=context, group=gpu-report |
| Prefixed type | `dev/CACHE-GAP-DETECT-CONTEXT.md` | type=context, group=cache-gap-detect |
| Jira filename | `dev/jira/AAO-66.md` | type=jira, jira=AAO-66 |

If you use different conventions, the inference logic is in
`llm-memory.metadata/infer-metadata`.

### Chunk size

The default maximum chunk size is 4000 characters, chosen to stay
within the embedding model's 512-token window. Adjust `max-chars`
in `llm-memory.chunk/split-sections` if needed.

### YAML frontmatter

Optional frontmatter overrides inferred values and adds fields that
can't be inferred:

```yaml
---
created: 2026-03-20
related: plans-system-improvement, search-improvement
tags: datalevin, vector-search
supersedes: old-plan-name
---
```

## Troubleshooting

| Problem | Solution |
|---------|----------|
| MCP server shows "failed" after VS Code restart | Check `~/.local/share/winze/plan-server.log` for errors. Kill zombie JVMs: `pkill -f winze-server` then restart VS Code |
| Search returns no results | Run `/register-plans` to register the project, then `/index-plans` to build the initial index |
| Stale results after file changes | The filesystem watcher should handle this automatically. Run `/index-plans` for manual reconciliation |
| Multiple JVM processes running | `pkill -f winze-server.jar` to kill all, then restart VS Code — the proxy starts one clean instance |
| Datalevin errors in server log | Store corruption — `rm -rf ~/.local/share/winze/.datalevin` then restart. The index rebuilds from disk |
| `bb` not found | Install Babashka: `brew install borkdude/brew/babashka` |
| `java` not found or wrong version | Install JDK 21+: `brew install temurin@21` |

## Uninstall

```bash
cd winze-server
make uninstall-winze
```

This deregisters the MCP server, removes skills, and stops the running
server. The Datalevin database at `~/.local/share/winze/` is
preserved — delete it manually if unwanted.
