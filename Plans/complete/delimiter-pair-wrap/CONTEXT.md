---
name: Delimiter-pair wrap — context
description: Auto-wrap selection with matching open/close delimiter when the user types an opener key
type: context
---

# Delimiter-Pair Wrap — Context

## Goal

Tracked in
[`Plans/todo/active-issues.md`](../active-issues.md) →
*Editor → "Some punctuation types naturally occur in pairs…"*.

When a selection is active in the Markdown editor and the user types a
**single character** that is recognised as a pair delimiter, replace the
default behaviour (which in SWT `StyledText` would overwrite the
selection with that single character) with a **wrap**:

1. Insert the *opener* before the selection.
2. Insert the matching *closer* after the selection.
3. Leave the original text selected (between the delimiters) so the user
   can keep typing, keep wrapping, or release the selection.

If there is no active selection, typing the same character keeps its
default behaviour — a plain character insert. This preserves every
existing interaction (including the `(` / `[` content-assist triggers
installed in
[`markdown_editor.clj:684-713`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L684-L713)).

## Delimiter Pairs

### Natural-language pairs (brackets / quotes)

Single-character opener → closer:

| Opener | Closer | Name                              |
|--------|--------|-----------------------------------|
| `(`    | `)`    | Parentheses / round brackets      |
| `[`    | `]`    | Square brackets                   |
| `{`    | `}`    | Curly braces                      |
| `<`    | `>`    | Angle brackets                    |
| `"`    | `"`    | Double quote (straight ASCII)     |
| `'`    | `'`    | Single quote / apostrophe (ASCII) |
| `` ` `` | `` ` `` | Backtick                         |

Closers (`)`, `]`, `}`, `>`) are **not** themselves wrap triggers — the
user types the *opener*. Symmetric quote characters (`"`, `'`, `` ` ``)
are their own closer.

### Markdown-specific pairs (single-character inline markers)

| Opener | Closer | Markdown meaning        |
|--------|--------|-------------------------|
| `*`    | `*`    | Italic (emphasis)       |
| `_`    | `_`    | Italic (emphasis, alt.) |
| `` ` `` | `` ` `` | Inline code (already listed above — same character) |

Multi-character markers (`**` bold, `__` bold, `~~` strikethrough, `==`
highlight, `$$` math block) are **out of scope for v1** because the
feature is triggered by one keystroke. Those continue to be served by
the existing toggle commands registered in
[`editor_commands.clj:569-583`](../../../winze-server/src/llm_memory/ui/editor_commands.clj#L569-L583)
(`Mod1+B`, `Mod1+I`, etc.).

### Deliberately excluded

- Smart / curly quotes (`“”`, `‘’`, `«»`, `「」`, …). The user types
  straight ASCII; IME or smart-quote substitution is the OS's job.
- `|` (table cell separator) — not a paired delimiter.
- `/`, `\`, `~` — not conventionally paired as single chars.
- Fenced code blocks (triple-backtick). Different semantics (line-level,
  triple-char).

## Current Architecture

### Where the feature fits

The Markdown editor is a `StyledText` built by the CDT init function
[`markdown-editor` in markdown_editor.clj:715](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L715).
At construction time it installs:

- An `on e/modify-text` handler that re-themes on every change and
  schedules the debounced save (line 735-747).
- `install-link-interaction!` — link-click handling (line 760).
- `install-content-assist-triggers!` — the `KeyListener` that fires
  *after* `(` or `[` is inserted, and schedules content-assist
  asynchronously (line 684-713, 761).

### What exists for wrapping selection

[`editor-commands/toggle-inline-wrap`](../../../winze-server/src/llm_memory/ui/editor_commands.clj#L58-L106)
already wraps a selection with a **symmetric** delimiter string and
returns a pure edit-result map consumed by `apply-edit!`. It also
handles unwrap (selection already delimited) and no-selection insert.
It's wired to commands (`:editor/toggle-bold`, etc.) but not to
single-key typing.

For this feature we need:

- **Asymmetric** pairs (`(` / `)`, `[` / `]`, etc.) — `toggle-inline-wrap`
  assumes opener == closer, so it can't be reused directly.
- A **pre-insertion** hook (so we can cancel the default
  replace-selection-with-char behaviour) — the existing
  `install-content-assist-triggers!` uses `KeyListener`, which fires
  *after* insertion. We need `StyledText.addVerifyKeyListener`, whose
  `event.doit = false` cancels the keystroke.

### How `StyledText` key handling composes

- `VerifyKeyListener` (StyledText-specific) fires first; setting
  `event.doit = false` prevents the default character insert/replace.
- `KeyListener` still fires regardless — but the existing content-assist
  triggers schedule their work via `async-exec!` and re-read text +
  caret state on the UI thread after the event cycle. If the wrap has
  already rewritten the buffer, their preconditions
  (`char-before-caret == \(`) no longer hold, so they silently no-op.
  This composition is intentional and requires no changes to
  `install-content-assist-triggers!`.

### Undo / redo and theming

- `.replaceTextRange` fires `ModifyEvent`, which triggers
  `apply-theme!` and the debounced save/undo snapshot in
  [markdown_editor.clj:735-747](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L735-L747).
  The wrap reuses that path — no extra theming, no extra
  undo-history plumbing.
- Undo granularity will match any other single `replaceTextRange` edit.

## Design Constraints & Decisions

1. **V1 = wrap only, no unwrap on keystroke.** If the user selects
   `"hello"` (including the quotes) and types `"`, the result is
   `""hello""` — explicit wrap. Unwrap via keystroke is deliberately
   not implemented: it's context-dependent and surprising. Unwrapping
   remains available via the existing `:editor/toggle-bold`,
   `:editor/toggle-italic`, etc. commands for the symmetric cases.

2. **Only plain keys trigger.** Any of `SWT.MOD1`, `SWT.MOD2`,
   `SWT.MOD3`, `SWT.CTRL`, `SWT.ALT`, `SWT.COMMAND` in `stateMask` →
   do not wrap. Shortcut routing (Mod1+B, etc.) is owned by the
   scoped keybinding system in
   [`keybindings.clj`](../../../winze-server/src/llm_memory/ui/keybindings.clj).
   `SHIFT` is allowed (required for `(`, `{`, `<`, `"` on US layouts).

3. **Empty selection → fall through.** The listener returns without
   setting `event.doit = false`, so the default char insert happens
   and the existing content-assist `(` / `[` trigger fires
   unchanged.

4. **Selection retained after wrap.** Post-wrap selection covers the
   *inner* text (not the delimiters), matching
   `toggle-inline-wrap`'s existing behaviour and enabling chained
   wraps (`"`, then `(`, then `[`, …).

5. **Scope: Markdown editor `StyledText` only.** Do not install on the
   search box, command palette, find-bar, or any other `Text` /
   `StyledText`. Those widgets either don't use delimiters or have
   different expectations.

6. **Pure core + thin SWT shell.** Delimiter-pair resolution and the
   edit-result computation are pure functions that take a map
   (`{:text :sel-start :sel-length :char}`) and return an edit map —
   unit-testable via RCF without a `Display`. The SWT listener is a
   thin adapter.

## Related Prior Work

- Symmetric-wrap command implementation:
  [`editor_commands.clj:58-106`](../../../winze-server/src/llm_memory/ui/editor_commands.clj#L58-L106).
  Tests:
  [`editor_commands.clj:803+`](../../../winze-server/src/llm_memory/ui/editor_commands.clj#L803).
- Post-insertion key trigger pattern (content-assist):
  [`markdown_editor.clj:684-713`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L684-L713).
- `apply-edit!` edit-result executor:
  [`editor_commands.clj:512-544`](../../../winze-server/src/llm_memory/ui/editor_commands.clj#L512-L544).
- SWT threading rules: [`Plans/SWT-UI-GUIDE.md`](../../SWT-UI-GUIDE.md).

## Success Criteria

- Selecting any non-empty range in the editor and typing one of the
  listed opener characters wraps the selection with the opener +
  closer pair, leaves the inner text selected, and fires no other
  unintended behaviour (no content-assist popup, no double-insert).
- Typing any of the listed characters with **no** selection is
  unchanged — plain character insert, including the existing `(` and
  `[[` content-assist triggers.
- Typing a modified key (`Mod1+B`, `Mod1+(`, etc.) is unchanged —
  still routed to commands or ignored.
- Visual verification via `llm-memory.ui.util/screenshot-widget!`
  after exercising each pair interactively. REPL-level verification
  via `(tests ... :rcf)` for the pure edit logic.
