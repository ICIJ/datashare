package org.icij.datashare.user;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.time.DatashareTime;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.fromJson;
import static org.icij.datashare.user.User.local;

public class UserTest {
    @Test
    public void test_stringify_arrays()  {
        assertThat(fromJson("{\"key\":\"value\",\"array\":[]}", "test").details).includes(entry("key", "value"), entry("array", new LinkedList<>()));
        assertThat(fromJson("{\"key\":\"value\",\"array\":[\"item\"]}", "test").details).includes(entry("array", Collections.singletonList("item")));
    }

    @Test
    public void test_from_json_provider() {
        User user = fromJson("{\"uid\": \"user_id\"}", "test");
        assertThat(user.provider).isEqualTo("test");
        assertThat(user.id).isEqualTo("user_id");
    }

    @Test
    public void test_from_json_null() {
        assertThat(fromJson(null, "test")).isNull();
    }

    @Test
    public void test_getMap_with_null_value() {
        assertThat(fromJson("{\"key\":null,\"array\":[]}", "test").getDetails()).isNotEmpty();
    }

    @Test
    public void test_equals_with_user_subclass() {
        assertThat(User.local()).isEqualTo(User.local());
        assertThat(User.local()).isEqualTo(User.local());
    }

    @Test
    public void test_get_map_filters_password() {
        assertThat(new User(new HashMap<String, Object>() {{
            put("uid", "userid");
            put("password", "secret");
        }}).getDetails()).excludes(entry("password", "secret"));
    }

    @Test
    public void test_get_indices_for_local_user() {
        assertThat(User.local().getProjects()).containsExactly("local-datashare");
        assertThat(User.localUser("foo").getProjects()).containsExactly("foo-datashare");
    }

    @Test
    public void test_get_indices_for_external_user() {
        assertThat(new User(new HashMap<String, Object>() {{
            put("uid", "userid");
            put("groups_by_applications", new HashMap<String, Object>() {{
                put("datashare", Collections.singletonList("external_index"));
            }});
        }}).getProjects()).containsExactly("external_index");
    }

    @Test
    public void test_constructor_with_json_details() {
        User user = new User("id", "name", "email", "provider", "{\"key1\": \"value1\", \"key2\": \"value2\"}");
        assertThat(user.details).includes(entry("key1", "value1"), entry("key2", "value2"));
    }

    @Test
    public void test_details_as_json() {
        String jsonDetails = "{\"key1\":\"value1\",\"key2\":\"value2\"}";
        User user = new User("id", "name", "email", "provider", jsonDetails);
        assertThat(user.getJsonDetails()).isEqualTo(jsonDetails);
    }

    @Test
    public void test_values_in_map() throws Exception {
        User user = new User(new HashMap<String, Object>() {{
            put("uid", "foo");
            put("name", "Foo Bar");
            put("email", "foo@bar.com");
            put("provider", "external");
        }});
        assertThat(user.id).isEqualTo("foo");
        assertThat(user.name).isEqualTo("Foo Bar");
        assertThat(user.email).isEqualTo("foo@bar.com");
        assertThat(user.provider).isEqualTo("external");
    }

    @Test
    public void test_copy_constructor_with_null() {
        assertThat(new User((User) null).isNull()).isTrue();
    }

    @Test
    public void test_copy_constructor() {
        User user = new User("id", "name", "email", "provider", "{}");
        User copy = new User(user);
        assertThat(copy.id).isEqualTo("id");
        assertThat(copy.name).isEqualTo("name");
        assertThat(copy.email).isEqualTo("email");
        assertThat(copy.provider).isEqualTo("provider");
        assertThat(copy.details).isEqualTo(new HashMap<>());
    }

    @Test
    public void test_can_set_one_project() {
        User user = new User("id", "name", "email", "provider", "{}");
        user.setProjects(Collections.singletonList("foo"));
        assertThat(user.getProjects()).hasSize(1);
        assertThat(user.getProjects()).contains("foo");
    }

    @Test
    public void test_can_set_several_projects() {
        User user = new User("id", "name", "email", "provider", "{}");
        user.setProjects(Arrays.asList("foo", "bar"));
        assertThat(user.getProjects()).hasSize(2);
        assertThat(user.getProjects()).contains("foo");
        assertThat(user.getProjects()).contains("bar");
    }

    @Test
    public void test_can_set_several_duplicated_projects() {
        User user = new User("id", "name", "email", "provider", "{}");
        user.setProjects(Arrays.asList("foo", "bar", "foo"));
        assertThat(user.getProjects()).hasSize(2);
        assertThat(user.getProjects()).contains("foo");
        assertThat(user.getProjects()).contains("bar");
    }

    @Test
    public void test_can_set_several_projects_in_addition_to_json_detail() {
        User user = new User("id", "name", "email", "provider", "{ \"groups_by_applications\": { \"datashare\": [\"foo\"] } }");
        user.setProjects(Collections.singletonList("bar"));
        assertThat(user.getProjects()).hasSize(2);
        assertThat(user.getProjects()).contains("foo");
        assertThat(user.getProjects()).contains("bar");
    }
}
