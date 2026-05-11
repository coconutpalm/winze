---
created: 2026-04-10
doc_type: context
group: styled-list-rendering
status: complete
tags: [swt, ui, styledtext, editor, markdown, lists]
related: [editor-enhancements, editor-commands]
---

> **Archived as complete.** Steps 1–4 shipped; Steps 5–6 are explicitly
> future refinements and will be split into their own todo when picked up.
> Current active Winze UI work is tracked in
> [`todo/CONTENT-ASSIST-WHITE-BAND-PLAN.md`](../../todo/CONTENT-ASSIST-WHITE-BAND-PLAN.md).

# Styled List Rendering — Context

## Goal

Replace the plain-text rendering of markdown lists in the StyledText editor
with visually rich list rendering that matches the HTML view. Use SWT's
native `Bullet`, `setLineBullet`, and `setLineWrapIndent` APIs to display:

- Bullet lists (`- `, `* `, `+ `) with a `•` glyph instead of the ASCII marker
- Numbered lists (`1. `, `2. `, etc.) with rendered numbering
- Checkbox lists (`- [ ] `, `- [x] `) with `☐` / `✓` glyphs
- Hanging indent: wrapped continuation lines align with the text after the bullet
- Nested lists indent visually to match each nesting level

The source text is **never modified** — all rendering is purely visual via
StyledText's line-level APIs.

## Implementation (completed)

### Critical discovery: bullet glyph always renders at x=0

**SWT's `setLineBullet` renders the bullet glyph at the left edge of the
line (x=0), regardless of `setLineIndent`.** `setLineIndent` only shifts
the text content, not the bullet. This means `setLineIndent` **cannot** be
used for bullet indentation — the bullet would stay at x=0 while the text
moves right.

**Solution**: Encode ALL indent spacing into the bullet's `GlyphMetrics.width`
and pad the bullet text with leading spaces. The bullet renders at x=0 but
the space characters push the visible glyph (`•`, `1.`, `☐`, `✓`) to the
correct visual position. `setLineIndent` is left at 0 for all list lines.

### Hiding the source marker text

The source marker (`- `, `* `, `1. `, `- [ ] `, etc.) remains in the text
buffer. After the bullet glyph renders, the marker text would appear after
it — causing visual duplication.

**Solution**: Apply `GlyphMetrics(0, 0, 0)` on the marker's `StyleRange`
to collapse it to **zero pixel width**. Combined with `foreground = background`
(invisible), the marker occupies no horizontal space. This is critical for
correct hanging indent alignment — if the marker had non-zero width, content
position would vary by marker text width (e.g. `"- [ ] "` is wider than
`"- "`), breaking vertical alignment.

Earlier attempts used `foreground = background` alone (invisible but
non-zero width). This caused content to start at different x-positions for
different marker types, because the hidden marker text still occupied its
rendered width. `GlyphMetrics(0,0,0)` eliminates this entirely.

### Hanging indent alignment

`setLineWrapIndent(line, 1, total-glyph-w)` sets where wrapped continuation
lines start. `total-glyph-w` is the measured pixel width of the full padded
bullet text (spaces + glyph), which equals the x-position where content
starts on the first line. Since the hidden marker is zero-width, wrapped
text aligns exactly with the first visible content character.

### Font/platform portability

**All pixel values are computed from font metrics at runtime.** No hardcoded
pixel sizes anywhere:

| Value | Derivation |
|-------|-----------|
| `base-indent` | `2.5 × avgCharWidth` — base margin before any bullet |
| `indent-step` | `2.5 × avgCharWidth` — additional indent per nesting level |
| `pad-spaces` | `pre-bullet / spaceAdvanceWidth` — number of spaces for indent |
| `total-glyph-w` | `GC.stringExtent(padded-text).x` — **measured** rendered width |
| `raw-glyph-w` | `GC.stringExtent(raw-text).x` — rendered bullet text width |

The `GC` is configured with the editor's actual font before measuring.
`total-glyph-w` is measured from the actual padded string via `stringExtent`,
not estimated from character count × character width. This handles
proportional fonts, kerning, and platform-specific rendering correctly.

### Bullet types and colors

| Type | Glyph | Color | Source |
|------|-------|-------|--------|
| `:bullet-item` | `• ` | Amethyst `#9B8FE0` | brand accent |
| `:numbered-item` | `1. `, `2. `, etc. | Deep Violet `#7B6FC0` | brand secondary |
| `:checkbox-unchecked` | `☐ ` | Deep Violet `#7B6FC0` | subtle |
| `:checkbox-checked` | `✓ ` (U+2713) | Green `#66BB6A` | matches search card status-complete |

`ST/BULLET_TEXT` is used for ALL types (not `ST/BULLET_NUMBER`) because
`ST/BULLET_NUMBER` auto-increments globally and cannot restart per-list.

### Block detection in `parse-blocks`

`md_theme.clj:parse-list-item` detects list lines via regex, returning:

| Key | Meaning |
|-----|---------|
| `:type` | `:bullet-item`, `:numbered-item`, `:checkbox-unchecked`, `:checkbox-checked` |
| `:indent` | Nesting level (`leading-spaces ÷ 2`) |
| `:marker-len` | Total length: leading spaces + marker (e.g. `"  - "` = 4) |
| `:bullet-len` | Marker only, no leading spaces (e.g. `"- "` = 2) |
| `:number` | Ordinal for numbered items |

Detection order (most specific first): checkbox → numbered → bullet.
Checkbox regex uses `#{"x" "X"}` for the check character (regex groups
return **strings**, not characters — a bug caught during implementation).

### Inline formatting within list items

List blocks pass through `find-inline-spans` + `split-around` in the
`theme` function, so bold, italic, code, and links render correctly within
list items. The list metadata (`:indent`, `:marker-len`, `:bullet-len`,
`:number`) is propagated to all fragments via `select-keys` + `merge`.

### Reset on re-theme

`apply-list-rendering!` clears all previous bullet/indent state before
re-applying:

```clojure
(.setLineBullet st 0 lc nil)
(.setLineIndent st 0 lc 0)
(.setLineWrapIndent st 0 lc 0)
```

### Files modified

| File | Change |
|------|--------|
| `md_theme.clj` | `parse-list-item` + list detection in `parse-blocks` + list routing in `theme` |
| `markdown_editor.clj` | `apply-list-rendering!` called from `apply-theme!` |
| `resources.clj` | `color-check-green` (`#66BB6A`) |

## Lessons Learned

### `setLineIndent` does NOT indent bullets

This was the most time-consuming discovery. `setLineIndent` shifts text
content but the bullet glyph always renders at x=0 (the left edge of the
widget). The only way to visually indent a bullet is to pad the bullet text
string with spaces and set `GlyphMetrics.width` to cover the full padded
extent.

### `GlyphMetrics(0, 0, 0)` collapses text to zero width

Applying a `StyleRange` with `GlyphMetrics(0, 0, 0)` on the hidden marker
characters makes them occupy zero horizontal pixels. This is essential for
consistent content alignment — without it, the hidden marker text (which
varies in width between `"- "`, `"1. "`, `"- [ ] "`, `"- [x] "`) shifts
content to different x-positions.

### `GlyphMetrics` on `StyleRange` is required for `Bullet`

`Bullet`'s `StyleRange` must have non-null `.metrics` or SWT throws
`IllegalArgumentException: Argument cannot be null`. The `.font` must also
be set.

### Measure, don't estimate

Early iterations estimated pixel widths from character counts
(`marker-len × avgCharWidth`). This produced misalignment because:
- Characters have different widths in proportional fonts
- `☐` (15px) and `✓` (14px) differ by 1px despite being "one character"
- Source markers like `"- [ ] "` (23px) and `"- [x] "` (26px) differ
  because `x` is wider than space

The fix: always use `GC.stringExtent(text).x` to measure actual rendered
pixel widths. This works correctly across fonts, platforms, and DPI scales.

### `SWT/BULLET_NUMBER` is unusable for markdown

`ST/BULLET_NUMBER` auto-increments from the first numbered line to the last,
ignoring list boundaries and nesting. There's no way to restart numbering.
Use `ST/BULLET_TEXT` with explicit number strings (`"1. "`, `"2. "`, etc.)
instead.

### Checkbox `:when` check — strings not characters

Regex capture groups return `String`, not `Character`. The initial
implementation used `(#{\x \X} check)` which always returned nil.
Fix: `(#{"x" "X"} check)`.

## Future Enhancements

- **Continuation lines**: Lines that are part of a list item but don't have
  their own marker (indented text under a bullet). These should get matching
  indent/wrap-indent but no bullet glyph.
- **Blockquote + list interaction**: Lists inside blockquotes need additive
  indent.
- **Cursor visual cue in hidden markers**: When the cursor enters the
  invisible marker area, show a subtle indicator.
