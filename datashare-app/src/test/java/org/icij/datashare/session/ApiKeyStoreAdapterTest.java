package org.icij.datashare.session;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.user.ApiKey;
import org.icij.datashare.user.ApiKeyRepository;
import org.icij.datashare.user.DatashareApiKey;
import org.icij.datashare.user.User;
import org.junit.Rule;
import org.junit.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ApiKeyStoreAdapterTest {
    @Rule public DatashareTimeRule time = new DatashareTimeRule("2026-01-01T00:00:00Z");

    private final ApiKeyRepository repository = mock(ApiKeyRepository.class);
    private final ApiKeyStoreAdapter adapter = new ApiKeyStoreAdapter(repository,
                                                                      new ApiKeyExpiration(new PropertiesProvider()));

    @Test
    public void test_get_returns_key_with_projects() {
        Date expiration = Date.from(Instant.parse("2026-02-01T00:00:00Z"));
        when(repository.get("key")).thenReturn(new DatashareApiKey("hash", User.local(), time.now(), expiration, List.of("p1")));

        ApiKey apiKey = adapter.get("key");

        assertThat(apiKey.getUser().id).isEqualTo(User.local().id);
        assertThat(apiKey.getProjects()).containsOnly("p1");
    }

    @Test
    public void test_get_returns_null_for_expired_key() {
        Date expiration = Date.from(Instant.parse("2025-12-31T00:00:00Z"));
        when(repository.get("key")).thenReturn(new DatashareApiKey("hash", User.local(), time.now(), expiration, List.of()));

        assertThat(adapter.get("key")).isNull();
    }

    @Test
    public void test_get_returns_null_for_unknown_key() {
        assertThat(adapter.get("unknown")).isNull();
    }
}
