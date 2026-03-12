package org.icij.datashare.session;

import com.google.inject.Inject;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.policies.Role;
import org.icij.datashare.text.Project;

public class PostLoginEnroller {
    private final Authorizer authorizer;

    @Inject
    public PostLoginEnroller(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    public void enroll(DatashareUser user) {
        for (Project project : user.getApplicationProjects()) {
            if (!authorizer.can(user.id, Domain.DEFAULT, project.getId(), Role.PROJECT_VISITOR)) {
                authorizer.addRoleForUserInProject(user, Role.PROJECT_MEMBER, Domain.DEFAULT, project);
            }
        }
    }
}
