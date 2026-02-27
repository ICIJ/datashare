package org.icij.datashare.policies;

import org.apache.commons.io.IOUtils;
import org.casbin.jcasbin.main.Enforcer;
import org.casbin.jcasbin.model.Model;
import org.casbin.jcasbin.rbac.DomainManager;
import org.casbin.jcasbin.util.BuiltInFunctions;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Authorizer {

    private final static String SEPARATOR = "::";
    private static final String DEFAULT_POLICY_FILE = "casbin/model.conf";
    private final Enforcer enforcer;

    public Authorizer(CasbinRuleAdapter adapter) {
        this(adapter, true);
    }

    public Authorizer(CasbinRuleAdapter adapter, boolean enableAutoSave) {
        Model model = new Model();
        try {
            String modelConf = loadCasbinConf(DEFAULT_POLICY_FILE);
            model.loadModelFromText(modelConf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        enforcer = new Enforcer(model, adapter);
        enforcer.setRoleManager(new DomainManager(10, null, BuiltInFunctions::allMatch));
        enforcer.enableAutoSave(enableAutoSave);
        enforcer.loadPolicy();
        initializeRoleHierarchy();
    }

    private static String domainSepProject(Domain domain, String project) {
        return String.format("%s%s%s", domain.id(), SEPARATOR, project);
    }

    // addLink adds the inheritance link between role: name1 and role: name2.
    // aka role: name1 inherits role: name2. domain is a prefix to the roles.
    private void addRoleHierarchy(Role role1, Role role2) {
        enforcer.getRoleManager().addLink(role1.name(), role2.name());
        enforcer.addNamedGroupingPolicy("g2", role1.name(), role2.name());
    }

    private void initializeRoleHierarchy() {
        addRoleHierarchy(Role.DOMAIN_ADMIN, Role.INSTANCE_ADMIN);
        addRoleHierarchy(Role.PROJECT_MEMBER, Role.DOMAIN_ADMIN);
        addRoleHierarchy(Role.PROJECT_ADMIN, Role.PROJECT_EDITOR);
        addRoleHierarchy(Role.PROJECT_EDITOR, Role.PROJECT_MEMBER);
        addRoleHierarchy(Role.PROJECT_MEMBER, Role.PROJECT_VISITOR);

        for (Role role : Role.values()) {
            enforcer.addPolicy(role.name(), "*", "*", role.name());
        }
        enforcer.buildRoleLinks();
    }

    private CasbinRule streamToCasbinRule(String ptype, List<String> rule) {
        return CasbinRule.fromArray(Stream.concat(Stream.of(ptype), rule.stream()).collect(Collectors.toList()));
    }

    protected Enforcer enforcer() {
        return enforcer;
    }

    private String loadCasbinConf(String modelPath) throws IOException {
        // Load Casbin model from classpath in a way that works both from IDE and packaged JARs
        String path = Optional.ofNullable(modelPath).orElse(DEFAULT_POLICY_FILE);
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(path);
        if (inputStream != null) {
            return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
        } else {
            throw new FileNotFoundException(String.format("Unable to find : %s", path));
        }
    }

    public boolean can(String userId, Domain domain, String project, Role action) {
        return enforcer.enforce(userId, domain.id(), project, action.name());
    }

    public boolean can(String userId, Domain domain, String project, String action) {
        return enforcer.enforce(userId, domain.id(), project, action);
    }

    /*
     *   Instance
     */
    public boolean addRoleForUserInInstance(String user, Role role) {
        return enforcer.addGroupingPolicy(user, role.name(), domainSepProject(Domain.of("*"), "*"));
    }

    public boolean deleteRoleForUserInInstance(String user, Role role) {
        return enforcer.removeGroupingPolicy(user, role.name(), domainSepProject(Domain.of("*"), "*"));
    }

    /*
     *   Domain
     */
    public boolean addRoleForUserInDomain(String user, Role role, Domain domain) {
        return enforcer.addGroupingPolicy(user, role.name(), domainSepProject(domain, "*"));
    }

    public boolean deleteRoleForUserInDomain(String user, Role role, Domain domain) {
        return enforcer.removeGroupingPolicy(user, role.name(), domainSepProject(domain, "*"));
    }

    public boolean updateRoleForUserInDomain(String user, Role role, Domain domain) {
        // Remove existing role if present, then ensure the new role is added
        deleteRoleForUserInDomain(user, role, domain);
        return addRoleForUserInDomain(user, role, domain);
    }

    public List<String> getRolesForUserInDomain(String user, Domain domain) {
        return enforcer.getRolesForUserInDomain(user, domainSepProject(domain, "*"));
    }

    /*
     *   Project
     */
    public boolean addProjectAdmin(String user, Domain domain, String project) {
        return addRoleForUserInProject(user, Role.PROJECT_ADMIN, domain, project);
    }

    public boolean addRoleForUserInProject(String user, Role role, Domain domain, String project) {
        return enforcer.addGroupingPolicy(user, role.name(), domainSepProject(domain, project));
    }

    public boolean deleteRoleForUserInProject(String user, Role role, Domain domain, String project) {
        return enforcer.removeGroupingPolicy(user, role.name(), domainSepProject(domain, project));
    }

    public boolean updateRoleForUserInProject(String user, Role role, Domain domain, String project) {
        // Remove existing role if present, then ensure the new role is added
        deleteRoleForUserInProject(user, role, domain, project);
        return addRoleForUserInProject(user, role, domain, project);
    }

    public List<String> getRolesForUserInProject(String user, Domain domain, String project) {
        return enforcer.getRolesForUserInDomain(user, domainSepProject(domain, project));
    }

    public List<CasbinRule> getPermissionsForUserInDomain(String user, Domain domain) {
        return enforcer.getImplicitPermissionsForUser(user, domainSepProject(domain, "*")).stream()
                .map(rule -> streamToCasbinRule("p", rule))
                .collect(Collectors.toList());
    }


    private List<CasbinRule> getFilteredPermissions(String user, Domain domain, String project) {
        // Get all grouping policies for the user that provide instance-wide access
        // This includes: *::* (instance), domain::* (domain), and domain::project (project level)
        List<List<String>> list;
        if (user != null) {
            list = enforcer.getFilteredGroupingPolicy(0, user);
        } else {
            list = enforcer.getGroupingPolicy();
        }
        String domainId = domain != null ? domain.id() : "*";
        String projectId = project != null ? project : "*";
        String casbinDomain = domainSepProject(Domain.of(domainId), projectId);

        Predicate<List<String>> listPredicate = rule -> {
            if (rule.size() < 3) return false;
            String ruleDomain = rule.get(2);
            if (domain == null && project == null) {
                return true;
            }
            if (domain != null && project == null) {
                return ruleDomain.equals("*::*") || ruleDomain.equals(domainId + "::*") || ruleDomain.startsWith(domainId + "::");
            }
            // When both domain and project are specified, only include exact matches for that domain-project scope.
            return ruleDomain.equals("*::*") || ruleDomain.equals(domainId + "::*") || ruleDomain.equals(casbinDomain);
        };


        return list.stream()
                .filter(listPredicate)
                .map(rule -> streamToCasbinRule("g", rule))
                .collect(Collectors.toList());
    }

    public List<CasbinRule> getGroupPermissions() {
        return getGroupPermissions(null, null, null);
    }

    public List<CasbinRule> getGroupPermissions(String user) {
        return getFilteredPermissions(user, null, null);
    }

    public List<CasbinRule> getGroupPermissions(String user, Domain domain) {
        return getFilteredPermissions(user, domain, null);
    }

    public List<CasbinRule> getGroupPermissions(Domain domain) {
        return getFilteredPermissions(null, domain, null);
    }

    public List<CasbinRule> getGroupPermissions(Domain domain, String project) {
        return getFilteredPermissions(null, domain, project);
    }

    public List<CasbinRule> getGroupPermissions(String user, Domain domain, String project) {
        return getFilteredPermissions(user, domain, project);
    }
}
