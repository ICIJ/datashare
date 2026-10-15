# Theme-Adaptive Monochrome Tray Icon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render a monochrome, theme-adaptive tray icon on macOS (live via OS template image), Gnome/Ubuntu, and Windows (startup theme-detect + tint).

**Architecture:** Add three small, single-purpose classes in `org.icij.datashare.tray` — `SystemThemeDetector` (reports OS theme), `IconTinter` (recolors + fits the black silhouette into a square), `TrayIconProvider` (platform policy: black template on macOS, black/white tinted silhouette on Linux/Windows; sets macOS template flags). `DatashareSystemTray` delegates icon loading to `TrayIconProvider`.

**Tech Stack:** Java, dorkbox `SystemTray` 4.4, `java.awt`/`javax.imageio`, JUnit 4 + Mockito + FEST assertions, Maven (multi-module).

---

## Background context for the implementer

- The tray lives in `datashare-app/src/main/java/org/icij/datashare/tray/`.
- dorkbox `SystemTray.setImage(...)` has a `java.awt.Image` overload and a `getMenuImageSize()` that returns the platform's expected icon edge size in px.
- dorkbox's default macOS tray is the native `Osx` type. The JDK feature `apple.awt.enableTemplateImages=true` only works when the tray routes through `java.awt.TrayIcon`, so on macOS we force `SystemTray.FORCE_TRAY_TYPE = SystemTray.TrayType.Awt`. Both must be set **before** `SystemTray.get()`.
- The delivered asset is a **black + alpha** silhouette (transparent background), portrait `519×900`, at `~/datashare-template.png` (SVG sibling at `~/datashare-template.svg`).
- Existing tests: `datashare-app/src/test/java/org/icij/datashare/tray/DatashareSystemTrayTest.java` (mocks the tray; currently asserts `setImage(URL)`), and the manual `SystemTrayTestManual.java`.

**Maven test command pattern (run from the worktree root):**
```bash
mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=<ClassName> test
```
Why this exact invocation (do NOT use `-am`):
- `datashare-db` runs jOOQ codegen against a postgres during its build; that DB is not reachable from the build shell, so we must not rebuild it. Its jar (21.7.0) is already installed in `~/.m2` and consumed as a binary dependency.
- `datashare-index` is shaded and publishes a dependency-reduced POM that hides its elasticsearch deps, so `datashare-app` only compiles when `datashare-index` is built **from source** in the reactor — hence the explicit module list (which omits `datashare-db` and `datashare-nlp-corenlp`, both available as installed jars).
- `-o` uses the populated local `~/.m2`; `-Dgpg.skip=true` avoids TTY-less GPG signing; `-Dsurefire.failIfNoSpecifiedTests=false` lets upstream modules report "no matching tests" without failing.
- The tray tests are headless and need no DB/ES at runtime.

---

## File structure

- Create: `datashare-app/src/main/resources/datashare-template.png` (the silhouette asset)
- Create: `datashare-app/src/main/java/org/icij/datashare/tray/OsFamily.java`
- Create: `datashare-app/src/main/java/org/icij/datashare/tray/IconTinter.java`
- Create: `datashare-app/src/main/java/org/icij/datashare/tray/SystemThemeDetector.java`
- Create: `datashare-app/src/main/java/org/icij/datashare/tray/TrayIconProvider.java`
- Modify: `datashare-app/src/main/java/org/icij/datashare/tray/DatashareSystemTray.java`
- Create: `datashare-app/src/test/java/org/icij/datashare/tray/OsFamilyTest.java`
- Create: `datashare-app/src/test/java/org/icij/datashare/tray/IconTinterTest.java`
- Create: `datashare-app/src/test/java/org/icij/datashare/tray/SystemThemeDetectorTest.java`
- Create: `datashare-app/src/test/java/org/icij/datashare/tray/TrayIconProviderTest.java`
- Modify: `datashare-app/src/test/java/org/icij/datashare/tray/DatashareSystemTrayTest.java`
- Modify: `datashare-app/src/test/java/org/icij/datashare/SystemTrayTestManual.java`

---

## Task 1: Add the silhouette asset and the `OsFamily` enum

**Files:**
- Create: `datashare-app/src/main/resources/datashare-template.png`
- Create: `datashare-app/src/main/java/org/icij/datashare/tray/OsFamily.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tray/OsFamilyTest.java`

- [ ] **Step 1: Copy the delivered asset into resources**

```bash
cp ~/datashare-template.png datashare-app/src/main/resources/datashare-template.png
file datashare-app/src/main/resources/datashare-template.png
```
Expected: `PNG image data, 519 x 900, 8-bit/color RGBA`.

- [ ] **Step 2: Write the failing test for OS-name parsing**

Create `datashare-app/src/test/java/org/icij/datashare/tray/OsFamilyTest.java`:

```java
package org.icij.datashare.tray;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OsFamilyTest {
    @Test
    public void test_parses_macos() {
        assertEquals(OsFamily.MAC, OsFamily.fromName("Mac OS X"));
        assertEquals(OsFamily.MAC, OsFamily.fromName("Darwin"));
    }

    @Test
    public void test_parses_windows() {
        assertEquals(OsFamily.WINDOWS, OsFamily.fromName("Windows 11"));
    }

    @Test
    public void test_parses_linux() {
        assertEquals(OsFamily.LINUX, OsFamily.fromName("Linux"));
    }

    @Test
    public void test_unknown_is_other() {
        assertEquals(OsFamily.OTHER, OsFamily.fromName("SunOS"));
        assertEquals(OsFamily.OTHER, OsFamily.fromName(null));
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=OsFamilyTest test`
Expected: FAIL — `OsFamily` does not exist / does not compile.

- [ ] **Step 4: Implement `OsFamily`**

Create `datashare-app/src/main/java/org/icij/datashare/tray/OsFamily.java`:

```java
package org.icij.datashare.tray;

import java.util.Locale;

public enum OsFamily {
    MAC, WINDOWS, LINUX, OTHER;

    public static OsFamily fromName(String osName) {
        if (osName == null) {
            return OTHER;
        }
        String name = osName.toLowerCase(Locale.ROOT);
        if (name.contains("mac") || name.contains("darwin")) {
            return MAC;
        }
        if (name.contains("win")) {
            return WINDOWS;
        }
        if (name.contains("nix") || name.contains("nux") || name.contains("linux")) {
            return LINUX;
        }
        return OTHER;
    }

    public static OsFamily current() {
        return fromName(System.getProperty("os.name"));
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=OsFamilyTest test`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add datashare-app/src/main/resources/datashare-template.png \
        datashare-app/src/main/java/org/icij/datashare/tray/OsFamily.java \
        datashare-app/src/test/java/org/icij/datashare/tray/OsFamilyTest.java
git commit -m "feat(tray): add monochrome silhouette asset and OsFamily enum"
```

---

## Task 2: `IconTinter` — recolor + fit into a square canvas

**Files:**
- Create: `datashare-app/src/main/java/org/icij/datashare/tray/IconTinter.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tray/IconTinterTest.java`

The tinter recolors **every** pixel's RGB to the target color while preserving each pixel's original alpha, then scales the recolored image to fit a square `sizePx × sizePx` canvas, centered, preserving aspect ratio with transparent padding. Recoloring all pixels (even transparent ones) to the same RGB means anti-aliased downscaling never produces muddy edges — only alpha is blended.

- [ ] **Step 1: Write the failing tests**

Create `datashare-app/src/test/java/org/icij/datashare/tray/IconTinterTest.java`:

```java
package org.icij.datashare.tray;

import org.junit.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IconTinterTest {

    private static BufferedImage opaque(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, 0xFF000000); // opaque black
            }
        }
        return img;
    }

    @Test
    public void test_output_is_square_of_requested_size() {
        BufferedImage out = IconTinter.tint(opaque(10, 10), Color.WHITE, 16);
        assertEquals(16, out.getWidth());
        assertEquals(16, out.getHeight());
    }

    @Test
    public void test_recolors_opaque_pixels_to_target_and_keeps_alpha() {
        // square source fills the whole square canvas -> center is solid
        BufferedImage out = IconTinter.tint(opaque(10, 10), Color.WHITE, 16);
        int center = out.getRGB(8, 8);
        assertEquals("alpha preserved", 0xFF, (center >>> 24) & 0xFF);
        assertEquals("red -> white", 0xFF, (center >> 16) & 0xFF);
        assertEquals("green -> white", 0xFF, (center >> 8) & 0xFF);
        assertEquals("blue -> white", 0xFF, center & 0xFF);
    }

    @Test
    public void test_black_target_produces_black_pixels() {
        BufferedImage out = IconTinter.tint(opaque(10, 10), Color.BLACK, 16);
        int center = out.getRGB(8, 8);
        assertEquals(0xFF, (center >>> 24) & 0xFF);
        assertEquals(0x00, (center >> 16) & 0xFF);
        assertEquals(0x00, (center >> 8) & 0xFF);
        assertEquals(0x00, center & 0xFF);
    }

    @Test
    public void test_portrait_source_is_centered_with_transparent_padding() {
        // portrait 5x10 -> fit into 16 -> 8 wide x 16 tall, centered (left pad ~4px)
        BufferedImage out = IconTinter.tint(opaque(5, 10), Color.WHITE, 16);
        // corners are padding -> fully transparent
        assertEquals("top-left transparent", 0x00, (out.getRGB(0, 0) >>> 24) & 0xFF);
        assertEquals("top-right transparent", 0x00, (out.getRGB(15, 0) >>> 24) & 0xFF);
        // a column at the horizontal centre, mid-height, is opaque content
        int mid = out.getRGB(8, 8);
        assertTrue("centre content is opaque", ((mid >>> 24) & 0xFF) > 0);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=IconTinterTest test`
Expected: FAIL — `IconTinter` does not exist.

- [ ] **Step 3: Implement `IconTinter`**

Create `datashare-app/src/main/java/org/icij/datashare/tray/IconTinter.java`:

```java
package org.icij.datashare.tray;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

public final class IconTinter {

    private IconTinter() {
    }

    /**
     * Recolor every pixel of {@code src} to {@code target} (keeping each pixel's
     * original alpha), then fit the result into a transparent {@code sizePx x sizePx}
     * square, centered, preserving aspect ratio.
     */
    public static BufferedImage tint(BufferedImage src, Color target, int sizePx) {
        BufferedImage recolored = recolor(src, target);
        return fitSquare(recolored, sizePx);
    }

    private static BufferedImage recolor(BufferedImage src, Color target) {
        int w = src.getWidth();
        int h = src.getHeight();
        int rgb = target.getRGB() & 0x00FFFFFF;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int alpha = (src.getRGB(x, y) >>> 24) & 0xFF;
                out.setRGB(x, y, (alpha << 24) | rgb);
            }
        }
        return out;
    }

    private static BufferedImage fitSquare(BufferedImage src, int sizePx) {
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = (double) sizePx / Math.max(w, h);
        int scaledW = Math.max(1, (int) Math.round(w * scale));
        int scaledH = Math.max(1, (int) Math.round(h * scale));
        int offsetX = (sizePx - scaledW) / 2;
        int offsetY = (sizePx - scaledH) / 2;

        BufferedImage canvas = new BufferedImage(sizePx, sizePx, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = canvas.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g2d.drawImage(src, offsetX, offsetY, scaledW, scaledH, null);
        g2d.dispose();
        return canvas;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=IconTinterTest test`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tray/IconTinter.java \
        datashare-app/src/test/java/org/icij/datashare/tray/IconTinterTest.java
git commit -m "feat(tray): add IconTinter for recoloring and square-fitting the silhouette"
```

---

## Task 3: `SystemThemeDetector` — detect OS light/dark theme

**Files:**
- Create: `datashare-app/src/main/java/org/icij/datashare/tray/SystemThemeDetector.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tray/SystemThemeDetectorTest.java`

The detector shells out per OS. The command execution sits behind a `CommandRunner` seam so tests feed canned output without spawning a subprocess.

- [ ] **Step 1: Write the failing tests**

Create `datashare-app/src/test/java/org/icij/datashare/tray/SystemThemeDetectorTest.java`:

```java
package org.icij.datashare.tray;

import org.junit.Test;

import static org.icij.datashare.tray.SystemThemeDetector.Theme.DARK;
import static org.icij.datashare.tray.SystemThemeDetector.Theme.LIGHT;
import static org.icij.datashare.tray.SystemThemeDetector.Theme.UNKNOWN;
import static org.junit.Assert.assertEquals;

public class SystemThemeDetectorTest {

    private static SystemThemeDetector detector(OsFamily os, SystemThemeDetector.CommandRunner runner) {
        return new SystemThemeDetector(os, runner);
    }

    @Test
    public void test_linux_prefer_dark() {
        assertEquals(DARK, detector(OsFamily.LINUX,
                (t, cmd) -> "'prefer-dark'").detect());
    }

    @Test
    public void test_linux_default_is_light() {
        assertEquals(LIGHT, detector(OsFamily.LINUX,
                (t, cmd) -> "'default'").detect());
    }

    @Test
    public void test_linux_prefer_light() {
        assertEquals(LIGHT, detector(OsFamily.LINUX,
                (t, cmd) -> "'prefer-light'").detect());
    }

    @Test
    public void test_linux_command_failure_is_unknown() {
        assertEquals(UNKNOWN, detector(OsFamily.LINUX,
                (t, cmd) -> { throw new RuntimeException("no gsettings"); }).detect());
    }

    @Test
    public void test_windows_light_theme() {
        assertEquals(LIGHT, detector(OsFamily.WINDOWS,
                (t, cmd) -> "    SystemUsesLightTheme    REG_DWORD    0x1").detect());
    }

    @Test
    public void test_windows_dark_theme() {
        assertEquals(DARK, detector(OsFamily.WINDOWS,
                (t, cmd) -> "    SystemUsesLightTheme    REG_DWORD    0x0").detect());
    }

    @Test
    public void test_windows_command_failure_is_unknown() {
        assertEquals(UNKNOWN, detector(OsFamily.WINDOWS,
                (t, cmd) -> { throw new RuntimeException("no reg"); }).detect());
    }

    @Test
    public void test_mac_dark() {
        assertEquals(DARK, detector(OsFamily.MAC,
                (t, cmd) -> "Dark").detect());
    }

    @Test
    public void test_mac_key_absent_is_light() {
        // `defaults read` exits non-zero when the key is absent (Light mode)
        assertEquals(LIGHT, detector(OsFamily.MAC,
                (t, cmd) -> { throw new RuntimeException("key not found"); }).detect());
    }

    @Test
    public void test_other_os_is_unknown() {
        assertEquals(UNKNOWN, detector(OsFamily.OTHER,
                (t, cmd) -> "whatever").detect());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=SystemThemeDetectorTest test`
Expected: FAIL — `SystemThemeDetector` does not exist.

- [ ] **Step 3: Implement `SystemThemeDetector`**

Create `datashare-app/src/main/java/org/icij/datashare/tray/SystemThemeDetector.java`:

```java
package org.icij.datashare.tray;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class SystemThemeDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger(SystemThemeDetector.class);
    private static final long TIMEOUT_MS = 2000;

    public enum Theme { DARK, LIGHT, UNKNOWN }

    /** Seam over process execution; returns trimmed stdout or throws on failure/timeout. */
    @FunctionalInterface
    public interface CommandRunner {
        String run(long timeoutMillis, String... command) throws Exception;
    }

    private final OsFamily os;
    private final CommandRunner runner;

    public SystemThemeDetector(OsFamily os) {
        this(os, new ProcessCommandRunner());
    }

    SystemThemeDetector(OsFamily os, CommandRunner runner) {
        this.os = os;
        this.runner = runner;
    }

    public Theme detect() {
        switch (os) {
            case LINUX:
                return detectLinux();
            case WINDOWS:
                return detectWindows();
            case MAC:
                return detectMac();
            default:
                return Theme.UNKNOWN;
        }
    }

    private Theme detectLinux() {
        try {
            String out = runner.run(TIMEOUT_MS,
                    "gsettings", "get", "org.gnome.desktop.interface", "color-scheme")
                    .toLowerCase(Locale.ROOT);
            if (out.contains("prefer-dark")) {
                return Theme.DARK;
            }
            if (out.contains("prefer-light") || out.contains("default")) {
                return Theme.LIGHT;
            }
            return Theme.UNKNOWN;
        } catch (Exception e) {
            LOGGER.warn("Could not detect Linux theme, defaulting to UNKNOWN", e);
            return Theme.UNKNOWN;
        }
    }

    private Theme detectWindows() {
        try {
            String out = runner.run(TIMEOUT_MS,
                    "reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "SystemUsesLightTheme")
                    .toLowerCase(Locale.ROOT);
            if (out.contains("0x1")) {
                return Theme.LIGHT;
            }
            if (out.contains("0x0")) {
                return Theme.DARK;
            }
            return Theme.UNKNOWN;
        } catch (Exception e) {
            LOGGER.warn("Could not detect Windows theme, defaulting to UNKNOWN", e);
            return Theme.UNKNOWN;
        }
    }

    private Theme detectMac() {
        try {
            String out = runner.run(TIMEOUT_MS, "defaults", "read", "-g", "AppleInterfaceStyle");
            return out.trim().equalsIgnoreCase("Dark") ? Theme.DARK : Theme.LIGHT;
        } catch (Exception e) {
            // key absent => Light mode
            return Theme.LIGHT;
        }
    }

    private static final class ProcessCommandRunner implements CommandRunner {
        @Override
        public String run(long timeoutMillis, String... command) throws Exception {
            Process process = new ProcessBuilder(command).start();
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Command timed out: " + String.join(" ", command));
            }
            if (process.exitValue() != 0) {
                throw new IOException("Command exited " + process.exitValue()
                        + ": " + String.join(" ", command));
            }
            try (InputStream in = process.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=SystemThemeDetectorTest test`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tray/SystemThemeDetector.java \
        datashare-app/src/test/java/org/icij/datashare/tray/SystemThemeDetectorTest.java
git commit -m "feat(tray): add SystemThemeDetector for OS light/dark detection"
```

---

## Task 4: `TrayIconProvider` — platform policy + macOS template setup

**Files:**
- Create: `datashare-app/src/main/java/org/icij/datashare/tray/TrayIconProvider.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tray/TrayIconProviderTest.java`

`prepare()` (macOS only) forces `TrayType.Awt` and sets the template property — must be called before `SystemTray.get()`. `loadTrayImage(preferredSize)` loads the silhouette, picks the color, and returns a fitted `java.awt.Image` (or null if the asset is missing). `loadSilhouette()` is `protected` so tests can simulate a missing asset.

- [ ] **Step 1: Write the failing tests**

Create `datashare-app/src/test/java/org/icij/datashare/tray/TrayIconProviderTest.java`:

```java
package org.icij.datashare.tray;

import dorkbox.systemTray.SystemTray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.awt.Image;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TrayIconProviderTest {

    private static SystemThemeDetector fixedDetector(OsFamily os, SystemThemeDetector.Theme theme) {
        // a detector whose CommandRunner yields output that maps to the desired theme is
        // overkill here; instead inject a runner via the package-private constructor that
        // forces the branch. Simplest: subclass-free stub through the CommandRunner seam.
        SystemThemeDetector.CommandRunner runner;
        switch (theme) {
            case DARK:
                runner = (t, cmd) -> os == OsFamily.WINDOWS ? "0x0" : "'prefer-dark'";
                break;
            case LIGHT:
                runner = (t, cmd) -> os == OsFamily.WINDOWS ? "0x1" : "'prefer-light'";
                break;
            default:
                runner = (t, cmd) -> "garbage";
        }
        return new SystemThemeDetector(os, runner);
    }

    @Before
    public void resetTrayState() {
        System.clearProperty("apple.awt.enableTemplateImages");
        SystemTray.FORCE_TRAY_TYPE = SystemTray.TrayType.AutoDetect;
    }

    @After
    public void clearTrayState() {
        System.clearProperty("apple.awt.enableTemplateImages");
        SystemTray.FORCE_TRAY_TYPE = SystemTray.TrayType.AutoDetect;
    }

    private static int alpha(int argb) { return (argb >>> 24) & 0xFF; }
    private static boolean isWhite(int argb) {
        return ((argb >> 16) & 0xFF) == 0xFF && ((argb >> 8) & 0xFF) == 0xFF && (argb & 0xFF) == 0xFF;
    }
    private static boolean isBlack(int argb) {
        return ((argb >> 16) & 0xFF) == 0x00 && ((argb >> 8) & 0xFF) == 0x00 && (argb & 0xFF) == 0x00;
    }

    // find an opaque content pixel and return its colour predicate result
    private static int firstOpaquePixel(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (alpha(img.getRGB(x, y)) == 0xFF) {
                    return img.getRGB(x, y);
                }
            }
        }
        throw new AssertionError("no fully opaque pixel found");
    }

    @Test
    public void test_linux_dark_theme_yields_white_icon() {
        TrayIconProvider provider = new TrayIconProvider(OsFamily.LINUX,
                fixedDetector(OsFamily.LINUX, SystemThemeDetector.Theme.DARK));
        BufferedImage img = (BufferedImage) provider.loadTrayImage(16);
        assertNotNull(img);
        assertEquals(16, img.getWidth());
        assertTrue("dark theme -> white silhouette", isWhite(firstOpaquePixel(img)));
    }

    @Test
    public void test_linux_light_theme_yields_black_icon() {
        TrayIconProvider provider = new TrayIconProvider(OsFamily.LINUX,
                fixedDetector(OsFamily.LINUX, SystemThemeDetector.Theme.LIGHT));
        BufferedImage img = (BufferedImage) provider.loadTrayImage(16);
        assertTrue("light theme -> black silhouette", isBlack(firstOpaquePixel(img)));
    }

    @Test
    public void test_unknown_theme_defaults_to_white() {
        TrayIconProvider provider = new TrayIconProvider(OsFamily.LINUX,
                fixedDetector(OsFamily.LINUX, SystemThemeDetector.Theme.UNKNOWN));
        BufferedImage img = (BufferedImage) provider.loadTrayImage(16);
        assertTrue("unknown -> white silhouette", isWhite(firstOpaquePixel(img)));
    }

    @Test
    public void test_macos_yields_black_template_and_uses_default_size() {
        TrayIconProvider provider = new TrayIconProvider(OsFamily.MAC,
                fixedDetector(OsFamily.MAC, SystemThemeDetector.Theme.LIGHT));
        BufferedImage img = (BufferedImage) provider.loadTrayImage(0); // 0 -> platform default
        assertEquals(22, img.getWidth());
        assertTrue("macOS template is black", isBlack(firstOpaquePixel(img)));
    }

    @Test
    public void test_prepare_sets_macos_template_flags() {
        TrayIconProvider provider = new TrayIconProvider(OsFamily.MAC,
                fixedDetector(OsFamily.MAC, SystemThemeDetector.Theme.LIGHT));
        provider.prepare();
        assertEquals("true", System.getProperty("apple.awt.enableTemplateImages"));
        assertEquals(SystemTray.TrayType.Awt, SystemTray.FORCE_TRAY_TYPE);
    }

    @Test
    public void test_prepare_is_noop_on_linux() {
        TrayIconProvider provider = new TrayIconProvider(OsFamily.LINUX,
                fixedDetector(OsFamily.LINUX, SystemThemeDetector.Theme.DARK));
        provider.prepare();
        assertNull(System.getProperty("apple.awt.enableTemplateImages"));
        assertEquals(SystemTray.TrayType.AutoDetect, SystemTray.FORCE_TRAY_TYPE);
    }

    @Test
    public void test_returns_null_when_asset_missing() {
        TrayIconProvider provider = new TrayIconProvider(OsFamily.LINUX,
                fixedDetector(OsFamily.LINUX, SystemThemeDetector.Theme.DARK)) {
            @Override
            protected BufferedImage loadSilhouette() {
                return null;
            }
        };
        assertNull(provider.loadTrayImage(16));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TrayIconProviderTest test`
Expected: FAIL — `TrayIconProvider` does not exist.

- [ ] **Step 3: Implement `TrayIconProvider`**

Create `datashare-app/src/main/java/org/icij/datashare/tray/TrayIconProvider.java`:

```java
package org.icij.datashare.tray;

import dorkbox.systemTray.SystemTray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

public class TrayIconProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(TrayIconProvider.class);
    private static final String TEMPLATE_ICON = "datashare-template.png";
    private static final int MAC_ICON_SIZE = 22;
    private static final int DEFAULT_ICON_SIZE = 16;

    private final OsFamily os;
    private final SystemThemeDetector detector;

    public static TrayIconProvider forCurrentPlatform() {
        OsFamily os = OsFamily.current();
        return new TrayIconProvider(os, new SystemThemeDetector(os));
    }

    TrayIconProvider(OsFamily os, SystemThemeDetector detector) {
        this.os = os;
        this.detector = detector;
    }

    /**
     * macOS only: force the AWT-backed tray and enable template images so the OS
     * recolors the black silhouette to match the menu bar. Must run before
     * {@link SystemTray#get()}.
     */
    public void prepare() {
        if (os == OsFamily.MAC) {
            SystemTray.FORCE_TRAY_TYPE = SystemTray.TrayType.Awt;
            System.setProperty("apple.awt.enableTemplateImages", "true");
        }
    }

    /** Returns the tray image, or null if the silhouette asset is unavailable. */
    public Image loadTrayImage(int preferredSize) {
        BufferedImage silhouette = loadSilhouette();
        if (silhouette == null) {
            return null;
        }
        int size = preferredSize > 0 ? preferredSize : defaultSize();
        return IconTinter.tint(silhouette, iconColor(), size);
    }

    protected BufferedImage loadSilhouette() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(TEMPLATE_ICON)) {
            if (in == null) {
                LOGGER.warn("Tray silhouette '{}' not found on classpath", TEMPLATE_ICON);
                return null;
            }
            return ImageIO.read(in);
        } catch (IOException e) {
            LOGGER.warn("Could not read tray silhouette '{}'", TEMPLATE_ICON, e);
            return null;
        }
    }

    private int defaultSize() {
        return os == OsFamily.MAC ? MAC_ICON_SIZE : DEFAULT_ICON_SIZE;
    }

    private Color iconColor() {
        if (os == OsFamily.MAC) {
            return Color.BLACK; // OS recolors via the template image
        }
        return detector.detect() == SystemThemeDetector.Theme.LIGHT ? Color.BLACK : Color.WHITE;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TrayIconProviderTest test`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tray/TrayIconProvider.java \
        datashare-app/src/test/java/org/icij/datashare/tray/TrayIconProviderTest.java
git commit -m "feat(tray): add TrayIconProvider with per-platform monochrome icon policy"
```

---

## Task 5: Wire `TrayIconProvider` into `DatashareSystemTray`

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tray/DatashareSystemTray.java`
- Modify: `datashare-app/src/test/java/org/icij/datashare/tray/DatashareSystemTrayTest.java`

- [ ] **Step 1: Update the existing test to inject a provider and expect an `Image`**

Replace the entire contents of `datashare-app/src/test/java/org/icij/datashare/tray/DatashareSystemTrayTest.java` with:

```java
package org.icij.datashare.tray;

import dorkbox.systemTray.Menu;
import dorkbox.systemTray.MenuItem;
import dorkbox.systemTray.SystemTray;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.awt.Image;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class DatashareSystemTrayTest {
    @Mock private TrayActions trayActions;
    @Mock private SystemTray systemTray;
    @Mock private Menu menu;
    @Mock private TrayIconProvider iconProvider;
    private DatashareSystemTray datashareSystemTray;

    @Before
    public void setUp() {
        initMocks(this);
        when(systemTray.getMenu()).thenReturn(menu);
        when(iconProvider.loadTrayImage(anyInt()))
                .thenReturn(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB));
        datashareSystemTray = new DatashareSystemTray(systemTray, trayActions, iconProvider);
    }

    @Test
    public void test_set_icon() {
        verify(systemTray).setImage(any(Image.class));
    }

    @Test
    public void test_falls_back_to_default_when_no_icon_image() {
        reset(systemTray);
        when(systemTray.getMenu()).thenReturn(menu);
        when(iconProvider.loadTrayImage(anyInt())).thenReturn(null);

        new DatashareSystemTray(systemTray, trayActions, iconProvider);

        verify(systemTray).setImage(any(Image.class)); // default pink image
    }

    @Test
    public void test_menu_items_are_added() {
        verify(menu, times(2)).add(any(MenuItem.class));
    }

    @Test
    public void test_open_browser_action() throws Exception {
        ArgumentCaptor<MenuItem> menuItemCaptor = ArgumentCaptor.forClass(MenuItem.class);
        verify(menu, times(2)).add(menuItemCaptor.capture());

        MenuItem openBrowserItem = menuItemCaptor.getAllValues().stream()
                .filter(item -> "Open Browser".equals(item.getText()))
                .findFirst()
                .orElseThrow(() -> new Exception("Menu item 'Open Browser' not found"));

        ActionListener actionListener = openBrowserItem.getCallback();
        actionListener.actionPerformed(null);

        verify(trayActions).openBrowser();
    }

    @Test
    public void test_quit_action() throws Exception {
        ArgumentCaptor<MenuItem> menuItemCaptor = ArgumentCaptor.forClass(MenuItem.class);
        verify(menu, times(2)).add(menuItemCaptor.capture());

        MenuItem quitItem = menuItemCaptor.getAllValues().stream()
                .filter(item -> "Quit".equals(item.getText()))
                .findFirst()
                .orElseThrow(() -> new Exception("Menu item 'Quit' not found"));

        ActionListener actionListener = quitItem.getCallback();
        actionListener.actionPerformed(null);

        verify(trayActions).quit();
    }

    @Test
    public void test_close_shuts_down_tray() throws Exception {
        datashareSystemTray.close();

        verify(systemTray).shutdown();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DatashareSystemTrayTest test`
Expected: FAIL — `DatashareSystemTray` has no 3-arg constructor / does not compile.

- [ ] **Step 3: Update `DatashareSystemTray`**

Edit `datashare-app/src/main/java/org/icij/datashare/tray/DatashareSystemTray.java`.

Replace the imports block and the top-of-class fields/constants. The new file should read:

```java

package org.icij.datashare.tray;

import dorkbox.systemTray.Menu;
import dorkbox.systemTray.MenuItem;
import dorkbox.systemTray.SystemTray;
import org.icij.datashare.utils.WebBrowserUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;

import static java.lang.String.format;

public class DatashareSystemTray implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatashareSystemTray.class);
    private static final int DEFAULT_ICON_SIZE = 16;

    private final SystemTray systemTray;
    private final TrayActions actions;
    private final TrayIconProvider iconProvider;

    DatashareSystemTray(SystemTray systemTray, TrayActions actions, TrayIconProvider iconProvider) {
        this.systemTray = systemTray;
        this.actions = actions;
        this.iconProvider = iconProvider;
        configure();
    }

    public static DatashareSystemTray create(String port) {
        TrayIconProvider iconProvider = TrayIconProvider.forCurrentPlatform();
        iconProvider.prepare(); // macOS: force AWT tray + template images BEFORE SystemTray.get()
        SystemTray systemTray = createSystemTray();
        if (systemTray == null) {
            return null;
        }
        return new DatashareSystemTray(systemTray, new TrayActions() {
            @Override
            public void openBrowser() {
                WebBrowserUtils.openBrowser(format("http://localhost:%s", port));
            }

            @Override
            public void quit() {
                LOGGER.info("Shutdown requested from system tray");
                System.exit(0);
            }
        }, iconProvider);
    }

    @Nullable
    private static SystemTray createSystemTray() {
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment();
            return SystemTray.get();
        } catch (Throwable e) {
            LOGGER.warn("SystemTray is not supported on this system", e);
            return null;
        }
    }

    private void configure() {
        systemTray.setTooltip("Datashare");
        loadIcon();
        configureMenu();
    }

    private void configureMenu() {
        Menu menu = systemTray.getMenu();
        menu.add(new MenuItem("Open Browser", e -> actions.openBrowser()));
        menu.add(new MenuItem("Quit", e -> actions.quit()));
    }

    private void loadIcon() {
        Image image = iconProvider.loadTrayImage(systemTray.getMenuImageSize());
        if (image != null) {
            systemTray.setImage(image);
            return;
        }
        LOGGER.warn("Tray icon could not be prepared, using default");
        setDefaultIcon();
    }

    private void setDefaultIcon() {
        BufferedImage defaultImage = new BufferedImage(DEFAULT_ICON_SIZE, DEFAULT_ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = defaultImage.createGraphics();
        g2d.setColor(Color.PINK);
        g2d.fillRect(0, 0, DEFAULT_ICON_SIZE, DEFAULT_ICON_SIZE);
        g2d.dispose();
        systemTray.setImage(defaultImage);
    }

    @Override
    public void close() throws IOException {
        systemTray.shutdown();
    }
}
```

Note: the old `DATASHARE_ICON_FILENAME` constant, the `loadIcon()` URL logic, the `java.net.URL` import, and the `java.util.Optional` import are all removed.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DatashareSystemTrayTest test`
Expected: PASS (6 tests).

- [ ] **Step 5: Run the whole tray test package**

Run: `mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=org.icij.datashare.tray.* test`
Expected: PASS (all tray tests green).

- [ ] **Step 6: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tray/DatashareSystemTray.java \
        datashare-app/src/test/java/org/icij/datashare/tray/DatashareSystemTrayTest.java
git commit -m "refactor(tray): delegate icon loading to TrayIconProvider"
```

---

## Task 6: Remove the now-unused color icon

**Files:**
- Delete: `datashare-app/src/main/resources/datashare.png`

- [ ] **Step 1: Confirm `datashare.png` is unreferenced**

Run:
```bash
grep -rin "datashare.png" --include="*.java" datashare-app/src | grep -v target
```
Expected: no output (the only references were removed in Task 5).

- [ ] **Step 2: Delete the asset**

```bash
git rm datashare-app/src/main/resources/datashare.png
```

- [ ] **Step 3: Rebuild the module to confirm nothing breaks**

Run: `mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=org.icij.datashare.tray.* test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git commit -m "chore(tray): drop unused color tray icon"
```

---

## Task 7: macOS runtime verification + manual-test notes

This task cannot be covered by headless unit tests; it verifies the live macOS template behavior and records the fallback. Do the verification if a Mac is available; otherwise complete only the documentation step and flag the runtime verification as pending.

**Files:**
- Modify: `datashare-app/src/test/java/org/icij/datashare/SystemTrayTestManual.java`

- [ ] **Step 1: Update the manual-test guidance**

Edit `datashare-app/src/test/java/org/icij/datashare/SystemTrayTestManual.java`. Replace the class-level comment block (lines 7-9, the three `//` comment lines above `@Ignore`) with:

```java
// this test is not executed by CI because it doesn't end with "Test"
// its goal is to test manually the SystemTray
// it has not been automated because UI elements cannot be managed in the CI
//
// Monochrome tray icon manual checks:
//  - macOS: the icon must appear as a single-color template that the OS recolors
//    to match the menu bar; toggle System Settings > Appearance (Light/Dark) and
//    confirm it recolors live without restarting.
//  - Gnome/Ubuntu: the icon is white on the (typically dark) top bar; switching
//    the theme requires a restart to update (startup detection only).
//  - Windows: the icon matches the taskbar theme at startup; restart to update.
```

- [ ] **Step 2: macOS runtime verification (requires a Mac)**

Run the app on macOS (e.g. via `SystemTrayTestManual.test_main_display_system_tray_for_web_server`, un-ignored locally, or a packaged build) and confirm:
1. The tray icon is a monochrome template (not the old gradient, not a black box).
2. Toggling macOS Light/Dark recolors the icon live.
3. The tray menu ("Open Browser", "Quit") opens and both actions work under the forced `Awt` tray.

**If the `Awt` tray misbehaves on macOS** (menu won't open, icon won't show): apply the fallback — in `TrayIconProvider.prepare()` remove the macOS `FORCE_TRAY_TYPE`/property lines, and in `iconColor()` replace the macOS branch with theme detection:
```java
if (os == OsFamily.MAC) {
    return detector.detect() == SystemThemeDetector.Theme.LIGHT ? Color.BLACK : Color.WHITE;
}
```
This keeps macOS on the native `Osx` tray with startup-only detection (like Linux/Windows). The `SystemThemeDetector` macOS branch already supports this.

- [ ] **Step 3: Commit the manual-test notes**

```bash
git add datashare-app/src/test/java/org/icij/datashare/SystemTrayTestManual.java
git commit -m "docs(tray): document monochrome tray icon manual checks per OS"
```

---

## Final verification

- [ ] **Run the full tray package + a module build**

Run: `mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=org.icij.datashare.tray.* test`
Expected: all tray tests PASS.

- [ ] **Confirm no stray references to the old icon/constant remain**

Run: `grep -rin "DATASHARE_ICON_FILENAME\|datashare.png" datashare-app/src | grep -v target`
Expected: no output.
