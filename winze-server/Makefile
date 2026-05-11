DATA_DIR   := $(HOME)/.local/share/winze
UBER_JAR   := target/winze-server.jar
PROXY      := mcp-proxy.clj
SKILLS_DIR := $(HOME)/.claude/skills
RULES_DIR  := $(HOME)/.claude/rules

# ── Platform auto-detection ──────────────────────────────────────
BB_VERSION := 1.12.215
JLINK_MODULES := java.base,java.desktop,java.naming,java.sql,java.management,jdk.management,jdk.unsupported,java.net.http,java.logging,java.xml

# ── Temurin foreign-JDK settings (for cross-platform jlink) ─────
# TEMURIN_VERSION_URL: '+' → '%2B' for the GitHub release directory path
# The filename portion replaces '+' with '_' (Adoptium convention)
TEMURIN_VERSION             := 21.0.5+11
TEMURIN_VERSION_URL         := 21.0.5%2B11
TEMURIN_BASE                := https://github.com/adoptium/temurin21-binaries/releases/download/jdk-$(TEMURIN_VERSION_URL)
TEMURIN_SUFFIX_linux-amd64  := x64_linux_hotspot_21.0.5_11.tar.gz
TEMURIN_SUFFIX_linux-arm64  := aarch64_linux_hotspot_21.0.5_11.tar.gz
TEMURIN_SUFFIX_windows-amd64 := x64_windows_hotspot_21.0.5_11.zip

# ── Host platform (always detected — used for cross-compile awareness) ──
UNAME_S_HOST := $(shell uname -s)
UNAME_M_HOST := $(shell uname -m)
ifeq ($(UNAME_S_HOST),Darwin)
  HOST_PLATFORM := macos-arm64
else ifeq ($(UNAME_S_HOST),Linux)
  ifeq ($(UNAME_M_HOST),aarch64)
    HOST_PLATFORM := linux-arm64
  else
    HOST_PLATFORM := linux-amd64
  endif
else
  HOST_PLATFORM := unknown
endif

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

PKG_DIR     := target/winze-$(PLATFORM)
PKG_ARCHIVE := target/winze-$(PLATFORM).tar.gz

# ── .app bundle variables (macOS only) ──────────────────────────
APP_DIR     := target/Winze.app
APP_VERSION := $(shell sed -n 's/^.def version "\(.*\)".*/\1/p' build.clj)

# ── Cross-compile: foreign JDK jmods path ───────────────────────
FOREIGN_JDK_CACHE := target/foreign-jdks
FOREIGN_JDK_DIR   := $(FOREIGN_JDK_CACHE)/$(PLATFORM)-$(TEMURIN_VERSION)
FOREIGN_JDK_JMODS := $(FOREIGN_JDK_DIR)/jmods

ifeq ($(PLATFORM),$(HOST_PLATFORM))
  JLINK_MODULE_PATH :=
else
  JLINK_MODULE_PATH := --module-path $(FOREIGN_JDK_JMODS)
endif

.PHONY: clean uber install run install-winze uninstall-winze jlink-jre download-bb foreign-jdk package app dmg check-java check-clj check-bb

## ── Prerequisite checks ──────────────────────────────────────────

check-java:
	@if ! command -v java >/dev/null 2>&1; then \
	  echo ""; \
	  echo "Error: JDK 21+ is required but 'java' was not found on PATH."; \
	  echo ""; \
	  echo "Install Eclipse Temurin:"; \
	  echo "  macOS:   brew install temurin@21"; \
	  echo "  Linux:   https://adoptium.net/installation/"; \
	  echo "  Windows: https://adoptium.net/"; \
	  echo ""; \
	  exit 1; \
	fi
	@JAVA_MAJOR=$$(java -version 2>&1 | head -1 | sed 's/.*version "\([0-9]*\).*/\1/'); \
	if [ "$$JAVA_MAJOR" -lt 21 ] 2>/dev/null; then \
	  echo ""; \
	  echo "Error: JDK 21+ is required but found Java $$JAVA_MAJOR."; \
	  echo ""; \
	  echo "Install Eclipse Temurin 21+:"; \
	  echo "  macOS:   brew install temurin@21"; \
	  echo "  Linux:   https://adoptium.net/installation/"; \
	  echo "  Windows: https://adoptium.net/"; \
	  echo ""; \
	  exit 1; \
	fi

check-clj: | check-java
	@if command -v clj >/dev/null 2>&1; then exit 0; fi; \
	echo ""; \
	echo "The Clojure CLI ('clj') is required but was not found on PATH."; \
	echo ""; \
	if [ ! -t 0 ]; then \
	  echo "Install it before running this target:"; \
	  echo "  macOS (Homebrew): brew install clojure/tools/clojure"; \
	  echo "  Linux/macOS:      https://clojure.org/guides/install_clojure"; \
	  echo ""; \
	  exit 1; \
	fi; \
	echo "Install it now?"; \
	echo ""; \
	if command -v brew >/dev/null 2>&1; then \
	  echo "  Detected Homebrew — will run: brew install clojure/tools/clojure"; \
	else \
	  echo "  Will download and run the official POSIX installer (requires sudo)."; \
	fi; \
	echo ""; \
	printf "Install Clojure? [y/N] "; \
	read -r REPLY; \
	case "$$REPLY" in \
	  [yY]*) ;; \
	  *) echo ""; \
	     echo "To install manually:"; \
	     echo "  macOS (Homebrew): brew install clojure/tools/clojure"; \
	     echo "  Linux/macOS:      https://clojure.org/guides/install_clojure"; \
	     echo ""; \
	     exit 1 ;; \
	esac; \
	if command -v brew >/dev/null 2>&1; then \
	  brew install clojure/tools/clojure; \
	else \
	  echo "Downloading Clojure installer..."; \
	  curl -fSL -O https://github.com/clojure/brew-install/releases/latest/download/posix-install.sh; \
	  chmod +x posix-install.sh; \
	  sudo ./posix-install.sh; \
	  rm -f posix-install.sh; \
	fi; \
	if ! command -v clj >/dev/null 2>&1; then \
	  echo ""; \
	  echo "Error: Clojure installation completed but 'clj' is still not on PATH."; \
	  echo "You may need to restart your shell or add it to PATH manually."; \
	  echo ""; \
	  exit 1; \
	fi; \
	echo ""; \
	echo "✓ Clojure installed successfully."

check-bb:
	@if command -v bb >/dev/null 2>&1; then exit 0; fi; \
	echo ""; \
	echo "Error: Babashka ('bb') is required on PATH for 'make install'."; \
	echo ""; \
	echo "Either install Babashka:"; \
	echo "  macOS:   brew install borkdude/brew/babashka"; \
	echo "  Other:   https://babashka.org"; \
	echo ""; \
	echo "Or use 'make install-winze' instead, which downloads and bundles"; \
	echo "Babashka automatically."; \
	echo ""; \
	exit 1

## ── Build targets ────────────────────────────────────────────────

clean: | check-clj
	clj -T:build clean

uber: | check-clj
	clj -T:build uber

install: uber | check-bb
	@# Stop running server if any (proxy will auto-start on next use)
	-@kill $$(cat $(DATA_DIR)/.pid 2>/dev/null) 2>/dev/null
	-@rm -f $(DATA_DIR)/.nrepl-port $(DATA_DIR)/.pid
	mkdir -p $(DATA_DIR)/lib
	cp $(UBER_JAR) $(DATA_DIR)/lib/winze-server.jar
	cp $(PROXY) $(DATA_DIR)/$(PROXY)
	@# Install rules globally
	mkdir -p $(RULES_DIR)
	cp rules/*.md $(RULES_DIR)/
	@echo "Installed to $(DATA_DIR)"
	@echo "Rules installed to $(RULES_DIR)"
	@echo "Server stopped — will auto-start on next MCP tool call."

JAVA_OPTS := --add-opens=java.base/java.nio=ALL-UNNAMED \
             --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
             --enable-native-access=ALL-UNNAMED
ifeq ($(UNAME_S_HOST),Darwin)
  JAVA_OPTS += -XstartOnFirstThread
endif

run: | check-java
	java $(JAVA_OPTS) -jar $(DATA_DIR)/lib/winze-server.jar

## ── Packaging targets ────────────────────────────────────────────

foreign-jdk:
ifeq ($(PLATFORM),$(HOST_PLATFORM))
	@true
else
	@if [ ! -d "$(FOREIGN_JDK_JMODS)" ]; then \
	  SUFFIX="$(TEMURIN_SUFFIX_$(PLATFORM))"; \
	  if [ -z "$$SUFFIX" ]; then \
	    echo "Error: no Temurin foreign-JDK suffix defined for $(PLATFORM)"; exit 1; \
	  fi; \
	  mkdir -p "$(FOREIGN_JDK_DIR)"; \
	  URL="$(TEMURIN_BASE)/OpenJDK21U-jdk_$$SUFFIX"; \
	  echo "Downloading foreign JDK for $(PLATFORM): $$SUFFIX"; \
	  echo "  $$URL"; \
	  curl -fSL -o "$(FOREIGN_JDK_DIR)/archive" "$$URL"; \
	  TMP="$(FOREIGN_JDK_DIR)/tmp"; \
	  mkdir -p "$$TMP"; \
	  if echo "$$SUFFIX" | grep -q '\.zip$$'; then \
	    unzip -q "$(FOREIGN_JDK_DIR)/archive" -d "$$TMP"; \
	  else \
	    tar -xzf "$(FOREIGN_JDK_DIR)/archive" -C "$$TMP"; \
	  fi; \
	  JMODS="$$(find $$TMP -type d -name jmods | head -1)"; \
	  if [ -z "$$JMODS" ]; then \
	    echo "Error: could not find jmods/ in downloaded archive"; exit 1; \
	  fi; \
	  mv "$$JMODS" "$(FOREIGN_JDK_JMODS)"; \
	  rm -rf "$$TMP" "$(FOREIGN_JDK_DIR)/archive"; \
	  echo "✓ Foreign JDK jmods cached: $(FOREIGN_JDK_JMODS)"; \
	else \
	  echo "  Foreign JDK already cached: $(FOREIGN_JDK_JMODS)"; \
	fi
endif

jlink-jre: uber foreign-jdk
	@echo "Building minimal JRE for $(PLATFORM)..."
	rm -rf target/jre
	jlink --output target/jre \
	      $(JLINK_MODULE_PATH) \
	      --add-modules $(JLINK_MODULES) \
	      --no-header-files --no-man-pages \
	      --strip-debug --compress=zip-6
	@echo "✓ JRE built: $$(du -sh target/jre | cut -f1)"

download-bb:
	@echo "Downloading Babashka $(BB_VERSION) for $(PLATFORM)..."
	@mkdir -p target
	@case $(PLATFORM) in \
	  macos-arm64)  BB_SUFFIX="macos-aarch64" ;; \
	  linux-amd64)  BB_SUFFIX="linux-amd64-static" ;; \
	  linux-arm64)  BB_SUFFIX="linux-aarch64-static" ;; \
	  windows-amd64) BB_SUFFIX="windows-amd64" ;; \
	  *) echo "Unknown platform: $(PLATFORM)"; exit 1 ;; \
	esac; \
	BB_BASE="babashka-$(BB_VERSION)-$$BB_SUFFIX"; \
	if [ "$(PLATFORM)" = "windows-amd64" ]; then \
	  BB_ARCHIVE="$$BB_BASE.zip"; \
	else \
	  BB_ARCHIVE="$$BB_BASE.tar.gz"; \
	fi; \
	URL="https://github.com/babashka/babashka/releases/download/v$(BB_VERSION)/$$BB_ARCHIVE"; \
	echo "  $$URL"; \
	curl -fSL -o "target/$$BB_ARCHIVE" "$$URL"; \
	if [ "$(PLATFORM)" = "windows-amd64" ]; then \
	  unzip -o -d target "target/$$BB_ARCHIVE" bb.exe; \
	else \
	  tar -xzf "target/$$BB_ARCHIVE" -C target bb; \
	fi; \
	rm -f "target/$$BB_ARCHIVE"
	@echo "✓ Babashka: $$(target/bb --version 2>/dev/null || echo 'downloaded (cross-platform binary)')"

package: uber jlink-jre download-bb
	@echo "Assembling package: $(PKG_DIR)..."
	rm -rf $(PKG_DIR)
	mkdir -p $(PKG_DIR)/jre $(PKG_DIR)/lib $(PKG_DIR)/bin
	@# JRE
	cp -R target/jre/ $(PKG_DIR)/jre/
	@# Uberjar
	cp $(UBER_JAR) $(PKG_DIR)/lib/winze-server.jar
	@# Babashka binary
	@if [ "$(PLATFORM)" = "windows-amd64" ]; then \
	  cp target/bb.exe $(PKG_DIR)/bin/bb.exe; \
	else \
	  cp target/bb $(PKG_DIR)/bin/bb; \
	  chmod +x $(PKG_DIR)/bin/bb; \
	fi
	@# Proxy script
	cp $(PROXY) $(PKG_DIR)/bin/$(PROXY)
	@# Launcher scripts
	cp pkg/bin/winze-server $(PKG_DIR)/bin/winze-server
	cp pkg/bin/winze-mcp $(PKG_DIR)/bin/winze-mcp
	chmod +x $(PKG_DIR)/bin/winze-server $(PKG_DIR)/bin/winze-mcp
	@# Windows launchers (windows-amd64 package only)
	@if [ "$(PLATFORM)" = "windows-amd64" ]; then \
	  cp pkg/bin/winze-server.bat $(PKG_DIR)/bin/winze-server.bat; \
	  cp pkg/bin/winze-mcp.bat $(PKG_DIR)/bin/winze-mcp.bat; \
	fi
	@# Install scripts
	cp pkg/install.sh $(PKG_DIR)/install.sh
	chmod +x $(PKG_DIR)/install.sh
	@if [ "$(PLATFORM)" = "windows-amd64" ]; then \
	  cp pkg/install.ps1 $(PKG_DIR)/install.ps1; \
	fi
	@# Skills
	cp -R skills $(PKG_DIR)/skills
	@# Rules
	cp -R rules $(PKG_DIR)/rules
	@# README
	cp pkg/README.txt $(PKG_DIR)/README.txt
	@# Archive
	@echo "Creating archive: $(PKG_ARCHIVE)..."
	@if [ "$(PLATFORM)" = "windows-amd64" ]; then \
	  cd target && zip -qr winze-$(PLATFORM).zip winze-$(PLATFORM)/; \
	else \
	  tar -czf $(PKG_ARCHIVE) -C target winze-$(PLATFORM); \
	fi
	@echo ""
	@echo "✓ Package assembled: $(PKG_DIR)"
	@echo "✓ Archive: $$(du -sh $(PKG_ARCHIVE) | cut -f1) $(PKG_ARCHIVE)"
	@echo ""
	@echo "To install: extract and run ./install.sh"

## ── macOS .app bundle ────────────────────────────────────────────

app: package
	@test "$(UNAME_S_HOST)" = "Darwin" || \
	  (echo "Error: 'make app' is macOS-only (requires hdiutil, codesign)"; exit 1)
	@test "$(PLATFORM)" = "macos-arm64" || \
	  (echo "Error: 'make app' requires PLATFORM=macos-arm64"; exit 1)
	@echo "Building Winze.app (version $(APP_VERSION))..."
	rm -rf $(APP_DIR)
	mkdir -p $(APP_DIR)/Contents/MacOS $(APP_DIR)/Contents/Resources
	@# Info.plist with version substitution
	sed "s/@@VERSION@@/$(APP_VERSION)/g" \
	    pkg/macos/Info.plist.template > $(APP_DIR)/Contents/Info.plist
	printf 'APPL????' > $(APP_DIR)/Contents/PkgInfo
	@# Launcher script
	cp pkg/macos/winze-launcher $(APP_DIR)/Contents/MacOS/winze-launcher
	chmod +x $(APP_DIR)/Contents/MacOS/winze-launcher
	@# App icon
	cp resources/branding/icons/winze.icns $(APP_DIR)/Contents/Resources/winze.icns
	@# Package contents → Resources/
	cp -R $(PKG_DIR)/jre     $(APP_DIR)/Contents/Resources/jre
	cp -R $(PKG_DIR)/lib     $(APP_DIR)/Contents/Resources/lib
	cp -R $(PKG_DIR)/bin     $(APP_DIR)/Contents/Resources/bin
	cp -R $(PKG_DIR)/skills  $(APP_DIR)/Contents/Resources/skills
	cp -R $(PKG_DIR)/rules   $(APP_DIR)/Contents/Resources/rules
	cp    $(PKG_DIR)/install.sh $(APP_DIR)/Contents/Resources/install.sh
	chmod +x $(APP_DIR)/Contents/Resources/install.sh
	@echo "✓ Winze.app: $$(du -sh $(APP_DIR) | cut -f1) $(APP_DIR)"

dmg: app
	@echo "Creating DMG..."
	rm -f target/Winze-macos-arm64.dmg
	hdiutil create -volname Winze \
	  -srcfolder $(APP_DIR) \
	  -ov -format UDZO \
	  target/Winze-macos-arm64.dmg
	@echo "✓ DMG: $$(du -sh target/Winze-macos-arm64.dmg | cut -f1) target/Winze-macos-arm64.dmg"

## ── MCP registration (all global / user scope) ─────────────────

install-winze: package
	@echo "Installing from package..."
	$(PKG_DIR)/install.sh

uninstall-winze:
	@# Deregister MCP server
	-claude mcp remove --scope user winze 2>/dev/null
	@# Remove global skills
	-rm -rf $(SKILLS_DIR)/search-plans $(SKILLS_DIR)/index-plans \
	        $(SKILLS_DIR)/recent-plans $(SKILLS_DIR)/related-plans \
	        $(SKILLS_DIR)/register-plans $(SKILLS_DIR)/list-plan-roots \
	        $(SKILLS_DIR)/help-plans
	-rm -f $(RULES_DIR)/swt-development.md
	@# Stop running server if any
	-kill $$(cat $(DATA_DIR)/.pid 2>/dev/null) 2>/dev/null
	-rm -f $(DATA_DIR)/.nrepl-port $(DATA_DIR)/.pid
	@echo ""
	@echo "✓ MCP server deregistered"
	@echo "✓ Skills removed from $(SKILLS_DIR)"
	@echo ""
	@echo "Data at $(DATA_DIR) preserved (delete manually if unwanted)."
