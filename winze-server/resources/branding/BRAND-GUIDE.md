# Winze Brand Guide

## Concept

**Winze** — a vertical shaft sunk from within a mine to explore deeper levels. The brand identity centers on **the crystal**: the precious, structured artifact you find when you mine deep into memory. The faceted gem represents the organized, indexed nature of the knowledge stored in the system — every facet is a chunk, every refraction a connection between documents.

## Icon: The Crystal

The product icon is a faceted gemstone (emerald-cut profile) viewed slightly from above. Key features:

- **Table facet** at top catches light — the entry point, where queries go in
- **Crown facets** show the structure — multiple angles on the same knowledge
- **Pavilion** tapers to a point below — depth of retrieval
- **Internal refraction lines** — connections between documents, cross-references
- **Sparkle highlight** on the table — the moment of discovery

The crystal floats on a dark circular background representing the deep underground.

## Color Palette

| Role | Name | Hex | Usage |
|------|------|-----|-------|
| **Primary Light** | Lavender Crystal | `#C4B8FF` | Table facets, highlights, light-mode text accents |
| **Primary** | Amethyst | `#9B8FE0` | Left crown facets, edge highlights, link color |
| **Primary Mid** | Deep Violet | `#7B6FC0` | Crystal body gradient start, ambient glow |
| **Secondary** | Royal Purple | `#5548A0` | Main crystal faces, buttons, interactive elements |
| **Secondary Dark** | Indigo | `#4A3F90` | Right facets, shadow side |
| **Dark** | Deep Amethyst | `#3A2F80` | Pavilion dark faces |
| **Darkest** | Obsidian Purple | `#241E5E` | Deepest crystal tones |
| **Background** | Mine Shaft | `#1E1B2E` | Dark UI backgrounds |
| **Background Deep** | Bedrock | `#0E0D18` | Deepest background |
| **Highlight** | Crystal White | `#E8E0FF` | Light text on dark, refraction highlights |
| **Spark** | Pure White | `#FFFFFF` | Sparkle highlights, maximum contrast |

### Usage Guidelines

- **Dark mode** (primary): Crystal colors pop against `#1E1B2E` / `#0E0D18` backgrounds
- **Light mode**: Use `#3A2F80` through `#241E5E` for the crystal; `#1E1B2E` for text
- **Accent color**: `#9B8FE0` (Amethyst) for links, active states, focus rings
- **Error states**: avoid red; use a desaturated warm tone to stay in palette
- **Success states**: use `#C4B8FF` (Lavender Crystal) — the sparkle means "found it"

## Typography

- **Font**: Inter (primary), Plus Jakarta Sans or Outfit (alternates)
- **Wordmark weight**: 300 (Light) with `letter-spacing: 4px`
- **Body text**: 400 (Regular)
- **Headings**: 500 (Medium)
- **Wordmark case**: always lowercase `winze` — it's a common noun, a tool name

## Icon Assets

### Product Icon (App Icon)

| File | Purpose |
|------|---------|
| `icons/winze-icon-512.svg` | **Master source** — all sizes derived from this |
| `icons/png/winze-icon-{16,32,48,64,128,256,512,1024}.png` | Rasterized PNGs |
| `icons/winze.icns` | macOS application icon bundle |

### Status Bar / System Tray Icons

**macOS:**

| File | Size | Notes |
|------|------|-------|
| `statusbar/winze-statusbar-macos.svg` | 18x18 source | Black + alpha template |
| `statusbar/winze-statusbar-macos@2x.svg` | 36x36 source | Retina template |
| `statusbar/macos/winzeTemplate.png` | 18x18 | Rasterized template |
| `statusbar/macos/winzeTemplate@2x.png` | 36x36 | Rasterized Retina template |

macOS template images use **black with varying opacity** — the OS applies color automatically based on menu bar appearance. The `Template` suffix in the filename is required.

**Windows:**

| File | Size | Notes |
|------|------|-------|
| `statusbar/winze-statusbar-windows.svg` | 16x16 source | Full color, simplified |
| `statusbar/winze-statusbar-windows-48.svg` | 48x48 source | Full color, more detail |
| `statusbar/windows/winze-tray-{16,24,32,48,256}.png` | Multi-size | Individual PNGs |
| `statusbar/windows/winze.ico` | Multi-size | Combined .ico (16+24+32+48+256) |

**Linux:**

| File | Size | Notes |
|------|------|-------|
| `statusbar/winze-statusbar-linux.svg` | 24x24 source | Full color indicator |
| `statusbar/winze-symbolic.svg` | 16x16 source | Monochrome, uses `currentColor` |
| `statusbar/linux/winze-indicator-{16,22,24,32,48}.png` | Multi-size | Rasterized PNGs |

For GNOME/Freedesktop, install `winze-symbolic.svg` into `hicolor/scalable/status/`.

### Wordmark

| File | Variant | Notes |
|------|---------|-------|
| `wordmark/winze-wordmark-light.svg` | Light text | For dark backgrounds |
| `wordmark/winze-wordmark-dark.svg` | Dark text | For light backgrounds |
| `wordmark/winze-wordmark-light.png` | Light text | Rasterized @2x (680x160) |
| `wordmark/winze-wordmark-dark.png` | Dark text | Rasterized @2x (680x160) |

## Rasterization

SVG sources are canonical. To regenerate PNGs:

```bash
# Requires: resvg (brew install resvg)
resvg input.svg output.png -w WIDTH -h HEIGHT

# macOS .icns: populate a .iconset directory, then:
iconutil -c icns winze.iconset -o winze.icns

# Windows .ico: combine PNGs with ImageMagick:
magick 16.png 24.png 32.png 48.png 256.png winze.ico
```

**Do not use ImageMagick's built-in SVG renderer** (MSVG) — it drops gradients and opacity compositing. Always use `resvg` for SVG→PNG conversion.

## Clear Space & Minimum Sizes

- **Clear space**: maintain at least 25% of the icon's width as padding on all sides
- **Minimum product icon size**: 32x32 px (below this, use the simplified tray variants)
- **Minimum wordmark width**: 200px
- **Never stretch, rotate, or recolor the crystal** — use the provided light/dark variants

## The Name

- Written as **winze** (lowercase) in running text and UI
- Written as **Winze** (title case) only at the start of a sentence or in a title
- Never written as WINZE, WinZe, or similar
