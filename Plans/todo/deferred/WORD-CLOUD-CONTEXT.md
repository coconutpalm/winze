# Word Cloud for Empty Search Page — Context

## Goal

Replace the static "Type to search…" placeholder on the Winze search tab with a clickable word cloud showing prominent indexed concepts. Clicking a word fills the search field and triggers a live search.

## Current State

### Empty Page

`winze-server/src/llm_memory/ui/search.clj:empty-page` returns a minimal HTML page with a centered "Type to search…" message. It is rendered into the SWT `Browser` widget (`:ui/live-search-results`) whenever the search field is blank or fewer than 3 characters.

### Search Tab Wiring

- **`main_window.clj`** builds the UI:
  - `header` → `Text` widget (`:ui/search`) with `e/modify-text` listener
  - `body` → `CTabFolder` containing a `Browser` (SWT/WEBKIT, `:ui/live-search-results`)
  - On every keystroke: `(search/results (.getText search-field) browser-widget)`
- **`search.clj:results`** debounces (300ms), runs `core/search` off-UI-thread, renders HTML via `results-page`, and pushes to the Browser via `async-exec!`.
- When query < 3 chars, it calls `(empty-page)` directly.

### Data Layer

All chunk text is queryable via Datalevin Datalog through `store/query`:

```clojure
;; All chunk text + file metadata
(store/query store
  '[:find ?text ?status ?type ?group
    :where
    [?c :chunk/text ?text]
    [?c :chunk/file ?f]
    [?f :file/status ?status]
    [?f :file/type ?type]
    [(get-else $ ?f :file/group "") ?group]])
```

File metadata available for filtering/scoping: `:file/status`, `:file/type`, `:file/group`, `:file/jira`.

Chunks are split at H2 boundaries with H1 title prepended. Slugs (`:chunk/slug`) are derived from H2 headings.

### Available Dependencies

- `hiccup2.core` — already used for HTML generation
- `nextjournal.markdown` — already used for markdown rendering
- No NLP/stemming libraries currently in the dependency tree

## Design Constraints

- **SWT threading**: All widget mutations must happen on the UI thread via `async-exec!`. Term extraction must run off the UI thread.
- **Browser widget**: Renders HTML — the word cloud is an HTML page, same as search results.
- **Performance**: The store may have hundreds of chunks. Term extraction should be fast enough to not block startup. Consider caching.
- **No new dependencies**: Use basic tokenization (split on whitespace/punctuation, lowercase, stopword filter). Stemming/NLP libraries are unnecessary for a word cloud of planning document terms.

## Key APIs

### `llm-memory.store.protocol/query`

```clojure
;; Single-arg: Datalog query against the store
(store/query store '[:find [?text ...] :where [?c :chunk/text ?text]])

;; Two-arg: Datalog query with parameters
(store/query store
  '[:find [?text ...] :in $ ?status :where
    [?c :chunk/text ?text] [?c :chunk/file ?f] [?f :file/status ?status]]
  {:status "active"})
```

### `llm-memory.server.main/store`

Returns the active `PlanStore` instance. Used by `search.clj` to access the store from the UI thread.

### Existing `winze:search?` Handler (already implemented)

`custom-browser` in `main_window.clj:51-81` already intercepts `winze:search?q=...` URLs via CDT's `(on e/changing ...)`:

```clojure
(str/starts-with? loc "winze:search?")
(do (set! (.-doit event) false)
    (let [params (parse-query-string (subs loc (count "winze:search?")))
          q      (get params "q")]
      (async-exec!
       (fn []
         (.setSelection (element :main-folder) 0)
         (.setText (element :search) q)))))
```

This cancels navigation, URL-decodes the query, switches to the search tab, and sets the search Text widget's text. Because `.setText` fires `modify-text`, this implicitly triggers `search/results` via the listener on `:ui/search`.

**No new LocationListener is needed.** Word cloud links using `href="winze:search?q=..."` will work out of the box with the existing handler.

## Click-to-Search Interaction Flow

```
User sees word cloud in Browser
  → clicks "deployments"
  → Browser navigates to "winze:search?q=deployments"
  → custom-browser's (on e/changing ...) fires
  → Clojure handler:
      1. event.doit = false (cancel navigation)
      2. (.setSelection main-folder 0) — switch to search tab
      3. (.setText search-text-widget "deployments")
      4. modify-text listener fires → (search/results "deployments" browser-widget)
```

## Term Extraction Approach

1. **Query all chunk text** from the store via Datalog
2. **Tokenize**: split on `\W+`, lowercase, filter length < 4
3. **Filter stopwords**: standard English stopwords + markdown artifacts (`http`, `https`, `html`, `clj`, etc.)
4. **Count frequencies** across all chunks
5. **Take top N** (60–80 terms)
6. **Map frequency → font size** (logarithmic scale, range ~12px–48px)
7. **Assign colors** from the brand palette (cycle through lavender/amethyst/deep-violet)
8. **Shuffle order** for visual variety (not alphabetical, not by frequency)

### Caching

Recompute term frequencies:
- On first call (lazy init)
- When the store changes (detect via chunk count or a generation counter)
- Not on every `empty-page` call — cache the HTML string

## Files to Modify

| File | Change |
|------|--------|
| `winze-server/src/llm_memory/ui/search.clj` | Add term extraction, word cloud HTML generation, update `empty-page` to accept store |
| `winze-server/src/llm_memory/ui/main_window.clj` | Update `empty-page` call in `body` to pass `(server/store)` |
