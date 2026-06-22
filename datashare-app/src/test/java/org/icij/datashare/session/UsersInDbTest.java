package org.icij.datashare.session;

import org.icij.datashare.Repository;
import org.icij.datashare.text.Hasher;
import org.icij.datashare.user.User;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.user.admin.UserFilter;
import org.icij.datashare.web.WebResponse;
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
    public void list_users_with_filter_delegates_to_repository() {
        Repository repository = mock(Repository.class);
        org.icij.datashare.user.User alice = new org.icij.datashare.user.User("alice", "Alice", "alice@example.org", "local", new HashMap<>());
        UserFilter filter = new UserFilter("ali", null, null, null);
        when(repository.listUsers(filter)).thenReturn(List.of(alice));

        List<org.icij.datashare.user.User> result = new UsersInDb(repository).listUsers(filter, 0, 100).items;

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id).isEqualTo("alice");
        assertThat(result.get(0)).isInstanceOf(DatashareUser.class);
    }

    @Test
    public void list_users_filters_group_in_memory() {
        Repository repository = mock(Repository.class);
        java.util.Map<String, Object> details = new HashMap<>();
        java.util.Map<String, Object> appsByGroup = new HashMap<>();
        appsByGroup.put("datashare", List.of("my-project"));
        details.put("groups_by_applications", appsByGroup);
        org.icij.datashare.user.User alice = new org.icij.datashare.user.User("alice", "Alice", "alice@example.org", "local", details);
        org.icij.datashare.user.User bob   = new org.icij.datashare.user.User("bob",   "Bob",   "bob@example.org",   "local", new HashMap<>());
        // Repository returns both (group filter not pushed to SQL)
        UserFilter filter = new UserFilter(null, null, null, "my-project");
        when(repository.listUsers(filter)).thenReturn(List.of(alice, bob));

        List<org.icij.datashare.user.User> result = new UsersInDb(repository).listUsers(filter, 0, 100).items;

        // In-memory group filter removes bob
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id).isEqualTo("alice");
    }

    @Test
    public void list_users_pagination_slices_results() {
        Repository repository = mock(Repository.class);
        org.icij.datashare.user.User alice = new org.icij.datashare.user.User("alice", "Alice", "alice@example.org", "local", new HashMap<>());
        org.icij.datashare.user.User bob   = new org.icij.datashare.user.User("bob",   "Bob",   "bob@example.org",   "local", new HashMap<>());
        org.icij.datashare.user.User carol = new org.icij.datashare.user.User("carol", "Carol", "carol@example.org", "local", new HashMap<>());
        UserFilter filter = new UserFilter(null, null, null, null);
        when(repository.listUsers(filter)).thenReturn(List.of(alice, bob, carol));

        WebResponse<org.icij.datashare.user.User> result = new UsersInDb(repository).listUsers(filter, 1, 1);

        assertThat(result.pagination.total()).isEqualTo(3);
        assertThat(result.items).hasSize(1);
        assertThat(result.items.get(0).id).isEqualTo("bob");
    }
}
