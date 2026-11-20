package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.session.ApiKeyExpiration;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.user.ApiKeyRepository;
import org.icij.datashare.user.DatashareApiKey;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

public class GenApiKeyTaskTest {
    @Rule public DatashareTimeRule time = new DatashareTimeRule("2026-01-01T00:00:00Z");
    @Mock public ApiKeyRepository apiKeyRepository;

    private final ApiKeyExpiration noCap = new ApiKeyExpiration(new PropertiesProvider());
    private final ApiKeyExpiration cap90Days = new ApiKeyExpiration(
            new PropertiesProvider(Map.of("apiKeyMaxLifetime", "P90D")));

    @Test
    public void test_call() throws Exception {
        assertThat(new GenApiKeyTask(apiKeyRepository, noCap, User.local(), null).call()).isNotNull();

        DatashareApiKey saved = savedKey();
        assertThat(saved.getUser()).isEqualTo(User.local());
        assertThat(saved.getExpirationDate()).isEqualTo(Date.from(Instant.parse("2026-01-31T00:00:00Z")));
    }

    @Test
    public void test_call_saves_requested_expiration() throws Exception {
        Date requested = Date.from(Instant.parse("2026-02-01T00:00:00Z"));

        new GenApiKeyTask(apiKeyRepository, noCap, User.local(), requested).call();

        assertThat(savedKey().getExpirationDate()).isEqualTo(requested);
    }

    @Test
    public void test_call_defaults_to_cap_when_shorter_than_30_days() throws Exception {
        ApiKeyExpiration cap7Days = new ApiKeyExpiration(new PropertiesProvider(Map.of("apiKeyMaxLifetime", "P7D")));
        new GenApiKeyTask(apiKeyRepository, cap7Days, User.local(), null).call();

        assertThat(savedKey().getExpirationDate()).isEqualTo(Date.from(Instant.parse("2026-01-08T00:00:00Z")));
    }

    @Test
    public void test_call_rejects_expiration_beyond_cap() throws Exception {
        Date requested = Date.from(Instant.parse("2027-01-01T00:00:00Z"));
        GenApiKeyTask task = new GenApiKeyTask(apiKeyRepository, cap90Days, User.local(), requested);

        assertThrows(ApiKeyExpiration.InvalidExpirationException.class, task::call);

        verify(apiKeyRepository, never()).replace(any());
    }

    @Test
    public void test_legacy_create_replaces_all_keys() throws Exception {
        new GenApiKeyTask(apiKeyRepository, noCap, User.local(), null).call();

        verify(apiKeyRepository).replace(any());
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    public void test_create_with_projects_adds_a_key() throws Exception {
        new GenApiKeyTask(apiKeyRepository, noCap, User.local(), null, List.of("p1")).call();

        verify(apiKeyRepository).save(argThat(k -> k.getProjects().equals(List.of("p1"))));
        verify(apiKeyRepository, never()).replace(any());
    }

    private DatashareApiKey savedKey() {
        ArgumentCaptor<DatashareApiKey> apiKey = forClass(DatashareApiKey.class);
        verify(apiKeyRepository).replace(apiKey.capture());
        return apiKey.getValue();
    }

    @Before
    public void setUp() throws Exception {
        initMocks(this);
    }
}
