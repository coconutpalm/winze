---
created: 2026-04-23
updated: 2026-04-23
status: complete
tags: swt, tray, packaging, macos, debugging, launchd, launchservices, resolved
---

# SWT System Tray Icon — `.app` Bundle Debug Plan

## ✅ RESOLUTION (2026-04-23 afternoon)

**Step 6 was not implemented — it was the wrong fix.** `setView:` is not
broken on macOS 26. The real cause was LaunchServices/launchd process
lineage; see [CONTEXT.md](CONTEXT.md) for the test matrix that isolated it.

### What was actually changed

One file: [`winze-server/pkg/macos/winze-launcher`](../../winze-server/pkg/macos/winze-launcher).

- Removed `exec` from the JVM invocation — runs `java` as a child of `sh`.
- Added `JAVA_PID=$!` and a `trap '... kill -TERM "$JAVA_PID" ...' TERM INT HUP`
  so Cmd-Q / Force Quit (launchd → sh) forwards to the JVM for clean shutdown.
- Final `wait "$JAVA_PID"` so the shell keeps the LaunchServices-tracked
  PID alive for the JVM's lifetime.

### What was NOT needed

- No changes to `main_window.clj` (`tray-item2`, theme reload, menu wiring).
- No changes to `resources.clj` (image loading unchanged).
- No Cocoa reflection on private SWT fields (`.item`, `.handle`).
- No NSStatusItem `setView:` → `button.image` rewrite.
- No Info.plist changes (original bundle metadata is correct).

### Why Step 6 would have failed

- `NSStatusItem.button` returns nil while a view is set. The proposed fix
  guarded with `(when (pos? btn-id) ...)`, which would have silently no-op'd.
- Even if the button were obtained, all TrayItem event listeners
  (`SWT.Selection`, `SWT.MenuDetect`) are wired to the `SWTImageView`; removing
  the view loses all tray click/menu handling.
- The private-field reflection on `.item` and `.handle` is SWT-version- and
  platform-fragile.

### Step 7 (template image tinting) — still valid

`-[NSImage setTemplate:YES]` auto-inverts in dark mode. Can be applied to
`@statusbar-icon` after the image is loaded (not via TrayItem Cocoa
internals). Leaving as a potential polish item — only needed if the current
rendering is insufficient in dark mode. Verify first before implementing.

---

## ✅ Step 1 — Log Reading (Done)

Log showed "plan server ready" with no tray errors in the successful 9:03 run.
Earlier runs (8:43–8:53) crashed with "Device is disposed" — fixed by adding
`NSPrincipalClass = NSApplication` to `Info.plist`.

## ✅ Step 2 — Stale PID (Done)

Verified not the cause. Server starts, nREPL is reachable, TrayItem is created.
**Note**: The server PID 87407 crashed during debugging (SIGSEGV from unsafe
Cocoa call). Stale PID file was removed. Restart by double-clicking `Winze.app`
before any REPL work below.

## ✅ Step 3 — Icon Load Check (Done)

`@statusbar-icon` holds `org.eclipse.swt.graphics.Image` (18×18). Not the
cause. TrayItem exists in `app-props`, not disposed, visible=true.

## ✅ Step 4 — CDT/SWT Native Loading (Done)

SWT loaded correctly. NSApplication activation policy = 0 (Regular). System
tray returns non-nil. SWT platform = "cocoa". All Java/SWT-side checks pass.

## ✅ Step 5 — Isolation Confirmed (Partial)

Cocoa-level investigation via REPL:
- NSStatusItem created, has SWTImageView, image 18×18, window exists, alpha=1.0
- **NSStatusBarWindow: frame `{0,0,38,0}`, isOnScreen=0** — proxy window, not
  an actual rendering failure signal
- **Accessibility API sees the item**: `menu bar item 1 of menu bar 2 of
  process "java"`, size `{24,24}` — proves the item IS in the system menu bar
- Root cause: `setView:` rendered by SystemUIServer on macOS 26 produces no
  visible pixels; the modern `button.image` API must be used instead

`make run` working/not-working has not been re-confirmed on macOS 26.3.1.
**Verify before next session**: run `make run` from terminal and check if tray
appears. If it also fails, the distinction is moot — the fix applies regardless.

---

## Step 6 — Fix: Replace `setView:` with `button.image` API

**Goal**: Make the status icon visually appear in the menu bar on macOS 26.

### 6.1 — Add `fix-tray-item-for-macos26!` in `main_window.clj`

Add a private helper after the `tray-item2` function (around line 821). It must
run on the UI thread (already guaranteed — called from within CDT init).

```clojure
(defn- fix-tray-button-image!
  "macOS 26: setView: no longer renders. Remove the custom view and use the
  modern button.image API so the icon is visible in the menu bar."
  [tray-item icon]
  (import '[org.eclipse.swt.internal.cocoa OS])
  (let [ti-cls   (.getClass tray-item)
        item-fld (doto (.getDeclaredField ti-cls "item") (.setAccessible true))
        ns-item  (.get item-fld tray-item)
        item-id  (.id ns-item)
        img-cls  (.getClass icon)
        hnd-fld  (doto (.getDeclaredField img-cls "handle") (.setAccessible true))
        ns-img   (.get hnd-fld icon)
        ns-img-id (.id ns-img)]
    (OS/objc_msgSend item-id (OS/sel_registerName "setView:") (long 0))
    (let [btn-id (OS/objc_msgSend item-id (OS/sel_registerName "button"))]
      (when (pos? btn-id)
        (OS/objc_msgSend btn-id (OS/sel_registerName "setImage:") ns-img-id)))))
```

Key type-safety notes:
- Use `(long 0)` for the nil argument to `setView:` to force the `(long,long,long)`
  overload of `OS/objc_msgSend` (Clojure may otherwise pick the wrong one)
- `ns-img-id` must be a `long` — `.id` on `NSObject` subclasses returns `long`
- Add `^long` hints if Clojure reflection still picks the wrong overload
- Test first in REPL after restart before writing to file

### 6.2 — Call `fix-tray-button-image!` from `tray-item2`

In `tray-item2` (line 793), after the TrayItem is created and image set:

```clojure
(let [tray-item (TrayItem. tray style)]
  (doto tray-item
    (.setImage @statusbar-icon)
    (.setHighlightImage @statusbar-icon))
  
  ;; macOS 26: switch from setView: to button.image API
  (when macos?
    (fix-tray-button-image! tray-item @statusbar-icon))
  
  ...)
```

`macos?` is already defined in `resources.clj`.

### 6.3 — Fix click event handling

After removing the custom view, SWT's `TrayItem.addListener(SWT.Selection/MenuDetect)`
will not fire. The `on e/menu-detected` and `on e/widget-selected` handlers in
`main_window.clj:1248-1253` will be silently ignored.

**Minimal fix**: Attach an `NSMenu` directly to the button via Cocoa bridge.
The menu should replicate the three items in the SWT POP_UP menu:
1. Toggle visibility → `toggle-visibility!`
2. About → `show-about-dialog!`
3. Quit → `quit!`

```clojure
(defn- attach-native-menu-to-button!
  "Attach an NSMenu to the NSStatusItem button so right-click shows the menu.
  Required after switching from setView: to button API on macOS 26."
  [btn-id]
  ;; Build an NSMenu via Cocoa bridge, add NSMenuItems that call back to
  ;; Clojure via a proxy target. See CONTEXT.md for full implementation plan.
  ...)
```

**Alternative (simpler for now)**: The CDT Menu (`:ui/tray-menu`) is still
created and attached to the Shell. We can trigger it manually by modifying how
the button press works. Or: accept no click handling in the interim, ship the
visible icon, and add click handling as a follow-on.

### 6.4 — Theme reload (image update)

`theme.clj:reload-theme!` updates `@statusbar-icon` and calls
`(.setImage tray-item @statusbar-icon)`. After switching to the button API,
this SWT call will update the TrayItem's internal image but the button won't
be refreshed. Add a corresponding `fix-tray-button-image!` call to the
theme-reload path in `main_window.clj:917`:

```clojure
(when-let [ti (element :tray-item)]
  (.setImage ti @statusbar-icon)
  (.setHighlightImage ti @statusbar-icon)
  (when macos?
    (fix-tray-button-image! ti @statusbar-icon)))
```

### 6.5 — Verify

1. Start server from `Winze.app`
2. Confirm icon appears in menu bar
3. Confirm right-click still shows menu (if native menu wired up)
4. Confirm `make run` also shows icon (expected: yes, since same fix applies)
5. Run `make dmg`, install fresh, verify in both light and dark mode

---

## Step 7 — Template Image Tinting (Polish, After Tray is Visible)

Once the tray icon is confirmed appearing, address the template image tinting
so it auto-inverts in dark mode.

7.1. After setting the button image, mark it as a template image via SWT's
Cocoa bridge:

```clojure
(OS/objc_msgSend
  ns-img-id
  (OS/sel_registerName "setTemplate:")
  (long 1))
```

Call this on the NSImage id after `fix-tray-button-image!` sets it on the
button. This makes `SystemUIServer` auto-tint the icon to white in dark mode.

7.2. Do NOT call this on the `handle` of the SWT Image directly — SWT may
re-create the native image on theme reload, losing the template flag. Wrap
in a helper that's called every time the image is set.

---

## REPL Setup for Next Session

```bash
# 1. Delete stale PID (already done, but check)
rm -f ~/.local/share/winze/.pid

# 2. Launch from .app
open /Applications/Winze.app

# 3. Find nREPL port
clj-nrepl-eval --discover-ports

# 4. Verify icon still missing (baseline)
osascript -e 'tell application "System Events" to get every menu bar item of menu bar 2 of process "java"'

# 5. Test fix-tray-button-image! in REPL before writing to file
PORT=<port>
clj-nrepl-eval -p $PORT << 'EOF'
(require '[ui.SWT :refer [sync-exec!]])
(import '[org.eclipse.swt.internal.cocoa OS])
(sync-exec!
  (fn []
    ;; ... fix-tray-button-image! body ...
    ))
EOF

# 6. If icon appears → write the fix to main_window.clj
# 7. make uber → make install → make dmg → verify
```
