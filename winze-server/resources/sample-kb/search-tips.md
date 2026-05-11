---
tags: [search, tips, filters]
---
# Search Tips

## Semantic queries

Type natural language questions or phrases. Winze matches by meaning:

- `what caused the cache miss` — finds root-cause analyses even without those words
- `how to deploy to staging` — finds deployment procedures described differently
- `open questions about auth` — finds uncertainty or TODO sections on auth topics

Short queries work; so do long ones. You don't need to guess keywords.

## Tag filters

Prefix a token with `#` to filter by metadata:

| Filter | Example | Matches |
|--------|---------|---------|
| Status | `#active` | Active / in-progress docs |
| Status | `#complete` | Archived completed work |
| Status | `#deferred` | Deferred / backlog items |
| Doc type | `#context` | CONTEXT.md files |
| Doc type | `#plan` | PLAN.md files |
| Doc type | `#story` | Story / narrative docs |
| Jira key | `#AAO-42` | Docs mentioning that issue |

Combine filters with a query: `deploy #plan #active` finds active plan documents
about deployment.

## Home pages

Create a `home.md` file in your registered folder (or in its `Plans/`
subdirectory). Winze displays it as a landing page when the search bar is empty.
Use it as a table of contents, a status dashboard, or a "start here" guide.
See [Creating a Home Page](creating-a-home-md.md).

## From Claude Code

Use the `/search-plans` skill for semantic search without opening the UI:

```
/search-plans deploy to staging
/search-plans what caused the cache miss #context
```
