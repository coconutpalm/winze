# Winze Documentation

Persistent, searchable project memory for AI-assisted development.

## Table of Contents

1. **[The AI-Centric Planning Workflow](workflow.md)**
   How the three-layer architecture (structured files + vector search +
   CLAUDE.md instructions) gives AI agents persistent memory across
   sessions. Covers the task lifecycle (`dev/` → `complete/`), document
   types, and why this approach works.

2. **[CLAUDE.md Integration Guide](claude-md-guide.md)**
   The exact CLAUDE.md sections that teach Claude Code how to use the
   planning tools — search-first protocol, filesystem watcher behavior,
   archiving completed work. Also covers slash command skills and
   auto-memory integration.

3. **[Setup Guide — Replicate in Your Own Project](setup-guide.md)**
   One-command installation (`make install-winze`), project registration,
   architecture overview, customization, and troubleshooting.

4. **[Search Quality & Relevance Scores](search-quality.md)**
   How relevance percentages work, what score ranges to expect,
   tips for writing effective queries, and embedding model details.

5. **[First-Time Setup Prompt](setup-prompt.md)**
   A copy-paste prompt for Claude Code to automate initial setup.
