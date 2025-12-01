package org.icij.datashare.db;

import junit.framework.TestCase;
import org.icij.datashare.text.ProjectProxy;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserPolicy;
import org.junit.AfterClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;

@RunWith(Parameterized.class)
public class JooqUserRepositoryTest extends TestCase {

    @Rule public DbSetupRule dbRule;
    private final JooqUserRepository repository;
    private static final List<DbSetupRule> rulesToClose = new ArrayList<>();

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {new DbSetupRule("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared")},
                {new DbSetupRule("jdbc:postgresql://postgres/dstest?user=dstest&password=test")}
        });
    }

    public JooqUserRepositoryTest(DbSetupRule rule) {
        dbRule = rule;
        repository = rule.createUserRepository();
        rulesToClose.add(dbRule);
    }

    @AfterClass
    public static void shutdownPools() {
        for (DbSetupRule rule : rulesToClose) {
            rule.shutdown();
        }
    }

    @Test
    public void test_save_and_get() {
        User user = new User("doe");
        ProjectProxy prj = new ProjectProxy("pA");
        UserPolicy p = new UserPolicy(user.id, prj.name, new Role[]{Role.READER});

        boolean saved = repository.save(p);

        assertThat(saved).isTrue();

        UserPolicy got = repository.get(user, prj.name);
        assertThat(got).isNotNull();
        assertThat(got.userId()).isEqualTo("doe");
        assertThat(got.projectId()).isEqualTo("pA");
        assertThat(got.isReader()).isTrue();
        assertThat(got.isWriter()).isFalse();
        assertThat(got.isAdmin()).isFalse();
    }

    @Test
    public void test_upsert_updates_existing() {
        User user = new User("doe");
        ProjectProxy prj = new ProjectProxy("pA");
        repository.save(new UserPolicy(user.id, prj.name, new Role[]{Role.WRITER}));

        boolean saved = repository.save(new UserPolicy(user.id, prj.name, new Role[]{Role.READER, Role.WRITER,Role.ADMIN}));
        assertThat(saved).isTrue();

        UserPolicy got = repository.get(user, prj.name);
        assertThat(got.isReader()).isTrue();
        assertThat(got.isWriter()).isTrue();
        assertThat(got.isAdmin()).isTrue();
    }

    @Test
    public void test_getPolicies_by_user() {
        User user = new User("doe");
        ProjectProxy prjA = new ProjectProxy("pA");
        ProjectProxy prjB = new ProjectProxy("pB");
        repository.save(new UserPolicy(user.id, prjA.name, new Role[] {Role.READER, Role.WRITER}));
        repository.save(new UserPolicy(user.id, prjB.name, new Role[]{ Role.WRITER,Role.ADMIN}));

        List<UserPolicy> list = repository.getPolicies(user);
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.stream().map(UserPolicy::projectId).toList()).contains(prjA.name, prjB.name);
    }

    @Test
    public void test_delete() {
        User user = new User("jane");
        ProjectProxy prj = new ProjectProxy("pA");
        assertThat(repository.delete(user, prj.name)).isFalse();
        repository.save(new UserPolicy(user.id, prj.name, new Role[]{Role.READER, Role.WRITER }));
        assertThat(repository.delete(user, prj.name)).isTrue();
        assertThat(repository.get(user, prj.name)).isNull();
    }

    @Test
    public void test_get_user_with_policies() {
        repository.save(new User("foo"));
        repository.save(new UserPolicy("foo", "bar", new Role[]{Role.READER, Role.WRITER}));
        repository.save(new UserPolicy("foo", "baz", new Role[]{Role.ADMIN}));

        User foo = repository.getUser("foo");

        assertThat(foo.policies).hasSize(2);
        assertThat(foo.getRoles("bar")).containsOnly(Role.READER, Role.WRITER);
        assertThat(foo.getRoles("baz")).containsOnly(Role.ADMIN);
    }
}