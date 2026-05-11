# Clickable Metadata Tag Search — Plan

## Phase 1: Schema & Indexing (clj-llm-memory)

### Step 1: Add `:file/created` to Schema (`store/datalevin.clj`)

Add to the schema map:

```clojure
:file/created {:db/valueType :db.type/string}
```

### Step 2: Map `:fm/created` → `:file/created` During Indexing (`index.clj`)

In the same locations where `:fm/tags` → `:file/tags` and `:fm/related` → `:file/related` are mapped (lines ~250-251 and ~457-458), add:

```clojure
(:fm/created merged) (assoc :file/created (:fm/created merged))
```

### Step 3: Surface New Fields in Search Results (`core.clj`)

In the `search` function's enrichment map (lines ~207-221), add alongside the existing `when` clauses:

```clojure
(when (:file/tags file)    {:file/tags (:file/tags file)})
(when (:file/related file) {:file/related (:file/related file)})
(when (:file/created file) {:file/created (:file/created file)})
```

### Step 4: Add `:jira` Filter to `core/search` (`core.clj`)

One-line addition to the existing filter chain:

```clojure
(or (nil? jira) (= jira (:file/jira r)))
```

Update the destructuring to include `:jira` from `opts`.

### Step 5: Pure Metadata Query (`core.clj`)

Add a function for tag-only searches (no semantic query text). Per CLAUDE.md conventions, decompose into small functions (~10 lines max). The result-enrichment logic mirrors `search` — extract a shared helper.

```clojure
(defn metadata-query
  "Query files by metadata filters only, without semantic search.
   Returns results in the same shape as `search`."
  [store opts]
  ...)
```

Returns results sorted by modification date (most recent first) since there's no semantic relevance to sort by. Uses the same shape as `search` so the UI renders them identically.

### Step 6: Re-index

After Steps 1-3, run `index_plans` with `reset: true` to populate `:file/created` from existing YAML frontmatter. One-time operation after the schema change.

## Phase 2: Query Parser (search.clj)

### Step 7: Query Parser (`search.clj`)

Add a function to split a search string into free text and metadata tags:

```clojure
(def ^:private status-values #{"active" "complete" "deferred"})
(def ^:private type-values   #{"context" "plan" "story" "report" "codemap"
                                "results" "info" "jira" "index" "tracker"})
(def ^:private jira-pattern  #"(?i)AAO-\d+")

(defn- classify-tag
  "Map a tag value to its filter key."
  [tag]
  (let [v (str/lower-case tag)]
    (cond
      (status-values v)             [:status v]
      (type-values v)               [:type v]
      (re-matches jira-pattern tag) [:jira (str/upper-case tag)]
      :else                         [:group v])))

(defn- parse-query
  "Parse a search string into {:text \"...\" :filters {:status \"...\" ...}}.
   Tokens prefixed with # are metadata filters; the rest is the semantic query."
  [raw]
  (let [tokens (str/split (str/trim raw) #"\s+")
        {tags true text false} (group-by #(str/starts-with? % "#") tokens)
        filters (into {}
                      (map (fn [t] (classify-tag (subs t 1))))
                      tags)]
    {:text    (str/join " " (or text []))
     :filters filters}))
```

### Step 8: Update `results` to Use Parser (`search.clj`)

Replace the direct `core/search` call with the parsed query:

```clojure
(let [{:keys [text filters]} (parse-query q)
      opts    (merge {:top 10 :dedupe true} filters)
      hits    (if (str/blank? text)
                (core/metadata-query store opts)
                (core/search store text opts))
      html    (results-page hits q filters)]
  (async-exec! #(.setText browser-widget html)))
```

Update the minimum-length check — skip when tags are present:

```clojure
(let [{:keys [text filters]} (parse-query q)]
  (if (and (< (count text) 3) (empty? filters))
    (async-exec! #(.setText browser-widget (empty-page)))
    ;; proceed with debounced search
    ...))
```

**Thread context**: `parse-query` is pure (no widget access), safe on any thread. Only `.setText` is marshalled to the UI thread via `async-exec!`.

## Phase 3: Card Redesign (search.clj)

### Step 9: Redesign `result-card`

Replace the current card header and `metadata-pills` with the new layout:

**Line 1** — Status indicator + file path + created date + relevance:

```clojure
[:div.result-header
 [:span.status-indicator
  (case (:file/status result)
    "active"   "☐ "
    "complete" "☑ "
    "")]
 [:a.file-path {...} (or path "—")]
 (when (:file/created result)
   [:span.created-date (str " (" (:file/created result) ")")])
 [:span {:class (relevance-class relevance)}
  (format "%.0f%%" (* 100.0 relevance))]]
```

**Line 2** — Clickable pills for group, related, tags:

```clojure
(defn- metadata-pills
  "Render metadata pills as clickable search links."
  [result active-filters]
  (let [group-pills   (when (:file/group result)
                         [(:file/group result)])
        related-pills (when (:file/related result)
                         (map str/trim (str/split (:file/related result) #",")))
        tag-pills     (when (:file/tags result)
                         (map str/trim (str/split (:file/tags result) #",")))
        all-pills     (concat (or group-pills [])
                               (or related-pills [])
                               (or tag-pills []))
        active-vals   (set (vals active-filters))]
    (when (seq all-pills)
      [:div.pills
       (for [p all-pills]
         [:a {:class (str "pill" (when (active-vals p) " pill-active"))
              :href  (str "winze:search?q="
                           (URLEncoder/encode (str "#" p) "UTF-8"))}
          (str "#" p)])])))
```

Note: pills display with a `#` prefix (e.g., `#gpu-report`) as a UI affordance indicating to users that they can type that syntax themselves in the search box. The `href` includes `#` URL-encoded so clicking triggers a tag search.

### Step 10: CSS Updates (`search.clj`)

Add styles for new elements:

```css
.status-indicator { font-size: 14px; margin-right: 4px; }
.created-date { font-size: 11px; color: {deep-violet}; margin-left: 6px; }
a.pill { text-decoration: none; cursor: pointer; color: inherit; }
a.pill:hover { opacity: 0.7; }
.pill-active { background: {lavender}; color: {mine-shaft}; }
```

### Step 11: Thread `active-filters` Through Rendering

Pass the parsed `filters` through `results-page` → `result-card` → `metadata-pills` so active pills are visually highlighted.

## Phase 4: Click Handler (main_window.clj)

### Step 12: Add `winze:search?q=` Handler

Add a branch to `custom-browser`'s `on e/changing` handler. Convert the existing `when` to a `cond`:

```clojure
(on e/changing [props parent event]
    (let [loc (.location event)]
      (cond
        (str/starts-with? loc "winze:open-file?")
        (do (set! (.-doit event) false)
            ;; ... existing file-open logic unchanged ...
            )

        (str/starts-with? loc "winze:search?")
        (do (set! (.-doit event) false)
            (let [params (parse-query-string (subs loc (count "winze:search?")))
                  q      (get params "q")]
              (async-exec!
               (fn []
                 (.setSelection (element :main-folder) 0)
                 (.setText (element :search) q))))))))
```

**SWT-UI-GUIDE rules applied:**
- `(set! (.-doit event) false)` is synchronous — cancels navigation before handler returns
- Widget mutations (`.setText`, `.setSelection`) inside `async-exec!` for thread safety (§14)
- Widget access via `(element :key)` pattern
- `.setText` fires `modify-text` → `search/results` automatically — do NOT call `search/results` explicitly

**No `declare` needed** — the handler calls `(element :search)` and `.setText`, not `custom-browser` or `open-tab!` (no mutual recursion — SWT-UI-GUIDE §4).

## Phase 5: Test

### Step 13: REPL Visual Test Loop

Use the SWT-UI-GUIDE §10 pattern against the running Winze server:

```clojure
(require '[llm-memory.ui.util :as ui-util])
(require '[llm-memory.ui.main-window :as mw])
(require '[ui.SWT :refer [ui]])

;; 1. Text + tag search
(ui (.setText (mw/element :search) "deployment #active"))
(Thread/sleep 3000)
(ui (ui-util/screenshot-widget! (mw/element :live-search-results) "/tmp/tag-search-1.png"))

;; 2. Tag-only search (pure metadata query path)
(ui (.setText (mw/element :search) "#gpu-report"))
(Thread/sleep 3000)
(ui (ui-util/screenshot-widget! (mw/element :live-search-results) "/tmp/tag-search-2.png"))

;; 3. Verify card layout: status emoji, created date, clickable pills
;; (visual inspection of screenshot)

;; 4. Multiple tags (AND)
(ui (.setText (mw/element :search) "#active #context"))
(Thread/sleep 3000)
(ui (ui-util/screenshot-widget! (mw/element :live-search-results) "/tmp/tag-search-3.png"))

;; 5. Empty after tag removal — should show placeholder
(ui (.setText (mw/element :search) ""))
(Thread/sleep 500)
(ui (ui-util/screenshot-widget! (mw/element :live-search-results) "/tmp/tag-search-4.png"))
```

Manual click verification:
1. Type `deployment #active` — verify only active-status results appear
2. Type `#gpu-report` (tag only) — verify all gpu-report docs listed, sorted by modification date
3. Click a pill in results — verify it navigates to live search tab with `#pill-text` in the search box
4. Verify `:file/created` dates display in parentheses after file paths
5. Verify status emoji: ☐ for active docs, ☑ for complete docs
6. Click a pill from a file-viewer tab — verify it switches to the live search tab
7. Verify pills show group first, then related items, then tags

## Files Modified

| File | Change |
|------|--------|
| `winze/clj-llm-memory/src/llm_memory/store/datalevin.clj` | Add `:file/created` to schema |
| `winze/clj-llm-memory/src/llm_memory/index.clj` | Map `:fm/created` → `:file/created` during indexing |
| `winze/clj-llm-memory/src/llm_memory/core.clj` | Surface `:file/tags`, `:file/related`, `:file/created` in search; add `:jira` filter; add `metadata-query` function |
| `winze-server/src/llm_memory/ui/search.clj` | `parse-query`, `classify-tag`; redesign `result-card` (status emoji, created date, group/related/tags pills); `metadata-pills` → clickable `<a>` with active highlighting; CSS; update `results` to route through parser |
| `winze-server/src/llm_memory/ui/main_window.clj` | Add `winze:search?q=` branch to `custom-browser`'s `on e/changing` (convert `when` → `cond`) |

## Dependencies on Other Stories

- **Shared `custom-browser` factory** (SWT-UI-GUIDE §4) with file viewer — both use `winze:` URL dispatch through the same `on e/changing` handler
- **No blocking dependencies** — can be implemented independently; the handler branches are additive
- **No `declare` needed** — the `winze:search` handler calls `(element :search)` and `.setText`, not `custom-browser` itself (no mutual recursion)

## SWT-UI-GUIDE Audit

Reviewed against SWT-UI-GUIDE patterns:
- **§4 Custom Control Factories**: `winze:search?` handler added as a `cond` branch in `custom-browser`, not as a separate listener or in `defmain`. ✓
- **§4 Mutual Recursion**: No new mutual recursion — handler only calls `element` accessors and `.setText`. No `declare` needed. ✓
- **§5 Event Handling / `on` macro**: `on e/changing` resolves `LocationListener.changing` reflectively — no `proxy` or manual imports needed. ✓
- **§8 CTabItem**: No new tabs created by tag search — it reuses the live search tab. ✓
- **§14 Thread Safety**: `(set! (.-doit event) false)` is synchronous in the handler. All widget mutations (`.setText`, `.setSelection`) are inside `async-exec!`. ✓
- **§2 Namespace Reload**: Use targeted `:reload` on changed namespaces, never `:reload-all`. ✓
