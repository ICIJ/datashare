package org.icij.datashare.asyncsearch;

import org.junit.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.fest.assertions.Assertions.assertThat;

public class MemoryAsyncSearchStoreTest {

    /** Mutable test clock so expiry is deterministic without sleeping. */
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-05-27T00:00:00Z");
        @Override public Instant instant() { return now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        void advance(Duration d) { now = now.plus(d); }
    }

    private final MutableClock clock = new MutableClock();
    private final MemoryAsyncSearchStore store = new MemoryAsyncSearchStore(clock);
    private final AsyncSearchOwner owner = new AsyncSearchOwner("alice", List.of("alice-datashare"));

    @Test
    public void test_put_then_get_returns_owner() {
        store.put("id-1", owner, Duration.ofMinutes(5));
        assertThat(store.get("id-1").isPresent()).isTrue();
        assertThat(store.get("id-1").get().userId).isEqualTo("alice");
        assertThat(store.get("id-1").get().projects).containsExactly("alice-datashare");
    }

    @Test
    public void test_get_unknown_id_is_empty() {
        assertThat(store.get("nope").isPresent()).isFalse();
    }

    @Test
    public void test_remove() {
        store.put("id-1", owner, Duration.ofMinutes(5));
        store.remove("id-1");
        assertThat(store.get("id-1").isPresent()).isFalse();
    }

    @Test
    public void test_entry_expires_after_keep_alive() {
        store.put("id-1", owner, Duration.ofMinutes(5));
        clock.advance(Duration.ofMinutes(4));
        assertThat(store.get("id-1").isPresent()).isTrue();
        clock.advance(Duration.ofMinutes(2)); // now 6 minutes, past the 5m TTL
        assertThat(store.get("id-1").isPresent()).isFalse();

        // at exactly the expiry instant, the entry is treated as expired
        MemoryAsyncSearchStore boundaryStore = new MemoryAsyncSearchStore(clock);
        boundaryStore.put("id-boundary", owner, Duration.ofMinutes(5));
        clock.advance(Duration.ofMinutes(5)); // now == expiresAt
        assertThat(boundaryStore.get("id-boundary").isPresent()).isFalse();
    }

    @Test
    public void test_refresh_extends_expiry() {
        store.put("id-1", owner, Duration.ofMinutes(5));
        clock.advance(Duration.ofMinutes(4));
        store.refresh("id-1", Duration.ofMinutes(5)); // expiry now at 9 minutes
        clock.advance(Duration.ofMinutes(3)); // now 7 minutes
        assertThat(store.get("id-1").isPresent()).isTrue();
    }
}
