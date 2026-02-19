package org.icij.datashare;

import org.icij.datashare.cli.Mode;
import org.icij.datashare.mode.CommonMode;
import org.junit.Test;

import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;

public class WebAppBindTest {
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
}
