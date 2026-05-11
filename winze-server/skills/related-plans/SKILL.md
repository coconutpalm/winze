---
name: related-plans
description: Find all Plans/ documents in a work-item group and their cross-references. Use when the user types /related-plans or asks about documents related to a specific group.
---

# Related Plans

Call the `related_plans` MCP tool (provided by `winze`) with the group name the user provided after `/related-plans`.

## Usage

- `/related-plans gpu-report` — find all documents in the gpu-report group
- `/related-plans cache-gap-detect` — find all documents in the cache-gap-detect group

## Presenting results

Show the group's files with their types and statuses. If cross-references exist, show those too.

If no group name is provided, ask the user which group they want to look up.
