# Anchor Link Navigation — Plan

## Step 1 — Add `heading-slug` function

Add a private function to `hiccup.clj` that converts heading text to a
GFM-compatible anchor slug:

```clojure
(defn- heading-slug
  "Convert heading plain-text to a GFM-style anchor slug."
  [text]
  (-> text
      str/lower-case
      str/trim
      (str/replace #"\s+" "-")
      (str/replace #"[^a-z0-9_-]" "")
      (str/replace #"-{2,}" "-")
      (str/replace #"^-|-$" "")))
```

Place it in the Helpers section (after `text-content`, before the AST walker).

**Verify**: Load namespace in REPL, test slug generation with representative
inputs (spaces, em-dashes, backticks, special characters).

## Step 2 — Update `render-heading` to emit `id`

Modify `render-heading` to merge a slug-derived `:id` into the heading attrs:

```clojure
(defn- render-heading
  [ctx node]
  (let [level (.getLevel ^Heading node)
        tag   (keyword (str "h" level))
        slug  (heading-slug (text-content node))
        attrs (cond-> (block-attrs ctx node)
                (not (str/blank? slug)) (assoc :id slug))]
    (into [tag attrs] (walk-children ctx node))))
```

**Verify**: Evaluate `(md->hiccup "## Hello World")` in REPL → expect
`[:div [:h2 {:data-line 0 :id "hello-world"} "Hello World"]]`.

## Step 3 — Update RCF tests

Update the existing heading test (line 271) and add new tests:

```clojure
;; Basic heading now has :id
(md->hiccup "# Hello") := [:div [:h1 {:data-line 0 :id "hello"} "Hello"]]

;; Heading slug generation
(md->hiccup "## Hello World") := [:div [:h2 {:data-line 0 :id "hello-world"} "Hello World"]]

;; Heading with special characters
(md->hiccup "## Step 3 — Details")
:= [:div [:h2 {:data-line 0 :id "step-3--details"} "Step 3 — Details"]]

;; Heading with inline code
(md->hiccup "## The `foo` function")
:= [:div [:h2 {:data-line 0 :id "the-foo-function"} "The " [:code "foo"] " function"]]
```

**Verify**: `make test` from `winze-server/` passes.

## Step 4 — Visual verification

Open a document with a Table of Contents (e.g. `CLOUD-GPU-GUIDE.md`) in Winze.
Click a ToC link. Confirm the browser scrolls to the target heading. Screenshot
the before and after states.

## Step 5 — Cross-file fragment links

Verify that cross-file links with fragments (e.g. `[sec](other.md#heading)`)
still work correctly. The `rewrite-local-link` function already preserves the
`#fragment` suffix when rewriting to `winze:open-file` URLs. The opened file
will now have heading `id`s, but WebKit may or may not auto-scroll to the
fragment when HTML is set via `Browser.setText()`.

If cross-file fragment scrolling doesn't work automatically, add a
`Browser.execute()` call in `open-tab!` to scroll to the fragment after setting
HTML. This is a follow-up concern — in-page anchor navigation is the primary
deliverable.

## Out of Scope

- **Duplicate heading disambiguation** (appending `-1`, `-2`): Rare in planning
  docs. Can be added later if needed.
- **LocationListener fragment interception**: Not needed — WebKit handles
  fragment navigation natively once `id` attributes exist.
- **Scroll-to-fragment on file open**: Deferred to Step 5 investigation.
