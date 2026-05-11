---
type: context
status: complete
group: root-config
related: complete/auto-register/CONTEXT.md
---

# Root Config File — Context

## Problem

Registered roots are persisted in Datalevin. When the database is deleted or restored
from backup, all root registrations are lost. The server starts with an empty root list
and search is broken until an MCP client connects and the proxy re-registers.

This happened in practice (2026-03-27): the HNSW corruption fix required deleting the
store. After restart, `list-roots` returned `[]` — no indexing, no search — until the
MCP proxy connected and called `register_plans`.

The prior auto-register work (see `complete/auto-register/`) solved the "first session"
case by having the proxy detect and register on MCP init. This is complementary: it
ensures the server can restore its own root registrations without waiting for a client.

## Design

A lightweight `roots.edn` file lives alongside the database:

```
~/.local/share/winze/
  lib/winze-server.jar
  mcp-proxy.clj
  roots.edn                ← new
  .datalevin/              ← Datalevin store
  .nrepl-port
  .pid
```

`roots.edn` is a vector of root maps, mirroring the fields stored in Datalevin:

```clojure
[{:uri "file:///Users/dorme/code/_finance" :name "_finance" :plans-dir "Plans"}
 ...]
```

### Write path

Whenever a root is registered or removed **via the server**, the file is rewritten:

- `register-root!` call succeeds → rewrite `roots.edn`
- `remove-root!` call succeeds → rewrite `roots.edn`

The file reflects the current registered set at all times.

### Read path (startup)

In `start!`, after `open-store-resilient`:

1. Read current roots from store (`list-roots`)
2. Read `roots.edn` (if it exists)
3. For each root in the file that is NOT in the store, call `register-root!` and
   `reconcile-and-watch!` (re-registers and re-indexes)
4. Continue normal startup

This handles two scenarios:
- **Fresh store** (deleted): all roots missing → all re-registered from file
- **Partial restore** (backup older than last registration): missing roots filled in from file

Roots already in the store are untouched — no redundant reconciliation.

### Remove path

`remove-root!` (programmatic or future UI) must:
1. Remove root from store
2. Rewrite `roots.edn` without that root
3. Stop its watcher

If `roots.edn` is not updated on removal, the root would re-register on next restart —
which would be incorrect behavior.

## Scope

All changes are in `winze-server/src/llm_memory/server/main.clj`:
- Add `roots-config-path` (path to `roots.edn`)
- Add `read-roots-config` (read and parse file; returns `[]` if missing/malformed)
- Add `write-roots-config!` (write current registered roots atomically)
- Add `sync-roots-from-config!` (register + reconcile any roots in file but not in store)
- Wrap the nREPL-exposed `register-root!` handler to call `write-roots-config!` after success
- Wrap the nREPL-exposed `remove-root!` handler to call `write-roots-config!` after success
- Call `sync-roots-from-config!` in `start!` after `open-store-resilient`

No changes to `clj-llm-memory` library. No changes to `mcp-proxy.clj`.

## Relationship to Prior Work

The proxy-side `ensure-root-registered!` (`complete/auto-register/`) remains in place.
It handles the "brand new project, never registered" case (heuristic filesystem check).
This work handles the "known root, lost after store reset" case. They are complementary.

**Successor to**: [Auto-Register Root on Init — Context](../complete/auto-register/CONTEXT.md) and [Plan](../complete/auto-register/PLAN.md) — proxy-side auto-registration on MCP init; design constraints and implementation history there.
