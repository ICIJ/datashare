package org.icij.datashare.session;

import com.google.inject.Inject;
import org.apache.commons.io.IOUtils;
import org.casbin.jcasbin.main.Enforcer;
import org.casbin.jcasbin.model.Model;
import org.casbin.jcasbin.persist.Adapter;
import org.icij.datashare.RecordNotFoundException;
import org.icij.datashare.Repository;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserPolicy;
import org.icij.datashare.user.UserPolicyRepository;

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
    private final UserPolicyRepository userPolicyRepository;
    private final Repository repository;
    private final UsersWritable users;

    @Inject
    public UserPolicyVerifier(final UserPolicyRepository userPolicyRepository, final Repository repository, final UsersWritable users) throws IOException {
        this.userPolicyRepository = userPolicyRepository;
        this.repository = repository;
        this.users = users;

        Adapter adapter = new UserPolicyAdapter(this.userPolicyRepository);
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
        // Check if all roles in a policy are enforced by checking if any is not.
        return Arrays.stream(userPolicy.roles()).allMatch(role ->
                this.enforce(userPolicy.userId(), userPolicy.projectId(), role.name()));
    }

    public boolean enforce(String userName, String projectName, String act) {
        return this.enforcer.enforce(userName, projectName, act);
    }

    public Stream<UserPolicy> getAllUserPolicies() {
        return this.userPolicyRepository.getAllPolicies();
    }

    /**
     * Retrieve the user policies for a given user or project.
     */
    public Stream<UserPolicy> getUserPolicies(String userId, String projectId) {
        if (userId != null && projectId != null) {
            return Stream.ofNullable(this.userPolicyRepository.get(userId, projectId));
        }
        if (userId != null) {
            return this.userPolicyRepository.getByUserId(userId);
        }
        if (projectId != null) {
            return this.userPolicyRepository.getByProjectId(projectId);
        }
        return this.userPolicyRepository.getAllPolicies();
    }

    public UserPolicy getUserPolicy(String userId, String projectId) {
        return this.userPolicyRepository.get(userId, projectId);
    }

    /**
     * Save a user policy for a given user, project, and roles.
     */
    public boolean saveUserPolicy(String userId, String projectId, Role[] roles) throws RecordNotFoundException {
        userAndProjectExist(userId, projectId);
        UserPolicy userPolicy = UserPolicy.of(userId, projectId, roles);
        return this.userPolicyRepository.save(userPolicy);
    }

    /**
     * Delete a user policy for a given user and project.
     */
    public boolean deleteUserPolicy(String userId, String projectId) throws RecordNotFoundException {
        userAndProjectExist(userId, projectId);
        return this.userPolicyRepository.delete(userId, projectId);
    }

    /**
     * Grant admin role to a user for a project if no admin exists for that project.
     */
    public boolean grantAdminIfNoneExists(String userId, String projectId) throws RecordNotFoundException {
        if (!hasAdmin(projectId)) {
            return saveUserPolicy(userId, projectId, new Role[]{Role.ADMIN});
        }
        return false;
    }

    /**
     * Check if a project has at least one user who has an admin policy.
     */
    public boolean hasAdmin(String projectId) {
        return userPolicyRepository.getByProjectId(projectId).anyMatch(UserPolicy::isAdmin);
    }

    private void userAndProjectExist(String userId, String projectId) throws RecordNotFoundException {
        if (this.users.find(userId).equals(User.nullUser())) {
            throw new RecordNotFoundException(User.class, userId);
        }
        if (this.repository.getProject(projectId) == null) {
            throw new RecordNotFoundException(Project.class, projectId);
        }
    }
}
