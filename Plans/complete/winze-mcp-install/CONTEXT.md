# Codex: Winze MCP Install Context

Goal: install the local `winze/winze-server` MCP proxy into Codex itself.

Key findings:

- `winze/winze-server/mcp-proxy.clj` is the MCP stdio proxy. It starts or reconnects to the long-lived JVM plan server and exposes the tools `search_plans`, `list_plans`, `related_plans`, `recent_plans`, `plans_status`, `index_plans`, `register_plans`, and `list_plan_roots`.
- The lightweight dev install path is `make install` from `winze/winze-server/`. That copies:
  - `target/winze-server.jar` to `~/.local/share/winze/lib/winze-server.jar`
  - `mcp-proxy.clj` to `~/.local/share/winze/mcp-proxy.clj`
  - SWT rule docs to `~/.claude/rules/`
- The proxy is designed to work in that dev-install layout and can be launched with:
  - `bb ~/.local/share/winze/mcp-proxy.clj`
- The full package path (`make install-winze`) additionally bundles a JRE, downloads Babashka, installs Claude skills, and registers with the Claude CLI. That is not required for Codex if `java`, `clj`, and `bb` already exist.
- This machine already has the needed prerequisites:
  - Java 21
  - Clojure CLI
  - Babashka
- Codex has its own MCP registry via `codex mcp ...`. `codex mcp list` showed no servers configured before this task.

Recommended Codex registration command after `make install`:

`codex mcp add winze -- bb ~/.local/share/winze/mcp-proxy.clj`
