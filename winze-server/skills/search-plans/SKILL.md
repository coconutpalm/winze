---
name: search-plans
description: Search Plans/ markdown documents by semantic similarity using the planning MCP server. Use when the user types /search-plans or asks to search planning documents.
---

# Search Plans

Call the `search_plans` MCP tool (provided by `winze`) with the query the user provided after `/search-plans`.

## Default parameters

- `n_results: 8` unless the user specifies a different number
- `detail: "full"` for agent use (complete chunk text)
- `dedupe: true` to get diverse results across files

## Presenting results

For each result, show the source file path and relevance badge as a header,
then the matching text. Do not truncate file paths.

If the query is empty, ask the user what they want to search for.
