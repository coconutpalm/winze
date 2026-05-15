---
name: Frontmatter Parse Resilience — CONTEXT
description: A single malformed YAML frontmatter block silently breaks both search-result link-click and watcher live-refresh because llm-memory.frontmatter/parse-frontmatter lets SnakeYAML exceptions propagate
type: context
status: active
group: frontmatter-parse-resilience
---

# Frontmatter Parse Resilience — Context

## The incident

Inside the `_finance` project, two Plans files were authored with `[[wikilink]]`-style values in the `related:` frontmatter key:

```yaml
related: [[usage-api-93-day-chunking]] [[cache-divergence]]
```

This is invalid YAML at line 6, column 40 — two adjacent flow sequences at the value position. SnakeYAML throws:

```
org.yaml.snakeyaml.parser.ParserException: while parsing a block mapping
  expected <block end>, but found '['
```

User-visible symptoms in the running Winze UI:

1. **Search worked.** `#usage-api-pagination-drain` returned the two documents at the top of the result list. Hovering each result showed a preview card.
2. **Clicking a result did nothing.** No new tab opened, no error banner appeared.
3. **Open viewers had previously been closed** because they stopped refreshing on file save — so the user had no on-screen indication that anything was wrong.

The Winze log (`~/.local/share/winze/plan-server.log`) showed an unbroken stream of two error patterns from the moment those files were modified:

```
[WARN] watcher action failed: while parsing a block mapping
ERROR llm-memory.ui.main-window - Failed to open file todo/usage-api-pagination-drain/CONTEXT.md
  ...
  at llm_memory.frontmatter$parse_frontmatter.invokeStatic(frontmatter.clj:20)
  at llm_memory.ui.search$file_page.invokeStatic(search.clj:584)
  at llm_memory.ui.main_window$open_file_in_tab_BANG_$fn__10100.invoke(main_window.clj:150)
```

## Root cause

[`llm-memory.frontmatter/parse-frontmatter`](../../../clj-llm-memory/src/llm_memory/frontmatter.clj#L11-L23) is the single shared decoder for every Plans doc the system touches:

```clojure
(defn parse-frontmatter [text]
  (if (str/starts-with? text "---\n")
    (let [end (str/index-of text "\n---\n" 4)]
      (if end
        (let [yaml-str (subs text 4 end)
              fm       (or (yaml/parse-string yaml-str) {})]   ; ← throws
          [fm (subs text (+ end 5))])
        [{} text]))
    [{} text]))
```

`yaml/parse-string` throws on malformed input. That throw propagates through **two structurally unrelated call sites** that both happen to share this helper:

| Call site                                       | Effect of the throw                                                                 |
| ---                                             | ---                                                                                 |
| `llm-memory.ui.search/file-page` (search.clj:584) | `main-window/open-file-in-tab!` future fails — no tab opens on click                |
| Plans **filesystem watcher** reindex action       | Reindex of the changed file fails — open viewers stop seeing post-save updates      |

So **one** malformed frontmatter block manifests as **two** unrelated-looking UI bugs (link click broken, live refresh broken), with no on-screen surface that points back to the actual cause. The only diagnostic was the log file.

## Why it's worth a defensive fix

- **Authoring is fallible.** The user-side convention in this workspace is `related: [bare-slug-a, bare-slug-b]`, but the **auto-memory** system in CLAUDE.md uses `[[wikilink]]` syntax for cross-references. The two conventions live in different storage layers and are easy to confuse when authoring fresh plan docs. There is no schema validation on `related:`, so the mistake passes silently until someone clicks a search result.
- **Blast radius is disproportionate.** A typo in a metadata field that nothing critical reads (UI just displays the related-doc list) takes down two unrelated core flows for the whole UI session.
- **No on-screen surface.** The user's only diagnostic was the log file. Without a developer's instinct to tail `plan-server.log`, this looks like Winze is "just broken." The hover preview rendering already coexists with broken frontmatter (the preview cards rendered fine), which makes the broken click especially confusing — the preview path uses a different code branch.
- **Project convention is `{:error ...}` returns, not throws.** The Clojure conventions section in [`winze/CLAUDE.md`](../../../CLAUDE.md) explicitly says: "Error handling: Return error maps (`{:error ...}`), don't throw." `parse-frontmatter` is currently the odd one out.

## Mitigation already applied

The two malformed files in `_finance/Plans/todo/usage-api-pagination-drain/` were corrected by hand to the canonical bare-slug form:

```yaml
related: [usage-api-93-day-chunking, cache-divergence]
```

Verified in the running Winze nREPL (port 50444) that `frontmatter/parse-frontmatter` and `search/file-page` both succeed on the repaired files. That fixes the immediate user-visible breakage but does not prevent the next occurrence.

## What this work covers

Make `parse-frontmatter` resilient: any malformed YAML between the `---` delimiters should be **logged** and **degraded gracefully** rather than thrown. The two affected flows (search-link open, watcher reindex) keep working. The body of the doc still renders so the user can see what's wrong and fix it.

Out of scope: no schema validation for `related:` / `tags:` values, no UI error banner, no auto-repair of malformed YAML. Those are larger pieces of work that this small fix does not block.
