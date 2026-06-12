package org.icij.datashare.user.admin;

import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.session.UserStore;
import org.icij.datashare.text.Hasher;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

public class UserAdminServiceImplTest {
    private UserStore userStore;
    private UserAdminServiceImpl service;

    @Before
    public void setUp() {
        userStore = mock(UserStore.class);
        service = new UserAdminServiceImpl(userStore);
    }

    @Test
    public void test_create_local_user_persists_hashed_password_and_groups() throws Exception {
        when(userStore.find("alice")).thenReturn(null);
        when(userStore.save(any(User.class))).thenReturn(true);

        UserCreated created = service.create(new UserCreateRequest(
                "alice", "alice@example.org", "Alice",
                "supersecret", "local", List.of("p1", "p2")));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userStore).save(captor.capture());
        User saved = captor.getValue();

        assertThat(saved.id).isEqualTo("alice");
        assertThat(saved.email).isEqualTo("alice@example.org");
        assertThat(saved.name).isEqualTo("Alice");
        assertThat(saved.provider).isEqualTo("local");
        assertThat(saved.details.get("password"))
                .isEqualTo(Hasher.SHA_256.hash("supersecret"));
        assertThat(((Map<String,Object>) saved.details.get("groups_by_applications"))
                .get("datashare"))
                .isEqualTo(List.of("p1", "p2"));

        assertThat(created.login()).isEqualTo("alice");
        assertThat(created.noop()).isFalse();
    }

    @Test
    public void test_create_oauth_user_does_not_store_password() throws Exception {
        when(userStore.find("bob")).thenReturn(null);
        when(userStore.save(any(User.class))).thenReturn(true);

        service.create(new UserCreateRequest(
                "bob", "bob@example.org", null,
                null, "oauth", List.of()));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userStore).save(captor.capture());
        assertThat(captor.getValue().details.containsKey("password")).isFalse();
    }

    @Test
    public void test_create_defaults_name_to_login_when_null() throws Exception {
        when(userStore.find("carol")).thenReturn(null);
        when(userStore.save(any(User.class))).thenReturn(true);

        service.create(new UserCreateRequest(
                "carol", "carol@example.org", null,
                "pw", "local", List.of()));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userStore).save(captor.capture());
        assertThat(captor.getValue().name).isEqualTo("carol");
    }

    @Test
    public void test_create_throws_when_user_exists() {
        when(userStore.find("alice"))
                .thenReturn(new DatashareUser(new User("alice", "Alice", "a@b.c")));

        try {
            service.create(new UserCreateRequest(
                    "alice", "a@b.c", "Alice", "pw", "local", List.of()));
            fail("expected UserExistsException");
        } catch (UserExistsException e) {
            assertThat(e.getMessage()).contains("alice");
        } catch (ValidationException e) {
            fail("unexpected ValidationException: " + e.getMessage());
        }
        verify(userStore, never()).save(any(User.class));
    }

    @Test
    public void test_create_local_without_password_throws_validation() {
        when(userStore.find("alice")).thenReturn(null);

        try {
            service.create(new UserCreateRequest(
                    "alice", "a@b.c", "Alice", null, "local", List.of()));
            fail("expected ValidationException");
        } catch (ValidationException e) {
            assertThat(e.field()).isEqualTo("password");
        } catch (UserExistsException e) {
            fail("unexpected UserExistsException");
        }
    }

    @Test
    public void test_create_with_unknown_provider_throws_validation() {
        when(userStore.find("alice")).thenReturn(null);

        try {
            service.create(new UserCreateRequest(
                    "alice", "a@b.c", "Alice", null, "ldap", List.of()));
            fail("expected ValidationException");
        } catch (ValidationException e) {
            assertThat(e.field()).isEqualTo("provider");
        } catch (UserExistsException e) {
            fail("unexpected UserExistsException");
        }
        verify(userStore, never()).save(any(User.class));
    }

    @Test
    public void test_create_with_null_login_throws_validation() {
        try {
            service.create(new UserCreateRequest(
                    null, "a@b.c", "Alice", "pw", "local", List.of()));
            fail("expected ValidationException");
        } catch (ValidationException e) {
            assertThat(e.field()).isEqualTo("login");
        } catch (UserExistsException e) {
            fail("unexpected UserExistsException");
        }
        verify(userStore, never()).save(any(User.class));
    }

    @Test
    public void test_create_with_null_email_throws_validation() {
        try {
            service.create(new UserCreateRequest(
                    "alice", null, "Alice", "pw", "local", List.of()));
            fail("expected ValidationException");
        } catch (ValidationException e) {
            assertThat(e.field()).isEqualTo("email");
        } catch (UserExistsException e) {
            fail("unexpected UserExistsException");
        }
        verify(userStore, never()).save(any(User.class));
    }

    @Test
    public void test_create_with_null_provider_throws_validation() {
        try {
            service.create(new UserCreateRequest(
                    "alice", "a@b.c", "Alice", "pw", null, List.of()));
            fail("expected ValidationException");
        } catch (ValidationException e) {
            assertThat(e.field()).isEqualTo("provider");
        } catch (UserExistsException e) {
            fail("unexpected UserExistsException");
        }
        verify(userStore, never()).save(any(User.class));
    }

    @Test
    public void test_create_if_not_exists_returns_noop_when_user_exists() throws Exception {
        when(userStore.find("alice"))
                .thenReturn(new DatashareUser(new User("alice", "Alice", "a@b.c")));

        UserCreated created = service.createIfNotExists(new UserCreateRequest(
                "alice", "a@b.c", "Alice", "pw", "local", List.of()));

        assertThat(created.noop()).isTrue();
        assertThat(created.login()).isEqualTo("alice");
        verify(userStore, never()).save(any(User.class));
    }

    @Test
    public void test_create_if_not_exists_saves_when_user_absent() throws Exception {
        when(userStore.find("dave")).thenReturn(null);
        when(userStore.save(any(User.class))).thenReturn(true);

        UserCreated created = service.createIfNotExists(new UserCreateRequest(
                "dave", "dave@example.org", "Dave", "pw", "local", List.of("p1")));

        verify(userStore).save(any(User.class));
        assertThat(created.noop()).isFalse();
        assertThat(created.login()).isEqualTo("dave");
    }

    @Test
    public void test_delete_returns_true_when_user_existed() throws Exception {
        when(userStore.delete("alice")).thenReturn(true);
        assertThat(service.delete("alice")).isTrue();
    }

    @Test
    public void test_delete_throws_when_user_missing() {
        when(userStore.delete("ghost")).thenReturn(false);
        try {
            service.delete("ghost");
            fail("expected UserNotFoundException");
        } catch (UserNotFoundException e) {
            assertThat(e.getMessage()).contains("ghost");
        }
    }

    @Test
    public void test_delete_if_exists_returns_false_when_user_missing() {
        when(userStore.delete("ghost")).thenReturn(false);
        assertThat(service.deleteIfExists("ghost")).isFalse();
    }

    @Test
    public void test_delete_if_exists_returns_true_when_user_existed() {
        when(userStore.delete("alice")).thenReturn(true);
        assertThat(service.deleteIfExists("alice")).isTrue();
    }
}
