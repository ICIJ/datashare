package org.icij.datashare.session;

import org.icij.datashare.PropertiesProvider;
import org.junit.Test;

import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

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
}
