---
type: plan
status: complete
group: tab-icons
---

# Tab Icons — Plan

## Steps

### 1. Rasterize the document icon SVG to PNG

```bash
cd winze/winze-server
mkdir -p resources/branding/ui/png

resvg resources/branding/ui/winze-tab-document.svg \
      resources/branding/ui/png/winze-tab-document-16.png -w 16 -h 16

resvg resources/branding/ui/winze-tab-document.svg \
      resources/branding/ui/png/winze-tab-document-32.png -w 32 -h 32
```

Verify the PNGs render correctly (inspect visually — check fold, lines, spark).

---

### 2. Add `tab-document-icon` image resource in `main_window.clj`

After the `statusbar-icon` and `header-image` `def`s (around line 49-52), add:

```clojure
(def tab-document-icon
  (delay (hidpi-image "branding/ui/png/winze-tab-document-16.png"
                      "branding/ui/png/winze-tab-document-32.png")))
```

---

### 3. Change `open-tab!` to accept `icon` as first argument

Current signature:
```clojure
(defn open-tab!
  ([title html] (open-tab! title html title))
  ([title html tooltip]
```

New signature:
```clojure
(defn open-tab!
  ([icon title html] (open-tab! icon title html title))
  ([icon title html tooltip]
```

Replace the hardcoded `:image @statusbar-icon` in the `ctab-item` body with `:image icon`.

---

### 4. Update call site: file tab (line ~101)

Change:
```clojure
(async-exec! #(open-tab! filename html rel-path))
```

To:
```clojure
(async-exec! #(open-tab! @tab-document-icon filename html rel-path))
```

---

### 5. Update call site: saved search tab (line ~156)

Change:
```clojure
(open-tab! q html)
```

To:
```clojure
(open-tab! @statusbar-icon q html)
```

---

### 6. Reload namespace in REPL and screenshot-verify

```clojure
(require '[llm-memory.ui.main-window] :reload)
```

Then open a Markdown file tab and confirm:
- File tab shows the document icon (purple dog-eared page)
- Live search tab retains the crystal diamond
- Saved search tab (type ≥3 chars, press Enter) retains the crystal diamond

Take a screenshot per project conventions before marking done.
