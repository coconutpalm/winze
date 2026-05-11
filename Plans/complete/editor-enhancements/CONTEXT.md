# Editor Enhancements — Context

## Goal

This document defines the implementation order for the five active editor
enhancement work items in `Plans/dev/`. Each is a standalone context/plan
pair; this document describes how they depend on each other and the
recommended sequencing.

## Work Items

| # | Work item | Plans | Scope |
|---|-----------|-------|-------|
| 1 | **Search History Navigation** | `SEARCH-HISTORY-NAV-*` | Back/forward navigation through search queries (toolbar arrows + keybindings) |
| 2 | **Language Tokenizers** | `EDN-TOKENIZERS-*` | Externalize syntax highlighting into `.lang` files; user-extensible |
| 3 | **Command Palette** | `COMMAND-PALETTE-*` | Scoped keybinding system, command registry, palette UI, prefix keys, keybinding presets |
| 4 | **Editor Commands** | `EDITOR-COMMANDS-*` | Formatting commands, insert commands, find/replace, wiki link registry, content assist popup, link preview |
| 5 | **Wiki Links** | `WIKI-LINKS-*` | `[[...]]` creation trigger, `wiki:uuid` link resolution, file creation, MOD1-click navigation |
| 6 | **Heading Folding** | `HEADING-FOLDING-*` | Fold/unfold at heading boundaries (optional, high complexity) |

## Dependency Graph

```
                    ┌─────────────────────┐
                    │ 1. Search History   │  (independent)
                    │    Navigation       │
                    └─────────────────────┘

                    ┌─────────────────────┐
                    │ 2. Language         │  (independent)
                    │    Tokenizers       │
                    └─────────────────────┘

                    ┌─────────────────────┐
                    │ 3. Command Palette  │  (independent)
                    └────────┬────────────┘
                             │ prerequisite
                    ┌────────▼────────────┐
                    │ 4. Editor Commands  │
                    │  (Phases 1-2: uses  │
                    │   command registry  │
                    │   + keybindings)    │
                    └────────┬────────────┘
                             │ Phase 3 provides content assist popup
                    ┌────────▼────────────┐
                    │ 5. Wiki Links       │
                    │  (Steps 1-4: MOD1-  │
                    │   click, styling —  │
                    │   independent)      │
                    │  (Steps 5+: [[...]] │
                    │   trigger needs     │
                    │   content assist)   │
                    └─────────────────────┘

                    ┌─────────────────────┐
                    │ 6. Heading Folding  │  (optional)
                    │  Needs: registry +  │
                    │  prefix keys (3) +  │
                    │  extract-headings   │
                    │  (4, Phase 3)       │
                    └─────────────────────┘
```

### Dependency details

| Depends on → | Provides | Needed by |
|-------------|----------|-----------|
| Command Palette (3) | Command registry, scoped keybinding dispatch, externalized `.keybinding` files, MOD1 convention | Editor Commands (4) Phase 1 Step 4 (wire registry into editor) |
| Command Palette (3) | `:when` predicates with `:active-popup` | Editor Commands (4) Phases 3-4 (content assist, find bar, link preview all need scoped Esc) |
| Editor Commands (4) | Content assist popup, wiki link registry (`:wiki/*` entities), `wiki:uuid` resolution | Wiki Links (5) Steps 5-6 (`[[...]]` trigger, `wiki:` URL handling) |
| Language Tokenizers (2) | `:lang` keyword in `.lang` files | Command Palette (3) `:when {:lang :clojure}` predicates (soft dependency — predicates work without it, just match nil) |
| Command Palette (3) + Editor Commands (4) | Registry + prefix keys + `extract-headings` (Phase 3, Step 6b) | Heading Folding (6) — fold regions calculated from heading positions; prefix keys for Mod1+K Mod1+0 |

### Independent items

- **Search History Navigation** (1) — no dependencies on any other work item.
  Small, self-contained, can be done at any time.
- **Language Tokenizers** (2) — no dependencies. Purely a refactoring of
  existing code into data files. Can be done at any time.
- **Wiki Links Steps 1-4** — MOD1-click navigation, link styling, dest
  extraction, cursor feedback. These only need SWT primitives already in
  the codebase. Can be done before or in parallel with the Command Palette.

## Recommended Implementation Order

### Round 1 — Foundations (parallelizable)

Do these three in parallel. They have no dependencies on each other and
together they establish the infrastructure everything else builds on.

**1a. Search History Navigation** — smallest item (~100 lines). Quick win that
improves daily usability immediately. Existing plan is complete and
implementation-ready.

**1b. Language Tokenizers** — medium item (~150 lines of loader + 9 `.lang`
files). Pure refactoring with zero functional change. Eliminates 693 lines
of boilerplate and makes syntax highlighting user-extensible. Low risk: each
`.lang` file is verified against existing RCF tests before the old code is
deleted.

**1c. Command Palette (Phase 1 only)** — the command registry, scope system,
keybinding loader, and scoped key dispatch. This is the foundation that
everything else plugs into. Does NOT include the palette UI yet — just the
registry + dispatch. The existing hardcoded keybindings (Esc, Mod1+E, Mod1+Z)
are migrated to `.keybinding` files and the Display filter delegates to the
new dispatch system.

**Why Phase 1 first**: The editor commands plan can't start wiring commands
into the registry until the registry exists. Starting the registry in Round 1
unblocks Round 2.

### Round 2 — Editor Core

**2a. Command Palette (Phase 2)** — the palette UI. Now that the registry
and dispatch exist (from Round 1), build the popup, fuzzy filter, and
platform-correct keybinding hints. This gives users a way to discover
commands even before many commands are registered.

**2b. Editor Commands (Phases 1-2)** — text manipulation primitives +
formatting/heading/list/line commands. These are the commands that populate
the registry. Each command is a pure function on a StyledText widget; the
registry wiring from Round 1 makes them immediately available via keyboard
and palette.

**2c. Wiki Links (Steps 1-4)** — MOD1-click navigation, link styling, dest
extraction, cursor feedback. These are independent of the command palette and
content assist. They add link interaction to the editor using the MOD1
convention established in Round 1.

**Why Round 2 is three items**: They're largely independent of each other
(palette UI doesn't need editor commands; editor commands don't need wiki
links). Doing them in parallel maximizes throughput. The only ordering
constraint within Round 2 is that 2b needs 2a's palette to be usable (but
not complete — commands work via keybindings even without the palette).

### Round 3 — Content Assist + Wiki Link Creation

**3a. Editor Commands (Phase 3)** — the content assist popup (wiki schema +
heading extraction + link popup + `(` trigger + Cmd+K + `wiki:uuid`
resolution). This is the most architecturally significant phase: it introduces
the `:wiki/*` Datalevin schema, the popup with HTML-rendered result cards,
semantic search prepopulation, and the wiki/Google mode switch.

**3b. Wiki Links (Steps 5-6)** — `[[...]]` trigger for content assist, file
creation from `[[New Topic]]`, and `wiki:` URL handling in the Browser view.
These depend on the content assist popup from 3a.

**Why Round 3 is sequential, not parallel**: Step 5 of wiki links literally
opens the same popup that Phase 3 of editor commands builds. They must be
done in order: 3a first, 3b second.

### Round 4 — Polish

**4a. Editor Commands (Phase 4)** — link preview on hover/cursor + find &
replace. These are self-contained overlay widgets that use the infrastructure
from earlier rounds.

**4b. Editor Commands (Phase 5)** — wiki link rename tracking (chunk-level
similarity matching to preserve UUIDs across heading renames). This is the
deepest backend change but has the narrowest UI surface.

**4c. Command Palette (Phase 3)** — startup wiring, preset activation, user
directory creation. Final integration polish.

### Round 5 — Optional

**5a. Heading Folding** — fold/unfold at heading boundaries. Separate work
item, see [HEADING-FOLDING-CONTEXT.md](HEADING-FOLDING-CONTEXT.md). Deferred
due to high SWT complexity. Evaluate whether the command palette + "Go to
heading" search covers the navigation use case well enough before investing.

## Summary

| Round | Items | Est. effort | Key deliverable |
|-------|-------|-------------|-----------------|
| 1 | Search History Nav + Language Tokenizers + Command Palette Phase 1 | Medium | Keybinding infrastructure + quick wins |
| 2 | Palette UI + Editor Commands Phases 1-2 + Wiki Links Steps 1-4 | Large | Full editor with formatting commands + link navigation |
| 3 | Editor Commands Phase 3 + Wiki Links Steps 5-6 | Large | Content assist + `[[...]]` creation + `wiki:uuid` links |
| 4 | Editor Commands Phases 4-5 + Palette Phase 3 | Medium | Link preview + find/replace + rename tracking |
| 5 | Heading Folding | Small | Folding (optional) |

## Rationale

The ordering is driven by three principles:

1. **Unblock downstream work early** — the command registry and scoped
   keybinding system (Round 1) are prerequisites for everything else. Getting
   them in first maximizes parallelism in subsequent rounds.

2. **Deliver value incrementally** — each round produces user-visible
   improvements. Round 1 gives externalized keybindings + search history +
   user-extensible syntax highlighting. Round 2 gives a full editor with
   formatting commands. Round 3 gives the wiki linking workflow.

3. **Minimize risk** — the hardest, most novel work (content assist popup,
   wiki link registry, rename tracking) is in Rounds 3-4, by which time the
   foundation is solid and well-tested. If any of these features proves too
   complex, the editor is still fully functional from Rounds 1-2.
