---
created: 2026-03-30
related: [file-viewer, file-refresh, md-hiccup-renderer]
tags: [pdf, export, openhtml2pdf]
---

# Live PDF Export — Plan

## Step 1 — Add OpenHTMLtoPDF dependency

**File**: `winze-server/deps.edn`

Add:

```clojure
com.openhtmltopdf/openhtmltopdf-pdfbox {:mvn/version "1.1.22"}
```

Verify it resolves: `cd winze/winze-server && clojure -Stree | grep openhtmltopdf`.

---

## Step 2 — Create `pdf-page-css` function

**File**: `winze-server/src/llm_memory/ui/search.clj`

Add a `pdf-page-css` function alongside `page-css`. Changes from screen CSS:

1. **Light theme**: `body { background: #FFFFFF; color: #1a1a1a; }`.
   Headings use brand purples for accent. Code blocks use light gray
   background (`#f5f3ff`) with dark text.
2. **Replace flex with CSS 2.1**:
   - `.result-header`: use `overflow:hidden` clearfix, float badge right.
   - `.pills`: `display:inline` on container, `display:inline-block` +
     `margin: 0 6px 6px 0` on each `.pill`.
   - `.result-header-left`: `display:inline-block; vertical-align:middle`
     on children.
3. **Print layout**: Add `@page { size: A4; margin: 20mm; }` and
   `page-break-inside: avoid` on `pre`, `table`, `.result-card`.
4. **Font stack**: Keep `Inter, sans-serif` and `'SF Mono', Menlo, monospace`
   — OpenHTMLtoPDF falls back to PDF base fonts if unavailable.

### Verification

Render a sample HTML string with `pdf-page-css` in the REPL and visually
inspect that the CSS is well-formed. No visual verification yet — that
comes in Step 4.

---

## Step 3 — Create `pdf-file-page` function

**File**: `winze-server/src/llm_memory/ui/search.clj`

Add a `pdf-file-page` function that mirrors `file-page` but uses
`pdf-page-css` and produces XHTML (OpenHTMLtoPDF requires well-formed XML):

```clojure
(defn pdf-file-page
  "Render a markdown file as XHTML suitable for OpenHTMLtoPDF."
  [markdown-text file-path & [metadata]]
  ;; Same structure as file-page but:
  ;; - Uses pdf-page-css
  ;; - Wraps in <?xml ...> + <!DOCTYPE ...> + <html xmlns="...">
  ;; - Ensures all tags are closed (Hiccup 2 already does this)
  ...)
```

`★ Insight ─────────────────────────────────────`
OpenHTMLtoPDF parses XHTML, not HTML5. Hiccup 2's `html` function emits
well-formed XML by default (self-closing tags like `<meta .../>`), so
this mostly works. The key additions are the XML declaration and the
`xmlns` attribute on the `<html>` element.
`─────────────────────────────────────────────────`

### Verification

Call `(pdf-file-page (slurp "Plans/todo/PDF-EXPORT-PLAN.md") "PDF-EXPORT-PLAN.md")`
in the REPL and verify the output is valid XHTML (well-formed XML).

---

## Step 4 — Create `llm-memory.pdf` namespace

**File**: `winze-server/src/llm_memory/pdf.clj`

Core rendering function:

```clojure
(ns llm-memory.pdf
  "Render markdown files to PDF using OpenHTMLtoPDF."
  (:require [llm-memory.ui.search :as search])
  (:import [com.openhtmltopdf.pdfboxout PdfRendererBuilder]
           [java.io FileOutputStream]))

(defn render-pdf!
  "Render markdown-text to a PDF file at pdf-path."
  [markdown-text file-path pdf-path & [metadata]]
  (let [xhtml (search/pdf-file-page markdown-text file-path metadata)]
    (with-open [os (FileOutputStream. pdf-path)]
      (-> (PdfRendererBuilder.)
          (.useFastMode)
          (.withHtmlContent xhtml nil)
          (.toStream os)
          (.run)))))

(defn md-path->pdf-path
  "Given a .md file path, return the sibling .pdf path."
  [md-path]
  (str/replace md-path #"\.md$" ".pdf"))
```

### Verification

From the REPL:

```clojure
(let [md   (slurp "Plans/todo/PDF-EXPORT-PLAN.md")
      out  "/tmp/test-export.pdf"]
  (render-pdf! md "PDF-EXPORT-PLAN.md" out))
```

Open `/tmp/test-export.pdf` and visually verify: correct headings, code
blocks, tables, brand-accented colors on white background.

---

## Step 5 — Watcher callback mechanism

**File**: `clj-llm-memory/src/llm_memory/watcher.clj`

Add callback support so multiple consumers can react to file events:

```clojure
;; Atom: {root-uri -> [callback-fn ...]}
(defonce callbacks (atom {}))

(defn register-callback!
  "Register a callback for file change events on a root.
   callback-fn receives {:type :create|:modify|:delete, :path Path, :root-uri str}."
  [root-uri callback-fn]
  (swap! callbacks update root-uri (fnil conj []) callback-fn))
```

Modify `handle-event!` to invoke registered callbacks after the indexing
dispatch. Callbacks run in a future to avoid blocking the watcher.

### Verification

Register a test callback that logs events, modify a `.md` file, confirm
the callback fires.

---

## Step 6 — Wire PDF export to watcher

**File**: `winze-server/src/llm_memory/pdf.clj` (extend)

Add a watcher callback function:

```clojure
(defn on-file-change!
  "Watcher callback: render PDF for created/modified .md files."
  [{:keys [type path root-uri]}]
  (when (#{:create :modify} type)
    (try
      (let [abs-path (str path)
            md-text  (slurp abs-path)
            pdf-path (md-path->pdf-path abs-path)
            rel-path (-> ... derive relative path ...)
            metadata (search/file-metadata-by-path root-uri rel-path)]
        (render-pdf! md-text rel-path pdf-path metadata)
        (log/info "PDF exported:" pdf-path))
      (catch Throwable t
        (log/warn t "PDF export failed for" (str path))))))
```

**File**: `winze-server/src/llm_memory/server/main.clj`

After `watcher/start-watcher!`, register the PDF callback:

```clojure
(watcher/register-callback! uri pdf/on-file-change!)
```

### Verification

Edit a Plans `.md` file in the StyledText editor. Verify:
1. A `.pdf` file appears alongside the `.md` file within ~1s.
2. The watcher does NOT re-trigger on the `.pdf` file creation.
3. The PDF content matches the file-viewer rendering (light-theme variant).

---

## Step 7 — Delete handling

**File**: `winze-server/src/llm_memory/pdf.clj`

Extend `on-file-change!` to handle `:delete` events — if the `.md` file
is deleted, delete the sibling `.pdf` if it exists:

```clojure
(when (= :delete type)
  (let [pdf-path (md-path->pdf-path (str path))]
    (when (.exists (io/file pdf-path))
      (io/delete-file pdf-path)
      (log/info "PDF deleted:" pdf-path))))
```

Rename events (detected by the watcher as delete + create) will naturally
delete the old PDF and create a new one at the new path.

### Verification

Delete a `.md` file that has a sibling `.pdf`. Confirm the `.pdf` is
also removed.

---

## Step 8 — Opt-in toggle (deferred)

Add a per-root `:root/pdf-export?` attribute to the Datalevin schema,
defaulting to `false`. Only register the PDF callback for roots where
this is enabled.

UI: A checkbox or menu item in the winze app to toggle per-root.
CLI: `(pdf/enable! root-uri)` / `(pdf/disable! root-uri)`.

This step can be deferred if we're fine with PDF export being always-on
during development.

---

## Exit Criteria

1. Saving a `.md` file in any watched root produces a `.pdf` sibling
   within ~1 second.
2. The PDF uses a light-theme print stylesheet (white background, brand
   accents) with correct headings, code blocks, tables, and frontmatter.
3. Deleting a `.md` file cleans up the sibling `.pdf`.
4. The watcher ignores `.pdf` files (no feedback loop).
5. No new external dependencies (Chrome, Pandoc, etc.) — pure JVM.
