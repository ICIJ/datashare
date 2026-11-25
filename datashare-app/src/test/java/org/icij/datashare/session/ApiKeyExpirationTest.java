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
import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

public class ApiKeyExpirationTest {
    @Rule public DatashareTimeRule time = new DatashareTimeRule("2026-01-01T00:00:00Z");

    private final ApiKeyExpiration noCap = new ApiKeyExpiration(new PropertiesProvider());
    private final ApiKeyExpiration cap90Days = new ApiKeyExpiration(
            new PropertiesProvider(Map.of("apiKeyMaxLifetime", "P90D")));

    @Test
    public void test_old_key_without_expiration_expires_30_days_after_creation() {
        ApiKey apiKey = apiKey("2020-01-01T00:00:00Z", null);

        assertThat(noCap.isExpired(apiKey)).isTrue();
        assertThat(noCap.effectiveExpiration(apiKey)).isEqualTo(date("2020-01-31T00:00:00Z"));
    }

    @Test
    public void test_recent_key_without_expiration_is_valid() {
        ApiKey apiKey = apiKey("2025-12-15T00:00:00Z", null);

        assertThat(noCap.isExpired(apiKey)).isFalse();
        assertThat(noCap.effectiveExpiration(apiKey)).isEqualTo(date("2026-01-14T00:00:00Z"));
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
    public void test_blank_max_lifetime_means_no_cap() {
        ApiKeyExpiration blankCap = new ApiKeyExpiration(new PropertiesProvider(Map.of("apiKeyMaxLifetime", " ")));
        ApiKey apiKey = apiKey("2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z");

        assertThat(blankCap.isExpired(apiKey)).isFalse();
    }

    @Test
    public void test_cap_longer_than_30_days_keeps_default_for_key_without_expiration() {
        ApiKey apiKey = apiKey("2025-09-01T00:00:00Z", null);

        assertThat(cap90Days.isExpired(apiKey)).isTrue();
        assertThat(cap90Days.effectiveExpiration(apiKey)).isEqualTo(date("2025-10-01T00:00:00Z"));
    }

    @Test
    public void test_key_without_expiration_is_capped_when_cap_is_shorter_than_30_days() {
        ApiKeyExpiration cap7Days = new ApiKeyExpiration(new PropertiesProvider(Map.of("apiKeyMaxLifetime", "P7D")));
        ApiKey apiKey = apiKey("2025-12-28T00:00:00Z", null);

        assertThat(cap7Days.isExpired(apiKey)).isFalse();
        assertThat(cap7Days.effectiveExpiration(apiKey)).isEqualTo(date("2026-01-04T00:00:00Z"));
    }

    @Test
    public void test_cap_shorter_than_expiration_wins() {
        ApiKey apiKey = apiKey("2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z");

        assertThat(cap90Days.effectiveExpiration(apiKey)).isEqualTo(date("2026-04-01T00:00:00Z"));
    }

    @Test
    public void test_new_key_defaults_to_30_days() {
        assertThat(noCap.expirationForNewKey(null)).isEqualTo(date("2026-01-31T00:00:00Z"));
        assertThat(cap90Days.expirationForNewKey(null)).isEqualTo(date("2026-01-31T00:00:00Z"));
    }

    @Test
    public void test_new_key_defaults_to_cap_when_shorter_than_30_days() {
        ApiKeyExpiration cap7Days = new ApiKeyExpiration(new PropertiesProvider(Map.of("apiKeyMaxLifetime", "P7D")));

        assertThat(cap7Days.expirationForNewKey(null)).isEqualTo(date("2026-01-08T00:00:00Z"));
    }

    @Test
    public void test_new_key_without_cap_keeps_requested_expiration_beyond_30_days() {
        Date requested = date("2027-01-01T00:00:00Z");

        assertThat(noCap.expirationForNewKey(requested)).isEqualTo(requested);
    }

    @Test
    public void test_new_key_keeps_requested_expiration() {
        Date requested = date("2026-02-01T00:00:00Z");

        assertThat(cap90Days.expirationForNewKey(requested)).isEqualTo(requested);
    }

    @Test
    public void test_beyond_cap_message_shows_configured_lifetime() {
        Date requested = date("2026-04-01T00:00:01Z");

        ApiKeyExpiration.InvalidExpirationException e = assertThrows(
                ApiKeyExpiration.InvalidExpirationException.class,
                () -> cap90Days.expirationForNewKey(requested));

        assertThat(e.getMessage()).isEqualTo("expiration date exceeds the maximum lifetime of P90D");
    }

    @Test(expected = ApiKeyExpiration.InvalidExpirationException.class)
    public void test_new_key_in_past_throws_invalid_expiration() {
        noCap.expirationForNewKey(date("2025-12-31T00:00:00Z"));
    }

    @Test(expected = ApiKeyExpiration.InvalidExpirationException.class)
    public void test_invalid_max_lifetime_throws() {
        new ApiKeyExpiration(new PropertiesProvider(Map.of("apiKeyMaxLifetime", "90d")));
    }

    @Test(expected = ApiKeyExpiration.InvalidExpirationException.class)
    public void test_negative_max_lifetime_throws() {
        new ApiKeyExpiration(new PropertiesProvider(Map.of("apiKeyMaxLifetime", "-P90D")));
    }

    @Test(expected = ApiKeyExpiration.InvalidExpirationException.class)
    public void test_zero_max_lifetime_throws() {
        new ApiKeyExpiration(new PropertiesProvider(Map.of("apiKeyMaxLifetime", "PT0S")));
    }

    @Test(expected = ApiKeyExpiration.InvalidExpirationException.class)
    public void test_max_lifetime_beyond_10_years_throws() {
        new ApiKeyExpiration(new PropertiesProvider(Map.of("apiKeyMaxLifetime", "P3651D")));
    }

    @Test
    public void test_max_lifetime_of_10_years_is_allowed() {
        ApiKeyExpiration cap10Years = new ApiKeyExpiration(new PropertiesProvider(Map.of("apiKeyMaxLifetime", "P3650D")));

        assertThat(cap10Years.expirationForNewKey(date("2035-12-29T00:00:00Z"))).isEqualTo(date("2035-12-29T00:00:00Z"));
    }

    @Test(expected = ApiKeyExpiration.InvalidExpirationException.class)
    public void test_to_expiration_date_before_1970_throws() {
        ApiKeyExpiration.toExpirationDate(Instant.parse("1969-12-31T23:59:59Z"));
    }

    @Test(expected = ApiKeyExpiration.InvalidExpirationException.class)
    public void test_to_expiration_date_far_in_the_past_throws() {
        ApiKeyExpiration.toExpirationDate(Instant.parse("-300000000-01-01T00:00:00Z"));
    }

    @Test
    public void test_to_expiration_date_keeps_year_9999() {
        Instant instant = Instant.parse("9999-12-31T23:59:59Z");

        assertThat(ApiKeyExpiration.toExpirationDate(instant)).isEqualTo(Date.from(instant));
    }

    @Test(expected = ApiKeyExpiration.InvalidExpirationException.class)
    public void test_to_expiration_date_beyond_year_9999_throws() {
        ApiKeyExpiration.toExpirationDate(Instant.parse("+10000-01-01T00:00:00Z"));
    }

    private static ApiKey apiKey(String creationDate, String expirationDate) {
        Date expiration = expirationDate == null ? null : date(expirationDate);
        return new DatashareApiKey("hash", User.local(), date(creationDate), expiration, List.of());
    }

    private static Date date(String instant) {
        return Date.from(Instant.parse(instant));
    }
}
