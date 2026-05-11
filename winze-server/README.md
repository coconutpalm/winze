# winze-server - LLM memory, MCP server, and user interface

> *A winze is a shaft that takes one deeper in a (data) mine*

Semantic search over project Markdown knowledge base / LLM memory, exposed to LLMs via the Model Context Protocol (MCP) and to the user via a GUI.

A filesystem watcher updates the index incrementally as you (or your LLM) edit your knowledge base, so changes appear in search results immediately.

![Winze window with live search](docs/winze-screenshot.png)

The server includes a native user interface (via Eclipse SWT and Clojure Desktop Toolkit) with live search of all documents.  A system tray icon toggles visibility.

## Documentation

See **[docs/](docs/toc.md)** for detailed guides:

- **[Workflow](docs/workflow.md)** — Why this system exists and how to use it effectively
- **[CLAUDE.md Guide](docs/claude-md-guide.md)** — Instructions to add to your project's CLAUDE.md
- **[Setup Guide](docs/setup-guide.md)** — Installation, registration, and troubleshooting
- **[Setup Prompt](docs/setup-prompt.md)** — Copy-paste prompt for first-time setup

## Architecture

```
Claude Code (stdin/stdout)
    ↓ JSON-RPC
bb mcp-proxy.clj (Babashka)
    ↓ nREPL (bencode, localhost)
Plan Server JVM (this project)
    ↓ llm-memory.tools/*
clj-llm-memory library
    ↓ Datalevin + inference4j
~/.local/share/winze/.datalevin/
```

**Embedding model:** `all-MiniLM-L12-v2` (384d, ~127MB ONNX). Auto-downloaded from HuggingFace on first server start. Runs entirely in-JVM via inference4j/ONNX Runtime with SIMD acceleration on Apple Silicon.

**Three processes:**

1. **Plan Server** (long-lived JVM) — owns the Datalevin store, runs filesystem watchers, exposes the library via nREPL on localhost, and runs an SWT desktop window with live search and system tray icon. Persists between Claude Code sessions.
2. **Babashka Proxy** (short-lived, per Claude Code session) — translates MCP JSON-RPC to nREPL eval calls. Auto-starts the Plan Server if not running.
3. **Claude Code** (client) — calls MCP tools via stdio to the proxy.

## Install

**Prerequisite:** JDK 21+ must be on `PATH` (`java -version` to check). [Eclipse Temurin](https://adoptium.net/) recommended — `brew install temurin@21` on macOS. Everything else (Clojure CLI, Babashka) is installed or downloaded automatically by the build.

```bash
# Build self-contained package, install, register with Claude Code
make install-winze
```

This is the only command most users need. It:
- Builds the uberjar (~125MB, includes Datalevin + ONNX Runtime + embedding model)
- Creates a minimal JRE via jlink (~40–60MB)
- Downloads a platform-matched Babashka binary
- Assembles a self-contained package with launcher scripts
- Installs everything to `~/.local/share/winze/`
- Registers `winze` as a global MCP server with Claude Code
- Installs all seven slash command skills globally (`~/.claude/skills/`)
- Installs rules globally (`~/.claude/rules/`)

## Uninstall

```bash
make uninstall-winze
```

Deregisters the MCP server, removes skills, and stops the running server. The Datalevin database at `~/.local/share/winze/` is preserved — delete it manually if unwanted.

## Register Your Project

Each project must register its Plans/ directory before search works. In a Claude Code session within your project:

```
/register-plans
```

This registers the current project with the default `Plans/` directory. For a different directory:

```
/register-plans docs/plans
```

The Plan Server starts automatically on first use. Use `/list-plan-roots` to verify registration.

## Usage

After `make install-winze`, start a new Claude Code session. The MCP tools are available immediately:

| Tool | Description |
|------|-------------|
| `search_plans` | Semantic search over Plans/ documents (scoped to current project) |
| `list_plans` | List all indexed file paths (scoped to current project) |
| `related_plans` | Find all documents in a work-item group + cross-references |
| `recent_plans` | List documents modified in the last N days |
| `plans_status` | Health check: embedding status, file/chunk counts, HNSW index health, watcher status |
| `index_plans` | Reconcile or full reindex (filesystem watcher handles most updates) |
| `register_plans` | Register a project's Plans/ directory for indexing |
| `list_plan_roots` | List all registered project roots and their status |

Query tools (`search_plans`, `list_plans`, `related_plans`, `recent_plans`) are scoped to the current project by default. Pass `all_roots: true` to search across all registered projects.

Slash commands (via installed skills):
- `/help-plans` — list all available plans commands
- `/search-plans <query>` — semantic search over Plans/
- `/index-plans [reset]` — reconcile or full reindex
- `/recent-plans [days]` — list recently modified documents (default 7 days)
- `/related-plans <group>` — find all documents in a work-item group + cross-references
- `/register-plans [dir]` — register a project's Plans/ directory
- `/list-plan-roots` — show all registered roots and watcher status

## How It Works

**First use in a session:**
1. Claude Code starts the proxy (`bb mcp-proxy.clj`)
2. Proxy checks `~/.local/share/winze/.nrepl-port`
3. If no server running: starts `java -jar winze-server.jar` (first run downloads the embedding model ~127MB, subsequent starts ~3s)
4. Server opens Datalevin store, reconciles registered roots, starts filesystem watchers
5. Proxy connects to nREPL, registers the project root
6. Tool calls flow: proxy → nREPL eval → `llm-memory.tools/*` → Datalevin → formatted markdown

**Subsequent tool calls:** proxy → nREPL → instant response (no startup overhead).

**Between sessions:** the Plan Server keeps running. Filesystem watchers keep the index current in real time. The next session's proxy reconnects to the existing nREPL.

## Search Quality & Relevance Scores

Results include a relevance percentage (cosine similarity) and a badge: **strong** (>50%), **partial** (30–50%), **weak** (<30%). For typical exploratory queries, 35–55% is normal — the top result is almost always relevant. Use domain-specific terms and full sentences for higher scores (70%+).

See **[docs/search-quality.md](docs/search-quality.md)** for details: score interpretation, query tips, examples, and embedding model info.

## Configuration

| Env var | Default | Description |
|---------|---------|-------------|
| `PLANS_DB_PATH` | `~/.local/share/winze` | Data directory (Datalevin store, PID/port files) |
| `PLANS_ROOT` | CWD | Fallback project root if MCP client doesn't provide roots |

## Files

```
~/.local/share/winze/
├── lib/winze-server.jar         # Uberjar
├── jre/                         # Minimal JRE (jlink)
├── bin/                         # Launcher scripts + Babashka binary
│   ├── bb                       # Babashka (platform-specific)
│   ├── winze-server             # Server launcher
│   ├── winze-mcp                # MCP proxy launcher
│   └── mcp-proxy.clj            # Babashka MCP proxy script
├── mcp-proxy.clj                # Babashka MCP proxy (legacy path)
├── .datalevin/                  # Datalevin database
├── plan-server.log              # Server + proxy log (rolling, 10MB max)
├── .nrepl-port                  # Server nREPL port (created at runtime)
├── .pid                         # Server PID (created at runtime)
└── .lock                        # Startup lock file (prevents concurrent launches)
```

## Development

```bash
make clean    # clean build artifacts
make uber     # build uberjar
make install  # build + copy to ~/.local/share/ (dev shortcut, no packaging)
make run      # start server directly (for debugging)
make package  # build self-contained platform archive (jlink JRE + Babashka + launchers)
```

`make install` is a lightweight dev shortcut: it builds the uberjar, copies it to `~/.local/share/winze/lib/`, and installs rules — but doesn't bundle a JRE or Babashka (it assumes both are already on `PATH`). Use `make install-winze` for full packaging and MCP registration.

`make package` builds a distributable archive (`target/winze-<platform>.tar.gz`) containing the uberjar, a minimal jlink JRE, a platform-matched Babashka binary, launcher scripts, skills, and rules. Platform is auto-detected; override with `PLATFORM=linux-amd64`.

The server uses `:local/root` to depend on the sibling `clj-llm-memory` library during development. Changes to the library are picked up immediately without publishing.

## Platform Support

| Platform | Status | Notes |
|----------|--------|-------|
| **macOS ARM64** (Apple Silicon) | Supported | Primary development platform |
| **Linux AMD64** | Supported | Static Babashka binary |
| **Linux ARM64** | Supported | Static Babashka binary |
| **Windows AMD64** | Supported | Uses `install.ps1` instead of `install.sh` |
| **macOS x86_64** (Intel) | Not supported | Datalevin native libs unavailable for this platform |

## Requirements

**Build-time** (needed for `make install-winze`):
- **JDK 21+** on `PATH` — [Eclipse Temurin](https://adoptium.net/) recommended (`brew install temurin@21` on macOS, or download from adoptium.net)
- **Clojure CLI** (`clj`) — if not already installed, the Makefile will offer to install it for you

**Run-time** (bundled by the build — no manual install needed):
- **Babashka** (`bb`) — downloaded and bundled automatically by `make install-winze`
- **JRE** — a minimal JRE is built via jlink and bundled in the package

**Integration:**
- **Claude Code** — for MCP server registration and slash command skills

## License

Copyright 2026. All rights reserved.
