package org.icij.datashare.session;

import org.icij.datashare.Repository;
import org.icij.datashare.text.Hasher;
import org.icij.datashare.user.User;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class UsersInDbTest {

    @Test
    public void find_user_in_db() {
        Repository repository = mock(Repository.class);
        User expected = new User("foo", "bar", "mail", "icij", new HashMap<String, Object>() {{
            put("field1", "val1");
        }});
        when(repository.getUser("foo")).thenReturn(expected);
        User actual = (User) new UsersInDb(repository).find("foo");
        assertThat(actual.id).isEqualTo(expected.id);
        assertThat(actual.name).isEqualTo(expected.name);
        assertThat(actual.email).isEqualTo(expected.email);
        assertThat(actual.details).isEqualTo(expected.details);
        assertThat(actual.provider).isEqualTo(expected.provider);
    }

    @Test
    public void find_user_in_db_with_password() {
        Repository repository = mock(Repository.class);
        User expected = new User("foo", "bar", "mail", "icij", new HashMap<String, Object>() {{
            put("password", Hasher.SHA_256.hash("bar"));
        }});
        when(repository.getUser("foo")).thenReturn(expected);
        User actual = (User) new UsersInDb(repository).find("foo", "bar");
        assertThat(actual.id).isEqualTo(expected.id);
        assertThat(actual.name).isEqualTo(expected.name);
        assertThat(actual.email).isEqualTo(expected.email);
        assertThat(actual.details).isEqualTo(expected.details);
        assertThat(actual.provider).isEqualTo(expected.provider);
    }

    @Test
    public void find_user_in_db_with_null() {
        assertThat(new UsersInDb(mock(Repository.class)).find("foo", "bar")).isNull();
    }

    @Test
    public void find_user_returns_null_when_user_does_not_exist(){
        assertThat(new UsersInDb(mock(Repository.class)).find("NotExistingUser")).isNull();
    }

    @Test
    public void find_user_in_db_with_bad_password() {
        Repository repository = mock(Repository.class);
        User expected = new User("foo", "bar", "mail", "icij", new HashMap<String, Object>() {{
            put("password", "unused");
        }});
        when(repository.getUser("foo")).thenReturn(expected);
        assertThat(new UsersInDb(repository).find("foo", "bad")).isNull();
    }

    @Test
    public void list_users_delegates_to_repository() {
        Repository repository = mock(Repository.class);
        User user1 = new User("alice", "Alice", "alice@example.com", "icij", new HashMap<>());
        User user2 = new User("bob", "Bob", "bob@example.com", "icij", new HashMap<>());
        when(repository.listUsers()).thenReturn(List.of(user1, user2));
        List<User> result = new UsersInDb(repository).listUsers();
        assertThat(result).hasSize(2);
        assertThat(result.get(0).id).isEqualTo("alice");
        assertThat(result.get(1).id).isEqualTo("bob");
    }
}
