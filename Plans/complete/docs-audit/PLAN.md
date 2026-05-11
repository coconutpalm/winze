# Documentation Audit — Plan

All steps complete.

## Step 1: Rewrite root README ✓

Restructured `README.md` to follow problem → solution → proof → install arc:

1. Tagline: "Persistent project memory for AI coding assistants"
2. One-paragraph value prop explaining the problem (ephemeral context windows) and solution
3. Screenshot (path updated to `winze-server/docs/winze-screenshot.png`)
4. "One knowledge base, two interfaces" — the dual-audience benefit
5. Key differentiators: no API keys, in-JVM embeddings, filesystem watcher, multi-project, native UI, Claude Code integration
6. "Why not just..." comparison table (condensed from workflow.md)
7. Quick install with JDK precondition note
8. Architecture diagram
9. Subproject links moved to bottom
10. Requirements simplified (JDK only, Clojure/Babashka handled by build)

## Step 2: Fix factual issues in winze-server README ✓

- Files section: `winze-server.jar` → `lib/winze-server.jar`, added `jre/`, `bin/` tree
- Install description: updated to reflect full packaging pipeline
- Rules: mentioned in install description
- `make install` vs `make install-winze` distinction clarified
- JDK precondition added to Install section
- Requirements restructured into build-time / run-time / integration

## Step 3: Fix toc.md title ✓

"Planning Tool Documentation" → "Winze Documentation"

## Step 4: Fix setup-guide.md ✓

- Removed legacy Python planning-tool reference from install steps
- Updated prerequisites: JDK only, Clojure/Babashka handled by build
- Clarified INDEX.md/STATUS.md regeneration vs search index (in workflow.md)

## Step 5: Add platform support to server README ✓

Platform support table added: macOS ARM64, Linux AMD64/ARM64, Windows AMD64. macOS x86_64 explicitly noted as unsupported (dtlvnative limitation).

## Step 6: Document the package target ✓

Development section updated with `make package` description, platform auto-detection, and `PLATFORM=` override.

## Step 7: Remove duplicate screenshot ✓

Deleted `docs/winze-screenshot.png` and empty `docs/` directory. Root README updated to reference `winze-server/docs/winze-screenshot.png`.

## Additional: install-mcp → install-winze rename

Renamed `install-mcp` / `uninstall-mcp` to `install-winze` / `uninstall-winze` across Makefile and 14 active documentation files. Completed plans left as historical record.
