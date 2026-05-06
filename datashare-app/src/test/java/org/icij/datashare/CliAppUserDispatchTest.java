package org.icij.datashare;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.user.admin.UserAdminService;
import org.icij.datashare.user.admin.UserCreated;
import org.icij.datashare.user.admin.UserCreateRequest;
import org.icij.datashare.user.admin.UserExistsException;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CliAppUserDispatchTest {
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private PrintStream origOut;
    private PrintStream origErr;

    @Before
    public void capture() {
        origOut = System.out;
        origErr = System.err;
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        System.setErr(new PrintStream(err));
    }

    private void restore() {
        System.setOut(origOut);
        System.setErr(origErr);
    }

    @Test
    public void test_user_create_text_success() throws Exception {
        UserAdminService svc = mock(UserAdminService.class);
        when(svc.create(any(UserCreateRequest.class))).thenReturn(
                new UserCreated("alice", "alice@example.org", "alice", "local",
                        java.util.List.of("p1"), false));

        Properties p = new Properties();
        p.setProperty("userCreate", new ObjectMapper().writeValueAsString(Map.of(
                "login", "alice",
                "email", "alice@example.org",
                "name", "alice",
                "password", "pw",
                "provider", "local",
                "groups", java.util.List.of("p1"),
                "ifNotExists", false,
                "json", false)));

        int exit = CliApp.handleUserCreate(svc, p);

        restore();
        assertThat(exit).isEqualTo(0);
        assertThat(out.toString()).contains("created user 'alice'");
        // password must have been removed from props
        assertThat(p.getProperty("userCreate")).isNull();
    }

    @Test
    public void test_user_create_conflict_returns_4() throws Exception {
        UserAdminService svc = mock(UserAdminService.class);
        when(svc.create(any(UserCreateRequest.class)))
                .thenThrow(new UserExistsException("alice"));

        Properties p = new Properties();
        p.setProperty("userCreate", new ObjectMapper().writeValueAsString(Map.of(
                "login", "alice",
                "email", "alice@example.org",
                "name", "alice",
                "password", "pw",
                "provider", "local",
                "groups", java.util.List.of(),
                "ifNotExists", false,
                "json", false)));

        int exit = CliApp.handleUserCreate(svc, p);

        restore();
        assertThat(exit).isEqualTo(4);
        assertThat(err.toString()).contains("alice");
    }

    @Test
    public void test_user_create_json_output() throws Exception {
        UserAdminService svc = mock(UserAdminService.class);
        when(svc.create(any(UserCreateRequest.class))).thenReturn(
                new UserCreated("alice", "alice@example.org", "alice", "local",
                        java.util.List.of(), false));

        Properties p = new Properties();
        p.setProperty("userCreate", new ObjectMapper().writeValueAsString(Map.of(
                "login", "alice",
                "email", "alice@example.org",
                "name", "alice",
                "password", "pw",
                "provider", "local",
                "groups", java.util.List.of(),
                "ifNotExists", false,
                "json", true)));

        int exit = CliApp.handleUserCreate(svc, p);

        restore();
        assertThat(exit).isEqualTo(0);
        assertThat(out.toString()).contains("\"created\":true");
        assertThat(out.toString()).contains("\"login\":\"alice\"");
    }
}
