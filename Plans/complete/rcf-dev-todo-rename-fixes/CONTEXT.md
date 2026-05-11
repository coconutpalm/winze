# Pre-existing Test Failures — Context

## Background

Four independent bugs cause RCF test failures across two projects
(`clj-llm-memory` and `winze-server`). All four predate the
content-assist-sizing work and were exposed when loading all 24
namespaces with RCF tests in the REPL. None affect runtime behavior
today, but they will cause `make test` failures if the test suites
are ever run in CI.

**Full audit**: All 24 files containing `(tests` blocks were loaded
via `:reload`. Only the failures documented here were found — all
other RCF tests pass.

## Bug 1 — `list-files` omits `:file/title`

**File**: `clj-llm-memory/src/llm_memory/core.clj`, `list-files` (line 389)

The `index-file!` function correctly stores `:file/title` on the file entity
(extracted via `chunk/page-title`). But `list-files` constructs its return map
with an explicit `cond->` that includes `:file/type`, `:file/status`,
`:file/group`, `:file/jira` — but **not** `:file/title`.

The test at `index.clj:648` asserts:

```clojure
(:file/title (first files)) := "Cache Context"
```

This always returns `nil` because `list-files` never propagates the title field
from the pulled entity.

**Root cause**: Missing key in `list-files`'s `cond->` chain.

## Bug 2 — `write-plan!` path vs. delete path mismatch

**Files**: `clj-llm-memory/src/llm_memory/index.clj`, tests at lines 1115, 1159,
1188, 1218, 1242

The `Plans/dev/` → `Plans/todo/` directory rename (commit `cdaf3df`) updated the
project's directory convention but was **not applied to the test fixtures** in
`index.clj`. The test helper `write-plan!` (in `test_support.clj`) creates files
at the literal relative path passed to it:

```clojure
(ts/write-plan! root "dev/X.md" "...")   ;; → root/Plans/dev/X.md
```

But the subsequent file operations assume `Plans/todo/`:

```clojure
(.delete (io/file root "Plans/todo/X.md"))  ;; → root/Plans/todo/X.md (doesn't exist!)
```

The delete is a no-op because the file lives at `Plans/dev/X.md`. After
`index-root!` or `reconcile!`, the original file is still present on disk,
causing cascading assertion failures:

- `index-root!` after delete reports 2 files instead of 1
- `reconcile!` rename detection sees "unchanged + new" instead of "renamed"
- `reconcile!` mixed test counts are all wrong (old files not actually gone)

**Affected tests** (all in `index.clj`):

| Line | Test name | Failure |
|------|-----------|---------|
| 1115 | `index-root!` — full reindex | `:files 2` instead of 1 after delete |
| 1159 | `reconcile!` — exact rename | `:renamed 0`, `:new 1` instead of rename |
| 1188 | `reconcile!` — rename+edit | `:renamed-modified 0`, `:new 1` |
| 1218 | `reconcile!` — unrelated files | `:gone 0` (old file still exists) |
| 1242 | `reconcile!` — mixed | All counts wrong (6 files instead of 3) |

**Root cause**: Stale `"dev/"` prefix in test fixtures after the directory rename.

## Bug 3 — `resolve-key` int/char type mismatch in test assertions

**File**: `winze-server/src/llm_memory/ui/keybindings.clj`, tests at line 354

SWT defines `ESC`, `TAB`, `CR`, `BS`, `DEL` as **`char` constants** in Java.
The `special-keys` map correctly coerces them to `int`:

```clojure
{:esc (int SWT/ESC) ...}  ;; → {:esc 27}
```

But the test asserts against the bare `SWT/ESC` (still a `char`):

```clojure
(resolve-key :esc) := SWT/ESC     ;; (= 27 (char 27)) → false
```

In Clojure, `(= (int 27) (char 27))` is `false` — different boxed types.

The `build-index` test has the same issue: it stores bindings keyed by
`(resolve-key k)` → `(int SWT/ESC)` = `27`, but looks them up with
`SWT/ESC` (char). The char key doesn't match the int key in the map,
so the lookup returns `nil` and `count` returns 0 instead of 1.

**Root cause**: Test assertions use `SWT/ESC` (char) but the code
intentionally coerces to `int`. Tests should use `(int SWT/ESC)`.

## Bug 4 — `tools.clj` test assertions use stale `"dev/"` path + fragile relevance threshold

**File**: `clj-llm-memory/src/llm_memory/tools.clj`, tests at lines 237, 265, 275

Three failures sharing two root causes:

### 4a — `search-plans` badge: `[strong]` vs `[partial]` (line 237)

The test searches for `"caching strategy"` against a file containing
`"Foo context content about caching"`. The assertion expects:

```clojure
(str/includes? result "[strong]") := true
```

But the actual relevance is 49% — just below the `> 0.5` threshold for
`"strong"` in `relevance-badge` (line 21). The result contains `[partial]`
instead.

**Actual output**:
```
### todo/FOO-CONTEXT.md  (slug: [overview], relevance 49% [partial])
```

**Root cause**: Fragile threshold test — semantic similarity scores vary
slightly between embedding model versions and content. The test should
assert on `"relevance"` and `"%"` (format structure), not on a specific
badge level.

### 4b — `list-plans` and `related-plans`: `"dev/"` vs `"todo/"` (lines 265, 275)

The `setup!` function (line 220) correctly creates files at `Plans/todo/`:

```clojure
(spit (io/file root "Plans/todo/FOO-CONTEXT.md") ...)
```

After indexing, the file path is `todo/FOO-CONTEXT.md`. But the test
assertions check for the old `"dev/"` prefix:

```clojure
(str/includes? result "dev/FOO-CONTEXT.md")  ;; ← should be "todo/"
```

This is the same `dev → todo` rename issue as Bug 2, but in a different
file.

**Actual output**:
```
todo/FOO-CONTEXT.md    ← list-plans
todo/FOO-CONTEXT.md    ← related-plans
```

**Root cause**: Test assertions use stale `"dev/"` prefix after the
directory rename.

## Files Affected

| File | Project | Bug |
|------|---------|-----|
| `clj-llm-memory/src/llm_memory/core.clj` | clj-llm-memory | Bug 1 |
| `clj-llm-memory/src/llm_memory/index.clj` | clj-llm-memory | Bug 2 |
| `clj-llm-memory/src/llm_memory/tools.clj` | clj-llm-memory | Bug 4 |
| `winze-server/src/llm_memory/ui/keybindings.clj` | winze-server | Bug 3 |

## Risks

- **Bug 1 fix** could expose callers that rely on `list-files` not including
  `:file/title` — unlikely since the test was written expecting it. No known
  consumers filter on the absence of that key.
- **Bug 2 fix** is a pure test fixture change — no production code affected.
- **Bug 3 fix** is a pure test assertion change — `resolve-key` and
  `build-index` already produce the correct `int` values at runtime.
- **Bug 4a fix** makes the relevance test less specific — acceptable because
  the exact badge level is an implementation detail of the embedding model.
- **Bug 4b fix** is a pure test assertion change — same class as Bug 2.
