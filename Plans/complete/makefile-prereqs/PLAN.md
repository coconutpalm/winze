# Makefile Prerequisite Checks — Plan

## Step 1: Add a `check-java` guard target

Create a `.PHONY` target `check-java` that:

1. Runs `java -version 2>&1` and checks the exit code
2. If `java` is not found: print a clear error message with install instructions and exit 1
   ```
   Error: JDK 21+ is required but 'java' was not found on PATH.
   Install Eclipse Temurin: https://adoptium.net/
     macOS:  brew install temurin@21
     Linux:  https://adoptium.net/installation/
   ```
3. If `java` is found: parse the version from `java -version` output (the first line contains e.g. `openjdk version "21.0.2"`) and verify it's ≥ 21
4. If the version is too low: print a clear error with the detected version and exit 1
   ```
   Error: JDK 21+ is required but found Java 17.
   Install Eclipse Temurin 21+: https://adoptium.net/
   ```

**Version parsing approach**: `java -version 2>&1 | head -1 | sed 's/.*"\([0-9]*\).*/\1/'` extracts the major version number.

## Step 2: Add a `check-clj` guard target

Create a `.PHONY` target `check-clj` that depends on `check-java` (Clojure requires Java):

1. Check if `clj` is on `PATH` via `command -v clj`
2. If found: pass silently
3. If not found: prompt the user interactively:
   ```
   The Clojure CLI ('clj') is required but was not found on PATH.

   Install it now? This will download and run the official Clojure installer.
     macOS (Homebrew): brew install clojure/tools/clojure
     POSIX (sudo):     curl + sudo ./posix-install.sh

   Install Clojure now? [y/N]
   ```
4. If the user agrees (`y` or `Y`):
   - Detect if Homebrew is available (`command -v brew`)
   - If brew available: run `brew install clojure/tools/clojure`
   - Otherwise: download and run the POSIX installer with `sudo`
   - After install, verify `clj` is now available
5. If the user declines or input is not a tty (CI): print install instructions and exit 1

## Step 3: Wire guard targets into build chain

Add `check-clj` as a dependency (order-only, via `|`) of the targets that need it:

```make
uber: | check-clj
    clj -T:build uber

clean: | check-clj
    clj -T:build clean
```

Targets that do NOT need the check:
- `uninstall-winze` — only calls `claude`, `rm`, `kill`
- `run` — runs the already-built jar with `java` (but does need `check-java`)
- `download-bb` — only uses `curl`/`tar`

The dependency chain `check-clj → check-java` ensures Java is verified before Clojure is checked or installed.

## Step 3a: Add a `check-bb` guard to `install`

`make install` is a dev shortcut that doesn't bundle Babashka — it assumes `bb` is already on `PATH` (since the proxy needs it at runtime). Add a `check-bb` guard target:

1. Check if `bb` is on `PATH` via `command -v bb`
2. If found: pass silently
3. If not found: print a clear message explaining that `make install` requires Babashka on PATH (since it doesn't bundle one), and suggest either:
   - Installing Babashka: `brew install borkdude/brew/babashka` (macOS) or https://babashka.org (other)
   - Using `make install-winze` instead, which downloads and bundles Babashka automatically

Wire `check-bb` as an order-only dependency of `install` only (not `install-winze` or `package`, since those download Babashka via `download-bb`):

```make
install: uber | check-bb
```

This is a warning/error, not an interactive prompt — unlike Clojure CLI, Babashka is a single binary with a trivial install, so we just tell the user how rather than automating it.

## Step 4: Make the prompt non-blocking in CI

If stdin is not a terminal (`[ ! -t 0 ]`), skip the interactive prompt and just print the install instructions with a non-zero exit. This ensures CI pipelines that pre-install `clj` pass through, and those that don't get a clear error rather than hanging on a read.

## Step 5: Test scenarios

Verify these scenarios manually:

1. **Java missing**: `PATH=/usr/bin make uber` — should error with JDK install instructions
2. **Java too old**: temporarily alias java to a JDK 11 — should error with version mismatch
3. **Clojure missing, user accepts**: `make uber` without `clj` — should prompt, install, then build
4. **Clojure missing, user declines**: answer `n` — should error with install instructions
5. **Both present**: `make uber` — should build with no prompts
6. **CI (non-interactive)**: `echo | make uber` without `clj` — should error, not hang
7. **Uninstall**: `make uninstall-winze` without java/clj — should succeed
8. **`make install` without bb**: should error telling user to install Babashka or use `make install-winze`
9. **`make install-winze` without bb**: should succeed (downloads Babashka automatically)
