---
created: 2026-03-30
related: [file-viewer, file-refresh, md-hiccup-renderer]
tags: [pdf, export, openhtml2pdf]
---

# Live PDF Export — Context

## Goal

Whenever a Markdown file changes in a watched Plans directory, automatically
render it to HTML and export a PDF file alongside the `.md` source. The PDF
reflects the same styled rendering the user sees in the file-viewer Browser
widget (brand palette, code blocks, tables, frontmatter header, metadata pills).

Example: editing `Plans/todo/FOO-PLAN.md` produces `Plans/todo/FOO-PLAN.pdf`
in the same directory, updated live on each save.

## Approach: OpenHTMLtoPDF (JVM-native)

Use [OpenHTMLtoPDF](https://github.com/danfickle/openhtmltopdf) — a Java
library that renders HTML + CSS 2.1 to PDF via a `PDFBoxRenderer`. Runs
in-process on the JVM; no external dependency on Chrome or Pandoc.

### Why not headless Chrome?

- Requires Chrome installed — adds an external dependency.
- Process spawn per file change is heavier than an in-process render.
- The CSS used by `page-css` is simple enough for OpenHTMLtoPDF.

### Why not SWT Browser `printToPDF`?

SWT's `Browser` (WEBKIT) has no PDF export API. The underlying macOS
`WKWebView.createPDF(configuration:completionHandler:)` exists but is
not exposed by SWT — accessing it would require fragile JNI/JNA code.

## Existing Infrastructure

### Markdown → HTML rendering

`llm-memory.ui.search/file-page` renders a markdown file to a complete
styled HTML page (with `<html>`, `<head>`, CSS, metadata header,
frontmatter block, and rendered body). This is the same function used
by the file-viewer tabs.

```clojure
(search/file-page markdown-text file-path metadata)
;; => "<html>...</html>" string
```

### Filesystem watcher

`llm-memory.watcher/start-watcher!` monitors each root's Plans directory
for `.md` file changes (create/modify/delete/rename). Events are debounced
(500ms) and dispatched to indexing functions. The watcher already filters
to `.md` files only via `md-file?` — PDF files will be ignored.

### File metadata

`llm-memory.ui.search/file-metadata-by-path` retrieves indexed metadata
(status, type, group, created date) for a file, used to render the header.

## CSS Compatibility

The `page-css` stylesheet is ~95% CSS 2.1 compatible. Three flex layouts
need CSS 2.1 alternatives for the PDF variant:

| Selector | Current (flex) | PDF alternative |
|---|---|---|
| `.result-header` | `display:flex; justify-content:space-between` | `float:right` on badge |
| `.pills` | `display:flex; gap:6px; flex-wrap:wrap` | `display:inline-block` on each pill + `margin-right` |
| `.result-header-left` | `display:flex; align-items:center; gap:6px` | `display:inline-block; vertical-align:middle` |

Everything else — colors, fonts, borders, tables, code blocks, blockquotes,
lists — works unchanged.

### Fonts

OpenHTMLtoPDF requires explicit font registration. Options:

1. **System font fallback**: Use `sans-serif` / `monospace` generics — PDF
   renders with the JVM's default serif/sans-serif. Acceptable for v1.
2. **Bundle TTFs**: Ship Inter and a monospace font (e.g. JetBrains Mono)
   as classpath resources and register them with the renderer. Better
   fidelity but more setup.

Recommendation: Start with system fallbacks (v1), add bundled fonts later
if the output quality matters.

## PDF Page Layout

- **Page size**: A4 or US Letter (configurable, default A4).
- **Margins**: Reasonable print margins (e.g. 20mm).
- **Dark theme consideration**: The current CSS uses a dark background
  (`#1E1B2E`) with light text (`#E8E0FF`). This looks great on screen but
  wastes toner and is hard to read on paper. The PDF stylesheet should
  invert to a light theme: white background, dark text, with the brand
  purple palette for accents (headings, links, code block backgrounds).
- **Page breaks**: Add `page-break-inside: avoid` on `.result-card`,
  `pre`, and `table` elements.

## Integration Points

### Watcher hook

The watcher's `handle-event!` function dispatches `:create` and `:modify`
events to indexing. PDF export can be triggered from the same event stream
— either by adding a callback mechanism to the watcher, or by having the
PDF exporter subscribe to the same Beholder watcher independently.

Preferred: Add a lightweight callback/hook system to the watcher so that
multiple consumers (indexer, PDF exporter) can react to file change events
without duplicating the Beholder subscription.

### Toggle

PDF export should be opt-in per root (not every user wants PDFs cluttering
their Plans directory). A per-root setting (e.g. `:root/pdf-export? true`)
or a global config flag is needed.

## Dependencies

Add to `winze-server/deps.edn`:

```clojure
com.openhtmltopdf/openhtmltopdf-pdfbox {:mvn/version "1.1.22"}
```

This brings in pdfbox transitively. No other new dependencies required —
the HTML rendering pipeline (`file-page`, `page-css`, `hiccup/md->hiccup`)
already exists.

## Risks

- **Render fidelity**: OpenHTMLtoPDF's CSS 2.1 engine may have minor
  differences from WebKit. Tables and code blocks should be fine; complex
  layouts may need tweaking. Mitigation: the PDF stylesheet is a separate
  function, tunable independently.
- **Performance**: Rendering a PDF per file save adds latency. For typical
  Plans files (1–5 pages), OpenHTMLtoPDF renders in <200ms. The watcher's
  500ms debounce already coalesces rapid saves.
- **Watcher feedback loop**: If the PDF is written to the same directory
  the watcher monitors, the watcher must ignore `.pdf` files. It already
  does (filters to `.md` only). Confirmed safe.
