
package org.icij.datashare;

import org.junit.Ignore;
import org.junit.Test;

// this test is not executed by CI because it doesn't end with "Test"
// its goal is to test manually the SystemTray
// it has not been automated because UI elements cannot be managed in the CI
//
// Tray icon manual checks (icons are pre-rendered variants: black / white / colour):
//  - macOS: the icon appears as the black template image that the OS recolors to match
//    the menu bar; toggle System Settings > Appearance (Light/Dark) and confirm it
//    recolors live without restarting.
//  - Gnome/Ubuntu & Windows: the icon is black on a light panel and white on a dark one.
//    Toggle the system theme while running and confirm it switches automatically within
//    ~30s (a background watcher re-detects; no restart needed).
//  - When the theme cannot be detected (e.g. a non-GNOME desktop with no gsettings
//    schema), the full-colour logo is shown instead, which stays legible on any panel.
@Ignore
public class SystemTrayTestManual {

    @Test
    public void test_main_display_system_tray_for_web_server() throws Exception {
        Thread main = new Thread(() -> {
            try {
                String[] args = {"--mode", "LOCAL"};
                Main.main(args);
            } catch (Exception ignored) {

            }
        });
        main.start();

        Thread.sleep(Long.MAX_VALUE);
    }

    @Test
    public void test_main_does_not_display_system_tray_for_task_worker() throws Exception {
        Thread main = new Thread(() -> {
            try {
                String[] args = {"--mode", "TASK_WORKER"};
                Main.main(args);
            } catch (Exception ignored) {

            }
        });
        main.start();

        Thread.sleep(Long.MAX_VALUE);
    }

    @Test
    public void test_main_does_not_display_system_if_headless() throws Exception {
        System.setProperty("java.awt.headless", "true");
        Thread main = new Thread(() -> {
            try {
                String[] args = {"--mode", "LOCAL"};
                Main.main(args);
            } catch (Exception ignored) {

            }
        });
        main.start();

        Thread.sleep(Long.MAX_VALUE);
    }
}