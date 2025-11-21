package org.icij.datashare.user;

import org.casbin.jcasbin.main.Enforcer;
import org.casbin.jcasbin.model.Model;
import org.casbin.jcasbin.persist.Adapter;
import org.icij.datashare.text.Project;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;


public class UserPolicyVerifier {
    private static UserPolicyVerifier instance;
    private final Enforcer enforcer;
    private static final String DEFAULT_POLICY_FILE = "casbin/model.conf";
    private static final boolean ENABLE_CASBIN_LOG = true;

    private UserPolicyVerifier(UserRepository repository) throws URISyntaxException {
        Adapter adapter = new UserPolicyRepositoryAdapter(repository);
        Model model = new Model();
        Path path = Paths.get(ClassLoader.getSystemResource(DEFAULT_POLICY_FILE).toURI());
        model.loadModel(path.toString());
        this.enforcer = new Enforcer(model, adapter, ENABLE_CASBIN_LOG);
    }

    public static synchronized UserPolicyVerifier getInstance(UserRepository repository) throws URISyntaxException {
        if (instance == null) {
            instance = new UserPolicyVerifier(repository);
        }
        return instance;
    }

    public boolean enforceAllRoles(UserPolicy userPolicy) {
        // Check if all roles in a policy are enforced by checking if any is not.
        return Arrays.stream(userPolicy.roles()).allMatch(role ->
            this.enforce(userPolicy.userId(), userPolicy.projectId(), role.name()));
    }

    public boolean enforce(User user, Project project, Role act) {
        return this.enforce(user.getId(), project.getName(), act.name());
    }

    public boolean enforce(String userName, String projectName, String act) {
        return this.enforcer.enforce(userName, projectName, act);
    }
}
