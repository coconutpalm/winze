# Makefile Prerequisite Checks — Context

## Problem

The Makefile assumes both `java` (JDK 21+) and `clj` (Clojure CLI) are already installed. If either is missing, the user gets an opaque shell error (e.g. `clj: command not found`) with no guidance on what to install or how.

This is the most common failure mode for first-time users running `make install-winze`.

## Current State

The Makefile has platform auto-detection (lines 11–29) that errors clearly when an unsupported platform is detected — but no equivalent checks for tool prerequisites. Every target that calls `clj` (`clean`, `uber`, `install`, `package`, `install-winze`) will fail silently if Clojure isn't installed.

Babashka is partially handled: `make package` (and therefore `make install-winze`) downloads it automatically via the `download-bb` target. However, `make install` — the dev shortcut — does NOT download or check for Babashka, even though the installed proxy script requires `bb` at runtime. A developer running `make install` without system Babashka gets a working-looking install that silently fails when the MCP proxy tries to start.

## Requirements

### JRE / JDK

- A JDK 21+ must be on `PATH` for the build (needed for `clj`, `jlink`, and running the uberjar)
- If `java` is not found or the version is below 21, the Makefile should **error with a clear message** directing the user to install Temurin
- We should NOT auto-install a JDK — it's a large, system-level dependency with multiple distribution choices; the user should pick

### Clojure CLI

- `clj` is needed for every build target
- If `clj` is not found, the Makefile should **prompt the user** for permission to install it
- Installation method: the official Clojure installer script from https://clojure.org/guides/install_clojure
  - macOS/Linux: `curl -L -O https://github.com/clojure/brew-install/releases/latest/download/posix-install.sh && chmod +x posix-install.sh && sudo ./posix-install.sh` (or the brew alternative)
  - The installer requires `java` to already be present (another reason JDK check must come first)
- The prompt must be interactive — a headless CI build should be able to pre-install `clj` and skip the prompt

## Design Constraints

- Make targets are not scripts — complex logic with prompts requires a shell recipe, not Make conditionals
- The check should run **once at the start** of any build-dependent target, not repeated per target
- The check should not run for targets that don't need these tools (e.g. `clean` when used alone, `uninstall-winze`)
- `$(shell ...)` evaluations in Make happen at parse time for variable assignments but at recipe time for recipes — the prompt must be in a recipe, not a variable assignment

## Clojure Install Methods

### POSIX (macOS / Linux)
```bash
curl -L -O https://github.com/clojure/brew-install/releases/latest/download/posix-install.sh
chmod +x posix-install.sh
sudo ./posix-install.sh
```

### macOS via Homebrew
```bash
brew install clojure/tools/clojure
```

### Linux package managers
```bash
# Not recommended — often out of date
```

The POSIX installer is the most portable option. It installs to `/usr/local/bin/` by default (configurable via `--prefix`).
