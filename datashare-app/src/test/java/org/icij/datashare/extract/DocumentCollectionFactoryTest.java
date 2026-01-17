package org.icij.datashare.extract;

import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.Collection;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;


@RunWith(Parameterized.class)
public class DocumentCollectionFactoryTest {
    private final DocumentCollectionFactory<String> factory;

    @Test
    public void test_should_return_one_queue() throws InterruptedException {
        factory.createQueue("foo:baz", String.class).put("bar");
        factory.createQueue("qux:blah", String.class).put("blah");
        assertThat(factory.getQueues("foo:*", String.class)).hasSize(1);
        assertThat(factory.getQueues("foo:*", String.class).get(0)).contains("bar");
    }

    @Test
    public void test_should_return_all_queues() throws InterruptedException {
        factory.createQueue("foo:baz", String.class).put("bar");
        factory.createQueue("qux:blah", String.class).put("blah");
        assertThat(factory.getQueues(String.class)).hasSize(2);
    }

    @Parameterized.Parameters
    public static Collection<Object[]> maps() {
        PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of( "queueName", "extract:test"));
        Config config = new Config();
        config.useSingleServer().setDatabase(1).setAddress(EnvUtils.resolveUri("redis", "redis://redis:6379"));
        RedissonClient redissonClient = Redisson.create(config);

        return asList(new Object[][]{
                {new RedisDocumentCollectionFactory<String>(propertiesProvider, redissonClient)},
                {new MemoryDocumentCollectionFactory<String>()},
        });
    }

    public DocumentCollectionFactoryTest(DocumentCollectionFactory<String> factory) {
        this.factory = factory;
    }
}
