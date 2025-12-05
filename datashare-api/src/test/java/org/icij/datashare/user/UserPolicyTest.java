package org.icij.datashare.user;

import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class UserPolicyTest {

    @Test
    public void test_factory_and_getters() {
        UserPolicy up = UserPolicy.of("user1", "projectA", new Role[]{Role.READER, Role.ADMIN});
        assertThat(up.userId()).isEqualTo("user1");
        assertThat(up.projectId()).isEqualTo("projectA");
        assertThat(up.isReader()).isTrue();
        assertThat(up.isWriter()).isFalse();
        assertThat(up.isAdmin()).isTrue();
    }

    @Test
    public void test_equals_and_hashcode() {
        UserPolicy up1 = UserPolicy.of("user1", "projectA", new Role[]{Role.READER});
        UserPolicy up2 = UserPolicy.of("user1", "projectA", new Role[]{Role.READER});
        UserPolicy up3 = UserPolicy.of("user1", "projectA", new Role[]{Role.READER, Role.WRITER});

        assertThat(up1).isEqualTo(up2);
        assertThat(up1.hashCode()).isEqualTo(up2.hashCode());
        assertThat(up1).isNotEqualTo(up3);
    }

    @Test
    public void test_json_round_trip_with_objectmapper() throws Exception {
        UserPolicy original = UserPolicy.of("user1", "projectA", new Role[]{Role.WRITER});

        String json = JsonObjectMapper.writeValueAsString(original);
        UserPolicy restored = JsonObjectMapper.readValue(json, UserPolicy.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.userId()).isEqualTo("user1");
        assertThat(restored.projectId()).isEqualTo("projectA");
        assertThat(restored.isReader()).isFalse();
        assertThat(restored.isWriter()).isTrue();
        assertThat(restored.isAdmin()).isFalse();
    }
}