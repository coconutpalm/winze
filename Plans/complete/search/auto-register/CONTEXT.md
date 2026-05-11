---
created: 2026-03-25
related: [winze, mcp-proxy, register-plans]
superseded_by: dev/ROOT-CONFIG-CONTEXT.md
tags: [winze, auto-registration, DX]
---

# Auto-Register Root on Init — Context

## Problem

When a Claude Code session starts for a project that has a `Plans/` directory but hasn't been registered with the Plan Server yet, the proxy logs a warning:

```
root not yet registered: file:///path/to/project — use register_plans tool or /register-plans skill
```

The user must then explicitly call `/register-plans` before plan search works. This is unnecessary friction — if the project clearly has a Plans directory with the expected `dev/` and `complete/` subdirectories, the system should register and index it automatically.

## Current Architecture

### Init Flow (mcp-proxy.clj)

1. MCP client sends `initialize` with `params.roots[0].uri`
2. `handle-initialize` extracts root URI → stores in `@root-uri` atom
3. `ensure-root-registered!` checks if root is in the Datalevin store
4. If not registered → **logs warning only**, does nothing

### Registration Flow (register_plans tool)

The `register_plans` tool (called manually) does:

1. Check if root already registered → skip if so
2. Validate `Plans/` directory exists (`.isDirectory()`)
3. `llm-memory.core/register-root!` → transacts `{:root/uri, :root/name, :root/plans-dir}`
4. `llm-memory.index/reconcile!` → indexes all `.md` files (six-way classify: unchanged/modified/renamed/renamed-modified/new/gone)
5. `llm-memory.watcher/start-watcher!` → filesystem watcher with 500ms debounce

### Key Files

| File | Role |
|------|------|
| `~/.local/share/winze/mcp-proxy.clj` | MCP proxy — `ensure-root-registered!` (line 454), `register_plans` tool (line 401) |
| `winze/clj-llm-memory/src/llm_memory/core.clj` | `register-root!`, `list-roots` |
| `winze/clj-llm-memory/src/llm_memory/index.clj` | `reconcile!` — six-way file classification and indexing |
| `winze/clj-llm-memory/src/llm_memory/watcher.clj` | `start-watcher!` — filesystem monitoring |
| `winze/clj-llm-memory/src/llm_memory/metadata.clj` | Status/group inference from directory structure |
| `winze/winze-server/src/llm_memory/server/main.clj` | Server startup — `reconcile-and-watch!` for existing roots |

### Directory Convention

The metadata system expects these reserved subdirectories under `Plans/`:
- `dev/` → status = "active"
- `dev/deferred/` → status = "deferred"
- `complete/` → status = "complete"
- `reference/` → status = "active"

The presence of both `dev/` and `complete/` is a strong signal that this is a legitimate Plans directory following project conventions.

## Design Constraints

1. **Proxy runs in Babashka** — limited to bb-compatible libraries. All Datalevin/index/watcher operations must be delegated to the JVM server via nREPL eval.
2. **Filesystem checks can happen in the proxy** — `(.isDirectory (io/file ...))` works in bb. Use this for the lightweight heuristic check; delegate registration/indexing to the server.
3. **Registration is idempotent** — `register_plans` already checks for existing roots. But avoid redundant nREPL roundtrips on every init for already-registered roots.
4. **Reconciliation is not instant** — indexing a large Plans directory takes a few seconds. This should not block the MCP init response (which has already been sent by `handle-initialize`). The current flow already calls `ensure-root-registered!` after the init response is written.
5. **Default `plans-dir` is `"Plans"`** — the auto-registration should use this default, matching `register-root!`'s default.
6. **Logging** — use `log-proxy` for proxy-side messages so the user can see what happened in stderr.
