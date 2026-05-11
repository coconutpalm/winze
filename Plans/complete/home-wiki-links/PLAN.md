---
group: home-wiki-links
doc_type: plan
status: complete
supersedes: WIKI-LINK-REGRESSIONS-PLAN.md
---

> **Archived as superseded.** Replaced by
> [`todo/WIKI-LINK-REGRESSIONS-PLAN.md`](../../todo/WIKI-LINK-REGRESSIONS-PLAN.md),
> which is the actionable fix plan. Current active Winze UI work is
> tracked in
> [`todo/CONTENT-ASSIST-WHITE-BAND-PLAN.md`](../../todo/CONTENT-ASSIST-WHITE-BAND-PLAN.md).

# home.md wiki: Link Navigation — Plan

> **Superseded by [`WIKI-LINK-REGRESSIONS-PLAN.md`](../../todo/WIKI-LINK-REGRESSIONS-PLAN.md)**,
> which is the actionable fix plan.  The steps here were based on an incomplete
> analysis and should not be followed.

## Goal

Make the `wiki:` links in `Plans/home.md` navigate to their target files when
clicked in the Winze viewer.

## Prerequisites

Start a headless nREPL in `winze-server/` before beginning:

```bash
# Use the start-nrepl skill from winze-server/
cd winze/winze-server && <start-nrepl skill>
```

Discover the port: `clj-nrepl-eval --discover-ports`

---

## Step 1 — Diagnose: confirm UUIDs are absent from Datalevin

Verify that the two UUIDs in `home.md` genuinely have no `:wiki/*` entities:

```clojure
(require '[llm-memory.server.main :as server]
         '[llm-memory.store.protocol :as store])

(let [s    (server/store)
      uids ["d3dad24e-b2ec-348f-af5c-77cd335ca2a9"
            "4d3bb480-c5c5-3b14-a0ca-846f8dca90e2"]]
  (mapv (fn [uid]
          [uid (store/query s
                            '[:find ?path
                              :in $ ?wid
                              :where
                              [?w :wiki/id ?wid]
                              [?w :wiki/file ?f]
                              [?f :file/path ?path]]
                            {:wid uid})])
        uids))
```

**Expected**: both return `[]` (empty — entities are missing).

---

## Step 2 — Add a log warning on nil UUID resolution

**File**: `winze-server/src/llm_memory/ui/main_window.clj`, lines 147–156

Change the silent `when-let` to log a warning when `resolve-wiki-uuid` returns
nil.  This makes future broken-link debugging visible in the server log.

```clojure
;; BEFORE
(str/starts-with? loc "wiki:")
(do (set! (.-doit event) false)
    (let [uuid (subs loc 5)]
      (when-let [s (server/store)]
        (when-let [resolved (index/resolve-wiki-uuid s uuid)]
          (let [{:keys [file-path root-uri slug type]} resolved]
            (open-file-in-tab! root-uri file-path)
            ;; TODO: scroll to heading anchor if type = :heading
            )))))

;; AFTER
(str/starts-with? loc "wiki:")
(do (set! (.-doit event) false)
    (let [uuid (subs loc 5)]
      (if-let [s (server/store)]
        (if-let [resolved (index/resolve-wiki-uuid s uuid)]
          (let [{:keys [file-path root-uri]} resolved]
            (open-file-in-tab! root-uri file-path))
          (log/warn "wiki: link not found in store" {:uuid uuid}))
        (log/warn "wiki: link clicked but store is nil"))))
```

**Verify**: load the changed namespace in the REPL:

```clojure
(load-file "src/llm_memory/ui/main_window.clj")
```

Then click a `wiki:` link in `home.md`.  Confirm the log warning appears (e.g.
in `~/.local/share/winze/winze.log` or nREPL output).

---

## Step 3 — Fix the wiki index gap in `classify-files`

This is the **root-cause fix** — already fully designed in
`todo/_WIKI-INDEX-GAP-PLAN.md`.  Execute Steps 1–3 of that plan:

- **Step 1**: Modify `classify-files` in `index.clj` to detect "unchanged"
  files that are missing `:wiki/*` entities, and reclassify them into a new
  `:wiki-gap` bucket.
- **Step 2**: Modify `reconcile!` to re-index the `:wiki-gap` files (call
  `index-file!` without changing chunks — only wiki entities and `:file/title`
  need to be added).
- **Step 3**: Build and install the updated JAR (`make install` from
  `clj-llm-memory/`, then `make install` from `winze-server/`).

See `todo/_WIKI-INDEX-GAP-PLAN.md` for the exact code changes.

---

## Step 4 — Run reconciliation to backfill wiki entities

After the fixed JAR is installed and the server has restarted, trigger a
reconcile via the MCP tool (no reset):

```
/index-plans
```

Do NOT pass `reset: true`.  The new `:wiki-gap` detection will identify the
~248 files and re-index only their wiki entities, preserving all existing
UUIDs.

**Verify**: re-run the Step 1 diagnostic query.  Both UUIDs should now resolve
to a file path.

---

## Step 5 — Test navigation in the viewer

1. Open the Winze app (or ensure it is running).
2. The live search tab should show `home.md` (it loads on startup).
3. Click `[GCP gpu report context](wiki:d3dad24e-…)` — confirm a new tab opens
   with the correct document.
4. Click `[GCP validation plan](wiki:4d3bb480-…)` — confirm the second target
   opens.
5. Confirm the two `guides/` file-path links still work (regression check).

Screenshot-verify the viewer state after navigation.

---

## Completion Criteria

- [ ] Step 1 diagnostic confirms UUIDs are absent before the fix
- [ ] Step 2 warning appears in the log for broken `wiki:` links
- [ ] Step 3 + 4: reconcile produces wiki entities for the target headings
- [ ] Step 1 re-run confirms both UUIDs now resolve
- [ ] Step 5: both `wiki:` links navigate correctly in the viewer
- [ ] No regression on the `guides/` file-path links

## Out of Scope

- Scroll-to-heading after navigation (`TODO` comment at line 155 of
  `main_window.clj`) — follow-up in a separate story.
- Showing a user-visible "broken link" indicator in the viewer — the log
  warning (Step 2) is sufficient for now.
