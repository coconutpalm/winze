---
group: inline-tests
tags: testing, refactor, clj-llm-memory
---

# Inline RCF Tests — Plan

## Step 1: Update `deps.edn` — add `:test` alias

Add a `:test` alias matching `clj-oci`'s pattern:

```clojure
:test {:jvm-opts ["--add-opens=java.base/java.nio=ALL-UNNAMED"
                  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
                  "--enable-native-access=ALL-UNNAMED"
                  "-Dhyperfiddle.rcf.generate-tests=true"]
       :extra-deps {eftest/eftest {:mvn/version "0.6.0"}
                    cloverage/cloverage {:mvn/version "1.2.4"}}
       :exec-fn cloverage.coverage/run-project
       :exec-args {:test-ns-path ["src"]
                   :src-ns-path ["src"]
                   :runner :eftest
                   :runner-opts {:multithread? false}}}
```

**Key difference from clj-oci**: `:multithread? false` instead of `:fail-fast? true` — Datalevin's LMDB requires single-threaded test execution.

Move `eftest` from `:dev :extra-deps` to `:test :extra-deps` only.

## Step 2: Create test support namespace

Create `src/llm_memory/test_support.clj`:

```clojure
(ns llm-memory.test-support
  "Shared test helpers for inline RCF tests.
   Only used when RCF is enabled — all functions are called from (tests ...) blocks."
  (:require [clojure.java.io :as io]
            [llm-memory.core :as mem]
            [llm-memory.store.protocol :as store]
            [llm-memory.store.datalevin :as dtlv]
            [llm-memory.embed.inference4j :as i4j])
  (:import [java.io File]))

(defonce ^:private test-embedder (delay (i4j/create-embedder)))

(defn tmp-dir ^File []
  (let [d (io/file (str "/tmp/llm-idx-test-" (System/nanoTime)))]
    (.mkdirs d) d))

(defn fresh-store []
  (let [s (dtlv/create-store {:path (str "/tmp/llm-store-" (System/nanoTime))
                               :embedder @test-embedder})]
    (store/connect! s) s))

(defn write-plan! [^File root rel-path content]
  (let [f (io/file root "Plans" rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content) f))

(defn root-uri [^File root]
  (str "file://" (.getAbsolutePath root)))
```

## Step 3: Move tests inline — `index.clj`

This is the largest file (14 blocks, 362 lines including the new fuzzy rename tests).

For each `(tests ...)` block in `index_test.clj`:
1. Place it immediately below the function it tests in `index.clj`
2. Add `[llm-memory.test-support :as ts]` to `:require`
3. Replace `fresh-store` → `ts/fresh-store`, `tmp-dir` → `ts/tmp-dir`, etc.
4. Add `[hyperfiddle.rcf :refer [tests]]` to `:require`

Group placement:
- `index-file!` tests → after `index-file!` definition
- `retract-file!` tests → after `retract-file!` / `retract-file-by-id!`
- `rename-file!` tests → after `rename-file!`
- Fuzzy rename tests (`cosine-similarity`, `centroid`, `reconcile!` rename+edit) → after `match-fuzzy-renames`
- `reconcile!` tests → after `reconcile!`
- `index-root!` tests → after `index-root!`
- `index-status` tests → after `index-status`

Verify after each move: `(require '[llm-memory.index] :reload)` in the REPL with RCF enabled.

## Step 4: Move tests inline — `core.clj`

12 blocks, 283 lines. Place after the corresponding functions (`register-root!`, `search`, `list-files`, `related`, `recent`, `status`).

Verify: `(require '[llm-memory.core] :reload)`

## Step 5: Move tests inline — `tools.clj`

10 blocks, 180 lines. Place after each tool function (`search-plans`, `list-plans`, `related-plans`, `recent-plans`, `plans-status`, `index-plans`).

Verify: `(require '[llm-memory.tools] :reload)`

## Step 6: Move tests inline — `watcher.clj`

6 blocks, 174 lines. Place after the watcher functions.

Verify: `(require '[llm-memory.watcher] :reload)`

## Step 7: Merge pipeline tests

`pipeline_test.clj` has 17 blocks testing end-to-end flows (index → search → reconcile). These don't belong to a single function. Options:

- **Option A**: Place at the bottom of `core.clj` (the main API namespace) as integration tests
- **Option B**: Place at the bottom of `index.clj` (the namespace that orchestrates indexing + reconciliation)
- **Recommended: Option A** — `core.clj` is the public API; integration tests belong there

Verify: `(require '[llm-memory.core] :reload)`

## Step 8: Update `Makefile`

```makefile
test:
	clj -X:test
```

Remove the `JVM_OPTS` variable if no longer needed elsewhere.

## Step 9: Delete `test/` directory

```bash
rm -rf test/
```

Remove `"test"` from `:dev :extra-paths` in `deps.edn`.

## Step 10: Verify full test suite

```bash
make test
```

Should produce cloverage output with coverage percentages and all RCF assertions passing. Confirm no test touches the production database path (`~/.local/share/winze/`).

## Step 11: Rebuild and install uberjar

The server uberjar should NOT include test support or RCF deps (they're in `:test`/`:dev` aliases only, not in main `:deps`). Verify:

```bash
cd ../winze-server && make clean uberjar install
```

## Execution Notes

- **Do one namespace at a time** — move tests, verify in REPL, then proceed. Don't batch.
- **RCF blocks are position-sensitive** — they must appear after all functions they call. If a test calls both `index-file!` and `reconcile!`, place it after whichever appears later in the file.
- **`defonce` for embedder** — the test-support namespace uses `defonce` + `delay` so the embedder is created once per JVM, not per test block.
- **Estimated effort**: ~1 hour. Most of the work is mechanical cut-paste with `:require` updates.
