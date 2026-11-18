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
import static org.icij.datashare.cli.DatashareCliOptions.API_KEY_MAX_LIFETIME_OPT;

@Singleton
public class ApiKeyExpiration {
    private final Duration maxLifetime;

    @Inject
    public ApiKeyExpiration(PropertiesProvider propertiesProvider) {
        this.maxLifetime = propertiesProvider.get(API_KEY_MAX_LIFETIME_OPT).map(ApiKeyExpiration::parse).orElse(null);
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
            throw new IllegalArgumentException("expiration date must be in the future");
        }
        if (cappedDate != null && requested.after(cappedDate)) {
            throw new IllegalArgumentException("expiration date exceeds the maximum lifetime of " + maxLifetime);
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
            throw new IllegalArgumentException("invalid " + API_KEY_MAX_LIFETIME_OPT + ": " + maxLifetime, e);
        }
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(API_KEY_MAX_LIFETIME_OPT + " must be positive: " + maxLifetime);
        }
        return duration;
    }
}
