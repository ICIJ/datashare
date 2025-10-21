package org.icij.datashare.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class UserPermissionTest {

    @Test
    public void test_factory_and_getters() {
        UserPermission up = UserPermission.create("user1", "projectA", true, false, true);
        assertThat(up.userId()).isEqualTo("user1");
        assertThat(up.projectId()).isEqualTo("projectA");
        assertThat(up.read()).isTrue();
        assertThat(up.write()).isFalse();
        assertThat(up.admin()).isTrue();
    }

    @Test
    public void test_equals_and_hashcode() {
        UserPermission up1 = UserPermission.create("user1", "projectA", true, false, false);
        UserPermission up2 = UserPermission.create("user1", "projectA", true, false, false);
        UserPermission up3 = UserPermission.create("user1", "projectA", true, true, false);

        assertThat(up1).isEqualTo(up2);
        assertThat(up1.hashCode()).isEqualTo(up2.hashCode());
        assertThat(up1).isNotEqualTo(up3);
    }

    @Test
    public void test_toString_contains_fields() {
        UserPermission up = UserPermission.create("user1", "projectA", true, false, true);
        String s = up.toString();
        assertThat(s).contains("userId=user1");
        assertThat(s).contains("projectId=projectA");
        assertThat(s).contains("read=true");
        assertThat(s).contains("write=false");
        assertThat(s).contains("admin=true");
    }

    @Test
    public void test_json_round_trip_with_objectmapper() throws Exception {
        ObjectMapper mapper = JsonObjectMapper.MAPPER;
        UserPermission original = UserPermission.create("user1", "projectA", false, true, false);

        String json = mapper.writeValueAsString(original);
        UserPermission restored = mapper.readValue(json, UserPermission.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.userId()).isEqualTo("user1");
        assertThat(restored.projectId()).isEqualTo("projectA");
        assertThat(restored.read()).isFalse();
        assertThat(restored.write()).isTrue();
        assertThat(restored.admin()).isFalse();
    }
}
