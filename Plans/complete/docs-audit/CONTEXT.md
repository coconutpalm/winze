# Documentation Audit — Context

## Summary

Full audit of winze README files, docs/ folder, and Makefile revealed the documentation was well-written and thorough but had structural issues (root README undersold the product), factual staleness (jar path, install target description), and missing coverage (platform support, package target, UI features in root README).

All issues have been addressed. The `install-mcp` / `uninstall-mcp` targets were also renamed to `install-winze` / `uninstall-winze` during this work.

## What Was Fixed

### Structural
- **Root README rewritten** with value-prop lead, "one knowledge base, two interfaces" section, key features list, "why not just..." comparison table, screenshot, then architecture/subprojects at bottom
- **toc.md title** changed from "Planning Tool Documentation" to "Winze Documentation"

### Factual
1. **Jar path**: Server README Files section updated to show `lib/winze-server.jar` and full `jre/`, `bin/` tree
2. **`install-winze` description**: Updated to reflect full packaging pipeline (jlink JRE, Babashka download, launcher scripts, rules)
3. **Rules installation**: Now mentioned in install description and Files section
4. **Legacy Python reference**: Removed from setup-guide.md install steps
5. **Duplicate screenshot**: Removed `docs/winze-screenshot.png` (and empty directory); root README points to `winze-server/docs/` copy
6. **INDEX.md/STATUS.md vs index**: Clarified in workflow.md that `/index-plans` on task completion only regenerates dashboard files (search index already current via watcher)

### Missing Documentation Added
1. **Platform support table** in server README (macOS ARM64, Linux AMD64/ARM64, Windows AMD64; macOS x86_64 not supported)
2. **`make package` target** documented in Development section with platform override
3. **`make install` vs `make install-winze`** distinction clarified
4. **Native UI** described in root README key features (live search, system tray, file viewer, tabs)
5. **JDK precondition** noted in Install sections of both READMEs and setup-guide.md
6. **Prerequisites updated**: setup-guide.md simplified — Clojure CLI and Babashka handled by build

### Rename
- `install-mcp` → `install-winze` and `uninstall-mcp` → `uninstall-winze` across Makefile and all active documentation (14 files). Completed plans in `Plans/complete/` left as historical record.

## Not Done
- **CHANGELOG**: Identified as a suggestion but not implemented — lower priority, can be added when versioned releases begin.

## Files Changed

| File | Changes |
|------|---------|
| `README.md` (root) | Complete rewrite |
| `CLAUDE.md` | Target name rename |
| `winze-server/Makefile` | Target rename + prerequisite checks (separate work item) |
| `winze-server/README.md` | Jar path, install description, Files section, platform support, package docs, requirements, JDK precondition |
| `winze-server/docs/toc.md` | Title fix |
| `winze-server/docs/setup-guide.md` | Prerequisites, install steps, legacy Python removal |
| `winze-server/docs/setup-prompt.md` | Target rename |
| `winze-server/docs/claude-md-guide.md` | Target rename |
| `winze-server/docs/workflow.md` | INDEX.md/STATUS.md clarification |
| `docs/winze-screenshot.png` | Deleted (duplicate) |
| `docs/` | Removed (empty) |
