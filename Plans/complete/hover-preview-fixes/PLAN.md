# Hover Preview Fixes — Plan

Implementation order is sequential with REPL verification after each
step. All edits land in
[`winze-server/src/llm_memory/ui/link_preview.clj`](../../../winze-server/src/llm_memory/ui/link_preview.clj)
unless otherwise noted.

Follow the usual SWT rules from
[`Plans/SWT-UI-GUIDE.md`](../../SWT-UI-GUIDE.md): UI mutations under
`async-exec!`, never `:reload-all` a running UI namespace, use
`load-file` to patch the running server, and screenshot-verify every
visual change.

## Step 1 — Widen the preview resolver

Goal: one function that takes a "link reference string" plus context
and returns the data needed to render a preview, or nil.

1. Rename the private `resolve-wiki-preview` to `resolve-file-preview`.
   It accepts a map — `{:file-path :root-uri :slug}` — produced by the
   callers in Steps 2 and 3, and returns the existing card-ready map
   or nil. No string parsing inside this function.

2. **File-level content** (addresses Issue 3): if `slug` is blank,
   query for the chunk with minimum `:chunk/section` for this file and
   use its text as `:chunk/text`. Fall back to
   `(str "# " (or title file-path))` only when the file has no
   indexed chunks at all.

   Datalog sketch (one query — pull the lowest-section chunk's text):

   ```clojure
   (query-fn s
     '[:find ?text ?section
       :in $ ?path ?ruri
       :where
       [?r :root/uri ?ruri]
       [?f :file/root ?r]
       [?f :file/path ?path]
       [?c :chunk/file ?f]
       [?c :chunk/text ?text]
       [?c :chunk/section ?section]]
     {:path file-path :ruri root-uri})
   ```

   Then `(->> results (sort-by second) first first)` for the
   minimum-section text. (Datalevin's aggregate `min` would be tidier
   but requires confirming aggregate support in the current version —
   do that in the RCF test rather than in a rushed first cut.)

3. Add a second helper `parse-link-dest` in the same namespace:

   ```clojure
   (defn- parse-link-dest
     "Normalize a raw link destination (edit-mode :dest or view-mode
      href) to {:file-path :root-uri :slug} or nil. `abs-path` and
      `root-uri` describe the file containing the link; used for
      relative-path resolution."
     [^String dest {:keys [abs-path root-uri]}]
     (cond
       ;; wiki:root::path[#slug]
       (str/starts-with? dest "wiki:") ...

       ;; winze:open-file?root=...&path=...[#slug]
       (str/starts-with? dest "winze:open-file?") ...

       ;; plain relative .md
       (and abs-path
            (local-md-link? dest))
       ...))
   ```

   For the `winze:open-file?` branch, reuse the existing
   `parse-query-string` already imported by `main_window.clj`
   (lift it or its equivalent into a shared util if necessary).
   For the relative branch, resolve against
   `(-> abs-path io/file .getParent)` using `java.nio.file.Paths`
   exactly as `rewrite-local-link` already does — i.e. factor the
   path-resolution logic out of `hiccup.clj` and share it, rather
   than copying.

**RCF verification** (add inline tests):

```clojure
(tests
 (parse-link-dest "wiki:winze::foo.md#bar"
                  {:abs-path "/tmp/x.md" :root-uri "file:///tmp"})
 := {:file-path "foo.md" :root-uri "winze" :slug "bar"}

 (parse-link-dest "winze:open-file?root=file%3A%2F%2F%2Ftmp&path=a.md"
                  {:abs-path "/tmp/x.md" :root-uri "file:///tmp"})
 := {:file-path "a.md" :root-uri "file:///tmp" :slug nil}

 (parse-link-dest "sibling.md"
                  {:abs-path "/tmp/sub/x.md" :root-uri "file:///tmp"})
 := {:file-path "sub/sibling.md" :root-uri "file:///tmp" :slug nil}

 (parse-link-dest "https://example.com" {}) := nil
 :rcf)
```

Run from the `:dev` alias nREPL (not the live Winze server).

## Step 2 — Fix the editor path

In `install-link-preview!` and `schedule-editor-preview!`:

1. Change the installer signature to
   `(install-link-preview! st abs-path root-uri)`. Thread the new
   arguments through `main_window.clj:544` from the edit-mode switch
   site (the same call already knows `abs-path`; `root-uri` lives on
   the `open-files` entry).

2. In the `MouseMoveListener` dispatcher, drop the
   `(str/starts-with? dest "wiki:")` gate. Route the span's `:dest`
   through `parse-link-dest` with `{:abs-path :root-uri}`; when it
   returns a non-nil ref, pass it to a new
   `schedule-editor-preview!` that accepts the already-resolved ref
   (instead of the dest string).

3. Apply the same treatment to the `CaretListener` branch. Use the
   **normalized ref** as the dedupe key for `:current-uuid`
   (rename the atom field to `:current-ref` for clarity).

4. Remove dead paths: anywhere that previously short-circuited on
   "not a wiki link" should now short-circuit only on
   `(nil? normalized-ref)`.

**Verify** in the running editor:

- Open a `.md` file that contains `[foo](other.md)` and
  `[bar](wiki:winze::other.md)`. Hover each with **no MOD1 held**;
  expect the card popup after ~300ms.
- Place the caret inside each; expect the popup after ~200ms.
- Hover an `https://` link; expect **no** popup.
- Screenshot both cases per
  [SWT-UI-GUIDE §visual](../../SWT-UI-GUIDE.md) and attach to the
  plan before marking done.

## Step 3 — Fix the viewer path

In `install-browser-link-preview!` and `hover-js`:

1. Broaden the selector to match any href that `parse-link-dest` can
   resolve. Simplest correct form:

   ```javascript
   var a = e.target.closest(
     'a[href^="wiki:"], a[href^="winze:open-file?"]'
   );
   ```

   (Relative-path `[text](foo.md)` destinations are already rewritten
   to `winze:open-file?` by `hiccup/rewrite-local-link`, so matching
   those two prefixes covers every in-DOM form that represents a
   local file.)

2. Change the `wpreviewHover` callback contract so JS passes the full
   `href` (not `href.substring(5)`). On the Clojure side, resolve it
   via `parse-link-dest` with `:abs-path` / `:root-uri` **left nil**
   — the rewritten `winze:` URL already carries absolute info, and
   bare `wiki:` refs don't need a containing-file context.

3. Keep `wpreviewLeave` as-is.

4. Preserve the existing `ProgressAdapter` injection pattern; re-JS
   on every page load.

**Verify** in the running viewer:

- Open a file that contains both kinds of link. Hover each; expect
  preview. Move off; expect dismissal after the preview-track leave.
- Hover the external (`https://`) link in the same file; expect no
  preview.
- Screenshot confirmation.

## Step 4 — Fix the preview body

This is folded into Step 1 (point 2). Re-verify explicitly here: hover
a file-level link (no heading anchor). The popup must render the H1
plus the first section of content, not a bare `# title` line.

Screenshot confirmation against a file with a clear first section
(e.g. `Plans/complete/file-refresh-regression/CONTEXT.md` which has a
recognisable "## Symptom" as section 0's body).

## Step 5 — Hot-patch the running server

Never `:reload-all`. Use the nREPL-on-62643 pattern:

```bash
clj-nrepl-eval -p 62643 << 'EOF'
(load-file "winze-server/src/llm_memory/ui/link_preview.clj")
;; Re-install on the currently-focused editor + viewer to pick up
;; the new installer signature. Leave other tabs alone; they'll get
;; the new code when their editors are recreated.
EOF
```

For broader uptake, run `make install` from `winze-server/` and
restart via `(llm-memory.ui.main-window/quit!)` (see CLAUDE.md
"Graceful Shutdown" — never `pkill`).

## Out of Scope

- **Viewer wiki-click navigation regression** (active-issues.md bullet
  "Clicking `wiki:` links doesn't work" in the Viewer section): same
  root cause as 2a — `rewrite-local-link` mangles `wiki:root::*.md`.
  Fixing `local-md-link?` to exclude `wiki:` destinations touches the
  click path and needs its own verification. Capture as a follow-up
  plan in `Plans/todo/wiki-link-rewrite-exclusion/`.
- **Cmd-click navigation for non-wiki links in the editor**
  (active-issues.md bullet): `navigate-link!` already handles
  relative `.md` dests, so the reported bug is likely a data-flow
  issue (cursor feedback works because the span is detected, but
  click fires on a different offset). Separate diagnosis.
- **Theme/UX polish** items from "Additional desired features" in
  active-issues.md — orthogonal, batch separately.

## Files Changed (estimate)

| File | Lines | Nature |
|------|-------|--------|
| `link_preview.clj` | ~80 modified | resolver split, selector, wiring |
| `main_window.clj` | ~3 modified | pass `root-uri` to installer |
| `hiccup.clj` (optional) | ~10 | extract path-resolution helper if shared |

## Verification Checkpoints

| Step | Verification |
|------|--------------|
| 1 | RCF tests pass for `parse-link-dest`; manual REPL call to `resolve-file-preview` for a file-level ref returns a chunk with H1 + first section body |
| 2 | Hover/caret preview fires for both wiki and relative `.md` editor links; external links still ignored; MOD1 dismiss still works |
| 3 | Hover preview fires for both wiki and relative `.md` viewer links; external links ignored; BrowserFunctions disposed on Browser close (no leaks in logs) |
| 4 | File-level hover renders H1 + first section; heading-level hover unchanged |
| 5 | `make install` + graceful restart, exercise all four cases, screenshots attached |
