---
created: 2026-04-23
tags: [onboarding, welcome, ui, first-run, roots, menu]
---

# First-Run Onboarding — Context

## Goal

Replace the silent "Type to search…" empty state shown on first launch with a
**Welcome tab** — a persistent, on-demand surface that lets a GUI user:

1. Understand what Winze is and how it's meant to be used.
2. Register their first root (pick a folder of markdown docs) **without
   touching a CLI**, nREPL, or MCP skill.
3. Optionally load a bundled sample knowledge base to explore the product
   with real content before committing their own.

The Welcome tab is always reachable from the **tray popup menu**, so users
who later want to revisit the onboarding actions — e.g. register a second
root via the UI, or reinstall the sample KB — can always get back to it.
On first launch with zero roots registered, the Welcome tab opens
automatically and is front-most. After that, it stays closed unless the
user opens it explicitly.

The tool is already strong once MCP has registered roots. The gap is entirely
in the initial UI surface: a GUI user launching the `.app` today sees an
empty search window with no visible path forward.

## Current State

### What first launch shows

When no roots are registered, the single Live Search tab renders `empty-page`
from [search.clj:341-359](winze-server/src/llm_memory/ui/search.clj#L341-L359)
(line numbers stable as of 2026-04-23):

- The only visible text is the centered placeholder **"Type to search…"**.
- The hint block `[:div] "Create a home page at: ..."` is guarded by
  `(when (seq paths))`, so with zero roots it never appears.
- There is no button, menu item, dialog, preference, or documentation link
  that tells the user how to register a root.

### How roots are actually registered today

- **MCP tool** `register_plans` (from Claude Code) — invisible to a GUI user.
- **CLI skill** `/register-plans` — invisible to a GUI user.
- **Direct nREPL**: `(llm-memory.core/register-root! store {...})`.
- **Backup restore**: `~/.local/share/winze/roots.edn` is read at startup by
  `read-roots-config` at [server/main.clj:65-74](winze-server/src/llm_memory/server/main.clj#L65-L74),
  called from `sync-roots-from-config!` at
  [server/main.clj:87-101](winze-server/src/llm_memory/server/main.clj#L87-L101).

No existing UI affordance calls `register-root!`.

### Existing menus

- **Tray popup menu** —
  [main_window.clj:1293-1306](winze-server/src/llm_memory/ui/main_window.clj#L1293-L1306) —
  items: `Toggle visibility`, `About…`, (separator), `Quit`. Registered under
  `(id! :ui/tray-menu)`; shown via `on e/menu-detected` from the tray item at
  [main_window.clj:1261-1266](winze-server/src/llm_memory/ui/main_window.clj#L1261-L1266).
- **macOS system menu hook** —
  [main_window.clj:835-849](winze-server/src/llm_memory/ui/main_window.clj#L835-L849) —
  `register-about-handler!` hooks the default macOS app menu's "About Winze"
  item by matching `SWT/ID_ABOUT`.
- **No `Menu SWT/BAR` is attached to the shell today** — custom top-level
  menu items therefore don't appear in the macOS screen menu bar. Adding
  one is deferred (see Non-Goals); the tray menu is the single entry
  point for this plan.

### Relevant code locations

- **Empty page / home-page dispatch**:
  [search.clj:341-359](winze-server/src/llm_memory/ui/search.clj#L341-L359) —
  `empty-page`, already branches on `(seq paths)`.
- **Live Search tab init**:
  [main_window.clj:800-802](winze-server/src/llm_memory/ui/main_window.clj#L800-L802) —
  `(ctab-item "Live search" :image @statusbar-icon …)`. Home page loads
  asynchronously at [main_window.clj:1313-1318](winze-server/src/llm_memory/ui/main_window.clj#L1313-L1318).
- **`open-tab!` machinery**:
  [main_window.clj:224-280](winze-server/src/llm_memory/ui/main_window.clj#L224-L280) —
  creates closable file-browser tabs with icon + title + Browser/StyledText
  toggle. This is the mechanism the Welcome tab will reuse.
- **URL dispatch (`on e/changing` handler inside `custom-browser`)**:
  [main_window.clj:157-186](winze-server/src/llm_memory/ui/main_window.clj#L157-L186) —
  handles `winze:open-file?root=…&path=…` and `winze:search?q=…` pseudo-URLs
  by intercepting `Browser` navigations. This is the mechanism the Welcome
  page buttons will extend.
- **Tray popup definition**:
  [main_window.clj:1293-1306](winze-server/src/llm_memory/ui/main_window.clj#L1293-L1306).
- **Roots API**:
  [core.clj](clj-llm-memory/src/llm_memory/core.clj) — `list-roots`,
  `register-root!`, `remove-root!`, `add-root-listener!`.
- **Pre-existing stubs (already in `main_window.clj`)**:
  - `refresh-welcome-tab!` at line 423 — empty no-op stub; docstring says
    "Body is filled in during onboarding Step 5". Do not re-add this function;
    fill in its body.
  - `on-root-changed` at line 442 — already calls `refresh-welcome-tab!` and
    `refresh-live-search!`. Already wired to `core/add-root-listener!` in
    `defmain` at line 1311. No changes needed here.
- **Commented-out sidebar**:
  [main_window.clj:1276-1280](winze-server/src/llm_memory/ui/main_window.clj#L1276-L1280) —
  a `SashForm` sidebar was scaffolded but disabled. Out of scope for this
  plan.
- **Classpath resource pattern (JAR-safe)**: `highlight/loader.clj` — the
  reference pattern for enumerating resources that works under both `file:`
  and `jar:` URLs. The sample-KB installer must follow this pattern, per the
  CLAUDE.md rule "never use `(io/file (io/resource "path"))`".
- **Wishlist pointers**: [Plans/todo/wishlist.md](Plans/todo/wishlist.md) —
  "UI to add or remove roots" overlaps with this work.

## Behavior Specification

### The Welcome tab

A dedicated tab, managed through the existing `open-tab!` infrastructure, in
`:synthetic` content-mode (Cmd+E suppressed). Distinguishing properties:

- **Tab title**: `"Welcome"`.
- **Icon**: reuses `@statusbar-icon` (same visual treatment as Live Search).
- **Closable**: yes — same `SWT/CLOSE` affordance as file tabs. Closing it
  doesn't unregister any roots or undo anything; it just hides the tab.
- **Identity**: only one Welcome tab exists at a time. Tracked via a known
  key (e.g. `:ui/welcome-tab`) on `app-props` or a dedicated atom. If the
  user closes it, the key is cleared. If `open-welcome-tab!` is invoked
  while the tab exists, it selects the existing tab rather than opening a
  duplicate.

### Welcome page content

Rendered inside the Welcome tab's Browser. Hiccup structure:

1. **Header** — Winze wordmark and a one-sentence tagline
   ("Nothing forgotten. Everything found.").
2. **Intro paragraph** — 2–3 sentences: "Winze indexes your markdown
   planning documents and makes them searchable by meaning, not just
   keywords. Point it at a folder to get started."
3. **Primary action** — prominent styled link **Add a folder…** →
   `winze:register-root`. Intercepted via `LocationListener`; opens a
   native SWT `DirectoryDialog`; calls `core/register-root!` with a
   reasonable default `{:plans-dir "Plans" :name <leaf-dir-name>}`.
4. **Secondary action** — styled link **Try the sample knowledge base** →
   `winze:install-sample-kb`. Unpacks a bundled resource tree to the OS's
   user-data directory and registers it as a root named `"Sample"`.
   Idempotent: if already installed, re-register if needed and no-op the
   unpack.
5. **Registered-roots summary** — if any roots are already registered, a
   small section: "**Folders you've added:** _Project A_, _Project B_…".
   This gives the on-demand Welcome-tab user (post-first-run) confirmation
   of current state and a hint that Add-a-folder adds another root rather
   than replacing the current one.
6. **Advanced footnote** — small muted text: "Or run `/register-plans`
   from Claude Code."

### When the Welcome tab opens

- **First launch with zero roots** — the Welcome tab is opened
  automatically after the shell is shown, and is brought to front (becomes
  the selected tab, ahead of Live Search).
- **Tray menu click** — "Open Welcome Page" → `open-welcome-tab!`.
  Idempotent: focus if already open; create if not.
- **Programmatic/REPL** — `(main-window/open-welcome-tab!)` — the same
  helper, callable from the REPL for testing.

### What Live Search shows

Unchanged from today's `empty-page` / `home-page` dispatch. The Welcome
page does **not** replace Live Search content. One minor tweak: when zero
roots are registered, extend `empty-page` to include a small line
"_No folders registered — see the Welcome tab._" below "Type to search…"
so a user who closed the Welcome tab can re-open it by reading the tip
and using the menu. This is a two-line change, not a redesign.

### Registration flow (from the Welcome tab)

`winze:register-root` handler:

1. Intercept the navigation, `event.setDoit(false)`.
2. Open `DirectoryDialog` on the UI thread (`sync-exec!` since we need the
   return value). Parent shell = `(element :main-window)`.
3. If the user cancels, no-op.
4. If confirmed, derive args via `derive-root-args` (pure helper):
   - Leaf name → `:name`.
   - If leaf is named `Plans` → `:plans-dir ""`.
   - Else if `Plans/` subdirectory exists → `:plans-dir "Plans"`.
   - Else → `:plans-dir ""`.
   - `:uri` → `"file://" + absolute path`.
5. Call `core/register-root!` to write the root to the Datalevin store.
6. Call `server/write-roots-config!` to persist the root to `roots.edn`
   so it survives an app restart.
7. On a background future (to keep the UI thread responsive), call
   `index/reconcile!` to index existing files in the root, then
   `watcher/start-watcher!` to monitor future file changes. All three
   namespaces (`server`, `index`, `watcher`) are already required in
   `main_window.clj` — no new `:require` needed.
8. Refresh the Welcome tab (re-render the welcome page so the "Folders
   you've added" section updates) and refresh the Live Search tab (via the
   existing home-page re-evaluation hook).

### Sample-KB flow

`winze:install-sample-kb` handler:

1. Intercept, `event.setDoit(false)`.
2. Push a transient "Installing sample knowledge base…" overlay / status
   message into the Welcome tab.
3. On a background future, call `sample-kb/install!` which:
   - Resolves the target directory (`~/Library/Application Support/Winze/sample-kb/`
     on macOS, `$XDG_DATA_HOME/winze/sample-kb/` otherwise, falling back
     to `~/.local/share/winze/sample-kb/`).
   - If the directory exists and is non-empty, skip the unpack.
   - Otherwise enumerate `resources/sample-kb/` via the JAR-safe pattern
     from `highlight/loader.clj`, copy each resource via `io/reader` →
     `io/writer`.
4. Register the directory as a root named `"Sample"` with `:plans-dir ""`.
   If that URI is already registered, skip registration.
5. Refresh the Welcome tab and Live Search tab.

### Closing and reopening

- Closing the Welcome tab via its `X` button does not ask for
  confirmation. State is discarded (`app-props` key cleared); reopening
  rebuilds it from scratch.
- Quitting and relaunching Winze does **not** re-auto-open the Welcome
  tab if any roots are registered. It only auto-opens on a truly empty
  state (zero roots).

## Constraints

- **SWT threading**: `DirectoryDialog` must be opened on the UI thread.
  The `winze:register-root` handler dispatches via `(async-exec!
  register-root-via-dialog!)`, so the dialog body is already on the UI
  thread — call `(.open dialog)` directly, no `sync-exec!` needed.
  `open-welcome-tab!` called from the REPL must wrap its body in
  `async-exec!`. See `Plans/SWT-UI-GUIDE.md`.
- **Tab identity via `app-props`**: the open Welcome tab's `CTabItem` must
  be tracked so `open-welcome-tab!` is idempotent. Use the existing
  `id!`/`element` pattern (`(id! :ui/welcome-tab)`) and clear the key in
  the tab's `SWT/Dispose` listener. **Important**: `element` in
  `resources.clj` calls `(keyword "ui" (name ui-kw))`, which always strips
  the namespace from its argument, so `(element :welcome-tab)` and
  `(element :ui/welcome-tab)` both look up `:ui/welcome-tab`. Use the
  unqualified form `(element :welcome-tab)` by convention throughout.
- **JAR-safe resources**: Follow the `highlight/loader.clj` pattern. Never
  call `(io/file (io/resource "sample-kb"))` — it throws inside an
  uberjar.
- **Content-mode flag**: the Welcome tab is `:synthetic` content; Cmd+E is
  suppressed (the content-mode flag from the home-page work already
  enforces this).
- **Idempotency**:
  - `open-welcome-tab!` → focus existing tab, don't open a duplicate.
  - `winze:install-sample-kb` → if sample-KB directory already populated
    and registered, short-circuit.
  - `winze:register-root` selecting a folder that's already registered
    → show a brief "already registered" message rather than failing.
- **LocationListener must `event.setDoit(false)`** for all `winze:`
  pseudo-URLs.
- **Errors are visible**: if `register-root!` or `install!` fails, render
  an error message into the Welcome tab — do not throw silently.

## Non-Goals

- **macOS application menu bar / `Menu SWT/BAR` on the shell**. Deferred.
  The tray menu is the single GUI entry point for "Open Welcome Page"
  in this plan. Adding a shell menu bar is a worthwhile follow-up —
  it gives future top-level menus (File / Edit / View / Help) a place
  to live — but it's not required to unblock onboarding.
- **Roots-management sidebar** (list/remove registered roots from the UI).
  Tracked as a follow-up.
- **Interactive tour / coach marks**. The sample KB is the tour.
- **Drag-and-drop folder registration**. Potential follow-up.
- **Keyboard accelerator for "Open Welcome Page"**. A basic mnemonic
  (`&Open Welcome Page`) is sufficient; a cross-platform accelerator
  (Cmd+Shift+W?) can be added later.

## Related Work

- **Home page** — [Plans/complete/home-page/](Plans/complete/home-page/) —
  established the `:file` / `:synthetic` content-mode distinction the
  Welcome tab reuses.
- **Search / auto-register** — [Plans/complete/search/auto-register/](Plans/complete/search/auto-register/) —
  prior context on root registration lifecycle.
- **Platform packaging** — [Plans/complete/platform-packaging/](Plans/complete/platform-packaging/) —
  describes the `.app` bundle; sample-KB resources ship via that pipeline.
- **SWT UI guide** — [Plans/SWT-UI-GUIDE.md](Plans/SWT-UI-GUIDE.md) —
  threading rules, CDT idioms, resource disposal. Required reading before
  touching `main_window.clj`.
- **Wishlist** — [Plans/todo/wishlist.md](Plans/todo/wishlist.md) — "UI to
  add or remove roots" is adjacent.
- **Word cloud (deferred)** —
  [Plans/todo/deferred/WORD-CLOUD-CONTEXT.md](Plans/todo/deferred/WORD-CLOUD-CONTEXT.md) —
  orthogonal empty-state enhancement for the Live Search tab.
