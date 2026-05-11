---
name: list-plan-roots
description: List all registered project roots in the Plan Server, showing their plans directories, indexed file counts, and watcher status. Use when the user types /list-plan-roots or asks which projects are registered.
---

# List Plan Roots

Call the `list_plan_roots` MCP tool (provided by `winze`).

Display the results showing each registered root's name, URI, plans directory, file count, and watcher status.

If no roots are registered, suggest using `/register-plans` to add one.
