---
name: index-plans
description: Re-index Plans/ markdown documents into the vector database using the planning MCP server. Use when the user types /index-plans, after adding or editing Plans/ files, or when search results seem stale.
---

# Index Plans

Call the `index_plans` MCP tool (provided by `winze`) with the appropriate arguments:

- `/index-plans` — incremental reconcile: diff db vs. disk, only re-embed what changed. This is the normal mode — the filesystem watcher handles most updates automatically.
- `/index-plans reset` — **ONLY when the user explicitly types "reset"**. This drops and rebuilds the entire index from scratch (~30s). Never pass `reset: true` on your own initiative.

Both modes auto-regenerate `INDEX.md` and `STATUS.md`.

**IMPORTANT**: Never call `index_plans` with `reset: true` automatically. The default incremental reconcile handles renames, modifications, and deletions correctly. A reset is destructive and slow — only use it when the user explicitly requests it.

Report back the reconciliation summary (unchanged/modified/renamed/new/gone counts).
