package org.icij.datashare.mode;

import com.google.inject.Injector;
import net.codestory.http.filters.Filter;
import org.icij.datashare.session.BasicAuthAdaptorFilter;
import org.icij.datashare.session.OAuth2CookieFilter;
import org.junit.Test;

import java.util.HashMap;

import static com.google.inject.Guice.createInjector;
import static org.fest.assertions.Assertions.assertThat;

public class ServerModeTest {
    @Test
    public void test_server_mode_default_auth_class() {
        Injector injector = createInjector(new ServerMode(new HashMap<>()));
        assertThat(injector.getInstance(Filter.class)).isInstanceOf(OAuth2CookieFilter.class);
    }

    @Test
    public void test_server_mode_auth_class() {
        Injector injector = createInjector(new ServerMode(new HashMap<String, String>() {{
            put("authFilter", "org.icij.datashare.session.BasicAuthAdaptorFilter");
        }}));
        assertThat(injector.getInstance(Filter.class)).isInstanceOf(BasicAuthAdaptorFilter.class);
    }

    @Test
    public void test_server_mode_bad_auth_class_uses_default() {
        Injector injector = createInjector(new ServerMode(new HashMap<String, String>() {{
            put("authFilter", "org.icij.UnknownClass");
        }}));
        assertThat(injector.getInstance(Filter.class)).isInstanceOf(OAuth2CookieFilter.class);
    }
}
