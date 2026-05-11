# Editor Cleanup — Content Assist Crash Context

**Parent**: [_EDITOR-CLEANUP-CONTEXT.md](_EDITOR-CLEANUP-CONTEXT.md) (Issue 1 — Content Assist)

## Incident

On 2026-04-14, the Winze UI crashed with an `SWTException: Widget is disposed`
while typing `[[` in the `home.md` editor to test the wiki-draft content assist
popup. The content assist popup appeared, but the Table items showed no visible
images (the offscreen screenshot rendering wasn't working). The crash followed
shortly after.

The crash was not captured in `plan-server.log` because logback had rotated
the file — stderr continued writing to the old file descriptor
(`plan-server.2026-04-11.0.log`) but the crash output was either lost in the
rotation gap or went to a buffer that was never flushed before the JVM exited.

## Root Cause: Two bugs working together

### Bug A — No disposed-widget guard in the render loop

`render-row-images!` (content_assist.clj:173-203) uses a continuation-based
trampoline where each `ProgressListener.completed` callback captures the
`table` reference in a closure. The callback checks `render-generation` for
staleness but **never checks if the Table is disposed**.

When the popup Shell closes for any reason while the render loop is in flight,
the next `completed` callback tries `(TableItem. table SWT/NONE)` on a disposed
Table, throwing `SWTException: Widget is disposed`.

The generation counter only protects against new searches superseding old ones.
It is **not incremented when the popup closes**, so it provides zero protection
against popup dismissal during rendering.

### Bug B — Offscreen Shell `.open` steals focus, causing premature popup dismissal

`ensure-offscreen-browser!` (content_assist.clj:85-109) calls `.open sh` on the
offscreen Shell (line 107). `Shell.open()` **activates** the Shell — it brings
it to the front in the window manager and gives it focus. On macOS, this causes
the popup Shell to fire `shellDeactivated`, which is wired to:

```clojure
(shellDeactivated [_e]
  (async-exec! #(when (popup-open?) (cancel!))))
```

This queues the popup to close itself. The crash sequence:

1. User types `[[` → content assist popup opens
2. Search fires → UI thread calls `ensure-offscreen-browser!`
3. `.open sh` activates the offscreen Shell → popup's `shellDeactivated` fires
4. `async-exec!` queues `cancel!` (popup closure)
5. `render-row-images!` calls `.setText browser html` (first render iteration)
6. Control returns to the SWT event loop
7. The queued `cancel!` runs → `.close` on popup Shell → Table disposed
8. `ProgressListener.completed` fires → `(TableItem. table SWT/NONE)` → **crash**

### Why images were blank

Separately from the crash, `Browser.print(GC)` may not work for a Shell
positioned beyond all monitor bounds on macOS. WebKit may skip compositing
for content it considers non-visible, producing blank captures. However, the
user may never have seen rendered images at all — if Bug B triggered
immediately (offscreen Shell stealing focus), the popup would have been
dismissed before the first `completed` callback even fired, and the crash
would follow.

## Key SWT Concepts

- **`Shell.open()` vs `.setVisible(true)`**: `.open()` both makes a Shell
  visible AND activates it (gives it input focus). For background/utility
  Shells that should never receive focus, `.setVisible(true)` shows the Shell
  without activating it.

- **ProgressListener timing**: `ProgressListener.completed` fires
  asynchronously on the UI thread, dispatched by the SWT event loop. There is
  always a window between when a widget is disposed and when a queued listener
  callback runs. Widget disposal checks are mandatory in every async callback
  that touches widgets.

- **Generation counter scope**: The `render-generation` atom currently only
  guards against search-supersedes-search. It must also be incremented on
  popup close to guard against renders outliving the popup.

## Files Affected

| File | Issue |
|------|-------|
| `content_assist.clj:107` | `.open sh` activates offscreen Shell, stealing focus |
| `content_assist.clj:190-201` | `completed` callback lacks disposed-widget checks |
| `content_assist.clj:564-569` | Shell DisposeListener doesn't increment `render-generation` |
