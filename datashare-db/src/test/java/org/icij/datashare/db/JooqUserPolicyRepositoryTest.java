package org.icij.datashare.db;

import junit.framework.TestCase;
import org.icij.datashare.user.Role;
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
public class JooqUserPolicyRepositoryTest extends TestCase {

    @Rule public DbSetupRule dbRule;
    private final JooqUserPolicyRepository repository;
    private static final List<DbSetupRule> rulesToClose = new ArrayList<>();

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {new DbSetupRule("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared")},
                {new DbSetupRule("jdbc:postgresql://postgres/dstest?user=dstest&password=test")}
        });
    }

    public JooqUserPolicyRepositoryTest(DbSetupRule rule) {
        dbRule = rule;
        repository = rule.createUserPolicyRepository();
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
        boolean saved = repository.save(new UserPolicy("doe", "pA", new Role[]{Role.READER}));

        assertThat(saved).isTrue();

        UserPolicy got = repository.get("doe", "pA");
        assertThat(got).isNotNull();
        assertThat(got.userId()).isEqualTo("doe");
        assertThat(got.projectId()).isEqualTo("pA");
        assertThat(got.isReader()).isTrue();
        assertThat(got.isWriter()).isFalse();
        assertThat(got.isAdmin()).isFalse();
    }

    @Test
    public void test_upsert_updates_existing() {
        UserPolicy oldPolicy = new UserPolicy("doe", "pA", new Role[]{Role.WRITER});
        repository.save(oldPolicy);

        UserPolicy newPolicy = new UserPolicy("doe", "pA", new Role[]{Role.READER, Role.WRITER, Role.ADMIN});
        boolean saved = repository.save(newPolicy);
        assertThat(saved).isTrue();

        UserPolicy got = repository.get("doe", "pA");
        assertThat(got.isReader()).isTrue();
        assertThat(got.isWriter()).isTrue();
        assertThat(got.isAdmin()).isTrue();
    }

    @Test
    public void test_getByUserId_by_user_id() {
        repository.save(new UserPolicy("bar", "pA", new Role[]{Role.READER, Role.WRITER}));
        repository.save(new UserPolicy("bar", "pB", new Role[]{Role.WRITER, Role.ADMIN}));
        repository.save(new UserPolicy("baz", "pB", new Role[]{Role.WRITER, Role.ADMIN}));

        List<UserPolicy> policiesBar = repository.getByUserId("bar").toList();
        assertThat(policiesBar).hasSize(2);
        List<UserPolicy> policiesFoo = repository.getByUserId("baz").toList();
        assertThat(policiesFoo).hasSize(1);
        List<UserPolicy> policiesNone = repository.getByUserId("none").toList();
        assertThat(policiesNone).hasSize(0);
    }

    @Test
    public void test_delete() {
        assertThat(repository.delete("jane", "pA")).isFalse();
        repository.save(new UserPolicy("jane", "pA", new Role[]{Role.READER, Role.WRITER}));
        assertThat(repository.delete("jane", "pA")).isTrue();
        assertThat(repository.get("jane", "pA")).isNull();
    }

}