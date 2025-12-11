
package org.icij.datashare;

import org.junit.Before;
import org.junit.Test;
import dorkbox.systemTray.SystemTray;

// this test is not executed by CI because it doesn't end with "Test"
// its goal is to test manually the SystemTray
// it has not been automated because it tests UI elements have to be manaaged in the CI
public class SystemTrayTestManual {

    @Before
    public void setUp() {
        if (SystemTray.get() != null) {
            SystemTray.get().shutdown();
        }
    }

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
}