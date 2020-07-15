package org.icij.datashare.session;

import org.icij.datashare.Repository;
import org.icij.datashare.user.User;
import org.junit.Test;

import java.util.HashMap;

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
}