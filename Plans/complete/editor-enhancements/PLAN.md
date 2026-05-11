# Editor Enhancements — Plan

**See [EDITOR-ENHANCEMENTS-CONTEXT.md](EDITOR-ENHANCEMENTS-CONTEXT.md) for
the dependency graph, rationale, and round-by-round summary.**

This plan references steps and phases from the individual work item plans.
Each entry points to the specific plan section to execute.

---

## Round 1 — Foundations

These three items have no dependencies on each other. Work in parallel or
in any order.

### 1a. Search History Navigation

**Plan**: [SEARCH-HISTORY-NAV-PLAN.md](SEARCH-HISTORY-NAV-PLAN.md)

Execute all steps. Standalone, ~100 lines, quick win.

**Deliverable**: Back/forward arrows + Mod1+[/] keybindings for search history.

---

### 1b. Language Tokenizers

**Plan**: [EDN-TOKENIZERS-PLAN.md](EDN-TOKENIZERS-PLAN.md)

Execute Steps 1-6. Pure refactoring — no functional change.

**Deliverable**: 9 `.lang` files replace 6 hardcoded Clojure tokenizer
namespaces. `~/.winze/languages/` exists for user-contributed languages.

---

### 1c. Command Palette — Phase 1 (Registry + Scopes + Dispatch)

**Plan**: [COMMAND-PALETTE-PLAN.md](COMMAND-PALETTE-PLAN.md), Phase 1

Execute Steps 1-7:
- Step 1: Command registry (`commands.clj`)
- Step 2: Focus scope system + context + `eval-when` with specificity
- Step 3: Keybinding loader + index builder (prefix support, `.keybinding` files)
- Step 4: Scoped key dispatch (replace Display filter)
- Step 5: Annotate existing widgets with scope data
- Step 6: Register workbench commands (Esc hierarchy, toggle-mode, palette stub)
- Step 7: Create `default.keybinding`, `editor.keybinding`, preset files

**Deliverable**: Externalized keybindings with scoped dispatch. Esc backs up
one level through the UI hierarchy. Mod1+Z only works in editor scope.
Mod1+Shift+P is wired but palette UI is a stub.

---

## Round 2 — Editor Core

Items are largely independent within this round. Can be done in parallel.
The only soft constraint: the palette UI (2a) is more useful once editor
commands (2b) populate it, but keybindings work without the palette.

### 2a. Command Palette — Phase 2 (Palette UI)

**Plan**: [COMMAND-PALETTE-PLAN.md](COMMAND-PALETTE-PLAN.md), Phase 2

Execute Steps 8-11:
- Step 8: Palette Shell widget
- Step 9: Platform-correct keybinding hints (⌘/Ctrl+)
- Step 10: Filtering and display
- Step 11: Interaction (keyboard nav, mouse, dismiss)

**Deliverable**: Mod1+Shift+P and F3 open a fuzzy-filtered command palette
showing all available commands with keybinding hints.

---

### 2b. Editor Commands — Phases 1-2 (Formatting + Line Operations)

**Plan**: [EDITOR-COMMANDS-PLAN.md](EDITOR-COMMANDS-PLAN.md), Phases 1-2

Execute Steps 1-5:
- Step 1: Text manipulation primitives (`toggle-inline-wrap`, `toggle-line-prefix`, etc.)
- Step 2: Register inline formatting commands (bold, italic, etc.)
- Step 3: Register heading + list commands
- Step 4: Line operations (indent, move, delete, duplicate)
- Step 5: Simple insert commands (HR, code block, table, etc.)

**Deliverable**: Full set of editor formatting, heading, list, and line
commands — available via keybindings and command palette.

---

### 2c. Wiki Links — Steps 1-4 (MOD1-Click + Styling)

**Plan**: [WIKI-LINKS-PLAN.md](WIKI-LINKS-PLAN.md), Steps 1-4

Execute Steps 1-4:
- Step 1: Wiki-draft styling (dotted underline for `[[...]]`)
- Step 2: Extract link destinations in `md_theme.clj`
- Step 3: MOD1-click navigation + `navigate-link!` dispatch
- Step 4: Cursor feedback on hover

**Deliverable**: MOD1-click on any link navigates correctly. `[[...]]` text
shows dotted underline. Link destinations are tracked per editor for
hit-testing.

---

## Round 3 — Content Assist + Wiki Link Creation

These items are **sequential** — 3b depends on 3a.

### 3a. Editor Commands — Phase 3 (Content Assist + Wiki Schema)

**Plan**: [EDITOR-COMMANDS-PLAN.md](EDITOR-COMMANDS-PLAN.md), Phase 3

Execute Steps 6-11:
- Step 6: Wiki link schema + heading extraction + UUID generation
- Step 7: Make `result-card` / `page-css` public in `search.clj`
- Step 8: Content assist popup (`content_assist.clj`) with HTML result cards, wiki/Google mode switch
- Step 9: `(` trigger for content assist (semantic prepopulation from link text)
- Step 10: Insert Link command (Mod1+K)
- Step 11: `wiki:` URL resolution in navigation

**Deliverable**: Content assist popup opens on `[text](` and Mod1+K. Semantic
search prepopulates results. `http://` switches to Google search mode. `wiki:uuid`
links resolve via Datalevin. `:wiki/*` entities indexed for all headings.

---

### 3b. Wiki Links — Steps 5-6 (`[[...]]` Trigger + File Creation)

**Plan**: [WIKI-LINKS-PLAN.md](WIKI-LINKS-PLAN.md), Steps 5-6

Execute Steps 5-6:
- Step 5: `[[...]]` trigger for content assist (detect `[[`, open popup, rewrite to `[title](wiki:uuid)`, "Create new page" option)
- Step 6: `wiki:` URL resolution in Browser view

Also execute Steps 7-8 (wiki-link CSS + integration testing).

**Deliverable**: Type `[[topic]]` → popup opens → select or create → rewrites
to `[title](wiki:uuid)`. Click `[[...]]` creates files. `wiki:uuid` links
work in both view and edit modes.

---

## Round 4 — Polish

These items are independent within this round.

### 4a. Editor Commands — Phase 4 (Link Preview + Find/Replace)

**Plan**: [EDITOR-COMMANDS-PLAN.md](EDITOR-COMMANDS-PLAN.md), Phase 4

Execute Steps 12-13:
- Step 12: Link preview popup (`link_preview.clj`) — hover/cursor preview showing result card
- Step 13: Find/replace bar (`find_replace.clj`)

**Deliverable**: Hover over a `wiki:uuid` link → preview card appears.
Mod1+F opens find bar, Mod1+H opens find & replace.

---

### 4b. Editor Commands — Phase 5 (Rename Tracking)

**Plan**: [EDITOR-COMMANDS-PLAN.md](EDITOR-COMMANDS-PLAN.md), Phase 5

Execute Steps 14-16:
- Step 14: Snapshot old wiki state before re-indexing
- Step 15: Chunk-level similarity matching for heading renames (UUID preservation)
- Step 16: Wire into `index-file!` lifecycle

**Deliverable**: Rename a heading → UUID is preserved → all `wiki:uuid` links
still resolve. Delete a heading → UUID removed → links degrade to file-level.

---

### 4c. Command Palette — Phase 3 (Integration)

**Plan**: [COMMAND-PALETTE-PLAN.md](COMMAND-PALETTE-PLAN.md), Phase 3

Execute Steps 12-14:
- Step 12: Wire keybinding loading into startup
- Step 13: Register editor undo/redo as scoped commands
- Step 14: Ensure user directories exist

**Deliverable**: Cold restart works end-to-end. User `.keybinding` files
override defaults.

---

## Round 5 — Optional

### 5a. Heading Folding

**Plan**: [HEADING-FOLDING-PLAN.md](HEADING-FOLDING-PLAN.md)

Execute Steps 1-6 if the command palette + "Go to heading" search doesn't
cover the navigation use case adequately. High SWT complexity — evaluate need
before investing.

**Deliverable**: Mod1+Shift+[ toggles fold at cursor. Mod1+K Mod1+0 folds
all. Mod1+K Mod1+J unfolds all.

---

## Verification Checkpoints by Round

| Round | Checkpoint |
|-------|------------|
| 1 | Search history arrows work; `.lang` tokenizers pass all RCF tests; Esc backs up one level; keybindings load from `.keybinding` files |
| 2 | Mod1+B toggles bold; command palette opens with Mod1+Shift+P; MOD1-click navigates links; `[[text]]` shows dotted underline |
| 3 | `[text](` opens content assist with semantic results; `[[topic]]` creates file and rewrites link; `wiki:uuid` resolves in both modes |
| 4 | Hover preview shows result card; Mod1+F opens find bar; heading rename preserves UUID |
| 5 | Mod1+Shift+[ toggles fold; Mod1+K Mod1+0 folds all; Mod1+K Mod1+J unfolds all |
