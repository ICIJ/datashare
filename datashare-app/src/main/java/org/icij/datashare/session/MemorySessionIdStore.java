package org.icij.datashare.session;

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.codestory.http.security.SessionIdStore;
import org.icij.datashare.PropertiesProvider;
import java.time.Duration;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SESSION_STORE_MAX_SIZE;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SESSION_TTL_SECONDS;
import static org.icij.datashare.cli.DatashareCliOptions.SESSION_STORE_MAX_SIZE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SESSION_TTL_SECONDS_OPT;

public class MemorySessionIdStore implements SessionIdStore {
    private final Cache<String, String> sessions;

    public MemorySessionIdStore(PropertiesProvider propertiesProvider) {
        this(propertiesProvider, Ticker.systemTicker());
    }

    MemorySessionIdStore(PropertiesProvider propertiesProvider, Ticker ticker) {
        String ttl =
                propertiesProvider.get(SESSION_TTL_SECONDS_OPT).orElse(String.valueOf(DEFAULT_SESSION_TTL_SECONDS));
        String maxSize = propertiesProvider.get(SESSION_STORE_MAX_SIZE_OPT)
                                           .orElse(String.valueOf(DEFAULT_SESSION_STORE_MAX_SIZE));
        Duration ttlDuration = Duration.ofSeconds(Long.parseLong(ttl));
        this.sessions = CacheBuilder.newBuilder().expireAfterWrite(ttlDuration).maximumSize(Long.parseLong(maxSize))
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
