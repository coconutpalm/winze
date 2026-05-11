---
created: 2026-04-14
tags: proxy, mcp-proxy, startup, jar, install
---

# Proxy Server JAR Path Mismatch — Plan

Companion: [PROXY-JAR-PATH-CONTEXT.md](PROXY-JAR-PATH-CONTEXT.md)

## Single Step

In `winze-server/mcp-proxy.clj`, fix the `bundled-jar` path:

```clojure
;; Before:
(def ^:private bundled-jar
  (when script-dir
    (let [f (io/file script-dir ".." "lib" "winze-server.jar")]
      (when (.exists f) (.getCanonicalPath f)))))

;; After:
(def ^:private bundled-jar
  (when script-dir
    (let [f (io/file script-dir "lib" "winze-server.jar")]
      (when (.exists f) (.getCanonicalPath f)))))
```

Remove the `".."` component. `script-dir` is already the data dir
(`~/.local/share/winze/`); the JAR lives at `lib/winze-server.jar`
relative to it.

## Deploy

```bash
cd winze-server && make install
```

The Makefile copies the updated `mcp-proxy.clj` to `~/.local/share/winze/`
and stops the running server. The proxy auto-starts the server on the next
MCP call.

## Verify

```bash
# Start a fresh Claude Code session or trigger a plans tool call
# Confirm the server auto-starts without needing manual make run
cat ~/.local/share/winze/.nrepl-port   # should appear within ~15s
grep "plan server ready" ~/.local/share/winze/plan-server.log
```
