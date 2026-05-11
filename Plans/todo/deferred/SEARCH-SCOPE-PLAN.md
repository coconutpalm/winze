---
related: [live-search, tag-search]
tags: [ui, search, multi-root]
---

# Search Scope Selector — Plan

## Step 1: Add Scope Atom and Root-Listing Helper (`main_window.clj`)

Define a scope atom holding the current search scope, and a helper to fetch roots
from the store.

```clojure
(def scope
  "Current search scope. :mode is :all, :single, or :multi.
   :all                          — search everything
   :single + :root-uri \"...\"   — one root
   :multi  + :root-uris #{...}  — subset of roots"
  (atom {:mode :all}))

(defn- available-roots
  "Fetch registered roots from the store. Returns [] if store unavailable."
  []
  (try
    (vec (core/list-roots (server/store)))
    (catch Throwable _ [])))
```

**Verify**: Eval `(available-roots)` in the REPL, confirm it returns root maps.

## Step 2: Scope Display Logic (`main_window.clj`)

Build the Link text string from the current scope and root list.

```clojure
(defn- scope-link-text
  "Build the Link widget text for the current scope."
  [scope-val roots]
  (let [n (count roots)]
    (cond
      (zero? n)
      "No roots registered"

      (= n 1)
      (str "Scope: " (:root/name (first roots)))

      :else
      (case (:mode scope-val)
        :all    "Scope: <a>All roots</a>"
        :single (let [root (first (filter #(= (:root-uri scope-val)
                                               (:root/uri %)) roots))]
                  (str "Scope: <a>" (:root/name root) "</a>"))
        :multi  (str "Scope: <a>" (count (:root-uris scope-val)) " roots</a>")))))

(defn- refresh-scope-link!
  "Update the scope Link widget text to reflect current scope."
  []
  (when-let [lnk (element :scope-link)]
    (.setText lnk (scope-link-text @scope (available-roots)))
    (.requestLayout (.getParent lnk))))
```

**Verify**: Eval `(scope-link-text {:mode :all} (available-roots))` in the REPL.

## Step 3: Scope Popup with Checkboxes (`main_window.clj`)

A borderless popup Shell with one checkbox per root. Changes take effect immediately.

```clojure
(def ^:private scope-popup (atom nil))

(defn- root-selected?
  "Is the given root included in the current scope?"
  [scope-val root]
  (case (:mode scope-val)
    :all    true
    :single (= (:root/uri root) (:root-uri scope-val))
    :multi  (contains? (:root-uris scope-val) (:root/uri root))))

(defn- scope-from-checkboxes
  "Derive scope value from a seq of [root-uri checked?] pairs."
  [pairs all-count]
  (let [selected (into #{} (comp (filter second) (map first)) pairs)]
    (cond
      (or (empty? selected)
          (= (count selected) all-count)) {:mode :all}
      (= (count selected) 1)             {:mode :single :root-uri (first selected)}
      :else                               {:mode :multi  :root-uris selected})))

(defn- re-trigger-search!
  "Re-run the live search with the current query and scope."
  []
  (let [q (.getText (element :search))]
    (search/results q (element :live-search-results) @scope)))

(defn- show-scope-popup!
  "Show a borderless popup below the scope link with a checkbox per root.
   focus? — when true (click/Enter), focus the first checkbox for keyboard nav.
            when false (hover), leave focus in the search field."
  [focus?]
  ;; Dispose existing popup if any
  (when-let [old @scope-popup]
    (when-not (.isDisposed old) (.dispose old)))
  (let [lnk   (element :scope-link)
        roots (available-roots)]
    (when (> (count roots) 1)
      (let [popup (Shell. (.getShell lnk) (| SWT/NO_TRIM SWT/ON_TOP SWT/TOOL))]
        (.setLayout popup (GridLayout. 1 false))
        (.setBackground popup obsidian-color)
        ;; Create checkboxes
        (let [cbs (doall
                    (for [root roots]
                      (let [cb (Button. popup SWT/CHECK)]
                        (.setText cb (:root/name root))
                        (.setSelection cb (root-selected? @scope root))
                        (.setForeground cb crystal-white-color)
                        (.setBackground cb obsidian-color)
                        (.addListener cb SWT/Selection
                          (reify Listener
                            (handleEvent [_ _]
                              (let [pairs (map (fn [r c] [(:root/uri r) (.getSelection c)])
                                               roots cbs)]
                                (reset! scope (scope-from-checkboxes pairs (count roots)))
                                (refresh-scope-link!)
                                (re-trigger-search!)))))
                        cb)))]
          ;; Esc hint label (visible + accessible)
          (doto (Label. popup SWT/NONE)
            (.setText "Press Esc to close")
            (.setForeground deep-violet-color)  ; subtle, lower-contrast
            (.setBackground obsidian-color))
          ;; Position below the link
          (let [loc (.toDisplay lnk (Point. 0 (.-y (.getSize lnk))))]
            (.setLocation popup loc))
          (.pack popup)
          (.setVisible popup true)
          ;; Focus first checkbox when opened via click or Enter
          (when focus?
            (.setFocus (first cbs)))
          (reset! scope-popup popup)))))))

(defn- hide-scope-popup! []
  (when-let [p @scope-popup]
    (when-not (.isDisposed p) (.dispose p))
    (reset! scope-popup nil)))
```

### Popup Triggers — Two Focus Modes

| Trigger | Call | Focus |
|---------|------|-------|
| Click on Link (`e/widget-selected`) | `(show-scope-popup! true)` | First checkbox |
| Enter on Link (`e/widget-default-selected`) | `(show-scope-popup! true)` | First checkbox |
| Mouse hover (Display filter) | `(show-scope-popup! false)` | Stays in search field |

When the popup has focus (click/Enter), arrow keys navigate checkboxes, Space toggles.
When the popup is hover-only, the user can keep typing in the search field while
seeing the scope indicator.

### Mouse Tracking for Auto-Dismiss

Use `Display.addFilter(SWT.MouseMove, ...)` to detect when the cursor leaves
both the Link and the popup. Must check whether the event's control is a child
of the popup Shell (checkboxes are children, not the popup itself).

```clojure
;; In defmain, after widget tree is built:
(.addFilter @display SWT/MouseMove
  (reify Listener
    (handleEvent [_ event]
      (when @scope-popup
        (let [ctrl (.getCursorControl @display)]
          (when-not (or (= ctrl (element :scope-link))
                        (and ctrl
                             (not (.isDisposed @scope-popup))
                             (= (.getShell ctrl) @scope-popup)))
            (hide-scope-popup!)))))))
```

### Escape Key Dismiss

```clojure
(.addFilter @display SWT/KeyDown
  (reify Listener
    (handleEvent [_ event]
      (when (and @scope-popup (= (.-keyCode event) SWT/ESC))
        (hide-scope-popup!)
        ;; Return focus to search field after Escape
        (.setFocus (element :search))))))
```

**Verify**:
1. Hover over scope link → popup appears, search field retains focus, keep typing.
2. Click scope link → popup appears, first checkbox focused, arrow keys navigate.
3. Press Escape → popup closes, focus returns to search field.
4. Screen reader announces "Press Esc to close" label in the popup.

## Step 4: Add Link Widget to Header (`main_window.clj`)

Insert the `link` widget in the header composite, above the search `text`.

```clojure
;; In (defn header []) — in the right-side composite, before the search text:
(link (id! :ui/scope-link)
      (grid/hgrab)
      :text (scope-link-text @scope (available-roots))
      (on e/widget-selected [props parent event]
          (show-scope-popup! true))
      (on e/widget-default-selected [props parent event]
          (show-scope-popup! true)))
```

**Verify**: Launch the GUI, confirm the scope link appears above the search field.
Click it → popup appears. Press Enter → popup appears.

## Step 5: Wire Scope into Search (`search.clj`)

Modify `search/results` to accept and apply the scope.

```clojure
(defn results
  "Run live search with optional scope filtering."
  [query-string browser-widget scope-val]
  ;; Cancel any pending search
  (when-let [^ScheduledFuture fut @pending]
    (.cancel fut false))
  (let [q (str/trim (or query-string ""))]
    (if (< (count q) 3)
      (async-exec! #(.setText browser-widget (empty-page)))
      (reset! pending
              (.schedule executor
                         ^Callable
                         (fn []
                           (try
                             (let [store (server/store)
                                   opts  (merge {:top 10 :dedupe true}
                                                (when (= :single (:mode scope-val))
                                                  {:root-uri (:root-uri scope-val)}))
                                   hits  (core/search store q opts)
                                   hits  (if (= :multi (:mode scope-val))
                                           (filter #(contains? (:root-uris scope-val)
                                                               (:file/root-uri %))
                                                   hits)
                                           hits)
                                   html  (results-page hits q)]
                               (async-exec! #(.setText browser-widget html)))
                             (catch Throwable t
                               (async-exec!
                                #(.setText browser-widget
                                           (str (h/html
                                                 [:html
                                                  [:head [:style (page-css)]]
                                                  [:body
                                                   [:div.no-results
                                                    (str "Search error: " (.getMessage t))]]])))))))
                         (long debounce-ms)
                         TimeUnit/MILLISECONDS)))))
```

Update all call sites in `main_window.clj` to pass `@scope`:
- `on modify-text` handler: `(search/results (.getText (element :search)) (element :live-search-results) @scope)`
- `on widget-default-selected` handler (Enter to pin tab): no change needed (reads current browser HTML)
- Checkbox change handler: calls `re-trigger-search!` which passes `@scope`

**Verify**: With multiple roots registered, uncheck one root and search.
Confirm results only come from checked roots. Re-check all, confirm cross-root results.

## Step 6: Edge Cases and Polish

- **0 roots**: Show "No roots registered" as plain text, no link, popup is a no-op
- **1 root**: Show root name as plain text, no link, popup is a no-op
- **Root added/removed while running**: `available-roots` is called fresh each time
  the popup opens. Stale `:root-uris` values that no longer exist are harmlessly
  ignored by `core/search` post-filter.
- **None checked**: `scope-from-checkboxes` treats empty selection as `:all`
- **Popup colors**: Brand palette — obsidian background, crystal-white checkbox text,
  deep-amethyst border (1px via `(.setBackground popup ...)` with inner composite margin)
- **Popup position**: Anchored below-left of Link widget via `.toDisplay`

## Summary

| Step | File | What |
|------|------|------|
| 1 | `main_window.clj` | Scope atom + root-listing helper |
| 2 | `main_window.clj` | Link text builder + refresh helper |
| 3 | `main_window.clj` | Checkbox popup shell + mouse tracking + dismiss |
| 4 | `main_window.clj` | Link widget in header |
| 5 | `search.clj` | Accept scope param, apply root filtering |
| 6 | both | Edge cases, brand colors, verify |
