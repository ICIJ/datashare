package org.icij.datashare.session;

import org.icij.datashare.text.Hasher;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserPolicy;
import org.icij.datashare.user.UserRepository;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class UsersInDbTest {

    @Test
    public void find_user_in_db() {
        UserRepository repository = mock(UserRepository.class);
        UserPolicy userPolicy = UserPolicy.create("foo", "bar");
        User expected = new User("foo", "bar", "mail", "icij", "{\"field1\": \"val1\"}}", Set.of(userPolicy));
        when(repository.getUserWithPolicies("foo")).thenReturn(expected);
        User actual = (User) new UsersInDb(repository).find("foo");
        assertThat(actual.id).isEqualTo(expected.id);
        assertThat(actual.name).isEqualTo(expected.name);
        assertThat(actual.email).isEqualTo(expected.email);
        assertThat(actual.details).isEqualTo(expected.details);
        assertThat(actual.provider).isEqualTo(expected.provider);
        assertThat(actual.policies).isEqualTo(expected.policies);
    }

    @Test
    public void find_user_in_db_with_password() {
        UserRepository repository = mock(UserRepository.class);
        UserPolicy userPolicy = UserPolicy.create("foo", "bar");
        User expected = new User("foo", "bar", "mail", "icij", "{\"password\": \"%s\"}}".formatted(Hasher.SHA_256.hash("bar")), Set.of(userPolicy));
        when(repository.getUserWithPolicies("foo")).thenReturn(expected);
        User actual = (User) new UsersInDb(repository).find("foo", "bar");
        assertThat(actual.id).isEqualTo(expected.id);
        assertThat(actual.name).isEqualTo(expected.name);
        assertThat(actual.email).isEqualTo(expected.email);
        assertThat(actual.details).isEqualTo(expected.details);
        assertThat(actual.provider).isEqualTo(expected.provider);
        assertThat(actual.policies).isEqualTo(expected.policies);
    }

    @Test
    public void find_user_in_db_with_null() {
        assertThat(new UsersInDb(mock(UserRepository.class)).find("foo", "bar")).isNull();
    }

    @Test
    public void find_user_in_db_with_bad_password() {
        UserRepository repository = mock(UserRepository.class);
        User expected = new User("foo", "bar", "mail", "icij", new HashMap<>() {{
            put("password", "unused");
        }});
        when(repository.getUserWithPolicies("foo")).thenReturn(expected);
        assertThat(new UsersInDb(repository).find("foo", "bad")).isNull();
    }
}
