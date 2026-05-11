---
created: 2026-03-21
updated: 2026-04-22
related: datalevin-migration
tags: packaging, distribution, jlink, babashka, cross-platform
---

# Platform Packaging & Distribution — Plan

Build self-contained platform-specific packages for macOS (arm64), Linux (amd64, arm64), and Windows (amd64). End users need not install Java or Babashka.

**Status**:
- Steps 1–4: complete (2026-03-23). `make package` produces a host-platform archive; `make install-winze` installs from it.
- Step 3bis (cross-platform builds from a single host): **complete (2026-04-23)**. `foreign-jdk` target + `JLINK_MODULE_PATH` conditional in Makefile. Cache at `target/foreign-jdks/<platform>-<version>/jmods/`.
- Step 5 (macOS `.app` bundle): **complete (2026-04-23)**. `pkg/macos/Info.plist.template`, `pkg/macos/winze-launcher`, `app` + `dmg` Makefile targets.
- Step 6 (CI pipeline on GitHub Actions): **complete (2026-04-23)**. `.github/workflows/release.yml` — matrix: macos-14 / ubuntu-latest / ubuntu-24.04-arm / windows-latest. Triggers on `v*` tags.
- Step 7 (macOS code signing & notarization): deferred.

**Key facts (confirmed 2026-04-22)**:
- The uberjar (~176MB) is cross-platform for **all** native libraries including SWT: dtlvnative, ONNX Runtime, JNA, zstd-jni, and six CDT-bundled SWT distributions (macOS aarch64/x86_64, Linux aarch64/x86_64, Windows aarch64/x86_64). No per-platform JAR build is needed.
- `jlink` cannot cross-compile by default — it emits a JRE for the host platform. Cross-platform packaging from macOS requires downloading foreign-platform Temurin JDK archives and pointing `jlink --module-path` at their `jmods/` directories.
- The SWT UI is a full windowed app (18 namespaces under `llm-memory.ui.*` — see CONTEXT.md for the canonical list), not the "future status bar stub" described in earlier drafts.

**Scaffolding status — what exists on disk vs. what is still aspiration** (so a reader can tell the two apart at a glance):
- Exists: [winze-server/Makefile](../../winze-server/Makefile) with `jlink-jre`/`download-bb`/`package`/`install-winze`/`uninstall-winze`/`check-*` targets; [winze-server/pkg/](../../winze-server/pkg/) with `install.sh`, `install.ps1`, `README.txt`, and Unix + `.bat` launchers in `bin/`; [winze-server/resources/branding/](../../winze-server/resources/branding/) with full icon/statusbar/wordmark tree.
- Does **not** exist yet: `winze-server/pkg/macos/` (Step 5 — Info.plist.template, `winze-launcher`), `.github/workflows/release.yml` (Step 6), and any foreign-JDK cache machinery (Step 3bis). The YAML + shell snippets further below are *designs*, not files on disk.
- Empty placeholder: [winze-server/release/](../../winze-server/release/) exists but is unused today. Intended purpose is TBD (likely signed-artefact drop point for Step 7); delete or repurpose when resuming Step 6/7.

## Step 1: Determine JRE Modules

1.1. Build the uberjar (if not already built): `cd winze/winze-server && make uber`
  - `build.clj` already has the `uber` target → produces `target/winze-server.jar` (~176MB as of 2026-04-22)

1.2. Run `jdeps --print-module-deps target/winze-server.jar` to discover required modules. If jdeps fails on the uberjar (multi-release JAR issues), use `jdeps --ignore-missing-deps --multi-release 21`.

1.3. Test a minimal JRE:
```bash
jlink --output target/jre \
      --add-modules <discovered-modules> \
      --no-header-files --no-man-pages \
      --strip-debug --compress=zip-6
target/jre/bin/java --add-opens=java.base/java.nio=ALL-UNNAMED \
                    --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
                    --enable-native-access=ALL-UNNAMED \
                    -jar target/winze-server.jar
```

1.4. Verify: server starts, nREPL connects, search returns results. Note the JRE size.

**Exit criteria**: Minimal module list determined. Server runs on jlink JRE.

**Result (2026-03-23, revised 2026-04-22)**: 10 modules, ~45–55MB JRE. `jdeps` found 3 static deps; runtime testing added Datalevin spill, GC notifications, HTTP client, and — after the SWT UI landed — `java.desktop`. The Makefile's `JLINK_MODULES` variable is the source of truth. See CONTEXT.md for the module table.

## Step 2: Automate Babashka Download

2.1. Add a `download-bb` target to the Makefile that:
  - Detects the current platform (`uname -s` + `uname -m`)
  - Downloads the correct Babashka release archive from GitHub
  - Extracts the `bb` binary to `target/bb`
  - Verifies the binary runs: `target/bb --version`

2.2. Pin a specific Babashka version (e.g. `1.12.215`) for reproducibility.

**Exit criteria**: `make download-bb` produces a working `bb` binary for the current platform.

**Result (2026-03-23)**: Complete. Downloads from GitHub releases, extracts to `target/bb`. Pinned to v1.12.215. Uses static Linux builds (no glibc dep).

## Step 3: Package Assembly

3.1. Add a `package` target to the Makefile that assembles:
```
target/winze-<platform>/
├── jre/                          # Minimal JRE from Step 1 (host platform unless Step 3bis is used)
├── lib/winze-server.jar          # Uberjar (platform-independent, ~176MB)
├── bin/
│   ├── winze-server              # Unix shell launcher (adds -XstartOnFirstThread on macOS)
│   ├── winze-mcp                 # Unix shell launcher → bin/bb bin/mcp-proxy.clj
│   ├── winze-server.bat          # Windows launcher (windows-amd64 package only)
│   ├── winze-mcp.bat             # Windows launcher (windows-amd64 package only)
│   ├── mcp-proxy.clj             # Babashka proxy script
│   └── bb (or bb.exe)            # Babashka binary from Step 2
├── skills/                       # 7 Claude Code skills
├── rules/                        # Claude Code rules (swt-development.md today)
├── install.sh                    # Unix installer (copies to ~/.local/share/winze/, registers MCP)
├── install.ps1                   # Windows installer (copies to %LOCALAPPDATA%\winze\, registers MCP)
└── README.txt                    # Quick-start instructions
```

3.2. Create launcher scripts:

`bin/winze-server` (Unix):
```bash
#!/bin/sh
DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec "$DIR/jre/bin/java" \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  -jar "$DIR/lib/winze-server.jar" "$@"
```

`bin/winze-mcp` (Unix):
```bash
#!/bin/sh
DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec "$DIR/bin/bb" "$DIR/bin/mcp-proxy.clj" "$@"
```

`bin/winze-server.bat` (Windows):
```bat
@echo off
set DIR=%~dp0..
"%DIR%\jre\bin\java" --add-opens=java.base/java.nio=ALL-UNNAMED ^
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED ^
  --enable-native-access=ALL-UNNAMED ^
  -jar "%DIR%\lib\winze-server.jar" %*
```

3.3. Update `mcp-proxy.clj` to find the server jar relative to its own location (not hardcoded `~/.local/share/winze/winze-server.jar`), with fallback to the current installed path.

3.4. Add platform auto-detection to the Makefile (used by `package` and `install-winze` when `PLATFORM` is not set). Darwin must nest an arch guard so x86_64 Macs hard-error rather than silently producing a broken archive:
```makefile
ifndef PLATFORM
  UNAME_S := $(shell uname -s)
  UNAME_M := $(shell uname -m)
  ifeq ($(UNAME_S),Darwin)
    ifeq ($(UNAME_M),arm64)
      PLATFORM := macos-arm64
    else
      $(error macOS x86_64 is not supported — dtlvnative has no macosx-x86_64 native libs)
    endif
  else ifeq ($(UNAME_S),Linux)
    ifeq ($(UNAME_M),aarch64)
      PLATFORM := linux-arm64
    else
      PLATFORM := linux-amd64
    endif
  else
    $(error Unsupported platform $(UNAME_S)/$(UNAME_M). Set PLATFORM explicitly or use install.ps1 on Windows)
  endif
endif
```

3.5. Create `target/winze-<platform>.tar.gz` (or `.zip` for Windows).

3.6. Create bundled installer scripts (included in every platform archive):

  **`install.sh`** (Unix — macOS/Linux):
  - Copies package contents to `~/.local/share/winze/`
  - Registers with Claude Code: `claude mcp add --scope user winze -- <path>/bin/winze-mcp`
  - Installs skills (same set as current `make install-winze`)
  - Offers to add `~/.local/share/winze/bin` to PATH (detects shell from `$SHELL`, appends `export PATH` line to `~/.bashrc`, `~/.zshrc`, or `~/.profile`; skips if already present; prints manual instructions if user declines)

  **`install.ps1`** (Windows):
  - Copies package contents to `$env:LOCALAPPDATA\winze\`
  - Registers with Claude Code: `claude mcp add --scope user winze -- <path>\bin\winze-mcp.bat`
  - Installs skills
  - Offers to add `%LOCALAPPDATA%\winze\bin` to user PATH (updates `[Environment]::SetEnvironmentVariable('Path', ..., 'User')`; skips if already present; prints manual instructions if user declines)

Both scripts are self-contained — no `make`, `clj`, or JDK required.

**Exit criteria**: `make package` produces a self-contained archive (auto-detecting platform on Unix). Extracting and running `install.sh` (or `install.ps1`) works without Java or Babashka on PATH.

**Result (2026-03-23)**: Complete for host-platform builds. `make package` produces `target/winze-macos-arm64.tar.gz`. Package includes JRE, uberjar, Babashka, launcher scripts, install.sh/install.ps1, skills, rules, README. Also updated `mcp-proxy.clj` to resolve bundled JRE/JAR relative to its own location with fallback to legacy paths. All packaging assets at `pkg/` in the repo.

**Caveat (exposed 2026-04-22)**: Running `make package PLATFORM=linux-amd64` on macOS silently embeds a macOS JRE in a linux-labeled archive, because `jlink` cannot cross-compile without being pointed at a foreign `jmods/` directory. Step 3bis fixes this.

## Step 3bis: Cross-Platform Local Builds (Foreign-JDK Cache)

**Goal**: `make package PLATFORM=linux-amd64` (or `linux-arm64`, or `windows-amd64`) on a macOS developer machine must produce a package whose JRE actually runs on the target platform. This unblocks local testing before Step 6 CI exists, and lets a single macOS release-cutter produce all four artefacts in a pinch.

3bis.1. **Pin foreign JDK URLs** at the top of the Makefile. Use Eclipse Temurin 21 LTS (stable naming, well-tested):
```makefile
TEMURIN_VERSION := 21.0.5+11
TEMURIN_BASE    := https://github.com/adoptium/temurin21-binaries/releases/download/jdk-$(TEMURIN_VERSION)

# URL-suffix per target (encoded: arch_os_distro)
TEMURIN_SUFFIX_linux-amd64  := x64_linux_hotspot_21.0.5_11.tar.gz
TEMURIN_SUFFIX_linux-arm64  := aarch64_linux_hotspot_21.0.5_11.tar.gz
TEMURIN_SUFFIX_windows-amd64 := x64_windows_hotspot_21.0.5_11.zip
```

(The macOS arm64 target uses the host's own JDK via `jlink` — no download needed if the developer already has JDK 21 installed.)

3bis.2. Add a `foreign-jdk` target that downloads and extracts the archive for the current `PLATFORM` into `target/foreign-jdks/<platform>-<version>/` if it's not already cached. The cache key includes `TEMURIN_VERSION` so a version bump invalidates everything (see the `FOREIGN_JDK_DIR` line below — this is the canonical directory layout; earlier drafts of this document showed a shorter `target/foreign-jdks/linux-amd64/` path that's wrong).

```makefile
FOREIGN_JDK_DIR := target/foreign-jdks/$(PLATFORM)-$(TEMURIN_VERSION)

foreign-jdk:
ifneq ($(PLATFORM),$(HOST_PLATFORM))
  @if [ ! -d "$(FOREIGN_JDK_DIR)/jmods" ]; then \
    mkdir -p $(FOREIGN_JDK_DIR); \
    URL="$(TEMURIN_BASE)/OpenJDK21U-jdk_$(TEMURIN_SUFFIX_$(PLATFORM))"; \
    echo "Downloading $$URL"; \
    curl -fSL -o "$(FOREIGN_JDK_DIR)/archive" "$$URL"; \
    # Extract — tar for *nix, unzip for Windows
    ...; \
  fi
endif
```

3bis.3. Modify the `jlink-jre` target to pass `--module-path <foreign-jdk>/jmods` when `PLATFORM` ≠ host, and to add `--endian` matching the target (jlink infers endianness from the module path):

```makefile
jlink-jre: uber foreign-jdk
  @MODULE_PATH=$${PLATFORM_IS_HOST:-$$(foreign-jdk-jmods-path)}; \
   jlink --output target/jre \
         --module-path "$$MODULE_PATH" \
         --add-modules $(JLINK_MODULES) \
         --no-header-files --no-man-pages \
         --strip-debug --compress=zip-6
```

Note: On macOS, the host `jlink` binary runs fine; only the module content needs to come from a foreign JDK. This works because jlink is a pure Java tool (it's a JPMS linker, not a native compiler) — the output format is platform-independent metadata + platform-specific `.so/.dll/.dylib` files pulled from the foreign `jmods/`.

3bis.4. Cross-target smoke test on macOS:
- `make package PLATFORM=linux-amd64` → `file target/winze-linux-amd64/jre/bin/java` should report `ELF 64-bit LSB executable, x86-64`.
- `make package PLATFORM=windows-amd64` → `file target/winze-windows-amd64/jre/bin/java.exe` should report `PE32+ executable … x86-64, for MS Windows`.

3bis.5. Full end-to-end validation (requires access to target-platform hardware or VMs — can defer to CI in Step 6):
- Linux amd64 in Docker: `docker run -v $PWD/target:/w -w /w ubuntu:24.04 bash -c "tar -xzf winze-linux-amd64.tar.gz && cd winze-linux-amd64 && ./bin/winze-server --help"`
- Linux arm64: same pattern with `--platform linux/arm64`
- Windows: Parallels/UTM VM or a colleague with Windows

**Exit criteria**: `make package PLATFORM=<target>` on macOS produces an archive whose `jre/bin/java*` binary runs on the target platform (verified via `file(1)` at minimum; executed in Docker for Linux targets; deferred to CI for Windows).

**Effort estimate**: Half a day. The Makefile changes are mechanical; the tricky parts are the archive-extraction cases (`.tar.gz` vs `.zip`) and verifying that `jlink --module-path <foreign-jmods>` actually emits foreign binaries (it does — this is a documented JDK feature).

## Step 4: Update install-winze for Packaged Mode

`make install-winze` already exists and works (builds uber, copies JAR + proxy to `~/.local/share/winze/`, registers MCP, installs 7 skills). This step modifies it to delegate to the bundled installer.

4.1. Update `make install-winze` to:
  - Run `make package` if not already built (auto-detects platform via `uname`)
  - Run the bundled `install.sh` from the assembled package directory

This keeps `make install-winze` as a convenience for developers who build from source. End users who download pre-built archives use `install.sh` / `install.ps1` directly.

4.2. Ensure the MCP proxy uses the bundled `bb` and bundled `java` (not system PATH).

4.3. Support idempotent re-install + uninstall. `install.sh` already:
  - Stops a running server (PID-based) before overwriting files.
  - Deregisters **legacy** MCP entries (`planning`, `planning-tool`, `clj-llm-memory`) at both `user` and `project` scope before re-registering `winze` — so upgrading from a pre-`winze` install does not leave orphaned MCP entries.
  - Supports `./install.sh --uninstall`: removes the MCP registration, skills, rules, and `jre/`/`lib/`/`bin/` trees but **preserves `.datalevin/` and logs** (delete manually if unwanted). `make uninstall-winze` calls the same cleanup via the source Makefile.

**Exit criteria**: `make install-winze` from source produces a working install. Pre-built archives install correctly via `install.sh` (macOS/Linux) or `install.ps1` (Windows). Re-running either installer (or `--uninstall` followed by a fresh install) leaves exactly one `winze` MCP entry and no legacy entries.

**Result (2026-03-23)**: Complete. `make install-winze` now depends on `package` (builds uber → jlink → download bb → assemble → run install.sh). MCP registered as `~/.local/share/winze/bin/winze-mcp` (bundled bb, not system bb). Verified: server starts with bundled JRE, nREPL connects, 6545 documents accessible.

## Step 5: macOS `.app` Bundle

Package the macOS build as a native `.app` bundle for drag-to-Applications installation and future Homebrew Cask distribution. A signed `.app` "just works" — no Gatekeeper bypass needed.

**Scope update (2026-04-22)**: Winze is a full SWT desktop application (18 namespaces under `llm-memory.ui.*` — canonical list in CONTEXT.md §"UI: Full SWT Desktop App"), not a headless daemon with a menu-bar dropdown. The Info.plist flags in earlier drafts of this step (`LSBackgroundOnly=true`, `LSUIElement=true`) are wrong and must be dropped. The `.app` should behave like any other windowed Mac app: Dock icon, Command-Tab entry, standard window lifecycle.

### Existing Assets

Branding assets in `winze-server/resources/branding/`:

- **`icons/winze.icns`** — macOS app icon bundle (16–1024px), Apple's native format
- **`icons/winze-icon-512.svg`** — master SVG source
- **`icons/png/winze-icon-{48,128,256,512}.png`** — raster exports
- **`statusbar/macos/winzeTemplate.png`** / `@2x.png` — `Template`-suffixed menu bar icons (reserved for a future optional menu bar extra; not needed by the main `.app`)
- **`wordmark/*.svg`** + PNGs — light/dark wordmarks (About dialog, splash)
- **`BRAND-GUIDE.md`** — color palette, typography, clear-space rules

### 5a. `.app` Bundle Structure

```
Winze.app/
└── Contents/
    ├── Info.plist                  # Bundle metadata (no LSBackgroundOnly, no LSUIElement)
    ├── PkgInfo                     # "APPL????"
    ├── MacOS/
    │   └── winze-launcher          # Shell script: sets APP_RESOURCES, execs jre/bin/java with -XstartOnFirstThread
    ├── Resources/
    │   ├── winze.icns              # From resources/branding/icons/winze.icns
    │   ├── jre/                    # Bundled JRE (~45–55MB, includes java.desktop)
    │   ├── lib/
    │   │   └── winze-server.jar    # Uberjar (~176MB)
    │   ├── bin/
    │   │   ├── bb                  # Babashka binary
    │   │   ├── mcp-proxy.clj       # MCP proxy
    │   │   ├── winze-server        # Server launcher (same logic as pkg/bin/winze-server)
    │   │   └── winze-mcp           # MCP launcher
    │   ├── skills/                 # Claude Code skills
    │   ├── rules/                  # Claude Code rules
    │   └── install.sh              # Copied verbatim from pkg/; first-run registers MCP + installs skills
    └── _CodeSignature/             # Present only when signed (Step 7)
```

### 5b. `Info.plist`

Key properties (stored as `pkg/macos/Info.plist.template` with `@@VERSION@@` placeholder substituted at build time):

- `CFBundleName`: Winze
- `CFBundleDisplayName`: Winze
- `CFBundleIdentifier`: `io.github.dorme.winze`
- `CFBundleVersion`: from `build.clj` (short version, e.g. `0.5.0`)
- `CFBundleShortVersionString`: same as CFBundleVersion
- `CFBundleIconFile`: winze (references `winze.icns`; no extension)
- `CFBundleExecutable`: winze-launcher
- `CFBundlePackageType`: APPL
- `CFBundleSignature`: `????`
- `LSMinimumSystemVersion`: `12.0` (macOS Monterey — matches JDK 21 minimum)
- `NSHighResolutionCapable`: `true`
- `LSApplicationCategoryType`: `public.app-category.developer-tools`

**Explicitly omitted**: `LSBackgroundOnly`, `LSUIElement`. Both default to false/absent, which is correct for a windowed app.

### 5c. `Contents/MacOS/winze-launcher`

Shell script that:
1. Resolves `$APP_RESOURCES` to `Contents/Resources/` (relative to `$0`).
2. On first launch (detected by absence of `~/.local/share/winze/.installed` marker): runs `$APP_RESOURCES/install.sh` to register MCP and install skills/rules. On subsequent launches, skips installation.
3. Execs the JVM — **not** the `winze-server` shell launcher, because `exec` from a `.app` launcher replaces the process image and the Dock needs to track the JVM directly as the app's main process (otherwise the Dock icon bounces and disappears).

```bash
#!/bin/sh
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_RESOURCES="$(cd "$SCRIPT_DIR/../Resources" && pwd)"

# First-run: register MCP + install skills
if [ ! -f "$HOME/.local/share/winze/.installed" ]; then
  "$APP_RESOURCES/install.sh" || true
  mkdir -p "$HOME/.local/share/winze"
  touch "$HOME/.local/share/winze/.installed"
fi

# -XstartOnFirstThread MUST be the first JVM arg for SWT on macOS
exec "$APP_RESOURCES/jre/bin/java" \
  -XstartOnFirstThread \
  -Xdock:name=Winze \
  -Xdock:icon="$APP_RESOURCES/winze.icns" \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  -jar "$APP_RESOURCES/lib/winze-server.jar"
```

The `-Xdock:name`/`-Xdock:icon` flags are macOS-specific JVM hints that help LaunchServices show the right name and icon while the JVM is booting (before SWT sets them).

### 5d. `make app` Target

```makefile
APP_DIR := target/Winze.app

app: package
	@test "$(UNAME_S)" = "Darwin" || (echo "make app is macOS-only"; exit 1)
	@test "$(PLATFORM)" = "macos-arm64" || (echo "make app requires PLATFORM=macos-arm64"; exit 1)
	@echo "Building Winze.app..."
	rm -rf $(APP_DIR)
	mkdir -p $(APP_DIR)/Contents/MacOS $(APP_DIR)/Contents/Resources
	@# Info.plist with version substitution
	sed "s/@@VERSION@@/$$(clj -T:build version 2>/dev/null || echo 0.0.0-dev)/" \
	    pkg/macos/Info.plist.template > $(APP_DIR)/Contents/Info.plist
	printf 'APPL????' > $(APP_DIR)/Contents/PkgInfo
	@# Launcher
	cp pkg/macos/winze-launcher $(APP_DIR)/Contents/MacOS/winze-launcher
	chmod +x $(APP_DIR)/Contents/MacOS/winze-launcher
	@# Icon
	cp resources/branding/icons/winze.icns $(APP_DIR)/Contents/Resources/winze.icns
	@# Package contents → Resources/
	cp -R $(PKG_DIR)/jre $(APP_DIR)/Contents/Resources/jre
	cp -R $(PKG_DIR)/lib $(APP_DIR)/Contents/Resources/lib
	cp -R $(PKG_DIR)/bin $(APP_DIR)/Contents/Resources/bin
	cp -R $(PKG_DIR)/skills $(APP_DIR)/Contents/Resources/skills
	cp -R $(PKG_DIR)/rules $(APP_DIR)/Contents/Resources/rules
	cp $(PKG_DIR)/install.sh $(APP_DIR)/Contents/Resources/install.sh
	@echo "✓ Winze.app built: $$(du -sh $(APP_DIR) | cut -f1) $(APP_DIR)"

dmg: app
	@rm -f target/Winze-macos-arm64.dmg
	hdiutil create -volname Winze -srcfolder $(APP_DIR) \
	  -ov -format UDZO target/Winze-macos-arm64.dmg
	@echo "✓ DMG: $$(du -sh target/Winze-macos-arm64.dmg | cut -f1)"
```

### 5e. Distribution Formats

| Format | Use Case | How |
|--------|----------|-----|
| `.app` in `.dmg` | Drag-to-Applications | `hdiutil create -volname Winze -srcfolder target/Winze.app -ov -format UDZO target/Winze-macos-arm64.dmg` |
| `.app` in `.zip` | Simplest download for CI | `ditto -c -k --sequesterRsrc --keepParent target/Winze.app target/Winze.zip` |
| `.pkg` installer | Scripted install | `pkgbuild` + `productbuild` — can be added later if needed |
| Homebrew Cask | `brew install --cask winze` | Cask formula pointing to the `.dmg` URL |

Start with `.dmg` as the primary distribution. Keep `.tar.gz` (from Step 3) around for users who prefer raw archives.

**Exit criteria**: `make app` produces `target/Winze.app` that:
1. Double-clicks into a normal windowed app with a Dock icon.
2. On first launch, registers the MCP server and installs skills/rules globally.
3. Is fully self-contained — no system Java, no system bb, no system Clojure required.

`make dmg` wraps it in a drag-to-Applications `.dmg`.

## Step 6: GitHub Actions CI Pipeline

6.1. Create `.github/workflows/release.yml`. Trigger on git tag `v*`. Use a matrix of hosted runners — no self-hosted runner needed for any target:

```yaml
name: Release

on:
  push:
    tags: ['v*']
  workflow_dispatch:

jobs:
  build:
    strategy:
      fail-fast: false
      matrix:
        include:
          - { target: macos-arm64,   runs-on: macos-14 }
          - { target: linux-amd64,   runs-on: ubuntu-latest }
          - { target: linux-arm64,   runs-on: ubuntu-24.04-arm }
          - { target: windows-amd64, runs-on: windows-latest }

    runs-on: ${{ matrix.runs-on }}

    steps:
      - uses: actions/checkout@v4
        with:
          submodules: recursive

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - uses: DeLaGuardo/setup-clojure@13.0
        with:
          cli: latest

      - name: Build package (Unix)
        if: matrix.target != 'windows-amd64'
        working-directory: winze-server
        run: make package PLATFORM=${{ matrix.target }}

      - name: Build package (Windows)
        if: matrix.target == 'windows-amd64'
        working-directory: winze-server
        shell: bash
        run: make package PLATFORM=windows-amd64

      - name: Build .app + .dmg (macOS only)
        if: matrix.target == 'macos-arm64'
        working-directory: winze-server
        run: make dmg

      - uses: actions/upload-artifact@v4
        with:
          name: winze-${{ matrix.target }}
          path: |
            winze-server/target/winze-${{ matrix.target }}.tar.gz
            winze-server/target/winze-${{ matrix.target }}.zip
            winze-server/target/Winze-macos-arm64.dmg
          if-no-files-found: ignore

  release:
    needs: build
    runs-on: ubuntu-latest
    if: startsWith(github.ref, 'refs/tags/v')
    steps:
      - uses: actions/download-artifact@v4
        with:
          path: artifacts
          merge-multiple: true

      - uses: softprops/action-gh-release@v2
        with:
          files: artifacts/*
          generate_release_notes: true
```

6.2. **First-run gotchas to watch for**:
  - `ubuntu-24.04-arm` is a relatively new GitHub-hosted runner. If it's not available on the org's plan, fall back to building linux-arm64 on `ubuntu-latest` using QEMU + `docker/setup-qemu-action` + a container image — slower (~3×) but works without a real arm64 runner.
  - `macos-14` runners are Apple Silicon by default. `macos-13` and earlier are Intel — do not use them; the packaging Makefile will hard-error.
  - On Windows, `make` is available via `choco install make` if not preinstalled; `setup-java` handles the JDK. Git Bash is the default shell for `shell: bash`.
  - Babashka is downloaded at `make package` time per platform — no separate setup step needed.

6.3. **Validate the pipeline** by pushing a pre-release tag (`v0.1.0-rc1`) and checking that all four platform archives land on the release page. Download the macOS `.dmg` on a real Mac and confirm:
  - Double-clicking mounts the DMG.
  - Dragging `Winze.app` to `/Applications` succeeds.
  - First launch prompts for the usual Gatekeeper bypass (right-click → Open), then boots the server.
  - The MCP server is registered in Claude Code (`claude mcp list` shows `winze`).

**Exit criteria**: Tagged release produces five downloadable artefacts attached to the GitHub release: `.tar.gz` for macOS, `.tar.gz` for each Linux target, `.zip` for Windows, and `.dmg` for macOS. All four platform archives install and run without external dependencies.

### Local equivalent (developer sanity check)

With Step 3bis in place, a macOS developer can produce every artefact locally without waiting for CI:

```bash
cd winze-server
make package PLATFORM=macos-arm64
make dmg
make package PLATFORM=linux-amd64
make package PLATFORM=linux-arm64
make package PLATFORM=windows-amd64
ls -lh target/winze-*.tar.gz target/*.zip target/*.dmg
```

Use this loop when iterating on packaging changes before opening a CI run.

## Step 7 (Deferred): macOS Code Signing & Notarization

**Not needed initially.** Unsigned builds work — users bypass Gatekeeper once via `xattr -cr <path>` or right-click → Open. Add this step later only if distribution friction warrants the $99/year Apple Developer enrollment.

**Reference**: [Freeplane's `mac.dist.gradle`](https://github.com/freeplane/freeplane/blob/1.12.x/mac.dist.gradle) — same pattern (Java/JVM app, conditional signing).

When ready to implement:

7.1. Add a `sign-macos` target to the Makefile, conditional on a `CODESIGN_IDENTITY` env var (skip gracefully when absent):

7.2. **Sign native-code JARs individually** — JARs containing `.dylib`/`.jnilib` files (JNA, ONNX Runtime, dtlvnative, zstd-jni) must be signed before the containing directory is signed:
```bash
# Extract, sign native libs, re-pack each JAR
codesign --force --sign "$CODESIGN_IDENTITY" <extracted .dylib files>
```
Freeplane uses a dedicated shell script for this (`codesign/sign-jars-on-mac.sh`).

7.3. **Sign binaries and the JRE**:
```bash
codesign --deep --force --options runtime \
  --sign "$CODESIGN_IDENTITY" target/winze-macos-arm64/jre/
codesign --force --options runtime \
  --sign "$CODESIGN_IDENTITY" target/winze-macos-arm64/bin/bb
```

7.4. **Notarize the `.app` bundle or archive** (requires one-time keychain profile setup):
```bash
xcrun notarytool submit target/winze-macos-arm64.tar.gz \
  --keychain-profile "winze-notarize" --wait
```

7.5. **README**: Document the Gatekeeper bypass for unsigned builds (`xattr -cr ~/.local/share/winze/`).

**Exit criteria**: `make sign-macos CODESIGN_IDENTITY="Developer ID Application: ..."` produces a signed, notarized archive. Without the env var, the target is a no-op.

## Dependency Summary

Build-time only (not bundled):
- JDK 21+ (for `jlink`)
- `clj` (Clojure CLI, for `tools.build`)
- `curl` + `tar`/`unzip` (for `download-bb` and `foreign-jdk`)
- macOS only: `hdiutil` (pre-installed) for `.dmg` creation

Bundled per platform:
- Minimal JRE (~45–55MB; includes `java.desktop` for SWT)
- Uberjar (~176MB as of 2026-04-22, platform-independent — includes CDT-bundled SWT distributions for all 6 target (os,arch) combinations; `du -sh target/winze-server.jar` is source of truth)
- Babashka binary (~25MB, platform-specific)
- Launcher scripts (<1KB each)
- Skills + rules (<1MB total)
- Branding (~200KB: `.icns` on macOS, `.ico` on Windows, `.png` on Linux)

Total compressed package size: ~200MB per platform.
