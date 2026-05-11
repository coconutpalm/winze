---
created: 2026-04-23
tags: [onboarding, welcome, ui, first-run, roots, menu]
related:
  - Plans/todo/ONBOARDING-CONTEXT.md
---

# First-Run Onboarding — Plan

## Step 1 — Welcome-page Hiccup

Add `welcome-page` to [search.clj](winze-server/src/llm_memory/ui/search.clj).
Returns a full HTML string (same shape as `empty-page` / `results-page`).

Function signature: `(welcome-page registered-roots)` — taking roots as an
argument keeps the function pure / testable; callers pass
`(core/list-roots (server/store))`.

Content structure (Hiccup):

```clojure
[:html
 [:head [:meta {:charset "UTF-8"}] [:style (page-css)]]
 [:body
  [:div.welcome
   [:h1 "Winze"]
   [:p.tagline "Nothing forgotten. Everything found."]
   [:p.intro "Winze indexes your markdown planning documents and makes them
             searchable by meaning, not just keywords. Point it at a folder
             of .md files to get started."]
   [:div.actions
    [:a.primary   {:href "winze:register-root"}    "Add a folder…"]
    [:a.secondary {:href "winze:install-sample-kb"} "Try the sample knowledge base"]]
   (when (seq registered-roots)
     [:div.current-roots
      [:h3 "Folders you've added"]
      [:ul (for [{:root/keys [name uri]} registered-roots]
             [:li [:strong name] " "
              [:span.path (str/replace uri #"^file://" "")]])]])
   [:p.footnote "Or run " [:code "/register-plans"] " from Claude Code."]]]]
```

Extend `page-css` with `.welcome`, `.actions`, `.primary`, `.secondary`,
`.tagline`, `.intro`, `.footnote`, `.current-roots`, `.path`. Match the
existing `#4A3F90` muted-purple palette used in `empty-page`.

Standalone REPL verification: `(spit "/tmp/welcome.html" (welcome-page []))`
and open in a browser. Add an RCF `(tests ... :rcf)` block asserting the
output contains both `"winze:register-root"` and `"winze:install-sample-kb"`
and that passing `[{:root/name "X" :root/uri "file:///foo"}]` renders the
"Folders you've added" block.

## Step 2 — Tweak empty-page for the zero-roots case

In `empty-page` ([search.clj:341-359](winze-server/src/llm_memory/ui/search.clj#L341-L359)),
extend the existing hint block: when `paths` is empty (i.e. no roots
registered), render a small line linking back to the Welcome tab:

```clojure
[:div {:style "..."}
 "No folders registered — see the "
 [:a {:href "winze:open-welcome"} "Welcome tab"]
 "."]
```

The `winze:open-welcome` pseudo-URL is wired up in Step 5.

This is an insurance-policy against the user closing the Welcome tab before
registering a root. Minimal additional CSS; no new branches beyond `(when
(empty? paths) …)`.

## Step 3 — `open-welcome-tab!` helper

Add a public function to
[main_window.clj](winze-server/src/llm_memory/ui/main_window.clj):

```clojure
(defn open-welcome-tab!
  "Open the Welcome tab, or focus it if it already exists.
   Safe to call from any thread — wraps UI work in async-exec!."
  []
  (async-exec!
   (fn []
     (if-let [existing (element :welcome-tab)]
       (when-not (.isDisposed existing)
         (.setSelection (element :main-folder) existing))
       (let [roots (core/list-roots (server/store))
             html  (search/welcome-page roots)
             folder (element :main-folder)]
         (child-of folder app-props
                   (defchildren
                     (custom-browser (id! :ui/welcome-browser)
                                     :text html)
                     (ctab-item SWT/CLOSE
                                "Welcome"
                                :image @statusbar-icon
                                (id! :ui/welcome-tab)
                                (control :ui/welcome-browser)
                                (on e/widget-disposed [props parent event]
                                    (swap! app-props dissoc :ui/welcome-tab
                                                            :ui/welcome-browser)))))
         (.setSelection folder (dec (.getItemCount folder)))
         (update-edit-button!)
         (focus-selected-tab-content!))))))
```

Notes:

- Uses inline CDT syntax (`child-of` / `defchildren`) instead of `open-tab!`
  so that `(id! :ui/welcome-tab)` can be placed directly on the `CTabItem`.
  `open-tab!` does not return the `CTabItem` — it returns the result of
  `focus-selected-tab-content!` (nil) — so it cannot be used to register
  the tab.
- `open-tab!` ends with `(update-edit-button!)`. This helper is included here
  for the same reason: `CTabFolder SWT/Selection` fires only on user-initiated
  clicks, not on programmatic `.setSelection`, so the edit button state must
  be updated explicitly whenever the Welcome tab is opened via tray menu or
  auto-open. Since the Welcome tab is not a file tab, `update-edit-button!`
  will disable the Edit button (correct behavior).
- `(element :welcome-tab)` (not `:ui/welcome-tab`) looks up `:ui/welcome-tab`
  in `app-props`; `element` automatically prepends the `ui/` namespace.
- The `(on e/widget-disposed ...)` CDT handler clears both the tab and browser
  keys when the user closes the tab. No raw Java `DisposeListener` is needed
  and no additional import is required.
- Content-mode is `:synthetic` by default for non-file tabs (no
  abs-path / rel-path / root-uri in `app-props`); Cmd+E is already suppressed
  for synthetic tabs per the home-page work.

Add a `(tests ... :rcf)` block that exercises the idempotency
(call twice → one `CTabItem`; dispose; call again → one `CTabItem`).

## Step 4 — `winze:` URL handlers

Extend the `LocationListener` dispatch at
[main_window.clj:156-185](winze-server/src/llm_memory/ui/main_window.clj#L156-L185)
with three new pseudo-URL branches.

### 4a — `winze:open-welcome`

```clojure
(= url "winze:open-welcome")
  (do (set! (.-doit event) false)
      (open-welcome-tab!))
```

Used by the `empty-page` tweak in Step 2.

### 4b — `winze:register-root`

```clojure
(= url "winze:register-root")
  (do (set! (.-doit event) false)
      (async-exec! register-root-via-dialog!))
```

Helper `register-root-via-dialog!`:

1. Open a `DirectoryDialog` on the shell obtained from
   `(element :main-window)`, title "Choose a folder of markdown documents".
2. `(.open dialog)` → selected path string or `nil`.
3. If `nil`, return.
4. Derive args via `derive-root-args` (pure helper, unit-testable):

   ```clojure
   (defn derive-root-args [selected-path]
     (let [f    (io/file selected-path)
           leaf (.getName f)
           plans-sub (io/file f "Plans")
           plans-dir (cond
                       (= leaf "Plans")        ""
                       (.isDirectory plans-sub) "Plans"
                       :else                    "")]
       {:uri       (str "file://" selected-path)
        :name      leaf
        :plans-dir plans-dir}))
   ```

5. If a root with that URI is already registered, render a brief
   "_Already registered._" fragment in the Welcome tab browser and return.
6. Otherwise call `(core/register-root! (server/store) args)`. Wrap the
   whole block in `try/catch` — on failure, render an error fragment in
   the Welcome tab browser via
   `(when-let [b (element :welcome-browser)] (.setText b ...))`.
7. On success, persist and index the new root:

   ```clojure
   (let [store (server/store)
         uri   (:uri args)]
     (server/write-roots-config! store)          ; persist to roots.edn — survives restart
     (future
       (index/reconcile! store uri)              ; index existing files
       (watcher/start-watcher! store uri)))      ; watch for future changes
   ```

   `server`, `index`, and `watcher` are already required in
   `main_window.clj` — no new `:require` needed.
8. Call `refresh-welcome-tab!` and `refresh-live-search!`.

RCF tests on `derive-root-args` cover the three branches: selected folder
is named `Plans`; selected folder contains a `Plans/` subdirectory; selected
folder is a bare docs directory.

### 4c — `winze:install-sample-kb`

```clojure
(= url "winze:install-sample-kb")
  (do (set! (.-doit event) false)
      (install-sample-kb-async!))
```

Helper `install-sample-kb-async!`:

1. Immediately update the Welcome tab's Browser with a transient
   "Installing sample knowledge base…" message.
2. `(future …)`:
   - Call `(sample-kb/install!)` (Step 8 below).
   - On result `{:status :installed | :already-installed :path p}`: build
     `{:uri (str "file://" p) :name "Sample" :plans-dir ""}`. If not
     already registered, call `core/register-root!`, then complete the
     full registration sequence:

     ```clojure
     (let [store (server/store)
           uri   (str "file://" p)]
       (core/register-root! store {:uri uri :name "Sample" :plans-dir ""})
       (server/write-roots-config! store)     ; persist to roots.edn
       (index/reconcile! store uri)           ; index existing files
       (watcher/start-watcher! store uri))    ; watch for future changes
     ```

   - On exception: catch, log, update Welcome tab with an error message.
3. `async-exec!` → call `refresh-welcome-tab!` and `refresh-live-search!`.

## Step 5 — Refresh helpers

One new helper plus one existing function:

**`refresh-welcome-tab!`** — new. A stub already exists in `main_window.clj`
(added alongside the `on-root-changed` callback infrastructure). Fill in the
body here:

```clojure
(defn- refresh-welcome-tab!
  "Re-render the Welcome tab's Browser with the current roots list.
   No-op when the tab is closed."
  []
  (async-exec!
    (fn []
      (when-let [b (element :welcome-browser)]
        (when-not (.isDisposed b)
          (.setText b (search/welcome-page
                        (core/list-roots (server/store)))))))))
```

Note: `(element :welcome-browser)` looks up `:ui/welcome-browser` in
`app-props` — `element` auto-prepends `ui/`. The `:welcome-browser` key is
populated by `(id! :ui/welcome-browser)` on the `custom-browser` in Step 3.

**`refresh-live-search!`** — already exists at
[main_window.clj:393](winze-server/src/llm_memory/ui/main_window.clj#L393)
as a private function. It handles both active-search re-runs and home-page
refresh. **Do not add a new definition** — call the existing one directly.

`set-live-search-content!` also already exists (declared at line 75).

Wire `refresh-welcome-tab!` and `refresh-live-search!` into:

- `winze:register-root` success path.
- `winze:install-sample-kb` success path.
- The `on-root-changed` listener (already wired via `core/add-root-listener!`
  in `defmain`) fires both automatically for MCP- and nREPL-triggered root
  changes, keeping the Welcome tab current even when roots are added outside
  the GUI.

## Step 6 — Tray menu item

In the existing tray `menu SWT/POP_UP` at
[main_window.clj:1293-1306](winze-server/src/llm_memory/ui/main_window.clj#L1293-L1306),
insert a new `menu-item SWT/PUSH` immediately after the existing
`Toggle visibility` item and before `About…`:

```clojure
(menu-item SWT/PUSH "Open &Welcome Page"
           (on e/widget-selected [props parent event]
               (open-welcome-tab!)))
```

Mnemonic on `W` (`Open &Welcome Page`). A keyboard accelerator
(Cmd+Shift+W or similar) and a proper `Menu SWT/BAR` on the shell are
both out of scope for this plan — see Out of Scope below.

## Step 7 — Auto-open on first launch with zero roots

In the existing startup `future` at
[main_window.clj:1313-1318](winze-server/src/llm_memory/ui/main_window.clj#L1313-L1318)
(which currently loads the home page into Live Search), extend the logic:

```clojure
(future
  (try
    (let [home  (search/home-page)
          roots (core/list-roots (server/store))]
      (async-exec!
        (fn []
          (set-live-search-content! home)
          (when (empty? roots)
            (open-welcome-tab!)
            ;; open-welcome-tab! calls async-exec! internally, so the tab
            ;; may not be registered yet in this same async-exec! frame.
            ;; Use a second async-exec! to let it settle before selecting.
            (async-exec!
              (fn []
                (when-let [tab (element :welcome-tab)]
                  (.setSelection (element :main-folder) tab))))))))
    (catch Throwable t
      (log/error t "Failed to load home page / welcome tab"))))
```

Note: `(element :welcome-tab)` (not `:ui/welcome-tab`) and
`(element :main-folder)` (not `:ui/main-folder`) — `element` auto-prepends
the `ui/` namespace prefix.

The Welcome tab becomes the front tab when it auto-opens, so a first-run
user sees it immediately. Subsequent launches with at least one root
registered skip the auto-open.

## Step 8 — Bundled sample KB + JAR-safe unpack

### 8a — Bundle resources

Create `winze-server/resources/sample-kb/` with five short markdown files
(~80 lines each, less is better):

- `home.md` — welcome / "start here". Having a `home.md` here means that
  once the Sample root is registered, the Live Search tab immediately
  shows a home page instead of "Type to search…", reinforcing the demo.
- `how-winze-works.md` — architecture summary.
- `search-tips.md` — semantic search behaviour, metadata filters.
- `creating-a-home-md.md` — the home.md convention.
- `metadata-conventions.md` — status / doc_type / group inference.

Any file under `resources/` ships inside the uberjar automatically.

### 8b — Unpack helper

Add `llm-memory.ui.sample-kb` (new namespace). Expose `(install!)`:

```clojure
(defn- target-dir []
  (let [home (System/getProperty "user.home")
        macos-support (io/file home "Library" "Application Support" "Winze" "sample-kb")
        xdg (or (System/getenv "XDG_DATA_HOME")
                (str home "/.local/share"))
        linux-win (io/file xdg "winze" "sample-kb")]
    (cond
      (str/starts-with? (System/getProperty "os.name") "Mac") macos-support
      :else                                                    linux-win)))

(defn install! []
  (let [target (target-dir)]
    (.mkdirs target)
    (if (and (.isDirectory target) (seq (.listFiles target)))
      {:status :already-installed :path (.getAbsolutePath target)}
      (do
        (doseq [[leaf url] (enumerate-resources "sample-kb/")]
          (when (seq leaf)                                ; skip the directory entry itself
            (with-open [r (io/reader url :encoding "UTF-8")
                        w (io/writer (io/file target leaf) :encoding "UTF-8")]
              (io/copy r w))))
        {:status :installed
         :path       (.getAbsolutePath target)
         :file-count (count (.listFiles target))}))))
```

`enumerate-resources` is the JAR-safe enumeration — adapt the
`classpath-lang-urls` pattern from
`winze-server/src/llm_memory/highlight/loader.clj` (check URL protocol,
branch on `file:` vs `jar:`). Like `classpath-lang-urls`, it returns
`[leaf-name URL]` pairs so callers can use the URL object directly with
`(io/reader url :encoding "UTF-8")`. **Never**
`(io/file (io/resource "sample-kb"))` — it throws inside the uberjar.

RCF tests:

- `(install!)` with an empty target → unpacks the expected file count.
- `(install!)` when target is already populated → returns
  `:already-installed`, doesn't overwrite.

## Step 9 — Visual verification (REQUIRED per SWT rules)

Per [Plans/SWT-UI-GUIDE.md](Plans/SWT-UI-GUIDE.md), every visual change
must be screenshot-verified. Use
`llm-memory.ui.util/screenshot-widget!` (fully qualified — aliases fail
intermittently).

Test sequence from a clean state (delete `~/.local/share/winze/.datalevin/`
and `~/.local/share/winze/roots.edn` first):

1. **First launch, zero roots** → Welcome tab auto-opens, is front-most,
   title "Welcome", X button visible. Screenshot.
2. **Tray right-click** → menu now contains "Open Welcome Page" between
   "Toggle visibility" and "About…". Screenshot of the open menu.
3. **Close Welcome tab, then click tray menu "Open Welcome Page"** → tab
   re-opens, focused. Screenshot.
4. **With Welcome tab already open, click menu again** → same tab focused,
   no duplicate. Verify via `(element :ui/welcome-tab)` at REPL.
5. **Click "Add a folder…"** → `DirectoryDialog` opens. Pick a folder
   containing `Plans/`. Welcome tab refreshes, "Folders you've added"
   block now lists it. Live Search tab also refreshes to show home-page
   or "Create home.md at …" hint. Screenshot.
6. **Restart app** → registered root persists; Welcome tab does **not**
   auto-open. Open manually via tray menu → Welcome tab reflects the
   registered root. Screenshot.
7. **Clean state, click "Try the sample knowledge base"** → transient
   "Installing…" message; then Sample root registered, sample `home.md`
   visible in Live Search. Screenshot.
8. **Click sample-KB button again** → idempotent; no duplicate root, no
   re-unpack. Verify `list-roots` at REPL.
9. **Close Welcome tab, register zero roots** (i.e. immediately after
   reset, don't add any) → Live Search's `empty-page` shows the
   "see the Welcome tab" link. Click it → Welcome tab opens. Screenshot.
10. **Error path**: point `winze:register-root` at a read-only location or
    mock `register-root!` to throw. Verify an error fragment renders in
    the Welcome tab without crashing the app.
11. **Cmd+E on the Welcome tab** → suppressed (tab is `:synthetic`).

## Step 10 — Edge cases

- **Two Winze windows concurrently** on first run: rare. `register-root!`
  should be idempotent or at least non-corrupting; if duplication occurs,
  add a pre-check.
- **Sample-KB directory exists but is empty** (interrupted install): detect
  via `(seq (.listFiles target))`; re-unpack.
- **Welcome tab closed while a background install is still running**:
  catch the `isDisposed` check before writing completion / error HTML;
  log a warning instead.
- **`.getSystemTray` returns `nil`** on platforms without a tray (some
  Linux desktops): the tray-item CDT init already guards this. The menu
  bar provides the "Open Welcome Page" affordance; nothing else to do.
- **Roots added via MCP while GUI is open**: if a roots-changed hook is
  wired, the Welcome tab's "Folders you've added" section updates live.
  If not, at worst the user sees a stale list until they reopen the tab.
- **Windows `file://` URIs**: paths with backslashes / drive letters
  differ. Best-effort for this plan; mark as a Windows follow-up.

## Step 11 — Documentation

- Update [README.md](README.md) to mention the Welcome tab and the tray
  menu entry.
- Edit [Plans/todo/wishlist.md](Plans/todo/wishlist.md):
  "UI to add or remove roots" — note the *add* half is done via the
  Welcome tab; *remove* is still outstanding. Add a "macOS menu bar /
  shell `Menu SWT/BAR`" entry if not already present.

## Out of Scope (Deferred)

- **macOS application menu bar / shell `Menu SWT/BAR`**. No menu bar is
  attached to the shell today. Adding one (with a `File` menu containing
  `Open Welcome Page`, migrating to the screen menu bar on macOS) is a
  worthwhile follow-up — it gives future top-level menus a place to live
  — but the tray menu alone is sufficient for this plan. When picked up,
  the wiring is: `(menu SWT/BAR (id! :ui/menu-bar) …)` inside the shell
  block at [main_window.clj:1268-1280](winze-server/src/llm_memory/ui/main_window.clj#L1268-L1280),
  plus `.setMenuBar` in `defmain` if CDT's BAR init doesn't handle it.
  The same `open-welcome-tab!` helper works unchanged.
- **Roots-management sidebar** (list / remove registered roots).
  Commented-out `SashForm` scaffold exists at
  [main_window.clj:1276-1280](winze-server/src/llm_memory/ui/main_window.clj#L1276-L1280).
  Track as a follow-up: `ROOTS-SIDEBAR-*`.
- **Drag-and-drop folder registration** onto the Welcome tab.
- **Keyboard accelerator** for "Open Welcome Page".
- **Animated onboarding tour / coach marks**.

## File Inventory

**New files:**
- `winze-server/resources/sample-kb/home.md`
- `winze-server/resources/sample-kb/how-winze-works.md`
- `winze-server/resources/sample-kb/search-tips.md`
- `winze-server/resources/sample-kb/creating-a-home-md.md`
- `winze-server/resources/sample-kb/metadata-conventions.md`
- `winze-server/src/llm_memory/ui/sample_kb.clj`

**Modified:**
- `winze-server/src/llm_memory/ui/search.clj` — add `welcome-page`,
  update `empty-page`, CSS additions.
- `winze-server/src/llm_memory/ui/main_window.clj` — add
  `open-welcome-tab!`, fill in `refresh-welcome-tab!` stub (already present),
  `derive-root-args`, `register-root-via-dialog!`, `install-sample-kb-async!`;
  extend the `on e/changing` handler in `custom-browser` (~line 156) with
  `winze:open-welcome`, `winze:register-root`, `winze:install-sample-kb`;
  add tray menu item "Open Welcome Page"; extend startup `future`
  (~line 1299) to auto-open on zero roots. Add new `:require`
  `[llm-memory.ui.sample-kb :as sample-kb]` (needed by
  `install-sample-kb-async!`); one new Java import: add `DirectoryDialog`
  to the existing
  `[org.eclipse.swt.widgets ...]` import vector
  (CDT `on e/widget-disposed` replaces raw `DisposeListener` — no
  `DisposeListener` import needed).
  Note: `refresh-live-search!` (line 392) and `on-root-changed`
  (wired via `core/add-root-listener!` in `defmain`) already exist.
- `clj-llm-memory/src/llm_memory/core.clj` — `add-root-listener!`,
  `root-listeners`, and `notify-root-listeners!` already added; `register-root!`
  and `remove-root!` already notify listeners. No further changes needed here.
- `README.md` — mention Welcome tab and menu paths.
- `Plans/todo/wishlist.md` — reflect the add-root half being done.
