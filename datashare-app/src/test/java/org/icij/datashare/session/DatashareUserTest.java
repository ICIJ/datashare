package org.icij.datashare.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserPolicy;
import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;

import static org.fest.assertions.Assertions.assertThat;

public class DatashareUserTest {

    @Test
    public void test_serialize_deserialize() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        HashSet<UserPolicy> policies = new HashSet<>(Collections.singleton(new UserPolicy("id", "projectA", new Role[]{Role.READER})));
        String json = objectMapper.writeValueAsString(new DatashareUser(new User("id", "name", "email", "provider", "{\"key1\": \"value1\", \"key2\": \"value2\"}", policies)));
        assertThat(json).contains("{\"id\":\"id\",\"name\":\"name\",\"email\":\"email\",\"provider\":\"provider\",\"policies\":[{\"userId\":\"id\",\"projectId\":\"projectA\",\"roles\":[\"READER\"]}]}");

        assertThat(objectMapper.readValue(json, DatashareUser.class)).isNotNull();
    }
}