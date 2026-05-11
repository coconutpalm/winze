---
created: 2026-04-14
tags: tests, test-support, write-plan, index
---

# Test write-plan! Path Mismatch — Context

## Problem

Two RCF tests in `clj-llm-memory/src/llm_memory/index.clj` fail with
`FileNotFoundException` or attempt to operate on paths that don't exist.
Both failures share the same root cause: tests were written expecting
`ts/write-plan!` with a `"dev/<name>.md"` argument to place files under
`Plans/todo/`, but `write-plan!` places them at `Plans/<rel-path>` verbatim.

## Current `write-plan!` behaviour

```clojure
(defn write-plan! [^File root rel-path content]
  (let [f (io/file root "Plans" rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)
    f))
```

`(write-plan! root "dev/A.md" ...)` → `root/Plans/dev/A.md`

## Failing Tests

### Error 1 — reconcile! test (line 1134)

```clojure
(ts/write-plan! root "dev/A.md" "# A…")       ; → Plans/dev/A.md
(ts/write-plan! root "dev/B.md" "# B…")       ; → Plans/dev/B.md
(index-root! s ruri)                           ; finds Plans/dev/A.md, Plans/dev/B.md ✓
(spit (io/file root "Plans/todo/B.md") "…")   ; Plans/todo/ never created → FAIL
(.delete (io/file root "Plans/todo/A.md"))     ; also a no-op — wrong path
```

The test intends to simulate: "file B is modified, file A is deleted, file C
is new" — classic reconcile scenario. But `Plans/todo/` was never created and
the `spit` throws `FileNotFoundException`.

### Error 2 — index-file! test (line 603)

```clojure
(ts/write-plan! root "dev/CACHE-CONTEXT.md" "…") ; → Plans/dev/CACHE-CONTEXT.md
abs   (.getAbsolutePath (io/file root "Plans/todo/CACHE-CONTEXT.md"))
result (index-file! s (ts/root-uri root) abs)   ; slurps wrong path → FAIL
```

`index-file!` tries to `slurp` the abs path, which is `Plans/todo/CACHE-CONTEXT.md`
(the file doesn't exist). The actual file is at `Plans/dev/CACHE-CONTEXT.md`.

## Root Cause

`write-plan!` was likely updated to accept an arbitrary `rel-path` (enabling
tests to place files in `complete/`, `deferred/`, etc.) but several existing
tests were not updated to match. They still use `"dev/<name>.md"` intending
"an active development file" (≈ `Plans/todo/`), then reference the file via
a hard-coded `Plans/todo/` path.

## Fix

Change the affected `write-plan!` calls from `"dev/<name>.md"` to
`"todo/<name>.md"`. This places files in `Plans/todo/` where the tests expect
them. No changes to `write-plan!` itself or to the production code.

## Affected Locations

| Line | Current call | Fix |
|------|-------------|-----|
| 609  | `(ts/write-plan! root "dev/CACHE-CONTEXT.md" …)` | `"todo/CACHE-CONTEXT.md"` |
| 638  | `abs = Plans/todo/CACHE-CONTEXT.md` | already correct once write-plan! is fixed |
| 1142 | `(ts/write-plan! root "dev/A.md" …)` | `"todo/A.md"` |
| 1143 | `(ts/write-plan! root "dev/B.md" …)` | `"todo/B.md"` |
| 1147 | `(ts/write-plan! root "dev/C.md" …)` | `"todo/C.md"` |

Tests that already use `"todo/<name>"`, `"complete/<path>"`, or `"dev/<name>"`
but construct `abs` from the same `write-plan!` return value are unaffected.
