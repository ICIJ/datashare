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
        Date expirationDate = apiKey.getExpirationDate();
        if (maxLifetime == null) {
            return expirationDate;
        }
        Date cappedDate = plusMaxLifetime(apiKey.getCreationDate());
        if (expirationDate == null || cappedDate.before(expirationDate)) {
            return cappedDate;
        }
        return expirationDate;
    }

    public boolean isExpired(ApiKey apiKey) {
        Date expiration = effectiveExpiration(apiKey);
        return expiration != null && now().after(expiration);
    }

    public Date expirationForNewKey(Date requested) {
        Date now = now();
        Date cappedDate = maxLifetime == null ? null : plusMaxLifetime(now);
        if (requested == null) {
            return cappedDate;
        }
        if (!requested.after(now)) {
            throw new InvalidExpirationException("expiration date must be in the future");
        }
        if (cappedDate != null && requested.after(cappedDate)) {
            throw new InvalidExpirationException(
                    "expiration date exceeds the maximum lifetime of " + maxLifetimeSetting);
        }
        return requested;
    }

    private Date plusMaxLifetime(Date date) {
        Instant capped = date.toInstant().plus(maxLifetime);
        return Date.from(capped);
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
