---
tags: [metadata, conventions, frontmatter]
---
# Metadata Conventions

Winze infers metadata from file naming and directory conventions — no
frontmatter required for most files.

## Inferred fields

| Field | How it's inferred |
|-------|-------------------|
| `status` | `todo/` → active; `complete/` → complete; `deferred/` → deferred |
| `doc_type` | filename contains `CONTEXT` → context; `PLAN` → plan; `STORY` → story; etc. |
| `group` | parent directory name inside `complete/` or `todo/` |

Coverage: ~97% for status/type, ~94% for group.

## Supported doc_type values

`context`, `plan`, `story`, `report`, `codemap`, `results`, `jira`, `info`,
`index`, `tracker`

## Overriding with frontmatter

Add a YAML block at the top of any `.md` file to override inferred values or
add fields Winze can't infer:

```yaml
---
created: 2026-01-15
tags: [deployment, infra]
related:
  - Plans/todo/INFRA-CONTEXT.md
supersedes: Plans/complete/old-deploy/PLAN.md
---
```

Supported frontmatter fields:

| Field | Type | Notes |
|-------|------|-------|
| `created` | ISO date string | Displayed in result cards |
| `tags` | list of strings | Filterable via `#tag` in search |
| `related` | list of paths | Cross-reference links |
| `supersedes` | path string | Points to the document this replaces |
| `status` | string | Override inferred status |
| `doc_type` | string | Override inferred doc type |
| `group` | string | Override inferred group |

## Naming conventions

The simplest way to get correct metadata is to follow the naming patterns:

```
Plans/
  todo/
    MY-FEATURE-CONTEXT.md    ← status=active, type=context, group=MY-FEATURE
    MY-FEATURE-PLAN.md       ← status=active, type=plan,    group=MY-FEATURE
  complete/
    my-feature/
      CONTEXT.md             ← status=complete, type=context, group=my-feature
      PLAN.md                ← status=complete, type=plan,    group=my-feature
  deferred/
    BIG-IDEA-PLAN.md         ← status=deferred, type=plan
```
