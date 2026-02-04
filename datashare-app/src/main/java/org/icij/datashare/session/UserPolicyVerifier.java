package org.icij.datashare.session;

import com.google.inject.Inject;
import org.apache.commons.io.IOUtils;
import org.casbin.jcasbin.main.Enforcer;
import org.casbin.jcasbin.model.Model;
import org.casbin.jcasbin.persist.Adapter;
import org.icij.datashare.RecordNotFoundException;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.CasbinRuleRepository;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserPolicy;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;


public class UserPolicyVerifier {
    private final Enforcer enforcer;
    private static final String DEFAULT_POLICY_FILE = "casbin/model.conf";
    private static final boolean ENABLE_CASBIN_LOG = false;
    private final CasbinRuleRepository casbinRuleRepository;
    private final UsersWritable users;

    @Inject
    public UserPolicyVerifier(final CasbinRuleRepository casbinRuleRepository, final UsersWritable users) throws IOException {
        this.casbinRuleRepository = casbinRuleRepository;
        this.users = users;

        Adapter adapter = new UserPolicyAdapter(this.casbinRuleRepository);
        Model model = new Model();
        model.loadModelFromText(loadCasbinConf());

        this.enforcer = new Enforcer(model, adapter, ENABLE_CASBIN_LOG);
    }

    private String loadCasbinConf() throws IOException {
        // Load Casbin model from classpath in a way that works both from IDE and packaged JARs
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(DEFAULT_POLICY_FILE);
        if (inputStream != null) {
            return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
        } else {
            throw new FileNotFoundException(String.format("Unable to find : %s", DEFAULT_POLICY_FILE));
        }
    }

    public boolean enforceAllRoles(UserPolicy userPolicy) {
        this.enforcer.getPermissionsForUserInDomain(userPolicy.userId(), "icij");
        // Check if all roles in a policy are enforced by checking if any is not.
        return Arrays.stream(userPolicy.roles()).allMatch(role ->
                this.enforce(userPolicy.userId(), userPolicy.projectId(), role.name()));
    }

    public boolean enforce(String userName, String projectName, String act) {
        return this.enforcer.enforce(userName, projectName, act);
    }

    public Stream<UserPolicy> getAllUserPolicies() {
        return this.casbinRuleRepository.getAllPolicies();
    }

    /**
     * Retrieve the user policies for a given user or project.
     */
    public Stream<UserPolicy> getUserPolicies(String userId, String projectId) {
        if (userId != null && projectId != null) {
            return Stream.ofNullable(this.casbinRuleRepository.get(userId, projectId));
        }
        if (userId != null) {
            return this.casbinRuleRepository.getByUserId(userId);
        }
        if (projectId != null) {
            return this.casbinRuleRepository.getByProjectId(projectId);
        }
        return this.casbinRuleRepository.getAllPolicies();
    }

    public UserPolicy getUserPolicy(String userId, String projectId) {
        return this.casbinRuleRepository.get(userId, projectId);
    }

    /**
     * Save a user policy for a given user, project, and roles.
     */
    public boolean saveUserPolicy(String userId, String projectId, Role[] roles) throws RecordNotFoundException {
        userAndProjectExist(userId, projectId);
        this.casbinRuleRepository.save(UserPolicy.of(userId, projectId, roles));
        return true;
    }

    /**
     * Delete a user policy for a given user and project.
     */
    public void deleteUserPolicy(String userId, String projectId) throws RecordNotFoundException {
        userAndProjectExist(userId, projectId);
        this.casbinRuleRepository.delete(userId, projectId);
    }

    private void userAndProjectExist(String userId, String projectId) throws RecordNotFoundException {
        DatashareUser user = (DatashareUser) this.users.find(userId);
        if (user == null || user.equals(User.nullUser())) {
            throw new RecordNotFoundException(User.class, userId);
        }
        if (!user.isGranted(projectId)) {
            throw new RecordNotFoundException(Project.class, projectId);
        }
    }
}
