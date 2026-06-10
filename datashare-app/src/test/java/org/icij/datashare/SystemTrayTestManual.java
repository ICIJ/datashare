
package org.icij.datashare;

import org.junit.Ignore;
import org.junit.Test;

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