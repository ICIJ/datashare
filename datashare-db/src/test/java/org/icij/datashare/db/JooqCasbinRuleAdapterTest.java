package org.icij.datashare.db;

import junit.framework.TestCase;
import org.casbin.jcasbin.model.Model;
import org.icij.datashare.EnvUtils;
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
public class JooqCasbinRuleAdapterTest extends TestCase {

    @Rule public DbSetupRule dbRule;
    private final JooqCasbinRuleAdapter repository;
    private static final List<DbSetupRule> rulesToClose = new ArrayList<>();

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {new DbSetupRule("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared")},
                {new DbSetupRule(EnvUtils.resolveUri("postgres", "jdbc:postgresql://postgres/dstest?user=dstest&password=test"))}
        });
    }

    public JooqCasbinRuleAdapterTest(DbSetupRule rule) {
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
    public void test_add_policy() {
        repository.addPolicy("p", "p", asList("alice", "data1", "read"));
        Model model = new Model();
        model.addDef("p", "p", "sub, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("alice", "data1", "read"))).isTrue();
    }

    @Test
    public void test_add_policies() {
        List<List<String>> rules = asList(
                asList("alice", "data1", "read"),
                asList("bob", "data2", "write")
        );
        repository.addPolicies("p", "p", rules);
        Model model = new Model();
        model.addDef("p", "p", "sub, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("alice", "data1", "read"))).isTrue();
        assertThat(model.hasPolicy("p", "p", asList("bob", "data2", "write"))).isTrue();
    }

    @Test
    public void test_remove_policy() {
        repository.addPolicy("p", "p", asList("alice", "data1", "read"));
        repository.removePolicy("p", "p", asList("alice", "data1", "read"));
        Model model = new Model();
        model.addDef("p", "p", "sub, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("alice", "data1", "read"))).isFalse();
    }

    @Test
    public void test_remove_policies() {
        List<List<String>> rules = asList(
                asList("alice", "data1", "read"),
                asList("bob", "data2", "write")
        );
        repository.addPolicies("p", "p", rules);
        repository.removePolicies("p", "p", rules);
        Model model = new Model();
        model.addDef("p", "p", "sub, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("alice", "data1", "read"))).isFalse();
        assertThat(model.hasPolicy("p", "p", asList("bob", "data2", "write"))).isFalse();
    }

    @Test
    public void test_remove_filtered_policy() {
        repository.addPolicy("p", "p", asList("alice", "data1", "read"));
        repository.addPolicy("p", "p", asList("bob", "data1", "read"));
        repository.addPolicy("p", "p", asList("alice", "data2", "write"));

        repository.removeFilteredPolicy("p", "p", 1, "data1");
        Model model = new Model();
        model.addDef("p", "p", "sub, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("alice", "data1", "read"))).isFalse();
        assertThat(model.hasPolicy("p", "p", asList("bob", "data1", "read"))).isFalse();
        assertThat(model.hasPolicy("p", "p", asList("alice", "data2", "write"))).isTrue();
    }

    @Test
    public void test_remove_filtered_policy_with_multiple_fields() {
        repository.addPolicy("p", "p", asList("alice", "data1", "read"));
        repository.addPolicy("p", "p", asList("alice", "data1", "write"));
        repository.addPolicy("p", "p", asList("bob", "data1", "read"));

        repository.removeFilteredPolicy("p", "p", 0, "alice", "data1");
        Model model = new Model();
        model.addDef("p", "p", "sub, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("alice", "data1", "read"))).isFalse();
        assertThat(model.hasPolicy("p", "p", asList("alice", "data1", "write"))).isFalse();
        assertThat(model.hasPolicy("p", "p", asList("bob", "data1", "read"))).isTrue();
    }

    @Test
    public void test_remove_filtered_policy_with_empty_values() {
        repository.addPolicy("p", "p", asList("alice", "data1", "read"));
        repository.addPolicy("p", "p", asList("bob", "data1", "read"));
        repository.addPolicy("p", "p", asList("alice", "data2", "read"));

        repository.removeFilteredPolicy("p", "p", 0, "alice", "", "read");
        Model model = new Model();
        model.addDef("p", "p", "sub, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("alice", "data1", "read"))).isFalse();
        assertThat(model.hasPolicy("p", "p", asList("alice", "data2", "read"))).isFalse();
        assertThat(model.hasPolicy("p", "p", asList("bob", "data1", "read"))).isTrue();
    }

    @Test
    public void test_save_policy() {
        Model model = new Model();
        model.addDef("p", "p", "sub, obj, act");
        model.addDef("g", "g", "_, _");
        model.addPolicy("p", "p", asList("alice", "data1", "read"));
        model.addPolicy("g", "g", asList("alice", "data2_admin"));
        repository.savePolicy(model);

        Model newModel = new Model();
        newModel.addDef("p", "p", "sub, obj, act");
        newModel.addDef("g", "g", "_, _");
        repository.loadPolicy(newModel);
        assertThat(newModel.hasPolicy("p", "p", asList("alice", "data1", "read"))).isTrue();
        assertThat(newModel.hasPolicy("g", "g", asList("alice", "data2_admin"))).isTrue();
    }

    @Test
    public void test_update_policy_with_non_existent_old_rule() {
        repository.addPolicy("p", "p", asList("alice", "data1", "read"));
        repository.updatePolicy("p", "p", asList("bob", "data1", "read"), asList("bob", "data1", "write"));
        Model model = new Model();
        model.addDef("p", "p", "sub, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("alice", "data1", "read"))).isTrue();
        assertThat(model.hasPolicy("p", "p", asList("bob", "data1", "write"))).isTrue();
    }

    @Test
    public void test_add_policies_batching() {
        List<List<String>> rules = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            rules.add(asList("user" + i, "data", "read"));
        }
        repository.addPolicies("p", "p", rules);
        Model model = new Model();
        model.addDef("p", "p", "sub, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("user0", "data", "read"))).isTrue();
        assertThat(model.hasPolicy("p", "p", asList("user1499", "data", "read"))).isTrue();
    }

    @Test
    public void test_update_policy() {
        repository.addPolicy("p", "p", asList("alice", "data1", "read"));
        repository.updatePolicy("p", "p", asList("alice", "data1", "read"), asList("alice", "data1", "write"));
        Model model = new Model();
        model.addDef("p", "p", "sub, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("alice", "data1", "read"))).isFalse();
        assertThat(model.hasPolicy("p", "p", asList("alice", "data1", "write"))).isTrue();
    }

    @Test
    public void test_add_policy_with_long_rule() {
        repository.addPolicy("p", "p", asList("v0", "v1", "v2", "v3", "v4", "v5"));
        Model model = new Model();
        model.addDef("p", "p", "sub, obj, act, v3, v4, v5");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("v0", "v1", "v2", "v3", "v4", "v5"))).isTrue();
    }
}