---
type: context
status: complete
group: tab-icons
---

# Tab Icons — Context

## Goal

Give each tab type its own icon:

| Tab type | Icon | Source |
|---|---|---|
| Markdown file (opened via `winze:open-file?` link) | Document icon | new `winze-tab-document.svg` |
| Saved search (opened via `widget-default-selected` on search Text) | Crystal diamond | existing `@statusbar-icon` |
| "Live search" home tab | Crystal diamond | existing `@statusbar-icon` (unchanged) |

---

## Relevant Files

| File | Role |
|---|---|
| `winze/winze-server/src/llm_memory/ui/main_window.clj` | All tab creation; the only call site for `open-tab!` |
| `winze/winze-server/resources/branding/ui/winze-tab-document.svg` | New document icon SVG (master source, created 2026-03-27) |
| `winze/winze-server/resources/branding/statusbar/macos/winzeTemplate.png` | Existing 1× crystal icon used by `@statusbar-icon` |
| `winze/winze-server/resources/branding/statusbar/macos/winzeTemplate@2x.png` | Existing 2× crystal icon |

---

## Current `open-tab!` Signature

```clojure
(defn open-tab!
  ([title html] (open-tab! title html title))
  ([title html tooltip]
   ...
   (ctab-item SWT/CLOSE (word-wrap 30 title)
              :image          @statusbar-icon   ; ← hardcoded; must become a parameter
              :tool-tip-text  tooltip
              (control tab-id))))
```

The icon is hardcoded inside the 2-arity body; there is no way for callers to specify it today.

---

## Call Sites

### 1. `winze:open-file?` handler (line ~101) — file tab

```clojure
(async-exec! #(open-tab! filename html rel-path))
```

Called from a `future` on a background thread via `async-exec!`.
Needs to pass the new document icon as the first argument.

### 2. `widget-default-selected` on search Text (line ~156) — saved search tab

```clojure
(open-tab! q html)
```

Needs to pass `@statusbar-icon` (crystal diamond) as the first argument.

### 3. `body []` "Live search" home tab (line ~187)

```clojure
(ctab-item "Live search"
           :image @statusbar-icon
           (control :ui/live-search-results))
```

This tab is **not** created via `open-tab!` — it is declared inline in the CDT tree.
No change needed; it already uses `@statusbar-icon` directly.

---

## SWT Image Constraint

SWT's `Image` class loads from a stream using `ImageData` — it supports PNG, BMP, GIF, JPEG, and ICO. **SVG is not natively supported.**

The existing pattern for HiDPI icons is `hidpi-image`, which loads a 1× PNG and a 2× PNG and returns an `Image` backed by an `ImageDataProvider`:

```clojure
(def statusbar-icon
  (delay (hidpi-image "branding/statusbar/macos/winzeTemplate.png"
                      "branding/statusbar/macos/winzeTemplate@2x.png")))
```

The new document icon must follow this same pattern:
- Rasterize `winze-tab-document.svg` to `resources/branding/ui/png/winze-tab-document-16.png` (16×16)
- Rasterize to `resources/branding/ui/png/winze-tab-document-32.png` (32×32) for 2× retina
- Define `tab-document-icon` as a `delay`-wrapped `hidpi-image` call

---

## Rasterization Command

Per the brand guide (`BRAND-GUIDE.md`), use `resvg` — not ImageMagick's MSVG renderer:

```bash
# From winze-server/
resvg resources/branding/ui/winze-tab-document.svg \
      resources/branding/ui/png/winze-tab-document-16.png -w 16 -h 16

resvg resources/branding/ui/winze-tab-document.svg \
      resources/branding/ui/png/winze-tab-document-32.png -w 32 -h 32
```

---

## New `open-tab!` Signature

Icon becomes the first argument in both arities:

```clojure
(defn open-tab!
  ([icon title html] (open-tab! icon title html title))
  ([icon title html tooltip]
   ...
   (ctab-item SWT/CLOSE (word-wrap 30 title)
              :image          icon
              :tool-tip-text  tooltip
              (control tab-id))))
```

The `declare` at line 81 (`(declare open-tab!)`) only needs a name — no signature change needed there.

---

## Visual Verification

Per project conventions, all SWT visual changes require screenshot verification.
After implementing, take a screenshot showing:
- A file tab open (document icon visible)
- The Live search tab (diamond icon visible)
- Optionally, a saved-search tab open (diamond icon visible)
