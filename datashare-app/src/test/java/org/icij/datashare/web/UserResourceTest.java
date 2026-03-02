package org.icij.datashare.web;

import net.codestory.http.filters.basic.BasicAuthFilter;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.UserEvent;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.net.URI;
import java.net.URISyntaxException;

import static java.util.Collections.singletonList;
import static org.icij.datashare.UserEvent.Type.DOCUMENT;
import static org.icij.datashare.session.DatashareUser.singleUser;
import static org.icij.datashare.text.Project.project;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class UserResourceTest extends AbstractProdWebServerTest {
    @Mock JooqRepository jooqRepository;
    PropertiesProvider propertiesProvider = new PropertiesProvider();

    @Before
    public void setUp() {
        initMocks(this);
        configure(routes -> routes.add(new UserResource(jooqRepository)).filter(new LocalUserFilter(new PropertiesProvider(), jooqRepository)));
    }

    @Test
    public void test_user_information() {
        configure(routes -> routes.add(new UserResource(jooqRepository)).
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
                    .add(new UserResource(jooqRepository));
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
}
