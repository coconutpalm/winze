---
created: 2026-03-30
status: complete
related: [CONTEXT.md, complete/scroll-sync-regression/CONTEXT.md]
tags: [swt, ui, file-viewer, search, header, yaml, frontmatter]
---

# File Viewer Header — Plan

## Step 1 — Add `file-metadata-by-path` lookup function

**File**: `search.clj`

Add a function that queries the Datalevin store for a file entity by its root-uri and
relative path, returning metadata in the same shape as a search result:

```clojure
(defn file-metadata-by-path
  "Look up indexed metadata for a file by root URI and relative path.
   Returns a map with :file/* keys, or nil if the file is not indexed."
  [root-uri rel-path]
  (let [store (server/store)
        root-name (->> (core/list-roots store)
                       (filter #(= root-uri (:root/uri %)))
                       first
                       :root/name)
        file-id (str root-name "::" rel-path)
        eids (store/query store
               '[:find [?f ...]
                 :in $ ?fid
                 :where [?f :file/id ?fid]]
               {:fid file-id})]
    (when (seq eids)
      (let [e (store/pull-entity store (first eids))]
        (cond-> {:file/path   (:file/path e)
                 :root/uri    root-uri}
          (:file/status e)  (assoc :file/status  (:file/status e))
          (:file/created e) (assoc :file/created (:file/created e))
          (:file/group e)   (assoc :file/group   (:file/group e))
          (:file/tags e)    (assoc :file/tags    (:file/tags e))
          (:file/related e) (assoc :file/related (:file/related e))
          (:file/type e)    (assoc :file/type    (:file/type e)))))))
```

Requires adding `llm-memory.store.protocol` to the `:require` (or use through `core`).

## Step 2 — Add `file-header` Hiccup component

**File**: `search.clj`

Extract a shared header component that both `result-card` and `file-page` can use.
The file viewer version omits the relevance badge:

```clojure
(defn- file-header
  "Render the standard file header: status indicator, path, date, and pills.
   When `relevance` is present, also renders the percentage badge."
  [{:keys [file/path root/uri relevance] :as metadata}]
  [:div
   [:div.result-header
    [:div.result-header-left
     [:span.status-indicator
      (case (:file/status metadata)
        "active"   "☐"
        "complete" "☑"
        "")]
     [:a.file-path {:href (str "winze:open-file?root="
                                (URLEncoder/encode (or uri "") "UTF-8")
                                "&path="
                                (URLEncoder/encode (or path "") "UTF-8"))}
      (or path "—")]
     (when (:file/created metadata)
       [:span.created-date (str "(" (:file/created metadata) ")")])]
    (when relevance
      [:span {:class (relevance-class relevance)}
       (format "%.0f%%" (* 100.0 relevance))])]
   (metadata-pills metadata {})])
```

Update `result-card` to call `(file-header result)` instead of inlining the header
markup. This ensures both views stay in sync.

## Step 3 — Extract and render YAML frontmatter as a code block

**File**: `search.clj`

Use `frontmatter/parse-frontmatter` (already in `clj-llm-memory`) to split the file
content into YAML text and markdown body. Render the YAML as a styled `<pre><code>`
block between the header and the markdown body.

Add CSS for the frontmatter block to `page-css`:

```css
.frontmatter-block {
  font-family: 'SF Mono', Menlo, monospace;
  font-size: 12px;
  line-height: 1.5;
  background: <bedrock>;
  color: <deep-violet>;
  border: 1px solid <obsidian>;
  border-radius: 4px;
  padding: 10px 12px;
  margin-bottom: 16px;
  overflow-x: auto;
  white-space: pre;
}
```

The raw YAML text (between the `---` fences, exclusive) is placed inside this block.
If no frontmatter is present, the block is omitted.

## Step 4 — Update `file-page` to accept metadata and render the full header

**File**: `search.clj`

Change the `file-page` signature to accept an optional metadata map:

```clojure
(defn file-page
  "Render a markdown file's full content as a styled HTML page.
   When metadata is provided, renders the search-card-style header."
  [markdown-text file-path & [metadata]]
  (let [[_fm-map body] (frontmatter/parse-frontmatter markdown-text)
        raw-yaml       (extract-raw-yaml markdown-text)]  ;; text between --- fences
    (str
     (h/html
      [:html
       [:head [:meta {:charset "UTF-8"}] [:style (page-css)]]
       [:body
        (if metadata
          (file-header metadata)
          [:div.header file-path])
        (when raw-yaml
          [:pre.frontmatter-block raw-yaml])
        [:div.result-body (hiccup/md->hiccup body)]]]))))
```

Add a small helper `extract-raw-yaml` that returns the text between `---` fences
(for display), or nil if no frontmatter. `parse-frontmatter` returns `[map, body]`
but we also need the raw YAML text for the code block display.

## Step 5 — Update call site in `main_window.clj`

**File**: `main_window.clj`

In the `winze:open-file?` handler (lines 63–76), add a metadata lookup before
calling `file-page`:

```clojure
(future
  (try
    (let [abs-path  (search/resolve-file-path root-uri rel-path)
          content   (slurp abs-path)
          metadata  (search/file-metadata-by-path root-uri rel-path)
          html      (search/file-page content rel-path metadata)
          filename  (last (str/split rel-path #"/"))]
      (async-exec! #(open-tab! @tab-document-icon filename html rel-path abs-path rel-path)))
    (catch Throwable t
      (log/error t "Failed to open file" rel-path))))
```

## Step 6 — Update `refresh-open-tabs!` to include metadata on re-render

**File**: `main_window.clj`

`refresh-open-tabs!` (line 179) re-reads the file on external modification. Update
it to also look up metadata so the header stays correct after file changes:

Find the call to `search/file-page` inside `refresh-open-tabs!` and pass the metadata:

```clojure
(let [content  (slurp abs-path)
      metadata (search/file-metadata-by-path root-uri rel-path)
      html     (search/file-page content rel-path metadata)]
  ...)
```

This requires storing `root-uri` in the `open-files` atom entry (currently only
`abs-path` and `rel-path` are stored). Add `:root-uri` to the map in `open-tab!`.

## Step 7 — Colored status indicators ✓

**File**: `search.clj`

Replaced the plain `☑` glyph with a CSS-colored approach. Added two CSS classes
to `page-css`:

```css
.status-active   { color: #7B6FC0; }   /* deep-violet */
.status-complete { color: #66BB6A; }   /* green */
```

Updated `file-header` to emit class-based styling:

```clojure
[:span {:class (str "status-indicator"
                    (case (:file/status metadata)
                      "active"   " status-active"
                      "complete" " status-complete"
                      ""))}
 (case (:file/status metadata)
   "active"   "☐"
   "complete" "✓"
   "")]
```

Both search result cards and file viewer headers share this through `file-header`.

## Step 8 — Verify with screenshots ✓

1. Open a file with YAML frontmatter (e.g. `FILE-VIEWER-HEADER-CONTEXT.md`) ✓
2. Screenshot the file viewer — verify:
   - Header shows: `☐ dev/FILE-VIEWER-HEADER-CONTEXT.md (2026-03-30)` ✓
   - Pills row shows tag/group/related pills ✓
   - YAML frontmatter renders as a monospace code block (not wrapped body text) ✓
   - Markdown body renders normally below the frontmatter block ✓
3. Open a completed file — verify green `✓` status indicator ✓
4. Compare with a search result card — headers visually consistent ✓

## Step 9 — Verify refresh and pill clicks

1. Edit the open file externally → verify the view refreshes with metadata intact
2. Click a `#tag` pill → verify it navigates to a `winze:search?q=%23tag` search
3. Click the file path link in the header → verify it opens a new tab (or focuses existing)
