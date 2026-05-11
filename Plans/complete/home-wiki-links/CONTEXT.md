---
group: home-wiki-links
doc_type: context
status: complete
supersedes: WIKI-LINK-REGRESSIONS-CONTEXT.md
---

> **Archived as superseded.** Replaced by
> [`todo/WIKI-LINK-REGRESSIONS-CONTEXT.md`](../../todo/WIKI-LINK-REGRESSIONS-CONTEXT.md),
> which owns the complete root-cause analysis. Current active Winze UI
> work is tracked in
> [`todo/CONTENT-ASSIST-WHITE-BAND-PLAN.md`](../../todo/CONTENT-ASSIST-WHITE-BAND-PLAN.md).

# home.md wiki: Link Navigation — Context

> **Superseded by [`WIKI-LINK-REGRESSIONS-CONTEXT.md`](../../todo/WIKI-LINK-REGRESSIONS-CONTEXT.md)**,
> which contains the complete root-cause analysis including three interacting
> regressions and the failed migration.  This file documents the initial
> symptom investigation only.

## Symptom

In the Winze file viewer, `home.md` contains four links under "Current work":

```markdown
* [ ] [GCP gpu report context](wiki:d3dad24e-b2ec-348f-af5c-77cd335ca2a9)
* [ ] [GCP validation plan](wiki:4d3bb480-c5c5-3b14-a0ca-846f8dca90e2)
- [ ] [Multi-Cloud GPU Cost Report](guides/CLOUD-GPU-GUIDE.md)
- [ ] [Actionability report](guides/CLOUD-GPU-SUMMARY.md)
```

Clicking the `guides/` file path links **works** — they open the target files.
Clicking the `wiki:` UUID links **does nothing** — no navigation, no error.

## Code Path

`home.md` is displayed in `:file` mode inside the live search tab, which uses
the `custom-browser` widget defined in `main_window.clj` (line 123).

`custom-browser` attaches a `LocationListener` `changing` handler (lines 128–156)
that dispatches on URL scheme:

```clojure
(cond
  (str/starts-with? loc "winze:open-file?") (open-file-in-tab! ...)
  (str/starts-with? loc "winze:search?")    (focus-search! ...)
  (str/starts-with? loc "wiki:")
  (do (set! (.-doit event) false)
      (let [uuid (subs loc 5)]
        (when-let [s (server/store)]
          (when-let [resolved (index/resolve-wiki-uuid s uuid)]
            (open-file-in-tab! (:root-uri resolved) (:file-path resolved)))))))
```

**The `wiki:` branch is fully wired** — the LocationListener fires for unknown
schemes in SWT/WebKit, `doit` is set to `false`, and `resolve-wiki-uuid` is
called.

**The silent failure point**: `resolve-wiki-uuid` returns `nil`, so the
`when-let` body never executes and nothing happens.  There is no log warning.

## Root Cause: Wiki Index Gap

`resolve-wiki-uuid` queries Datalevin for a `:wiki/id` entity matching the UUID.
The UUIDs in `home.md` correspond to specific headings in specific planning
documents.  These `:wiki/*` entities **do not exist** in the current Datalevin
store.

This is the same gap documented in `todo/_WIKI-INDEX-GAP-CONTEXT.md`:

- Wiki entities are created only during `index-file!`.
- The wiki feature was added **after** most files were already indexed.
- `reconcile!` skips files whose content-hash is unchanged — it never backfills
  missing wiki entities.
- Result: only ~59 of 307 indexed files have `:wiki/*` entities.

## Why the File-Path Links Work

`guides/CLOUD-GPU-GUIDE.md` is a relative `.md` path.  During rendering,
`hiccup/rewrite-local-link` (called from `render-link` in `hiccup.clj`)
rewrites it to a `winze:open-file?root=...&path=...` URL.  That URL is handled
by the first branch of the `cond`, which calls `open-file-in-tab!` directly —
no Datalevin lookup required.

`wiki:` links are **not** rewritten during rendering (`local-md-link?` returns
`false` for non-`.md` destinations).  They remain as `wiki:uuid` and require
a live Datalevin lookup to resolve.

## UUID Determinism

`wiki-uuid` in `index.clj` (line 240) is deterministic:

```clojure
(UUID/nameUUIDFromBytes (.getBytes (str file-id "#" slug) "UTF-8"))
```

Once the backfill is run, the store will produce the **exact same UUIDs** for
the same file-id + heading-slug pairs.  The links in `home.md` will start
working without any edits to the file.

## Affected Source Files

| File | Lines | Role |
|------|-------|------|
| `clj-llm-memory/src/llm_memory/index.clj` | 916-943 | `classify-files` — wiki-gap detection missing |
| `clj-llm-memory/src/llm_memory/index.clj` | 987-1054 | `reconcile!` — skips unchanged files |
| `clj-llm-memory/src/llm_memory/index.clj` | 482-515 | `resolve-wiki-uuid` — returns nil silently |
| `winze-server/src/llm_memory/ui/main_window.clj` | 147-156 | `wiki:` handler — no log on nil result |

## Related Work

- **`todo/_WIKI-INDEX-GAP-CONTEXT.md`** / **`todo/_WIKI-INDEX-GAP-PLAN.md`** —
  the comprehensive fix for the underlying gap (covers `classify-files`,
  `rename-file!`, and backfill via reconcile).  The present plan focuses only
  on the `home.md` symptom and adds the diagnostic improvement.
