# install.ps1 — Install Winze Plan Server on Windows from a self-contained package.
#
# No external dependencies required (JDK, Babashka, Clojure, etc.).
# Everything needed is bundled in this package.
#
# Usage:
#   .\install.ps1              # Install to %LOCALAPPDATA%\winze\
#   .\install.ps1 -Uninstall   # Remove installation (preserves data)

param(
    [switch]$Uninstall
)

$ErrorActionPreference = "Stop"

# ── Configuration ──────────────────────────────────────────────────

$InstallDir = Join-Path $env:LOCALAPPDATA "winze"
$SkillsDir  = Join-Path $env:USERPROFILE ".claude" "skills"
$SkillNames = @("search-plans", "index-plans", "recent-plans", "related-plans",
                "register-plans", "list-plan-roots", "help-plans")
$ScriptDir  = Split-Path -Parent $MyInvocation.MyCommand.Path

# ── Helpers ────────────────────────────────────────────────────────

function Info($msg)  { Write-Host "  $msg" }
function Ok($msg)    { Write-Host "  ✓ $msg" -ForegroundColor Green }
function Err($msg)   { Write-Host "  ✗ $msg" -ForegroundColor Red }

# ── Uninstall ──────────────────────────────────────────────────────

if ($Uninstall) {
    Write-Host "Uninstalling Winze..."
    if (Get-Command claude -ErrorAction SilentlyContinue) {
        claude mcp remove --scope user winze 2>$null
        Ok "MCP server deregistered"
    }
    foreach ($skill in $SkillNames) {
        $p = Join-Path $SkillsDir $skill
        if (Test-Path $p) { Remove-Item -Recurse -Force $p }
    }
    Ok "Skills removed"
    $pidFile = Join-Path $InstallDir ".pid"
    if (Test-Path $pidFile) {
        $pid = Get-Content $pidFile
        Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue
        Remove-Item (Join-Path $InstallDir ".nrepl-port"), $pidFile -Force -ErrorAction SilentlyContinue
        Ok "Server stopped"
    }
    Remove-Item (Join-Path $InstallDir "jre"), (Join-Path $InstallDir "lib"), (Join-Path $InstallDir "bin") -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $InstallDir "winze-server.jar"), (Join-Path $InstallDir "mcp-proxy.clj") -Force -ErrorAction SilentlyContinue
    Ok "Installation removed"
    Write-Host ""
    Info "Data preserved at $InstallDir\.datalevin\"
    Info "To remove all data: Remove-Item -Recurse -Force '$InstallDir'"
    exit 0
}

# ── Validate package ──────────────────────────────────────────────

Write-Host ""
Write-Host "Installing Winze Plan Server..."
Write-Host ""

if (-not (Test-Path (Join-Path $ScriptDir "jre")))                { throw "Missing jre/ — is this a valid package?" }
if (-not (Test-Path (Join-Path $ScriptDir "lib\winze-server.jar"))) { throw "Missing lib\winze-server.jar" }
if (-not (Test-Path (Join-Path $ScriptDir "bin\mcp-proxy.clj")))  { throw "Missing bin\mcp-proxy.clj" }
if (-not (Test-Path (Join-Path $ScriptDir "bin\bb.exe")))         { throw "Missing bin\bb.exe" }

# ── Stop existing server ──────────────────────────────────────────

$pidFile = Join-Path $InstallDir ".pid"
if (Test-Path $pidFile) {
    $oldPid = Get-Content $pidFile
    Stop-Process -Id $oldPid -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1
    Remove-Item (Join-Path $InstallDir ".nrepl-port"), $pidFile -Force -ErrorAction SilentlyContinue
    Ok "Stopped existing server (PID $oldPid)"
}

# ── Copy files ────────────────────────────────────────────────────

New-Item -ItemType Directory -Force -Path (Join-Path $InstallDir "jre"), (Join-Path $InstallDir "lib"), (Join-Path $InstallDir "bin") | Out-Null

# JRE
if (Test-Path (Join-Path $InstallDir "jre")) { Remove-Item -Recurse -Force (Join-Path $InstallDir "jre") }
Copy-Item -Recurse (Join-Path $ScriptDir "jre") (Join-Path $InstallDir "jre")
Ok "JRE installed"

# Uberjar
Copy-Item (Join-Path $ScriptDir "lib\winze-server.jar") (Join-Path $InstallDir "lib\winze-server.jar") -Force
Ok "Server JAR installed"

# Babashka + proxy + launchers
Copy-Item (Join-Path $ScriptDir "bin\bb.exe") (Join-Path $InstallDir "bin\bb.exe") -Force
Copy-Item (Join-Path $ScriptDir "bin\mcp-proxy.clj") (Join-Path $InstallDir "bin\mcp-proxy.clj") -Force
Copy-Item (Join-Path $ScriptDir "bin\winze-server.bat") (Join-Path $InstallDir "bin\winze-server.bat") -Force
Copy-Item (Join-Path $ScriptDir "bin\winze-mcp.bat") (Join-Path $InstallDir "bin\winze-mcp.bat") -Force
Ok "Binaries installed"

# Legacy compat
Copy-Item (Join-Path $ScriptDir "lib\winze-server.jar") (Join-Path $InstallDir "winze-server.jar") -Force
Copy-Item (Join-Path $ScriptDir "bin\mcp-proxy.clj") (Join-Path $InstallDir "mcp-proxy.clj") -Force

# ── Register MCP server ──────────────────────────────────────────

if (Get-Command claude -ErrorAction SilentlyContinue) {
    foreach ($name in @("planning", "planning-tool", "clj-llm-memory")) {
        foreach ($scope in @("user", "project")) {
            claude mcp remove --scope $scope $name 2>$null
        }
    }
    claude mcp remove --scope user winze 2>$null
    $mcpCmd = Join-Path $InstallDir "bin\winze-mcp.bat"
    claude mcp add --scope user winze -- $mcpCmd
    Ok "MCP server registered (global)"
} else {
    Err "Claude Code CLI not found — skipping MCP registration."
    Info "Run manually: claude mcp add --scope user winze -- $InstallDir\bin\winze-mcp.bat"
}

# ── Install skills ────────────────────────────────────────────────

foreach ($skill in $SkillNames) {
    $dest = Join-Path $SkillsDir $skill
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Copy-Item (Join-Path $ScriptDir "skills\$skill\SKILL.md") (Join-Path $dest "SKILL.md") -Force
}
Ok "Skills installed ($SkillsDir)"

# ── Summary ───────────────────────────────────────────────────────

Write-Host ""
Write-Host "  Installation complete!"
Write-Host ""
Info "Install dir:  $InstallDir"
Info "JRE:          $InstallDir\jre\bin\java.exe"
Info "Babashka:     $InstallDir\bin\bb.exe"
Info "Server JAR:   $InstallDir\lib\winze-server.jar"
Write-Host ""
Info "The Plan Server starts automatically on first MCP tool call."
Info "New projects must run /register-plans before search works."
Write-Host ""
Info "To uninstall: .\install.ps1 -Uninstall"
Write-Host ""
