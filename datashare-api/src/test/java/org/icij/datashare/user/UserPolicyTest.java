package org.icij.datashare.user;

import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class UserPolicyTest {

    @Test
    public void test_factory_and_getters() {
        UserPolicy up = UserPolicy.create("user1", "projectA", new Role[] {Role.READER, Role.ADMIN});
        assertThat(up.userId()).isEqualTo("user1");
        assertThat(up.projectId()).isEqualTo("projectA");
        assertThat(up.reader()).isTrue();
        assertThat(up.writer()).isFalse();
        assertThat(up.admin()).isTrue();
    }

    @Test
    public void test_equals_and_hashcode() {
        UserPolicy up1 = UserPolicy.create("user1", "projectA", new Role[] {Role.READER});
        UserPolicy up2 = UserPolicy.create("user1", "projectA", new Role[] {Role.READER});
        UserPolicy up3 = UserPolicy.create("user1", "projectA", new Role[] {Role.READER, Role.WRITER});

        assertThat(up1).isEqualTo(up2);
        assertThat(up1.hashCode()).isEqualTo(up2.hashCode());
        assertThat(up1).isNotEqualTo(up3);
    }

    @Test
    public void test_json_round_trip_with_objectmapper() throws Exception {
        UserPolicy original = UserPolicy.create("user1", "projectA", new Role[] {Role.WRITER});

        String json = JsonObjectMapper.writeValueAsString(original);
        UserPolicy restored = JsonObjectMapper.readValue(json, UserPolicy.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.userId()).isEqualTo("user1");
        assertThat(restored.projectId()).isEqualTo("projectA");
        assertThat(restored.reader()).isFalse();
        assertThat(restored.writer()).isTrue();
        assertThat(restored.admin()).isFalse();
    }
}
