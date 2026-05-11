---
tags: [home-page, setup, markdown]
---
# Creating a Home Page

Winze shows a **home page** in the Live Search tab when no query is active.
The home page comes from a `home.md` file in your registered folder.

## Where to put it

Winze looks for `home.md` in these locations (first match wins):

1. Directly inside your registered folder: `my-project/home.md`
2. Inside a `Plans/` subdirectory: `my-project/Plans/home.md`

If no `home.md` exists, Winze shows a hint with the path where you can create one.

## What to put in it

Anything useful as a landing page for your project. Common patterns:

**Table of contents** — links to key documents using wiki-style links:
```markdown
- [Architecture overview](wiki:myproject::architecture/CONTEXT.md)
- [Current sprint](wiki:myproject::todo/sprint-42.md)
```

**Status dashboard** — a table of in-progress work items with their Jira keys.

**Start-here guide** — onboarding notes for a new contributor (or future you).

**Tag shortcuts** — clickable `#tag` links that pre-fill the search bar:
```markdown
Click [#active](#active) to see all in-progress work.
```

## YAML frontmatter

Add frontmatter at the top of `home.md` to give it tags that appear as
clickable filter buttons in the viewer:

```yaml
---
tags: [active, todo]
---
```

This file itself is an example — it ships inside Winze's sample knowledge base.
