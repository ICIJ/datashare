package org.icij.datashare.user.admin;

import org.junit.Test;
import java.util.List;
import static org.fest.assertions.Assertions.assertThat;

public class UserListItemTest {

    @Test
    public void test_fields_accessible() {
        UserListItem.Permission perm = new UserListItem.Permission("INSTANCE_ADMIN", "*::*");
        UserListItem item = new UserListItem("toto", "Toto", "toto@t.com", List.of(perm));

        assertThat(item.uid()).isEqualTo("toto");
        assertThat(item.name()).isEqualTo("Toto");
        assertThat(item.email()).isEqualTo("toto@t.com");
        assertThat(item.permissions()).hasSize(1);
        assertThat(item.permissions().get(0).v1()).isEqualTo("INSTANCE_ADMIN");
        assertThat(item.permissions().get(0).v2()).isEqualTo("*::*");
    }

    @Test
    public void test_no_name_allowed() {
        UserListItem item = new UserListItem("alice", null, "a@b.com", List.of());
        assertThat(item.name()).isNull();
        assertThat(item.permissions()).isEmpty();
    }
}
