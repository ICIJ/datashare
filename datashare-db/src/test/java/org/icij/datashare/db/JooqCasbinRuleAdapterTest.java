package org.icij.datashare.db;

import junit.framework.TestCase;
import org.casbin.jcasbin.exception.CasbinAdapterException;
import org.casbin.jcasbin.model.Model;
import org.casbin.jcasbin.persist.file_adapter.FilteredAdapter.Filter;
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
        repository.addPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"));
        Model model = new Model();
        model.addDef("p", "p", "sub, dom, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"))).isTrue();
    }

    @Test
    public void test_add_policies() {
        List<List<String>> rules = asList(
                asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"),
                asList("bob", "icij", "citrus-confidential", "PROJECT_EDITOR")
        );
        repository.addPolicies("p", "p", rules);
        Model model = new Model();
        model.addDef("p", "p", "sub, dom, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"))).isTrue();
        assertThat(model.hasPolicy("p", "p", asList("bob", "icij", "citrus-confidential", "PROJECT_EDITOR"))).isTrue();
    }

    @Test
    public void test_remove_policy() {
        repository.addPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"));
        repository.removePolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"));
        Model model = new Model();
        model.addDef("p", "p", "sub, dom, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"))).isFalse();
    }

    @Test
    public void test_remove_policies() {
        List<List<String>> rules = asList(
                asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"),
                asList("bob", "icij", "citrus-confidential", "PROJECT_EDITOR")
        );
        repository.addPolicies("p", "p", rules);
        repository.removePolicies("p", "p", rules);
        Model model = new Model();
        model.addDef("p", "p", "sub, dom, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"))).isFalse();
        assertThat(model.hasPolicy("p", "p", asList("bob", "icij", "citrus-confidential", "PROJECT_EDITOR"))).isFalse();
    }

    @Test
    public void test_remove_filtered_policy() {
        repository.addPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"));
        repository.addPolicy("p", "p", asList("bob", "icij", "banana-papers", "PROJECT_MEMBER"));
        repository.addPolicy("p", "p", asList("alice", "citrus-confidential", "PROJECT_EDITOR"));

        repository.removeFilteredPolicy("p", "p", 2, "banana-papers");
        Model model = new Model();
        model.addDef("p", "p", "sub, dom, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"))).isFalse();
        assertThat(model.hasPolicy("p", "p", asList("bob", "icij", "banana-papers", "PROJECT_MEMBER"))).isFalse();
        assertThat(model.hasPolicy("p", "p", asList("alice", "citrus-confidential", "PROJECT_EDITOR"))).isTrue();
    }

    @Test
    public void test_remove_filtered_policy_with_multiple_fields() {
        repository.addPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"));
        repository.addPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_EDITOR"));
        repository.addPolicy("p", "p", asList("bob", "icij", "banana-papers", "PROJECT_MEMBER"));

        repository.removeFilteredPolicy("p", "p", 0, "alice", "icij", "banana-papers");
        Model model = new Model();
        model.addDef("p", "p", "sub, dom, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"))).isFalse();
        assertThat(model.hasPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_EDITOR"))).isFalse();
        assertThat(model.hasPolicy("p", "p", asList("bob", "icij", "banana-papers", "PROJECT_MEMBER"))).isTrue();
    }

    @Test
    public void test_remove_filtered_policy_with_empty_values() {
        repository.addPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"));
        repository.addPolicy("p", "p", asList("bob", "icij", "banana-papers", "PROJECT_MEMBER"));
        repository.addPolicy("p", "p", asList("alice", "icij", "citrus-confidential", "PROJECT_MEMBER"));

        repository.removeFilteredPolicy("p", "p", 0, "alice", "icij", "", "PROJECT_MEMBER");
        Model model = new Model();
        model.addDef("p", "p", "sub, dom, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"))).isFalse();
        assertThat(model.hasPolicy("p", "p", asList("alice", "icij", "citrus-confidential", "PROJECT_MEMBER"))).isFalse();
        assertThat(model.hasPolicy("p", "p", asList("bob", "icij", "banana-papers", "PROJECT_MEMBER"))).isTrue();
    }

    @Test
    public void test_save_policy() {
        Model model = new Model();
        model.addDef("p", "p", "sub, dom, obj, act");
        model.addDef("g", "g", "_, _, _");
        model.addPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"));
        model.addPolicy("g", "g", asList("alice", "icij::banana-papers", "PROJECT_ADMIN"));
        repository.savePolicy(model);

        Model newModel = new Model();
        newModel.addDef("p", "p", "sub, dom, obj, act");
        newModel.addDef("g", "g", "_, _, _");
        repository.loadPolicy(newModel);
        assertThat(newModel.hasPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"))).isTrue();
        assertThat(newModel.hasPolicy("g", "g", asList("alice", "icij::banana-papers", "PROJECT_ADMIN"))).isTrue();
    }

    @Test
    public void test_update_policy_with_non_existent_old_rule() {
        repository.addPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"));
        repository.updatePolicy("p", "p", asList("bob", "icij", "banana-papers", "PROJECT_MEMBER"), asList("bob", "icij", "banana-papers", "PROJECT_EDITOR"));
        Model model = new Model();
        model.addDef("p", "p", "sub, dom, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"))).isTrue();
        assertThat(model.hasPolicy("p", "p", asList("bob", "icij", "banana-papers", "PROJECT_EDITOR"))).isTrue();
    }

    @Test
    public void test_add_policies_batching() {
        List<List<String>> rules = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            rules.add(asList("user" + i, "domain", "data", "PROJECT_MEMBER"));
        }
        repository.addPolicies("p", "p", rules);
        Model model = new Model();
        model.addDef("p", "p", "sub, dom, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("user0", "domain", "data", "PROJECT_MEMBER"))).isTrue();
        assertThat(model.hasPolicy("p", "p", asList("user1499", "domain", "data", "PROJECT_MEMBER"))).isTrue();
    }

    @Test
    public void test_update_policy() {
        repository.addPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"));
        repository.updatePolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"), asList("alice", "icij", "banana-papers", "PROJECT_EDITOR"));
        Model model = new Model();
        model.addDef("p", "p", "sub, dom, obj, act");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"))).isFalse();
        assertThat(model.hasPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_EDITOR"))).isTrue();
    }

    @Test
    public void test_add_policy_with_long_rule() {
        repository.addPolicy("p", "p", asList("v0", "v1", "v2", "v3", "v4", "v5"));
        Model model = new Model();
        model.addDef("p", "p", "sub, obj, act, v3, v4, v5");
        repository.loadPolicy(model);
        assertThat(model.hasPolicy("p", "p", asList("v0", "v1", "v2", "v3", "v4", "v5"))).isTrue();
    }

    @Test
    public void test_loadFilteredPolicy_null_filter_loads_all_and_isFiltered_false() {
        repository.addPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"));
        repository.addPolicy("p", "p", asList("bob", "icij", "citrus-confidential", "PROJECT_EDITOR"));
        Model model = new Model();
        model.addDef("p", "p", "sub, dom, obj, act");
        repository.loadFilteredPolicy(model, null);
        assertThat(model.hasPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"))).isTrue();
        assertThat(model.hasPolicy("p", "p", asList("bob", "icij", "citrus-confidential", "PROJECT_EDITOR"))).isTrue();
        assertThat(repository.isFiltered()).isFalse();
    }

    @Test
    public void test_loadFilteredPolicy_with_valid_filter_loads_only_matching_and_isFiltered_true() {
        repository.addPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"));
        repository.addPolicy("p", "p", asList("bob", "icij", "citrus-confidential", "PROJECT_EDITOR"));
        Model model = new Model();
        model.addDef("p", "p", "sub, dom, obj, act");
        Filter filter = new Filter();
        filter.p = new String[]{"alice", null, null};
        repository.loadFilteredPolicy(model, filter);
        assertThat(model.hasPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"))).isTrue();
        assertThat(model.hasPolicy("p", "p", asList("bob", "icij", "citrus-confidential", "PROJECT_EDITOR"))).isFalse();
        assertThat(repository.isFiltered()).isTrue();
    }

    @Test
    public void test_loadFilteredPolicy_with_filter_matches_nothing() {
        repository.addPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"));
        Model model = new Model();
        model.addDef("p", "p", "sub, dom, obj, act");
        Filter filter = new Filter();
        filter.p = new String[]{"bob", null, null};
        repository.loadFilteredPolicy(model, filter);
        assertThat(model.getPolicy("p", "p").size()).isEqualTo(0);
        assertThat(repository.isFiltered()).isTrue();
    }

    @Test(expected = CasbinAdapterException.class)
    public void test_loadFilteredPolicy_with_invalid_filter_type_throws() {
        repository.loadFilteredPolicy(new Model(), "not_a_filter");
    }

    @Test
    public void test_loadFilteredPolicy_with_partial_filter() {
        repository.addPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"));
        repository.addPolicy("p", "p", asList("alice", "citrus-confidential", "PROJECT_EDITOR"));
        repository.addPolicy("p", "p", asList("bob", "icij", "banana-papers", "PROJECT_MEMBER"));
        Model model = new Model();
        model.addDef("p", "p", "sub, dom, obj, act");
        Filter filter = new Filter();
        filter.p = new String[]{"alice", null, null, null};
        repository.loadFilteredPolicy(model, filter);
        assertThat(model.hasPolicy("p", "p", asList("alice", "icij", "banana-papers", "PROJECT_MEMBER"))).isTrue();
        assertThat(model.hasPolicy("p", "p", asList("alice", "citrus-confidential", "PROJECT_EDITOR"))).isTrue();
        assertThat(model.hasPolicy("p", "p", asList("bob", "icij", "banana-papers", "PROJECT_MEMBER"))).isFalse();
        assertThat(repository.isFiltered()).isTrue();
    }

    @Test
    public void test_loadFilteredPolicy_with_g_section() {
        repository.addPolicy("g", "g", asList("alice", "icij::project1", "PROJECT_ADMIN"));
        repository.addPolicy("g", "g", asList("bob", "icij::project1", "PROJECT_MEMBER"));
        Model model = new Model();
        model.addDef("g", "g", "_, _, _");
        Filter filter = new Filter();
        filter.g = new String[]{"alice", null, null};
        repository.loadFilteredPolicy(model, filter);
        assertThat(model.hasPolicy("g", "g", asList("alice", "icij::project1", "PROJECT_ADMIN"))).isTrue();
        assertThat(model.hasPolicy("g", "g", asList("bob", "icij::project1", "PROJECT_MEMBER"))).isFalse();
        assertThat(repository.isFiltered()).isTrue();
    }

    @Test
    public void test_loadFilteredPolicy_with_g2_section_if_supported() {
        // Only run if Filter has g2 field
        try {
            Filter filter = new Filter();
            java.lang.reflect.Field g2Field = filter.getClass().getDeclaredField("g2");
            repository.addPolicy("g2", "g2", asList("INSTANCE_ADMIN", "PROJECT_ADMIN"));
            repository.addPolicy("g2", "g2", asList("PROJECT_ADMIN", "PROJECT_MEMBER"));
            Model model = new Model();
            model.addDef("g2", "g2", "_, _");
            g2Field.set(filter, new String[]{"INSTANCE_ADMIN", null});
            repository.loadFilteredPolicy(model, filter);
            assertThat(model.hasPolicy("g2", "g2", asList("INSTANCE_ADMIN", "PROJECT_ADMIN"))).isTrue();
            assertThat(model.hasPolicy("g2", "g2", asList("PROJECT_ADMIN", "PROJECT_MEMBER"))).isFalse();
            assertThat(repository.isFiltered()).isTrue();
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
        }
    }

}