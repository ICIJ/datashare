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
public class JooqCasbinRuleRepositoryTest extends TestCase {

    @Rule public DbSetupRule dbRule;
    private final JooqCasbinRuleRepository repository;
    private static final List<DbSetupRule> rulesToClose = new ArrayList<>();

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {new DbSetupRule("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared")},
                {new DbSetupRule(EnvUtils.resolveUri("postgres", "jdbc:postgresql://postgres/dstest?user=dstest&password=test"))}
        });
    }

    public JooqCasbinRuleRepositoryTest(DbSetupRule rule) {
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
    public void testAddPolicy() {
        repository.addPolicy("p", "p", asList("alice", "data1", "read"));
        Model model = new Model();
        model.addDef("p", "p", "sub, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("alice", "data1", "read"))).isTrue();
    }

    @Test
    public void testAddPolicies() {
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
    public void testRemovePolicy() {
        repository.addPolicy("p", "p", asList("alice", "data1", "read"));
        repository.removePolicy("p", "p", asList("alice", "data1", "read"));
        Model model = new Model();
        model.addDef("p", "p", "sub, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("alice", "data1", "read"))).isFalse();
    }

    @Test
    public void testRemovePolicies() {
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
    public void testRemoveFilteredPolicy() {
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
    public void testRemoveFilteredPolicyWithMultipleFields() {
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
    public void testRemoveFilteredPolicyWithEmptyValues() {
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
    public void testSavePolicy() {
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
    public void testUpdatePolicyWithNonExistentOldRule() {
        repository.addPolicy("p", "p", asList("alice", "data1", "read"));
        repository.updatePolicy("p", "p", asList("bob", "data1", "read"), asList("bob", "data1", "write"));
        Model model = new Model();
        model.addDef("p", "p", "sub, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("alice", "data1", "read"))).isTrue();
        assertThat(model.hasPolicy("p", "p", asList("bob", "data1", "write"))).isTrue();
    }

    @Test
    public void testAddPoliciesBatching() {
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
    public void testUpdatePolicy() {
        repository.addPolicy("p", "p", asList("alice", "data1", "read"));
        repository.updatePolicy("p", "p", asList("alice", "data1", "read"), asList("alice", "data1", "write"));
        Model model = new Model();
        model.addDef("p", "p", "sub, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("alice", "data1", "read"))).isFalse();
        assertThat(model.hasPolicy("p", "p", asList("alice", "data1", "write"))).isTrue();
    }

    @Test
    public void testAddPolicyWithLongRule() {
        repository.addPolicy("p", "p", asList("v0", "v1", "v2", "v3", "v4", "v5"));
        Model model = new Model();
        model.addDef("p", "p", "sub, obj, act, v3, v4, v5");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("v0", "v1", "v2", "v3", "v4", "v5"))).isTrue();
    }
}