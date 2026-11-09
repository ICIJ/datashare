package org.icij.datashare;

import net.codestory.http.misc.Env;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class BindableWebServerTest {
    // Expose the protected createEnv() so the test can assert the server forces production mode,
    // which turns off the live-reload server, the source routes and stack-trace error pages.
    private static class ExposedServer extends BindableWebServer {
        ExposedServer() {
            super("localhost");
        }

        Env env() {
            return createEnv();
        }
    }

    @Test
    public void test_server_forces_production_mode() {
        assertThat(new ExposedServer().env().prodMode()).isTrue();
    }

    @Test
    public void test_server_disables_live_reload() {
        assertThat(new ExposedServer().env().liveReloadServer()).isFalse();
    }
}
