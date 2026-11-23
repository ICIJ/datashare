package org.icij.datashare.session;

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.codestory.http.security.SessionIdStore;
import org.icij.datashare.PropertiesProvider;
import java.time.Duration;
import static com.google.common.base.Preconditions.checkArgument;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SESSION_STORE_MAX_SIZE;
import static org.icij.datashare.cli.DatashareCliOptions.SESSION_STORE_MAX_SIZE_OPT;

public class MemorySessionIdStore implements SessionIdStore {
    private final Cache<String, String> sessions;

    public MemorySessionIdStore(PropertiesProvider propertiesProvider) {
        this(propertiesProvider, Ticker.systemTicker());
    }

    MemorySessionIdStore(PropertiesProvider propertiesProvider, Ticker ticker) {
        String defaultMaxSize = String.valueOf(DEFAULT_SESSION_STORE_MAX_SIZE);
        String maxSizeValue = propertiesProvider.get(SESSION_STORE_MAX_SIZE_OPT).orElse(defaultMaxSize);
        int ttl = propertiesProvider.sessionTtlSeconds();
        long maxSize = Long.parseLong(maxSizeValue);
        checkArgument(maxSize >= 1, "%s must be at least 1, got %s", SESSION_STORE_MAX_SIZE_OPT, maxSize);
        Duration ttlDuration = Duration.ofSeconds(ttl);
        // Guava splits maximumSize across segments, so more than one segment evicts below the cap.
        this.sessions = CacheBuilder.newBuilder().concurrencyLevel(1).expireAfterWrite(ttlDuration).maximumSize(maxSize)
                                    .ticker(ticker).build();
    }

    @Override
    public void put(String sessionId, String login) {
        sessions.put(sessionId, login);
    }

    @Override
    public void remove(String sessionId) {
        sessions.invalidate(sessionId);
    }

    @Override
    public String getLogin(String sessionId) {
        return sessions.getIfPresent(sessionId);
    }
}
