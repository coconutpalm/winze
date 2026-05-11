---
group: remove-wiki-uuid
doc_type: plan
status: complete
---
# Remove Wiki UUID Permalinks — Plan

## Goal

Strip the UUID-based `wiki:` permalink system, replace `resolve-wiki-uuid`
with a simple file-ID resolver, and fix both the editor and viewer so that
`wiki:root::path[#slug]` links work end-to-end.

## Step Order

Steps 1–3 are library changes (`clj-llm-memory`). Steps 4–7 are server/UI
changes (`winze-server`). Step 8 cleans up data and plans. Each step is
independently testable.

---

## Step 1 — Remove `:wiki/*` schema and add store-cleanup migration

**File**: `clj-llm-memory/src/llm_memory/store/datalevin.clj`

Remove the six `:wiki/*` schema attributes (lines ~55–61):
`:wiki/id`, `:wiki/slug`, `:wiki/text`, `:wiki/file`, `:wiki/line`, `:wiki/level`

Keep `:file/title` — it is used by `content_assist.clj:title-search`.

**File**: `winze-server/src/llm_memory/server/main.clj`

Add a one-time startup migration that retracts all existing `:wiki/*` entities
from the store (stale data from the old system). Run it after the store opens,
before any indexing:

```clojure
(defn- retract-wiki-schema-data!
  "Remove all :wiki/* entities left over from the UUID permalink system."
  [store]
  (let [eids (store/query store '[:find [?e ...] :where [?e :wiki/id]])]
    (when (seq eids)
      (log/info "Retracting" (count eids) "stale :wiki/* entities")
      (store/transact! store (mapv (fn [e] [:db/retractEntity e]) eids)))))
```

Also remove `migrate-per-root-registries!` (lines ~253–275) and its call
site — the registry consolidation migration is moot.

**Verify**: REPL — confirm `[:find [?e ...] :where [?e :wiki/id]]` returns `[]`
after startup.

---

## Step 2 — Remove wiki UUID indexing from `index.clj`

**File**: `clj-llm-memory/src/llm_memory/index.clj`

Remove these functions entirely:
- `wiki-uuid` (lines ~240–254) — including its `(tests ...)` block
- `retract-wiki-entities!` (lines ~261–272)
- `build-wiki-entities` (lines ~274–286)
- `snapshot-wiki-state` (lines ~292–322)
- `match-heading-renames` (lines ~324–356)
- `build-wiki-entities-tracked` (lines ~358–374) — including its `(tests ...)`
- `registry-write-lock`, `wiki-registry-path`, `load-central-registry`
- `query-all-wikis-for-root`, `save-wiki-registry!`, `load-wiki-registry`

Inside `index-file!` (lines ~521–640), remove:
- The `headings` and `title` local bindings (lines ~547–549)
- The `(assoc :file/title title)` clause if `title` is computed here — **only**
  if `:file/title` is set to `nil` for files without an H1. Keep the binding
  if it is used for `:file/title` on the file entity.
- The call to `build-wiki-entities-tracked` or `build-wiki-entities`
- The call to `retract-wiki-entities!`
- The call to `save-wiki-registry!`

**Note**: Keep `extract-headings` and `page-title` calls if they're the source
of `:file/title`. If `:file/title` is set via `(chunk/page-title body)`, keep
that — it's needed by `title-search`. Only remove the wiki entity construction.

**Verify**: `make test` from `clj-llm-memory/` — all RCF tests pass. Confirm
the removed `(tests ...)` blocks for `wiki-uuid` and `build-wiki-entities-tracked`
are gone.

---

## Step 3 — Replace `resolve-wiki-uuid` with `resolve-wiki-ref`

**File**: `clj-llm-memory/src/llm_memory/index.clj`

Delete `resolve-wiki-uuid` (lines ~482–515). Add `resolve-wiki-ref`:

```clojure
(defn resolve-wiki-ref
  "Resolve a wiki file-ref to navigation info.
  Accepts 'root-name::rel-path' or 'root-name::rel-path#slug'.
  Returns:
    {:type :heading :file-path str :root-uri str :slug str} — heading target
    {:type :file    :file-path str :root-uri str}           — file target
    nil                                                      — not found"
  [store wiki-ref]
  (let [[file-part slug] (str/split wiki-ref #"#" 2)
        result (first (store/query store
                                   '[:find ?path ?root-uri
                                     :in $ ?fid
                                     :where
                                     [?f :file/id ?fid]
                                     [?f :file/path ?path]
                                     [?f :file/root ?r]
                                     [?r :root/uri ?root-uri]]
                                   {:fid file-part}))]
    (when result
      (let [[file-path root-uri] result]
        (if (seq slug)
          {:type :heading :file-path file-path :root-uri root-uri :slug slug}
          {:type :file    :file-path file-path :root-uri root-uri})))))
```

Add RCF tests covering:
- File-level ref: `"_finance::guides/ARGOCD-GUIDE.md"` → `{:type :file ...}`
- Heading ref: `"_finance::guides/ARGOCD-GUIDE.md#deployment"` → `{:type :heading ... :slug "deployment"}`
- Unknown ref: `"_finance::nonexistent.md"` → `nil`

**Build and install**:

```bash
cd clj-llm-memory && make install
```

---

## Step 4 — Fix viewer `wiki:` handler in `main_window.clj`

**File**: `winze-server/src/llm_memory/ui/main_window.clj`

Replace the UUID-based handler (lines ~159–168) with a `resolve-wiki-ref` call:

```clojure
;; wiki:root::path[#slug] — resolve via file-id and navigate
(str/starts-with? loc "wiki:")
(do (set! (.-doit event) false)
    (let [wiki-ref (subs loc 5)]
      (if-let [s (server/store)]
        (if-let [resolved (index/resolve-wiki-ref s wiki-ref)]
          (let [{:keys [file-path root-uri slug type]} resolved]
            (open-file-in-tab! root-uri file-path)
            ;; TODO: scroll to anchor when anchor nav is implemented
            )
          (log/warn "wiki: broken link — ref not found:" wiki-ref))
        (log/warn "wiki: link clicked but store not available"))))
```

This fixes the silent-failure bug. Broken links now log a warning.

**Verify** (manual): Open Winze viewer, click `[ArgoCD Guide](wiki:_finance::guides/ARGOCD-GUIDE.md)` in `home.md` — confirm navigation opens the file tab.

---

## Step 5 — Fix editor `navigate-link!` in `markdown_editor.clj`

**File**: `winze-server/src/llm_memory/ui/markdown_editor.clj`

In `navigate-link!` (lines ~259–308), replace the UUID branch with a
`resolve-wiki-ref` call:

```clojure
(str/starts-with? dest "wiki:")
(let [wiki-ref   (subs dest 5)
      store-fn   (requiring-resolve 'llm-memory.server.main/store)
      resolve-fn (requiring-resolve 'llm-memory.index/resolve-wiki-ref)]
  (if-let [s (store-fn)]
    (if-let [resolved (resolve-fn s wiki-ref)]
      (let [{:keys [type file-path root-uri slug]} resolved
            dest-url (str "winze:open-file?"
                          "root=" (java.net.URLEncoder/encode (or root-uri "") "UTF-8")
                          "&path=" (java.net.URLEncoder/encode (or file-path "") "UTF-8")
                          (when (and (= type :heading) slug)
                            (str "#" slug)))]
        (when-let [f @navigate-link-fn]
          (f dest-url abs-path)))
      (log/warn "Broken wiki link — ref not found:" wiki-ref))
    (log/warn "Wiki link resolution failed — store not available")))
```

This is essentially the same logic as before but calls `resolve-wiki-ref`
instead of `resolve-wiki-uuid`.

**Verify** (manual): Cmd-click `[ArgoCD Guide](wiki:_finance::guides/ARGOCD-GUIDE.md)` in the editor — confirm navigation.

---

## Step 6 — Update content assist to emit `wiki:root::path`

**File**: `winze-server/src/llm_memory/ui/content_assist.clj`

### `wiki-search` (lines ~334–372)

Remove the `:wiki/id` enrichment query. The `:wiki/*` entities no longer exist.
For each search result, compute `wiki-ref` from `:file/id`:

```clojure
;; BEFORE: enrich with :wiki/id UUID from Datalevin
;; AFTER: compute wiki-ref directly from :file/id
(let [wiki-ref (:file/id result)]
  (assoc result :wiki/ref wiki-ref))
```

### `title-search` (lines ~374–422)

Same: replace `:wiki/id` query with `:file/id` → `:wiki/ref`.

### `on-select` callback (lines ~499–506)

Change what the callback destructures and returns:

```clojure
;; BEFORE
(let [{:keys [wiki/id file/path chunk/slug file/title]} result]
  (on-select {:type :wiki :uuid id ...}))

;; AFTER
(let [{:keys [wiki/ref file/path file/title]} result]
  (on-select {:type :wiki :wiki-ref ref :file-path path :title title}))
```

### All `on-select` call sites in `markdown_editor.clj`

Change `handle-wiki-draft-trigger!`, `handle-paren-trigger!`, and
`handle-insert-link!` to use `:wiki-ref` instead of `:uuid`:

```clojure
;; BEFORE
(fn [{:keys [uuid title]}]
  (let [link (str "[" title "](wiki:" uuid ")")])
  ...)

;; AFTER
(fn [{:keys [wiki-ref title]}]
  (let [link (str "[" title "](wiki:" wiki-ref ")")])
  ...)
```

**Verify** (manual): Type `[[` in the editor to open content assist. Select a
file. Confirm the inserted link is `[Title](wiki:_finance::path/to/file.md)`.

---

## Step 7 — Fix link preview in `link_preview.clj`

**File**: `winze-server/src/llm_memory/ui/link_preview.clj`

In `resolve-wiki-preview` (lines ~120–168), replace `resolve-wiki-uuid` call
with `resolve-wiki-ref`:

```clojure
;; BEFORE
(index/resolve-wiki-uuid s uuid)

;; AFTER
(index/resolve-wiki-ref s wiki-ref)
```

Adjust the parsing to extract `wiki-ref = (subs dest 5)` (same as before —
the 5-char prefix is still `"wiki:"`).

---

## Step 8 — Clean up data and Plans files

### 8a — Delete UUID test links in `_finance/Plans/home.md`

Remove lines 30–31 (the two UUID links that are unresolvable):

```markdown
* [abc](wiki:a59fa7e6-3819-3e37-8672-c90d281ba9b0)      ← DELETE
* [UUID](wiki:464f81f0-2d74-3fe7-8492-9ef904a930f4)     ← DELETE
```

These were test entries. The file-ID links above them (lines 28–29) remain.

### 8b — Delete stale wiki-registry.edn

```bash
rm -f ~/.local/share/winze/wiki-registry.edn
```

### 8c — Retire regression plans

Delete or archive the following (the fixes they describe are now moot):

```bash
rm winze/Plans/todo/WIKI-LINK-REGRESSIONS-CONTEXT.md
rm winze/Plans/todo/WIKI-LINK-REGRESSIONS-PLAN.md
```

Also delete `_WIKI-INDEX-GAP-CONTEXT.md` and `_WIKI-INDEX-GAP-PLAN.md` if they
exist in `Plans/todo/`.

---

## Step 9 — Build and install

```bash
# Library first
cd clj-llm-memory && make test && make install

# Server
cd ../winze-server && make install
```

Then restart Winze gracefully:

```clojure
;; Via REPL on the running server
(llm-memory.ui.main-window/quit!)
```

The server will restart on next MCP call. On startup, the store-cleanup
migration retracts stale `:wiki/*` entities.

Run a reconcile to confirm nothing regressed:

```
/index-plans
```

---

## Verification

| Check | Method |
|-------|--------|
| `wiki:_finance::guides/ARGOCD-GUIDE.md` navigates in viewer | Click link in home.md |
| `wiki:_finance::guides/ARGOCD-GUIDE.md` navigates in editor | Cmd-click same link |
| `wiki:_finance::path/to/file.md#heading` navigates + anchors (if anchor nav implemented) | Manual |
| Content assist inserts `wiki:root::path` links | Type `[[` or Mod1+K |
| Broken `wiki:` links log a warning | Use a nonexistent ref, check server log |
| No `:wiki/*` entities in store | REPL: `(store/query s '[:find ?e :where [?e :wiki/id]])` → `[]` |
| `wiki-registry.edn` deleted | `ls ~/.local/share/winze/*.edn` → not found |
| UUID links gone from `_finance/Plans/home.md` | Read file |

---

## Completion Criteria

- [ ] Step 1: `:wiki/*` schema removed; startup migration retracts stale entities
- [ ] Step 2: All UUID indexing functions removed from `index.clj`; `make test` passes
- [ ] Step 3: `resolve-wiki-ref` replaces `resolve-wiki-uuid`; RCF tests pass
- [ ] Step 4: Viewer navigates `wiki:root::path` links; broken links log warning
- [ ] Step 5: Editor Cmd-click navigates `wiki:root::path` links
- [ ] Step 6: Content assist inserts `wiki:root::path` links
- [ ] Step 7: Link preview resolves `wiki:root::path` links
- [ ] Step 8: UUID links deleted from home.md; registry file deleted; regression plans retired
- [ ] Step 9: Build + install + restart successful; reconcile clean
