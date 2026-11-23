package org.icij.datashare.session;

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import net.codestory.http.security.SessionIdStore;
import org.icij.datashare.PropertiesProvider;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import static com.google.common.base.Preconditions.checkArgument;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SESSION_STORE_MAX_SIZE;
import static org.icij.datashare.cli.DatashareCliOptions.SESSION_STORE_MAX_SIZE_OPT;

public class MemorySessionIdStore implements SessionIdStore {
    static final int MAX_SESSIONS_PER_LOGIN = 10;
    private final Cache<String, String> sessions;
    private final Map<String, Deque<String>> sessionIdsByLogin = new HashMap<>();

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
        RemovalListener<String, String> forgetSessionId = removal -> forget(removal.getKey(), removal.getValue());
        this.sessions = CacheBuilder.newBuilder().concurrencyLevel(1).expireAfterWrite(ttlDuration).maximumSize(maxSize)
                                    .ticker(ticker).removalListener(forgetSessionId).build();
    }

    /**
     * Each login keeps only its newest sessions, so one account logging in again and again cannot push every other
     * user out of the capped store.
     */
    @Override
    public synchronized void put(String sessionId, String login) {
        Deque<String> loginSessionIds = sessionIdsByLogin.computeIfAbsent(login, key -> new ArrayDeque<>());
        loginSessionIds.addLast(sessionId);
        sessions.put(sessionId, login);
        if (loginSessionIds.size() > MAX_SESSIONS_PER_LOGIN) {
            sessions.invalidate(loginSessionIds.peekFirst());
        }
    }

    private synchronized void forget(String sessionId, String login) {
        Deque<String> loginSessionIds = sessionIdsByLogin.get(login);
        if (loginSessionIds == null) {
            return;
        }
        loginSessionIds.remove(sessionId);
        if (loginSessionIds.isEmpty()) {
            sessionIdsByLogin.remove(login);
        }
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
