@echo off
REM Launch the Winze MCP proxy using the bundled Babashka.
set DIR=%~dp0..
"%DIR%\bin\bb.exe" "%DIR%\bin\mcp-proxy.clj" %*
