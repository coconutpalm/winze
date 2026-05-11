---
created: 2026-04-10
doc_type: plan
group: styled-list-rendering
status: complete
tags: [swt, ui, styledtext, editor, markdown, lists]
related: [editor-enhancements, editor-commands]
---

> **Archived as complete.** Steps 1–4 shipped; Steps 5–6 are explicitly
> future refinements and will be split into their own todo when picked up.
> Current active Winze UI work is tracked in
> [`todo/CONTENT-ASSIST-WHITE-BAND-PLAN.md`](../../todo/CONTENT-ASSIST-WHITE-BAND-PLAN.md).

# Styled List Rendering — Plan

**See [CONTEXT.md](CONTEXT.md) for
architecture, SWT API discoveries, and lessons learned.**

**Status: Steps 1-4 complete. Steps 5-6 are future refinements.**

---

## Step 1 — Extend `parse-blocks` to detect list items ✓

**File**: `winze-server/src/llm_memory/ui/md_theme.clj`

Added `parse-list-item` function and integrated into `parse-blocks`.

Block types: `:bullet-item`, `:numbered-item`, `:checkbox-unchecked`,
`:checkbox-checked`. Each carries `:indent`, `:marker-len`, `:bullet-len`,
and optionally `:number`.

**Key detail**: Checkbox regex check character comparison uses **strings**
(`#{"x" "X"}`), not characters — regex groups return `String`.

---

## Step 2 — Route list blocks through inline detection ✓

**File**: `winze-server/src/llm_memory/ui/md_theme.clj`

Added list type cases to `theme` function alongside `:body`. List blocks
pass through `find-inline-spans` + `split-around`. List metadata
(`:indent`, `:marker-len`, `:bullet-len`, `:number`) is propagated to
all fragments via `select-keys` + `merge`.

---

## Step 3 — Create `apply-list-rendering!` ✓

**File**: `winze-server/src/llm_memory/ui/markdown_editor.clj`

### Final implementation (after iterating through several approaches)

**Approach that works**: Encode all indent spacing into the bullet's
`GlyphMetrics.width` and pad the bullet text with leading spaces.
`setLineIndent` is NOT used (bullets always render at x=0).

```
Layout per line:
[pad-spaces • ][content text that may wrap to...]
                [continuation aligned here     ]
```

**Key implementation details**:

1. `base-indent = 2.5 × avgCharWidth` — base margin before any bullet
2. `indent-step = 2.5 × avgCharWidth` — per nesting level
3. `pre-bullet = base-indent + (indent × indent-step)` — total indent
4. `pad-spaces = pre-bullet / spaceAdvanceWidth` — space characters for padding
5. `padded-text = (repeat pad-spaces \space) + raw-text` — e.g. `"    • "`
6. `total-glyph-w = GC.stringExtent(padded-text).x` — **measured** width
7. `GlyphMetrics(0, 0, total-glyph-w)` — reserves this width for the bullet
8. `setLineWrapIndent(line, 1, total-glyph-w)` — continuation lines align here
9. Hidden marker: `GlyphMetrics(0, 0, 0)` + `foreground = background`

### Approaches that DON'T work

- **`setLineIndent` for nesting**: Bullet renders at x=0 regardless of
  lineIndent. Only text shifts.
- **`GlyphMetrics.width = marker-len × avgCharWidth`**: Over-estimates for
  some types (checkboxes), under-estimates for others. Use `stringExtent`.
- **`foreground = background` alone** (without zero-width metrics): Hidden
  marker text still occupies space, shifting content position by variable
  amounts depending on marker type.
- **`ST/BULLET_NUMBER`**: Auto-increments globally, can't restart per-list.

---

## Step 4 — Wire into `apply-theme!` ✓

**File**: `winze-server/src/llm_memory/ui/markdown_editor.clj`

```clojure
(defn apply-theme! [styled-text text]
  (let [blocks (md-theme/parse-blocks text)
        spans  (md-theme/theme text)
        ranges (into-array StyleRange (map span->style-range spans))]
    (.setStyleRanges styled-text ranges)
    (apply-code-block-line-backgrounds! styled-text blocks)
    (apply-list-rendering! styled-text blocks)     ;; ← added
    (update-link-spans! styled-text spans)))
```

Also added `color-check-green` (#66BB6A) to `resources.clj`.

---

## Step 5 — Future: Continuation lines

Markdown list items can span multiple lines:
```
- This is a long list item
  that wraps to a second line
```

Continuation lines (indented text under a bullet, not a new marker) should
get matching indent/wrap-indent but no bullet glyph. Not yet implemented —
word-wrap handles the common case (single-line items that wrap), but
explicit multi-line items don't get visual treatment.

---

## Step 6 — Future: Edge cases and refinements

- Empty list items (`- ` with no text)
- Deeply nested lists (4+ levels) — verify padding calculation
- Blockquote + list interaction (additive indent)
- Cursor visual cue when positioned in the invisible marker area
- Numbered lists that don't start at 1 (`5. fifth item`)

---

## Verification Checklist

| Feature | Status |
|---------|--------|
| Bullet glyph replaces `- ` / `* ` / `+ ` | ✓ |
| Bullet glyph indented (not at x=0) | ✓ (via space padding in glyph area) |
| Numbered list renders `1.` `2.` `3.` | ✓ |
| Checkbox unchecked shows `☐` | ✓ |
| Checkbox checked shows `✓` in green | ✓ |
| Nesting indent progressive | ✓ |
| Hanging indent aligns wrapped text | ✓ |
| Inline formatting within list items | ✓ |
| Marker text hidden (zero-width) | ✓ |
| All sizes from font metrics | ✓ |
| Checkboxes align with bullets | ✓ |
