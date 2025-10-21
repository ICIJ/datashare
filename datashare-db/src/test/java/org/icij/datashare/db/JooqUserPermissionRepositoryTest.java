package org.icij.datashare.db;

import org.icij.datashare.text.ProjectProxy;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserPermission;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Collection;
import java.util.List;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;

@RunWith(Parameterized.class)
public class JooqUserPermissionRepositoryTest {
    @Rule
    public DbSetupRule dbRule;
    private final JooqUserPermissionRepository repository;

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {new DbSetupRule("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared")},
                {new DbSetupRule("jdbc:postgresql://postgres/dstest?user=dstest&password=test")}
        });
    }

    public JooqUserPermissionRepositoryTest(DbSetupRule rule) {
        this.dbRule = rule;
        this.repository = rule.createUserPermissionRepository();
    }

    @Test
    public void test_save_and_get() {
        User user = new User("doe");
        ProjectProxy prj = new ProjectProxy("pA");
        UserPermission p = new UserPermission(user.id, prj.name, true, false, false);

        boolean saved = repository.save(p);
        assertThat(saved).isTrue();

        UserPermission got = repository.get(user, prj.name);
        assertThat(got).isNotNull();
        assertThat(got.userId()).isEqualTo("doe");
        assertThat(got.projectId()).isEqualTo("pA");
        assertThat(got.read()).isTrue();
        assertThat(got.write()).isFalse();
        assertThat(got.admin()).isFalse();
    }

    @Test
    public void test_upsert_updates_existing() {
        User user = new User("doe");
        ProjectProxy prj = new ProjectProxy("pA");
        repository.save(new UserPermission(user.id, prj.name, false, true, false));

        boolean saved = repository.save(new UserPermission(user.id, prj.name, true, true, true));
        assertThat(saved).isTrue();

        UserPermission got = repository.get(user, prj.name);
        assertThat(got.read()).isTrue();
        assertThat(got.write()).isTrue();
        assertThat(got.admin()).isTrue();
    }

    @Test
    public void test_list_by_user() {
        User user = new User("doe");
        ProjectProxy prjA = new ProjectProxy("pA");
        ProjectProxy prjB = new ProjectProxy("pB");
        repository.save(new UserPermission(user.id, prjA.name, true, true, false));
        repository.save(new UserPermission(user.id, prjB.name, false, true, true));

        List<UserPermission> list = repository.list(user);
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.stream().map(UserPermission::projectId).toList()).contains(prjA.name, prjB.name);
    }

    @Test
    public void test_delete() {
        User user = new User("doe");
        ProjectProxy prj = new ProjectProxy("pA");
        assertThat(repository.delete(user, prj.name)).isFalse();
        repository.save(new UserPermission(user.id, prj.name, true, true, false));
        assertThat(repository.delete(user, prj.name)).isTrue();
        assertThat(repository.get(user, prj.name)).isNull();
    }
}
