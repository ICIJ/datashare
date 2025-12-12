package org.icij.datashare.session;

import com.google.inject.Inject;
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

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;


public class UserPolicyVerifier {
    private final Enforcer enforcer;
    private static final String DEFAULT_POLICY_FILE = "casbin/model.conf";
    private static final boolean ENABLE_CASBIN_LOG = true;
    private final Repository repository;
    private final UserPolicyRepository userPolicyRepository;

    @Inject
    public UserPolicyVerifier(final UserPolicyRepository userPolicyRepository, final Repository repository) {
        this.userPolicyRepository = userPolicyRepository;
        this.repository = repository;

        Adapter adapter = new UserPolicyAdapter(this.userPolicyRepository);
        Model model = new Model();
        Path path = null;
        try {
            path = Paths.get(ClassLoader.getSystemResource(DEFAULT_POLICY_FILE).toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        model.loadModel(path.toString());
        this.enforcer = new Enforcer(model, adapter, ENABLE_CASBIN_LOG);
    }

    public boolean enforceAllRoles(UserPolicy userPolicy) {
        // Check if all roles in a policy are enforced by checking if any is not.
        return Arrays.stream(userPolicy.roles()).allMatch(role ->
                this.enforce(userPolicy.userId(), userPolicy.projectId(), role.name()));
    }

    public boolean enforce(String userName, String projectName, String act) {
        return this.enforcer.enforce(userName, projectName, act);
    }

    public Stream<UserPolicy> getUserPolicies() {
        return this.userPolicyRepository.getAllPolicies();
    }

    /**
     * Retrieve the user policy for a given user and project.
     */
    public Optional<UserPolicy> getUserPolicyByProject(String userId, String projectId) {
        return Optional.ofNullable(this.userPolicyRepository.get(userId, projectId));
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

    private void userAndProjectExist(String userId, String projectId) throws RecordNotFoundException {
        if (this.repository.getUser(userId) == null) {
            throw new RecordNotFoundException(User.class, userId);
        }
        if (this.repository.getProject(projectId) == null) {
            throw new RecordNotFoundException(Project.class, projectId);
        }
    }



}
