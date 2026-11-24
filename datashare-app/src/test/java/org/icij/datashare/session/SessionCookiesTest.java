package org.icij.datashare.session;

import net.codestory.http.Context;
import org.icij.datashare.PropertiesProvider;
import org.junit.Test;

import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SessionCookiesTest {
    @Test
    public void test_secure_option_accepts_true_and_false_in_any_case() {
        PropertiesProvider upperCaseTrue = new PropertiesProvider(Map.of("secureSessionCookie", "TRUE"));
        PropertiesProvider lowerCaseFalse = new PropertiesProvider(Map.of("secureSessionCookie", "false"));

        assertThat(SessionCookies.isSecure(upperCaseTrue)).isTrue();
        assertThat(SessionCookies.isSecure(lowerCaseFalse)).isFalse();
    }

    @Test(expected = InvalidSecureSessionCookieException.class)
    public void test_secure_option_rejects_a_value_that_is_not_a_boolean() {
        PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of("secureSessionCookie", "yes"));

        SessionCookies.isSecure(propertiesProvider);
    }

    @Test
    public void test_https_uses_the_first_entry_of_a_proxy_chain() {
        Context context = mock(Context.class);
        when(context.header("x-forwarded-proto")).thenReturn("https, http");

        assertThat(SessionCookies.isHttps(context)).isTrue();
    }

    @Test
    public void test_https_falls_back_to_the_connection_when_the_proxy_header_is_blank() {
        Context context = mock(Context.class, RETURNS_DEEP_STUBS);
        when(context.header("x-forwarded-proto")).thenReturn("");
        when(context.request().isSecure()).thenReturn(true);

        assertThat(SessionCookies.isHttps(context)).isTrue();
    }
}
