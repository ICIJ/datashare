package org.icij.datashare.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.user.User;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class DatashareUserTest {

    @Test
    public void test_serialize_deserialize() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        String json = objectMapper.writeValueAsString(new DatashareUser(new User("id", "name", "email", "provider", "{\"key1\": \"value1\", \"key2\": \"value2\"}")));
        assertThat(json).contains("{\"id\":\"id\",\"name\":\"name\",\"email\":\"email\",\"provider\":\"provider\"}");

        assertThat(objectMapper.readValue(json, DatashareUser.class)).isNotNull();
    }
}