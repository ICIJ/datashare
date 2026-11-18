package org.icij.datashare.web;


import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.session.ApiKeyExpiration;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.tasks.DelApiKeyTask;
import org.icij.datashare.tasks.GenApiKeyTask;
import org.icij.datashare.tasks.GetApiKeyTask;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.user.ApiKeyRepository;
import org.icij.datashare.user.DatashareApiKey;
import org.icij.datashare.user.User;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.time.Instant;
import java.util.Date;

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
    public void test_generate_key_rejected_by_policy_is_bad_request() throws Exception {
        GenApiKeyTask task = mock(GenApiKeyTask.class);
        when(task.call()).thenThrow(new IllegalArgumentException("expiration date must be in the future"));
        when(taskFactory.createGenApiKey(User.local(), null)).thenReturn(task);

        put("/api/key/" + User.local().id).should().respond(400).contain("expiration date must be in the future");
    }

    @Test
    public void test_get_key_returns_expiration_date() throws Exception {
        GetApiKeyTask task = mock(GetApiKeyTask.class);
        when(task.call()).thenReturn("hashedKeyUser");
        when(taskFactory.createGetApiKey(User.local())).thenReturn(task);
        Date expiration = Date.from(Instant.parse("2027-02-01T00:00:00Z"));
        when(apiKeyRepository.get(User.local())).thenReturn(
                new DatashareApiKey("hashedKeyUser", User.local(), new Date(), expiration));

        get("/api/key/" + User.local().id).should().respond(200).contain("\"expirationDate\":\"2027-02-01T00:00:00Z\"");
    }

    @Test
    public void test_get_key_without_key_returns_null_expiration_date() throws Exception {
        GetApiKeyTask task = mock(GetApiKeyTask.class);
        when(taskFactory.createGetApiKey(User.local())).thenReturn(task);

        get("/api/key/" + User.local().id).should().respond(200).contain("\"expirationDate\":null");
    }

    @Test
    public void test_get_key() throws Exception {
        GetApiKeyTask task = mock(GetApiKeyTask.class);
        when(task.call()).thenReturn("hashedKeyUser");
        when(taskFactory.createGetApiKey(User.local())).thenReturn(task);

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

    @Before
    public void setUp() {
        initMocks(this);
        configure(routes -> routes.add(new ApiKeyResource(taskFactory, apiKeyRepository, new ApiKeyExpiration(new PropertiesProvider()))).filter(new LocalUserFilter(new PropertiesProvider(), jooqRepository)));
    }
}
