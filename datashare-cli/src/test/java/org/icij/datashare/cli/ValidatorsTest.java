package org.icij.datashare.cli;

import org.junit.Test;

import java.util.List;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.fail;

public class ValidatorsTest {

    @Test
    public void test_login_accepts_valid_logins() {
        Validators.login("alice");
        Validators.login("alice.bob");
        Validators.login("alice_bob");
        Validators.login("alice-bob");
        Validators.login("a1");
        Validators.login("0abc");
    }

    @Test
    public void test_login_rejects_uppercase() {
        try {
            Validators.login("Alice");
            fail("expected exception");
        } catch (Validators.InvalidValueException e) {
            assertThat(e.field()).isEqualTo("login");
        }
    }

    @Test
    public void test_login_rejects_starting_with_punct() {
        try { Validators.login("-alice"); fail(); }
        catch (Validators.InvalidValueException e) { assertThat(e.field()).isEqualTo("login"); }
        try { Validators.login(".alice"); fail(); }
        catch (Validators.InvalidValueException e) { assertThat(e.field()).isEqualTo("login"); }
    }

    @Test
    public void test_login_rejects_too_long() {
        String tooLong = "a".repeat(65);
        try { Validators.login(tooLong); fail(); }
        catch (Validators.InvalidValueException e) { assertThat(e.field()).isEqualTo("login"); }
    }

    @Test
    public void test_login_rejects_too_short() {
        try { Validators.login("a"); fail(); }
        catch (Validators.InvalidValueException e) { assertThat(e.field()).isEqualTo("login"); }
        try { Validators.login(""); fail(); }
        catch (Validators.InvalidValueException e) { assertThat(e.field()).isEqualTo("login"); }
        try { Validators.login(null); fail(); }
        catch (Validators.InvalidValueException e) { assertThat(e.field()).isEqualTo("login"); }
    }

    @Test
    public void test_email_accepts_rfc5322() {
        Validators.email("alice@example.org");
        Validators.email("alice+filter@sub.example.org");
        Validators.email("a.b-c@example.co.uk");
    }

    @Test
    public void test_email_rejects_garbage() {
        try { Validators.email("not-an-email"); fail(); }
        catch (Validators.InvalidValueException e) { assertThat(e.field()).isEqualTo("email"); }
        try { Validators.email("@example.org"); fail(); }
        catch (Validators.InvalidValueException e) { assertThat(e.field()).isEqualTo("email"); }
        try { Validators.email(""); fail(); }
        catch (Validators.InvalidValueException e) { assertThat(e.field()).isEqualTo("email"); }
    }

    @Test
    public void test_provider_accepts_canonical_three() {
        Validators.provider("local");
        Validators.provider("oauth");
        Validators.provider("external");
    }

    @Test
    public void test_provider_rejects_unknown() {
        try { Validators.provider("ldap"); fail(); }
        catch (Validators.InvalidValueException e) { assertThat(e.field()).isEqualTo("provider"); }
        try { Validators.provider("LOCAL"); fail(); }  // case-sensitive on the wire
        catch (Validators.InvalidValueException e) { assertThat(e.field()).isEqualTo("provider"); }
    }

    @Test
    public void test_groups_parses_comma_separated() {
        assertThat(Validators.groups("p1,p2,p3")).isEqualTo(List.of("p1", "p2", "p3"));
        assertThat(Validators.groups("p1, p2 ,p3")).isEqualTo(List.of("p1", "p2", "p3"));
        assertThat(Validators.groups(null)).isEqualTo(List.of());
        assertThat(Validators.groups("")).isEqualTo(List.of());
    }

    @Test
    public void test_groups_rejects_single_char_project_name() {
        try { Validators.groups("a"); fail(); }
        catch (Validators.InvalidValueException e) { assertThat(e.field()).isEqualTo("groups"); }
    }

    @Test
    public void test_groups_rejects_invalid_project_name() {
        try { Validators.groups("p1,B,p3"); fail(); }
        catch (Validators.InvalidValueException e) { assertThat(e.field()).isEqualTo("groups"); }
        try { Validators.groups("p1,-b,p3"); fail(); }
        catch (Validators.InvalidValueException e) { assertThat(e.field()).isEqualTo("groups"); }
    }

    @Test
    public void test_project_name_accepts_valid_name() {
        Validators.projectName("foo");
        Validators.projectName("project-1");
        Validators.projectName("0abc");
    }

    @Test
    public void test_project_name_rejects_invalid() {
        assertProjectNameInvalid(null);
        assertProjectNameInvalid("");
        assertProjectNameInvalid("-leading-dash");
        assertProjectNameInvalid("Has-Uppercase");
        assertProjectNameInvalid("has_underscore");
        assertProjectNameInvalid("a");                                // too short (1 char)
        assertProjectNameInvalid("a".repeat(65));                     // too long
    }

    private void assertProjectNameInvalid(String value) {
        try {
            Validators.projectName(value);
            fail("expected InvalidValueException for " + value);
        } catch (Validators.InvalidValueException e) {
            assertThat(e.field()).isEqualTo("projectName");
        }
    }

    @Test
    public void test_allow_from_mask_accepts_valid() {
        Validators.allowFromMask("*.*.*.*");
        Validators.allowFromMask("10.0.0.0");
        Validators.allowFromMask("192.168.*.*");
    }

    @Test
    public void test_allow_from_mask_rejects_invalid() {
        assertAllowFromMaskInvalid(null);
        assertAllowFromMaskInvalid("");
        assertAllowFromMaskInvalid("192.168");                        // too few octets
        assertAllowFromMaskInvalid("192.168.1.1.1");                  // too many
        assertAllowFromMaskInvalid("a.b.c.d");                        // non-digit non-star
    }

    private void assertAllowFromMaskInvalid(String value) {
        try {
            Validators.allowFromMask(value);
            fail("expected InvalidValueException for " + value);
        } catch (Validators.InvalidValueException e) {
            assertThat(e.field()).isEqualTo("allowFromMask");
        }
    }

    @Test
    public void test_uri_accepts_https_and_http() {
        Validators.uri("https://example.org");
        Validators.uri("http://example.org/path?q=1");
    }

    @Test
    public void test_uri_rejects_invalid() {
        assertUriInvalid(null);
        assertUriInvalid("");
        assertUriInvalid("not a uri");
        assertUriInvalid("/just/a/path");                              // no scheme
    }

    private void assertUriInvalid(String value) {
        try {
            Validators.uri(value);
            fail("expected InvalidValueException for " + value);
        } catch (Validators.InvalidValueException e) {
            assertThat(e.field()).isEqualTo("uri");
        }
    }

    @Test
    public void test_iso8601_accepts_valid_instants() {
        Validators.iso8601("2026-05-15T10:00:00Z");
        Validators.iso8601("2026-05-15T10:00:00.123Z");
        Validators.iso8601("1970-01-01T00:00:00Z");
    }

    @Test
    public void test_iso8601_rejects_invalid() {
        assertIso8601Invalid(null);
        assertIso8601Invalid("");
        assertIso8601Invalid("not-a-date");
        assertIso8601Invalid("2026-05-15");                          // date only, no time
        assertIso8601Invalid("2026-05-15T10:00:00");                 // missing zone
    }

    private void assertIso8601Invalid(String value) {
        try {
            Validators.iso8601(value);
            fail("expected InvalidValueException for " + value);
        } catch (Validators.InvalidValueException e) {
            assertThat(e.field()).isEqualTo("iso8601");
        }
    }
}
