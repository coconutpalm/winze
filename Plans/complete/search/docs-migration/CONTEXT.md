---
group: docs-migration
tags: documentation, clj-llm-memory
---

# Documentation Migration — Context

## Source Material

The legacy Python `planning-tool` has a `docs/` folder with four documents plus two project-level files:

| File | Lines | Content |
|------|-------|---------|
| `docs/workflow.md` | 335 | The AI-centric planning workflow: three-layer architecture, task lifecycle, document types, "why this works" comparison |
| `docs/claude-md-guide.md` | 191 | CLAUDE.md integration: essential sections to copy, slash command skills, auto-memory tips |
| `docs/setup-guide.md` | 244 | Step-by-step installation: prerequisites, embedding pipeline, MCP registration, customization, troubleshooting |
| `docs/toc.md` | 24 | Table of contents linking the three docs |
| `README.md` | 78 | Quick-start summary of the project |
| `SETUP-PROMPT.md` | 43 | Copy-paste prompt for first-time setup via Claude Code |

## What to Carry Over

### workflow.md — Almost entirely applicable

The conceptual material is technology-agnostic and just as valid for the Clojure implementation:

- **Three-layer architecture** description (files + search + CLAUDE.md instructions)
- **Task lifecycle** (`dev/` → `complete/`, document types, naming conventions)
- **Collaborative planning process** (create CONTEXT+PLAN together, iterate, then implement in a clean session)
- **"Keep documents alive"** and **"Resuming work"** sections
- **Jira integration** section
- **"Why this works"** comparison table
- **Tips** section

**Changes needed:**
- Update the three-layer diagram: replace "ChromaDB + nomic-embed-text via Ollama" with "Datalevin + inference4j (all-MiniLM-L6-v2)"
- Replace any references to "Python MCP server" with the Babashka proxy + JVM server architecture
- "Re-index with `reset: true`" on task completion is no longer needed — the filesystem watcher handles renames/deletes automatically. Mention that `index_plans` is only needed for INDEX.md/STATUS.md regeneration.
- Add a note about root scoping (multi-project support)

### claude-md-guide.md — Carry over with updates

- The concept ("CLAUDE.md provides behavior, tools provide capability") is exactly right
- **Section 1 (Searching Plans)**: Update tool descriptions — add `register_plans`, `list_plan_roots`. Change `plans_status` description from "Ollama status" to "embedding status, watcher status". Add `all_roots` parameter mention.
- **Section 2 (Prior Work)**: Carry over as-is
- **Section 3 (Task Lifecycle)**: Remove "Always call `index_plans` after any `Plans/` file operation" — the filesystem watcher handles this now. Keep the `dev/` → `complete/` archival instructions.
- **Indexing section**: Major rewrite. Remove "Pass `reset: true` for moves/renames/deletes." Replace with filesystem watcher description. `index_plans` is now only for INDEX.md/STATUS.md regeneration or manual reconciliation.
- **Slash commands**: Update from 2 to 6 (add `/recent-plans`, `/related-plans`, `/register-plans`, `/list-plan-roots`)
- **Auto-memory section**: Remove "Always call `index_plans` after modifications" — replace with watcher description

### setup-guide.md — Major rewrite needed

The Python-specific content is entirely obsolete:
- Python 3.12 requirement → JDK 21+
- Ollama requirement → none (in-JVM embeddings)
- ChromaDB → Datalevin
- `venv`, `setup.sh`, `requirements.txt` → `make install-mcp`
- Manual `claude mcp add` → handled by `make install-mcp`
- Manual `venv/bin/python index.py` → auto-start on first use

**Carry over:**
- The general structure (prerequisites → install → register → configure CLAUDE.md → verify)
- The troubleshooting table format (replace all entries)
- The "What You Need to Copy" section concept (but the answer is now just `make install-mcp`)
- The embedding pipeline technical explanation (rewrite for inference4j + Datalevin)

### toc.md — Rewrite

Same structure, updated descriptions.

### README.md — Already exists and is current

The server README (`winze-server/README.md`) already covers quick start, install, tools, architecture, and development. No need to duplicate.

### SETUP-PROMPT.md — Rewrite

The concept is good (a copy-paste prompt for first-time setup), but every step changes:
1. No Python/Ollama prerequisites
2. `make install-mcp` replaces 5 manual steps
3. `/register-plans` replaces manual indexing
4. Verification via `/list-plan-roots` and `/search-plans`

## What to Drop

1. **All Python-specific content**: venv, requirements.txt, setup.sh, ChromaDB, Ollama, `python3.12`, `ModuleNotFoundError` troubleshooting
2. **`index_plans` as mandatory post-write step**: The filesystem watcher makes this unnecessary. Every reference to "always call index_plans after writes" should be removed or replaced with "the filesystem watcher handles this automatically."
3. **`reset: true` for renames/deletes**: The six-category reconciler (with fuzzy rename detection) handles renames and deletes without a full reset. Only mention reset for manual troubleshooting.
4. **ChromaDB-specific troubleshooting**: "Large `.vector-db/` directory", "Python 3.13+ errors", etc.
5. **Embedding model customization via Ollama**: Now using inference4j with a bundled model — no external model management.

## What to Add (not in the original docs)

1. **Multi-project support**: The legacy tool was single-project. The new system supports multiple registered roots with scoped search. Document `/register-plans` for new projects and `all_roots` for cross-project search.
2. **Fuzzy rename detection**: The six-category reconciler is a significant improvement over the legacy five-category approach. Document what it does and when it activates.
3. **Filesystem watcher behavior**: Document the 500ms debounce, rename detection window, and what events trigger re-indexing. This replaces the "always call index_plans" protocol.
4. **Auto-start architecture**: The proxy auto-starts the Plan Server on first use. The server persists between sessions. This is a key operational difference from the Python server (which was started fresh each session).
5. **Logback logging**: Server logs to `plan-server.log` (rolling, 10MB max). Proxy also logs there. No stdout/stderr output (critical for MCP stdio transport).
6. **Library vs. server separation**: The library (`clj-llm-memory`) is reusable independently of the MCP server. Document this distinction for users who want to embed it in their own tools.

## Target Location

`mcp/winze-server/docs/` — matching the source structure. The server project is the user-facing distribution point; the library README covers the programmatic API.
