---
group: inline-tests
tags: testing, refactor, clj-llm-memory
---

# Inline RCF Tests — Context

## Current State

`clj-llm-memory` has 59 RCF test blocks across 5 separate test files in `test/`:

| Test file | Blocks | Lines | Tests for |
|-----------|--------|-------|-----------|
| `index_test.clj` | 14 | 362 | `index.clj` — index-file!, retract-file!, rename-file!, reconcile!, index-root!, fuzzy rename |
| `core_test.clj` | 12 | 283 | `core.clj` — register-root!, search, list-files, related, recent, status |
| `pipeline_test.clj` | 17 | 177 | End-to-end pipeline (index → search → reconcile) |
| `tools_test.clj` | 10 | 180 | `tools.clj` — formatted markdown output |
| `watcher_test.clj` | 6 | 174 | `watcher.clj` — filesystem event handling |
| **Total** | **59** | **1176** | |

All tests use `fresh-store` (temp Datalevin in `/tmp/`) and are isolated from the production database.

## Problem

1. **Tests are in separate files** — diverges from the project convention (clj-oci) where RCF tests are inline in source files, immediately below the function they test. Separate test files mean the tests don't serve as documentation for readers of the source.

2. **`make test` uses eftest's `--dir test` runner** — which doesn't match `clj-oci`'s `clj -X:test` (cloverage + eftest). The current runner also requires `:multithread? false` because Datalevin's LMDB can't handle concurrent env opens.

3. **No coverage reporting** — cloverage is not wired up.

## Target State

Match `clj-oci`'s testing pattern:
- RCF `(tests ... :rcf)` blocks inline in source files
- `make test` → `clj -X:test` → cloverage + eftest
- `:test` alias in `deps.edn` with `:test-ns-path ["src"]` and `:src-ns-path ["src"]`
- No `test/` directory
- Single-threaded eftest runner (LMDB constraint)

## Reference: clj-oci `:test` Alias

```clojure
:test {:jvm-opts ["-Dhyperfiddle.rcf.generate-tests=true"]
       :extra-deps {eftest/eftest {:mvn/version "0.6.0"}
                    cloverage/cloverage {:mvn/version "1.2.4"}}
       :exec-fn cloverage.coverage/run-project
       :exec-args {:test-ns-path ["src"]
                   :src-ns-path ["src"]
                   :runner :eftest
                   :runner-opts {:fail-fast? true}}}
```

## Test Infrastructure Shared Across Test Blocks

Each test file has shared helpers (`fresh-store`, `tmp-dir`, `write-plan!`, `root-uri`) that create temp Datalevin stores. These will need to move to a shared test-support namespace or be duplicated per source file.

**Recommended**: Create `src/llm_memory/test_support.clj` with the shared helpers. This stays in `src/` so cloverage's `:test-ns-path ["src"]` finds it. Guard the helpers behind `(tests ...)` blocks so they don't execute in production (they require RCF enabled + the `:dev`/`:test` alias for the RCF dep).

Alternative: since the helpers are small (~15 lines), duplicating them in each source file's `(tests ...)` block keeps things self-contained at the cost of some repetition. This matches `clj-oci`'s pattern more closely — each source file is fully self-contained for testing.

## Constraints

- **LMDB concurrency**: Datalevin can only open a limited number of LMDB environments simultaneously. Tests must run single-threaded. In the `:test` alias, use `:runner-opts {:multithread? false}` (or `:fail-fast? true` which implies sequential).
- **Embedding model load time**: The inference4j embedder takes ~2-3s to initialize. Tests that need embeddings should share a singleton via `defonce` + `delay`.
- **Production database isolation**: No test should reference `~/.local/share/clj-llm-memory/`. All test stores use `/tmp/` paths.
