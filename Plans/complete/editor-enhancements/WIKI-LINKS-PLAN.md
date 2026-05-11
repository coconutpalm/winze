# Markdown Wiki Links — Plan

**Prerequisites**:
- Read `winze/Plans/SWT-UI-GUIDE.md` before implementation
- See [WIKI-LINKS-CONTEXT.md](WIKI-LINKS-CONTEXT.md) for architecture and
  design decisions
- **Depends on**: content assist popup (Phase 3, Step 8) and wiki link registry
  (Phase 3, Step 6) from [EDITOR-COMMANDS-PLAN.md](EDITOR-COMMANDS-PLAN.md)

---

## Step 1 — Wiki-draft styling in StyledText (`md_theme.clj`)

Add `:inline/wiki-draft` as a new inline pattern so `[[...]]` text is styled
with a dotted underline while the user is typing it.

**File**: `winze-server/src/llm_memory/ui/md_theme.clj`

1. Add to `inline-patterns`:
   ```clojure
   [:inline/wiki-draft #"\[\[([^\]]*)\]\]"]
   ```
   Place **before** `:inline/link` so `[[...]]` is detected before the
   generic link pattern can partially match the brackets.

2. Add to `type->style`:
   ```clojure
   :inline/wiki-draft {:fg res/color-amethyst
                       :underline true
                       :underline-style SWT/UNDERLINE_DOT}
   ```
   SWT `StyleRange` supports `.underline` (boolean) and `.underlineStyle`
   (`SWT.UNDERLINE_SINGLE`, `SWT.UNDERLINE_DOUBLE`, `SWT.UNDERLINE_ERROR`,
   `SWT.UNDERLINE_SQUIGGLE`, `SWT.UNDERLINE_LINK`). Check if
   `SWT/UNDERLINE_DOT` exists — it was added in SWT 3.4. If not available,
   use `SWT/UNDERLINE_SQUIGGLE` as a visual alternative. Verify via
   `(swtdoc StyleRange)` in the REPL.

3. Update `span->style-range` in `markdown_editor.clj` to handle the new
   underline fields:
   ```clojure
   (when underline       (set! (.-underline sr) true))
   (when underline-style (set! (.-underlineStyle sr) underline-style))
   ```

4. Add RCF tests for wiki-draft span detection.

**Verify**: REPL — open a file containing `[[some text]]` in edit mode and
confirm it's styled with amethyst color + dotted underline.

---

## Step 2 — Extract link destinations in `md_theme.clj`

Extend `find-inline-spans` to include `:dest` metadata for link spans, so the
editor's MOD1-click handler knows where to navigate.

**File**: `winze-server/src/llm_memory/ui/md_theme.clj`

1. For `:inline/link` spans (`[text](url)`), extract the URL from the capture
   group. Change the regex to capture the destination:
   ```clojure
   [:inline/link #"\[([^\]]*)\]\(([^\)]*)\)"]
   ```
   Store the second capture group as `:dest` in the span map.

2. For `:inline/wiki-draft` spans (`[[target]]`), store the inner text as
   `:dest` with the original brackets — the `[[...]]` trigger handler (Step 5)
   uses this to locate and replace the span:
   ```clojure
   {:start 40 :length 22 :type :inline/wiki-draft :dest "OPEN-QUESTIONS"}
   ```

3. The span map becomes:
   ```clojure
   {:start 5 :length 28 :type :inline/link :dest "wiki:a1b2c3d4-..."}
   {:start 40 :length 22 :type :inline/wiki-draft :dest "OPEN-QUESTIONS"}
   ```

4. Update `theme` to propagate `:dest` through the span pipeline (currently
   spans are merged/split — ensure `:dest` survives).

5. Add RCF tests verifying `:dest` extraction for both link types.

**Verify**: REPL — call `find-inline-spans` on a line with both link types and
confirm `:dest` values.

---

## Step 3 — MOD1-click navigation in StyledText (`markdown_editor.clj`)

Add a `MouseListener` to the StyledText widget that navigates to the link
target on MOD1-click.

**File**: `winze-server/src/llm_memory/ui/markdown_editor.clj`

### 3a — Store link spans per editor

After `apply-theme!` runs, retain the span list (with `:dest` metadata) in an
atom associated with the editor:

```clojure
:link-spans (atom [])  ;; [{:start :length :type :dest} ...]
```

Update `apply-theme!` to populate `:link-spans` after each restyle.

### 3b — Hit-test function

```clojure
(defn- link-at-offset
  "Return the link span at `offset`, or nil."
  [link-spans offset]
  (some (fn [{:keys [start length] :as span}]
          (when (<= start offset (+ start length -1))
            span))
        link-spans))
```

### 3c — MouseListener

```clojure
(.addMouseListener styled-text
  (proxy [MouseAdapter] []
    (mouseDown [^MouseEvent e]
      (when (not= 0 (bit-and (.stateMask e) SWT/MOD1))
        (try
          (let [offset (.getOffsetAtPoint styled-text (Point. (.x e) (.y e)))
                span   (link-at-offset @link-spans offset)]
            (when span
              (handle-link-click! span)))
          (catch IllegalArgumentException _))))))
```

### 3d — `handle-link-click!` dispatch

```clojure
(defn- handle-link-click!
  "Handle MOD1-click on a link span."
  [span]
  (let [{:keys [type dest]} span]
    (case type
      ;; Draft wiki link — clicking it also triggers resolution
      ;; (same as selecting from the popup, but via click instead)
      :inline/wiki-draft
      (resolve-wiki-draft! dest)

      ;; Resolved link — navigate by protocol
      :inline/link
      (navigate-link! dest)

      nil)))

(defn- navigate-link!
  "Open the link target. Dispatches by protocol."
  [dest]
  (cond
    ;; wiki:uuid — look up in Datalevin
    (str/starts-with? dest "wiki:")
    (let [uuid (subs dest 5)]
      (resolve-and-navigate-wiki-uuid! uuid))

    ;; winze:open-file? — parse and open
    (str/starts-with? dest "winze:open-file?")
    (let [params   (parse-query-string (subs dest (count "winze:open-file?")))
          root-uri (get params "root")
          rel-path (get params "path")]
      (open-file-in-tab! root-uri rel-path))

    ;; Relative .md link — resolve relative to current file
    (local-md-link? dest)
    (let [{:keys [root-uri file-dir]} (current-file-context)]
      (open-file-in-tab! root-uri (resolve-relative dest file-dir)))

    ;; External URL — system browser
    (or (str/starts-with? dest "https://")
        (str/starts-with? dest "http://")
        (str/starts-with? dest "mailto:"))
    (.browse (java.awt.Desktop/getDesktop) (java.net.URI. dest))

    ;; Fragment-only link — scroll within current file
    (str/starts-with? dest "#")
    nil))
```

Note: `resolve-and-navigate-wiki-uuid!` is implemented in the editor-commands
plan (Phase 3, Step 13). It can be a stub initially.

### 3e — `open-file-in-tab!` helper

Extract the file-opening logic from `custom-browser`'s `on-changing` handler
into a shared function. Move to `main_window.clj`, make public.

**Verify**: REPL — open a file in edit mode, MOD1-click a `[text](wiki:uuid)`
link, confirm a new tab opens.

---

## Step 4 — Cursor feedback on hover

Change the mouse cursor to a hand pointer when hovering over a link with MOD1
held.

**File**: `winze-server/src/llm_memory/ui/markdown_editor.clj`

1. Create a `SWT/CURSOR_HAND` cursor from the Display.
2. Add a `MouseMoveListener`:
   ```clojure
   (.addMouseMoveListener styled-text
     (proxy [MouseMoveListener] []
       (mouseMove [^MouseEvent e]
         (let [mod1? (not= 0 (bit-and (.stateMask e) SWT/MOD1))
               over-link? (when mod1?
                            (try
                              (let [offset (.getOffsetAtPoint styled-text
                                            (Point. (.x e) (.y e)))]
                                (link-at-offset @link-spans offset))
                              (catch IllegalArgumentException _ nil)))]
           (.setCursor styled-text (if over-link? hand-cursor nil))))))
   ```
3. Dispose the cursor when the StyledText is disposed.

**Verify**: REPL — hover over a link with MOD1 held, confirm hand cursor.

---

## Step 5 — `[[...]]` trigger for content assist

Detect `[[` in the editor and open the content assist popup. When the user
selects a result (or creates a new page), rewrite the `[[...]]` span into a
standard `[title](wiki:uuid)` link.

**Depends on**: content assist popup from
[EDITOR-COMMANDS-PLAN.md](EDITOR-COMMANDS-PLAN.md) (Phase 3, Steps 8-10).

**File**: `winze-server/src/llm_memory/ui/markdown_editor.clj`

### 5a — Detect `[[` trigger

In the editor's `key-down` or `modify-text` handler, detect when `[[` is typed:

```clojure
(when (and (= (.character event) \[)
           (> caret 0)
           (= \[ (.charAt text (dec caret))))
  ;; User typed [[
  ;; Start tracking the wiki-draft span
  (let [start (dec caret)]  ;; position of first [
    (open-wiki-draft! styled-text start)))
```

### 5b — Open content assist with live filtering

Once `[[` is detected, open the content assist popup. As the user types
between the brackets, the popup filters live:

```clojure
(defn- open-wiki-draft!
  "Open content assist popup seeded by the text between [[ and the cursor.
  The popup filters as the user types."
  [styled-text bracket-start]
  ;; Track the start of the [[ so we know what to replace later
  ;; Open the content assist popup with:
  ;;   :seed-text → text between [[ and cursor (initially empty)
  ;;   :mode → :wiki-create (adds "Create new page" option)
  ;;   :on-select → (fn [result] (rewrite-wiki-draft! ...))
  ;;   :on-cancel → (fn [] (leave [[...]] as plain text))
  )
```

The popup's modify listener updates the seed text as the user types, triggering
re-search on each keystroke.

### 5c — Handle selection: existing page

When the user selects an existing page/heading from the popup:

```clojure
(defn- rewrite-wiki-draft!
  "Replace the [[...]] span with [title](wiki:uuid)."
  [styled-text bracket-start result]
  (let [{:keys [uuid title]} result
        ;; Find the end of the [[...]] span (look for ]])
        text     (.getText styled-text)
        end      (or (str/index-of text "]]" bracket-start)
                     (.getCaretOffset styled-text))
        end      (if end (+ end 2) (.getCaretOffset styled-text))
        ;; Build the replacement
        link     (str "[" title "](wiki:" uuid ")")]
    (.replaceTextRange styled-text bracket-start (- end bracket-start) link)
    ;; Place caret after the link
    (.setCaretOffset styled-text (+ bracket-start (count link)))))
```

### 5d — Handle selection: create new page

When the user selects "Create new page":

```clojure
(defn- create-and-link!
  "Create a new file from the [[...]] text, index it, and rewrite the link."
  [styled-text bracket-start typed-text]
  (let [;; Derive filename
        slug     (chunk/slugify typed-text)
        filename (str slug ".md")
        ;; Determine directory (same as current file)
        {:keys [root-uri file-dir]} (current-file-context)
        abs-path (str file-dir "/" filename)
        ;; Derive page title (capitalize the typed text)
        title    (str/capitalize typed-text)]
    ;; Create the file with H1
    (spit abs-path (str "# " title "\n"))
    ;; The filesystem watcher will index it and create :wiki/* + :file/* entities
    ;; Wait briefly for indexing, then query the UUID
    ;; (or generate it deterministically — same as index-file! would)
    (let [uuid (wiki-uuid file-id slug)]
      ;; Rewrite the [[...]] into [title](wiki:uuid)
      (rewrite-wiki-draft! styled-text bracket-start
                           {:uuid uuid :title title})
      ;; Optionally open the new file in a tab
      (open-file-in-tab! root-uri (str (rel-dir file-dir) "/" filename)))))
```

### 5e — Handle cancel

If the user presses Esc or clicks outside the popup without selecting:
- Close the popup
- Leave the `[[...]]` text as-is (it remains styled with dotted underline)
- The auto-save callback should handle unresolved `[[...]]` gracefully:
  either leave as plain text or strip the brackets

### 5f — Auto-save safety

The auto-save callback (1.5s debounce) must not save files with unresolved
`[[...]]` as wiki syntax. Before saving, scan for `[[...]]` patterns:
- If the content assist popup is still open → defer the save
- If no popup → either strip brackets (save as `some text`) or leave as-is
  (it's just text with brackets)

**Verify**: REPL — type `[[design`, confirm popup opens with filtered results.
Select a result, confirm `[[design]]` is replaced with
`[Editor Commands — Context](wiki:uuid)`. Type `[[new topic`, select
"Create new page", confirm file is created and link is rewritten.

---

## Step 6 — `wiki:` URL resolution in Browser view

Add `wiki:uuid` URL handling to the Browser's `custom-browser` `on-changing`
handler so that `wiki:uuid` links work in view mode.

**File**: `winze-server/src/llm_memory/ui/main_window.clj`

```clojure
(when (str/starts-with? url "wiki:")
  (let [uuid (subs url 5)]
    ;; Look up :wiki/id → get file path + slug → navigate
    ;; Fallback: look up uuid as :file/id → navigate to file
    ;; Fallback: broken link warning
    (resolve-and-navigate-wiki-uuid! uuid)))
```

Also: the Hiccup renderer (`hiccup.clj`) should pass `wiki:uuid` URLs through
to the HTML unchanged (they're handled by the `on-changing` handler, not by
WebKit). No rewriting needed — just ensure the `<a href="wiki:uuid">` is
emitted.

**Verify**: REPL — open a file containing `[text](wiki:uuid)` in view mode,
click the link, confirm navigation to the target file.

---

## Step 7 — Wiki-link CSS in Browser view

Add styling for wiki links in the Browser HTML. Since `[[...]]` is never
persisted, there's no `[[...]]` to style in view mode — only standard
`[text](wiki:uuid)` links. These can optionally be styled differently from
regular links to signal "this is an internal wiki link":

**File**: `winze-server/src/llm_memory/ui/search.clj`

```css
a[href^="wiki:"] { color: #9B6FDF; }
```

This uses a CSS attribute selector to target `<a>` tags whose `href` starts
with `wiki:`.

**Verify**: REPL — render a file with `wiki:uuid` links in view mode, confirm
amethyst color.

---

## Step 8 — Integration testing

1. Create a test markdown file with:
   - Standard links `[text](OTHER-FILE.md)`
   - Wiki UUID links `[Page Title](wiki:a1b2c3d4-...)`
   - External links `[Google](https://google.com)`
2. Open in edit mode:
   - Verify all link types are styled (amethyst for resolved, dotted underline
     for `[[...]]`)
   - Type `[[OPEN-QUESTIONS]]` — confirm popup opens, select a result, confirm
     rewrite to `[Page Title](wiki:uuid)`
   - Type `[[New Topic]]` — confirm "Create new page" option, confirm file
     created with H1, confirm link rewritten
   - MOD1-click a `wiki:uuid` link — confirm navigation
   - MOD1-click an external link — confirm system browser opens
3. Open in view mode:
   - Verify `wiki:uuid` links render and navigate correctly
   - Verify external links work
4. Hover with MOD1 held — verify cursor changes
5. Screenshot-verify both modes

---

## Summary of Changes

| File | Lines (est.) | Nature |
|------|-------------|--------|
| `md_theme.clj` | ~20 modified | Wiki-draft pattern (dotted underline), `:dest` extraction |
| `markdown_editor.clj` | ~120 new | MOD1-click handler, cursor feedback, link-span tracking, `[[` trigger, rewrite logic, file creation |
| `main_window.clj` | ~30 refactored | Extract `open-file-in-tab!`; add `wiki:` URL handling |
| `search.clj` | ~3 new | Wiki-link CSS (`a[href^="wiki:"]`) |
| `content_assist.clj` | ~20 modified | Add "Create new page" option (editor-commands plan owns this file) |

## Risks

- **Content assist dependency**: Steps 5-6 require the content assist popup
  from the editor-commands plan. Steps 1-4 (styling, dest extraction, MOD1-
  click, cursor feedback) can be implemented independently.
- **Auto-save timing**: If the user types `[[text]]` and the popup is still
  open when auto-save fires, the `[[...]]` could be saved. The auto-save
  callback should defer when the popup is active.
- **Filename collisions**: `create-and-link!` must check for existing files
  before creating. If the file exists, offer to link to it instead.
- **Deterministic UUID generation**: `create-and-link!` generates a UUID before
  the watcher indexes the file. The UUID must match what `index-file!` would
  generate. Both should use `UUID/nameUUIDFromBytes` on the same input
  (`file-id + "#" + slug`). Coordinate with the editor-commands plan.
- **Thread safety**: `link-spans` atom is written by `apply-theme!` (UI thread)
  and read by the `MouseListener` (also UI thread) — no concurrency issue.
  File creation in `create-and-link!` happens on the UI thread (fast spit +
  watcher handles indexing asynchronously).
