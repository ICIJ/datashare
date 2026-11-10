package org.icij.datashare.session;

import com.google.common.base.Ticker;
import org.icij.datashare.PropertiesProvider;
import org.junit.Test;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.fest.assertions.Assertions.assertThat;

public class MemorySessionIdStoreTest {
    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return nanos.get();
        }
    };

    private MemorySessionIdStore store(int ttlSeconds, int maxSize) {
        PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
            put("sessionTtlSeconds", String.valueOf(ttlSeconds));
            put("sessionStoreMaxSize", String.valueOf(maxSize));
        }});
        return new MemorySessionIdStore(propertiesProvider, ticker);
    }

    @Test
    public void test_put_get_remove() {
        MemorySessionIdStore store = store(60, 10);

        store.put("sid", "login");
        assertThat(store.getLogin("sid")).isEqualTo("login");

        store.remove("sid");
        assertThat(store.getLogin("sid")).isNull();
    }

    @Test
    public void test_session_expires_after_ttl() {
        MemorySessionIdStore store = store(60, 10);
        store.put("sid", "login");

        nanos.addAndGet(TimeUnit.SECONDS.toNanos(59));
        assertThat(store.getLogin("sid")).isEqualTo("login");

        nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertThat(store.getLogin("sid")).isNull();
    }

    @Test
    public void test_reading_a_session_does_not_extend_its_ttl() {
        MemorySessionIdStore store = store(60, 10);
        store.put("sid", "login");

        nanos.addAndGet(TimeUnit.SECONDS.toNanos(30));
        store.getLogin("sid");
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(30));

        assertThat(store.getLogin("sid")).isNull();
    }

    @Test
    public void test_sessions_are_evicted_past_max_size() {
        MemorySessionIdStore store = store(60, 2);

        IntStream.range(0, 10).forEach(i -> store.put("sid" + i, "login" + i));

        long remaining = IntStream.range(0, 10).filter(i -> store.getLogin("sid" + i) != null).count();
        assertThat(remaining).isLessThanOrEqualTo(2);
        assertThat(store.getLogin("sid9")).isEqualTo("login9");
    }

    @Test
    public void test_sessions_are_kept_up_to_max_size() {
        MemorySessionIdStore store = store(60, 1000);

        IntStream.range(0, 1000).forEach(i -> store.put("sid" + i, "login" + i));

        long remaining = IntStream.range(0, 1000).filter(i -> store.getLogin("sid" + i) != null).count();
        assertThat(remaining).isEqualTo(1000);
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_max_size_below_one_is_rejected() {
        store(60, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_ttl_below_one_is_rejected() {
        store(0, 10);
    }
}
