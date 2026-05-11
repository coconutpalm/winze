---
created: 2026-03-23
related: [datalevin-migration, docs-migration, platform-packaging]
tags: [rename, branding]
---

# Winze Rename — Plan

Rename `clj-llm-memory-server` → `winze-server` across code, config, docs, and runtime.

## Prerequisites

- [ ] Stop the running Plan Server (`pkill -f clj-llm-memory-server` or via PID)
- [ ] Verify no active Claude Code sessions depend on MCP tools mid-operation

## Phase 1: Server Project Source

All changes within `mcp/clj-llm-memory-server/` (before directory rename).

- [ ] **1.1** `build.clj` — Change `lib` to `'io.github.dorme/winze-server`, `uber-file` to `"target/winze-server.jar"`
- [ ] **1.2** `Makefile` — Update `UBER_JAR`, `DATA_DIR` (`~/.local/share/winze`), jar copy/run paths
- [ ] **1.3** `mcp-proxy.clj` — Update `data-dir` to `~/.local/share/winze`, `server-jar` to `winze-server.jar`
- [ ] **1.4** `dev/runner.clj` — Update `project-name` to `"winze-server"`
- [ ] **1.5** `README.md` — Update title, all references to project name, jar name, directory paths
- [ ] **1.6** `docs/setup-guide.md` — Update directory paths, pkill commands
- [ ] **1.7** `docs/setup-prompt.md` — Update cd/make commands
- [ ] **1.8** Skills source files (`skills/*/SKILL.md`) — Update MCP provider name from `clj-llm-memory` to `winze` in all seven skill files

## Phase 2: Directory Rename

- [ ] **2.1** `git mv mcp/clj-llm-memory-server mcp/winze-server` (preserves git history)

## Phase 3: Runtime Migration

- [ ] **3.1** Move runtime data directory: `mv ~/.local/share/clj-llm-memory ~/.local/share/winze`
- [ ] **3.2** Rename jar inside: `mv ~/.local/share/winze/clj-llm-memory-server.jar ~/.local/share/winze/winze-server.jar`
- [ ] **3.3** Update the installed `mcp-proxy.clj` in `~/.local/share/winze/` (copy from source)

## Phase 4: Claude Code Configuration

- [ ] **4.1** Update `~/.claude.json` — Re-register MCP server:
  - Remove `clj-llm-memory` entry
  - Add `winze` entry pointing to `~/.local/share/winze/mcp-proxy.clj`
  - (Or use `claude mcp remove clj-llm-memory && claude mcp add winze -- bb ~/.local/share/winze/mcp-proxy.clj`)
- [ ] **4.2** Update `~/.claude/settings.json` — Update permission entries:
  - Jar execution permission: update path to `~/.local/share/winze/winze-server.jar`
  - MCP tool permissions: `mcp__clj-llm-memory__*` → `mcp__winze__*` (7 entries)
- [ ] **4.3** Reinstall skills: copy updated skill files from `mcp/winze-server/skills/` to `~/.claude/skills/`

## Phase 5: Project Documentation

- [ ] **5.1** Update `CLAUDE.md` — Plans search system architecture section (library path stays, server path changes)
- [ ] **5.2** Update memory file `MEMORY.md` — Plans Search System entry

## Phase 6: Plans Documents

Update references in active/deferred plans for accuracy:

- [ ] **6.1** `Plans/dev/PLATFORM-PACKAGING-PLAN.md` — build commands, jar paths, distribution paths
- [ ] **6.2** `Plans/dev/PLATFORM-PACKAGING-CONTEXT.md` — project description
- [ ] **6.3** `Plans/dev/deferred/WEB-SEARCH-PLAN.md` — build/skill paths

Completed plans (optional — historical record, lower priority):

- [ ] **6.4** `Plans/complete/datalevin-migration/CONTEXT.md`
- [ ] **6.5** `Plans/complete/datalevin-migration/PLAN.md`
- [ ] **6.6** `Plans/complete/docs-migration/CONTEXT.md`
- [ ] **6.7** `Plans/complete/docs-migration/PLAN.md`
- [ ] **6.8** `Plans/complete/inline-tests/PLAN.md`

## Phase 7: Verify

- [ ] **7.1** Build: `cd mcp/winze-server && make clean uberjar`
- [ ] **7.2** Install: `make install-mcp`
- [ ] **7.3** Start server: verify auto-start via MCP tool call
- [ ] **7.4** Test: run `search_plans`, `list_plans`, `plans_status` — verify data intact
- [ ] **7.5** Verify skills: run `/search-plans test query` — confirm skill invocation works
- [ ] **7.6** Verify permissions: confirm no unexpected permission prompts for MCP tools

## Phase 8: Cleanup

- [ ] **8.1** Remove old runtime directory if still present: `rm -rf ~/.local/share/clj-llm-memory`
- [ ] **8.2** Verify no remaining references: `grep -r "clj-llm-memory-server" ~/.claude/ mcp/ Plans/ CLAUDE.md`
- [ ] **8.3** Git commit (top-level repo)
- [ ] **8.4** Re-index plans: `/index-plans` to pick up renamed/moved files

## Notes

- **Clojure namespaces are unchanged** — `llm-memory.server.main` stays as-is. The namespace refers to the library's domain concept, not the product brand. Renaming namespaces would require changing the library too, which is out of scope.
- **The library dependency in deps.edn** stays as `io.github.dorme/clj-llm-memory {:local/root "../clj-llm-memory"}` — library is not being renamed.
- **Datalevin data is portable** — moving the `.datalevin/` directory preserves all indexed data. No reindexing needed.
- **Total estimated files to edit:** ~25 files (8 source, 2 config, 2 project docs, 7 skills, ~8 plans docs)
