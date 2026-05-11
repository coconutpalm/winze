# Anchor Link Navigation — Context

## Problem Statement

Internal markdown links (e.g. Table of Contents entries like
`[Universal Pipeline Architecture](#universal-pipeline-architecture)`) do not
navigate to their target headings when clicked in Winze's file viewer. The links
render correctly as `<a href="#heading-slug">` but nothing happens on click.

This is most visible in documents with a Table of Contents, such as
`CLOUD-GPU-GUIDE.md` which has ~14 non-functional ToC links.

## Root Cause

Two gaps in the rendering pipeline combine to break anchor navigation:

### 1. Headings have no `id` attribute

`render-heading` in `winze-server/src/llm_memory/ui/hiccup.clj:99-104` produces:

```clojure
[:h2 {:data-line 29} "Universal Pipeline Architecture"]
```

Which renders as:

```html
<h2 data-line="29">Universal Pipeline Architecture</h2>
```

HTML anchor navigation requires a matching `id` on the target element:

```html
<h2 id="universal-pipeline-architecture" data-line="29">Universal Pipeline Architecture</h2>
```

The `block-attrs` helper (line 46-52) only emits `:data-line` — it has no
slug-generation logic.

### 2. LocationListener doesn't handle fragment navigation

The `custom-browser` LocationListener in
`winze-server/src/llm_memory/ui/main_window.clj:75-106` handles two URL schemes:

- `winze:open-file?...` → opens a file in a new tab
- `winze:search?...` → executes a search

Fragment-only URLs (`#heading-slug`) fall through with no handler. WebKit's
default behavior is to scroll to the element with the matching `id` — but since
no element has an `id`, nothing happens.

## Prior Work

The md-link-rewrite feature (`Plans/complete/swt-ui/md-link-rewrite/`) explicitly
noted that fragment-only links are NOT rewritten (preserved as-is), expecting the
browser's native anchor navigation to handle them. But headings were never given
`id` attributes to make that work.

The `text-content` helper (`hiccup.clj:55-69`) already extracts plain text from
AST nodes — this is the building block for slug generation.

## Affected Components

| File | Function | Change needed |
|------|----------|---------------|
| `winze-server/src/llm_memory/ui/hiccup.clj` | `render-heading` (L99-104) | Add `:id` slug to heading attrs |
| `winze-server/src/llm_memory/ui/hiccup.clj` | (new) | Slug generation function |
| `winze-server/src/llm_memory/ui/hiccup.clj` | tests (L269-358) | Update heading test expectations |

## Slug Generation Rules

GitHub-Flavored Markdown (GFM) slug convention — the de facto standard:

1. Extract plain text content from the heading (strip inline markup)
2. Downcase
3. Replace spaces and consecutive whitespace with `-`
4. Strip characters that are not alphanumeric, `-`, or `_`
5. Strip leading/trailing hyphens

Examples:
- `## Universal Pipeline Architecture` → `universal-pipeline-architecture`
- `## Step 3 — Extract H1 heading` → `step-3--extract-h1-heading`
- `### `oci-build` Pipeline` → `oci-build-pipeline`

## Scope

This fix is entirely within `hiccup.clj`. No changes to `main_window.clj` are
needed — WebKit's native fragment navigation will work once headings have `id`
attributes. No changes to `search.clj` or CSS are needed.

## Risks

- **Scroll sync**: The `data-line` attribute system for view↔edit scroll sync is
  independent of `id` — adding `id` does not interfere.
- **Duplicate headings**: If two headings produce the same slug, the browser
  navigates to the first. GFM appends `-1`, `-2` etc. to disambiguate. This is
  a nice-to-have but not essential for the initial fix — duplicate headings in
  planning documents are rare.
