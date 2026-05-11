---
created: 2026-03-23
related: [datalevin-migration, docs-migration, platform-packaging]
tags: [rename, branding]
---

# Winze Rename — Context

## Goal

Rename the `clj-llm-memory-server` project to `winze-server`. The underlying library (`clj-llm-memory`) retains its current name — it's a Clojure library following `clj-` naming conventions and isn't user-facing.

The server application is the user-facing distribution artifact, and "winze" is the new product name. "Winze" is a mining term for a shaft sunk from one level to a deeper one — evoking the tool's purpose of going deeper into accumulated knowledge.

## Scope

**What changes:**
- The server project directory: `mcp/clj-llm-memory-server/` → `mcp/winze-server/`
- Build artifacts: `clj-llm-memory-server.jar` → `winze-server.jar`
- The lib identifier in build.clj: `io.github.dorme/clj-llm-memory-server` → `io.github.dorme/winze-server`
- The MCP server registration name: `clj-llm-memory` → `winze`
- The runtime data directory: `~/.local/share/clj-llm-memory/` → `~/.local/share/winze/`
- All documentation, CLAUDE.md, memory files, Plans references
- Claude Code configuration (`.claude.json`, `.claude/settings.json`)

**What does NOT change:**
- The library project: `mcp/clj-llm-memory/` stays as-is
- Clojure namespaces in the library: `llm-memory.*` stays as-is
- Clojure namespaces in the server: `llm-memory.server.main` stays as-is (it references the library's domain, not the product name)
- The Datalevin database contents (data is portable)
- MCP tool names: `search_plans`, `related_plans`, etc. stay as-is (though their MCP-prefixed permission names change from `mcp__clj-llm-memory__*` to `mcp__winze__*`)

## Affected Files — Complete Inventory

### 1. Server Project Source (mcp/clj-llm-memory-server/)

| File | What changes |
|------|-------------|
| `build.clj` | `lib` symbol, `uber-file` path |
| `Makefile` | `UBER_JAR` variable, `DATA_DIR`, jar copy/run paths |
| `mcp-proxy.clj` | `data-dir`, `server-jar` paths |
| `dev/runner.clj` | `project-name` string |
| `README.md` | Title, all jar references, directory paths |
| `docs/setup-guide.md` | Directory paths, pkill commands |
| `docs/setup-prompt.md` | cd/make commands |
| Directory itself | Rename `clj-llm-memory-server/` → `winze-server/` |

### 2. Installed Skills (~/.claude/skills/*/SKILL.md)

Seven skill files reference `clj-llm-memory` as the MCP provider name. These need updating to `winze`:
- `search-plans/SKILL.md`
- `index-plans/SKILL.md`
- `recent-plans/SKILL.md`
- `related-plans/SKILL.md`
- `register-plans/SKILL.md`
- `list-plan-roots/SKILL.md`
- `help-plans/SKILL.md`

**Note:** The source skills in `mcp/clj-llm-memory-server/skills/` are the source of truth; installed skills are copies. Update the source, then reinstall.

### 3. Claude Code Configuration

| File | What changes |
|------|-------------|
| `~/.claude.json` | MCP server key: `clj-llm-memory` → `winze`; proxy path |
| `~/.claude/settings.json` | Permission entries referencing jar path and `mcp__clj-llm-memory__*` tool names |

### 4. Project Documentation

| File | What changes |
|------|-------------|
| `/Users/dorme/code/_finance/CLAUDE.md` | Plans search system architecture section |
| Memory file: `MEMORY.md` | Plans Search System entry |

### 5. Plans Documents (Historical — update for accuracy)

| File | References |
|------|-----------|
| `Plans/complete/datalevin-migration/CONTEXT.md` | Project name, jar names |
| `Plans/complete/datalevin-migration/PLAN.md` | Project creation, build steps |
| `Plans/complete/docs-migration/CONTEXT.md` | README location |
| `Plans/complete/docs-migration/PLAN.md` | Directory paths, pkill commands |
| `Plans/complete/inline-tests/PLAN.md` | Build commands |
| `Plans/dev/PLATFORM-PACKAGING-PLAN.md` | Build/jdeps commands, distribution paths |
| `Plans/dev/PLATFORM-PACKAGING-CONTEXT.md` | Project description |
| `Plans/dev/deferred/WEB-SEARCH-PLAN.md` | Build/skill paths |

### 6. Runtime Artifacts

| Location | What changes |
|----------|-------------|
| `~/.local/share/clj-llm-memory/` | Entire directory moves to `~/.local/share/winze/` |
| `~/.local/share/clj-llm-memory/clj-llm-memory-server.jar` | Becomes `~/.local/share/winze/winze-server.jar` |
| `~/.local/share/clj-llm-memory/mcp-proxy.clj` | Becomes `~/.local/share/winze/mcp-proxy.clj` |

The Datalevin database (`.datalevin/`), PID file, nREPL port file, and logs move with the directory.

## Risks and Considerations

1. **Running server must be stopped first** — the current JVM holds file handles on `.datalevin/` and writes to `.nrepl-port`/`.pid`. Stop before moving.
2. **MCP permission names change** — `mcp__clj-llm-memory__search_plans` becomes `mcp__winze__search_plans`. All seven tool permissions in `settings.json` need updating or Claude Code will prompt for re-approval.
3. **Plans documents are historical record** — updating completed-work plans is optional but reduces confusion for future Claude sessions that search them.
4. **The Makefile `install-mcp` target** handles copying jar + proxy to the data dir and registering with Claude Code. It should be updated to handle the new paths and also clean up old artifacts.
5. **Environment variable `PLANS_DB_PATH`** — if anyone overrides the default data directory, they're unaffected. The default changes from `~/.local/share/clj-llm-memory` to `~/.local/share/winze`.
