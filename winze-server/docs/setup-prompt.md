# First-Time Setup Prompt

Copy and paste the block below into Claude Code to have it set up the
planning tool automatically.

---

```
I need you to set up the Winze MCP server for this project.
The source is in `winze-server/` (adjust the path if your clone is
elsewhere). Please do the following steps in order, stopping and
telling me what happened if anything fails:

1. Check that prerequisites are installed:
   - JDK 21+ (`java -version`)
   - Babashka (`bb --version`)
   If either is missing, stop and tell me — I need to install them first.

2. Build and install:
       cd winze-server && make install-winze

   This builds the uberjar, installs it to ~/.local/share/winze/,
   registers the MCP server with Claude Code, and installs all skills.

3. Tell me to restart VS Code. The MCP server connects on restart.

4. After restart, register this project's Plans/ directory:
       /register-plans Plans

5. Verify everything works:
       /list-plan-roots         — should show this project registered
       /search-plans test       — should return results from Plans/

If /search-plans returns no results, run /index-plans to trigger a
manual reconciliation.
```
