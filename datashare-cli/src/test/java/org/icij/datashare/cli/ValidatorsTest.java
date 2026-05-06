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
        assertThat(Validators.groups("a,b,c")).isEqualTo(List.of("a", "b", "c"));
        assertThat(Validators.groups("a, b ,c")).isEqualTo(List.of("a", "b", "c"));
        assertThat(Validators.groups(null)).isEqualTo(List.of());
        assertThat(Validators.groups("")).isEqualTo(List.of());
    }

    @Test
    public void test_groups_rejects_invalid_project_name() {
        try { Validators.groups("a,B,c"); fail(); }
        catch (Validators.InvalidValueException e) { assertThat(e.field()).isEqualTo("groups"); }
        try { Validators.groups("a,-b,c"); fail(); }
        catch (Validators.InvalidValueException e) { assertThat(e.field()).isEqualTo("groups"); }
    }
}
