---
created: 2026-04-14
tags: tests, test-support, write-plan, index
---

# Test write-plan! Path Mismatch — Plan

Companion: [TEST-WRITE-PLAN-CONTEXT.md](TEST-WRITE-PLAN-CONTEXT.md)

## Steps

### Step 1 — Fix index-file! test (line ~603)

In `clj-llm-memory/src/llm_memory/index.clj`, the "indexes a file and creates
file + chunk entities" test:

Change:
```clojure
_     (ts/write-plan! root "dev/CACHE-CONTEXT.md"
                      "---\ntags: cache\n---\n\n# Cache Context\n\n## Overview…")
abs   (.getAbsolutePath (io/file root "Plans/todo/CACHE-CONTEXT.md"))
```
To:
```clojure
_     (ts/write-plan! root "todo/CACHE-CONTEXT.md"
                      "---\ntags: cache\n---\n\n# Cache Context\n\n## Overview…")
abs   (.getAbsolutePath (io/file root "Plans/todo/CACHE-CONTEXT.md"))
```

**Verify**: REPL — reload `index.clj`, confirm this test no longer errors.

### Step 2 — Fix reconcile! test (line ~1134)

In the same file, the "detects unchanged, modified, new, and gone files" test:

Change:
```clojure
(ts/write-plan! root "dev/A.md" "# A\n\n## Sec\n\nOriginal A.")
(ts/write-plan! root "dev/B.md" "# B\n\n## Sec\n\nOriginal B.")
(index-root! s ruri)
…
(spit (io/file root "Plans/todo/B.md") "# B\n\n## Sec\n\nModified B content.")
(ts/write-plan! root "dev/C.md" "# C\n\n## Sec\n\nNew file C.")
(.delete (io/file root "Plans/todo/A.md"))
```
To:
```clojure
(ts/write-plan! root "todo/A.md" "# A\n\n## Sec\n\nOriginal A.")
(ts/write-plan! root "todo/B.md" "# B\n\n## Sec\n\nOriginal B.")
(index-root! s ruri)
…
(spit (io/file root "Plans/todo/B.md") "# B\n\n## Sec\n\nModified B content.")
(ts/write-plan! root "todo/C.md" "# C\n\n## Sec\n\nNew file C.")
(.delete (io/file root "Plans/todo/A.md"))
```

Note: the `spit` and `.delete` calls already reference `Plans/todo/` correctly;
only the `write-plan!` calls need updating.

**Verify**: REPL — reload `index.clj`, confirm the reconcile test passes.

### Step 3 — Run full test suite

```bash
cd clj-llm-memory && make test
```

Expect: `0 failures, 0 errors` (was `0 failures, 2 errors`).
