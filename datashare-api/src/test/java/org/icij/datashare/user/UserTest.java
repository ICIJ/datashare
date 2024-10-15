package org.icij.datashare.user;


import org.icij.datashare.json.JsonObjectMapper;
import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.fromJson;

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
    public void test_local_user_with_mapper() throws Exception {
        User user = JsonObjectMapper.MAPPER.readValue("{\"id\":\"local\",\"name\":null,\"email\":null,\"provider\":\"test\"}", User.class);
        assertThat(user.provider).isEqualTo("test");
        assertThat(user.id).isEqualTo("local");
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
        assertThat(User.local().getProjectNames()).containsExactly("local-datashare");
        assertThat(User.localUser("foo").getProjectNames()).containsExactly("foo-datashare");
    }

    @Test
    public void test_get_indices_for_external_user() {
        assertThat(new User(new HashMap<>() {{
            put("uid", "userid");
            put("groups_by_applications", new HashMap<String, Object>() {{
                put("datashare", Collections.singletonList("external_index"));
            }});
        }}).getProjectNames()).containsExactly("external_index");
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
        user.setProjectNames(Collections.singletonList("foo"));
        assertThat(user.getProjectNames()).hasSize(1);
        assertThat(user.getProjectNames()).contains("foo");
    }

    @Test
    public void test_local_user_with_projects() {
        User user = User.localUser("foo", List.of("p1", "p2"));
        assertThat(user.id).isEqualTo("foo");
        assertThat(user.getProjects()).contains (project("p1"), project("p2"));
    }

    @Test
    public void test_can_set_several_projects() {
        User user = new User("id", "name", "email", "provider", "{}");
        user.setProjectNames(Arrays.asList("foo", "bar"));
        assertThat(user.getProjectNames()).hasSize(2);
        assertThat(user.getProjectNames()).contains("foo");
        assertThat(user.getProjectNames()).contains("bar");
    }

    @Test
    public void test_can_set_several_duplicated_projects() {
        User user = new User("id", "name", "email", "provider", "{}");
        user.setProjectNames(Arrays.asList("foo", "bar", "foo"));
        assertThat(user.getProjectNames()).hasSize(2);
        assertThat(user.getProjectNames()).contains("foo");
        assertThat(user.getProjectNames()).contains("bar");
    }

    @Test
    public void test_get_project_key() {
        System.setProperty("datashare.user.projects", "projects");
        assertThat(User.getDefaultProjectsKey()).isEqualTo("projects");
    }

    @Test
    public void test_get_projects() {
        assertThat(new User(Map.of("groups_by_applications", Map.of("datashare", List.of("my_project")))).getProjects())
                .contains(project("my_project"));
        System.setProperty("datashare.user.projects", "projects");
        assertThat(new User(Map.of("projects", List.of("my_project"))).getProjects())
                .contains(project("my_project"));
    }

    @Test
    public void test_can_set_several_projects_in_addition_to_json_detail() {
        User user = new User("id", "name", "email", "provider", "{ \"groups_by_applications\": { \"datashare\": [\"foo\"] } }");
        user.setProjectNames(Collections.singletonList("bar"));
        assertThat(user.getProjectNames()).hasSize(2);
        assertThat(user.getProjectNames()).contains("foo");
        assertThat(user.getProjectNames()).contains("bar");
    }

    @Test
    public void test_clear_projects_but_details() {
        User user = new User("id", "name", "email", "provider", "{ \"groups_by_applications\": { \"datashare\": [\"foo\"] } }");
        user.setProjectNames(Collections.singletonList("bar"));
        assertThat(user.getProjectNames()).hasSize(2);
        user.clearProjects();
        assertThat(user.getProjectNames()).hasSize(1);
        assertThat(user.getProjectNames()).contains("foo");
    }

    @Test
    public void test_clear_projects() {
        User user = new User("id", "name", "email", "provider");
        user.setProjectNames(Collections.singletonList("bar"));
        assertThat(user.getProjectNames()).hasSize(1);
        user.clearProjects();
        assertThat(user.getProjectNames()).hasSize(0);
    }

    @After
    public void tearDown() throws Exception {
        System.setProperty("datashare.user.projects", "");
    }
}
