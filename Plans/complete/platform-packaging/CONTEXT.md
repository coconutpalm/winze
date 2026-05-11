---
created: 2026-03-21
updated: 2026-04-22
related: datalevin-migration
tags: packaging, distribution, jlink, babashka, cross-platform
---

# Platform Packaging & Distribution — Context

## Motivation

The `winze-server` originally required JDK 21+ and Babashka on the user's PATH. Steps 1–4 of the plan replaced that with a self-contained package: a minimal jlink JRE, the uberjar, a bundled Babashka binary, and launcher scripts. Users extract an archive and run `install.sh` (or `install.ps1` on Windows).

What's left (Steps 5–6) is a native macOS `.app` bundle, a CI pipeline that produces signed artefacts for all four target platforms on every tag, and the underlying machinery that lets `make package PLATFORM=<target>` actually cross-compile a foreign-platform package from a macOS developer machine.

## Current State

The server (`winze/winze-server/`, a submodule of the top-level repo) is operational:
- **Uberjar**: ~176MB as of 2026-04-22 (Datalevin + ONNX Runtime + inference4j model + nREPL + Clojure + 6 bundled SWT distributions via CDT + commonmark). The size grows organically as dependencies evolve — treat `du -sh target/winze-server.jar` as source of truth, not the number in this doc.
- **Babashka proxy**: `mcp-proxy.clj` — resolves the bundled JAR/JRE relative to its own location, with fallback to the legacy flat path (`~/.local/share/winze/winze-server.jar`) for backward compatibility with pre-packaging installs
- **Build system**: `build.clj` with `uber` target; `deps.edn` with `:build` and `:dev` aliases. Clojure 1.12+ required — CDT uses `clojure.repl.deps` for dynamic SWT native loading
- **Installation**: `make install-winze` runs `make package` then delegates to the bundled `install.sh`. Installs: JRE, uberjar, bb, proxy, launchers → `~/.local/share/winze/{jre,lib,bin}/`. Also copies the JAR and proxy to the flat `~/.local/share/winze/` root for legacy compatibility. Registers Claude Code MCP server as `winze` (scope: user). As part of registration, `install.sh` also deregisters legacy MCP entries (`planning`, `planning-tool`, `clj-llm-memory`) at both `user` and `project` scope to keep upgrades idempotent. Installs 7 skills to `~/.claude/skills/` and rules to `~/.claude/rules/`.
- **Uninstall**: `install.sh --uninstall` removes the MCP registration, skills, rules, and `jre/`/`lib/`/`bin/` trees but preserves `.datalevin/` and logs. `make uninstall-winze` does the same via the source Makefile.
- **Prerequisites for building**: JDK 21+ (for `jlink` and `clj`), Clojure CLI. `make install-winze` downloads Babashka into the package itself — no system `bb` needed to install
- **Proxy auto-start**: The proxy detects the server JVM via PID file and auto-starts it. Server JAR location is resolved relative to the proxy script's install directory (bundled `lib/winze-server.jar`), with a legacy fallback to the flat root path

## Why Not GraalVM Native Image

GraalVM native-image is **not viable** for this project:
- **nREPL** requires dynamic class loading (eval of arbitrary Clojure forms sent by the proxy)
- **Datalevin** uses JNA via `dtlvnative` (LMDB bindings) which needs runtime `dlopen`
- **beholder** (filesystem watcher) uses JNA for platform-native file watching (FSEvents on macOS)
- **inference4j** loads ONNX Runtime native libraries dynamically

All of these are fundamentally incompatible with ahead-of-time compilation.

## Approach: jlink + Bundled Babashka

Bundle a minimal JRE (via `jlink`) alongside the uberjar, plus a platform-specific Babashka binary:

```
winze-<platform>/
├── jre/                      # Minimal JRE (~45-55MB after strip+compress, includes java.desktop for SWT)
├── lib/winze-server.jar      # Uberjar (~176MB, cross-platform — see "Cross-platform uberjar" below)
├── bin/
│   ├── winze-server          # Unix shell launcher (invokes jre/bin/java, adds -XstartOnFirstThread on macOS)
│   ├── winze-mcp             # Unix shell launcher (invokes bin/bb mcp-proxy.clj)
│   ├── winze-server.bat      # Windows launcher (windows-amd64 package only)
│   ├── winze-mcp.bat         # Windows launcher (windows-amd64 package only)
│   ├── mcp-proxy.clj         # Babashka MCP proxy
│   └── bb / bb.exe           # Babashka binary (~25MB, platform-specific)
├── skills/                   # 7 Claude Code skills (search/index/recent/related/register/list-plan-roots/help-plans)
├── rules/                    # Claude Code rules (swt-development.md today)
├── install.sh                # Unix installer (copies to ~/.local/share/winze/, registers MCP, installs skills + rules)
├── install.ps1               # Windows installer (copies to %LOCALAPPDATA%\winze\, registers MCP, installs skills + rules)
└── README.txt                # Quick-start instructions
```

Total package size estimate: ~200MB compressed for macOS arm64; similar for other platforms.

## Target Platforms

| Platform | Architecture | JRE | Babashka | Native libs in uberjar | Notes |
|----------|-------------|-----|----------|----------------------|-------|
| macOS | arm64 (Apple Silicon) | temurin-21 aarch64 | bb-macos-aarch64 | ✅ dtlvnative + ONNX + JNA + SWT | Primary dev platform |
| Linux | amd64 | temurin-21 x64 | bb-linux-amd64-static | ✅ dtlvnative + ONNX + JNA + SWT | CI/server use; SWT best-effort (see below) |
| Linux | arm64 | temurin-21 aarch64 | bb-linux-aarch64-static | ✅ dtlvnative + ONNX + JNA + SWT | ARM servers (Graviton); SWT best-effort |
| Windows | amd64 (x64) | temurin-21 x64 | bb-windows-amd64 | ✅ dtlvnative + ONNX + JNA + SWT | `.bat` launchers, `install.ps1` |

**Enforced at build time**: `make` hard-errors if `uname -s -m` = `Darwin x86_64` (Intel Mac) — dtlvnative has no `macosx-x86_64` native libs. Windows arm64 is not a supported target — CDT bundles the SWT zip for it but the broader stack (ONNX, dtlvnative) does not ship Windows arm64 natives.

### Cross-platform uberjar

A single uberjar runs on all four targets. Native libraries are bundled for every supported architecture:

| Library | Loading mechanism | Bundled architectures |
|---------|-------------------|------------------------|
| dtlvnative (Datalevin/LMDB) | JNA, standard resource path | linux-arm64, linux-x86_64, macosx-arm64, windows-x86_64 |
| ONNX Runtime | JNA | linux-aarch64, linux-x64, osx-aarch64, osx-x64, win-x64 |
| JNA | self-loading | all major platforms |
| zstd-jni | self-loading | all major platforms |
| SWT (via CDT) | **dynamic — CDT unzips the matching `swt-4.38-*.zip` at runtime** | cocoa-macosx-{aarch64,x86_64}, gtk-linux-{aarch64,x86_64}, win32-win32-{aarch64,x86_64} |

The six SWT ZIPs live at the root of the uberjar (e.g. `swt-4.38-cocoa-macosx-aarch64.zip`). CDT's `ui.internal.SWT_deps` extracts the correct one at startup based on `os.name` / `os.arch` and adds its JARs + natives to the runtime classpath via `clojure.repl.deps/add-lib`. This is why the uberjar is ~30MB larger than a bare Clojure app and why the packaging pipeline does **not** need per-platform uberjar builds.

**Linux/GTK caveat**: CDT lists Linux/GTK as best-effort-only due to known incompatibilities between the bundled GTK version in SWT 4.38 and various distro GTK versions. In practice this means:
- The MCP proxy + server (headless features — search, indexing) works fine on Linux.
- The SWT UI (markdown editor, command palette, etc.) may fail at runtime on some Linux/GTK combinations.
- Linux packages ship anyway — the headless path is the primary use case on Linux CI/server hosts.

## JRE Modules — Determined (2026-03-23, revised 2026-04-22)

Static analysis (`jdeps --ignore-missing-deps --multi-release 21 --print-module-deps`) found only 3 modules: `java.base,java.naming,java.sql`. Runtime testing added modules for Datalevin (spill management, GC notifications), the HTTP client, and SWT.

**Final module set (validated — server starts, nREPL connects, Datalevin operational, SWT UI launches):**
```
java.base,java.desktop,java.naming,java.sql,java.management,jdk.management,jdk.unsupported,java.net.http,java.logging,java.xml
```

| Module | Required by |
|--------|-------------|
| `java.base` | Core (always included) |
| `java.desktop` | SWT / AWT (required once the `llm-memory.ui.*` tree took over from the `hello` stub) |
| `java.naming` | JNDI (nREPL) |
| `java.sql` | Datalevin JDBC-like internals |
| `java.management` | Datalevin spill (`ManagementFactory`) |
| `jdk.management` | Datalevin spill (`GarbageCollectionNotificationInfo`) |
| `jdk.unsupported` | `sun.misc.Unsafe` (Clojure internal access) |
| `java.net.http` | `HttpResponse$BodySubscriber` (inference4j) |
| `java.logging` | SLF4J/logback |
| `java.xml` | Logback XML config |

**JRE size**: ~45–55MB on macOS arm64 (stripped, compressed) — up from the original 34MB once `java.desktop` was added. Full JDK 21 would be ~300MB.

```bash
jlink --output target/jre \
      --add-modules java.base,java.desktop,java.naming,java.sql,java.management,jdk.management,jdk.unsupported,java.net.http,java.logging,java.xml \
      --no-header-files --no-man-pages \
      --strip-debug --compress=zip-6
```

The live list is in the Makefile (`JLINK_MODULES` at the top) — treat the Makefile as source of truth if these drift again.

**Key lesson**: `jdeps` misses runtime-only dependencies (JNA, reflection, dynamic class loading). Always validate a jlink JRE with a full server startup test, not just static analysis.

## Cross-compilation from macOS

The Makefile accepts `PLATFORM=<target>` but **`jlink` cannot cross-compile by default** — running `jlink` on macOS produces a macOS JRE regardless of `PLATFORM`. The JRE inside `target/winze-linux-amd64/` would silently be a Darwin binary, unrunnable on Linux.

To cross-compile locally (macOS developer → Linux/Windows package):

1. **Download the foreign platform's Temurin JDK 21** archive (must match the target arch):
   - Linux amd64: `OpenJDK21U-jdk_x64_linux_hotspot_21.*.tar.gz`
   - Linux arm64: `OpenJDK21U-jdk_aarch64_linux_hotspot_21.*.tar.gz`
   - Windows amd64: `OpenJDK21U-jdk_x64_windows_hotspot_21.*.zip`
2. **Extract it** to a version-keyed cache directory (e.g. `target/foreign-jdks/linux-amd64-<temurin-version>/`). See PLAN.md Step 3bis for the canonical layout — a JDK bump must invalidate the cache.
3. **Run `jlink` with `--module-path <foreign-jdk>/jmods/`** — this produces a JRE for the foreign platform using the host `jlink` binary. The `jlink` tool itself is portable; only the `jmods/` content is platform-specific.
4. **Verify**: `file target/jre/bin/java` on the foreign JRE should report the target architecture (`ELF 64-bit LSB executable, x86-64` for linux-amd64; `PE32+ executable … x86-64, for MS Windows` for windows-amd64).

Babashka is already cross-compiled — `make download-bb` fetches the correct binary for `PLATFORM` regardless of the host.

The Makefile should gain a `foreign-jdks` target that downloads each required JDK archive into a cache, plus `jlink-jre` awareness that picks `--module-path` from the cache when `PLATFORM` ≠ host. See PLAN.md Step 3bis.

## UI: Full SWT Desktop App

The plan originally described `src/llm_memory/server/ui.clj` as a stub with `(defn hello [])` — a placeholder for a future macOS status bar item. **That file no longer exists.** What replaced it is a complete SWT desktop workspace under `src/llm_memory/ui/`, containing 18 namespaces:

- `main_window.clj` — top-level Shell, menu bar, tab folder
- `markdown_editor.clj` + `md_theme.clj` — StyledText-based markdown editor with syntax highlighting
- `hiccup.clj` + `search.clj` — live search results rendered as hiccup → HTML
- `command_palette.clj`, `commands.clj`, `editor_commands.clj` — VS Code–style command palette + command registry
- `keybindings.clj` — scoped keybindings (editor, palette, window)
- `find_replace.clj` — find/replace bar
- `content_assist.clj` — auto-complete popup
- `spellcheck.clj` + `spellcheck_menu.clj` — live spellcheck with squiggly underline + right-click suggestions
- `link_preview.clj` — hover previews and relative-link navigation
- `about_dialog.clj` — about dialog
- `resources.clj` + `theme.clj` — theme atoms, EDN-externalized colors/fonts/icons
- `util.clj` — SWT helpers

The startup sequence in [server/main.clj](../../winze-server/src/llm_memory/server/main.clj) auto-launches `llm-memory.ui.main-window` once the nREPL server is ready. The headless MCP path (proxy → nREPL → Datalevin) still works independently of the UI — `main-window` is not a prerequisite for serving Claude Code.

**Implication for packaging**: Winze is a **full GUI application with Dock presence**, not a background daemon with a menu-bar item. The Info.plist flags recommended in earlier drafts of this document (`LSBackgroundOnly=true`, `LSUIElement=true`) are **wrong** — both should be omitted (or set to false) so that the `.app` shows in the Dock and on Command-Tab like any other windowed app.

## Babashka Binary

Babashka releases are published on GitHub: `https://github.com/babashka/babashka/releases`

Archive naming convention:
- `babashka-<version>-macos-aarch64.tar.gz`
- `babashka-<version>-linux-amd64-static.tar.gz`
- `babashka-<version>-linux-aarch64-static.tar.gz`
- `babashka-<version>-windows-amd64.zip`

The static Linux builds are preferred (no glibc dependency).

## CI Pipeline

Trigger on git tag `v*`. Strategy: build the uberjar once (platform-independent), then fan out to platform runners for jlink + packaging.

**CI system**: **GitHub Actions** is the target (the `winze` repo is on GitHub — `github.com/dorme/winze` — and all runners needed are available on github.com's hosted pool). A GitLab CI equivalent can be written later if the repo is mirrored somewhere else, but GitHub Actions is simpler: no self-hosted runner required for any of the four targets.

| Target | Runner | Steps |
|--------|--------|-------|
| macOS arm64 | `macos-14` (Apple Silicon, hosted) | `setup-java` → `make uber` → `make package PLATFORM=macos-arm64` → `make app` → upload `.tar.gz` + `.dmg` |
| Linux amd64 | `ubuntu-latest` | `setup-java` → `make package PLATFORM=linux-amd64` → upload `.tar.gz` |
| Linux arm64 | `ubuntu-24.04-arm` (hosted) | `setup-java` → `make package PLATFORM=linux-arm64` → upload `.tar.gz` |
| Windows amd64 | `windows-latest` | `setup-java` → `make package PLATFORM=windows-amd64` → upload `.zip` |

Produces: `winze-macos-arm64.tar.gz`, `winze-macos-arm64.dmg`, `winze-linux-amd64.tar.gz`, `winze-linux-arm64.tar.gz`, `winze-windows-amd64.zip`. All attached to a GitHub release.

**Why run uberjar build per-platform instead of building once?** Because running the Clojure build on each runner costs ~30s and sidesteps the artefact-passing / checksum complexity of building once and sharing across jobs. The uberjar is identical byte-for-byte across platforms (Clojure compilation is deterministic), but dealing with job-to-job artefacts to prove that is more trouble than re-running `clj -T:build uber`.

**Why not cross-compile on a single runner?** Possible, but `macos-14` cannot run Windows `codesign`/`signtool`, can't exercise the Linux launcher in anger, and can't test `.app` double-click behavior for the other platforms. Matrix runners give real platform validation for free.

## Branding Assets & Packaging Destinations

All source assets live under `winze-server/resources/branding/`. Design concept: a faceted crystal/gemstone on dark background. Purple palette (`#9B8FE0` amethyst primary). See `BRAND-GUIDE.md` for the full brand guide.

### Source → SVG masters (canonical, PNGs derived via `resvg`)

| SVG Source | Purpose |
|-----------|---------|
| `icons/winze-icon-512.svg` | Master app icon — all icon sizes and formats derived from this |
| `statusbar/winze-statusbar-macos.svg` | macOS menu bar template (18x18, black+alpha) |
| `statusbar/winze-statusbar-macos@2x.svg` | macOS menu bar Retina template (36x36) |
| `statusbar/winze-statusbar-windows.svg` | Windows system tray (16x16, full color) |
| `statusbar/winze-statusbar-windows-48.svg` | Windows system tray high-DPI (48x48, full color) |
| `statusbar/winze-statusbar-linux.svg` | Linux indicator (24x24, full color) |
| `statusbar/winze-symbolic.svg` | Linux symbolic icon (16x16, uses `currentColor`) |
| `wordmark/winze-wordmark-{light,dark}.svg` | Wordmark for dark/light backgrounds |
| `wordmark/winze-wordmark-slogan-{light,dark}.svg` | Wordmark with slogan variant |

### Packaging Destination Map — Per Platform

#### macOS `.app` Bundle

| Source Asset | Destination in `Winze.app/` | Used By |
|-------------|---------------------------|---------|
| `icons/winze.icns` | `Contents/Resources/winze.icns` | Finder, Dock, Spotlight, `CFBundleIconFile` in Info.plist |
| `statusbar/macos/winzeTemplate.png` | `Contents/Resources/winzeTemplate.png` | SWT `NSStatusBar` menu bar icon (OS auto-tints for light/dark) |
| `statusbar/macos/winzeTemplate@2x.png` | `Contents/Resources/winzeTemplate@2x.png` | Same, Retina displays |
| `wordmark/winze-wordmark-light.png` | `Contents/Resources/` (optional) | About dialog / splash (future) |

The `.icns` is mandatory for a proper `.app`. The `winzeTemplate` PNGs are needed once `ui.clj` status bar is implemented. The `Template` suffix in the filename is an Apple convention — macOS renders the image as a silhouette, automatically adapting to menu bar appearance.

#### macOS `.tar.gz` / `.dmg` (no `.app`)

| Source Asset | Destination in package | Used By |
|-------------|----------------------|---------|
| `icons/png/winze-icon-512.png` | `README.txt` reference (not embedded) | Documentation only |

The current `make package` tar.gz is headless — no icons needed. If a `.dmg` is built, the `.icns` is used for the DMG volume icon via `hdiutil`.

#### Windows `.zip`

| Source Asset | Destination in `winze-windows-amd64/` | Used By |
|-------------|--------------------------------------|---------|
| `statusbar/windows/winze.ico` | `bin/winze.ico` | System tray icon (SWT `Shell.setImage`) — multi-size ICO (16+24+32+48+256) |
| `statusbar/windows/winze-tray-{16,24,32,48,256}.png` | Not packaged separately | Only needed if loading individual PNGs at runtime instead of `.ico` |
| `icons/png/winze-icon-256.png` | `winze-icon.png` (optional) | Shortcut icon, Explorer preview |

Windows uses `.ico` for both the system tray and any desktop shortcut. The multi-size `.ico` lets Windows pick the best resolution for each context (16px for tray, 48px for Explorer, 256px for high-DPI).

#### Linux `.tar.gz`

| Source Asset | Destination in `winze-linux-{amd64,arm64}/` | Used By |
|-------------|---------------------------------------------|---------|
| `statusbar/linux/winze-indicator-{16..48}.png` | `share/icons/` or loaded from classpath | AppIndicator / `StatusNotifierItem` tray icon |
| `statusbar/winze-symbolic.svg` | `share/icons/hicolor/scalable/status/winze-symbolic.svg` | GNOME/Freedesktop symbolic icon (monochrome, uses `currentColor`) |
| `icons/png/winze-icon-{48,128,256,512}.png` | `share/icons/hicolor/{48x48,128x128,...}/apps/winze.png` | Desktop entry icon, application menu |

For Freedesktop `.desktop` file integration (`install.sh` could optionally install `winze.desktop` to `~/.local/share/applications/`), the icon PNGs go into the standard `hicolor` icon theme hierarchy.

#### All Platforms — In the Uberjar (classpath)

| Source Asset | Classpath Path | Used By |
|-------------|---------------|---------|
| All `resources/branding/**` | `branding/**` on classpath | `ui.clj` can load icons at runtime via `(io/resource "branding/...")` regardless of install location |

Since `resources/` is in the uberjar's source paths, all branding assets are available on the classpath at runtime. This is the fallback for the SWT UI — it can load the platform-appropriate icon from the classpath even if the packaging step didn't place it in a special OS location.

### Other resources on the classpath (not branding)

The uberjar also bundles live data trees under `resources/` that this document does not enumerate in the per-platform tables above, because they are platform-independent and ship unchanged inside the JAR: `dictionaries/`, `keybindings/`, `languages/`, `logback.xml`, `theme.edn`. Mentioned here only so a future reader does not think they are missing.

### Additional branding subtrees

`resources/branding/` contains two further subdirectories not broken out in the tables above: `header/` (wordmark variants — overlaps with `wordmark/`) and `ui/` (`winze-edit.svg`, `winze-tab-document.svg`, plus `png/`). `icons/` also includes a 16px SVG variant (`winze-icon-16.svg`) in addition to the 512px master. These exist; the tables do not yet route them to per-platform destinations.

### Other source trees

`src/llm_memory/highlight/` (`core.clj` + `loader.clj`) is not mentioned in the packaging narrative above. It contains the canonical JAR-safe classpath-enumeration pattern referenced from CLAUDE.md, and is relevant to how SWT-side resource loading behaves inside a packaged `.app`.

### Makefile prerequisite helpers

The Makefile's `check-java`, `check-clj`, and `check-bb` targets interactively detect Homebrew, offer to install Clojure, and print platform-specific install instructions on failure — a feature not described in the "Prerequisites for building" paragraph above.

### Assets Not Packaged (Source/Documentation Only)

| Asset | Reason |
|-------|--------|
| `icons/winze-icon-512.svg` | SVG master — used to regenerate PNGs, not shipped |
| `statusbar/winze-statusbar-*.svg` | SVG sources — rasterized versions shipped instead |
| `wordmark/*.svg` | SVG sources |
| `BRAND-GUIDE.md` | Developer reference |

### UI: Current State (supersedes the "UI Stub" in earlier drafts)

The SWT UI is real and production — see the "UI: Full SWT Desktop App" section above for the namespace inventory. No `src/llm_memory/server/ui.clj` exists; the code lives under `src/llm_memory/ui/`.

**SWT dependency note**: SWT is already in `deps.edn` indirectly, via `io.github.coconutpalm/clojure-desktop-toolkit 0.5.1`. CDT bundles SWT 4.38 for all six supported `(os,arch)` combinations as ZIP payloads inside its own JAR; at runtime it picks the right ZIP, extracts JARs + natives to a temp dir, and adds them to the classpath via `clojure.repl.deps/add-lib`. That requires **Clojure 1.12+** (see the comment at the top of `deps.edn`). No manual per-platform SWT JAR is declared in `deps.edn` — CDT handles it.

**macOS SWT JVM flag**: The macOS launcher **must** pass `-XstartOnFirstThread` to the JVM as the first argument (before `--add-opens` etc.). SWT on macOS requires the Cocoa event loop to run on the main thread (thread 0) — without this flag, SWT initialization crashes with `SWTException: Invalid thread access`. This is a hard requirement for any SWT/Cocoa UI, not specific to Winze. The flag is macOS-only; Linux and Windows launchers must not include it. Already wired in:
- [Makefile `run` target](../../winze-server/Makefile) — `JAVA_OPTS` has a conditional `$(UNAME_S)=Darwin` branch
- [pkg/bin/winze-server](../../winze-server/pkg/bin/winze-server) — `case "$(uname -s)"` switch
- Needs to be added to `Contents/MacOS/winze-launcher` in the future `.app` bundle (Step 5)

## macOS Code Signing & Notarization

**Decision**: Defer signing to a later step (Step 6). Unsigned builds work — users bypass Gatekeeper once via `xattr -cr` or right-click → Open.

**Reference implementation**: [Freeplane](https://github.com/freeplane/freeplane/blob/1.12.x/mac.dist.gradle) (open-source Java/JVM app) uses a conditional three-stage approach:

1. **JAR signing** — JARs containing native code (JNA, ONNX Runtime, dtlvnative) must be individually signed with `codesign` before the app bundle is signed. Freeplane uses a shell script (`codesign/sign-jars-on-mac.sh`) for this.
2. **App/binary signing** — `codesign --deep --force --sign <identity>` on the JRE, `bb` binary, and launcher scripts.
3. **Notarization** — `xcrun notarytool submit <archive> --keychain-profile <profile>` submits the final `.tar.gz`/`.dmg` to Apple for notarization.

**All three stages are conditional** — guarded by the presence of a signing identity in a config file. When absent, the build produces an unsigned package. This means:
- Contributors without an Apple Developer account ($99/year) can still build and test
- Only the release maintainer needs signing credentials
- CI can produce unsigned artifacts on Linux runners; a separate macOS signing step runs only when credentials are available

**Keychain setup** (one-time, on the signing machine):
```bash
# Store notarization credentials in keychain
xcrun notarytool store-credentials "winze-notarize" \
  --apple-id <apple-id> --team-id <team-id> --password <app-specific-password>
```

## Resolved Questions

| # | Question | Answer |
|---|----------|--------|
| Q1 | Which CI system? | **GitHub Actions** — the repo is on GitHub and all four target-platform runners are available on github.com's hosted pool (`macos-14`, `ubuntu-latest`, `ubuntu-24.04-arm`, `windows-latest`). No self-hosted runner required. GitLab CI can be added later as a mirror if needed. |
| Q2 | macOS code signing needed? | **Deferred.** Unsigned builds work with a one-time Gatekeeper bypass (`xattr -cr` or right-click → Open). Add signing later if distribution friction warrants the $99/year Apple Developer enrollment. Freeplane's conditional signing pattern (see above) is the model to follow. |
| Q3 | Should the installer detect platform automatically or require explicit selection? | **Auto-detect on Unix; PowerShell script on Windows.** `make package` (without explicit `PLATFORM=`) detects the host via `uname -s` + `uname -m`. Explicit `PLATFORM=<target>` remains available; combined with a foreign-JDK cache it enables true cross-compilation (see "Cross-compilation from macOS" above and PLAN.md Step 3bis). macOS x86_64 is hard-rejected at Makefile level (not just documented as unsupported). Windows lacks `make`/`uname`, so it gets a separate `install.ps1` that detects architecture via `$env:PROCESSOR_ARCHITECTURE`. Windows users typically download the pre-built `.zip` from CI rather than building from source. |
| Q4 | Does the uberjar bundle native libs for all target platforms? | **Yes, fully.** dtlvnative (linux-arm64, linux-x86_64, macosx-arm64, windows-x86_64), ONNX Runtime (linux-aarch64, linux-x64, osx-aarch64, osx-x64, win-x64), JNA (all majors), zstd-jni (all majors), and — via CDT — SWT 4.38 as six zipped distributions (cocoa-macosx-{aarch64,x86_64}, gtk-linux-{aarch64,x86_64}, win32-win32-{aarch64,x86_64}). **Single uberjar is fully cross-platform** — no per-platform JAR builds needed. |
| Q5 | Is Linux/GTK supported? | **Best-effort only.** CDT ships the GTK SWT zips but Linux/GTK is known to have compatibility issues between SWT 4.38's bundled GTK and various distro GTK versions. The headless MCP path (search, indexing, nREPL) works reliably on Linux; the SWT UI may fail at runtime on some Linux/GTK combinations. Linux packages ship regardless — server-side/CI use of Winze is the dominant Linux use case. |
| Q6 | Is Windows arm64 a supported target? | **No.** CDT ships a Windows arm64 SWT zip, but the rest of the stack (ONNX Runtime, dtlvnative) does not provide Windows arm64 natives. Do not list it as a target or attempt to build one. |
