---
created: 2026-03-30
status: complete
related: [complete/view-edit-scroll-sync/CONTEXT.md, PLAN.md, dev/DAVE.md]
tags: [markdown, hiccup, commonmark, rendering, swt, ui]
---

# Custom Markdown → Hiccup Renderer — Context

## Goal

Replace `nextjournal/markdown`'s `md->hiccup` pipeline with a custom renderer
that walks the commonmark-java AST directly and produces Hiccup vectors
annotated with source line numbers (`{:data-line N}`). The output remains pure
Hiccup data — no raw HTML strings, no SWT objects.

## Why

The [VIEW-EDIT-SCROLL-SYNC-CONTEXT.md](VIEW-EDIT-SCROLL-SYNC-CONTEXT.md)
feature needs `data-line` attributes on block-level HTML elements so the
Browser and StyledText can synchronize scroll position by source line number.

`nextjournal/markdown` wraps commonmark-java but discards source position info
during its `open-node` / `close-node` visitor pass. The Clojure AST and hiccup
renderers never see line numbers. Monkey-patching the library's multimethods
is fragile; post-processing hiccup with text-matching is unreliable.

commonmark-java's `Parser` with `IncludeSourceSpans/BLOCKS` gives every `Block`
node a `SourceSpan` with `.getLineIndex()` (0-based). Writing a thin Clojure
walker over this AST gives us line info natively, full control over the hiccup
output, and a foundation for future features:

- **Scroll sync** — `data-line` attributes (immediate need)
- **Cmd+click link navigation** — href interception in the Browser (DAVE.md)
- **Wiki links** — custom inline syntax handling (DAVE.md)
- **Tag search** — `#tag` detection from body text (DAVE.md)

## commonmark-java AST

### Enabling source spans

```java
Parser.builder()
      .includeSourceSpans(IncludeSourceSpans.BLOCKS)
      .extensions(exts)
      .build()
```

`IncludeSourceSpans/BLOCKS` annotates every `Block` subclass node. Inline nodes
do not get source spans (they are children of block nodes and inherit the parent
line). `BLOCKS_AND_INLINES` exists but is unnecessary for our needs — the
scroll sync feature only needs block-level line numbers.

### Node hierarchy

The AST is a tree of `Node` objects linked via `.getFirstChild()` /
`.getNext()` (sibling chain). Every node has:

- `.getSourceSpans()` → `List<SourceSpan>` (empty for inlines unless enabled)
- `.getFirstChild()` / `.getLastChild()` / `.getNext()` / `.getPrevious()`
- `.getParent()`

Block nodes (subclasses of `Block`):

| Java class           | Hiccup tag      | Data from node            |
|----------------------|-----------------|---------------------------|
| `Document`           | `:div`          | (root container)          |
| `Heading`            | `:h1`…`:h6`     | `.getLevel()`             |
| `Paragraph`          | `:p`            |                           |
| `BlockQuote`         | `:blockquote`   |                           |
| `FencedCodeBlock`    | `:pre > :code`  | `.getLiteral()`, `.getInfo()` (language) |
| `IndentedCodeBlock`  | `:pre > :code`  | `.getLiteral()`           |
| `BulletList`         | `:ul`           |                           |
| `OrderedList`        | `:ol`           | `.getMarkerStartNumber()` |
| `ListItem`           | `:li`           |                           |
| `ThematicBreak`      | `:hr`           |                           |
| `HtmlBlock`          | raw HTML        | `.getLiteral()`           |

Inline nodes (children of block nodes):

| Java class         | Hiccup tag   | Data from node                       |
|--------------------|--------------|--------------------------------------|
| `Text`             | (string)     | `.getLiteral()`                      |
| `Code`             | `:code`      | `.getLiteral()`                      |
| `Emphasis`         | `:em`        |                                      |
| `StrongEmphasis`   | `:strong`    |                                      |
| `Link`             | `:a`         | `.getDestination()`, `.getTitle()`   |
| `Image`            | `:img`       | `.getDestination()`, `.getTitle()`   |
| `SoftLineBreak`    | `" "`        |                                      |
| `HardLineBreak`    | `:br`        |                                      |
| `HtmlInline`       | raw HTML     | `.getLiteral()`                      |

GFM extension nodes (from extension JARs):

| Java class             | Hiccup tag         | Data from node             |
|------------------------|--------------------|----------------------------|
| `TableBlock`           | `:table`           |                            |
| `TableHead`            | `:thead`           |                            |
| `TableBody`            | `:tbody`           |                            |
| `TableRow`             | `:tr`              |                            |
| `TableCell`            | `:th` / `:td`      | `.isHeader()`, `.getAlignment()` |
| `Strikethrough`        | `:s`               |                            |
| `TaskListItemMarker`   | `:input[checkbox]` | `.isChecked()`             |

### Walking the AST

Rather than using the `Visitor` pattern (which requires implementing every
`visit` overload), we can walk the tree structurally using `.getFirstChild()`
and `.getNext()`:

```clojure
(defn- walk-children
  "Return a seq of hiccup vectors for all children of `node`."
  [node]
  (loop [child (.getFirstChild node), acc []]
    (if child
      (recur (.getNext child) (conj acc (node->hiccup child)))
      acc)))
```

Dispatch on node type using `condp instance?` (or a protocol, though `condp`
is simpler for a finite set of types).

## Hiccup output format

Block elements carry `{:data-line N}` where N is the 0-based source line:

```clojure
;; Input: "# Hello\n\nSome text"
[:div
 [:h1 {:data-line 0} "Hello"]
 [:p {:data-line 2} "Some text"]]
```

Inline elements do not carry `:data-line` — they inherit their parent block's
line.

Code blocks include the language as a class:

```clojure
[:pre {:data-line 4}
 [:code.language-clojure "(+ 1 2)"]]
```

## Current usage

`md->hiccup` appears in two places in `search.clj`:

| Call site       | Line | Purpose                          | Needs `data-line`? |
|-----------------|------|----------------------------------|--------------------|
| `result-card`   | 249  | Search result snippet rendering  | No (harmless)      |
| `file-page`     | 310  | Full file viewer in Browser tab  | **Yes**            |

The new renderer replaces both call sites. The `data-line` attributes are
invisible in `result-card` (the Browser ignores unknown data attributes) and
essential in `file-page`.

## CSS compatibility

The existing CSS in `page-css` targets block-level HTML tags within
`.result-body`:

```css
.result-body h1 { ... }
.result-body p { ... }
.result-body pre code { ... }
.result-body blockquote { ... }
.result-body table { ... }
```

All selectors are **tag-based**, not class-based. As long as the new renderer
emits the same HTML tags, the CSS applies unchanged. The only exception is
code blocks: nextjournal emits `<code class="language-X">` which the new
renderer should match (using Hiccup's `.class` shorthand on the `:code`
keyword).

## Namespace

```
llm-memory.ui.hiccup   — commonmark-java → Hiccup with data-line attributes
```

Single namespace. Dependencies:
- `org.commonmark.parser.Parser` + `IncludeSourceSpans`
- `org.commonmark.node.*` (all node types)
- `org.commonmark.ext.gfm.tables.*`
- `org.commonmark.ext.gfm.strikethrough.Strikethrough`
- `org.commonmark.ext.task.list.items.TaskListItemMarker`
- `clojure.string` (for `.getInfo()` trimming, alt-text extraction)
- `hyperfiddle.rcf` (inline tests)

No SWT, hiccup2, or UI dependencies. The output is plain Clojure vectors —
the caller (search.clj) wraps them in `h/html`.

## Tight vs. loose lists

commonmark distinguishes tight and loose lists. In a **tight** list, `ListItem`
children are `Paragraph` nodes that should **not** be wrapped in `<p>` — their
content is rendered directly inside `<li>`. In a **loose** list, paragraphs
stay wrapped.

`ListBlock.isTight()` reports this. The renderer must check `(.isTight list)`
and, for tight lists, unwrap `Paragraph` children of `ListItem` (emit their
inline children directly rather than wrapping in `:p`).

nextjournal/markdown handles this with a `*in-tight-list?*` dynamic var. We
can do the same, or pass a context map through the recursion.

## Image handling

commonmark-java's `Image` is an inline node whose children are the alt-text
(may contain `Text`, `Code`, `Emphasis` etc.). The hiccup output should be:

```clojure
[:img {:src destination :alt (text-content node)}]
```

Where `text-content` recursively extracts plain text from child nodes. Images
that are direct children of `Document` (block-level images) should be wrapped
in `[:p {:data-line N} ...]` to match commonmark HTML spec behavior.

## What this replaces

After this work:
- `nextjournal/markdown` is no longer imported in `search.clj`
- `md->hiccup` calls are replaced with the new renderer's entry point
- The `nextjournal/markdown` dep stays in `deps.edn` only if other code uses
  it (check `clj-llm-memory/` — the chunker may use `md/parse`)
- No behavioral changes to search results or file viewer rendering, except that
  block elements now carry `data-line` attributes
