package org.icij.datashare;

import org.icij.datashare.user.admin.UserAdminService;
import org.icij.datashare.user.admin.UserCreated;
import org.icij.datashare.user.admin.UserCreateRequest;
import org.icij.datashare.user.admin.UserExistsException;
import org.icij.datashare.user.admin.UserNotFoundException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    private static Properties createProps(String login, String email, String password,
                                          String provider, String groupsCsv,
                                          boolean ifNotExists, boolean json) {
        Properties p = new Properties();
        p.setProperty("userCreate", login);
        p.setProperty("userCreate.email", email);
        p.setProperty("userCreate.password", password);
        p.setProperty("userCreate.provider", provider);
        p.setProperty("userCreate.groups", groupsCsv);
        if (ifNotExists) p.setProperty("userCreate.ifNotExists", "true");
        if (json) p.setProperty("userCreate.json", "true");
        return p;
    }

    private static Properties deleteProps(String login, boolean ifExists, boolean json) {
        Properties p = new Properties();
        p.setProperty("userDelete", login);
        if (ifExists) p.setProperty("userDelete.ifExists", "true");
        if (json) p.setProperty("userDelete.json", "true");
        return p;
    }

    @Test
    public void test_user_create_text_success() throws Exception {
        UserAdminService svc = mock(UserAdminService.class);
        when(svc.create(any(UserCreateRequest.class))).thenReturn(
                new UserCreated("alice", "alice@example.org", "alice", "local",
                        List.of("p1"), false));

        Properties p = createProps("alice", "alice@example.org", "pw", "local", "p1", false, false);

        int exit = CliApp.handleUserCreate(svc, p);

        restore();
        assertThat(exit).isEqualTo(0);
        assertThat(out.toString()).contains("created user 'alice'");
        // Password is wiped from properties as soon as the request is built.
        assertThat(p.getProperty("userCreate.password")).isNull();
    }

    @Test
    public void test_user_create_parses_groups_csv_into_list() throws Exception {
        UserAdminService svc = mock(UserAdminService.class);
        when(svc.create(any(UserCreateRequest.class))).thenReturn(
                new UserCreated("alice", "a@b.c", "alice", "local", List.of("p1", "p2"), false));

        Properties p = createProps("alice", "a@b.c", "pw", "local", "p1,p2", false, false);

        CliApp.handleUserCreate(svc, p);

        restore();
        ArgumentCaptor<UserCreateRequest> captor = ArgumentCaptor.forClass(UserCreateRequest.class);
        verify(svc).create(captor.capture());
        assertThat(captor.getValue().groups()).isEqualTo(List.of("p1", "p2"));
    }

    @Test
    public void test_user_create_empty_groups_csv_yields_empty_list() throws Exception {
        UserAdminService svc = mock(UserAdminService.class);
        when(svc.create(any(UserCreateRequest.class))).thenReturn(
                new UserCreated("alice", "a@b.c", "alice", "local", List.of(), false));

        Properties p = createProps("alice", "a@b.c", "pw", "local", "", false, false);

        CliApp.handleUserCreate(svc, p);

        restore();
        ArgumentCaptor<UserCreateRequest> captor = ArgumentCaptor.forClass(UserCreateRequest.class);
        verify(svc).create(captor.capture());
        assertThat(captor.getValue().groups()).isEqualTo(List.of());
    }

    @Test
    public void test_user_create_conflict_returns_4() throws Exception {
        UserAdminService svc = mock(UserAdminService.class);
        when(svc.create(any(UserCreateRequest.class)))
                .thenThrow(new UserExistsException("alice"));

        Properties p = createProps("alice", "alice@example.org", "pw", "local", "", false, false);

        int exit = CliApp.handleUserCreate(svc, p);

        restore();
        assertThat(exit).isEqualTo(4);
        assertThat(err.toString()).contains("alice");
    }

    @Test
    public void test_user_create_json_output() throws Exception {
        UserAdminService svc = mock(UserAdminService.class);
        when(svc.create(any(UserCreateRequest.class))).thenReturn(
                new UserCreated("alice", "alice@example.org", "alice", "local", List.of(), false));

        Properties p = createProps("alice", "alice@example.org", "pw", "local", "", false, true);

        int exit = CliApp.handleUserCreate(svc, p);

        restore();
        assertThat(exit).isEqualTo(0);
        assertThat(out.toString()).contains("\"created\":true");
        assertThat(out.toString()).contains("\"login\":\"alice\"");
    }

    @Test
    public void test_user_delete_text_success() throws Exception {
        UserAdminService svc = mock(UserAdminService.class);
        when(svc.delete("alice")).thenReturn(true);

        Properties p = deleteProps("alice", false, false);

        int exit = CliApp.handleUserDelete(svc, p);

        restore();
        assertThat(exit).isEqualTo(0);
        assertThat(out.toString()).contains("deleted user 'alice'");
    }

    @Test
    public void test_user_delete_not_found_returns_3() throws Exception {
        UserAdminService svc = mock(UserAdminService.class);
        when(svc.delete("ghost")).thenThrow(new UserNotFoundException("ghost"));

        Properties p = deleteProps("ghost", false, false);

        int exit = CliApp.handleUserDelete(svc, p);

        restore();
        assertThat(exit).isEqualTo(3);
        assertThat(err.toString()).contains("ghost");
    }

    @Test
    public void test_user_delete_if_exists_missing_user_is_noop() {
        UserAdminService svc = mock(UserAdminService.class);
        when(svc.deleteIfExists("ghost")).thenReturn(false);

        Properties p = deleteProps("ghost", true, false);

        int exit = CliApp.handleUserDelete(svc, p);

        restore();
        assertThat(exit).isEqualTo(0);
        assertThat(out.toString()).contains("does not exist (no-op)");
    }
}
