---
name: register-plans
description: Register a project's planning documents directory with the Plan Server for indexing and search. Use when the user types /register-plans, when search returns no results for a new project, or when the proxy logs "root not yet registered".
---

# Register Plans

Call the `register_plans` MCP tool (provided by `winze`) to register the current project's planning documents directory.

## Arguments

- `/register-plans` — register with default directory `Plans/`
- `/register-plans docs` — register with `docs/` as the plans directory
- `/register-plans planning/notes` — any relative path from the project root

The directory must exist and contain `.md` files.

## When to use automatically

If a `search_plans` or other plans tool returns an error about the root not being registered, call `register_plans` with the appropriate directory before retrying.

## After registration

Report back the registration result (how many files were indexed).
