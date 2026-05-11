---
created: 2026-04-23
updated: 2026-04-23
status: complete
tags: swt, tray, packaging, macos, debugging, launchd, launchservices, resolved
---

# SWT System Tray Icon — `.app` Bundle Debug Context

## ✅ RESOLUTION (2026-04-23 afternoon)

**The original diagnosis below was wrong.** SWT's `setView:` is NOT broken on
macOS 26. The `NSStatusItem.button` rewrite proposed in the plan would have
silently failed (`.button` returns nil while a view is set) AND broken all
tray event handling.

### Actual root cause

**macOS 26's `SystemUIServer` refuses to render `NSStatusItem` custom views
when the owning process is spawned directly by `launchd` as the
LaunchServices-tracked app process.** The status item is registered in the
menu bar (Accessibility API sees it at size 24×24, `isVisible=1`), but the
compositor renders zero pixels. Interposing any parent process — even a
trivial `sh` wrapper — fixes rendering.

Not a SWT bug. Not an API deprecation. Not an Info.plist issue.

### Test matrix that isolated the cause

| # | Launch path | Flags | JRE | Info.plist | Icon? |
|---|---|---|---|---|---|
| `make run` | shell | minimal | system | n/a (not a bundle) | ✅ |
| A | shell | full `.app` flags | system | n/a | ✅ |
| C | shell (launcher script) | full `.app` flags | bundled | full | ✅ |
| E | shell (launcher script) | full `.app` flags | bundled | `NSHighResolutionCapable` removed | ✅ |
| original | `open` → LaunchServices | full `.app` flags | bundled | full | ❌ |
| D-1 | `open` → LaunchServices | full `.app` flags | bundled | `NSHighResolutionCapable` removed | ❌ |
| **F (fix)** | **`open` → LaunchServices** | **full `.app` flags** | **bundled** | **full** | **✅** |

The invariant: `open` ⇒ ❌ regardless of Info.plist, flags, or JRE.
Test F added one layer between `launchd` and the JVM (dropped `exec` in the
launcher so `sh` waits on `java`). Icon appears.

### The fix

`winze-server/pkg/macos/winze-launcher` — remove `exec` from the final JVM
invocation; run `java` as a child of the launcher shell; forward signals:

```sh
"$APP_RESOURCES/jre/bin/java" \
  -XstartOnFirstThread \
  ... \
  -jar "$APP_RESOURCES/lib/winze-server.jar" &

JAVA_PID=$!
trap 'kill -TERM "$JAVA_PID" 2>/dev/null; wait "$JAVA_PID"' TERM INT HUP
wait "$JAVA_PID"
```

The signal trap is essential: `launchd` sends SIGTERM to the tracked
process (now `sh`, not the JVM) on Cmd-Q / Force Quit. Without forwarding,
the JVM orphans and shutdown hooks don't run — risking a corrupt Datalevin
store.

### Lessons / debugging heuristics to remember

1. **When `make run` works and `.app` doesn't, the cause is in the launch
   environment delta, not SWT code.** The original plan explicitly flagged
   this ("verify before treating it as ground truth") but went on to
   propose a fix that contradicted the hypothesis.
2. **Native Cocoa investigation via REPL is powerful but misleading.** Every
   Java/SWT-side check passed (TrayItem healthy, NSStatusItem registered,
   isVisible=1, NSStatusBar.thickness=22). The bug is outside what the
   process can introspect — it's in `SystemUIServer`'s decision to render
   or not.
3. **`NSStatusItem.button` returns nil while a view is set.** Any migration
   from `setView:` to `button.image` must unset the view first, and loses
   all the event listeners wired to the view.
4. **Bisect launch mechanism before bisecting code.** Four small shell
   tests (A, C, E, F) isolated the cause in minutes; code-level fixes were
   unnecessary.

### Docs superseded

- The "Root Cause (Confirmed)" section below — wrong.
- The "Why `make run` works (hypothesis)" section — the hypothesis was
  wrong; `make run` works because it doesn't go through `launchd`/`open`,
  not because of dock flags.
- The "Why `setView:` is broken in macOS 26" section — it isn't.

The rest of the document (rule-outs, REPL state, Cocoa probe results) is
accurate and useful as a record of the investigation.

---

## Problem Statement

The system tray icon (macOS menu bar `NSStatusItem`) does **not** appear when
Winze is launched by double-clicking `Winze.app`. It **does** appear when the
server is launched via `make run` (i.e., `java -XstartOnFirstThread ... -jar
~/.local/share/winze/lib/winze-server.jar` from the terminal).

The two code paths use the **same uberjar** and the **same Clojure source**.
The issue is environmental — something in the `.app` bundle launch context
prevents the tray icon from appearing.

## Current Server State (as of 2026-04-23, ~11:00 AM)

The server (PID 87407) **crashed** during REPL debugging — a JVM SIGSEGV from
an unsafe `OS/objc_msgSend` call with wrong argument types. The stale PID file
was removed (`rm -f ~/.local/share/winze/.pid`). The server needs to be
restarted by double-clicking `Winze.app`.

## What Has Been Tried (and Ruled Out)

| Hypothesis | Verdict | Reason |
|------------|---------|--------|
| Template image black pixels invisible in dark mode | ❌ Ruled out | System is in light mode; also not root cause |
| `@statusbar-icon` atom is nil (image load failed) | ❌ Ruled out | REPL probe: `org.eclipse.swt.graphics.Image` (18×18) |
| `NSPrincipalClass` missing from Info.plist | ✅ Fixed (2026-04-23) | Added; stopped the "Device is disposed" event-loop crash |
| TrayItem never created | ❌ Ruled out | REPL: TrayItem in `app-props`, not disposed, visible=true |
| `getSystemTray` returns nil | ❌ Ruled out | REPL: Tray object non-nil |
| NSApplication activation policy wrong | ❌ Ruled out | REPL: policy=0 (NSApplicationActivationPolicyRegular) |
| macOS Sequoia "menu bar crowding" hiding | ❌ Ruled out | `NSStatusItem.isVisible=1` |
| NSStatusItem not registered in system menu bar | ❌ Ruled out | AppleScript/Accessibility sees it with size {24,24} |
| Image is nil after view removal | N/A | Separate from root cause |

## Root Cause (Confirmed)

**SWT's deprecated `setView:` API does not render in macOS 26.**

### Evidence

1. **Accessibility API sees the status item** — AppleScript:
   ```
   tell application "System Events" to get every menu bar item of menu bar 2 of process "java"
   → menu bar item 1 of menu bar 2 of application process java
   ```
   Position ~`{711, 57}`, size `{24, 24}` — correct dimensions.

2. **All Cocoa-level checks pass on the Java/SWT side**:
   - TrayItem: `disposed=false`, `visible=true`, `image set`
   - NSStatusItem: has `SWTImageView` view, view has NSImage (18×18), view has a
     window, alpha=1.0, view not hidden, `NSStatusItem.isVisible=1`

3. **NSStatusBarWindow stuck at `{0,0,38,0}`, `isOnScreen=0`** — the window
   SWT exposes in our process's window list has `h=0`. This is likely a
   **proxy/placeholder** window; the actual rendering is done by
   `SystemUIServer` in its own window. The `isOnScreen=0` reflects SWT's proxy
   not being on-screen, NOT the icon being absent.

4. **Fresh NSStatusItem created while event loop is running also gets `{h=0}`**
   — rules out startup timing. The NSStatusBar's connection to SystemUIServer
   is functional; it just doesn't render SWT's custom view.

5. **All screens report `menuBarHeight=0`** — macOS 26 API change;
   `NSScreen.menuBarHeight` is deprecated and now returns 0. This is a
   symptom, not a cause.

### Why `setView:` is broken in macOS 26

`SWTImageView` (SWT's custom `NSView` subclass) is passed to
`[NSStatusItem setView:]`. This API was deprecated in macOS 10.14 (Mojave).
On macOS 26, it appears the custom view is registered with the menu bar
(Accessibility confirms) but the `SystemUIServer` compositor no longer renders
the custom view's content.

The modern replacement: `NSStatusItem.button.image = nsImage`. This is the
`NSStatusItemButton` API available since macOS 10.10.

### Why `make run` works (hypothesis)

Terminal-launched processes are handled differently by LaunchServices. Without
`-Xdock:name` and the `.app` bundle context, the NSApplication/NSStatusBar
initialization takes a slightly different path that may still honor `setView:`.
This has **not been confirmed** — it's possible `make run` is also broken on
macOS 26 and the observation is stale. **Verify before treating it as ground
truth.**

## Secondary Issue: "Device is Disposed" Event-Loop Crash (Fixed)

Four `.app` launch attempts before the 9:03 run crashed ~10s after startup:
```
ERROR llm-memory.ui.main-window - UI crashed during initialization
org.eclipse.swt.SWTException: Device is disposed
  at Display.getShells(Display.java:1894)
  at ui.SWT$process_event.invokeStatic(SWT.clj:341)
  at ui.SWT$application.doInvoke(SWT.clj:367)
```

`process_event` calls `(.getShells display)`. If the Display is disposed
concurrently (likely by a macOS `applicationShouldTerminate:` or system event
during the `.app` activation sequence), this throws.

**Fix already applied**: `NSPrincipalClass = NSApplication` in `Info.plist`
(added 2026-04-23). The 9:03 run did not crash and ran the full event loop
successfully. The fix is in the installed DMG.

## Key Files

| File | Role |
|------|------|
| [`pkg/macos/winze-launcher`](../../winze-server/pkg/macos/winze-launcher) | Shell launcher — JVM flags, first-run install.sh |
| [`pkg/macos/Info.plist.template`](../../winze-server/pkg/macos/Info.plist.template) | Bundle metadata — NSPrincipalClass fix already here |
| [`src/llm_memory/server/main.clj`](../../winze-server/src/llm_memory/server/main.clj) | Entry point — CDT/SWT init, Display creation, UI launch |
| [`src/llm_memory/ui/main_window.clj`](../../winze-server/src/llm_memory/ui/main_window.clj) | `tray-item2` (line 793) — TrayItem creation; **fix goes here** |
| [`src/llm_memory/ui/theme.clj`](../../winze-server/src/llm_memory/ui/theme.clj) | `apply-theme-startup!` — image loading, atom population |
| [`src/llm_memory/ui/resources.clj`](../../winze-server/src/llm_memory/ui/resources.clj) | `statusbar-icon` atom — holds `org.eclipse.swt.graphics.Image` |
| `resources/branding/statusbar/macos/winzeTemplate.png` | 18×18 black+alpha template icon |

## The Fix: Replace `setView:` with `NSStatusItem.button.image`

After SWT's `TrayItem.` constructor calls `setView:` internally, immediately
override it with the modern API. This must run on the UI thread (already
satisfied — `tray-item2` runs during CDT init on the main thread).

### Getting the NSImage handle

`org.eclipse.swt.graphics.Image` has a private `handle` field of type
`org.eclipse.swt.internal.cocoa.NSImage`. Its `.id` field is the Cocoa object
pointer (a `long`).

```clojure
(let [img-cls (.getClass @statusbar-icon)
      hnd-fld (doto (.getDeclaredField img-cls "handle") (.setAccessible true))
      ns-img  ^org.eclipse.swt.internal.cocoa.NSImage (.get hnd-fld @statusbar-icon)
      ns-img-id (.id ns-img)]
  ...)
```

### Getting the NSStatusItem

`org.eclipse.swt.widgets.TrayItem` (macOS) has a private `item` field of type
`org.eclipse.swt.internal.cocoa.NSStatusItem`.

```clojure
(let [ti-cls   (.getClass tray-item)
      item-fld (doto (.getDeclaredField ti-cls "item") (.setAccessible true))
      ns-item  ^org.eclipse.swt.internal.cocoa.NSStatusItem (.get item-fld tray-item)
      item-id  (.id ns-item)]
  ...)
```

### Calling the modern API

```clojure
(import '[org.eclipse.swt.internal.cocoa OS])

;; 1. Remove the custom view SWT set via setView:
(OS/objc_msgSend item-id (OS/sel_registerName "setView:") (long 0))

;; 2. Get the button (NSStatusItemButton)
(let [btn-id (OS/objc_msgSend item-id (OS/sel_registerName "button"))]
  (when (pos? btn-id)
    ;; 3. Set the image
    (OS/objc_msgSend btn-id (OS/sel_registerName "setImage:") ns-img-id)))
```

**WARNING**: Use `(long 0)` not `0` for the nil argument — Clojure may select
the wrong overload. Similarly, ensure `ns-img-id` is typed as `long`. Add
`^long` hints or explicit casts as needed to force the `(long, long, long)`
overload of `OS/objc_msgSend`.

### Event handler impact

SWT's `TrayItem.addListener(SWT.Selection, ...)` and
`TrayItem.addListener(SWT.MenuDetect, ...)` are wired to the `SWTImageView`
(the custom view). After removing the view, these listeners will NOT fire on
click.

The `tray-item2` call in `main_window.clj:1248-1253` uses:
- `(on e/menu-detected ...)` → right-click shows `(element :tray-menu)`
- `(on e/widget-selected ...)` → left-click calls `toggle-visibility!`

After switching to the button API, click handling needs an alternative. Options:
1. **NSMenu on the button**: Set an `NSMenu` on the button with
   `[button setMenu:nsMenu]`. For right-click this works natively. For
   left-click, an `NSButton` action target is needed (requires native Obj-C
   proxy).
2. **Polling / timer approach**: Not recommended.
3. **Minimal approach**: Accept that SWT TrayItem events are broken; create
   a simple NSMenu via Cocoa bridge for the three menu items (Toggle, About,
   Quit) and attach it to the button. Remove the CDT `on e/` event handler
   wiring for tray events.

The minimal approach is cleanest: for a tray-only app, all interaction goes
through the right-click menu. Left-click on a status item in macOS typically
shows a menu (Finder, Bartender, etc. follow this pattern). Left-click toggling
is a nice-to-have, not essential.
