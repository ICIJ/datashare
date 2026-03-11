package org.icij.datashare.session;

import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.policies.Role;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class PostLoginEnrollerTest {

    private DatashareUser userWithProjects(String... projectNames) {
        return new DatashareUser(new HashMap<>() {{
            put("uid", "user1");
            put("groups_by_applications", new HashMap<>() {{
                put("datashare", List.of(projectNames));
            }});
        }});
    }

    @Test
    public void test_enroll_adds_member_when_user_has_no_existing_role() {
        Authorizer authorizer = mock(Authorizer.class);
        PostLoginEnroller enroller = new PostLoginEnroller(authorizer);
        DatashareUser user = userWithProjects("project-a");

        enroller.enroll(user);

        verify(authorizer).addRoleForUserInProject(any(User.class), eq(Role.PROJECT_MEMBER), eq(Domain.DEFAULT), eq(new Project("project-a")));
    }

    @Test
    public void test_enroll_skips_when_user_already_has_role() {
        Authorizer authorizer = mock(Authorizer.class);
        when(authorizer.can(any(), eq(Domain.DEFAULT), eq("project-a"), eq(Role.PROJECT_VISITOR))).thenReturn(true);
        PostLoginEnroller enroller = new PostLoginEnroller(authorizer);
        DatashareUser user = userWithProjects("project-a");

        enroller.enroll(user);

        verify(authorizer, never()).addRoleForUserInProject(any(), any(), any(), eq(new Project("project-a")));
    }

    @Test
    public void test_enroll_does_nothing_when_user_has_no_projects() {
        Authorizer authorizer = mock(Authorizer.class);
        PostLoginEnroller enroller = new PostLoginEnroller(authorizer);
        DatashareUser user = new DatashareUser(new HashMap<>() {{
            put("uid", "user1");
        }});

        enroller.enroll(user);

        verify(authorizer, never()).addRoleForUserInProject(any(), any(), any(), any());
    }

    @Test
    public void test_enroll_handles_multiple_projects_independently() {
        Authorizer authorizer = mock(Authorizer.class);
        PostLoginEnroller enroller = new PostLoginEnroller(authorizer);
        DatashareUser user = userWithProjects("project-a", "project-b");

        enroller.enroll(user);

        verify(authorizer).addRoleForUserInProject(any(User.class), eq(Role.PROJECT_MEMBER), eq(Domain.DEFAULT), eq(new Project("project-a")));
        verify(authorizer).addRoleForUserInProject(any(User.class), eq(Role.PROJECT_MEMBER), eq(Domain.DEFAULT), eq(new Project("project-b")));
    }


    @Test
    public void test_enroll_skipped_when_user_already_has_existing_role() {
        Authorizer authorizer = mock(Authorizer.class);
        when(authorizer.can(any(), eq(Domain.DEFAULT), eq("project-a"), eq(Role.PROJECT_VISITOR))).thenReturn(true);
        PostLoginEnroller enroller = new PostLoginEnroller(authorizer);
        DatashareUser user = userWithProjects("project-a", "project-b");

        enroller.enroll(user);

        verify(authorizer, never()).addRoleForUserInProject(any(), any(), any(), eq(new Project("project-a")));
        verify(authorizer).addRoleForUserInProject(any(User.class), eq(Role.PROJECT_MEMBER), eq(Domain.DEFAULT), eq(new Project("project-b")));
    }

}
