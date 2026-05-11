---
name: recent-plans
description: List recently modified Plans/ documents. Use when the user types /recent-plans or asks what planning documents changed recently.
---

# Recent Plans

Call the `recent_plans` MCP tool (provided by `winze`) with the arguments the user provided.

## Default parameters

- `days: 7` unless the user specifies a different number
- Parse `/recent-plans 3` as `days: 3`
- Parse `/recent-plans 14 context` as `days: 14, doc_type: "context"`

## Presenting results

Show the formatted list of recently modified files with their modification dates and metadata.
