---
doc_type: plan
status: complete
group: editor-readability
created: 2026-04-21
---

# Editor Readability — Plan

Implementation is sequential. Each step ends with a REPL reload + RCF
pass (where applicable) and, for Steps 2 and 3, a screenshot per
[`SWT-UI-GUIDE.md §15`](../../SWT-UI-GUIDE.md). All REPL reloads use
targeted `:reload` (never `:reload-all` — see §13).

## Step 1 — Emit a separate marker span for headings

Goal: `parse-blocks` emits two spans per heading line — one for
`#…# ` and one for the heading text — so each can be styled
independently.

Edits to
[`md_theme.clj`](../../../winze-server/src/llm_memory/ui/md_theme.clj):

1. Extend `heading-type` with sibling marker keys:
   ```clojure
   (def ^:private heading-marker-type
     {1 :heading/h1-marker, 2 :heading/h2-marker, 3 :heading/h3-marker
      4 :heading/h4-marker, 5 :heading/h5-marker, 6 :heading/h6-marker})
   ```
2. In the `parse-blocks` heading branch
   ([`md_theme.clj:111-116`](../../../winze-server/src/llm_memory/ui/md_theme.clj#L111)),
   replace the single `conj` with two conjs:
   ```clojure
   (let [marker-len (inc level)] ; '#'×level plus the space
     (conj result
           {:start  offset
            :length marker-len
            :type   (heading-marker-type level)}
           {:start  (+ offset marker-len)
            :length (- line-len marker-len)
            :type   (heading-type level)}))
   ```
3. Update the existing heading RCF tests so the expected shape matches
   two spans per heading, and add a coverage case for `######` (H6).

Verify:

```clojure
(require '[llm-memory.ui.md-theme :as mt] :reload)
```

RCF tests pass silently on reload (dev nREPL only — see CLAUDE.md
"RCF tests require a dev-mode REPL").

## Step 2 — Style the marker spans dim

Edits to
[`markdown_editor.clj`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj):

1. Extend the `type->style` map
   ([`markdown_editor.clj:23`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L23))
   with six marker entries. Use the same font as the corresponding
   heading text (so the `#` glyphs sit on the same baseline and share
   metrics) but substitute `color-royal-purple` as the foreground:
   ```clojure
   :heading/h1-marker {:font res/h1-font :fg res/color-royal-purple}
   :heading/h2-marker {:font res/h2-font :fg res/color-royal-purple}
   :heading/h3-marker {:font res/h3-font :fg res/color-royal-purple}
   :heading/h4-marker {:font res/h4-font :fg res/color-royal-purple}
   :heading/h5-marker {:font res/h5-font :fg res/color-royal-purple}
   :heading/h6-marker {:font res/h6-font :fg res/color-royal-purple}
   ```
2. Reload the editor namespace in the running server (targeted, not
   `:reload-all`):
   ```clojure
   (require '[llm-memory.ui.markdown-editor :as me] :reload)
   ```
3. Force a re-theme of an open editor and screenshot:
   ```clojure
   (let [st (… :ui/some-editor-styled-text from app-props …)]
     (ui (me/apply-theme! st (.getText st)))
     (ui (llm-memory.ui.util/screenshot-widget! st "/tmp/editor-markers.png")))
   ```
4. If `color-royal-purple` still reads too loud against mine-shaft, add
   a new palette entry in
   [`resources.clj`](../../../winze-server/src/llm_memory/ui/resources.clj)
   (e.g. `color-marker-muted` around `#3A335E`, matching the
   find-bar tone) and retarget the six marker styles. Any new `Color`
   follows the registry rules in
   [`SWT-UI-GUIDE.md §29`](../../SWT-UI-GUIDE.md) and is disposed on
   shell-closed alongside the existing colors.

## Step 3 — Loosen line pitch for prose and lists

Goal: match the viewer's `line-height: 1.7` on prose and list lines
while keeping code blocks tight (viewer's `line-height: normal`).

Implement with a per-line callback on the editor — `StyledText` accepts
a `StyledTextLineSpacingProvider` that returns extra leading in pixels
for each line.

Edits to
[`markdown_editor.clj`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj):

1. Add a pure helper that classifies a line as prose or code, using
   the `blocks` already computed by
   [`apply-theme!`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L194):
   ```clojure
   (defn- line-in-code-block?
     "True if line `line-idx` in `styled-text` falls inside a
     :code-block span (including the fence lines)."
     [styled-text blocks line-idx]
     (let [off (.getOffsetAtLine styled-text line-idx)]
       (boolean
        (some (fn [{:keys [start length type]}]
                (and (= type :code-block)
                     (<= start off (+ start length))))
              blocks))))
   ```
2. Add an `apply-line-spacing!` step invoked from `apply-theme!` right
   after `apply-code-block-line-backgrounds!`. Install a per-editor
   provider that returns `0` for code-block lines and a prose constant
   (start at 6px — iterate at the REPL) for everything else:
   ```clojure
   (defn- apply-line-spacing!
     [^StyledText st blocks]
     (.setLineSpacingProvider
      st
      (reify org.eclipse.swt.custom.StyledTextLineSpacingProvider
        (getLineSpacing [_ line-idx]
          (if (line-in-code-block? st blocks line-idx)
            0
            6)))))
   ```
   The provider closes over `blocks`, which is re-created on every
   `apply-theme!` call — so spacing stays correct as the user edits.
3. In
   [`apply-theme!`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L194)
   add the call after the existing code-block background pass:
   ```clojure
   (apply-code-block-line-backgrounds! styled-text blocks)
   (apply-line-spacing!                 styled-text blocks)
   (apply-list-rendering!               styled-text blocks)
   ```

Verify in REPL:

```clojure
(require '[llm-memory.ui.markdown-editor :as me] :reload)
;; Re-theme an open editor:
(let [st  <editor-styled-text>]
  (ui (me/apply-theme! st (.getText st)))
  (ui (llm-memory.ui.util/screenshot-widget! st "/tmp/editor-pitch.png")))
```

Iterate the prose constant (6 → 4 → 8) until the editor's visual
density roughly matches the viewer card side-by-side on screen. Lock in
the chosen value as a `def ^:private prose-line-spacing-px` at the top
of `markdown_editor.clj` rather than an inline literal.

## Step 4 — Validate across the content matrix

Open an editor tab on a document that exercises:

- H1 through H6 headings
- A multi-paragraph body with inline bold/italic/links
- A bulleted list, a numbered list, a checkbox list
- A fenced code block with box-drawing characters

Screenshot the editor beside the viewer for the same file. Confirm:

- Hash glyphs are visibly dimmer than the heading text.
- Prose and list lines have the same "airiness" as the viewer card.
- Code block lines are as tight as before (box-drawing stays connected
  — see [`complete/code-block-line-height/CONTEXT.md`](../../complete/code-block-line-height/CONTEXT.md)
  for the precedent).

## Step 5 — `make install` and task wrap-up

Only after Steps 1–4 are all green and screenshot-verified:

```bash
cd winze-server && make install
```

Per CLAUDE.md "Concurrent nREPL Access", do the single install after
all REPL iteration is complete. Server restarts on install.

After user confirmation, move this plan group to
`Plans/complete/editor-readability/` per the root CLAUDE.md archival
convention.

---

## Post-hoc — what actually shipped in Step 3

The original Step 3 recipe (provider alone returning
`prose-line-spacing-px` for every non-code source line) had two
unexpected weaknesses discovered during screenshot review:

1. **Wrapped (soft-wrap) visual lines got no leading.** SWT's
   `StyledTextLineSpacingProvider.getLineSpacing` fires per *source*
   line (newline-separated), not per visual line. A long paragraph that
   is one source line wrapping across three screen lines only received
   leading once at its source-line boundary — its internal wrap seams
   were as tight as raw font line-height.
2. **Markdown paragraph breaks were far too tall.** A blank source line
   separating two paragraphs got 6 px leading both above and below,
   stacking to roughly 2× line-height plus the blank's own natural
   height.

The shipped implementation combines two SWT primitives:

- **Uniform `setLineSpacing(prose-line-spacing-px)`** — applies per
  visual line, so wrapped segments of a single source line breathe.
- **Provider override** — `setLineSpacingProvider` returns `0` for two
  specific source-line classes, which in practice suppresses the
  uniform leading on just those lines:
  - blank lines (compresses paragraph breaks to one blank-line height)
  - code-block lines (keeps code tight — mirrors viewer CSS
    `pre { line-height: normal }`)

```clojure
(defn- apply-line-spacing!
  [^StyledText styled-text blocks]
  (.setLineSpacing styled-text prose-line-spacing-px)
  (.setLineSpacingProvider
   styled-text
   (reify StyledTextLineSpacingProvider
     (getLineSpacing [_ line-idx]
       (int
        (cond
          (line-in-code-block? styled-text blocks line-idx) 0
          (blank-line? styled-text line-idx)                0
          :else                                             prose-line-spacing-px))))))
```

### Gotchas captured here for future SWT work

- **`StyledTextLineSpacingProvider.getLineSpacing` must return `int`**,
  not a Clojure `Long`. A literal `6` from a `reify` body boxes as
  `Long` and throws `ClassCastException` on every paint — which in a
  running server propagates through the SWT event loop and exhausts
  the nREPL thread pool (symptom: `.nrepl-port` / `.pid` files
  disappear while the JVM keeps running but never responds).
  Wrap the return expression in `(int …)`.
- The provider overrides the uniform baseline on the source lines it
  covers, but the uniform still controls spacing between *visual*
  (wrapped) segments of a source line. That's precisely what lets the
  hybrid model give different spacing rules to source-line boundaries
  vs. wrap-internal seams.
