# Word Cloud for Empty Search Page — Plan

## Step 1: Term Extraction (`search.clj`)

Add a `term-frequencies` function that queries all chunk text and produces a frequency map.

```clojure
(defn- term-frequencies
  "Extract term frequencies from all indexed chunk text."
  [store]
  (let [texts (store/query store '[:find [?text ...] :where [?c :chunk/text ?text]])]
    (->> texts
         (mapcat #(str/split (str/lower-case %) #"\W+"))
         (remove #(< (count %) 4))
         (remove stopwords)
         frequencies)))
```

- Define a `stopwords` set: standard English stopwords + markdown/code artifacts (`http`, `https`, `html`, `code`, `clojure`, `true`, `false`, `null`, `nil`, etc.)
- Include common planning-doc noise words: `file`, `plan`, `context`, `section`, etc. — tune after seeing initial output

## Step 2: Frequency → Cloud Data

Add a `cloud-terms` function that takes raw frequencies and returns a seq of `{:term "..." :size N :color "..."}` maps.

```clojure
(defn- cloud-terms
  "Select top-N terms and map frequencies to font sizes and colors."
  [freq-map n]
  (let [top       (->> freq-map (sort-by val >) (take n))
        max-freq  (val (first top))
        min-freq  (val (last top))
        log-range (- (Math/log (inc max-freq)) (Math/log (inc min-freq)))
        palette   [(:lavender colors) (:amethyst colors) (:deep-violet colors)
                   (:crystal-white colors)]
        scale     (fn [freq]
                    (if (zero? log-range)
                      30
                      (+ 13 (* 35 (/ (- (Math/log (inc freq)) (Math/log (inc min-freq)))
                                     log-range)))))]
    (->> top
         (map-indexed (fn [i [term freq]]
                        {:term  term
                         :size  (int (scale freq))
                         :color (nth palette (mod i (count palette)))}))
         shuffle)))
```

- Font size range: 13px–48px (logarithmic scale)
- Colors: cycle through 4 brand palette entries
- Shuffle for visual variety

## Step 3: Word Cloud HTML (`search.clj`)

Add CSS for the cloud layout and update `empty-page` to render the cloud.

**CSS additions:**
```css
.cloud {
  display: flex; flex-wrap: wrap; justify-content: center;
  align-items: baseline; gap: 8px 14px;
  padding: 40px 20px;
}
.cloud a {
  text-decoration: none; cursor: pointer;
  transition: opacity 0.15s;
}
.cloud a:hover { opacity: 0.7; }
.cloud-label {
  text-align: center; padding: 16px;
  color: <deep-violet>; font-size: 12px;
}
```

**Hiccup for the cloud:**
```clojure
[:div.cloud
 (for [{:keys [term size color]} terms]
   [:a {:href  (str "winze:search?q=" (java.net.URLEncoder/encode term "UTF-8"))
        :style (str "font-size:" size "px;color:" color)}
    term])]
```

**Update `empty-page`:**
- Accept the store as an argument (or use `(server/store)` directly)
- If the store has indexed chunks, render the word cloud
- If no chunks (empty store), fall back to "No documents indexed yet."

## Step 4: Cache the Cloud HTML

Add an atom to cache the generated cloud HTML and a generation counter.

```clojure
(def ^:private cloud-cache (atom {:chunk-count 0 :html nil}))

(defn- cloud-html
  "Return cached word cloud HTML, recomputing if the store has changed."
  [store]
  (let [current-count (or (ffirst (store/query store '[:find (count ?c) :where [?c :chunk/id]])) 0)
        cached        @cloud-cache]
    (if (and (:html cached) (= current-count (:chunk-count cached)))
      (:html cached)
      (let [freqs (term-frequencies store)
            terms (cloud-terms freqs 70)
            html  (render-cloud-page terms)]
        (reset! cloud-cache {:chunk-count current-count :html html})
        html))))
```

Invalidation: recompute when chunk count changes. This catches indexing, deletion, and re-indexing. A lightweight check (single Datalog aggregate) on every empty-page render.

## Step 5: Click-to-Search (no changes needed)

`custom-browser` in `main_window.clj:51-81` already handles `winze:search?q=...` URLs via CDT's `(on e/changing ...)`. It cancels navigation, decodes the query, switches to the search tab, and sets the search Text widget's text — which fires `modify-text`, triggering `search/results` automatically.

Word cloud links using `href="winze:search?q=..."` work out of the box.

## Step 6: Update `empty-page` Signature

Currently `empty-page` takes no arguments. Update it to accept the store and pass it through from `results`:

```clojure
;; In search.clj
(defn empty-page [store]
  (cloud-html store))

;; In the debounce handler within `results`:
(if (< (count q) 3)
  (async-exec! #(.setText browser-widget (empty-page (server/store))))
  ...)
```

This is a minor breaking change — the only caller is `main_window.clj:body` which sets `:text (search/empty-page)`. Update that to `:text (search/empty-page (server/store))`. The store is available at that point since `main.clj` initializes it before the UI launches.

## Step 7: Test and Tune

- REPL-verify `term-frequencies` output — check for noise terms that should be added to stopwords
- Verify the cloud renders correctly in the Browser widget (screenshot)
- Click a word — verify the search field populates and results appear
- Verify the cloud refreshes after indexing new documents (change chunk count)

## Files Modified

| File | Change |
|------|--------|
| `winze-server/src/llm_memory/ui/search.clj` | `stopwords`, `term-frequencies`, `cloud-terms`, `render-cloud-page`, `cloud-cache`, `cloud-html`; update `empty-page` signature; add `.cloud` CSS |
| `winze-server/src/llm_memory/ui/main_window.clj` | Update `empty-page` call in `body` to pass `(server/store)` |
