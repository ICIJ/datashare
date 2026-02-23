package org.icij.datashare.session;

import org.apache.commons.io.IOUtils;
import org.casbin.jcasbin.main.Enforcer;
import org.casbin.jcasbin.model.Model;
import org.casbin.jcasbin.rbac.DomainManager;
import org.casbin.jcasbin.util.BuiltInFunctions;
import org.icij.datashare.policies.CasbinRule;
import org.icij.datashare.policies.CasbinRuleAdapter;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.policies.Role;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class Authorizer {

    private final static String SEPARATOR = "::";
    private static final String DEFAULT_POLICY_FILE = "casbin/model.conf";
    private final Enforcer enforcer;

    public Authorizer(CasbinRuleAdapter adapter) {
        Model model = new Model();
        try {
            model.loadModelFromText(loadCasbinConf(DEFAULT_POLICY_FILE));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        enforcer = new Enforcer(model, adapter);
        enforcer.setRoleManager(new DomainManager(10, null, BuiltInFunctions::allMatch));
        enforcer.enableAutoSave(true);
        enforcer.loadPolicy();
        initializeRoleHierarchy();
    }

    private static String DomainSepProject(Domain domain, String project) {
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
        addRoleHierarchy(Role.PROJECT_EDITOR, Role.PROJECT_ADMIN);
        addRoleHierarchy(Role.PROJECT_MEMBER, Role.PROJECT_EDITOR);
        addRoleHierarchy(Role.PROJECT_VISITOR, Role.PROJECT_MEMBER);

        for (Role role : Role.values()) {
            enforcer.addPolicy(role.name(), "*", "*", role.name());
        }
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
        return enforcer.addRoleForUserInDomain(user, role.name(), DomainSepProject(Domain.of("*"), "*"));
    }

    /*
     *   Domain
     */
    public boolean addRoleForUserInDomain(String user, Role role, Domain domain) {
        return enforcer.addRoleForUserInDomain(user, role.name(), DomainSepProject(domain, "*"));
    }

    public boolean deleteRoleForUserInDomain(String user, Role role, Domain domain) {
        return enforcer.deleteRoleForUserInDomain(user, role.name(), DomainSepProject(domain, "*"));
    }

    public boolean updateRoleForUserInDomain(String user, Role role, Domain domain) {
        return deleteRoleForUserInDomain(user, role, domain) && addRoleForUserInDomain(user, role, domain);
    }

    public List<String> getRolesForUserInDomain(String user, Domain domain) {
        return enforcer.getRolesForUserInDomain(user, domain.id());
    }

    /*
     *   Project
     */
    public boolean addProjectAdmin(String user, Domain domain, String project) {
        return addRoleForUserInProject(user, Role.PROJECT_ADMIN, domain, project);
    }

    public boolean addRoleForUserInProject(String user, Role role, Domain domain, String project) {
        return enforcer.addRoleForUserInDomain(user, role.name(), DomainSepProject(domain, project));
    }

    public boolean deleteRoleForUserInProject(String user, Role role, Domain domain, String project) {
        return enforcer.deleteRoleForUserInDomain(user, role.name(), DomainSepProject(domain, project));
    }

    public boolean updateRoleForUserInProject(String user, Role role, Domain domain, String project) {
        return deleteRoleForUserInProject(user, role, domain, project) && addRoleForUserInProject(user, role, domain, project);
    }

    public List<String> getRolesForUserInProject(String user, Domain domain, String project) {
        return enforcer.getRolesForUserInDomain(user, DomainSepProject(domain, project));
    }


    public List<CasbinRule> getPermissionsForUserInDomain(String user, Domain domain) {
        return enforcer.getPermissionsForUserInDomain(user, DomainSepProject(domain, "*")).stream()
                .map(rule -> new CasbinRule(rule.get(0), rule.get(1), rule.get(2), rule.get(3), rule.get(4), rule.get(5), rule.get(6)))
                .collect(Collectors.toList());
    }


}
