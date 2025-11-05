package org.icij.datashare.user;
import org.casbin.jcasbin.main.Enforcer;
import org.casbin.jcasbin.model.Model;
import org.casbin.jcasbin.persist.Adapter;
import org.icij.datashare.text.Project;


public class UserPolicyVerifier {
    private static UserPolicyVerifier instance;
    private final Enforcer enforcer;
    private static final String DEFAULT_POLICY_FILE = "src/main/resources/casbin/model.conf";
    private static final boolean ENABLE_CASBIN_LOG = false;

    private UserPolicyVerifier(UserPolicyRepository repository) {
        Adapter adapter = new UserPolicyRepositoryAdapter(repository);
        Model model = new Model();
        model.loadModel(DEFAULT_POLICY_FILE);
        this.enforcer = new Enforcer(model, adapter, ENABLE_CASBIN_LOG);
    }

    public static synchronized UserPolicyVerifier getInstance(UserPolicyRepository repository) {
        if (instance == null) {
            instance = new UserPolicyVerifier(repository);
        }
        return instance;
    }

    public boolean enforce(UserPolicy userPolicy) {
        return this.enforcer.enforce(userPolicy);
    }
    public boolean enforce(User user, Project project, UserPolicyRepositoryAdapter.Permission act ) {
        return this.enforce(user.name, project.name, act.value());
    }

    public boolean enforce(String userName, String projectName, String permission) {
        return this.enforcer.enforce(userName, projectName, permission);
    }

}
