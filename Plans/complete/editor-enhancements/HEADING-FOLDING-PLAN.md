# Heading-Level Folding — Plan

**Prerequisites**:
- Read `winze/Plans/SWT-UI-GUIDE.md` before implementation
- See [HEADING-FOLDING-CONTEXT.md](HEADING-FOLDING-CONTEXT.md) for design
  constraints and approach options
- **Depends on**: Command registry + prefix keys from
  [COMMAND-PALETTE-PLAN.md](COMMAND-PALETTE-PLAN.md);
  `extract-headings` from [EDITOR-COMMANDS-PLAN.md](EDITOR-COMMANDS-PLAN.md)
  Phase 3, Step 6b

---

## Step 1 — Fold region calculation

Add a function that computes fold regions from heading positions.

**File**: `winze-server/src/llm_memory/ui/markdown_editor.clj` (or a new
`folding.clj` if the logic is substantial)

```clojure
(defn fold-regions
  "Given a seq of {:level :line} heading maps and total line count,
  return a seq of {:heading-line N :start-line N :end-line N :level N}.
  Each region spans from the heading to the line before the next heading
  of equal-or-higher level (or end of document)."
  [headings line-count]
  ...)
```

RCF tests:
- Single heading → region spans to end of document
- Two same-level headings → each region ends before the next
- Nested headings (H2 under H1) → H2 region ends before next H1 or H2
- Empty document → no regions

**Verify**: REPL — compute fold regions for a sample document, confirm ranges.

---

## Step 2 — Fold state tracking

Add fold state to the editor's per-instance state.

```clojure
;; In the editor's state atoms:
:fold-state (atom {:regions []        ;; computed fold regions
                   :folded  #{}})     ;; set of heading-line numbers that are folded
:shadow-text (atom nil)               ;; full unfolded text, used by auto-save
```

Update `apply-theme!` to recompute fold regions whenever the document changes
(headings may have moved).

**Verify**: REPL — edit a document, confirm fold regions update as headings
are added/removed.

---

## Step 3 — Toggle fold (text replacement approach)

Implement fold/unfold as text replacement with shadow buffer.

**3a — Fold**:
```clojure
(defn fold-at-line!
  "Collapse the fold region at `heading-line`. Replaces the region's body
  lines with a single summary line. Stores full text in shadow-text."
  [styled-text fold-state shadow-text heading-line]
  ;; 1. Save current full text to shadow-text (if not already shadowed)
  ;; 2. Find the region for heading-line
  ;; 3. Replace lines (start-line+1 .. end-line) with " ▸ (N lines)"
  ;;    appended to the heading line
  ;; 4. Add heading-line to :folded set
  ;; 5. Recompute fold regions (line numbers shifted)
  ;; 6. Re-apply theme
  )
```

**3b — Unfold**:
```clojure
(defn unfold-at-line!
  "Expand the fold region at `heading-line`. Restores the original text
  from shadow-text."
  [styled-text fold-state shadow-text heading-line]
  ;; 1. Get the original text for this region from shadow-text
  ;; 2. Replace the summary line with the full heading + body
  ;; 3. Remove heading-line from :folded set
  ;; 4. Recompute fold regions
  ;; 5. Re-apply theme
  )
```

**3c — Toggle**:
```clojure
(defn toggle-fold!
  "Toggle fold state at the cursor's current heading."
  [styled-text fold-state shadow-text]
  (let [cursor-line (.getLineAtOffset styled-text (.getCaretOffset styled-text))
        region      (enclosing-region cursor-line (:regions @fold-state))]
    (when region
      (if (contains? (:folded @fold-state) (:heading-line region))
        (unfold-at-line! ...)
        (fold-at-line! ...)))))
```

**3d — Auto-save integration**:

The auto-save callback must write the full unfolded text:
```clojure
;; In schedule-save!:
(let [content (or @shadow-text (.getText styled-text))]
  (spit abs-path content))
```

**Verify**: REPL — fold a section, confirm lines disappear and summary shows.
Unfold, confirm lines return. Verify auto-saved file contains full text.

---

## Step 4 — Fold All / Unfold All

```clojure
(defn fold-all!
  "Fold all top-level heading regions."
  [styled-text fold-state shadow-text]
  ;; Fold regions in reverse order (bottom-up) so line numbers
  ;; remain valid as earlier regions shift
  ...)

(defn unfold-all!
  "Unfold all folded regions. Restores full text from shadow."
  [styled-text fold-state shadow-text]
  ;; Replace entire widget text with shadow-text
  ;; Clear :folded set
  ;; Recompute regions
  ...)
```

**Verify**: REPL — fold all in a multi-heading document, confirm all sections
collapsed. Unfold all, confirm full document restored.

---

## Step 5 — Register commands and keybindings

Register fold commands:
```clojure
(register! {:id :editor/toggle-fold
            :label "Toggle Fold at Cursor"
            :category :fold
            :action (fn [] (toggle-fold! ...))})

(register! {:id :editor/fold-all
            :label "Fold All"
            :category :fold
            :action (fn [] (fold-all! ...))})

(register! {:id :editor/unfold-all
            :label "Unfold All"
            :category :fold
            :action (fn [] (unfold-all! ...))})
```

Add keybindings to `editor.keybinding`:
```clojure
{:key \[ :mod #{:mod1 :shift} :when {:in :editor}
 :command :editor/toggle-fold}
{:prefix {:key \k :mod #{:mod1}} :key \0 :mod #{:mod1}
 :when {:in :editor}
 :command :editor/fold-all}
{:prefix {:key \k :mod #{:mod1}} :key \j :mod #{:mod1}
 :when {:in :editor}
 :command :editor/unfold-all}
```

**Verify**: REPL — Mod1+Shift+[ toggles fold. Mod1+K Mod1+0 folds all.
Mod1+K Mod1+J unfolds all.

---

## Step 6 — Undo interaction

Decide on undo behavior while folds are active:

**Option A — Suspend undo**: Disable undo/redo while any fold is active.
Unfold-all before any undo operation. Simplest but limits usability.

**Option B — Fold-aware undo**: Fold/unfold operations push onto the undo
stack as special entries. Undo can reverse a fold. More complex but correct.

Recommend **Option A** for v1 — it's safe and simple. If users find it
limiting, upgrade to Option B later.

**Verify**: REPL — fold a section, try Mod1+Z, confirm it either does nothing
or unfolds first.

---

## Summary of Changes

| File | Lines (est.) | Nature |
|------|-------------|--------|
| `markdown_editor.clj` (or `folding.clj`) | ~150 | Fold regions, state, text replacement, shadow buffer |
| `commands.clj` | ~15 | Register 3 fold commands |
| `editor.keybinding` | ~10 | Fold keybindings (including prefix keys) |

## Verification Checkpoints

| Step | How |
|------|-----|
| 1 | `fold-regions` produces correct ranges for sample documents |
| 2 | Fold state updates as document is edited |
| 3 | Toggle fold collapses/expands section; auto-save writes full text |
| 4 | Fold all / unfold all work on multi-heading documents |
| 5 | Keybindings fire correctly, including prefix-key fold all |
| 6 | Undo behavior is safe (no data loss) |
