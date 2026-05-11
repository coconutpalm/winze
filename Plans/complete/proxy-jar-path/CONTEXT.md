---
created: 2026-04-14
tags: proxy, mcp-proxy, startup, jar, install
---

# Proxy Server JAR Path Mismatch — Context

## Problem

After `make install` the winze server does not auto-start when the MCP proxy
receives a tool call. The proxy silently falls back to the old JAR
(`~/.local/share/winze/winze-server.jar`) instead of using the newly installed
one at `~/.local/share/winze/lib/winze-server.jar`.

## How the Proxy Resolves the JAR

`mcp-proxy.clj` has a two-stage fallback (lines 39–50):

```clojure
(def ^:private bundled-jar
  (when script-dir
    (let [f (io/file script-dir ".." "lib" "winze-server.jar")]
      (when (.exists f) (.getCanonicalPath f)))))

(def server-jar (or bundled-jar (str data-dir "/winze-server.jar")))
```

`script-dir` is the directory containing the proxy script itself.
The proxy is installed at `~/.local/share/winze/mcp-proxy.clj`, so
`script-dir` = `~/.local/share/winze/`.

`bundled-jar` resolves to:
```
~/.local/share/winze/../lib/winze-server.jar
= ~/.local/share/lib/winze-server.jar   ← does NOT exist
```

Because the bundled path misses, `server-jar` falls back to:
```
~/.local/share/winze/winze-server.jar   ← old JAR (not updated by make install)
```

## What `make install` Actually Does

```makefile
mkdir -p $(DATA_DIR)/lib
cp $(UBER_JAR) $(DATA_DIR)/lib/winze-server.jar
```

Writes to `~/.local/share/winze/lib/winze-server.jar` — which is NEVER
reached by the bundled-jar check because the proxy is not in `bin/`.

The bundled layout (`bin/mcp-proxy.clj` + `lib/winze-server.jar`) was designed
for a packaged distribution where both live under the same parent directory.
In the current dev install, the proxy is at the top level (`winze/`) and the
JAR in a subdirectory (`winze/lib/`), so `script-dir/../lib/` misses.

## Options

### Option A — Update `make install` to also copy to the flat path (minimal)

```makefile
cp $(UBER_JAR) $(DATA_DIR)/lib/winze-server.jar
cp $(UBER_JAR) $(DATA_DIR)/winze-server.jar     # keeps legacy fallback working
```

No changes to the proxy. Both layouts satisfied.

### Option B — Move the proxy to `bin/` (aligns with bundled layout)

```makefile
mkdir -p $(DATA_DIR)/bin
cp $(PROXY) $(DATA_DIR)/bin/$(PROXY)
```

And update `mcp-proxy.clj`'s auto-start command to reference `bin/mcp-proxy.clj`.
This is the "right" long-term layout but requires updating the MCP registration
(which points Claude at the proxy script path).

### Option C — Fix `bundled-jar` path in `mcp-proxy.clj` (proxy-side fix)

Change the proxy to look for the JAR relative to `script-dir` directly (not
via `../lib/`):

```clojure
(def ^:private bundled-jar
  (when script-dir
    (let [f (io/file script-dir "lib" "winze-server.jar")]   ; was: ".." "lib"
      (when (.exists f) (.getCanonicalPath f)))))
```

`~/.local/share/winze/lib/winze-server.jar` ✓

No Makefile changes needed. Simpler fix but modifies the proxy.

## Recommendation

**Option C** — fix the proxy path. It's a one-line change, requires no
Makefile restructuring, and correctly models the actual install layout
(`winze/` is the data dir; `lib/` is a subdirectory of it).

Option A (double-copy) is a workaround. Option B (move proxy to `bin/`) is
a larger change with MCP registration implications.

## Key Files

| File | Change |
|------|--------|
| `winze-server/mcp-proxy.clj` | Fix `bundled-jar` path: `script-dir "lib"` not `script-dir ".." "lib"` |
