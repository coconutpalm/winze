#!/bin/sh
# install.sh — Install Winze Plan Server from a self-contained package.
#
# No external dependencies required (JDK, Babashka, Clojure, etc.).
# Everything needed is bundled in this package.
#
# Usage:
#   ./install.sh              # Install to ~/.local/share/winze/
#   ./install.sh --uninstall  # Remove installation (preserves data)

set -e

# ── Configuration ──────────────────────────────────────────────────

INSTALL_DIR="${HOME}/.local/share/winze"
SKILLS_DIR="${HOME}/.claude/skills"
RULES_DIR="${HOME}/.claude/rules"
SKILL_NAMES="search-plans index-plans recent-plans related-plans register-plans list-plan-roots help-plans"

# Locate the package directory (where this script lives)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── Helpers ────────────────────────────────────────────────────────

info()  { printf '  %s\n' "$*"; }
ok()    { printf '  \342\234\223 %s\n' "$*"; }  # ✓
err()   { printf '  \342\234\227 %s\n' "$*" >&2; }  # ✗
die()   { err "$*"; exit 1; }

# ── Uninstall ──────────────────────────────────────────────────────

if [ "$1" = "--uninstall" ]; then
  echo "Uninstalling Winze..."
  # Deregister MCP server
  if command -v claude >/dev/null 2>&1; then
    claude mcp remove --scope user winze 2>/dev/null && ok "MCP server deregistered" || true
  fi
  # Remove skills
  for skill in $SKILL_NAMES; do
    rm -rf "${SKILLS_DIR}/${skill}"
  done
  ok "Skills removed"
  # Remove rules (only winze-installed ones)
  rm -f "${RULES_DIR}/swt-development.md"
  ok "Rules removed"
  # Stop server
  if [ -f "${INSTALL_DIR}/.pid" ]; then
    kill "$(cat "${INSTALL_DIR}/.pid")" 2>/dev/null || true
    rm -f "${INSTALL_DIR}/.nrepl-port" "${INSTALL_DIR}/.pid"
    ok "Server stopped"
  fi
  # Remove installed files (preserve .datalevin data and logs)
  rm -rf "${INSTALL_DIR}/jre" "${INSTALL_DIR}/lib" "${INSTALL_DIR}/bin"
  rm -f "${INSTALL_DIR}/winze-server.jar" "${INSTALL_DIR}/mcp-proxy.clj"
  ok "Installation removed"
  echo ""
  echo "  Data preserved at ${INSTALL_DIR}/.datalevin/"
  echo "  To remove all data: rm -rf ${INSTALL_DIR}"
  exit 0
fi

# ── Validate package ──────────────────────────────────────────────

echo ""
echo "Installing Winze Plan Server..."
echo ""

[ -d "${SCRIPT_DIR}/jre" ]                || die "Missing jre/ — is this a valid package?"
[ -f "${SCRIPT_DIR}/lib/winze-server.jar" ] || die "Missing lib/winze-server.jar"
[ -f "${SCRIPT_DIR}/bin/mcp-proxy.clj" ]  || die "Missing bin/mcp-proxy.clj"
[ -d "${SCRIPT_DIR}/skills" ]             || die "Missing skills/"
[ -d "${SCRIPT_DIR}/rules" ]              || die "Missing rules/"

# Check for bb (may be named bb or bb.exe)
if [ -f "${SCRIPT_DIR}/bin/bb" ]; then
  BB_BIN="bb"
elif [ -f "${SCRIPT_DIR}/bin/bb.exe" ]; then
  BB_BIN="bb.exe"
else
  die "Missing bin/bb — is this a valid package?"
fi

# ── Stop existing server ──────────────────────────────────────────

if [ -f "${INSTALL_DIR}/.pid" ]; then
  OLD_PID="$(cat "${INSTALL_DIR}/.pid")"
  if kill -0 "$OLD_PID" 2>/dev/null; then
    kill "$OLD_PID" 2>/dev/null || true
    sleep 1
    ok "Stopped existing server (PID ${OLD_PID})"
  fi
  rm -f "${INSTALL_DIR}/.nrepl-port" "${INSTALL_DIR}/.pid"
fi

# ── Copy files ────────────────────────────────────────────────────

mkdir -p "${INSTALL_DIR}/jre" "${INSTALL_DIR}/lib" "${INSTALL_DIR}/bin"

# JRE (full directory replace)
rm -rf "${INSTALL_DIR}/jre"
cp -R "${SCRIPT_DIR}/jre" "${INSTALL_DIR}/jre"
ok "JRE installed"

# Uberjar
cp "${SCRIPT_DIR}/lib/winze-server.jar" "${INSTALL_DIR}/lib/winze-server.jar"
ok "Server JAR installed"

# Babashka + proxy + launchers
cp "${SCRIPT_DIR}/bin/${BB_BIN}" "${INSTALL_DIR}/bin/${BB_BIN}"
chmod +x "${INSTALL_DIR}/bin/${BB_BIN}"
cp "${SCRIPT_DIR}/bin/mcp-proxy.clj" "${INSTALL_DIR}/bin/mcp-proxy.clj"
cp "${SCRIPT_DIR}/bin/winze-server" "${INSTALL_DIR}/bin/winze-server"
cp "${SCRIPT_DIR}/bin/winze-mcp" "${INSTALL_DIR}/bin/winze-mcp"
chmod +x "${INSTALL_DIR}/bin/winze-server" "${INSTALL_DIR}/bin/winze-mcp"
ok "Binaries installed"

# Legacy compat: also copy JAR and proxy to DATA_DIR root (proxy expects them there)
cp "${SCRIPT_DIR}/lib/winze-server.jar" "${INSTALL_DIR}/winze-server.jar"
cp "${SCRIPT_DIR}/bin/mcp-proxy.clj" "${INSTALL_DIR}/mcp-proxy.clj"

# ── Register MCP server ──────────────────────────────────────────

if command -v claude >/dev/null 2>&1; then
  # Remove legacy servers
  for name in planning planning-tool clj-llm-memory; do
    for scope in user project; do
      claude mcp remove --scope "$scope" "$name" 2>/dev/null || true
    done
  done
  # Deregister old winze entry (idempotent re-register)
  claude mcp remove --scope user winze 2>/dev/null || true
  # Register with bundled bb + proxy
  claude mcp add --scope user winze -- "${INSTALL_DIR}/bin/winze-mcp"
  ok "MCP server registered (global)"
else
  err "Claude Code CLI not found — skipping MCP registration."
  info "Run manually: claude mcp add --scope user winze -- ${INSTALL_DIR}/bin/winze-mcp"
fi

# ── Install skills ────────────────────────────────────────────────

for skill in $SKILL_NAMES; do
  mkdir -p "${SKILLS_DIR}/${skill}"
  cp "${SCRIPT_DIR}/skills/${skill}/SKILL.md" "${SKILLS_DIR}/${skill}/SKILL.md"
done
ok "Skills installed (${SKILLS_DIR})"

# ── Install rules ─────────────────────────────────────────────────

mkdir -p "${RULES_DIR}"
for rule in "${SCRIPT_DIR}"/rules/*.md; do
  [ -f "$rule" ] && cp "$rule" "${RULES_DIR}/$(basename "$rule")"
done
ok "Rules installed (${RULES_DIR})"

# ── Summary ───────────────────────────────────────────────────────

echo ""
echo "  Installation complete!"
echo ""
echo "  Install dir:  ${INSTALL_DIR}"
echo "  JRE:          ${INSTALL_DIR}/jre/bin/java"
echo "  Babashka:     ${INSTALL_DIR}/bin/${BB_BIN}"
echo "  Server JAR:   ${INSTALL_DIR}/lib/winze-server.jar"
echo ""
echo "  The Plan Server starts automatically on first MCP tool call."
echo "  New projects must run /register-plans before search works."
echo ""
echo "  Available commands:"
echo "    /help-plans              — list all available plans commands"
echo "    /register-plans [dir]    — register project's plans directory"
echo "    /search-plans <query>    — semantic search over plans"
echo "    /index-plans [reset]     — reconcile or full reindex"
echo "    /recent-plans [days]     — list recently modified documents"
echo "    /related-plans <group>   — find all documents in a group"
echo "    /list-plan-roots         — show all registered roots"
echo ""
echo "  To uninstall: $0 --uninstall"
echo ""
