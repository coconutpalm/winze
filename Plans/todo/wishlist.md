---
tags: [active, todo]
---
# Winze Wishlist

## Known Bugs

* Occasionally when typing fast, a refresh after save isn't ignored and the cursor or viewer gets moved to position 0,0 after save.  (Race condition?  Dirty flag isn't considered?)
* Similarly, occasionally when switching back to the viewer, the viewer is scrolled back to the top (the editor's scroll position isn't preserved).  This seems to happen only after edits.
* Place the cursor at the beginning of the initial bullet in a bullet list and press `<enter>`.  Instead of an empty bullet on the line above, you get an empty line followed by the current  line with an additional bullet inserted at its beginning.
* Insert a link this way:
  * In a file viewer, right-click the file name link at the top of the page and click Copy
  * Paste it into a link form `[text](paste url here)`
  * Place the cursor after the `=` in the `?path=` part of the URL.  *Backspace until Winze crashes.*
* Some (mainly editor) keybindings don't have a scope attached.  Audit and fix.

---

## Desired Features

### Navigation & File Discovery

* **Filterable file tree popup** — open a file when you know its name or where it's located.  Fuzzy match on name or path fragment.
* **`[[wiki link]]` syntax for not-yet-created files** — clicking a `[[new file name]]` link creates the file and rewrites all instances of that link (across all files in the same root)  to the resolved `[new file name](wiki:path/to/new_file_name.md)` syntax.
  * The link can optionally be prefixed with a target directory:
    `[[todo:new task name]]` — places the new file under `todo/` when created.
  * Resolution scope: the root of the file the link appears in.
* **`#tags` clickable inline** — Ctrl-click in the editor; plain click in the viewer.  Jumps to live search filtered by that tag.  Exact-match via the tag index, optionally  blended with semantic search.  Before implementing, read the existing search grammar and tag index code to understand the current semantics.

### Search & Exploration

* **Paged search results** — load more results in the live search pane.  This is a UI-choice limit (not a performance constraint); a modest increase in default page size plus a "Load more" control would both help.
* **Status bar** — notifies of file watcher events with a progress meter showing (re)indexing progress.
* **Recent updates** — surface recently modified files, exposed in the sidebar.

### Tab & Pane Layout

* **File-group sub-tabs** — when a file is part of an inferred metadata group, the top-level tab displays all files in that group as sub-tabs across the bottom (like Excel worksheets). The existing metadata inference system already knows the group from path/filename conventions and can drive this without a new concept.  The top-level tab identity is the group; the active sub-tab is the most recently viewed file in that group.
* **Split pane / side-by-side view** — open two documents simultaneously.  There is significant mileage to be gained from creative use of split panes, e.g. CONTEXT.md and PLAN.md for the same work item side by side.
* **Drag-to-reorder and drag-to-split** *(deferred — significant rework of `main_window.clj`)*
* **Maximize / restore CTabFolder** — keybindings for maximize/restore; reinstate maximize/restore buttons on the CTabFolder when the sidebar is present.

### Sidebar

Permanent sidebar with collapsible/hidable shelves, each showing a different kind of content.  Individual shelves can be expanded or collapsed independently.

* **Roots shelf** — list registered roots; add/remove root; create new root (generates standard directory structure).  Supply a default root at `~/.winze/wiki`.
* **Inbound links shelf** — files that link to the currently open file.
* **Starred / favorites shelf** — files tagged `#favorite` in their YAML header.
* **Recent updates shelf** — recently modified files (replaces the standalone "expose recent updates" item).

### Workspace & Session Management

* **Save and restore open tabs** — per-root state.  When a root becomes active, all saved tabs for that root reopen automatically.
* **UI to add / remove roots** — exposed in the Roots shelf (see Sidebar).
* **Shift/Cmd+p command palette** — opens filtered to commands in the current keybinding scope.  Pressing again cycles to show all commands across all scopes.  Useful for discoverability.

### Content & Authoring

* **Star / favorite a file** — written into the file's YAML header as a `#favorite` tag. The existing tag search machinery then automatically finds all favorited files without additional infrastructure.
* **Snippet / template insertion** — palette command to insert common structures:  front-matter block, Jira link, wiki-link syntax, standard section headers.  Particularly useful given that files follow strict naming and frontmatter conventions.

### UI & Presentation

* **UX Modernization** — Should we introduce extra whitespace around the content areas inside the tabs like most modern / web apps do?
* **Focus / distraction-free mode** — a single command (keybinding + palette entry) that animated-hides the sidebar and the header/search bar, leaving only the CTabFolder visible.  Toggling it again restores the full layout.
* **Background graphic under the header** — optional cosmetic.  Consider whether the header should be maximally minimized (just the Search box, no logo) to reclaim vertical space for the CTabFolder; this is an open question.

---

## Emergent Features Worth Considering

These are not yet on the backlog but are natural extensions of the current architecture.

* **Document graph view** — a 2D node-link diagram of how files reference each other via markdown links and wiki links.  Particularly useful once `[[wiki link]]` syntax is in,  since the graph then visualizes the full knowledge structure of a root.

* **Outline / TOC panel** — for long documents, a collapsible heading tree in the sidebar (or a dedicated sub-pane).  Click any heading to jump directly to it.  Most Plans docs are long enough that this would replace repeated scrolling.

* **Heading anchor navigation** — `[text](path.md#heading)` links that scroll the viewer to the named heading.  Your plans docs already use this convention in cross-references; making them work in the viewer would complete the navigation loop.

* **Link health / broken link detection** — a badge or indicator (in the tab, or inline in the viewer) when a markdown or wiki link target does not exist.  Could also appear as a sidebar shelf showing all broken links across a root.  Complements the wiki-link creation flow: a broken link is exactly a `[[wiki link]]` that hasn't been created yet.

* **Multi-file link update on rename** — when a file is renamed or moved, find all markdown and wiki links in the root that point to the old path and rewrite them to the new path.  This is the inverse of link health: instead of flagging broken links after a rename, it prevents them from occurring.

* **Git-backed diff / history view** — a tab pane or sidebar shelf that shows what changed in the current file across commits.  Since multi-user sync is already via Git, the history is always present; surfacing it in Winze would make reviewing AI-authored changes or tracking plan evolution much faster.

* **Low-power mode** — Lower the embedding thread priority when on laptop battery power.  See: https://github.com/Hakky54/senzu

---

## Code Hygiene

* `main_window.clj` is getting large and cluttered.  Suggest ways to refactor / split this.
  * In particular, examine the UI code and see if there are ways to modularize things.  The file-group sub-tab feature (see Tab & Pane Layout) requires a new tab type; the current architecture should be audited to understand how easily new tab types can be introduced before that work begins.

---

## Open Questions

* Should the header be reduced to just the Search box, dropping the logo?  This reclaims meaningful vertical space for the CTabFolder area.
* Icon drawing API: a sandboxed Clojure file that MUST define an `(image [gc])` macro evaluated inside a `(doto-gc ...)` block.  What constraints apply to that sandbox?
