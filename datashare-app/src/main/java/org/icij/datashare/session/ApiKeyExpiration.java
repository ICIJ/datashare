package org.icij.datashare.session;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.ApiKey;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Optional;
import static org.icij.datashare.cli.DatashareCliOptions.API_KEY_MAX_LIFETIME_OPT;

@Singleton
public class ApiKeyExpiration {
    private static final Duration DEFAULT_LIFETIME = Duration.ofDays(30);
    private static final Duration MAX_LIFETIME_LIMIT = Duration.ofDays(3650);
    private static final Instant MAX_EXPIRATION = Instant.parse("9999-12-31T23:59:59Z");
    private final String maxLifetimeSetting;
    private final Duration maxLifetime;

    @Inject
    public ApiKeyExpiration(PropertiesProvider propertiesProvider) {
        Optional<String> configured = propertiesProvider.get(API_KEY_MAX_LIFETIME_OPT);
        Optional<String> setting = configured.filter(value -> !value.isBlank());
        this.maxLifetimeSetting = setting.orElse(null);
        this.maxLifetime = setting.map(ApiKeyExpiration::parse).orElse(null);
    }

    public Date effectiveExpiration(ApiKey apiKey) {
        Date creationDate = apiKey.getCreationDate();
        Date expirationDate = apiKey.getExpirationDate();
        if (expirationDate == null) {
            expirationDate = plus(creationDate, DEFAULT_LIFETIME);
        }
        return capped(creationDate, expirationDate);
    }

    public boolean isExpired(ApiKey apiKey) {
        Date expiration = effectiveExpiration(apiKey);
        return expiration != null && now().after(expiration);
    }

    public Date expirationForNewKey(Date requested) {
        Date now = now();
        if (requested == null) {
            Date defaultDate = plus(now, DEFAULT_LIFETIME);
            return capped(now, defaultDate);
        }
        Date cappedDate = maxLifetime == null ? null : plus(now, maxLifetime);
        if (!requested.after(now)) {
            throw new InvalidExpirationException("expiration date must be in the future");
        }
        if (cappedDate != null && requested.after(cappedDate)) {
            throw new InvalidExpirationException(
                    "expiration date exceeds the maximum lifetime of " + maxLifetimeSetting);
        }
        return requested;
    }

    public static Date toExpirationDate(Instant instant) {
        if (instant.isBefore(Instant.EPOCH)) {
            throw new InvalidExpirationException("expiration date must be in the future");
        }
        if (instant.isAfter(MAX_EXPIRATION)) {
            throw new InvalidExpirationException("expiration date is too far in the future");
        }
        return Date.from(instant);
    }

    private Date capped(Date creationDate, Date expirationDate) {
        if (maxLifetime == null) {
            return expirationDate;
        }
        Date cappedDate = plus(creationDate, maxLifetime);
        return cappedDate.before(expirationDate) ? cappedDate : expirationDate;
    }

    private static Date plus(Date date, Duration duration) {
        Instant instant = date.toInstant();
        Instant shifted = instant.plus(duration);
        return Date.from(shifted);
    }

    private static Date now() {
        return DatashareTime.getInstance().now();
    }

    private static Duration parse(String maxLifetime) {
        Duration duration;
        try {
            duration = Duration.parse(maxLifetime);
        } catch (DateTimeParseException e) {
            throw new InvalidExpirationException("invalid " + API_KEY_MAX_LIFETIME_OPT + ": " + maxLifetime, e);
        }
        if (duration.isNegative() || duration.isZero()) {
            throw new InvalidExpirationException(API_KEY_MAX_LIFETIME_OPT + " must be positive: " + maxLifetime);
        }
        if (duration.compareTo(MAX_LIFETIME_LIMIT) > 0) {
            throw new InvalidExpirationException(API_KEY_MAX_LIFETIME_OPT + " must not exceed P3650D: " + maxLifetime);
        }
        return duration;
    }

    public static class InvalidExpirationException extends IllegalArgumentException {
        public InvalidExpirationException(String message) {
            super(message);
        }

        public InvalidExpirationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
