package org.icij.datashare.asyncsearch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process ownership store for LOCAL/EMBEDDED (single-process) modes.
 * Entries expire lazily on access. Cross-instance correctness is not required
 * here because memory mode runs in a single process.
 */
public class MemoryAsyncSearchStore implements AsyncSearchStore {
    private record Entry(AsyncSearchOwner owner, Instant expiresAt) {}

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public MemoryAsyncSearchStore() {
        this(Clock.systemUTC());
    }

    public MemoryAsyncSearchStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void put(String asyncId, AsyncSearchOwner owner, Duration keepAlive) {
        entries.put(asyncId, new Entry(owner, clock.instant().plus(keepAlive)));
    }

    @Override
    public Optional<AsyncSearchOwner> get(String asyncId) {
        Entry entry = entries.get(asyncId);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.expiresAt().isAfter(clock.instant())) {
            entries.remove(asyncId);
            return Optional.empty();
        }
        return Optional.of(entry.owner());
    }

    @Override
    public void remove(String asyncId) {
        entries.remove(asyncId);
    }

    @Override
    public void refresh(String asyncId, Duration keepAlive) {
        entries.computeIfPresent(asyncId, (k, entry) ->
                new Entry(entry.owner(), clock.instant().plus(keepAlive)));
    }
}
