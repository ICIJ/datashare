package org.icij.datashare;

import org.icij.datashare.cli.Mode;
import org.icij.datashare.mode.CommonMode;
import org.junit.Test;

import java.util.HashMap;
import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_MAX_HTTP_BODY_SIZE;
import static org.icij.datashare.cli.DatashareCliOptions.MAX_HTTP_BODY_SIZE_OPT;
import static org.junit.Assert.assertThrows;

public class WebAppBindTest {
    @Test
    public void test_only_server_mode_backfills_wide_admin_inventories_at_startup() {
        for (Mode mode : Mode.values()) {
            assertThat(WebApp.backfillsAtStartup(mode)).as(mode.name()).isEqualTo(mode == Mode.SERVER);
        }
    }

    @Test
    public void test_default_bind_host_local_mode() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", Mode.LOCAL.name());
        }});
        assertThat(WebApp.resolveBindHost(mode)).isEqualTo("localhost");
    }

    @Test
    public void test_default_bind_host_server_mode() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", Mode.SERVER.name());
        }});
        assertThat(WebApp.resolveBindHost(mode)).isEqualTo("0.0.0.0");
    }

    @Test
    public void test_explicit_bind_overrides_local_default() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", Mode.LOCAL.name());
            put("bind", "0.0.0.0");
        }});
        assertThat(WebApp.resolveBindHost(mode)).isEqualTo("0.0.0.0");
    }

    @Test
    public void test_explicit_bind_overrides_server_default() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", Mode.SERVER.name());
            put("bind", "127.0.0.1");
        }});
        assertThat(WebApp.resolveBindHost(mode)).isEqualTo("127.0.0.1");
    }

    @Test
    public void test_max_http_body_size_defaults_when_absent() {
        Properties properties = new Properties();
        assertThat(WebApp.maxHttpBodySize(properties)).isEqualTo(DEFAULT_MAX_HTTP_BODY_SIZE);
    }

    @Test
    public void test_max_http_body_size_reads_the_property() {
        Properties properties = new Properties();
        properties.setProperty(MAX_HTTP_BODY_SIZE_OPT, " 0 ");
        assertThat(WebApp.maxHttpBodySize(properties)).isEqualTo(0L);
    }

    @Test
    public void test_max_http_body_size_names_the_property_when_invalid() {
        Properties properties = new Properties();
        properties.setProperty(MAX_HTTP_BODY_SIZE_OPT, "100MB");
        InvalidMaxHttpBodySizeException error =
                assertThrows(InvalidMaxHttpBodySizeException.class, () -> WebApp.maxHttpBodySize(properties));
        assertThat(error.getMessage()).isEqualTo("invalid maxHttpBodySize \"100MB\": expected a number of bytes");
    }
}
