# Clickable File Viewer Tabs — Plan

## Step 1: Make File Path Clickable (`search.clj`)

Change `result-card` to render the file path as a link instead of a plain `<span>`:

```clojure
(defn- result-card
  [{:keys [file/path root/uri chunk/text relevance] :as result}]
  [:div.result-card
   [:div.result-header
    [:a.file-path {:href (str "winze:open-file?root="
                               (URLEncoder/encode (or uri "") "UTF-8")
                               "&path="
                               (URLEncoder/encode (or path "") "UTF-8"))}
     (or path "—")]
    [:span {:class (relevance-class relevance)}
     (format "%.0f%%" (* 100.0 relevance))]]
   (metadata-pills result)
   [:div.result-body (md->hiccup (or text ""))]])
```

Add CSS for the clickable file path (cursor, hover underline). The existing `.file-path` style already has the right font/color — just add interaction states:

```css
.file-path { cursor: pointer; text-decoration: none; }
.file-path:hover { text-decoration: underline; }
```

Import `java.net.URLEncoder` in the ns declaration.

## Step 2: Add File Page Renderer (`search.clj`)

Add a `file-page` function that renders a full markdown file as a styled HTML page:

```clojure
(defn file-page
  "Render a markdown file's full content as a styled HTML page."
  [markdown-text file-path]
  (str
   (h/html
    [:html
     [:head
      [:meta {:charset "UTF-8"}]
      [:style (page-css)]]
     [:body
      [:div.header file-path]
      [:div.result-body (md->hiccup markdown-text)]]])))
```

This reuses `page-css` and `md->hiccup` — the file content renders with the same styling as search result snippets, but at full length.

## Step 3: Add `resolve-file-path` Helper (`search.clj`)

Resolve a `root-uri` + `file/path` to an absolute filesystem path:

```clojure
(defn- resolve-file-path
  "Resolve a file's absolute path from its root URI and relative path."
  [store root-uri rel-path]
  (let [roots     (store/query store
                    '[:find ?dir
                      :in $ ?uri
                      :where
                      [?r :root/uri ?uri]
                      [?r :root/plans-dir ?dir]]
                    {:uri root-uri})
        plans-dir (ffirst roots)
        base-path (-> (java.net.URI. root-uri) .getPath)]
    (str base-path "/" plans-dir "/" rel-path)))
```

## Step 4: Add `open-file-tab!` Function (`main_window.clj`)

Create a function that opens a new tab with rendered markdown content:

```clojure
(defn- open-file-tab!
  "Open a new closable tab displaying the rendered markdown file."
  [file-path html-content]
  (let [folder  (element :main-folder)
        brow    (Browser. folder SWT/WEBKIT)
        tab     (CTabItem. folder SWT/CLOSE)]
    (.setJavascriptEnabled brow true)
    (.setText brow html-content)
    (.setText tab (last (str/split file-path #"/")))
    (.setToolTipText tab file-path)
    (.setImage tab @statusbar-icon)
    (.setControl tab brow)
    (.setSelection folder tab)))
```

- Tab text = filename only (e.g. `"PLAN.md"`) for brevity
- Tooltip = full relative path for disambiguation
- Icon = reuses the existing statusbar icon
- `SWT/CLOSE` style enables the close button

Add necessary imports: `[org.eclipse.swt.browser Browser]` (the `CTabItem` import already exists).

## Step 5: LocationListener — Unified Handler (`main_window.clj`)

Add a `LocationListener` on the search Browser widget that dispatches on URL scheme. This handler serves both the word cloud (click-to-search) and file viewer (open-tab) features:

```clojure
(import '[org.eclipse.swt.browser LocationAdapter LocationEvent])

;; Inside defmain, after (reset! app-props @props):
(let [brow   (element :live-search-results)
      search (element :search)]
  (.addLocationListener
   brow
   (proxy [LocationAdapter] []
     (changing [^LocationEvent event]
       (let [loc (.location event)]
         (cond
           ;; Word cloud: click-to-search
           (str/starts-with? loc "winze:search?q=")
           (do (set! (.-doit event) false)
               (let [q (URLDecoder/decode (subs loc (count "winze:search?q=")) "UTF-8")]
                 (async-exec!
                  (fn []
                    (.setText search q)
                    (search/results q brow)))))

           ;; File viewer: open in new tab
           (str/starts-with? loc "winze:open-file?")
           (do (set! (.-doit event) false)
               (let [params  (parse-query-string (subs loc (count "winze:open-file?")))
                     root-uri (get params "root")
                     rel-path (get params "path")]
                 (future
                   (try
                     (let [store    (server/store)
                           abs-path (search/resolve-file-path store root-uri rel-path)
                           content  (slurp abs-path)
                           html     (search/file-page content rel-path)]
                       (async-exec! #(open-file-tab! rel-path html)))
                     (catch Throwable t
                       (log/error t "Failed to open file" rel-path))))))))))))
```

File reading and markdown rendering happen on a background thread (`future`), with only the tab creation on the UI thread (`async-exec!`).

## Step 6: Query String Parser (`main_window.clj`)

Add a small utility to parse URL query parameters:

```clojure
(defn- parse-query-string
  "Parse a URL query string into a map of decoded key-value pairs."
  [qs]
  (into {}
        (for [pair (str/split qs #"&")
              :let [[k v] (str/split pair #"=" 2)]]
          [(URLDecoder/decode k "UTF-8")
           (URLDecoder/decode (or v "") "UTF-8")])))
```

Import `java.net.URLDecoder` in the ns declaration.

## Step 7: Test

1. Start the server, run a search
2. Verify file paths appear as clickable links (cursor changes on hover)
3. Click a file path — verify a new tab opens with the rendered markdown
4. Verify the tab has a close button and the tab text shows the filename
5. Close the tab — verify it's removed and the Browser is disposed
6. Click a file from a different root — verify path resolution works across roots

## Files Modified

| File | Change |
|------|--------|
| `winze-server/src/llm_memory/ui/search.clj` | `result-card` file path → `<a>` link; add `file-page`, `resolve-file-path`; CSS for clickable `.file-path`; import `URLEncoder` |
| `winze-server/src/llm_memory/ui/main_window.clj` | Add `open-file-tab!`, `parse-query-string`; `LocationListener` with `winze:open-file` dispatch; import `Browser`, `LocationAdapter`, `URLDecoder` |

## Shared Infrastructure with Word Cloud

Both stories share the `LocationListener` (Step 5). The implementation order doesn't matter — whichever lands first creates the listener, the second adds a `cond` branch. The plan above shows the combined handler for clarity.
