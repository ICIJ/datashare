package org.icij.datashare.web;

import net.codestory.http.filters.basic.BasicAuthFilter;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.UserEvent;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.CasbinRuleAdapter;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.policies.Policy;
import org.icij.datashare.policies.PolicyAnnotation;
import org.icij.datashare.policies.Role;
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
import org.mockito.Mock;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static java.util.Collections.singletonList;
import static org.icij.datashare.UserEvent.Type.DOCUMENT;
import static org.icij.datashare.session.DatashareUser.singleUser;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.localUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class UserResourceTest extends AbstractProdWebServerTest {
    @Mock JooqRepository jooqRepository;
    @Mock CasbinRuleAdapter casbinRuleAdapter;
    @Mock UserAdminService userAdminService;
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
                .add(new UserResource(jooqRepository, authorizer, userAdminService))
                .filter(new LocalUserFilter(new PropertiesProvider(), jooqRepository)));
    }

    @Test
    public void test_user_information() {
        configure(routes -> routes.add(new UserResource(jooqRepository, authorizer, userAdminService)).
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
                    .add(new UserResource(jooqRepository, authorizer, userAdminService));
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

    // GET /api/users — list (UserAdminService.list is NEW — add to interface)

    @Test
    public void test_list_users_returns_200() {
        User alice = new User("alice", "Alice", "alice@example.org", "local", new HashMap<>());
        when(userAdminService.list(new UserFilter(null, null, null, null), null, 0, 100))
                .thenReturn(new WebResponse<>(List.of(alice), 0, 100, 1));

        get("/api/users").should().respond(200).contain("alice");
    }

    @Test
    public void test_list_users_returns_501_for_unsupported_store() {
        when(userAdminService.list(new UserFilter(null, null, null, null), null, 0, 100))
                .thenThrow(new UnsupportedOperationException("not supported"));

        get("/api/users").should().respond(501);
    }

    @Test
    public void test_list_users_filters_by_provider() {
        User alice = new User("alice", "Alice", "alice@example.org", "local", new HashMap<>());
        when(userAdminService.list(new UserFilter(null, null, "local", null), null, 0, 100))
                .thenReturn(new WebResponse<>(List.of(alice), 0, 100, 1));

        get("/api/users?provider=local").should().respond(200).contain("alice");
    }

    @Test
    public void test_list_users_filters_by_name() {
        User alice = new User("alice", "Alice", "alice@example.org", "local", new HashMap<>());
        when(userAdminService.list(new UserFilter("ali", null, null, null), null, 0, 100))
                .thenReturn(new WebResponse<>(List.of(alice), 0, 100, 1));

        get("/api/users?name=ali").should().respond(200).contain("alice");
    }

    @Test
    public void test_list_users_with_no_filter_passes_empty_filter() {
        when(userAdminService.list(new UserFilter(null, null, null, null), null, 0, 100))
                .thenReturn(new WebResponse<>(List.of(), 0, 100, 0));

        get("/api/users").should().respond(200);
    }

    @Test
    public void test_list_users_with_pagination_params() {
        when(userAdminService.list(new UserFilter(null, null, null, null), null, 10, 5))
                .thenReturn(new WebResponse<>(List.of(), 10, 5, 100));

        get("/api/users?from=10&size=5").should().respond(200).contain("\"total\":100");
    }

    @Test
    public void test_list_users_filters_by_role() {
        User alice = new User("alice", "Alice", "alice@example.org", "local", new HashMap<>());
        authorizer.addRoleForUserInDomain(localUser("alice"), Role.PROJECT_ADMIN, Domain.DEFAULT);
        when(userAdminService.getByIds(argThat(ids -> ids != null && ids.contains("alice"))))
                .thenReturn(List.of(alice));

        get("/api/users?role=PROJECT_ADMIN").should().respond(200).contain("alice");
    }

    @Test
    public void test_list_users_filters_by_role_scoped_to_project() {
        User alice = new User("alice", "Alice", "alice@example.org", "local", new HashMap<>());
        Project myProject = new Project("my-project");
        authorizer.addRoleForUserInProject(localUser("alice"), Role.PROJECT_ADMIN, Domain.DEFAULT, myProject);
        when(userAdminService.getByIds(argThat(ids -> ids != null && ids.contains("alice"))))
                .thenReturn(List.of(alice));

        get("/api/users?role=PROJECT_ADMIN&domain=default&project=my-project").should().respond(200).contain("alice");
    }

    @Test
    public void test_list_users_without_role_does_not_call_get_by_ids() {
        User alice = new User("alice", "Alice", "alice@example.org", "local", new HashMap<>());
        when(userAdminService.list(any(UserFilter.class), isNull(), eq(0), eq(100)))
                .thenReturn(new WebResponse<>(List.of(alice), 0, 100, 1));

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
    public void test_delete_user_returns_404_when_not_found() throws Exception {
        when(userAdminService.delete("ghost")).thenThrow(new UserNotFoundException("ghost"));

        delete("/api/users/ghost").should().respond(404);
    }

    @Test
    public void test_list_users_sort_by_uid_ascending() {
        User alice = new User("alice", "Alice", "a@a.com", "local", new HashMap<>());
        User bob   = new User("bob",   "Bob",   "b@b.com", "local", new HashMap<>());
        when(userAdminService.list(any(UserFilter.class), argThat(c -> c != null), eq(0), eq(100)))
            .thenReturn(new WebResponse<>(List.of(alice, bob), 0, 100, 2));

        get("/api/users?sort=uid").should().respond(200).contain("alice").contain("bob");
    }

    @Test
    public void test_list_users_sort_invalid_value_returns_400() {
        get("/api/users?sort=unknown").should().respond(400);
    }

    @Test
    public void test_list_users_no_sort_passes_null_comparator() {
        when(userAdminService.list(any(UserFilter.class), isNull(), eq(0), eq(100)))
            .thenReturn(new WebResponse<>(List.of(), 0, 100, 0));

        get("/api/users").should().respond(200);
    }

    @Test
    public void test_list_users_sort_by_role_ascending() {
        User alice = new User("alice", "Alice", "a@a.com", "local", new HashMap<>());
        authorizer.addRoleForUserInDomain(User.localUser("alice"), Role.PROJECT_ADMIN, Domain.DEFAULT);
        when(userAdminService.list(any(UserFilter.class), argThat(c -> c != null), eq(0), eq(100)))
            .thenReturn(new WebResponse<>(List.of(alice), 0, 100, 1));

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
                .add(new UserResource(jooqRepository, restrictedAuthorizer, userAdminService))
                .filter(new LocalUserFilter(new PropertiesProvider(), jooqRepository)));

        get("/api/users").should().respond(403);
    }

    @Test
    public void test_list_users_returns_200_for_admin() {
        // setUp() grants PROJECT_ADMIN to User.local(), so admin can list users
        when(userAdminService.list(new UserFilter(null, null, null, null), null, 0, 100))
                .thenReturn(new WebResponse<>(List.of(), 0, 100, 0));

        get("/api/users").should().respond(200);
    }
}
