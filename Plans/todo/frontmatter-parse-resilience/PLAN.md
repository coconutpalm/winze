---
name: Frontmatter Parse Resilience — PLAN
description: Wrap yaml/parse-string in parse-frontmatter so malformed YAML degrades to no-frontmatter (with a warning log), keeping search-link open and watcher reindex working across the rest of the corpus
type: plan
status: active
group: frontmatter-parse-resilience
---

# Frontmatter Parse Resilience — Plan

## Goal

A malformed YAML frontmatter block in any Plans markdown file must not break **any** caller of `llm-memory.frontmatter/parse-frontmatter`. Specifically:

- A search result whose underlying file has bad YAML **opens** when clicked. The tab renders the body cleanly, and the malformed YAML appears in the styled frontmatter pre-block above the body (via `extract-raw-yaml`) so the author can see and fix the problem.
- The filesystem watcher **reindexes** unaffected files normally even when a sibling file's YAML is broken.
- The log emits one clear `[WARN]` per failed parse so the operator knows what to fix.

## Approach

Single change in [`clj-llm-memory/src/llm_memory/frontmatter.clj`](../../../clj-llm-memory/src/llm_memory/frontmatter.clj): wrap the `yaml/parse-string` call in a `try`/`catch`, emit a `[WARN]` line on failure, and fall back to `[{} body-after-frontmatter]` — the same body slice the success path returns, just with an empty metadata map. Existing callers already handle the `[{} body]` shape (it's what an absent or empty frontmatter produces).

### Why `[{} body-after-frontmatter]` (and not the full original `text`)

[`search/file-page`](../../../winze-server/src/llm_memory/ui/search.clj#L576-L603) already calls a **separate**, purely-substring helper `extract-raw-yaml` (search.clj:516) and renders the raw YAML into a styled `[:pre.frontmatter-block raw-yaml]` block above the body. That helper never parses — it just slices between the `---` fences — so it cannot throw.

Given that, the cleanest fallback is "strip the frontmatter the same way the success path does, return an empty metadata map":

- Author sees the malformed YAML in the **styled** frontmatter pre-block at the top of the tab — much nicer than seeing raw `---` delimiters as plain markdown.
- Body renders cleanly with no duplicated `---` block.
- `fm-offset` in `file-page` (line 586-587 — `total-lines − body-lines`) stays correct, so markdown source-line numbers continue to match the original file.
- The chunker / indexer still chunks the body as usual; no metadata is extracted, but the body remains searchable.

Returning the **original `text`** would have double-rendered the `---` block (once via `extract-raw-yaml`'s styled pre, once as raw markdown at the top of the body) and broken `fm-offset`'s line-number math. That option was in an earlier draft of this plan; it was discarded after reading `file-page`.

### Why `println` and not a logging library

`clj-llm-memory/deps.edn` has **no** logging library — no `tools.logging`, no `telemere`, no `timbre`. The existing in-project convention is raw `println` to stdout: [`watcher.clj:117`](../../../clj-llm-memory/src/llm_memory/watcher.clj#L117) emits `(println "[WARN] watcher action failed:" (.getMessage e))`, which the server's stdout redirect captures into `plan-server.log`. Matching that style avoids a new dep and produces consistent `[WARN]` lines in the same log file.

### Why not return `{:error ...}` at the function boundary

The function signature is `[fm body]`, not a single map, and every call site already pattern-destructures the pair. Switching to `{:error ...}` is a breaking change for every caller for no benefit — the caller still has to display the body. Logging at the parse site preserves the operator diagnostic without forcing each call site to handle a third shape.

## Implementation

### Step 1 — Wrap the parse call

In [`frontmatter.clj`](../../../clj-llm-memory/src/llm_memory/frontmatter.clj), replace:

```clojure
(let [yaml-str (subs text 4 end)
      fm       (or (yaml/parse-string yaml-str) {})]
  [fm (subs text (+ end 5))])
```

with:

```clojure
(let [yaml-str (subs text 4 end)
      body     (subs text (+ end 5))
      fm       (try
                 (or (yaml/parse-string yaml-str) {})
                 (catch Exception e
                   (println "[WARN] frontmatter YAML parse failed —"
                            "treating file as having no frontmatter:"
                            (.getMessage e))
                   {}))]
  [fm body])
```

No new `:require` entries (no logger dep). Append one sentence to the docstring: "Malformed YAML degrades to `[{} body]` with a `[WARN]` printed to stdout."

### Step 2 — Add RCF test for the failure path

Append next to the existing two `(tests ...)` blocks in `frontmatter.clj`:

```clojure
(tests
 "parse-frontmatter — malformed YAML returns [{} body-after-frontmatter]"
 (let [bad "---\nrelated: [[a]] [[b]]\n---\n\n# Body"
       [fm body] (parse-frontmatter bad)]
   fm   := {}
   body := "\n# Body")
 :rcf)
```

The malformed input mirrors the real-world incident captured in [CONTEXT](CONTEXT.md). The `body := "\n# Body"` assertion proves the frontmatter delimiters and contents were stripped — that's what lets `file-page` render cleanly while `extract-raw-yaml` (a separate substring helper) still shows the malformed YAML in the styled frontmatter pre-block.

### Step 3 — Verify in a dev REPL

The running production Winze server (auto-started by the MCP proxy) does **not** have RCF enabled — per [`winze/CLAUDE.md`](../../../CLAUDE.md) "Development Guidelines". Start a separate `:dev` nREPL from `clj-llm-memory/` with the `start-nrepl` skill, then:

```
(require '[llm-memory.frontmatter :as fm] :reload)
;; existing RCF tests should still pass, plus the new failure-path test
```

### Step 4 — End-to-end verification on the running server

Once the new build is installed:

1. Create a synthetic malformed file under any registered Plans root:
   ```yaml
   ---
   related: [[a]] [[b]]
   ---
   # Synthetic Bad YAML
   ```
2. Confirm `plan-server.log` shows exactly **one** `[WARN] frontmatter YAML parse failed` line per file change (not a repeated stack trace).
3. From a Claude session, `search_plans "synthetic bad yaml"` returns the file.
4. Clicking the search result in the Winze UI opens a tab; the `# Synthetic Bad YAML` heading renders normally, and the malformed `related:` line appears in the styled frontmatter pre-block above the body.
5. Edit the file to fix the YAML; the open viewer refreshes (no `[WARN] watcher action failed`).
6. Delete the synthetic file.

## Files changed

- `clj-llm-memory/src/llm_memory/frontmatter.clj` — try/catch + warn log + new RCF test

No deps.edn changes. No schema changes. No data migration.

## Install + restart

Per [`winze/CLAUDE.md`](../../../CLAUDE.md) "Server Lifecycle":

1. `(llm-memory.ui.main-window/quit!)` over nREPL to shut the running server down gracefully (never `pkill` — Datalevin corruption risk).
2. From `winze-server/`: `make install-winze`.
3. Next MCP call auto-starts the new build.

## Not in scope (intentionally deferred)

- **Schema-level validation** of `related:` / `tags:` values (e.g. enforce flat string list, reject `[[wikilink]]`). Would need a per-field validator and a clear error-surfacing strategy; not needed to recover from the current failure mode.
- **Author-facing diagnostic** in the rendered tab (e.g. red banner above the body when parse failed). The "malformed YAML shown in the styled frontmatter pre-block" experience is sufficient for the first iteration; a banner can be added later if authors keep tripping the same trap.
- **Hover preview parity.** The preview-card path appears to already tolerate this failure mode (the preview rendered correctly during the incident). Worth re-checking once the fix lands, but no action required up front.

## Acceptance

- [ ] `frontmatter.clj` updated; all three RCF blocks pass under `:dev` REPL.
- [ ] Synthetic malformed file: search result opens, body renders, exactly one `[WARN]` logged.
- [ ] Synthetic malformed file: fixing the YAML triggers watcher reindex; open viewer refreshes.
- [ ] No regression to the two existing test cases (valid frontmatter / no frontmatter).
