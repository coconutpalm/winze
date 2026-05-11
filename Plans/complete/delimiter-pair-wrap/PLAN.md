---
name: Delimiter-pair wrap — plan
description: Step-by-step plan to wrap selections with matching open/close delimiters on keystroke
type: plan
---

# Delimiter-Pair Wrap — Plan

Context lives in [CONTEXT.md](CONTEXT.md). Read it before starting.
SWT rules from [`Plans/SWT-UI-GUIDE.md`](../../SWT-UI-GUIDE.md) apply:
every UI mutation in `async-exec!`, no `:reload-all`, patch the running
server via `load-file`, and screenshot-verify every visual change.

All edits land in
[`winze-server/src/llm_memory/ui/markdown_editor.clj`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj).
No changes needed to `editor_commands.clj`, `keybindings.clj`, or
`commands.clj` — this feature is not a registered command; it is
pre-insertion keystroke interception scoped to the editor widget.

## Step 1 — Pair table

Add a private map near the other editor-scope top-level defs (above
`install-content-assist-triggers!`):

```clojure
(def ^:private pair-delimiters
  "Map of opener char → closer char. Typing an opener with a non-empty
  selection wraps the selection with opener + closer and leaves the
  inner text selected.

  Includes natural-language brackets / quotes plus single-char Markdown
  inline markers. Multi-char markers (**, __, ~~, ==) are served by the
  Mod1+B / Mod1+I / toggle-strikethrough / toggle-highlight commands."
  {\(  \)
   \[  \]
   \{  \}
   \<  \>
   \"  \"
   \'  \'
   \`  \`
   \*  \*
   \_  \_})
```

No RCF test needed — this is a data literal.

## Step 2 — Pure wrap edit builder

Add a private pure function `wrap-with-pair` that computes the edit
result. Keep it next to `pair-delimiters`:

```clojure
(defn- wrap-with-pair
  "Build an edit-result map that wraps [sel-start, sel-start+sel-length)
  in `text` with `opener` + `closer`. Leaves the inner text selected.
  Returns nil if sel-length is zero or opener has no pair entry."
  [text sel-start sel-length opener]
  (when (and (pos? sel-length)
             (contains? pair-delimiters opener))
    (let [closer    (get pair-delimiters opener)
          selected  (subs text sel-start (+ sel-start sel-length))
          wrapped   (str opener selected closer)]
      {:replace-start  sel-start
       :replace-length sel-length
       :replacement    wrapped
       :select-after   [(inc sel-start) sel-length]})))
```

Then add an RCF block immediately below to verify the math:

```clojure
(tests
 "wrap-with-pair — wraps selection with opener/closer and re-selects inner"
 (wrap-with-pair "hello world" 6 5 \()
 := {:replace-start 6 :replace-length 5
     :replacement   "(world)"
     :select-after  [7 5]}

 (wrap-with-pair "hello world" 0 5 \")
 := {:replace-start 0 :replace-length 5
     :replacement   "\"hello\""
     :select-after  [1 5]}

 (wrap-with-pair "hello world" 0 5 \[)
 := {:replace-start 0 :replace-length 5
     :replacement   "[hello]"
     :select-after  [1 5]}

 "wrap-with-pair — returns nil for zero-length selection"
 (wrap-with-pair "abc" 1 0 \() := nil

 "wrap-with-pair — returns nil for un-paired character"
 (wrap-with-pair "abc" 0 3 \x) := nil
 :rcf)
```

**REPL check:** `load-file` the namespace, verify the RCF block passes
(dev nREPL only, per CLAUDE.md). Then move on.

## Step 3 — Apply-edit helper (local, minimal)

`apply-edit!` lives in `editor-commands.clj` but is public. We can
either require it (introducing a dependency) or inline the two-line
equivalent here, since the edit shape is trivial. Prefer inlining — the
wrap listener is self-contained and shouldn't couple the editor module
to the commands module for a single call.

Inside the Step 4 listener we'll do:

```clojure
(.replaceTextRange st replace-start replace-length replacement)
(.setSelection st sel-start (+ sel-start sel-len))
(.showSelection st)
```

No separate helper. `apply-theme!` re-runs automatically via the
existing `on e/modify-text` handler
([markdown_editor.clj:735-747](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L735-L747)).

## Step 4 — VerifyKeyListener installer

Add a new installer. Place it next to `install-content-assist-triggers!`:

```clojure
(defn- modifier-pressed?
  "True if any keyboard modifier (other than SHIFT) is in stateMask.
  SHIFT is allowed because (, {, <, \" require it on US layouts."
  [state-mask]
  (not (zero? (bit-and state-mask
                       (bit-or SWT/MOD1 SWT/MOD2 SWT/MOD3
                               SWT/CTRL SWT/ALT SWT/COMMAND)))))

(defn install-pair-wrap!
  "Install a VerifyKeyListener on the editor StyledText that wraps the
  active selection with matching delimiters when the user types a pair
  opener character. Leaves the default behaviour untouched when there
  is no selection or a modifier is held. Must be called on the UI
  thread after the widget is created."
  [^StyledText st]
  (.addVerifyKeyListener
   st
   (reify org.eclipse.swt.custom.VerifyKeyListener
     (verifyKey [_ event]
       (when (.doit event)
         (let [ch         (.character event)
               state-mask (.stateMask event)]
           (when (and (contains? pair-delimiters ch)
                      (not (modifier-pressed? state-mask)))
             (let [sel   (.getSelectionRange st)
                   start (.-x sel)
                   len   (.-y sel)]
               (when (pos? len)
                 (when-let [{:keys [replace-start replace-length
                                    replacement select-after]}
                            (wrap-with-pair (.getText st) start len ch)]
                   (set! (.doit event) false)
                   (.replaceTextRange st replace-start replace-length
                                      replacement)
                   (let [[sel-start sel-len] select-after]
                     (.setSelection st sel-start (+ sel-start sel-len)))
                   (.showSelection st)))))))))))
```

Import `org.eclipse.swt.custom.VerifyKeyListener` if SWT's auto-import
doesn't cover it (the namespace already imports
`org.eclipse.swt.custom.StyledText` — `VerifyKeyListener` is the sibling
interface in the same package). Add to the `:import` vector in the
`ns` form:

```clojure
[org.eclipse.swt.custom Bullet ST StyleRange StyledText
 StyledTextLineSpacingProvider VerifyKeyListener]
```

## Step 5 — Wire into `markdown-editor`

In [markdown_editor.clj:758-762](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L758-L762),
the CDT init function ends with:

```clojure
(fn [_props parent]
  (apply-theme! parent content)
  (install-link-interaction! parent abs-path)
  (install-content-assist-triggers! parent abs-path)
  parent)
```

Add the new installer on the line below the content-assist triggers
(order matters only stylistically — `VerifyKeyListener` and
`KeyListener` fire independently):

```clojure
(fn [_props parent]
  (apply-theme! parent content)
  (install-link-interaction! parent abs-path)
  (install-content-assist-triggers! parent abs-path)
  (install-pair-wrap! parent)
  parent)
```

## Step 6 — REPL verification in the running server

Do **not** `:reload-all`. Use `load-file` (per
[SWT-UI-GUIDE](../../SWT-UI-GUIDE.md) — `:reload-all` destroys running
UI state):

```clojure
(clojure.core/load-file
 "winze-server/src/llm_memory/ui/markdown_editor.clj")
```

Because `install-pair-wrap!` is called inside the CDT init function,
already-open editor tabs will **not** pick it up from a bare
`load-file`. Options:

1. **Easiest — new tab.** Open a new editor tab (`Cmd+N` or via the
   tree). The new `StyledText` constructor path runs the updated init
   fn.
2. **Apply to existing tabs.** Retrieve the active StyledText via
   `(mw/element :markdown-editor)` (or whatever the registered id is;
   check `main_window.clj` to confirm the key) and call
   `(install-pair-wrap! st)` on it directly.

Use option 1 for the first verification — it's the clean path and
avoids stale listener state. Option 2 is only needed when visually
comparing behaviour against an existing open document.

### Interactive smoke tests (in the running editor)

For each of the pairs `(`, `[`, `{`, `<`, `"`, `'`, `` ` ``, `*`, `_`:

1. Open a test document containing `the quick brown fox`.
2. Select `quick brown`.
3. Type the opener character.
4. Verify:
   - Text becomes `the <opener>quick brown<closer> fox`.
   - `quick brown` is still the selection.
   - No content-assist popup appears (especially for `(` and `[`).
5. With the selection still active, type a second opener from the
   list — verify chained wrap works
   (`the <o1><o2>quick brown<c2><c1> fox`).

Then, with an **empty** selection:

1. Position caret inside `]` (e.g. after typing `[text]`).
2. Type `(` — verify the content-assist popup opens (existing
   behaviour from `handle-paren-trigger!` must still work).
3. Type `[` twice on a blank line — verify wiki-draft content assist
   fires (existing behaviour from `handle-wiki-draft-trigger!`).

Screenshot-verify each state with fully-qualified
`llm-memory.ui.util/screenshot-widget!` (per CLAUDE.md — aliases fail
intermittently).

### Modifier-key pass-through checks

- Select text, press `Cmd+B` → should still toggle bold (goes through
  the keybinding system; `*` arrives as part of the action, not via a
  raw keystroke, so our listener doesn't fire).
- Select text, press `Cmd+(` → should be a no-op (no command bound);
  verify no wrap happens, no crash.

## Step 7 — Tests and artefacts

- `(tests ... :rcf)` block from Step 2 is the automated coverage. Run
  `make test` from `winze-server/` if CI requires it, but the dev
  nREPL's RCF evaluation on load is sufficient for local confidence.
- No additional fixtures, config files, or docs. The pair table is
  self-documenting in the source.
- Update [`Plans/todo/active-issues.md`](../active-issues.md) to
  remove the "Some punctuation types…" bullet under *Editor* when
  merging. Do not remove it earlier — it is the ticket of record.

## Step 8 — Completion

When the feature is verified in the running server:

1. Run the clj-llm-memory and winze-server test suites
   (`make test` in each subproject) if any refactor has crept in.
2. `make install-winze` from `winze-server/` to rebuild the uberjar
   and update `~/.local/share/winze/`. Restart the server via
   `(llm-memory.ui.main-window/quit!)` (never `pkill` — corrupts
   Datalevin).
3. Move `Plans/todo/delimiter-pair-wrap/` → `Plans/complete/delimiter-pair-wrap/`.
   Delete the bullet from `active-issues.md` as part of the same
   commit.
4. Commit winze-server separately from the root `_finance` repo (the
   winze submodule has its own git history).

## Out of Scope (tracked for future)

- **Unwrap on keystroke.** Pressing `"` around already-quoted text to
  strip the quotes. Non-trivial for asymmetric pairs — defer until
  there is a clear user demand. Symmetric-case unwrap is already
  available via the `Mod1+B`, `Mod1+I`, `Mod1+~`, etc. commands.
- **Multi-char opener support.** Double-star bold, double-underscore
  bold, double-tilde strikethrough, double-equals highlight. Not
  triggerable from a single keystroke; the existing commands cover
  these.
- **Smart quotes.** Any transformation of `"` → `“` / `”` based on
  surrounding context.
- **Per-file-type pairs.** Disable wrap inside fenced code blocks, or
  change the pair set for `$...$` math context. Current implementation
  wraps the same way everywhere in the document.
