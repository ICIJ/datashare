package org.icij.datashare.session;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.user.ApiKey;
import org.icij.datashare.user.DatashareApiKey;
import org.icij.datashare.user.User;
import org.junit.Rule;
import org.junit.Test;

import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class ApiKeyExpirationTest {
    @Rule public DatashareTimeRule time = new DatashareTimeRule("2026-01-01T00:00:00Z");

    private final ApiKeyExpiration noCap = new ApiKeyExpiration(new PropertiesProvider());
    private final ApiKeyExpiration cap90Days = new ApiKeyExpiration(
            new PropertiesProvider(Map.of("apiKeyMaxLifetime", "P90D")));

    @Test
    public void test_no_cap_no_expiration_never_expires() {
        ApiKey apiKey = apiKey("2020-01-01T00:00:00Z", null);

        assertThat(noCap.isExpired(apiKey)).isFalse();
        assertThat(noCap.effectiveExpiration(apiKey)).isNull();
    }

    @Test
    public void test_expiration_date_in_past_is_expired() {
        ApiKey apiKey = apiKey("2025-12-01T00:00:00Z", "2025-12-31T23:59:59Z");

        assertThat(noCap.isExpired(apiKey)).isTrue();
    }

    @Test
    public void test_expiration_date_in_future_is_not_expired() {
        ApiKey apiKey = apiKey("2025-12-01T00:00:00Z", "2026-01-01T00:00:01Z");

        assertThat(noCap.isExpired(apiKey)).isFalse();
    }

    @Test
    public void test_old_key_without_expiration_is_capped_from_creation() {
        ApiKey apiKey = apiKey("2025-09-01T00:00:00Z", null);

        assertThat(cap90Days.isExpired(apiKey)).isTrue();
        assertThat(cap90Days.effectiveExpiration(apiKey)).isEqualTo(date("2025-11-30T00:00:00Z"));
    }

    @Test
    public void test_cap_shorter_than_expiration_wins() {
        ApiKey apiKey = apiKey("2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z");

        assertThat(cap90Days.effectiveExpiration(apiKey)).isEqualTo(date("2026-04-01T00:00:00Z"));
    }

    @Test
    public void test_new_key_defaults_to_cap() {
        assertThat(cap90Days.expirationForNewKey(null)).isEqualTo(date("2026-04-01T00:00:00Z"));
        assertThat(noCap.expirationForNewKey(null)).isNull();
    }

    @Test
    public void test_new_key_keeps_requested_expiration() {
        Date requested = date("2026-02-01T00:00:00Z");

        assertThat(cap90Days.expirationForNewKey(requested)).isEqualTo(requested);
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_new_key_beyond_cap_throws() {
        cap90Days.expirationForNewKey(date("2026-04-01T00:00:01Z"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_new_key_in_past_throws() {
        noCap.expirationForNewKey(date("2025-12-31T00:00:00Z"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_invalid_max_lifetime_throws() {
        new ApiKeyExpiration(new PropertiesProvider(Map.of("apiKeyMaxLifetime", "90d")));
    }

    private static ApiKey apiKey(String creationDate, String expirationDate) {
        Date expiration = expirationDate == null ? null : date(expirationDate);
        return new DatashareApiKey("hash", User.local(), date(creationDate), expiration);
    }

    private static Date date(String instant) {
        return Date.from(Instant.parse(instant));
    }
}
