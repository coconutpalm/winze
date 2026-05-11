---
name: help-plans
description: List all available Plans/ slash commands from the winze system. Use when the user types /help-plans or asks what planning commands are available.
---

# Plans System — Available Commands

When the user invokes `/help-plans`, display this reference:

## Search & Browse

| Command | Description |
|---------|-------------|
| `/search-plans <query>` | Semantic search over planning documents. Supports filters: `status` (active/complete/deferred), `doc_type` (context/plan/story/jira/...), `group`, `since` (ISO date). |
| `/recent-plans [days] [doc_type]` | List recently modified documents. Defaults to last 7 days. Example: `/recent-plans 14 context` |
| `/related-plans <group>` | Find all documents in a work-item group with cross-references. Example: `/related-plans gpu-report` |

## Index & Registration

| Command | Description |
|---------|-------------|
| `/register-plans [dir]` | Register a project's planning documents directory (default: `Plans/`). Required before search works in a new project. |
| `/index-plans [reset]` | Reconcile index with disk. Pass `reset` only when explicitly requested — normally the filesystem watcher handles updates automatically. |
| `/list-plan-roots` | Show all registered project roots, their plans directories, indexed file counts, and watcher status. |

## Tips

- The Plan Server **auto-starts** on first use and persists between sessions.
- A **filesystem watcher** keeps the index up to date — manual `/index-plans` is rarely needed.
- `/index-plans` (without `reset`) also regenerates `INDEX.md` and `STATUS.md`.
- Use `search_plans` filters to narrow results: e.g., `status="complete"`, `group="cache-gap-detect"`.
