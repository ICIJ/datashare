package org.icij.datashare.user;

import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.fest.assertions.Assertions.assertThat;

public class ProjectScopeTest {
    @Test
    public void test_all_allows_anything() {
        assertThat(ProjectScope.ALL.allows("a")).isTrue();
        assertThat(ProjectScope.ALL.allows("*")).isTrue();
        assertThat(ProjectScope.ALL.allowsAll(List.of())).isTrue();
    }

    @Test
    public void test_only_allows_its_projects() {
        ProjectScope only = new ProjectScope.Only(Set.of("b"));

        assertThat(only.allows("b")).isTrue();
        assertThat(only.allowsAll(List.of("b"))).isTrue();
    }

    @Test
    public void test_only_refuses_other_projects_wildcard_and_empty_list() {
        ProjectScope only = new ProjectScope.Only(Set.of("b"));

        assertThat(only.allows("a")).isFalse();
        assertThat(only.allows("*")).isFalse();
        assertThat(only.allows(null)).isFalse();
        assertThat(new ProjectScope.Only(Set.of("*")).allows("*")).isFalse();
        assertThat(only.allowsAll(List.of("b", "a"))).isFalse();
        assertThat(only.allowsAll(List.of())).isFalse();
    }
}
