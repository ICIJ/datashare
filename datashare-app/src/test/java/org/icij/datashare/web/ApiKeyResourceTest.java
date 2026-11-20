package org.icij.datashare.web;


import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.db.JooqRepository;
import net.codestory.http.security.Users;
import org.icij.datashare.session.ApiKeyExpiration;
import org.icij.datashare.session.ApiKeyFilter;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.tasks.DelApiKeyTask;
import org.icij.datashare.tasks.GenApiKeyTask;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.user.ApiKeyRepository;
import org.icij.datashare.user.DatashareApiKey;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class ApiKeyResourceTest extends AbstractProdWebServerTest {
    @Mock public DatashareTaskFactory taskFactory;
    @Mock JooqRepository jooqRepository;
    @Mock ApiKeyRepository apiKeyRepository;

    @Test
    public void test_generate_key() throws Exception {
        GenApiKeyTask task = mock(GenApiKeyTask.class);
        when(task.call()).thenReturn("privateKey");
        when(taskFactory.createGenApiKey(User.local(), null)).thenReturn(task);

        put("/api/key/" + User.local().id).should().respond(201).haveType("application/json").contain("privateKey");
        put("/api/key/" + User.local().id).should().respond(201).haveType("application/json").contain("privateKey");
    }

    @Test
    public void test_generate_key_without_body() throws Exception {
        GenApiKeyTask task = mock(GenApiKeyTask.class);
        when(task.call()).thenReturn("privateKey");
        when(taskFactory.createGenApiKey(User.local(), null)).thenReturn(task);

        put("/api/key/" + User.local().id).should().respond(201).contain("privateKey");
        verify(taskFactory, times(1)).createGenApiKey(User.local(), null);
    }

    @Test
    public void test_generate_key_with_expiration_date() throws Exception {
        Date expiration = Date.from(Instant.parse("2027-02-01T00:00:00Z"));
        GenApiKeyTask task = mock(GenApiKeyTask.class);
        when(task.call()).thenReturn("privateKey");
        when(taskFactory.createGenApiKey(User.local(), expiration)).thenReturn(task);

        put("/api/key/" + User.local().id, "{\"expirationDate\":\"2027-02-01T00:00:00Z\"}").should().respond(201)
                .contain("privateKey");
        verify(taskFactory).createGenApiKey(User.local(), expiration);
    }

    @Test
    public void test_generate_key_with_invalid_date_is_bad_request() {
        put("/api/key/" + User.local().id, "{\"expirationDate\":\"tomorrow\"}").should().respond(400);
    }

    @Test
    public void test_generate_key_with_non_object_body_is_bad_request() {
        put("/api/key/" + User.local().id, "\"2027-02-01T00:00:00Z\"").should().respond(400);
        put("/api/key/" + User.local().id, "[\"2027-02-01T00:00:00Z\"]").should().respond(400);
    }

    @Test
    public void test_generate_key_with_date_beyond_year_9999_is_bad_request() {
        put("/api/key/" + User.local().id, "{\"expirationDate\":\"+300000-01-01T00:00:00Z\"}").should().respond(400);
        put("/api/key/" + User.local().id, "{\"expirationDate\":\"+300000000-01-01T00:00:00Z\"}").should().respond(400);
    }

    @Test
    public void test_generate_key_rejected_by_policy_is_bad_request() throws Exception {
        GenApiKeyTask task = mock(GenApiKeyTask.class);
        when(task.call()).thenThrow(
                new ApiKeyExpiration.InvalidExpirationException("expiration date must be in the future"));
        when(taskFactory.createGenApiKey(User.local(), null)).thenReturn(task);

        put("/api/key/" + User.local().id).should().respond(400).haveType("application/json")
                .contain("{\"error\":\"expiration date must be in the future\"}");
    }

    @Test
    public void test_generate_key_internal_illegal_argument_is_server_error() throws Exception {
        GenApiKeyTask task = mock(GenApiKeyTask.class);
        when(task.call()).thenThrow(new IllegalArgumentException("internal detail"));
        when(taskFactory.createGenApiKey(User.local(), null)).thenReturn(task);

        put("/api/key/" + User.local().id).should().respond(500);
    }

    @Test
    public void test_get_key_returns_expiration_date() throws Exception {
        Date expiration = Date.from(Instant.parse("2027-02-01T00:00:00Z"));
        when(apiKeyRepository.get(User.local())).thenReturn(
                new DatashareApiKey("hashedKeyUser", User.local(), new Date(), expiration));

        get("/api/key/" + User.local().id).should().respond(200).contain("\"hashedKey\":\"hashedKeyUser\"")
                .contain("\"expirationDate\":\"2027-02-01T00:00:00Z\"");
        verify(apiKeyRepository, times(1)).get(User.local());
    }

    @Test
    public void test_get_key_without_key_returns_null_expiration_date() {
        get("/api/key/" + User.local().id).should().respond(200).contain("\"expirationDate\":null");
    }

    @Test
    public void test_get_key() {
        when(apiKeyRepository.get(User.local())).thenReturn(new DatashareApiKey("hashedKeyUser", User.local()));

        get("/api/key/" + User.local().id).should().respond(200).haveType("application/json").contain("hashedKeyUser");
    }

    @Test
    public void test_delete_key() throws Exception {
        DelApiKeyTask task = mock(DelApiKeyTask.class);
        when(task.call()).thenReturn(true);
        when(taskFactory.createDelApiKey(User.local())).thenReturn(task);

        delete("/api/key/" + User.local().id).should().respond(204);
    }

    @Test
    public void test_delete_key_false() throws Exception {
        DelApiKeyTask task = mock(DelApiKeyTask.class);
        when(task.call()).thenReturn(false);
        when(taskFactory.createDelApiKey(User.local())).thenReturn(task);

        delete("/api/key/" + User.local().id).should().respond(204);
    }

    @Test
    public void test_generate_key_for_other_user_is_forbidden() {
        put("/api/key/other-user").should().respond(403);
    }

    @Test
    public void test_get_key_for_other_user_is_forbidden() {
        get("/api/key/other-user").should().respond(403);
    }

    @Test
    public void test_delete_key_for_other_user_is_forbidden() {
        delete("/api/key/other-user").should().respond(403);
    }

    @Test
    public void test_generate_key_with_api_key_is_forbidden() {
        Users users = mock(Users.class);
        when(users.find("local")).thenReturn(new DatashareUser(User.local()));
        ApiKeyResource resource = new ApiKeyResource(taskFactory, apiKeyRepository,
                                                     new ApiKeyExpiration(new PropertiesProvider()));
        ApiKeyFilter apiKeyFilter = new ApiKeyFilter(users, key -> new DatashareApiKey("hash", User.local()), null);
        configure(routes -> routes.add(resource).filter(apiKeyFilter));

        put("/api/key/" + User.local().id).withHeader("Authorization", "Bearer some-key").should().respond(403);
        verify(taskFactory, never()).createGenApiKey(any(), any());
    }

    @Test
    public void test_list_keys() {
        Date creation = Date.from(Instant.parse("2026-01-01T00:00:00Z"));
        Date expiration = Date.from(Instant.parse("2027-02-01T00:00:00Z"));
        when(apiKeyRepository.getAll(User.local())).thenReturn(List.of(
                new DatashareApiKey("hash1", User.local(), creation, expiration, List.of("p1"))));

        get("/api/key/local/keys").should().respond(200).haveType("application/json")
                .contain("\"hashedKey\":\"hash1\"")
                .contain("\"projects\":[\"p1\"]")
                .contain("\"creationDate\":\"2026-01-01T00:00:00Z\"")
                .contain("\"expirationDate\":\"2027-02-01T00:00:00Z\"");
    }

    @Test
    public void test_add_key_with_projects() throws Exception {
        when(jooqRepository.getProjects()).thenReturn(List.of(new Project("p1")));
        GenApiKeyTask task = mock(GenApiKeyTask.class);
        when(task.call()).thenReturn("privateKey");
        when(taskFactory.createGenApiKey(User.local(), null, List.of("p1"))).thenReturn(task);

        post("/api/key/local/keys", "{\"projects\":[\"p1\",\"p1\"]}").should().respond(201)
                .haveType("application/json").contain("{\"apiKey\":\"privateKey\"}");
        verify(taskFactory).createGenApiKey(User.local(), null, List.of("p1"));
    }

    @Test
    public void test_add_key_without_body_adds_unscoped() throws Exception {
        GenApiKeyTask task = mock(GenApiKeyTask.class);
        when(task.call()).thenReturn("privateKey");
        when(taskFactory.createGenApiKey(User.local(), null, List.of())).thenReturn(task);

        post("/api/key/local/keys").should().respond(201).contain("privateKey");
        verify(taskFactory).createGenApiKey(User.local(), null, List.of());
    }

    @Test
    public void test_add_key_with_ungranted_project() {
        when(jooqRepository.getProjects()).thenReturn(List.of(new Project("p1")));

        post("/api/key/local/keys", "{\"projects\":[\"p1\",\"nope\"]}").should().respond(400).contain("nope");
        verify(taskFactory, never()).createGenApiKey(any(), any(), any());
    }

    @Test
    public void test_add_key_with_non_array_projects() {
        post("/api/key/local/keys", "{\"projects\":\"p1\"}").should().respond(400);
        verify(taskFactory, never()).createGenApiKey(any(), any(), any());
    }

    @Test
    public void test_delete_one_key() {
        when(apiKeyRepository.delete(User.local(), "hash")).thenReturn(true);

        delete("/api/key/local/keys/hash").should().respond(204);
    }

    @Test
    public void test_delete_unknown_key() {
        when(apiKeyRepository.delete(User.local(), "hash")).thenReturn(false);

        delete("/api/key/local/keys/hash").should().respond(404);
    }

    @Test
    public void test_write_routes_refused_with_api_key() {
        configureWithApiKeyFilter();

        put("/api/key/local").withHeader("Authorization", "Bearer some-key").should().respond(403);
        post("/api/key/local/keys").withHeader("Authorization", "Bearer some-key").should().respond(403);
        delete("/api/key/local").withHeader("Authorization", "Bearer some-key").should().respond(403);
        delete("/api/key/local/keys/hash").withHeader("Authorization", "Bearer some-key").should().respond(403);
        verify(taskFactory, never()).createGenApiKey(any(), any());
        verify(taskFactory, never()).createGenApiKey(any(), any(), any());
        verify(apiKeyRepository, never()).delete(any(User.class), any());
    }

    @Test
    public void test_read_routes_open_with_api_key() {
        configureWithApiKeyFilter();

        get("/api/key/local").withHeader("Authorization", "Bearer some-key").should().respond(200);
        get("/api/key/local/keys").withHeader("Authorization", "Bearer some-key").should().respond(200);
    }

    @Test
    public void test_other_user_keys_forbidden() {
        get("/api/key/bob/keys").should().respond(403);
        post("/api/key/bob/keys").should().respond(403);
        delete("/api/key/bob/keys/hash").should().respond(403);
    }

    private void configureWithApiKeyFilter() {
        Users users = mock(Users.class);
        when(users.find("local")).thenReturn(new DatashareUser(User.local()));
        ApiKeyResource resource = new ApiKeyResource(taskFactory, apiKeyRepository,
                                                     new ApiKeyExpiration(new PropertiesProvider()));
        ApiKeyFilter apiKeyFilter = new ApiKeyFilter(users, key -> new DatashareApiKey("hash", User.local()), null);
        configure(routes -> routes.add(resource).filter(apiKeyFilter));
    }

    @Before
    public void setUp() {
        initMocks(this);
        configure(routes -> routes.add(new ApiKeyResource(taskFactory, apiKeyRepository, new ApiKeyExpiration(new PropertiesProvider()))).filter(new LocalUserFilter(new PropertiesProvider(), jooqRepository)));
    }
}
