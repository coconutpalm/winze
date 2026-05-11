---
group: content-assist-white-band
doc_type: context
status: complete
related: [editor-cleanup, content-assist-sizing, content-assist-sizing-v2]
tags: [swt, ui, content-assist, browser, offscreen, rendering]
---

> **Archived as complete.** Landed in winze commits `9d3d676` (initial
> attempt) and `f2b7229` (refined fix — four moves rather than the two
> originally planned). See [PLAN.md](PLAN.md) "Realized fix" for the
> actual shape of what shipped, which is larger than the initial
> hypothesis (the primary hypothesis in this context doc was correct
> but incomplete — there were *two* paint races to close, not one, and
> a separate self-triggering paint storm in `MeasureItem` that
> manifested as a delayed duplicate row).

# Content Assist — First-Render White Band — Context

## Symptom

The content-assist popup shows an "ugly white band" between the first and
second result cards **only on the first display of the popup in a session**.
Subsequent opens render cleanly. The band is roughly 100–200 px tall, spans
the full popup width, and appears inside what the user perceives as the gap
between row 1 and row 2 of the result Table.

Originally misdiagnosed (during the 2026-04-17 Plans audit) as a paint flash
in the live-search Browser widget. It is not — the affected widget is the
content-assist popup's `Table`, and the white pixels live inside row 1's
`Image` itself, not in any inter-row SWT widget background.

## Root cause

Offscreen-render → screenshot → `TableItem` pipeline in
[`winze-server/src/llm_memory/ui/content_assist.clj`](../../winze-server/src/llm_memory/ui/content_assist.clj).

The offscreen `Shell` + `Browser` used to rasterise cards is created with a
small initial height (`row-height = 80 px`) and grown to the natural content
height **immediately before the screenshot is captured** on the first card of
the first popup. WebKit has not yet painted the newly-exposed region when
`Browser/print` fires, so the bottom of the `Image` contains unpainted native
backing pixels — white on macOS.

### Exact first-render sequence

1. `ensure-offscreen-browser!` line 128:
   `(.setBounds sh ox oy (+ (popup-width) 20) row-height)`, with
   `row-height = 80` (line 44). Offscreen Browser is 80 px tall.
2. `render-row-images!` line 258: `.setText browser html`. HTML renders
   into the 80 px viewport; CSS `body { background: mine-shaft }` paints
   only that viewport.
3. `ProgressListener.completed` line 223 fires. At `@idx == 0`,
   `measure-scrollbar-gutter!` (line 230 → line 167) keeps the Shell at
   `row-height = 80`.
4. Line 232 reads `body.scrollHeight` — natural content height (e.g. 280).
5. Lines 239–242 grow Shell/Browser from 80 → 280 and call
   `.layout shell true`. WebKit schedules a paint; has not yet run.
6. Line 243 calls `screenshot-browser` → `.print browser gc` into an
   `Image` sized to `bounds.height = 280`. Top 80 px are card content;
   bottom 200 px are unpainted native backing = white.
7. Line 252: `.setData item "image" img`. `MeasureItem` (lines 656–665)
   sets `event.height = image.bounds.height = 280`. Row 1 is 280 px tall.
   `PaintItem`'s obsidian `fillRectangle` (line 687) is overdrawn by the
   Image's own white pixels (line 693).

### Why only the first time

`@offscreen-state` is `defonce` (line 66). The Shell is hidden, not
disposed, between popup openings (`hide-offscreen!`, lines 135–140), so
its size is retained. After the first successful render the Shell sits at
some content-sized height, so subsequent `.setText` calls never grow the
viewport — they stay equal or shrink, and shrinks do not expose unpainted
pixels.

## Secondary hypothesis considered — and rejected

`NSTableView` does not truly support per-row variable heights on macOS and
applies a small intercell spacing. This could explain a thin separator, but
not a 100–200 px band and not "first-time only" — the quirk would reproduce
on every open. Keep in mind while verifying the fix; do not pre-fix it.

## Affected source files

| File | Lines | Role |
|------|-------|------|
| `winze-server/src/llm_memory/ui/content_assist.clj` | 44–45 | `row-height` / `max-row-height` constants |
| `winze-server/src/llm_memory/ui/content_assist.clj` | 117–128 | `ensure-offscreen-browser!` — initial Shell size and Browser construction |
| `winze-server/src/llm_memory/ui/content_assist.clj` | 154–169 | `measure-scrollbar-gutter!` — first-iteration resize at 80 px |
| `winze-server/src/llm_memory/ui/content_assist.clj` | 171–190 | `screenshot-browser` — `.print browser gc` capture |
| `winze-server/src/llm_memory/ui/content_assist.clj` | 207–259 | `render-row-images!` — grow-then-print loop |
| `winze-server/src/llm_memory/ui/content_assist.clj` | 656–694 | `MeasureItem` / `EraseItem` / `PaintItem` listeners |

## Related prior work

- [`complete/editor-cleanup/`](../complete/editor-cleanup/) — introduced
  the offscreen Browser + screenshot → `TableItem` architecture.
- [`complete/content-assist-sizing/`](../complete/content-assist-sizing/) —
  first sizing pass; did not cover offscreen viewport lifecycle.
- [`complete/content-assist-sizing-v2/`](../complete/content-assist-sizing-v2/) —
  follow-on sizing fixes.

None of them contemplated a grow-before-paint race on the first popup.

## See also

- [`CONTENT-ASSIST-WHITE-BAND-PLAN.md`](CONTENT-ASSIST-WHITE-BAND-PLAN.md) —
  the fix plan.
- Reproduction and verification rely on the
  [`SWT-UI-GUIDE.md`](../SWT-UI-GUIDE.md) screenshot discipline.
