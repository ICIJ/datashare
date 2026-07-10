package org.icij.datashare.web;

import net.codestory.http.filters.basic.BasicAuthFilter;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.UserEvent;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.CasbinRule;
import org.icij.datashare.policies.CasbinRuleAdapter;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.policies.Policy;
import org.icij.datashare.policies.PolicyAnnotation;
import org.icij.datashare.policies.Role;
import org.icij.datashare.project.admin.ProjectAdminService;
import org.icij.datashare.project.admin.ProjectGranted;
import org.icij.datashare.project.admin.ProjectNotFoundException;
import org.icij.datashare.project.admin.ProjectRevoked;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.icij.datashare.user.admin.UserAdminService;
import org.icij.datashare.user.admin.UserCreated;
import org.icij.datashare.user.admin.UserExistsException;
import org.icij.datashare.user.admin.UserFilter;
import org.icij.datashare.user.admin.UserNotFoundException;
import org.icij.datashare.user.admin.ValidationException;
import org.icij.datashare.web.WebResponse;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.icij.datashare.UserEvent.Type.DOCUMENT;
import static org.icij.datashare.session.DatashareUser.singleUser;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.localUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class UserResourceTest extends AbstractProdWebServerTest {
    @Mock JooqRepository jooqRepository;
    @Mock CasbinRuleAdapter casbinRuleAdapter;
    @Mock UserAdminService userAdminService;
    @Mock ProjectAdminService projectAdminService;
    Authorizer authorizer;
    PropertiesProvider propertiesProvider = new PropertiesProvider();

    @Before
    public void setUp() throws IOException {
        initMocks(this);
        authorizer = new Authorizer(casbinRuleAdapter);
        authorizer.addRoleForUserInDomain(User.local(), Role.PROJECT_ADMIN, Domain.DEFAULT);
        PolicyAnnotation policyAnnotation = new PolicyAnnotation(authorizer);
        configure(routes -> routes
                .registerAroundAnnotation(Policy.class, policyAnnotation)
                .add(new UserResource(jooqRepository, authorizer, userAdminService, projectAdminService))
                .filter(new LocalUserFilter(new PropertiesProvider(), jooqRepository)));
    }

    @Test
    public void test_user_information() {
        configure(routes -> routes.add(new UserResource(jooqRepository, authorizer, userAdminService, projectAdminService)).
                        filter(new BasicAuthFilter("/", "icij", singleUser("pierre"))));

        get("/api/users/me")
                .withPreemptiveAuthentication("pierre", "pass")
                .should()
                    .respond(200)
                    .contain("\"uid\":\"pierre\"")
                    .contain("\"pierre-datashare\"");
    }

    @Test
    public void test_user_information_with_local_user_filter() {
        Project foo = new Project("foo");
        when(jooqRepository.getProjects()).thenReturn(singletonList(foo));

        configure(routes -> {
            LocalUserFilter localUserFilter = new LocalUserFilter(propertiesProvider, jooqRepository);
            routes
                    .filter(localUserFilter)
                    .add(new UserResource(jooqRepository, authorizer, userAdminService, projectAdminService));
        });

        get("/api/users/me")
                .should()
                    .respond(200)
                    .contain("\"uid\":\"local\"")
                    .contain("\"foo\"")
                    .contain("\"local-datashare\"");
    }

    @Test
    public void test_get_user_history() {
        UserEvent userEvent = new UserEvent(User.local(), DOCUMENT, "doc_name", URI.create("doc_uri"));
        when(jooqRepository.getUserHistory(User.local(), DOCUMENT, 0, 10, "modification_date",true)).thenReturn(singletonList(userEvent));
        when(jooqRepository.getUserHistorySize(User.local(), DOCUMENT)).thenReturn(1);

        get("/api/users/me/history?type=document&from=0&size=10&sort=modification_date&desc=true").should().contain(userEvent.uri.toString()).contain(User.local().id)
                .contain("\"total\":1").respond(200);
    }
    @Test
    public void test_get_user_history_with_default_sort_and_order() {
        UserEvent userEvent = new UserEvent(User.local(), DOCUMENT, "doc_name", URI.create("doc_uri"));
        when(jooqRepository.getUserHistory(User.local(), DOCUMENT, 0, 10, "modification_date",true)).thenReturn(singletonList(userEvent));
        when(jooqRepository.getUserHistorySize(User.local(), DOCUMENT)).thenReturn(1);

        get("/api/users/me/history?type=document&from=0&size=10").should().contain(userEvent.uri.toString()).contain(User.local().id)
                .contain("\"total\":1").respond(200);
        get("/api/users/me/history?type=document&from=0&size=10&sort=").should().contain(userEvent.uri.toString()).contain(User.local().id)
                .contain("\"total\":1").respond(200);
        get("/api/users/me/history?type=document&from=0&size=10&desc=").should().contain(userEvent.uri.toString()).contain(User.local().id)
                .contain("\"total\":1").respond(200);
    }
    @Test
    public void test_get_user_history_with_sort_field() {
        UserEvent userEvent = new UserEvent(User.local(), DOCUMENT, "doc_name", URI.create("doc_uri"));
        when(jooqRepository.getUserHistory(User.local(), DOCUMENT, 0, 10, "name",true)).thenReturn(singletonList(userEvent));
        when(jooqRepository.getUserHistorySize(User.local(), DOCUMENT)).thenReturn(1);

        get("/api/users/me/history?type=document&from=0&size=10&sort=name").should().contain(userEvent.uri.toString()).contain(User.local().id)
                .contain("\"total\":1").respond(200);
    }
    @Test
    public void test_get_user_history_with_sort_and_order() {
        UserEvent userEvent = new UserEvent(User.local(), DOCUMENT, "doc_name", URI.create("doc_uri"));
        when(jooqRepository.getUserHistory(User.local(), DOCUMENT, 0, 10, "uri",false)).thenReturn(singletonList(userEvent));
        when(jooqRepository.getUserHistorySize(User.local(), DOCUMENT)).thenReturn(1);

        get("/api/users/me/history?type=document&from=0&size=10&sort=uri&desc=false").should().contain(userEvent.uri.toString()).contain(User.local().id)
                .contain("\"total\":1").respond(200);
    }

    @Test
    public void test_get_user_history_with_invalid_sort(){
        when(jooqRepository.getUserHistory(User.local(), DOCUMENT, 0, 10, "modificationDate",true)).thenThrow(new IllegalArgumentException("Invalid sort attribute : modificationDate"));
        when(jooqRepository.getUserHistorySize(User.local(), DOCUMENT)).thenReturn(1);
        get("/api/users/me/history?type=document&from=0&size=10&sort=modificationDate").should().respond(400);
    }
    @Test
    public void test_get_user_history_with_default_desc_order() {
        UserEvent userEvent = new UserEvent(User.local(), DOCUMENT, "doc_name", URI.create("doc_uri"));
        when(jooqRepository.getUserHistory(User.local(), DOCUMENT, 0, 10, "modification_date",true)).thenReturn(singletonList(userEvent));
        when(jooqRepository.getUserHistorySize(User.local(), DOCUMENT)).thenReturn(1);

        get("/api/users/me/history?type=document&from=0&size=10").should().contain(userEvent.uri.toString()).contain(User.local().id)
                .contain("\"pagination\":{\"count\":1,\"from\":0,\"size\":10,\"total\":1}")
                .respond(200);
        get("/api/users/me/history?type=document&from=0&size=10&desc=TOTO").should().contain(userEvent.uri.toString()).contain(User.local().id)
                .contain("\"pagination\":{\"count\":1,\"from\":0,\"size\":10,\"total\":1}")
                .respond(200);
        get("/api/users/me/history?type=document&from=0&size=10&desc=true").should().contain(userEvent.uri.toString()).contain(User.local().id)
                .contain("\"pagination\":{\"count\":1,\"from\":0,\"size\":10,\"total\":1}")
                .respond(200);
    }
    @Test
    public void test_get_user_history_with__false_desc_order() {
        UserEvent userEvent = new UserEvent(User.local(), DOCUMENT, "doc_name", URI.create("doc_uri"));
        when(jooqRepository.getUserHistory(User.local(), DOCUMENT, 0, 10, "modification_date",false)).thenReturn(singletonList(userEvent));
        when(jooqRepository.getUserHistorySize(User.local(), DOCUMENT)).thenReturn(1);

        get("/api/users/me/history?type=document&from=0&size=10&desc=false").should().contain(userEvent.uri.toString()).contain(User.local().id)
                .contain("\"total\":1").respond(200);
        get("/api/users/me/history?type=document&from=0&size=10&desc=FALSE").should().contain(userEvent.uri.toString()).contain(User.local().id)
                .contain("\"total\":1").respond(200);
    }

    @Test
    public void test_get_user_history_without_project_filter() {
        UserEvent userEvent = new UserEvent(User.local(), DOCUMENT, "doc_name", URI.create("doc_uri"));
        when(jooqRepository.getUserHistory(User.local(), DOCUMENT, 0, 10, "modification_date",true)).thenReturn(singletonList(userEvent));
        when(jooqRepository.getUserHistorySize(User.local(), DOCUMENT)).thenReturn(1);

        get("/api/users/me/history?type=document&from=0&size=10&projects=").should().contain(userEvent.uri.toString()).contain(User.local().id)
                .contain("\"total\":1").respond(200);
        get("/api/users/me/history?type=document&from=0&size=10").should().contain(userEvent.uri.toString()).contain(User.local().id)
                .contain("\"total\":1").respond(200);
    }

    @Test
    public void test_get_user_history_with_one_project_filter() {
        UserEvent userEvent = new UserEvent(User.local(), DOCUMENT, "doc_name", URI.create("doc_uri"));
        when(jooqRepository.getUserHistory(User.local(), DOCUMENT, 0, 10, "modification_date",true, "toto")).thenReturn(singletonList(userEvent));
        when(jooqRepository.getUserHistorySize(User.local(), DOCUMENT, "toto")).thenReturn(1);
        get("/api/users/me/history?type=document&from=0&size=10&projects=toto").should().contain(userEvent.uri.toString()).contain(User.local().id)
                .contain("\"total\":1").respond(200);
    }

    @Test
    public void test_get_user_history_with_two_projects_filter() {
        UserEvent userEvent = new UserEvent(User.local(), DOCUMENT, "doc_name", URI.create("doc_uri"));
        when(jooqRepository.getUserHistory(User.local(), DOCUMENT, 0, 10, "modification_date",true, "toto","titi")).thenReturn(singletonList(userEvent));
        when(jooqRepository.getUserHistorySize(User.local(), DOCUMENT, "toto","titi")).thenReturn(1);
        get("/api/users/me/history?type=document&from=0&size=10&projects=toto,titi").should().contain(userEvent.uri.toString()).contain(User.local().id)
                .contain("\"total\":1").respond(200);
    }

    @Test
    public void test_put_user_new_event_to_history() throws URISyntaxException {
        when(jooqRepository.addToUserHistory(eq(singletonList(project("prj"))), any(UserEvent.class))).thenReturn(true);
        put("/api/users/me/history", "{\"type\": \"SEARCH\", \"name\": \"TOTOTOTO AND bar\", \"uri\": \"search_uri\"}").should().respond(200);
    }
    @Test
    public void test_put_user_existing_event_to_history() {
        when(jooqRepository.renameSavedSearch(User.local(),12,"test")).thenReturn(true);
        put("/api/users/me/history", "{\"type\": \"SEARCH\", \"name\": \"test\", \"eventId\":12}").should().respond(200);
        put("/api/users/me/history", "{\"type\": \"SEARCH\", \"name\": \"test\", \"eventId\":14}").should().respond(400);
    }
    @Test
    public void test_delete_user_history_by_type() {
        when(jooqRepository.deleteUserHistory(User.local(), DOCUMENT)).thenReturn(true).thenReturn(false);

        delete("/api/users/me/history?type=search").should().respond(204);
        delete("/api/users/me/history?type=document").should().respond(204);
        delete("/api/users/me/history?type=document").should().respond(204);
    }

    @Test
    public void test_delete_user_event_by_id() {
        when(jooqRepository.deleteUserHistoryEvent(User.local(), 1)).thenReturn(true).thenReturn(false);

        delete("/api/users/me/history/event?id=7").should().respond(204);
        delete("/api/users/me/history/event?id=1").should().respond(204);
        delete("/api/users/me/history/event?id=1").should().respond(204);
    }

    @Test
    public void test_get_user_permissions() {
        authorizer.addRoleForUserInProject(localUser("local"), Role.PROJECT_MEMBER, Domain.of("icij"), project("my-project"));

        get("/api/users/me/permissions").should().respond(200).contain("PROJECT_MEMBER").contain("icij::my-project");
    }

    @Test
    public void test_create_user_returns_201() throws Exception {
        UserCreated created = new UserCreated("alice", "alice@example.org", "Alice", "local", List.of(), false);
        when(userAdminService.create(any())).thenReturn(created);

        post("/api/users", "{\"login\":\"alice\",\"email\":\"alice@example.org\",\"name\":\"Alice\",\"password\":\"secret\",\"provider\":\"local\",\"groups\":[]}")
                .should().respond(201).contain("\"login\":\"alice\"");
    }

    @Test
    public void test_create_user_returns_409_when_already_exists() throws Exception {
        when(userAdminService.create(any())).thenThrow(new UserExistsException("alice"));

        post("/api/users", "{\"login\":\"alice\",\"email\":\"a@b.c\",\"provider\":\"local\",\"password\":\"pw\",\"groups\":[]}")
                .should().respond(409);
    }

    @Test
    public void test_create_user_returns_400_on_validation_error() throws Exception {
        when(userAdminService.create(any())).thenThrow(new ValidationException("email", "email is required"));

        post("/api/users", "{\"login\":\"alice\",\"provider\":\"local\",\"password\":\"pw\",\"groups\":[]}")
                .should().respond(400);
    }

    // GET /api/users — list

    @Test
    public void test_list_users_returns_200() {
        User alice = new User("alice", "Alice", "alice@example.org", "local", new HashMap<>());
        when(userAdminService.list(new UserFilter(null), null, 0, Integer.MAX_VALUE))
                .thenReturn(new WebResponse<>(List.of(alice), 0, Integer.MAX_VALUE, 1));

        get("/api/users").should().respond(200).contain("alice");
    }

    @Test
    public void test_list_users_returns_501_for_unsupported_store() {
        when(userAdminService.list(new UserFilter(null), null, 0, Integer.MAX_VALUE))
                .thenThrow(new UnsupportedOperationException("not supported"));

        get("/api/users").should().respond(501);
    }

    @Test
    public void test_list_users_with_no_filter_passes_empty_filter() {
        when(userAdminService.list(new UserFilter(null), null, 0, Integer.MAX_VALUE))
                .thenReturn(new WebResponse<>(List.of(), 0, Integer.MAX_VALUE, 0));

        get("/api/users").should().respond(200);
    }

    @Test
    public void test_list_users_with_pagination_params() {
        when(userAdminService.list(new UserFilter(null), null, 0, Integer.MAX_VALUE))
                .thenReturn(new WebResponse<>(List.of(), 0, Integer.MAX_VALUE, 0));

        // from/size are applied in-memory via WebResponse.fromStream; just check 200
        get("/api/users?from=0&size=5").should().respond(200);
    }

    @Test
    public void test_list_users_without_role_does_not_call_get_by_ids() {
        User alice = new User("alice", "Alice", "alice@example.org", "local", new HashMap<>());
        when(userAdminService.list(any(UserFilter.class), isNull(), eq(0), eq(Integer.MAX_VALUE)))
                .thenReturn(new WebResponse<>(List.of(alice), 0, Integer.MAX_VALUE, 1));

        get("/api/users").should().respond(200).contain("alice");
    }

    @Test
    public void test_get_user_by_login_returns_200() throws Exception {
        User alice = new User("alice", "Alice", "alice@example.org", "local", new HashMap<>());
        when(userAdminService.get("alice")).thenReturn(alice);

        get("/api/users/alice").should().respond(200).contain("alice");
    }

    @Test
    public void test_get_user_by_login_returns_404_when_not_found() throws Exception {
        when(userAdminService.get("ghost")).thenThrow(new UserNotFoundException("ghost"));

        get("/api/users/ghost").should().respond(404);
    }

    @Test
    public void test_update_user_returns_200() throws Exception {
        UserCreated updated = new UserCreated("alice", "new@example.org", "Alice B", "local", List.of(), false);
        when(userAdminService.update(eq("alice"), any())).thenReturn(updated);

        put("/api/users/alice", "{\"email\":\"new@example.org\",\"name\":\"Alice B\"}")
                .should().respond(200).contain("\"login\":\"alice\"");
    }

    @Test
    public void test_update_user_returns_404_when_not_found() throws Exception {
        when(userAdminService.update(eq("ghost"), any())).thenThrow(new UserNotFoundException("ghost"));

        put("/api/users/ghost", "{\"email\":\"e@e.com\"}").should().respond(404);
    }

    @Test
    public void test_update_user_returns_400_on_validation_error() throws Exception {
        when(userAdminService.update(eq("alice"), any())).thenThrow(new ValidationException("password", "cannot be empty"));

        put("/api/users/alice", "{\"password\":\"\"}").should().respond(400);
    }

    @Test
    public void test_delete_user_returns_204() throws Exception {
        when(userAdminService.delete("alice")).thenReturn(true);

        delete("/api/users/alice").should().respond(204);
    }

    @Test
    public void test_delete_user_is_idempotent_when_not_found() throws Exception {
        when(userAdminService.delete("ghost")).thenThrow(new UserNotFoundException("ghost"));

        delete("/api/users/ghost").should().respond(204);
    }

    @Test
    public void test_delete_user_removes_casbin_policies() throws Exception {
        authorizer.addRoleForUserInInstance(new User("alice"), Role.PROJECT_MEMBER);
        when(userAdminService.delete("alice")).thenReturn(true);

        delete("/api/users/alice").should().respond(204);

        assertTrue(authorizer.getGroupPermissions(localUser("alice")).isEmpty());
    }

    @Test
    public void test_grant_project_returns_200() throws Exception {
        ProjectGranted granted = new ProjectGranted("someproject", "alice", Role.PROJECT_ADMIN, null, false);
        when(projectAdminService.grant("someproject", "alice", Role.PROJECT_ADMIN)).thenReturn(granted);

        put("/api/users/alice/index/someproject?role=admin")
                .should().respond(200).contain("\"userLogin\":\"alice\"");
    }

    @Test
    public void test_grant_project_if_not_exists_returns_noop() throws Exception {
        ProjectGranted granted = new ProjectGranted("someproject", "alice", Role.PROJECT_ADMIN, null, true);
        when(projectAdminService.grantIfNotExists("someproject", "alice", Role.PROJECT_ADMIN)).thenReturn(granted);

        put("/api/users/alice/index/someproject?role=admin&ifNotExists=true")
                .should().respond(200).contain("\"noop\":true");
    }

    @Test
    public void test_grant_project_returns_400_on_invalid_role() throws Exception {
        put("/api/users/alice/index/someproject?role=bogus").should().respond(400);
    }

    @Test
    public void test_grant_project_returns_404_when_project_not_found() throws Exception {
        when(projectAdminService.grant("ghost", "alice", Role.PROJECT_ADMIN))
                .thenThrow(new ProjectNotFoundException("ghost"));

        put("/api/users/alice/index/ghost?role=admin").should().respond(404);
    }

    @Test
    public void test_grant_project_returns_404_when_user_not_found() throws Exception {
        when(projectAdminService.grant("someproject", "ghost", Role.PROJECT_ADMIN))
                .thenThrow(new org.icij.datashare.project.admin.UserNotFoundException("ghost"));

        put("/api/users/ghost/index/someproject?role=admin").should().respond(404);
    }

    @Test
    public void test_grant_project_does_not_conflict_with_me_route_when_login_is_me() throws Exception {
        ProjectGranted granted = new ProjectGranted("someproject", "me", Role.PROJECT_ADMIN, null, false);
        when(projectAdminService.grant("someproject", "me", Role.PROJECT_ADMIN)).thenReturn(granted);

        put("/api/users/me/index/someproject?role=admin")
                .should().respond(200).contain("\"userLogin\":\"me\"");
        verify(projectAdminService).grant("someproject", "me", Role.PROJECT_ADMIN);
    }

    // DELETE /api/users/:login/index/:index — revoke

    @Test
    public void test_revoke_project_returns_200() throws Exception {
        ProjectRevoked revoked = new ProjectRevoked("someproject", "alice", List.of(Role.PROJECT_ADMIN), false);
        when(projectAdminService.revoke("someproject", "alice")).thenReturn(revoked);

        delete("/api/users/alice/index/someproject").should().respond(200).contain("\"userLogin\":\"alice\"");
    }

    @Test
    public void test_revoke_project_if_exists_returns_noop() throws Exception {
        ProjectRevoked revoked = new ProjectRevoked("someproject", "alice", List.of(), true);
        when(projectAdminService.revokeIfExists("someproject", "alice")).thenReturn(revoked);

        delete("/api/users/alice/index/someproject?ifExists=true").should().respond(200).contain("\"noop\":true");
    }

    @Test
    public void test_revoke_project_returns_404_when_project_not_found() throws Exception {
        when(projectAdminService.revoke("ghost", "alice")).thenThrow(new ProjectNotFoundException("ghost"));

        delete("/api/users/alice/index/ghost").should().respond(404);
    }

    @Test
    public void test_revoke_project_returns_404_when_user_not_found() throws Exception {
        when(projectAdminService.revoke("someproject", "ghost"))
                .thenThrow(new org.icij.datashare.project.admin.UserNotFoundException("ghost"));

        delete("/api/users/ghost/index/someproject").should().respond(404);
    }

    @Test
    public void test_list_users_sort_by_login_ascending() {
        User alice = new User("alice", "Alice", "a@a.com", "local", new HashMap<>());
        User bob   = new User("bob",   "Bob",   "b@b.com", "local", new HashMap<>());
        when(userAdminService.list(any(UserFilter.class), isNull(), eq(0), eq(Integer.MAX_VALUE)))
            .thenReturn(new WebResponse<>(List.of(bob, alice), 0, Integer.MAX_VALUE, 2));

        String body = get("/api/users?sort=login").response().content();
        assertTrue(body.indexOf("alice") < body.indexOf("bob"));
    }

    @Test
    public void test_list_users_sort_invalid_value_returns_400() {
        get("/api/users?sort=unknown").should().respond(400);
    }

    @Test
    public void test_list_users_no_sort_passes_null_comparator() {
        when(userAdminService.list(any(UserFilter.class), isNull(), eq(0), eq(Integer.MAX_VALUE)))
            .thenReturn(new WebResponse<>(List.of(), 0, Integer.MAX_VALUE, 0));

        get("/api/users").should().respond(200);
    }

    @Test
    public void test_list_users_sort_by_role_ascending() {
        User alice = new User("alice", "Alice", "a@a.com", "local", new HashMap<>());
        authorizer.addRoleForUserInInstance(User.localUser("alice"), Role.PROJECT_ADMIN);
        when(userAdminService.list(any(UserFilter.class), isNull(), eq(0), eq(Integer.MAX_VALUE)))
            .thenReturn(new WebResponse<>(List.of(alice), 0, Integer.MAX_VALUE, 1));

        get("/api/users?sort=role").should().respond(200).contain("alice");
    }

    @Test
    public void test_me_route_still_works_after_login_param_added() {
        get("/api/users/me").should().respond(200).contain("\"uid\":\"local\"");
    }

    @Test
    public void test_list_users_returns_403_for_non_admin() throws IOException {
        // Fresh authorizer without PROJECT_ADMIN role for the local user
        Authorizer restrictedAuthorizer = new Authorizer(casbinRuleAdapter);
        PolicyAnnotation policyAnnotation = new PolicyAnnotation(restrictedAuthorizer);
        configure(routes -> routes
                .registerAroundAnnotation(Policy.class, policyAnnotation)
                .add(new UserResource(jooqRepository, restrictedAuthorizer, userAdminService, projectAdminService))
                .filter(new LocalUserFilter(new PropertiesProvider(), jooqRepository)));

        get("/api/users").should().respond(403);
    }

    @Test
    public void test_list_users_returns_200_for_admin() {
        // setUp() grants PROJECT_ADMIN to User.local(), so admin can list users
        when(userAdminService.list(new UserFilter(null), null, 0, Integer.MAX_VALUE))
                .thenReturn(new WebResponse<>(List.of(), 0, Integer.MAX_VALUE, 0));

        get("/api/users").should().respond(200);
    }

    @Test
    public void test_list_users_sort_login_orders_alphabetically() {
        User alice = new User("alice", "Alice", "a@a.com", "local", new HashMap<>());
        User bob   = new User("bob",   "Bob",   "b@b.com", "local", new HashMap<>());
        when(userAdminService.list(any(UserFilter.class), isNull(), eq(0), eq(Integer.MAX_VALUE)))
                .thenReturn(new WebResponse<>(List.of(bob, alice), 0, Integer.MAX_VALUE, 2));

        String body = get("/api/users?sort=login").response().content();
        assertTrue(body.indexOf("alice") < body.indexOf("bob"));
    }

    @Test
    public void test_list_users_sort_login_desc_reverses_order() {
        User alice = new User("alice", "Alice", "a@a.com", "local", new HashMap<>());
        User bob   = new User("bob",   "Bob",   "b@b.com", "local", new HashMap<>());
        when(userAdminService.list(any(UserFilter.class), isNull(), eq(0), eq(Integer.MAX_VALUE)))
                .thenReturn(new WebResponse<>(List.of(alice, bob), 0, Integer.MAX_VALUE, 2));

        String body = get("/api/users?sort=login&desc=true").response().content();
        // desc=true reverses: bob before alice
        assertTrue(body.indexOf("bob") < body.indexOf("alice"));
    }

    @Test
    public void test_list_users_sort_by_email_ascending() {
        User alice = new User("alice", "Alice", "z@z.com", "local", new HashMap<>());
        User bob   = new User("bob",   "Bob",   "a@a.com", "local", new HashMap<>());
        when(userAdminService.list(any(UserFilter.class), isNull(), eq(0), eq(Integer.MAX_VALUE)))
                .thenReturn(new WebResponse<>(List.of(alice, bob), 0, Integer.MAX_VALUE, 2));

        String body = get("/api/users?sort=email").response().content();
        // bob's email a@a.com sorts before alice's z@z.com
        assertTrue(body.indexOf("bob") < body.indexOf("alice"));
    }

    @Test
    public void test_list_users_sort_email_desc_reverses_order() {
        User alice = new User("alice", "Alice", "a@a.com", "local", new HashMap<>());
        User bob   = new User("bob",   "Bob",   "b@b.com", "local", new HashMap<>());
        when(userAdminService.list(any(UserFilter.class), isNull(), eq(0), eq(Integer.MAX_VALUE)))
                .thenReturn(new WebResponse<>(List.of(alice, bob), 0, Integer.MAX_VALUE, 2));

        String body = get("/api/users?sort=email&desc=true").response().content();
        assertTrue(body.indexOf("bob") < body.indexOf("alice"));
    }

    @Test
    public void test_list_users_sort_by_name_ascending() {
        User alice = new User("alice", "Zed",   "a@a.com", "local", new HashMap<>());
        User bob   = new User("bob",   "Adam",  "b@b.com", "local", new HashMap<>());
        when(userAdminService.list(any(UserFilter.class), isNull(), eq(0), eq(Integer.MAX_VALUE)))
                .thenReturn(new WebResponse<>(List.of(alice, bob), 0, Integer.MAX_VALUE, 2));

        String body = get("/api/users?sort=name").response().content();
        // bob's name "Adam" sorts before alice's "Zed"
        assertTrue(body.indexOf("bob") < body.indexOf("alice"));
    }

    @Test
    public void test_list_users_sort_name_desc_reverses_order() {
        User alice = new User("alice", "Alice", "a@a.com", "local", new HashMap<>());
        User bob   = new User("bob",   "Bob",   "b@b.com", "local", new HashMap<>());
        when(userAdminService.list(any(UserFilter.class), isNull(), eq(0), eq(Integer.MAX_VALUE)))
                .thenReturn(new WebResponse<>(List.of(alice, bob), 0, Integer.MAX_VALUE, 2));

        String body = get("/api/users?sort=name&desc=true").response().content();
        assertTrue(body.indexOf("bob") < body.indexOf("alice"));
    }

    @Test
    public void test_list_users_sort_by_name_null_names_sort_last() {
        User alice = new User("alice", null,    "a@a.com", "local", new HashMap<>());
        User bob   = new User("bob",   "Bob",   "b@b.com", "local", new HashMap<>());
        when(userAdminService.list(any(UserFilter.class), isNull(), eq(0), eq(Integer.MAX_VALUE)))
                .thenReturn(new WebResponse<>(List.of(alice, bob), 0, Integer.MAX_VALUE, 2));

        String body = get("/api/users?sort=name").response().content();
        assertTrue(body.indexOf("bob") < body.indexOf("alice"));
    }

    @Test
    public void test_list_users_sort_uid_now_returns_400() {
        get("/api/users?sort=uid").should().respond(400);
    }

    @Test
    public void test_list_users_sort_role_orders_by_ordinal() {
        User alice = new User("alice", "Alice", "a@a.com", "local", new HashMap<>());
        User bob   = new User("bob",   "Bob",   "b@b.com", "local", new HashMap<>());
        authorizer.addRoleForUserInInstance(User.localUser("alice"), Role.INSTANCE_ADMIN);
        authorizer.addRoleForUserInProject(User.localUser("bob"), Role.PROJECT_MEMBER, Domain.DEFAULT, new Project("cantina"));
        when(userAdminService.list(any(UserFilter.class), isNull(), eq(0), eq(Integer.MAX_VALUE)))
                .thenReturn(new WebResponse<>(List.of(bob, alice), 0, Integer.MAX_VALUE, 2));

        // INSTANCE_ADMIN ordinal < PROJECT_MEMBER ordinal → alice before bob
        String body = get("/api/users?sort=role").response().content();
        assertTrue(body.indexOf("alice") < body.indexOf("bob"));
    }

    // --- new listUsers tests ---

    @Test
    public void test_list_users_no_scope_returns_all_users() {
        User alice = new User("alice", "Alice", "alice@x.com", "local", new HashMap<>());
        when(userAdminService.list(new UserFilter(null), null, 0, Integer.MAX_VALUE))
                .thenReturn(new WebResponse<>(List.of(alice), 0, Integer.MAX_VALUE, 1));

        get("/api/users").should().respond(200).contain("alice");
    }

    @Test
    public void test_list_users_q_param_forwarded_to_service() {
        when(userAdminService.list(new UserFilter("ali"), null, 0, Integer.MAX_VALUE))
                .thenReturn(new WebResponse<>(List.of(), 0, Integer.MAX_VALUE, 0));

        get("/api/users?q=ali").should().respond(200);
        // verify service received the filter
        ArgumentCaptor<UserFilter> captor = ArgumentCaptor.forClass(UserFilter.class);
        verify(userAdminService).list(captor.capture(), isNull(), eq(0), eq(Integer.MAX_VALUE));
        assertEquals("ali", captor.getValue().q());
    }

    @Test
    public void test_list_users_domain_scope_includes_wildcard_rule() {
        User toto = new User("toto", null, "toto@t.com", "local", new HashMap<>());
        User bob  = new User("bob",  null, "bob@b.com",  "local", new HashMap<>());
        when(userAdminService.list(new UserFilter(null), null, 0, Integer.MAX_VALUE))
                .thenReturn(new WebResponse<>(List.of(toto, bob), 0, Integer.MAX_VALUE, 2));
        // toto has *::* (instance-wide) → matches any domain filter
        authorizer.addRoleForUserInInstance(User.localUser("toto"), Role.INSTANCE_ADMIN);

        get("/api/users?domain=icij").should().respond(200)
                .contain("toto").not().contain("bob");
    }

    @Test
    public void test_list_users_domain_scope_excludes_other_domain() {
        User tutu = new User("tutu", "Tutu", "tutu@t.com", "local", new HashMap<>());
        when(userAdminService.list(new UserFilter(null), null, 0, Integer.MAX_VALUE))
                .thenReturn(new WebResponse<>(List.of(tutu), 0, Integer.MAX_VALUE, 1));
        // tutu only has other::papers → doesn't match domain=icij
        authorizer.addRoleForUserInProject(User.localUser("tutu"), Role.PROJECT_MEMBER, Domain.of("other"), new Project("papers"));

        // scoped to icij, tutu only has other::papers → excluded
        get("/api/users?domain=icij").should().respond(200)
                .not().contain("tutu");
    }

    @Test
    public void test_list_users_index_scope_filters_permissions() {
        User titi = new User("titi", "Titi", "titi@t.com", "local", new HashMap<>());
        when(userAdminService.list(new UserFilter(null), null, 0, Integer.MAX_VALUE))
                .thenReturn(new WebResponse<>(List.of(titi), 0, Integer.MAX_VALUE, 1));
        authorizer.addRoleForUserInProject(User.localUser("titi"), Role.PROJECT_ADMIN, Domain.DEFAULT, new Project("cantina"));
        authorizer.addRoleForUserInProject(User.localUser("titi"), Role.PROJECT_EDITOR, Domain.DEFAULT, new Project("local-datashare"));

        // scoped to default::cantina → only cantina permission should appear
        String body = get("/api/users?domain=default&index=cantina").response().content();
        assertTrue(body.contains("PROJECT_ADMIN"));
        assertTrue(body.contains("default::cantina"));
        assertFalse(body.contains("local-datashare"));
    }

    @Test
    public void test_list_users_index_scope_without_domain_filters_permissions() {
        User titi = new User("titi", "Titi", "titi@t.com", "local", new HashMap<>());
        when(userAdminService.list(new UserFilter(null), null, 0, Integer.MAX_VALUE))
                .thenReturn(new WebResponse<>(List.of(titi), 0, Integer.MAX_VALUE, 1));
        authorizer.addRoleForUserInProject(User.localUser("titi"), Role.PROJECT_ADMIN, Domain.DEFAULT, new Project("cantina"));
        authorizer.addRoleForUserInProject(User.localUser("titi"), Role.PROJECT_EDITOR, Domain.DEFAULT, new Project("local-datashare"));

        // scoped to index=cantina only (no domain param) → only cantina permission should appear
        String body = get("/api/users?index=cantina").response().content();
        assertTrue(body.contains("PROJECT_ADMIN"));
        assertTrue(body.contains("default::cantina"));
        assertFalse(body.contains("local-datashare"));
    }

    @Test
    public void test_list_users_index_scope_without_domain_excludes_revoked_user() {
        User titi = new User("titi", "Titi", "titi@t.com", "local", new HashMap<>());
        when(userAdminService.list(new UserFilter(null), null, 0, Integer.MAX_VALUE))
                .thenReturn(new WebResponse<>(List.of(titi), 0, Integer.MAX_VALUE, 1));
        // titi's cantina role was revoked, but they still hold a role on another project
        authorizer.addRoleForUserInProject(User.localUser("titi"), Role.PROJECT_EDITOR, Domain.DEFAULT, new Project("local-datashare"));

        get("/api/users?index=cantina").should().respond(200)
                .not().contain("titi");
    }

    @Test
    public void test_list_users_no_role_false_excludes_users_without_permissions() {
        User dudu = new User("dudu", null, "dudu@d.com", "local", new HashMap<>());
        User toto = new User("toto", null, "toto@t.com", "local", new HashMap<>());
        when(userAdminService.list(new UserFilter(null), null, 0, Integer.MAX_VALUE))
                .thenReturn(new WebResponse<>(List.of(dudu, toto), 0, Integer.MAX_VALUE, 2));
        authorizer.addRoleForUserInInstance(User.localUser("toto"), Role.INSTANCE_ADMIN);

        get("/api/users?noRole=false").should().respond(200)
                .contain("toto").not().contain("dudu");
    }

    @Test
    public void test_list_users_no_role_true_includes_users_without_permissions() {
        User dudu = new User("dudu", null, "dudu@d.com", "local", new HashMap<>());
        when(userAdminService.list(new UserFilter(null), null, 0, Integer.MAX_VALUE))
                .thenReturn(new WebResponse<>(List.of(dudu), 0, Integer.MAX_VALUE, 1));
        // dudu has no rules → with noRole=true they should still appear

        get("/api/users?noRole=true").should().respond(200).contain("dudu");
    }

    @Test
    public void test_list_users_scoped_excludes_no_permission_users_by_default() {
        User dudu = new User("dudu", null, "dudu@d.com", "local", new HashMap<>());
        when(userAdminService.list(new UserFilter(null), null, 0, Integer.MAX_VALUE))
                .thenReturn(new WebResponse<>(List.of(dudu), 0, Integer.MAX_VALUE, 1));
        // dudu has no rules for icij domain → excluded when scoped

        // scoped but no noRole param → dudu has no matching permissions → excluded
        get("/api/users?domain=icij").should().respond(200).not().contain("dudu");
    }

    @Test
    public void test_list_users_sort_login_ascending() {
        User charlie = new User("charlie", null, "c@c.com", "local", new HashMap<>());
        User alice   = new User("alice",   null, "a@a.com", "local", new HashMap<>());
        when(userAdminService.list(new UserFilter(null), null, 0, Integer.MAX_VALUE))
                .thenReturn(new WebResponse<>(List.of(charlie, alice), 0, Integer.MAX_VALUE, 2));

        String body = get("/api/users?sort=login").response().content();
        assertTrue(body.indexOf("alice") < body.indexOf("charlie"));
    }

    @Test
    public void test_list_users_sort_role_puts_highest_privilege_first() {
        User toto = new User("toto", null, "toto@t.com", "local", new HashMap<>());
        User titi = new User("titi", null, "titi@t.com", "local", new HashMap<>());
        when(userAdminService.list(new UserFilter(null), null, 0, Integer.MAX_VALUE))
                .thenReturn(new WebResponse<>(List.of(titi, toto), 0, Integer.MAX_VALUE, 2));
        authorizer.addRoleForUserInInstance(User.localUser("toto"), Role.INSTANCE_ADMIN);
        authorizer.addRoleForUserInProject(User.localUser("titi"), Role.PROJECT_MEMBER, Domain.DEFAULT, new Project("cantina"));

        String body = get("/api/users?sort=role").response().content();
        assertTrue(body.indexOf("toto") < body.indexOf("titi"));
    }

    @Test
    public void test_list_users_sort_role_no_role_users_sort_last() {
        User dudu = new User("dudu", null, "dudu@d.com", "local", new HashMap<>());
        User toto = new User("toto", null, "toto@t.com", "local", new HashMap<>());
        when(userAdminService.list(new UserFilter(null), null, 0, Integer.MAX_VALUE))
                .thenReturn(new WebResponse<>(List.of(dudu, toto), 0, Integer.MAX_VALUE, 2));
        authorizer.addRoleForUserInInstance(User.localUser("toto"), Role.INSTANCE_ADMIN);

        String body = get("/api/users?sort=role&noRole=true").response().content();
        assertTrue(body.indexOf("toto") < body.indexOf("dudu"));
    }

    @Test
    public void test_list_users_invalid_sort_returns_400() {
        when(userAdminService.list(any(), any(), anyInt(), anyInt()))
                .thenReturn(new WebResponse<>(List.of(), 0, 100, 0));

        get("/api/users?sort=unknown").should().respond(400);
    }

    @Test
    public void test_list_users_unsupported_store_returns_501() {
        when(userAdminService.list(any(), any(), anyInt(), anyInt()))
                .thenThrow(new UnsupportedOperationException("not supported"));

        get("/api/users").should().respond(501);
    }

    @Test
    public void test_list_users_response_has_pagination() {
        when(userAdminService.list(new UserFilter(null), null, 0, Integer.MAX_VALUE))
                .thenReturn(new WebResponse<>(List.of(), 0, Integer.MAX_VALUE, 0));

        get("/api/users?from=5&size=10").should().respond(200)
                .contain("\"from\":5").contain("\"size\":10");
    }
}
