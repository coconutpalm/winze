---
name: Live spellcheck — context
description: Squiggly underline + right-click suggestions / add-to-dictionary for the Winze Markdown editor
type: context
---

# Live Spellcheck — Context

## Goal

Tracked in
[`Plans/todo/active-issues.md`](../active-issues.md) →
*Editor → "Need a spellchecker!"*.

Add a live spellchecker to the Markdown editor
([`markdown_editor.clj:850`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L850)):

1. **Squiggly red underline** under misspelled words in prose, computed
   incrementally after the user stops typing.
2. **Right-click on a misspelled word** (or caret in a misspelled word
   + `Mod1+Space`) shows a context menu with up to **10 suggestions,
   sorted alphabetically**, a *Add to Dictionary* item, and an
   *Ignore* item. Selecting a suggestion replaces the word in place.
3. **User dictionary** persists across sessions. Adding a word removes
   the underline immediately in every open editor tab.

Scope: the Markdown editor `StyledText` only. The search box, command
palette, find-bar, and other text fields stay untouched — they have
different expectations and short-lived content.

## What to Check (and what to skip)

Spellcheck runs on *prose* regions. It must skip anything that would
produce spurious misspellings on user text that is deliberately
non-prose. Reuse the existing block and inline parsers
([`md_theme.clj:68-144`](../../../winze-server/src/llm_memory/ui/md_theme.clj#L68-L144),
[`md_theme.clj:238-273`](../../../winze-server/src/llm_memory/ui/md_theme.clj#L238-L273))
to classify the document, then extract words only from these span types:

| Included                                     | Excluded                                                 |
|----------------------------------------------|----------------------------------------------------------|
| `:body`                                      | `:code-block` and every `:token/*`                       |
| `:heading/h1` … `:heading/h6` (text portion) | `:heading/*-marker` (`#`, `##` …)                        |
| `:blockquote`                                | `:inline/code` (backtick spans)                          |
| `:inline/bold` / `:italic` / `:bold-italic`  | `:inline/link` destination URL — check only the label    |
| `:bullet-item`, `:numbered-item`             | `:inline/wiki-draft` target — not user prose             |
| `:checkbox-checked`, `:checkbox-unchecked`   | YAML frontmatter structure (future work)                 |

`md-theme/theme` ([`md_theme.clj:360-415`](../../../winze-server/src/llm_memory/ui/md_theme.clj#L360-L415))
already returns non-overlapping, sorted spans covering the full document
— each with a `:type`. The checker filters to *prose-bearing* types,
tokenises each span into words with offsets, and runs each word against
the dictionary.

### Tokenisation rules

- A **word** is `[A-Za-z]+(?:['’][A-Za-z]+)*` — letters plus optional
  inner apostrophes for contractions (`don't`, `it's`, `you're`).
- Words with digits (`v2`, `x86`), a leading underscore, or a
  surrounding `@` are **ignored** — they are identifiers, not prose.
- Single-letter words (`a`, `I`) bypass the dictionary — too noisy.
- ALL-CAPS tokens bypass the dictionary (`HTTP`, `AAO`, `GPU`).
- camelCase / PascalCase tokens are treated as **single tokens** and
  bypass the dictionary too (identifiers, not prose).

These rules keep the underline off code-like content that survives as
prose — e.g. a filename accidentally typed without backticks.

## Rendering: Merging into Existing Theme Spans

`StyledText` allows **one StyleRange per character** — you cannot stack
two StyleRanges over the same offset. Spellcheck therefore cannot be a
parallel style layer; it must be **merged into the theme spans** before
they are handed to `.setStyleRanges`.

The machinery mostly exists — with one required fix:

- `span->style-range`
  ([`markdown_editor.clj:59-74`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L59-L74))
  reads `:underline`, `:underline-style`, `:underline-color` from
  `(get type->style type)` — i.e. a **type-keyed** style map looked
  up by the span's `:type`. The implementation must also read
  per-span overrides (so a span's own `:underline …` fields take
  precedence over the type's defaults). The shipped fix merges
  per-span keys into the type default before building the StyleRange,
  so the squiggly layers on top of whatever font/colour the
  underlying block type provides.
- `:inline/wiki-draft`
  ([`markdown_editor.clj:46-48`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L46-L48))
  already uses `SWT/UNDERLINE_SQUIGGLE` — proof the renderer does
  what we need once `span->style-range` is override-aware.

The new work is a **pure span-merge step**: given

- `spans` — the theme output from `md-theme/theme`
- `misspellings` — a seq of `{:start :length}` covering misspelled words

produce a new seq of theme spans where each misspelling region is
split out and carries `:underline true`, `:underline-style
SWT/UNDERLINE_SQUIGGLE`, `:underline-color <registry :spellcheck-error>`
*in addition to* whatever font / fg / bg the underlying theme span
supplied. This happens inside `apply-theme!`
([`markdown_editor.clj:250-263`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L250-L263))
between `md-theme/theme` and `span->style-range`.

Because the merged spans are still non-overlapping and sorted, the rest
of `apply-theme!` (`setStyleRanges`, code-block line backgrounds, list
rendering, link registry) works unchanged.

## Scheduling & Threading

Spellcheck must not block typing. Current `on e/modify-text`
([`markdown_editor.clj:870-882`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L870-L882))
re-themes synchronously on every keystroke. Three-stage flow:

1. **Synchronous theme** keeps its current role — runs on every
   keystroke so font, colour, and code-block backgrounds update
   instantly. Uses whatever `misspellings` atom currently holds (may be
   stale for the very-latest edits; acceptable, see below).
2. **Debounced spellcheck job** schedules on the shared background
   executor (`res/executor` at
   [`resources.clj:245-250`](../../../winze-server/src/llm_memory/ui/resources.clj#L245-L250))
   ~400 ms after the last keystroke. Tokenises, checks the dictionary,
   and computes the new misspelling set on the background thread — no
   widget access.
3. **Apply on UI thread** — once the job returns, `async-exec!` the
   updated misspelling set into the per-editor atom and call
   `apply-theme!` one more time so the underline appears.

Acceptable staleness: between keystroke and debounce, the underline
may linger under a word the user has just finished correcting. It
disappears on the next debounce cycle. This is standard behaviour in
VS Code and IntelliJ.

## Right-Click Context Menu

No context menu currently exists on the editor `StyledText` — the only
popup-menu precedent in the project is the tray menu in
[`main_window.clj:1137+`](../../../winze-server/src/llm_memory/ui/main_window.clj#L1137)
(`Menu SWT/POP_UP` + `MenuDetectListener`). We introduce the same
pattern for the editor:

- On `e/menu-detected`, read `event.x / event.y` (display coordinates),
  translate to a character offset with
  `StyledText.getOffsetAtPoint(Point)`.
- Look up the misspelling at that offset. If found, **veto** the event
  (`event.doit = false`), build a fresh `Menu SWT/POP_UP` populated
  with suggestions + *Add to Dictionary* + *Ignore*, and
  `menu.setVisible(true)`.
- If the offset is not on a misspelling, let SWT show its default
  context menu (we do not override Cut/Copy/Paste in v1 — we simply
  return, `event.doit = true`).
- Dispose the menu on `menu.addMenuListener` → `menuHidden` to avoid
  leaking native handles.

Menu item selection invokes a pure edit:

- **Suggestion** → `.replaceTextRange(start, length, suggestion)` —
  goes through the existing `on e/modify-text` path, so theming and
  debounced re-spellcheck run automatically.
- **Add to Dictionary** → append the word to the user-dictionary atom,
  persist to disk, broadcast a refresh to every open editor.
- **Ignore** → add the word to the per-session ignores atom (not
  persisted) and broadcast a refresh.

## Dictionary Engine: Pure Clojure on a Wordlist

No Java spellcheck library is currently on the classpath (see
[`winze-server/deps.edn`](../../../winze-server/deps.edn) and
[`clj-llm-memory/deps.edn`](../../../clj-llm-memory/deps.edn) — the
full deps set is: Clojure, CDT, nREPL, logging, Hiccup, CommonMark).
We keep it that way.

**Engine shape:**

1. **Main dictionary** — a plain English wordlist bundled as a
   classpath resource at
   `winze-server/resources/dictionaries/en_US.txt` (one word per line,
   UTF-8). Loaded at startup into a persistent `clojure.core/set` held
   in a `defonce` delay. Lowercase-folded at load time; checks
   lowercase the candidate before lookup so capitalised sentence
   starters (`The`, `Hello`) match.
2. **User dictionary** — plain wordlist at
   `~/.winze/spellcheck/user-dictionary.txt` (one word per line,
   UTF-8, sorted alphabetically), loaded into a `defonce atom` at
   startup. Same case-folding policy. Same on-disk shape as the
   bundled main dictionary — the user can `cat` the two together if
   they ever want to graft in a big list.
3. **Session ignores** — `defonce atom` holding a set. Not persisted.
4. **Suggestions** — linear scan of the main dictionary with a
   ceiling-aware Damerau–Levenshtein edit distance capped at 2. The
   ceiling lets the distance function reject most words in O(1) via a
   length-delta guard, so a full 370 000-word scan completes in under
   100 ms — acceptable for a right-click interaction. When more than
   10 hits survive, we trim to the 10 closest by distance, then sort
   alphabetically for the final menu.

**Why not a SymSpell deletes-index.** The original proposal called
for one; benchmarking on the dwyl wordlist showed the index ballooned
to ~11 million entries and ~2 GB of heap for sub-millisecond lookups.
The linear scan hits every query in ≤ 100 ms at zero heap cost —
dramatically simpler and well within the UX budget for a
click-triggered popup. A SymSpell-style index can be reintroduced
later if the scan ever becomes the bottleneck.

**Why not LanguageTool / Hunspell JNA bindings:** LanguageTool adds
~30–50 MB to the uberjar and licenses as LGPL; Hunspell JNA wrappers
require deploying the native library on every target machine. Both
options can be dropped in later behind the same `spellcheck/check`
surface if grammar checking or affix-aware dictionaries are wanted.

**Wordlist source.** We vendor
[`dwyl/english-words`](https://github.com/dwyl/english-words) — the
`words_alpha.txt` list (~370 000 modern English words including
plurals / inflections, Unlicense / public-domain dedication). It is
lowercase-folded, letters-only-filtered (`^[a-z]+$`), deduplicated,
and sorted at build time.

**Why not `/usr/share/dict/words`.** The macOS default ships the
1934 `web2` list, which is missing modern common words like "has",
"words", and "inline" — using it produces a storm of false
positives on any normal prose.

## User Dictionary: Storage & Lifecycle

Mirrors the `~/.winze/keybindings/` precedent
([`main_window.clj:1054-1062`](../../../winze-server/src/llm_memory/ui/main_window.clj#L1054-L1062))
for the directory placement; the *file format* follows the bundled
wordlist rather than the `.keybinding` EDN format because a word list
is a degenerate case where EDN is pure ceremony.

- **Path**: `(io/file (System/getProperty "user.home") ".winze"
  "spellcheck" "user-dictionary.txt")`
- **Format**: one word per line, UTF-8, sorted alphabetically, no
  frontmatter, trailing newline. Example contents:

  ```
  clj-oci
  foo
  coconutpalm
  ```

- **Load**: lazily on first spellcheck run. Missing file → empty set.
  Blank lines and surrounding whitespace are trimmed and ignored;
  lines are lowercase-folded on load.
- **Add word**: `swap!` the atom to conj the lowercased word, then
  write the whole set back to disk — sorted — atomically (write to
  `<path>.tmp`, `renameTo` the real path). The sort is cheap
  (user dictionaries stay in the low hundreds of entries) and makes
  the file diff-friendly for users who track it in dotfiles.
- **Do NOT** use Datalevin or EDN — the data is a bag of strings;
  a sorted text file is the simplest thing that survives a user
  opening it in their editor and bulk-editing.

## Multi-Editor Coordination

Every open editor tab has its own `StyledText` and its own
`on e/modify-text` listener. The main and user dictionaries are
**global** atoms; the misspelling set is **per-editor**.

- Dictionary changes (add-to-dictionary, add-to-ignore) must trigger a
  re-theme on *every* open editor. Maintain an atom watcher on the
  user-dictionary atom and the session-ignores atom that fans the
  refresh out. The list of open editors is already tracked in
  `resources.clj:264` (`open-files`); `(mw/element ...)` can retrieve
  each one.
- A small `refresh-all-editors!` helper — walks the open-editors
  registry, calls `async-exec!` with `apply-theme!` on each.

## Theme Integration

Add one colour to the registry
([`resources.clj:229-239`](../../../winze-server/src/llm_memory/ui/resources.clj#L229-L239)):

```clojure
(defonce color-spellcheck-error
  (delay (ui (Color. @display 0xE5 0x48 0x4A))))   ; accessible red on mine-shaft
```

Disposal is automatic — `dispose-registry!`
([`resources.clj:445-462`](../../../winze-server/src/llm_memory/ui/resources.clj#L445-L462))
reflectively disposes every realised `defonce` delay that holds a
`Resource`.

Do not add a new `:inline/spellcheck-error` key to `type->style`. The
merge step instead *augments* whichever type the underlying span
already carries, so `:underline true / :underline-style … /
:underline-color @res/color-spellcheck-error` is attached to, say, a
`:body` span or an `:inline/bold` span — preserving the original font
and colour underneath.

## Commands & Keybindings

Three scoped commands registered in
[`editor_commands.clj`](../../../winze-server/src/llm_memory/ui/editor_commands.clj)
(scope `:editor`), following the `:editor/*` pattern already used for
toggle-bold etc.:

| Id                               | Label                      | Default chord               |
|----------------------------------|----------------------------|-----------------------------|
| `:editor/spellcheck-suggest`     | Show spellcheck suggestions | `Mod1+Space` (⌘ Space / Ctrl+Space) |
| `:editor/spellcheck-add-word`    | Add word to dictionary     | *(context menu only in v1)* |
| `:editor/spellcheck-ignore-word` | Ignore word this session   | *(context menu only in v1)* |

`:editor/spellcheck-suggest` is the keyboard equivalent of a
right-click on a misspelling. It opens the same suggestion menu —
anchored to the screen position of the caret, not the mouse — when the
caret is inside a misspelled word. If the caret is not inside a
misspelling, the command is a no-op (no beep, no toast). The
menu-building logic in `spellcheck_menu.clj` is factored so both the
mouse (`MenuDetectListener`) and keyboard (`:editor/spellcheck-suggest`)
paths reuse it; only the anchor point differs.

The add / ignore commands take the word under the caret (or clicked
position). The context-menu handler passes the word and range
directly. They have no default chord in v1 — users can bind one from
the command palette if desired. The commands exist so that the palette
and future chord definitions can use them.

Default keybinding, added to
[`resources/keybindings/editor.keybinding`](../../../winze-server/resources/keybindings/editor.keybinding):

```clojure
{:key :space :mod #{:mod1} :when {:in :editor}
 :command :editor/spellcheck-suggest
 :comment "Show spellcheck suggestions at caret"}
```

The notation mirrors the existing editor bindings — `:mod #{:mod1}`
resolves to Cmd on macOS and Ctrl on Linux/Windows
([`keybindings.clj:116-123`](../../../winze-server/src/llm_memory/ui/keybindings.clj#L116-L123)),
and `:space` is already registered in the special-key table at
[`keybindings.clj:89-101`](../../../winze-server/src/llm_memory/ui/keybindings.clj#L89-L101).

## Performance Budget

Typical Plans markdown is < 5 KB, < 1 000 words. Tokenise + 1 000 set
lookups is sub-millisecond on a modern JVM. Even a 100 KB document is
< 20 ms for the check. Suggestions are only computed lazily on
right-click, so they never appear on the hot path.

The main dictionary load (~100 000 words) runs once at first spellcheck
invocation. Budget: < 200 ms cold, including deletes-index build. Load
on the background executor so the first debounce job absorbs it; the
UI is never blocked.

## Design Constraints & Decisions

1. **No inline auto-correct.** V1 only shows the underline + menu.
   Inline "typed a wrong word → silently fixed" is out of scope.
2. **Language is English-only.** The dictionary file path is
   parameterised so a language picker can be added later, but no
   per-document language detection.
3. **No run-time dictionary download.** The wordlist is vendored in
   `resources/dictionaries/`; no network fetches at any time.
4. **Purely pre-rendered underline.** We do not draw squigglies via
   `GC` in a `PaintObjectListener`. SWT's built-in
   `SWT/UNDERLINE_SQUIGGLE` is already in use and renders correctly on
   macOS, Linux, and Windows.
5. **Resource scope: editor only.** The install hook adds a
   `MenuDetectListener` and hooks into the existing `on e/modify-text`
   debounce — every other widget is untouched.
6. **Tests: pure functions get RCF coverage.** Tokenisation, the
   span-merge step, the SymSpell deletes lookup, and the edit-result
   produced by a "replace with suggestion" action are unit-testable
   without a `Display`. SWT glue is thin and verified by
   screenshot + `Display.post` synthetic events.

## Related Prior Work

- Squiggly underline rendering, proof of concept:
  [`markdown_editor.clj:46-48`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L46-L48)
  + [`markdown_editor.clj:59-74`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L59-L74).
- Theme pipeline (where the merge hooks in):
  [`markdown_editor.clj:250-263`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L250-L263).
- Debounced save precedent (same executor, same cancellable `ScheduledFuture` pattern):
  [`markdown_editor.clj:870-882`](../../../winze-server/src/llm_memory/ui/markdown_editor.clj#L870-L882).
- User-config-under-home precedent (`~/.winze/keybindings/`):
  [`main_window.clj:1054-1062`](../../../winze-server/src/llm_memory/ui/main_window.clj#L1054-L1062).
- Pop-up menu precedent (tray menu):
  [`main_window.clj:1137+`](../../../winze-server/src/llm_memory/ui/main_window.clj#L1137).
- Command registration pattern:
  [`editor_commands.clj:574-789`](../../../winze-server/src/llm_memory/ui/editor_commands.clj#L574-L789).
- Colour registry + disposal:
  [`resources.clj:229-239`](../../../winze-server/src/llm_memory/ui/resources.clj#L229-L239),
  [`resources.clj:445-462`](../../../winze-server/src/llm_memory/ui/resources.clj#L445-L462).
- Analogous editor-enhancement plan and test style:
  [`Plans/todo/delimiter-pair-wrap/`](../delimiter-pair-wrap/).
- SWT threading & resource rules:
  [`Plans/SWT-UI-GUIDE.md`](../../SWT-UI-GUIDE.md).

## Success Criteria

- Typing a misspelled word in prose produces a red squiggly underline
  within ~500 ms of stopping typing. Typing inside a fenced code
  block, inline code span, or link URL produces no underline.
- Right-clicking a misspelled word opens a menu containing up to 10
  suggestions in alphabetical order, a separator, *Add to Dictionary*,
  and *Ignore*.
  Selecting a suggestion replaces the word in place and clears the
  underline. Selecting *Add to Dictionary* clears the underline in
  every open editor and persists across restart. Selecting *Ignore*
  clears the underline in every open editor for the current session.
- Right-clicking a correctly spelled word does **not** veto the
  default context menu (if/when one exists).
- Pressing `Mod1+Space` with the caret inside a misspelled word opens
  the same suggestion menu anchored just below the caret position.
  Pressing `Mod1+Space` with the caret outside any misspelling is a
  silent no-op.
- The bundled English dictionary loads in < 200 ms and uses < 10 MB
  heap. The uberjar grows by at most the size of the wordlist file
  (~1 MB for SCOWL-60).
- Screenshot-verified squiggly rendering on macOS at 1× and 2× DPI.
- RCF tests pass for the pure-Clojure tokeniser, span-merge, and
  SymSpell suggestion functions.
