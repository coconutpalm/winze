# Heading-Level Folding — Context

## Goal

Add fold/unfold support to the Winze StyledText editor at heading boundaries.
Folding collapses the content between one heading and the next heading of
equal-or-higher level, leaving only the heading line visible. This lets users
focus on sections of long documents without scrolling.

## Current State

The editor has no folding capability. Users navigate long documents by
scrolling or (once implemented) using the command palette's "Go to heading"
search.

## Design Constraints

### SWT StyledText has no built-in folding

Unlike Eclipse's `ProjectionViewer` (which requires a full `SourceViewer`
stack with document partitioning, annotation models, and projection support),
`StyledText` is a raw text widget with no native folding API. Any folding
implementation must be built from primitives:

- **`.setLineBackground`** — can visually indicate collapsed regions
- **`.replaceTextRange`** — can hide/show content by removing/reinserting text
- **Line hiding** — `StyledText` has no `.setLineVisible()` method (unlike
  some other widget toolkits)

### Approach options

**Option A — Text replacement**: Remove folded lines from the widget text,
store them in a side buffer, reinstate on unfold. This is conceptually simple
but breaks:
- Undo history (undo would need to understand folding operations)
- Line numbers (everything below a fold shifts)
- Search/find (hidden text can't be found)
- Auto-save (must save the full unfolded content, not the displayed text)

**Option B — Projection overlay**: Use a custom `StyledTextContent` that
presents a filtered view of the underlying document. Fold/unfold operations
modify the projection, not the source text. This is what Eclipse does, but
`StyledTextContent` is a complex interface (~30 methods) and the interaction
with our existing theme/styling pipeline would need careful design.

**Option C — Visual collapse only**: Keep all text in the widget but set
folded lines' height to zero (not possible in StyledText) or set their
content to empty strings (breaks the document). Not viable.

**Option D — Summary line**: Replace folded content with a single summary
line (e.g., `## Design Decisions ▸ (14 lines)`). This is a variant of
Option A with a placeholder. Same undo/line-number problems.

### Recommendation

Option B (projection) is the most correct but the most complex. Option A
(text replacement) is the most pragmatic for a first pass — the key
mitigations are:
- Auto-save always writes the full unfolded content (save from a shadow
  buffer, not from the widget text)
- Undo is disabled while folds are active, or fold/unfold operations are
  tracked as undoable actions in the history
- Find/replace operates on the full document (unfold-all before find)

**Complexity assessment**: High. This is the riskiest item in the editor
enhancement suite. Consider whether the command palette + "Go to heading"
search covers the navigation use case well enough before investing in folding.

## Commands

| Command ID | Label | Hotkey |
|-----------|-------|--------|
| `:editor/toggle-fold` | Toggle Fold at Cursor | Mod1+Shift+[ |
| `:editor/fold-all` | Fold All | Mod1+K Mod1+0 (prefix key) |
| `:editor/unfold-all` | Unfold All | Mod1+K Mod1+J (prefix key) |

Fold All and Unfold All use prefix keys (requires the prefix key support from
[COMMAND-PALETTE-CONTEXT.md](COMMAND-PALETTE-CONTEXT.md)).

## Files to Modify

| File | Change |
|------|--------|
| `winze-server/src/llm_memory/ui/markdown_editor.clj` | Fold state tracking, text replacement/reinstatement, fold region calculation |
| `winze-server/src/llm_memory/ui/commands.clj` | Register fold commands |
| `winze-server/resources/keybindings/editor.keybinding` | Add fold keybindings (prefix keys) |

## Dependencies

- **Command registry + keybinding system** — from
  [COMMAND-PALETTE-CONTEXT.md](COMMAND-PALETTE-CONTEXT.md). Fold commands
  register into the registry; keybindings use prefix keys.
- **Heading parsing** — `chunk.clj:extract-headings` (from
  [EDITOR-COMMANDS-PLAN.md](EDITOR-COMMANDS-PLAN.md) Phase 3, Step 6b)
  provides the heading positions needed to calculate fold regions.

## Related Work

- **editor-commands** — the broader editor enhancement that this was originally
  part of. See [EDITOR-COMMANDS-CONTEXT.md](EDITOR-COMMANDS-CONTEXT.md).
- **editor-enhancements** — the umbrella sequencing document. Folding is
  Round 5 (optional). See
  [EDITOR-ENHANCEMENTS-CONTEXT.md](EDITOR-ENHANCEMENTS-CONTEXT.md).
- **command-palette** — provides the prefix key infrastructure for Mod1+K
  Mod1+0 and Mod1+K Mod1+J. See
  [COMMAND-PALETTE-CONTEXT.md](COMMAND-PALETTE-CONTEXT.md).

## Risks

- **High SWT complexity**: Line-number bookkeeping, undo interaction, and
  search integration are all fragile. Off-by-one errors cause scroll/caret
  bugs that are hard to diagnose.
- **Undo interaction**: Folding operations that modify widget text conflict
  with the existing undo history. May need a separate undo track or undo
  suspension during folds.
- **Auto-save interaction**: Auto-save must write the full unfolded content.
  If folds modify the widget text, the auto-save callback must read from a
  shadow buffer rather than `(.getText styled-text)`.
- **Moderate value for high effort**: The command palette's "Go to heading"
  search may cover the primary navigation use case, making folding less
  critical than it appears.
