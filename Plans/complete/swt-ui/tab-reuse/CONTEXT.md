---
created: 2026-03-30
related: [complete/file-viewer-header/CONTEXT.md, complete/file-viewer/CONTEXT.md]
tags: [swt, ui, tabs, file-viewer, search]
---

# Tab Reuse on File Open — Context

## Problem Statement

Clicking a file path link in a Live Search result card always opens a new tab, even
if the same file is already open. This creates duplicate tabs and loses the user's
scroll position and edit state in the existing tab.

## Current Behavior

The `winze:open-file?` handler in `custom-browser` (main_window.clj:63–76) always:
1. Resolves `abs-path` from `root-uri` + `rel-path`
2. Reads the file, looks up metadata, renders HTML
3. Calls `open-tab!` unconditionally — creates a new wrapper Composite, Browser, and CTabItem

`open-tab!` (main_window.clj:102–152) performs no duplicate check. It always generates
a fresh `tab-id`/`wrapper-id`, replaces the `open-files` entry for that `abs-path`
(overwriting the previous entry's `:wrapper-id`, `:tab-ids`, etc.), and creates new
SWT widgets. The old tab's widgets become orphaned — still visible but disconnected
from the `open-files` registry.

## Existing Infrastructure

Everything needed for tab reuse already exists:

- **`open-files` atom** (resources.clj:150): maps `abs-path → entry`. A simple
  `(get @open-files abs-path)` tells us if the file is already open.

- **`:wrapper-id`** in each entry links to the wrapper Composite stored in `app-props`.
  The CTabFolder's `.getItems` array can be iterated to find the CTabItem whose
  `.getControl` matches the wrapper.

- **`.setSelection`** on CTabFolder accepts either an index or a CTabItem directly.
  Already used at main_window.clj:151 (`(.setSelection folder (dec (.getItemCount folder)))`)
  and main_window.clj:84 (`(.setSelection (element :main-folder) 0)`).

## Desired Behavior

When the `winze:open-file?` handler fires:

1. Resolve `abs-path`
2. Check `(get @open-files abs-path)` — if the file is already open:
   - Find the CTabItem for the existing tab
   - Call `.setSelection` to switch to it
   - Skip file I/O, metadata lookup, and HTML rendering entirely
3. If not open: proceed with current behavior (open new tab)

## Edge Cases

- **Disposed wrapper**: The `open-files` entry might reference a wrapper that was
  disposed (e.g. race with tab close). Guard with `(.isDisposed wrapper)`.
- **Multiple tabs for same file**: Current code replaces the `open-files` entry on
  each `open-tab!` call, so at most one entry per `abs-path` exists. After this
  fix, duplicates won't be created, so this is moot.
- **File changed since tab was opened**: The existing tab shows stale content.
  The file watcher already handles this — `refresh-open-tabs!` updates the
  Browser HTML on `:modify` events. No additional refresh needed.
