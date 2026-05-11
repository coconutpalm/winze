Winze Plan Server — Semantic Search for Planning Documents
===========================================================

A self-contained MCP server that provides semantic search over markdown
planning documents via Claude Code.

No external dependencies required — Java, Babashka, and all native
libraries are bundled.


Quick Start
-----------

1. Run the installer:

   macOS/Linux:   ./install.sh
   Windows:       .\install.ps1

2. Open Claude Code in any project and register your plans directory:

   /register-plans Plans

3. Search your documents:

   /search-plans "deployment architecture"


Available Commands
------------------

  /help-plans              — List all available plans commands
  /register-plans [dir]    — Register project's plans directory
  /list-plan-roots         — Show all registered roots
  /search-plans <query>    — Semantic search over plans
  /index-plans [reset]     — Reconcile or full reindex
  /recent-plans [days]     — List recently modified documents
  /related-plans <group>   — Find all documents in a group

The Plan Server starts automatically on first MCP tool call.


Uninstall
---------

  macOS/Linux:   ./install.sh --uninstall
  Windows:       .\install.ps1 -Uninstall

Data (the vector index at ~/.local/share/winze/.datalevin/) is
preserved on uninstall. Delete the directory manually if unwanted.


macOS Gatekeeper Note
---------------------

On macOS, unsigned binaries may trigger a Gatekeeper warning on first
run. To bypass:

  xattr -cr ~/.local/share/winze/

Or right-click the binary in Finder → Open.
