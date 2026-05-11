---
related: [live-search, tag-search]
tags: [ui, search, multi-root]
---

# Search Scope Selector — Context

## Problem

The GUI live search (`search.clj`) always searches across all registered roots.
`core/search` supports `:root-uri` scoping, and the MCP `search_plans` tool exposes it,
but the GUI has no way to narrow scope. As more project roots are registered, users need
control over which roots are included in search results.

## Current Architecture

### Search Data Flow

```
main_window.clj  header/text (SWT/SEARCH)
       │  on modify-text
       ▼
search.clj  results(query-string, browser-widget)
       │  debounce 300ms → executor thread
       ▼
core/search(store, query, {:top 10 :dedupe true})
       │  ← no :root-uri passed
       ▼
store/datalevin  HNSW vector search → post-filter
```

### Root Data Model

Roots are stored as Datalevin entities with `:root/uri`, `:root/name`, `:root/plans-dir`.
`core/list-roots` returns `[{:eid N :root/uri "file:///..." :root/name "name" :root/plans-dir "Plans"}]`.

### core/search :root-uri Behaviour

- `nil` → search all roots (current GUI behaviour)
- `"file:///path"` → scope to single root

There is no built-in multi-root filter (`:root-uri` accepts a single string, not a set).
Multi-root scoping requires either multiple searches or a post-filter in `search.clj`.

## Design

### Scope States

Three distinct states:

1. **All roots** (default) — `:root-uri nil` passed to `core/search`
2. **Single root** — `:root-uri "file:///..."` passed directly
3. **Multiple roots (subset)** — post-filter results by root URI set

### UI Element: SWT Link Widget

A `link` widget placed above the search `text` widget in the header composite.
SWT Link renders mixed plain text + clickable `<a href="...">` regions.
Selection events fire with `event.text` set to the `href` value.

**Display formats by state:**

| State | Link text |
|-------|-----------|
| All roots (1 registered) | `Scope: projectName` (plain, no links — nothing to change) |
| All roots (2+ registered) | `Scope: <a>All roots</a>` |
| Single root | `Scope: <a>rootName</a>` |
| Multiple roots (subset) | `Scope: <a>N roots</a>` |

### Scope Popup with Checkboxes

Clicking or pressing Enter on the Link opens a **borderless popup Shell** with a checkbox
per registered root. SWT has no native hover/popover widget, so we simulate one with
`Shell(SWT.NO_TRIM | SWT.ON_TOP | SWT.TOOL)`.

The popup also appears on **mouse hover** over the Link widget.

**Popup contents:**

- One `Button(SWT.CHECK)` per registered root, labelled with `:root/name`
- Checked state reflects the current scope (all checked = "All roots")
- Changes are **immediate** — toggling a checkbox updates the scope atom,
  refreshes the Link text, and re-triggers the live search

**Popup lifecycle — two opening modes with different focus behaviour:**

| Trigger | Focus behaviour | Dismiss |
|---------|----------------|---------|
| Mouse hover | Popup does NOT steal focus (search field keeps it) | Mouse leaves Link + popup |
| Click on Link | Popup receives focus (first checkbox focused) | Escape, click outside, mouse leave |
| Enter on Link | Popup receives focus (first checkbox focused) | Escape, click outside, mouse leave |

- When the popup has focus, arrow keys navigate between checkboxes, Space toggles.
- `SWT.TOOL` flag keeps the popup out of the taskbar on all platforms.
- Track mouse position with `Display.addFilter(SWT.MouseMove, ...)` to detect
  when the cursor leaves both the link and the popup.

**Accessibility:**

- A label at the bottom of the popup reads "Press Esc to close" — visible to sighted
  users and announced by screen readers.
- Checkboxes have accessible names via their `:root/name` text.

**Scope derivation from checkboxes:**

| Checkboxes checked | Scope state |
|--------------------|-------------|
| All | `{:mode :all}` |
| One | `{:mode :single :root-uri "..."}` |
| Multiple (not all) | `{:mode :multi :root-uris #{"..." "..."}}` |
| None | Treated as all (can't search nothing) |

### Search Integration

`search.clj/results` gains a `scope` parameter (an atom or dereffable) holding the
current scope state: `{:mode :all}`, `{:mode :single :root-uri "..."}`, or
`{:mode :multi :root-uris #{"..." "..."}}`.

For `:mode :all` and `:mode :single`, pass `:root-uri` directly to `core/search`.
For `:mode :multi`, pass `:root-uri nil` and post-filter results by the URI set.

## SWT Link Widget Reference

```java
// Text with clickable regions — <a> tags
link.setText("Scope: <a href=\"all\">All roots</a>");

// Selection event carries href in event.text
link.addListener(SWT.Selection, event -> {
    System.out.println("Clicked: " + event.text);  // "all"
});
```

In CDT:
```clojure
(link (id! :ui/scope-link)
      :text "Scope: <a>All roots</a>"
      (on e/widget-selected [props parent event]
          (show-scope-popup!))
      (on e/widget-default-selected [props parent event]
          (show-scope-popup!)))
```

## Borderless Shell Popup Reference

```clojure
;; Create a checkbox popup below the link
(let [popup (Shell. (.getShell (element :scope-link))
                    (| SWT/NO_TRIM SWT/ON_TOP SWT/TOOL))]
  (.setLayout popup (GridLayout. 1 false))
  (.setBackground popup obsidian-color)
  ;; Add a checkbox per root
  (doseq [root roots]
    (doto (Button. popup SWT/CHECK)
      (.setText (:root/name root))
      (.setSelection (root-selected? root))
      (.addListener SWT/Selection
        (reify Listener
          (handleEvent [_ _]
            (update-scope-from-checkboxes!)
            (refresh-scope-link!)
            (re-trigger-search!))))))
  ;; Position below the link widget
  (let [loc (.toDisplay (element :scope-link)
                        (Point. 0 (.-y (.getSize (element :scope-link)))))]
    (.setLocation popup loc))
  (.pack popup)
  (.setVisible popup true))
```

## Files to Modify

| File | Change |
|------|--------|
| `winze-server/src/llm_memory/ui/main_window.clj` | Add scope Link + hover popup to `header`, wire scope atom |
| `winze-server/src/llm_memory/ui/search.clj` | Accept scope parameter, apply root filtering |

## Constraints

- All scope state is UI-local (atom in `main_window.clj`) — no persistence needed
- Default is always "All roots" on startup
- Must work with 0 roots (show "No roots registered"), 1 root, and N roots
- Popup must not steal focus from search field (SWT/TOOL flag prevents this)
- Popup must track mouse leaving both link and popup (use Display filter)
- Checkbox changes take effect immediately (no OK/Cancel buttons)
- Unchecking all checkboxes resets to "All roots" (can't search nothing)
