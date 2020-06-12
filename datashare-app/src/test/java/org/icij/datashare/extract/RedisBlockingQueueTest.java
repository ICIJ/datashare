package org.icij.datashare.extract;

import org.icij.datashare.PropertiesProvider;
import org.junit.After;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class RedisBlockingQueueTest {
    RedisBlockingQueue queue = new RedisBlockingQueue(new PropertiesProvider());

    @Test
    public void test_offer_poll() {
        assertThat(queue.offer("test")).isTrue();
        assertThat(queue.poll()).isEqualTo("test");
    }

    @After
    public void tearDown() throws Exception {
        queue.delete();
        queue.close();
    }
}
