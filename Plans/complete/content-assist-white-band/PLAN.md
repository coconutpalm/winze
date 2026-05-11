---
group: content-assist-white-band
doc_type: plan
status: complete
related: [editor-cleanup, content-assist-sizing, content-assist-sizing-v2]
tags: [swt, ui, content-assist, browser, offscreen, rendering]
---

> **Archived as complete.** Shipped in winze commits `9d3d676` (initial
> attempt) and `f2b7229` (refined fix). The plan below is the original
> two-change hypothesis; the "Realized fix" section records what
> actually landed and why the original plan was insufficient.

# Content Assist — First-Render White Band — Plan

## Realized fix (what actually shipped)

Four code moves in `winze-server/src/llm_memory/ui/content_assist.clj`:

1. **Initial offscreen Shell height → `max-row-height`**. Per the plan.
2. **`measure-scrollbar-gutter!` holds at `max-row-height`** instead of
   snapping the Shell back to `row-height = 80` on the first iteration.
   The original plan missed this — it defeated Change 1 on first render
   because every iteration still started from an 80 px viewport and
   grew.
3. **Stop resizing the offscreen Shell per iteration.** `screenshot-browser`
   now takes a `content-h` argument and trims the `Image` via
   `drawImage` instead of `.setSize`/`.layout` on the Shell. This
   removes the grow/shrink paint race entirely; the Shell stays at
   `max-row-height` for the whole session.
4. **Remove `.redraw tbl` from the `MeasureItem` handler.** That call
   scheduled a paint that refired `MeasureItem` on the next cycle — a
   self-triggering storm. On macOS Cocoa the storm eventually produced
   a stale repaint of row 0 about a second after the initial render,
   replacing its image with a duplicate of a later row. This was the
   delayed-duplicate symptom surfaced during verification.

The plan's "Change 2" (defensive `.setBackground` on the offscreen
Shell/Composite/Browser) was **reverted** — it was speculative and
contributed nothing once the real races were closed.

## Verification performed

- Reproduction: duplicate build showed the white band + eventual
  duplicate row on first popup only.
- After the four moves (and Change 2 reverted): band gone on first
  open; no duplicate row at the 1 s mark across multiple app restarts.
- Diagnostic logging (later stripped) confirmed only one
  `render-row-images!` cycle ever fires for a single popup open, so
  the duplicate was a paint-layer artefact, not a second render.

---

**See [CONTEXT.md](CONTEXT.md) for the root-cause analysis and exact
first-render sequence.**

---

## Original plan (for historical reference)

## Goal

Eliminate the 100–200 px white band that appears inside the first result
card's `TableItem` `Image` on the first display of the content-assist popup
in a session.

## Fix order

1. **Reproduce first** — confirm the bug in the running winze-server
   before touching code.
2. **Change 1** — raise initial offscreen Shell height to `max-row-height`.
   Verify; this alone should fix it.
3. **Change 2** — defensive `SWT.WIDGET_BACKGROUND` suppression on the
   offscreen Shell / Composite / Browser. Verify no visible regression.
4. **`make install`** from `winze-server/`; restart server; run through
   the regression sweep on the live session.

If Change 1 alone does not fix it, stop and reopen the investigation
rather than stacking Change 2 on top of a wrong hypothesis.

---

## Change 1 — initial Shell size

**File**: `winze-server/src/llm_memory/ui/content_assist.clj`, line 128.

```clojure
;; BEFORE
(.setBounds sh ox oy (+ (popup-width) 20) row-height)

;; AFTER
(.setBounds sh ox oy (+ (popup-width) 20) max-row-height)
```

Every card's content height is clamped to `max-row-height` at line 233
(currently 400 px). Starting the Shell at `max-row-height` means every
subsequent resize is a shrink (or equal). Shrinks do not expose unpainted
pixels — the larger viewport was already fully painted by WebKit.

---

## Change 2 — defensive `SWT.WIDGET_BACKGROUND` suppression

**File**: `winze-server/src/llm_memory/ui/content_assist.clj`, lines 117–125.

```clojure
;; BEFORE
sh     (Shell. (Display/getDefault) SWT/NO_TRIM)
_      (.setLayout sh (FillLayout.))
guard  (doto (Composite. sh SWT/NONE)
         (.setLayout (FillLayout.))
         (.setEnabled false))
brow   (Browser. guard SWT/WEBKIT)

;; AFTER
bg     ^Color @resources/color-mine-shaft
sh     (doto (Shell. (Display/getDefault) SWT/NO_TRIM)
         (.setLayout (FillLayout.))
         (.setBackground bg))
guard  (doto (Composite. sh SWT/NONE)
         (.setLayout (FillLayout.))
         (.setBackground bg)
         (.setEnabled false))
brow   (doto (Browser. guard SWT/WEBKIT)
         (.setBackground bg))
```

Literal interpretation of the user's hint: if any future change
reintroduces a grow-before-paint situation, the fall-through pixel colour
is `mine-shaft`, not white — the band, if it reappears, is invisible
against the card background.

---

## Why not a paint-sync wait after resize

Adding a `Display.readAndDispatch` loop between line 242 and line 243
would also fix the primary cause, but WebKit paint completion is not
reliably signalled by dispatch completion across platforms. The size-
initialisation fix is unconditional and cheap; the background-colour
change is cheap insurance.

---

## Verification

Per [`SWT-UI-GUIDE.md`](../SWT-UI-GUIDE.md): `load-file` only (never
`:reload-all`), always screenshot. Use the `start-nrepl` skill from
`winze-server/` with `:dev` for a second headless nREPL; discover the
running server's port with `clj-nrepl-eval --discover-ports`.

### Reproduction (BEFORE any code change)

```clojure
(require '[llm-memory.ui.content-assist :as ca])

;; Force first-time state: dispose any existing offscreen Shell so the
;; next popup open hits the buggy initial-size path.
(ui.SWT/sync-exec! #(ca/dispose-offscreen!))

;; Trigger content assist in an editor tab (type `[text](` or press the
;; Insert-Link shortcut) — or open it directly:
;; (ui.SWT/sync-exec! #(ca/open-content-assist! {...}))

(Thread/sleep 1500)
(ui.SWT/sync-exec!
 #(llm-memory.ui.util/screenshot-widget!
   (:shell @@#'ca/popup-state)
   "/tmp/ca-before.png"))
```

Expected: `/tmp/ca-before.png` shows the white band between row 1 and
row 2.

### After Change 1 only

```clojure
(load-file "src/llm_memory/ui/content_assist.clj")
(ui.SWT/sync-exec! #(ca/dispose-offscreen!))   ; force re-init at new size
;; Repeat the reproduction, save as /tmp/ca-after-change1.png.
```

Confirm the band is gone. If it is not — **stop**. The primary
hypothesis is wrong; reopen investigation before applying Change 2.

### After Change 2

Same procedure; save as `/tmp/ca-after-change2.png`. Confirm: no band,
no visible change to card rendering.

### Regression sweep

- Short single-heading result — row height matches card content
  exactly; no extra mine-shaft band appears below it (`MeasureItem`
  still reports `image.bounds.height`, which equals the trimmed card
  height).
- Very tall result that would hit `max-row-height` (400) — clipped to
  400 as before.
- Open popup 5× in a row — every first card renders cleanly.

### After install / restart

```bash
cd winze-server && make install
```

Restart the Winze server (graceful: `(llm-memory.ui.main-window/quit!)`
via nREPL — never `pkill`, see `CLAUDE.md` "Server Lifecycle").
Re-open the popup and repeat the regression sweep against the running
server.

---

## Completion criteria

- [ ] Reproduction screenshot captured showing the bug.
- [ ] Change 1 applied; `/tmp/ca-after-change1.png` has no band.
- [ ] Change 2 applied; `/tmp/ca-after-change2.png` has no band and no
      regression.
- [ ] `make install` run; Winze server restarted gracefully.
- [ ] Live-session regression sweep passes.
- [ ] Commit inside the `winze/` subrepo (not `_finance/`).

## Out of scope

- Live-search Browser's SWT-native background (separate flash flagged
  during the audit) — follow-up.
- `WIKI-LINK-REGRESSIONS-*` and `_WIKI-INDEX-GAP-*` — remain in `todo/`
  and tracked separately.
- Any change to `search/card-html` or CSS — the bug is in the
  SWT/WebKit pipeline, not the HTML.

## On completion

Move this pair to `Plans/complete/content-assist-white-band/`:

- `CONTENT-ASSIST-WHITE-BAND-CONTEXT.md` → `complete/content-assist-white-band/CONTEXT.md`
- `CONTENT-ASSIST-WHITE-BAND-PLAN.md`    → `complete/content-assist-white-band/PLAN.md`

Drop the prefix on rename per project convention.
