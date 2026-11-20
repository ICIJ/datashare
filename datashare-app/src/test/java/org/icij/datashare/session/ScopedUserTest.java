package org.icij.datashare.session;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class ScopedUserTest {
    private final DatashareUser original = new DatashareUser(Map.of(
            "uid", "user",
            "groups_by_applications", Map.of("datashare", List.of("a", "b", "c"))));

    @Test
    public void test_projects_are_intersected_with_scope() {
        ScopedUser scoped = new ScopedUser(original, List.of("b", "c", "z"));

        assertThat(scoped.getProjectNames()).containsOnly("b", "c");
    }

    @Test
    public void test_scope_with_no_remaining_project_reaches_nothing() {
        DatashareUser single = new DatashareUser(Map.of(
                "uid", "user",
                "groups_by_applications", Map.of("datashare", List.of("a"))));
        ScopedUser scoped = new ScopedUser(single, List.of("z"));

        assertThat(scoped.getProjects()).isEmpty();
        assertThat(scoped.isGranted("z")).isFalse();
    }

    @Test
    public void test_wrapped_user_is_not_mutated() {
        new ScopedUser(original, List.of("b"));

        assertThat(original.getProjectNames()).containsOnly("a", "b", "c");
    }

    @Test
    public void test_details_project_list_is_narrowed() {
        ScopedUser scoped = new ScopedUser(original, List.of("b"));

        Map<String, Object> details = scoped.getDetails();
        Map<?, ?> groups = (Map<?, ?>) details.get("groups_by_applications");

        assertThat(groups.get("datashare")).isEqualTo(List.of("b"));
    }

    @Test
    public void test_unscoped_returns_delegate() {
        ScopedUser scoped = new ScopedUser(original, List.of("b"));
        DatashareUser plain = new DatashareUser("plain");

        assertThat(ScopedUser.unscoped(scoped)).isSameAs(original);
        assertThat(ScopedUser.unscoped(plain)).isSameAs(plain);
    }

    @Test
    public void test_allows() {
        DatashareUser plain = new DatashareUser("plain");
        ScopedUser scoped = new ScopedUser(original, List.of("b"));

        assertThat(ScopedUser.allows(plain, List.of("*"))).isTrue();
        assertThat(ScopedUser.allows(scoped, List.of())).isFalse();
        assertThat(ScopedUser.allows(scoped, List.of("*"))).isFalse();
        assertThat(ScopedUser.allows(scoped, List.of("b", "a"))).isFalse();
        assertThat(ScopedUser.allows(scoped, List.of("b"))).isTrue();
    }
}
