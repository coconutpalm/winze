---
group: live-search
type: plan
related: winze-ctabfolder-ui
---

# Live Search Implementation — Plan

Implement the `llm-memory.ui.search/results` function: debounced live search that renders results as styled HTML in the SWT Browser widget. Pure Hiccup pipeline — page structure is hand-written Hiccup, chunk body markdown is parsed to Hiccup via nextjournal/markdown.

## Step 1: Add dependencies

**File**: `winze-server/deps.edn`

Add to `:deps`:

```clojure
hiccup/hiccup                          {:mvn/version "2.0.0"}
io.github.nextjournal/markdown         {:mvn/version "0.7.222"}
```

- **Hiccup 2.x** — page structure as Clojure data (`[:div ...]` → HTML string via `hiccup2.core/html`)
- **nextjournal/markdown** — chunk markdown → hiccup vectors (brings commonmark-java transitively)

**Verify**: Start nREPL, confirm both load:
```clojure
(require '[hiccup2.core :as h])
(require '[nextjournal.markdown :as md])
(md/->hiccup (md/parse "**hello**"))
;; => [:div [:p [:strong "hello"]]]
```

## Step 2: Implement markdown→hiccup for chunk body

**File**: `winze-server/src/llm_memory/ui/search.clj`

Write a `md->hiccup` function for converting `:chunk/text` markdown to hiccup data:

```clojure
(defn- md->hiccup [markdown-text]
  (md/->hiccup (md/parse markdown-text)))
```

The result is a hiccup vector (e.g. `[:div [:p ...]]`) that composes directly into the page template — no `h/raw` needed. The outer `[:div ...]` wrapper from nextjournal can be destructured or used as-is depending on styling needs.

Custom renderers can be added later via `md/default-hiccup-renderers` (e.g. for code block syntax highlighting).

## Step 3: Build Hiccup page template

Compose the entire HTML page as Hiccup data structures. Three functions:

### `page-css` — embedded stylesheet (returns a string)

Uses the Winze brand palette (see `resources/branding/BRAND-GUIDE.md`):

- **Page background**: Mine Shaft `#1E1B2E` / Bedrock `#0E0D18` for deepest areas
- **Card background**: Obsidian Purple `#241E5E` with Deep Amethyst `#3A2F80` border
- **Body text**: Crystal White `#E8E0FF`
- **File paths / secondary text**: Amethyst `#9B8FE0`
- **Links**: Amethyst `#9B8FE0` (accent color per brand guide)
- **Relevance badges**: Lavender Crystal `#C4B8FF` (strong — "the sparkle means found it"), Deep Violet `#7B6FC0` (partial), Indigo `#4A3F90` (weak)
- **Metadata pills**: Royal Purple `#5548A0` background, Crystal White `#E8E0FF` text
- **Code blocks**: `<pre>` / `<code>` on Bedrock `#0E0D18` background, monospace, Lavender Crystal `#C4B8FF` text
- **Typography**: Inter (primary), system-ui fallback. Body 400, headings 500
- **Headings in chunk body**: Amethyst `#9B8FE0`

### `result-card` — single result (returns Hiccup vector)

```clojure
(defn- result-card [{:keys [file/path chunk/text relevance] :as result}]
  [:div.result-card
   [:div.result-header
    [:span.file-path path]
    [:span.relevance-badge {:class (relevance-class relevance)}
     (format "%.0f%%" (* 100 relevance))]]
   (metadata-pills result)
   [:div.result-body (md->hiccup text)]])
```

### `results-page` — full HTML document (returns HTML string)

```clojure
(defn- results-page [results query-string]
  (str
   (h/html
    [:html
     [:head [:style (page-css)]]
     [:body
      [:div.header (format "%d results for \"%s\"" (count results) query-string)]
      (for [r results] (result-card r))]])))
```

### `empty-page` — placeholder when query is blank or too short

```clojure
(defn- empty-page []
  (str (h/html [:html [:head [:style (page-css)]]
                [:body [:div.empty "Type to search plans..."]]])))
```

## Step 4: Implement debouncing

Add a debounce mechanism so search only fires after the user pauses typing:

1. Use a `ScheduledExecutorService` (single-thread) — `def` at namespace level
2. Track the pending task in an `atom` (holding a `ScheduledFuture`)
3. On each call to `results`:
   - Cancel any pending future
   - Schedule a new task with 300ms delay
   - The task runs the search and pushes HTML to the browser

```clojure
(def ^:private debounce-executor
  (java.util.concurrent.Executors/newSingleThreadScheduledExecutor))

(def ^:private pending-search (atom nil))
```

## Step 5: Implement `results` function

Wire everything together:

```clojure
(defn results [query-string browser-widget]
  ;; Cancel any pending search
  (when-let [fut @pending-search]
    (.cancel fut false))
  ;; Handle empty/short queries immediately
  (if (< (count (str/trim query-string)) 3)
    (.setText browser-widget (empty-page))
    ;; Schedule debounced search
    (reset! pending-search
      (.schedule debounce-executor
        (fn []
          (let [store   (server/store)
                hits    (core/search store query-string
                                     {:top 10 :dedupe true})
                html    (results-page hits query-string)]
            ;; Push to browser on UI thread
            (async-exec! #(.setText browser-widget html))))
        300 TimeUnit/MILLISECONDS))))
```

**Key points**:
- `server/store` retrieves the live PlanStore from server state
- Search runs on the executor thread (off UI thread — avoids freezing the event loop)
- `async-exec!` marshals the `.setText` call back to the UI thread
- Minimum 3 characters before searching (avoids noisy single-char vector lookups)

## Step 6: REPL verification ✓

1. Connect to the running Winze nREPL (port 54561 from uberjar)
2. Dynamically add deps via `clojure.repl.deps/add-libs` (uberjar supports this — see CONTEXT)
3. Load source via `load-file` (not `require :reload` — see CONTEXT for why)
4. Simulate typing via `(ui (.setText (mw/element :search) "query"))` — fires `modify-text`
5. Screenshot via `(ui (ui-util/screenshot-widget! (mw/element :live-search-results) "/tmp/test.png"))`

**Tested edge cases**:
- "gpu report cost" → 10 result cards with metadata pills, rendered markdown ✓
- "datahike caching" → caching docs with status/type/group badges ✓
- "K8s deployment ArgoCD" → deployment docs ✓
- Empty string → "Type to search plans…" placeholder ✓
- "ab" (2 chars) → placeholder (below 3-char minimum) ✓

## Step 7: Visual polish (pending, iterate from REPL)

- Take screenshots via `screenshot-widget!` to verify layout
- Adjust CSS spacing, colors, font sizes
- Consider highlighting query terms in results (post-render string replacement or CSS)

## Architecture Summary

```
keystroke → modify-text event → async-exec! → results (UI thread)
                                                  │
                                            cancel pending
                                            schedule 300ms
                                                  │
                                          ┌───────▼─────────────┐
                                          │   executor thread    │
                                          │                      │
                                          │ server/store         │
                                          │ core/search          │
                                          │ md/parse → md/->hiccup │
                                          │ h/html (full page)   │
                                          └───────┬─────────────┘
                                                  │
                                            async-exec!
                                                  │
                                          ┌───────▼────────┐
                                          │   UI thread     │
                                          │ .setText html   │
                                          └────────────────┘
```

## Files Modified

| File | Change |
|------|--------|
| `winze-server/deps.edn` | Add hiccup + nextjournal/markdown dependencies |
| `winze-server/src/llm_memory/ui/search.clj` | Full implementation |

## Risks

- **Hiccup 2.0.0**: GA release, stable.
- **nextjournal/markdown version**: 0.7.222 is the latest release. Used in production by Clerk. Stable API.
- **Embedding latency**: First search after cold start may be slow (~500ms) while the model warms up. Subsequent searches should be ~50–200ms.
- **Browser widget `.setText` size**: Very large HTML documents could be slow to render. Capping at 10 results (with deduplication) should keep this manageable.
- **Thread safety**: `core/search` reads from Datalevin which is thread-safe. The `ScheduledExecutorService` serializes searches naturally. No shared mutable state except the `pending-search` atom (which only holds a `ScheduledFuture`).
