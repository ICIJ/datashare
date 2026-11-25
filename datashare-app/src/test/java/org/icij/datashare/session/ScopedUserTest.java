package org.icij.datashare.session;

import org.icij.datashare.user.ProjectScope;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
    public void test_scope_is_the_projects_still_granted() {
        ScopedUser scoped = new ScopedUser(original, List.of("b", "z"));

        assertThat(scoped.scope()).isEqualTo(new ProjectScope.Only(Set.of("b")));
    }

    @Test
    public void test_scope_allows() {
        DatashareUser plain = new DatashareUser("plain");
        ScopedUser scoped = new ScopedUser(original, List.of("b"));

        assertThat(plain.scope()).isSameAs(ProjectScope.ALL);
        assertThat(plain.scope().allowsAll(List.of("*"))).isTrue();
        assertThat(scoped.scope().allowsAll(List.of())).isFalse();
        assertThat(scoped.scope().allowsAll(List.of("*"))).isFalse();
        assertThat(scoped.scope().allowsAll(List.of("b", "a"))).isFalse();
        assertThat(scoped.scope().allowsAll(List.of("b"))).isTrue();
    }
}
