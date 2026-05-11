---
doc_type: context
status: complete
group: editor-readability
created: 2026-04-21
---

# Editor Readability — Context

Two small visual polish items for the StyledText markdown editor. Both
serve the same goal: reduce visual noise in the editor and bring its
look closer to the HTML preview rendered by the `Browser` viewer.

## Issues

1. **Header hash symbols are too loud.** In the editor, an H1 line like
   `# Title` renders the `#` glyphs in the same bright Lavender
   (`#C4B8FF`) as the title text. The hashes are syntax markup — they
   should recede, not compete with the heading text.
2. **Editor line pitch is too tight vs. the HTML preview.** The viewer's
   `.result-body` uses `line-height: 1.7` for paragraphs and lists,
   which gives lists and multi-line paragraphs real breathing room. The
   editor uses the font's default line pitch, so the same content looks
   visibly denser when switching between the two panes.

## Current Architecture

### Styling pipeline

All editor styling is data-driven through one flow, in
[`md_theme.clj`](../../../winze-server/src/llm_memory/ui/md_theme.clj)
and
[`markdown_editor.clj`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj):

1. [`md-theme/parse-blocks`](../../../winze-server/src/llm_memory/ui/md_theme.clj#L64)
   walks the source line-by-line and emits block spans
   (`:heading/hN`, `:blockquote`, `:bullet-item`, `:body`,
   `:code-block`, …). A heading currently emits a **single** span
   covering the full line — hash marker, space, and text.
2. [`md-theme/theme`](../../../winze-server/src/llm_memory/ui/md_theme.clj#L318)
   splits body and list-item blocks around inline spans (bold, italic,
   links, …). Headings are passed through untouched.
3. [`markdown-editor/type->style`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L23)
   maps each span `:type` to `{:font :fg :bg …}` drawn from the resource
   registry in [`resources.clj`](../../../winze-server/src/llm_memory/ui/resources.clj).
4. [`markdown-editor/span->style-range`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L52)
   converts each themed span to an SWT `StyleRange`, and
   [`apply-theme!`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L194)
   installs them on the widget via `setStyleRanges`.

### Line spacing

There is no call to `StyledText.setLineSpacing` anywhere in the
codebase — the editor inherits the font's natural leading. SWT exposes
two levers:

- **`setLineSpacing(int pixels)`** — uniform extra leading between every
  pair of lines.
- **`setLineSpacingProvider(StyledTextLineSpacingProvider)`** —
  per-line callback `(int line-index) -> int pixels`. This is the
  analogue of the viewer's discriminated CSS: `line-height: 1.7` on
  `.result-body` but `line-height: normal` on `.result-body pre` —
  see the [completed code-block-line-height work](../../complete/code-block-line-height/CONTEXT.md),
  which sets the precedent that code blocks get a tighter pitch than
  prose.

### HTML preview reference

Relevant rules in
[`search.clj`](../../../winze-server/src/llm_memory/ui/search.clj#L38)
`page-css`:

- `body`: `line-height: 1.6`
- `.result-body`: `line-height: 1.7` (the number to match)
- `.result-body pre`: `line-height: normal` (code blocks tight)
- `.result-body ul, ol`: `margin: 6px 0; li { margin: 2px 0; }`
- `.result-body p`: `margin: 6px 0`

The inter-paragraph/inter-list-item margins are a distinct effect from
`line-height` — the user's feedback "particularly in lists and
paragraphs" reads as *within*-item line-pitch (line-height), not
between-item margin. The editor has no notion of CSS margin, so this
plan targets line-height only; inter-block margin is deliberately out
of scope.

## Palette

Heading marker color needs to be dimmer than any current heading color.
Existing palette in
[`resources.clj`](../../../winze-server/src/llm_memory/ui/resources.clj#L229):

| Role              | Hex      | Used for                              |
|-------------------|----------|---------------------------------------|
| `color-lavender`  | `#C4B8FF`| H1 text                               |
| `color-amethyst`  | `#9B8FE0`| H2/H3 text                            |
| `color-deep-violet` | `#7B6FC0`| H4 text                             |
| `color-royal-purple` | `#5548A0`| H5/H6 text                         |
| `color-mine-shaft`| `#1E1B2E`| editor background                     |

`color-royal-purple` (`#5548A0`) is the dimmest purple already in the
palette and is a plausible marker color. If it still reads as "loud"
against mine-shaft, we may need a new muted entry (e.g. `color-marker`
around `#3A335E`, matching the find-bar tone). We decide after the
first screenshot in the REPL.

## Non-Goals

- No inter-block margin (would require a layout overhaul).
- No change to the viewer's CSS (the viewer is already the reference).
- No change to the bullet / numbered-item / checkbox rendering — those
  already have their own spacing dialed in by
  `apply-list-rendering!`.

## Verification

Every visual change screenshot-verified per
[`SWT-UI-GUIDE.md §15`](../../SWT-UI-GUIDE.md). RCF tests for the
parse-blocks split-heading emission. No integration with Jira — this is
small cosmetic polish.
