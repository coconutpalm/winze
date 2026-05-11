# The AI-Centric Planning Workflow

## The Problem: Context Windows Are Ephemeral

AI coding assistants like Claude Code are powerful, but every conversation
starts with a blank slate. When you begin a new session, the AI doesn't
know what you decided last week, why you chose approach A over B, or what
half-finished work is sitting in a branch. You end up re-explaining context,
and the AI ends up re-reading files it already analyzed in a prior session.

This workflow solves that problem by giving the AI **persistent, searchable
project memory** that survives across sessions.

## The Three-Layer Architecture

```
┌─────────────────────────────────────────────────────┐
│  Layer 3: Instructional Scaffolding                 │
│  CLAUDE.md teaches the AI *how* to use the system   │
│  "search first, index after writes, move on done"   │
├─────────────────────────────────────────────────────┤
│  Layer 2: Vector Search (MCP Server)                │
│  Datalevin + inference4j (all-MiniLM-L12-v2, 384d)  │
│  In-JVM embeddings — no external processes needed   │
│  Semantic retrieval — "find docs about caching"     │
├─────────────────────────────────────────────────────┤
│  Layer 1: Structured Markdown Files                 │
│  Plans/ directory with lifecycle & type conventions │
│  dev/ → complete/   |   CONTEXT, PLAN, STORY, ...   │
└─────────────────────────────────────────────────────┘
```

**Layer 1** is the foundation: a `Plans/` directory of markdown files
organized by lifecycle status (`dev/` for active work, `complete/` for
finished work) and document type (`CONTEXT` for background and decisions,
`PLAN` for implementation steps, and so on). You can use this layer
alone — it's just files. See [The `dev/` → `complete/` Lifecycle](#the-dev--complete-lifecycle)
and [Document Types](#document-types) below for the full conventions.

**Layer 2** makes those files searchable by meaning, not just keywords.
An MCP server embeds every document into a local vector database and
exposes semantic search to the AI. When the AI needs context, it searches
rather than reading files one by one. The system supports **multiple
project roots** — each project registers its own Plans/ directory and
gets scoped search results by default.

**Layer 3** is the secret sauce: instructions in your `CLAUDE.md` that
teach the AI the *protocol* for using the system. Without this layer,
the AI won't know to search before starting work, or to move files when
a task is done.

## The Workflow in Practice

### Start a New Task

Don't write CONTEXT and PLAN documents yourself — have the AI write
them *with* you. The goal is to get the AI's understanding on paper
where you can verify and correct it, rather than assuming it understood
your verbal description.

1. **Prompt the AI to create both documents together.** Describe the
   task conversationally, then ask the AI to write a CONTEXT doc
   (`Plans/todo/TOPIC-CONTEXT.md`) and a PLAN doc
   (`Plans/todo/TOPIC-PLAN.md`) at the same time. The CONTEXT is a
   briefing document — background, constraints, decisions, open
   questions — written for a future AI session that has no memory of
   this conversation. The PLAN is a step-by-step implementation plan
   concrete enough that a different session (or a human) could follow
   it without asking clarifying questions.

2. **Iterate on both documents together until both are right.** This
   is the most valuable part of the process — you're debugging the
   AI's mental model *before* it starts writing code. A wrong
   assumption caught in a planning document costs minutes; the same
   assumption caught after implementation costs hours.

   Creating both documents at once matters because they illuminate
   each other. The PLAN reveals what the AI *actually understood*
   from the CONTEXT — if it plans steps you didn't intend, that tells
   you the CONTEXT is misleading or incomplete. And when you need to
   explain *why* a plan step is wrong, the CONTEXT gives you a shared
   reference to point at: "look at constraint X in the CONTEXT — that
   rules out approach Y in step 3."

   Read both critically. Did the AI miss a constraint? Plan steps
   that don't match the stated goals? Gloss over a hard part? Push
   back on either document. Corrections to one often ripple into the
   other — fixing a misunderstanding in the CONTEXT changes which
   plan steps make sense, and challenging a plan step may reveal a
   gap in the CONTEXT that needs filling.

3. **(Optional) Have the AI create the Jira story.** If you have the
   Atlassian MCP server configured, ask the AI to write the Jira
   issue description and create the story in Jira — *after* you've
   finished iterating on the CONTEXT and PLAN. The order matters:
   by this point the AI has a thorough, vetted understanding of the
   work, so the story it writes is grounded in that understanding.
   The acceptance criteria naturally align with the steps in the
   PLAN, because the AI derived both from the same mental model.

4. **The filesystem watcher indexes new files automatically.** As soon
   as the AI writes a Plans/ file, the watcher detects the change and
   re-indexes it within a second. No manual indexing step needed.

This collaborative drafting process typically takes 5–10 minutes and
saves far more time downstream. It also creates a written record of
*why* the plan looks the way it does — something that's lost when you
just start coding.

### Start Implementation in a Clean Session

Once the CONTEXT and PLAN are solid, consider starting a **new session**
for implementation rather than continuing in the planning session.

The planning conversation may have consumed significant context window
— the AI read source files, explored alternatives, drafted and revised
documents, and discussed trade-offs with you. That's all valuable work,
but none of it needs to be in the context window during implementation.
The essential information has been distilled into the CONTEXT and PLAN
documents.

A fresh session starts with a clean context window. Point it at the
documents:

> "Read Plans/todo/TOPIC-CONTEXT.md and Plans/todo/TOPIC-PLAN.md, then
> start implementing."

The AI gets all the essential information — background, constraints,
decisions, and a step-by-step plan — without hundreds of messages of
exploratory conversation taking up space. This is especially valuable
when working with models that have smaller context windows (like Claude
Sonnet), where every token of context matters.

To the AI: This is a core payoff of externalizing knowledge into
documents: planning and implementation don't have to share a context
window.

For you: You get to code a lot more before the context window fills up
and the AI does more accurate work since you created a concrete and
specific plan before you started.

### Keep Documents Alive During Work

CONTEXT and PLAN documents aren't write-once artifacts. They should
evolve as you learn things during implementation.

**Periodically ask the AI to update them.** After a few steps of
implementation, or when you hit a surprise, prompt:

> "Update the CONTEXT doc with what we've learned so far."

> "Update the PLAN — mark steps 1–3 as done and revise step 4 based
> on what we discovered."

This matters for two reasons:

- **Session continuity.** If your context window fills up, or you need
  to start a fresh session, the updated documents capture the current
  state — not just the initial plan. The next session picks up where
  you left off without re-deriving what changed. Just as you can start
  a clean session after planning, you can start a clean session
  mid-implementation — as long as the documents are current.

- **Decision archaeology.** Implementation always reveals things the
  initial plan didn't anticipate: a library that doesn't work as
  expected, a constraint you didn't know about, a simpler approach
  that emerged. If these discoveries only live in the conversation,
  they're lost when the session ends. Written into the CONTEXT doc,
  they become permanent project knowledge.

A good rhythm: update after each major milestone or decision point,
and always before ending a session that will be continued later.

The filesystem watcher re-indexes updated files automatically — no
manual step needed.

### Resuming Work in a New Session

This is where the workflow pays off. Your prompt can be short:

> "Continue working on the cache unification task."

The AI's CLAUDE.md instructions tell it to search first, so it runs
`search_plans("cache unification")` and immediately recovers the full
context: what was decided, what's been done, what's left. Because you
kept the documents updated during work, the AI doesn't just recover
the *initial* plan — it recovers the *current* state, including
decisions made and lessons learned along the way.

No need to paste in background, no need to re-explain what changed
since the original plan.

### Complete a Task

1. Move all related files from `Plans/todo/` to `Plans/complete/<group>/`.
2. The filesystem watcher detects the file moves and updates the search
   index automatically, including re-inferring metadata (status changes
   from "active" to "complete"). If the content also changed, the fuzzy
   rename detector links the old and new files by embedding similarity.
3. Optionally run `/index-plans` to regenerate the INDEX.md and
   STATUS.md dashboard files. (The search index is already current from
   step 2 — this step only regenerates the static markdown summaries.)

The AI does this automatically when CLAUDE.md encodes the protocol.

### Optional: Jira Integration

If your team uses Jira, you can keep issue descriptions as local
markdown files in a `jira/` subfolder alongside your planning documents.
During active work they live in `Plans/todo/jira/AAO-123.md`; when the
task is completed they move to `Plans/complete/<group>/AAO-123.md` with
everything else.

This is powerful in combination with an MCP server that can talk to
Jira (such as the [Atlassian MCP server](https://github.com/anthropics/claude-code/tree/main/packages/mcp-servers)). With both in place,
the AI can:

- **Sync descriptions**: Write or update the Jira issue description
  from the local markdown file, keeping the canonical version in your
  Plans corpus where the AI can search it.
- **Comment on progress**: Post status updates to Jira as the work
  progresses, keeping the rest of the team informed without you
  switching to a browser.
- **Transition tickets**: Move issues through the Jira workflow
  (To Do → In Progress → Done) as part of the task completion protocol.

The local files give you traceability through the entire project
lifecycle. A completed feature group in `Plans/complete/` contains
not just what was planned and decided, but also the Jira issue
description that the broader team saw — all searchable, all in
version control, all movable together:

```
Plans/complete/cache-refactor/
├── CONTEXT.md         ← What we learned
├── PLAN.md            ← How we did it
├── STORY.md           ← User story
└── AAO-45.md          ← Jira issue description (synced to Jira)
```

This is entirely optional. The workflow works without Jira. But if
your team already uses Jira, this pattern means the AI can participate
in your existing project-management workflow rather than operating in
a silo.

### The `dev/` → `complete/` Lifecycle

```
Plans/
├── dev/                    ← Active work
│   ├── TOPIC-CONTEXT.md
│   ├── TOPIC-PLAN.md
│   ├── jira/               ← Jira issue descriptions (optional)
│   │   └── AAO-123.md
│   └── deferred/           ← Parked for later
├── complete/               ← Archived finished work
│   ├── topic-name/
│   │   ├── CONTEXT.md
│   │   ├── PLAN.md
│   │   ├── STORY.md
│   │   └── AAO-123.md      ← Jira file moves with the group
│   └── other-topic/
│       └── ...
└── reference/              ← Reusable procedures (always "active")
```

- **`dev/`** is the working set. Everything here is current.
- **`complete/`** is the archive. Organized by feature group in
  subdirectories. The AI can still search it — "how did we handle X
  last time?" — but it knows these aren't action items.
- **`reference/`** holds procedures you run repeatedly (validation
  scripts, deployment checklists).
- **`dev/deferred/`** is for work you've scoped but aren't doing yet.
- **`dev/jira/`** (optional) holds Jira issue descriptions for active
  work. On completion, these move into the feature group directory.

## Document Types

Each file has a conventional type, encoded in its filename:

| Type | Filename pattern | Purpose |
|------|-----------------|---------|
| **CONTEXT** | `*-CONTEXT.md` | Background, decisions, constraints. The briefing document. |
| **PLAN** | `*-PLAN.md` | Step-by-step implementation plan. |
| **STORY** | `*-STORY.md` | User story / Jira description. |
| **CODEMAP** | `*-CODEMAP.md` | Source file inventory mapping old → new code. |
| **REPORT** | `*-REPORT.md` | Validation results, run output. |

The AI can predict what kind of information a file contains from its
name alone, without reading it. This is a **type system for documents**.

## Why This Works

### For AI agents

- **Persistent memory**: Decisions, root causes, and design rationale
  survive across context windows. The AI doesn't re-derive what it
  already figured out.
- **Semantic search**: The AI finds relevant context by meaning, not
  exact keywords. "How do we handle rate limiting?" finds documents
  about retry backoff even if they don't use the phrase "rate limiting."
- **Structured metadata**: Status, type, and group filters let the AI
  ask precise questions like "what's actively in progress?" or "show me
  all completed caching work."
- **Self-maintaining**: The filesystem watcher keeps the index current
  in real time. CLAUDE.md instructions tell the AI to move completed
  work and regenerate dashboards — all without being asked.

### For humans

- **Readable without tools**: It's just markdown files in directories.
  You can browse them in your editor, on GitHub, or with `ls`.
- **Auto-generated dashboards**: `INDEX.md` and `STATUS.md` are
  regenerated on demand, so you always have a current table of
  contents and status overview.
- **Git-friendly**: Everything is plain text in version control.
  Diffs are meaningful. History is preserved.
- **Onboarding**: A new team member (or a new AI session) can get up
  to speed by searching the Plans corpus instead of reading months of
  Slack threads.

### Compared to alternatives

| Approach | Weakness this workflow addresses |
|----------|--------------------------------|
| Pasting context into every prompt | Doesn't scale; you forget things; wastes tokens |
| Relying on code comments | Comments explain *what*, not *why* or *what we tried and rejected* |
| Wiki / Confluence | AI can't search it efficiently; goes stale; separate from code |
| Git commit messages | Too granular; no narrative; can't search semantically |
| AI memory features | Opaque; not version-controlled; limited capacity |

## Tips

- **Write CONTEXT docs for your future AI sessions**, not for yourself.
  Include the "why" behind decisions, not just the "what."
- **Search before you start**. Even if you think you know the answer,
  the Plans corpus may contain a decision or root-cause analysis that
  changes your approach.
- **Keep CONTEXT docs updated** as you learn things during implementation.
  The document should reflect final understanding, not just initial research.
- **Don't over-document**. A CONTEXT + PLAN pair is usually enough.
  Add STORY/CODEMAP/REPORT only when they're genuinely useful.
