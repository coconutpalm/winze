---
created: 2026-03-27
doc_type: context
group: global-esc
tags: [swt, ui, keyboard, event-filter]
---

# Global Esc Key Handler — Context

## Goal

Make the Esc key work globally in the Winze UI: pressing Esc from any widget
clears the search field and returns focus to it. Currently, Esc is only handled
on the search text widget itself via `SWT/CANCEL` in an `on e/widget-selected`
handler.

## Current State

In `main_window.clj`, the search text widget (lines 143–159) has three event
handlers:

1. `on e/modify-text` — triggers live search on every keystroke
2. `on e/widget-default-selected` — Enter opens a new tab with results
3. `on e/widget-selected` (with `SWT/CANCEL` check) — clears the search text
   when Esc is pressed **while the search field has focus**

The third handler only fires when the search text widget itself has focus. If
the user is focused on a browser tab or any other widget, Esc does nothing.

## Design: Display Event Filter

SWT's `Display.addFilter(eventType, listener)` installs a global event filter
that intercepts events **before** they reach any widget. This is the correct
mechanism for a global hotkey.

### Key pattern: async-exec for keypress "transactions"

A physical keypress isn't a single SWT event — it spawns multiple events
(KeyDown, KeyUp, Traverse, etc.) depending on the key and the platform. If we
mutate the UI (clear text, move focus) synchronously inside the filter, we
interrupt the remaining events in the keypress "transaction", which can cause
platform-specific misbehavior.

The solution: set `event.doit = false` immediately (to consume the event), then
queue the real work via `async-exec!`. This lets all pending events in the
current batch complete before our mutations run.

### Implementation location

The `defmain` block in `main-window/main-window` runs after the entire UI tree
is constructed. Its `parent` parameter is the Display object. This is the
natural place to install global event filters.

## Relevant Files

- `winze-server/src/llm_memory/ui/main_window.clj` — main window definition
- CDT `ui.SWT` — provides `async-exec!`, `display` atom, `defmain` macro
- `org.eclipse.swt.SWT` — `SWT/KeyDown`, `SWT/ESC` constants
- `org.eclipse.swt.widgets.Listener` — already imported

## Testing Approach

Construct an SWT `Event` object with `type = SWT/KeyDown` and `keyCode = SWT/ESC`,
then post it to the Display via `(.post @display event)`. This simulates a
global Esc keypress without physical keyboard input.
