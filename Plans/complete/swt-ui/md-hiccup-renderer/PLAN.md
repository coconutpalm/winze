---
created: 2026-03-30
status: complete
related: [CONTEXT.md, complete/view-edit-scroll-sync/CONTEXT.md]
tags: [markdown, hiccup, commonmark, rendering]
---

# Custom Markdown → Hiccup Renderer — Plan

## Step 1 — Namespace skeleton and parser setup

**File**: `winze-server/src/llm_memory/ui/hiccup.clj`

Create the namespace with imports and a parser factory:

```clojure
(ns llm-memory.ui.hiccup
  "Markdown → Hiccup renderer with source-line annotations.
  Walks the commonmark-java AST directly, producing Hiccup vectors
  with {:data-line N} on block elements."
  (:require
   [clojure.string :as str]
   [hyperfiddle.rcf :refer [tests]])
  (:import
   [org.commonmark.ext.gfm.strikethrough Strikethrough StrikethroughExtension]
   [org.commonmark.ext.gfm.tables TableBlock TableBody TableCell TableHead
                                  TableRow TablesExtension]
   [org.commonmark.ext.task.list.items TaskListItemMarker TaskListItemsExtension]
   [org.commonmark.node Block BlockQuote BulletList Code Document Emphasis
                        FencedCodeBlock HardLineBreak Heading HtmlBlock
                        HtmlInline Image IndentedCodeBlock Link ListBlock
                        ListItem OrderedList Paragraph SoftLineBreak
                        StrongEmphasis Text ThematicBreak]
   [org.commonmark.parser IncludeSourceSpans Parser]))
```

Build a shared parser instance (thread-safe per commonmark-java docs):

```clojure
(def ^:private parser
  (.. (Parser/builder)
      (includeSourceSpans IncludeSourceSpans/BLOCKS)
      (extensions [(TablesExtension/create)
                   (StrikethroughExtension/create)
                   (TaskListItemsExtension/create)])
      build))
```

Add a helper to extract the 0-based line number from a node:

```clojure
(defn- source-line
  "Return the 0-based source line of `node`, or nil if unavailable."
  [node]
  (let [spans (.getSourceSpans node)]
    (when (seq spans)
      (.getLineIndex (first spans)))))
```

Add a helper to extract plain text from a node tree (for image alt text):

```clojure
(defn- text-content
  "Recursively extract plain text from a node and its children."
  [node]
  (let [sb (StringBuilder.)]
    (loop [n node]
      (when n
        (cond
          (instance? Text n)          (.append sb (.getLiteral ^Text n))
          (instance? Code n)          (.append sb (.getLiteral ^Code n))
          (instance? SoftLineBreak n) (.append sb " ")
          (instance? HardLineBreak n) (.append sb "\n")
          :else (when-let [c (.getFirstChild n)]
                  (loop [child c]
                    (when child
                      (.append sb (text-content child))
                      (recur (.getNext child))))))
        (recur (.getNext n))))
    (str sb)))
```

**Verify**: Load the namespace in the REPL. Parse a simple markdown string,
confirm `.getSourceSpans` returns populated spans:

```clojure
(let [doc (.parse parser "# Hello\n\nworld")]
  (-> doc .getFirstChild .getSourceSpans first .getLineIndex))
;; => 0
```

## Step 2 — AST walker: children + dispatch

The core recursion. Walk children via `.getFirstChild` / `.getNext`, dispatch
on node type via `condp instance?`:

```clojure
(declare node->hiccup)

(defn- walk-children
  "Return a vector of hiccup forms for all children of `node`.
  `ctx` carries rendering context (e.g. tight-list? flag)."
  [ctx node]
  (loop [child (.getFirstChild node), acc []]
    (if child
      (let [h (node->hiccup ctx child)]
        (recur (.getNext child)
               (if (sequential? h)
                 ;; node->hiccup may return a vector of forms (e.g. tight paragraph)
                 ;; or a single hiccup vector. Distinguish by checking if the first
                 ;; element is a keyword (single hiccup) or not (seq of forms).
                 (if (keyword? (first h))
                   (conj acc h)
                   (into acc h))
                 (cond-> acc h (conj h)))))
      acc)))
```

`node->hiccup` is a multi-armed function (step 3 + 4). It returns:
- A hiccup vector like `[:p {:data-line 0} "text"]`
- A plain string (for `Text` nodes)
- A vector of forms (for tight-list paragraph unwrapping)
- `nil` (for nodes that produce no output, e.g. `LinkReferenceDefinition`)

## Step 3 — Block-level rendering

Implement `node->hiccup` cases for block nodes. Each block node gets
`{:data-line N}` from `source-line`:

```clojure
(defn- block-attrs
  "Return {:data-line N} for a block node, or {} if no source span."
  [node]
  (if-let [line (source-line node)]
    {:data-line line}
    {}))
```

| Node type          | Hiccup output                                           |
|--------------------|---------------------------------------------------------|
| `Document`         | `(into [:div] (walk-children ctx node))`                |
| `Heading`          | `[:hN (block-attrs node) ...children]`                  |
| `Paragraph`        | If tight-list: unwrap (return children directly). Else: `[:p (block-attrs node) ...children]` |
| `BlockQuote`       | `[:blockquote (block-attrs node) ...children]`          |
| `FencedCodeBlock`  | `[:pre (block-attrs node) [:code.language-X literal]]`  |
| `IndentedCodeBlock`| `[:pre (block-attrs node) [:code literal]]`             |
| `BulletList`       | `[:ul (block-attrs node) ...children]` (set `:tight?` in ctx) |
| `OrderedList`      | `[:ol (merge (block-attrs node) {:start N}) ...children]` (set `:tight?` in ctx) |
| `ListItem`         | `[:li ...children]` (no `data-line` — too noisy)        |
| `ThematicBreak`    | `[:hr (block-attrs node)]`                              |
| `HtmlBlock`        | Emit literal HTML via `hiccup2.core/raw` — or skip.     |

**Tight list handling**: When entering a `BulletList` or `OrderedList`, check
`(.isTight ^ListBlock node)` and pass `{:tight? true}` in ctx. The `Paragraph`
case checks `(:tight? ctx)` — if true, returns `(walk-children ctx node)`
directly (a seq of inline forms) instead of wrapping in `[:p ...]`.

**Code block language**: `(.getInfo ^FencedCodeBlock node)` returns the info
string (e.g. `"clojure"`, `"js"`, or `""`). Trim it, and if non-blank, use
`(keyword (str "code.language-" lang))` as the inner tag.

**RCF tests** after this step:

```clojure
(tests
  (md->hiccup "# Hello") := [:div [:h1 {:data-line 0} "Hello"]]

  (md->hiccup "para one\n\npara two")
  := [:div [:p {:data-line 0} "para one"] [:p {:data-line 2} "para two"]]

  ;; Fenced code block
  (md->hiccup "```clojure\n(+ 1 2)\n```")
  := [:div [:pre {:data-line 0} [:code.language-clojure "(+ 1 2)\n"]]]

  ;; Tight list — no <p> inside <li>
  (md->hiccup "- one\n- two")
  := [:div [:ul {:data-line 0} [:li "one"] [:li "two"]]]

  ;; Loose list — <p> inside <li>
  (md->hiccup "- one\n\n- two")
  := [:div [:ul {:data-line 0}
           [:li [:p {:data-line 0} "one"]]
           [:li [:p {:data-line 2} "two"]]]]

  :rcf)
```

## Step 4 — Inline-level rendering

Inline nodes are children of block nodes. They do not carry `data-line`.

| Node type          | Hiccup output                                    |
|--------------------|--------------------------------------------------|
| `Text`             | `(.getLiteral node)` (plain string)              |
| `Code`             | `[:code (.getLiteral node)]`                     |
| `Emphasis`         | `[:em ...children]`                              |
| `StrongEmphasis`   | `[:strong ...children]`                          |
| `Link`             | `[:a {:href dest} ...children]`                  |
| `Image`            | `[:img {:src dest :alt (text-content node)}]`    |
| `SoftLineBreak`    | `" "` (single space string)                      |
| `HardLineBreak`    | `[:br]`                                          |
| `HtmlInline`       | `(.getLiteral node)` (pass through — rare in .md)|

**Image in block context**: commonmark-java makes `Image` a child of
`Paragraph`. If a paragraph contains only an image (common pattern), the
`<p>` wrapper is standard HTML — keep it. No special unwrapping needed.

**RCF tests**:

```clojure
(tests
  ;; Bold + italic
  (md->hiccup "**bold** and *italic*")
  := [:div [:p {:data-line 0} [:strong "bold"] " and " [:em "italic"]]]

  ;; Inline code
  (md->hiccup "use `foo` here")
  := [:div [:p {:data-line 0} "use " [:code "foo"] " here"]]

  ;; Link
  (md->hiccup "[click](http://x)")
  := [:div [:p {:data-line 0} [:a {:href "http://x"} "click"]]]

  ;; Image
  (md->hiccup "![alt text](img.png)")
  := [:div [:p {:data-line 0} [:img {:src "img.png" :alt "alt text"}]]]

  :rcf)
```

## Step 5 — GFM extension nodes

Handle the three extensions we load (tables, strikethrough, task list items).

### Tables

`TableBlock` → `[:table (block-attrs) [:thead ...] [:tbody ...]]`
`TableHead` → `[:thead ...rows]`
`TableBody` → `[:tbody ...rows]`
`TableRow` → `[:tr ...cells]`
`TableCell` → `[:th ...]` or `[:td ...]` based on `.isHeader()`

Alignment: `(.getAlignment ^TableCell node)` returns a `TableCell$Alignment`
enum (`LEFT`, `CENTER`, `RIGHT`, or `null`). Emit as
`{:style {:text-align "left"}}` when non-null.

### Strikethrough

`Strikethrough` → `[:s ...children]` (inline node, no `data-line`)

### Task list items

`TaskListItemMarker` → `[:input {:type "checkbox" :checked (.isChecked node)}]`

This node appears as the first child of a `ListItem` in a task list. The
`ListItem` itself is still rendered as `[:li ...]`.

**RCF tests**:

```clojure
(tests
  ;; Table
  (let [h (md->hiccup "| a | b |\n|---|---|\n| 1 | 2 |")]
    (first (second h))) := :table

  ;; Strikethrough
  (md->hiccup "~~deleted~~")
  := [:div [:p {:data-line 0} [:s "deleted"]]]

  ;; Task list
  (let [h (md->hiccup "- [x] done\n- [ ] todo")]
    ;; First li should contain a checked input
    (some #(= [:input {:type "checkbox" :checked true}] %) (flatten h)))
  := true

  :rcf)
```

## Step 6 — Public API

Single entry point:

```clojure
(defn md->hiccup
  "Parse markdown text and return a Hiccup vector.
  Block-level elements carry {:data-line N} (0-based source line).
  Uses commonmark-java with GFM extensions (tables, strikethrough, task lists)."
  [markdown-text]
  (let [doc (.parse parser (or markdown-text ""))]
    (node->hiccup {} doc)))
```

## Step 7 — Wire into search.clj

**File**: `winze-server/src/llm_memory/ui/search.clj`

1. Replace the `nextjournal.markdown` require with `llm-memory.ui.hiccup`:

   ```clojure
   ;; Remove:
   [nextjournal.markdown :as md]
   ;; Add:
   [llm-memory.ui.hiccup :as hiccup]
   ```

2. Delete the `md->hiccup` function (lines 16–19).

3. Replace call sites:

   ```clojure
   ;; result-card (line 249):
   [:div.result-body (hiccup/md->hiccup (or text ""))]

   ;; file-page (line 310):
   [:div.result-body (hiccup/md->hiccup markdown-text)]
   ```

4. Check whether `nextjournal/markdown` is used elsewhere in `winze-server/`.
   If not, it can be removed from `deps.edn`. (The `clj-llm-memory/` library
   may still use it for chunking — check before removing.)

**Verify**: Open the app, search for a document, open a file tab. Visual
appearance should be identical to before. Right-click → Inspect in the Browser
to confirm `data-line` attributes on `<p>`, `<h1>`, `<pre>`, etc.

## Step 8 — Visual comparison

Screenshot the file viewer before and after the change. The two screenshots
should be visually identical. Differences to watch for:

- **Tight vs. loose list spacing** — if lists render with unexpected `<p>`
  wrappers (or missing ones), the tight-list logic needs adjustment.
- **Code block language classes** — verify `class="language-clojure"` appears
  on the `<code>` element so syntax-specific CSS can target it.
- **Table cell alignment** — verify tables with alignment markers (`|:---|`,
  `|---:|`) render with correct `text-align` styles.

## Files changed

| File | Change |
|------|--------|
| `src/llm_memory/ui/hiccup.clj` | **New** — commonmark-java → Hiccup renderer |
| `src/llm_memory/ui/search.clj` | Replace `nextjournal.markdown` with `llm-memory.ui.hiccup` |
| `deps.edn` | Remove `nextjournal/markdown` if unused elsewhere |

## Dependency note

`nextjournal/markdown` transitively depends on `org.commonmark/commonmark`
0.24.0 plus the GFM extension JARs. Once we stop requiring
`nextjournal.markdown`, those transitive deps disappear. We must declare the
commonmark-java deps directly in `deps.edn`:

```clojure
org.commonmark/commonmark                       {:mvn/version "0.24.0"}
org.commonmark/commonmark-ext-gfm-tables        {:mvn/version "0.24.0"}
org.commonmark/commonmark-ext-gfm-strikethrough {:mvn/version "0.24.0"}
org.commonmark/commonmark-ext-task-list-items   {:mvn/version "0.24.0"}
```

If `nextjournal/markdown` is retained (e.g. for `clj-llm-memory`), these are
already on the classpath transitively and explicit deps are optional but
recommended for clarity.
