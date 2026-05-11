---
created: 2026-03-25
related: [AUTO-REGISTER-CONTEXT.md]
superseded_by: dev/ROOT-CONFIG-PLAN.md
tags: [winze, auto-registration, DX]
---

# Auto-Register Root on Init — Plan

## Summary

Modify `ensure-root-registered!` in `mcp-proxy.clj` to auto-register a project root when:
1. The root is not yet registered
2. The project has a `Plans/` directory containing both `dev/` and `complete/` subdirectories

All changes are in a single file: `~/.local/share/winze/mcp-proxy.clj`.

## Steps

### 1. Add Plans directory heuristic check

Create a helper function `plans-dir-looks-valid?` in the proxy:

```clojure
(defn- plans-dir-looks-valid?
  "Check if root-uri has a Plans/ directory with dev/ and complete/ subdirs."
  [root-uri]
  (let [base (str/replace root-uri #"^file://" "")
        pdir (io/file base "Plans")]
    (and (.isDirectory pdir)
         (.isDirectory (io/file pdir "dev"))
         (.isDirectory (io/file pdir "complete")))))
```

This runs in the proxy (Babashka) — no nREPL call needed for the filesystem check.

### 2. Add auto-register helper

Create `auto-register-root!` that sends the same nREPL code as `register_plans`, reusing the proven registration sequence:

```clojure
(defn- auto-register-root!
  "Register root, reconcile index, and start watcher via nREPL."
  [conn root-uri plans-dir]
  (let [code (format "(let [store (llm-memory.server.main/store)]
                        (llm-memory.core/register-root! store {:uri %s :plans-dir %s})
                        (let [summary (llm-memory.index/reconcile! store %s)]
                          (llm-memory.watcher/start-watcher! store %s)
                          (str \"auto-registered: \"
                               (:new summary) \" new, \"
                               (:unchanged summary) \" unchanged\")))"
                     (pr-str root-uri) (pr-str plans-dir)
                     (pr-str root-uri) (pr-str root-uri))
        result (nrepl-eval conn code)]
    (if (:error result)
      (log-proxy "auto-register failed:" (:error result))
      (log-proxy "auto-registered root:" root-uri "—" (:value result)))))
```

### 3. Modify `ensure-root-registered!`

Change the existing function from warn-only to auto-register-if-valid:

```clojure
(defn- ensure-root-registered!
  "Check if root is registered; auto-register if Plans/dev/ and Plans/complete/ exist."
  [conn]
  (let [ruri   @root-uri
        code   (format "(let [roots (llm-memory.core/list-roots (llm-memory.server.main/store))
                              exists? (some #(= %s (:root/uri %%)) roots)]
                          (if exists? :registered :not-registered))"
                       (pr-str ruri))
        result (nrepl-eval conn code)]
    (when (= ":not-registered" (:value result))
      (if (plans-dir-looks-valid? ruri)
        (do (log-proxy "detected Plans directory with dev/ and complete/ — auto-registering:" ruri)
            (auto-register-root! conn ruri "Plans"))
        (log-proxy "root not yet registered:" ruri
                   "— use register_plans tool or /register-plans skill")))))
```

**Behavior change**: When not registered AND the heuristic passes → auto-register + reconcile + watch. When the heuristic fails → same warning as today.

### 4. Test scenarios

| Scenario | Expected |
|----------|----------|
| Project with `Plans/dev/` + `Plans/complete/` — not registered | Auto-registers, reconciles, starts watcher |
| Project already registered | No-op (`:registered` check short-circuits) |
| Project with `Plans/` but no `dev/` or `complete/` | Warning only (no auto-register) |
| Project with no `Plans/` directory at all | Warning only |
| Project with `Plans/dev/` but no `Plans/complete/` | Warning only — incomplete convention |

### 5. Manual verification

After implementation:
1. Remove an existing root registration (or use a new project)
2. Start a Claude Code session pointing at the project
3. Verify stderr shows "auto-registering" message
4. Verify `search_plans` works immediately without `/register-plans`
5. Verify `list_plan_roots` shows the root with correct file count and active watcher

## Scope

- **Single file change**: `~/.local/share/winze/mcp-proxy.clj`
- **No library changes**: All logic is proxy-side filesystem check + existing nREPL registration sequence
- **No breaking changes**: Projects without `Plans/dev/` + `Plans/complete/` get the same warning behavior as today
- **Backwards compatible**: `register_plans` tool still works for non-standard layouts (custom `plans_dir`)
