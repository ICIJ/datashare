# Theme-Adaptive Monochrome Tray Icon — Design

**Date:** 2026-06-10
**Status:** Approved design, ready for implementation planning

## Problem

Datashare's system tray uses a full-color gradient "D" icon
(`datashare-app/src/main/resources/datashare.png`, 128×128 RGBA). macOS menu
bars and Gnome/Ubuntu top bars conventionally use **monochrome "template"
icons** that the OS recolors to match the active light/dark theme. A color
gradient never adapts and looks out of place on those platforms.

## Goal

Render a **monochrome, theme-adaptive** tray icon on **macOS, Gnome/Ubuntu, and
Windows**:

- **macOS:** the OS recolors a black silhouette live, tracking theme changes
  automatically (template image).
- **Linux/Windows:** detect the system theme **once at tray creation** and pick
  a black or (code-tinted) white silhouette. Theme changes are picked up on
  restart, not live. (No background watcher.)

## Decisions (locked during brainstorming)

| Decision | Choice |
| --- | --- |
| Adaptation model | True theme-adaptive (live on macOS, startup-detect on Linux/Win) |
| Platforms | macOS + Gnome/Ubuntu + Windows, full effort |
| Asset source | Designer supplies one **black + alpha silhouette** (delivered) |
| Asset authoring | High-res PNG `519×900` (portrait) + matching SVG; code tints, fits & resizes |
| Light variant | Derived in code by tinting the black silhouette to white |
| Linux/Win live tracking | No — detect once at startup |
| Architecture | Platform-strategy with small focused helpers (no new heavy deps) |

> **Asset format note:** PNG is the default (no new dependencies). If an SVG
> source is preferred, a rasterizer dependency (e.g. Batik) must be added — to
> be flagged before implementation if desired.

## Library context

- dorkbox `SystemTray` 4.4 (`datashare-app/pom.xml`).
- dorkbox auto-selects a backend: GTK/AppIndicator on Linux, AWT
  (`java.awt.SystemTray`) on macOS/Windows.

## Architecture

All code lives in the existing `org.icij.datashare.tray` package.

```
DatashareSystemTray.loadIcon()
        │
        ▼
  TrayIconProvider.icon() ──► java.awt.Image
        │              │
        │              ├── SystemThemeDetector.detect() ──► DARK | LIGHT | UNKNOWN
        │              └── IconTinter.tint(img, color, sizePx) ──► recolored + resized
        ▼
  (macOS) TrayIconProvider.prepare(): force TrayType.Awt + set
          apple.awt.enableTemplateImages=true, BEFORE SystemTray.get()
```

### Components

- **`SystemThemeDetector`** — single responsibility: report the OS theme as
  `DARK | LIGHT | UNKNOWN`. Branches by OS internally; each branch shells out and
  parses:
  - Linux: `gsettings get org.gnome.desktop.interface color-scheme`
    (`prefer-dark` → DARK; `default`/`prefer-light` → LIGHT).
  - Windows: registry `SystemUsesLightTheme`
    (`0x0` → DARK; `0x1` → LIGHT).
  - macOS: `defaults read -g AppleInterfaceStyle` — only used by the macOS
    fallback path (see Error Handling). On the primary macOS path the detector
    is **not** consulted because the OS recolors for us.
  - The shell-out is behind an injectable seam (a command-runner abstraction or
    overridable `protected` method) so it can be unit-tested with canned output.

- **`IconTinter`** — pure image utility, no I/O, no platform knowledge:
  `tint(BufferedImage silhouette, Color target, int sizePx)`. Recolors opaque
  pixels to `target`, preserves alpha, and fits the source into a **square
  `sizePx × sizePx`** canvas. The delivered mark is portrait (519×900), so the
  resize must **preserve aspect ratio and center the mark with transparent
  padding** — never stretch to fill. Trivially testable.

- **`TrayIconProvider`** — the only class that knows platform rules. Loads the
  silhouette resource once; decides black vs tinted-white vs template; owns the
  icon size constants; sets the macOS template property via `prepare()`. The
  macOS-backend risk and its fallback live here.

- **`DatashareSystemTray`** — unchanged except `loadIcon()` delegates to
  `TrayIconProvider`, and `createSystemTray()` calls `TrayIconProvider.prepare()`
  (macOS-guarded) before `SystemTray.get()`. Keeps its existing
  "missing asset → pink square default" safety net.

Detector (the *fact*: what theme is active) and provider (the *policy*: which
color for which theme) are kept separate so they don't tangle.

## Data flow

At tray creation (`create` → `createSystemTray` → `loadIcon`):

1. **macOS setup (critical, ordering-sensitive):** if OS is macOS, call
   `TrayIconProvider.prepare()` **before** `SystemTray.get()` to (a) force
   `SystemTray.FORCE_TRAY_TYPE = TrayType.Awt` so the tray routes through
   `java.awt.TrayIcon`, and (b) set `apple.awt.enableTemplateImages=true`.
   dorkbox's default macOS tray is the native `Osx` type, for which the AWT
   template property has no effect — hence forcing `Awt`. Both must be set
   before the toolkit initializes the tray image, or they are silently ignored.
2. `loadIcon()` calls `TrayIconProvider.icon()`:
   - Load `datashare-template.png` (black + alpha) → `BufferedImage`. Missing →
     return null → `DatashareSystemTray` falls back to pink default.
   - **macOS:** return the black silhouette (resized). OS inverts it live.
   - **Linux/Windows:** `SystemThemeDetector.detect()`:
     - `DARK` → tint to **white**.
     - `LIGHT` → keep **black**.
     - `UNKNOWN` → default to **white** (legible on the common dark bar).
   - Resize via `IconTinter` (16 px Linux/Windows; ~22 px macOS, let dorkbox/OS
     scale).
3. `systemTray.setImage(image)`.

Theme changes: macOS live; Linux/Windows picked once at startup (no watcher,
nothing to shut down).

## Assets & resources

- **New:** `datashare-app/src/main/resources/datashare-template.png` — black +
  alpha silhouette (transparent background), delivered at 519×900 portrait.
  Source assets exist at `~/datashare-template.png` and `~/datashare-template.svg`
  (PNG is the path of record; SVG kept for future re-authoring). One file ships;
  code tints/fits/resizes.
- **`datashare.png` (color gradient):** referenced **only** by the tray code
  (verified via grep). Once the tray switches to the template asset it is fully
  unused and can be removed. Removal is optional and can be a follow-up.
- **Constants:** `DATASHARE_ICON_FILENAME` → `datashare-template.png`;
  icon-size constants move into `TrayIconProvider` (Linux/Win 16, macOS ~22).
- **No new runtime dependencies** (PNG path). Detection uses `Runtime.exec`
  with already-present OS tools (`gsettings`, `reg`, `defaults`).

## Error handling & fallbacks

Layered; any failure degrades gracefully instead of crashing the tray:

- **Missing/unreadable asset** → provider returns null → existing pink-square
  default.
- **Theme detection failure** (missing command, non-zero exit, unparseable
  output, timeout) → `UNKNOWN` → default to white. Detection uses a short
  timeout so a hung command can't block tray startup.
- **macOS template backend risk** — the primary path forces `TrayType.Awt` +
  the template property (see Data flow). Verify on a real Mac that the icon
  renders as a recolored template and the menu works under the `Awt` tray. If
  the `Awt` tray misbehaves on macOS, fall back to the native `Osx` tray and
  treat macOS like Linux/Windows (detect via `defaults read -g
  AppleInterfaceStyle` + tint, startup-only, no live updates). The macOS branch
  in `SystemThemeDetector` makes this fallback cheap to switch on.
- **`SystemTray.get()` null / headless** → unchanged; `create()` returns null,
  app runs tray-less.

All failure paths log at WARN with context, matching the current `loadIcon`
logging style.

## Testing

Mirrors the existing `DatashareSystemTrayTest` / `SystemTrayTestManual` split;
all automated tests are headless (CI has no display).

- **`IconTinterTest`** (pure, fast) — opaque pixels become target color, alpha
  preserved, transparent stays transparent, output is square `sizePx × sizePx`,
  and a portrait source is centered with transparent padding (aspect ratio
  preserved, not stretched).
- **`SystemThemeDetectorTest`** — inject canned command output:
  `prefer-dark` → DARK; `default`/`prefer-light` → LIGHT; Windows `0x0` → DARK,
  `0x1` → LIGHT; garbage/empty/non-zero-exit/timeout → UNKNOWN. No real
  subprocess.
- **`TrayIconProviderTest`** — stub detector + fixed OS: macOS → black + property
  set; Linux/Win DARK → white; LIGHT → black; UNKNOWN → white; missing asset →
  null.
- **`DatashareSystemTrayTest`** (existing) — extend: missing-asset path still
  yields pink default; keep tray-wiring assertions.
- **Manual (`SystemTrayTestManual`)** — note how to eyeball each OS (toggle macOS
  theme to see live recolor; restart on Linux/Win).

## Out of scope

- Live theme tracking on Linux/Windows (explicitly deferred; restart picks up
  changes).
- Redesigning the icon mark itself (designer supplies the silhouette).
- Removing the now-unused `datashare.png` (optional follow-up).
