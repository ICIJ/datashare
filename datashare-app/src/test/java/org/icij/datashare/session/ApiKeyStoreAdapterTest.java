package org.icij.datashare.session;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.user.ApiKeyRepository;
import org.icij.datashare.user.DatashareApiKey;
import org.icij.datashare.user.User;
import org.junit.Rule;
import org.junit.Test;

import java.time.Instant;
import java.util.Date;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ApiKeyStoreAdapterTest {
    @Rule public DatashareTimeRule time = new DatashareTimeRule("2026-01-01T00:00:00Z");

    private final ApiKeyRepository repository = mock(ApiKeyRepository.class);
    private final ApiKeyStoreAdapter adapter = new ApiKeyStoreAdapter(repository,
                                                                      new ApiKeyExpiration(new PropertiesProvider()));

    @Test
    public void test_get_login_returns_user_for_valid_key() {
        Date expiration = Date.from(Instant.parse("2026-02-01T00:00:00Z"));
        when(repository.get("key")).thenReturn(new DatashareApiKey("hash", User.local(), time.now(), expiration));

        assertThat(adapter.getLogin("key")).isEqualTo(User.local().id);
    }

    @Test
    public void test_get_login_returns_null_for_expired_key() {
        Date expiration = Date.from(Instant.parse("2025-12-31T00:00:00Z"));
        when(repository.get("key")).thenReturn(new DatashareApiKey("hash", User.local(), time.now(), expiration));

        assertThat(adapter.getLogin("key")).isNull();
    }

    @Test
    public void test_get_login_returns_null_for_unknown_key() {
        assertThat(adapter.getLogin("unknown")).isNull();
    }
}
