package org.icij.datashare.policies;

import org.icij.datashare.EnvUtils;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.redisson.api.RedissonClient;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.mockito.Mockito.mock;

public class AuthorizerRedisSyncIntTest {
    private static final String REDIS_ADDRESS = EnvUtils.resolveUri("redis", "redis://redis:6379");

    private RedissonClient client1;
    private RedissonClient client2;
    private PolicyWatcher watcher1;
    private PolicyWatcher watcher2;
    private Authorizer instance1;
    private Authorizer instance2;

    @Before
    public void setUp() throws Exception {
        Map<String, Object> config = Map.of("redisAddress", REDIS_ADDRESS, "redisPoolSize", "5");
        client1 = new RedissonClientFactory().withOptions(Options.from(config)).create();
        client2 = new RedissonClientFactory().withOptions(Options.from(config)).create();
        watcher1 = new PolicyWatcher(client1);
        watcher2 = new PolicyWatcher(client2);
        instance1 = new Authorizer(mock(CasbinRuleAdapter.class), watcher1);
        instance2 = new Authorizer(mock(CasbinRuleAdapter.class), watcher2);
    }

    @After
    public void tearDown() throws Exception {
        instance1.close();
        instance2.close();
        watcher1.close();
        watcher2.close();
        client1.shutdown();
        client2.shutdown();
    }

    @Test
    public void policy_change_on_instance1_notifies_instance2() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        watcher2.setUpdateCallback(latch::countDown);

        instance1.addRoleForUserInProject(new User("alice"), Role.PROJECT_MEMBER, Domain.of("test_domain"), project("test_project"));

        assertThat(latch.await(3, TimeUnit.SECONDS))
                .as("instance2 watcher should be notified within 3s")
                .isTrue();
    }

    @Test
    public void policy_change_does_not_trigger_own_watcher() throws Exception {
        AtomicInteger selfCallCount = new AtomicInteger(0);
        watcher1.setUpdateCallback(selfCallCount::incrementAndGet);

        CountDownLatch otherLatch = new CountDownLatch(1);
        watcher2.setUpdateCallback(otherLatch::countDown);

        instance1.addRoleForUserInProject(new User("bob"), Role.PROJECT_MEMBER, Domain.of("test_domain"), project("test_project"));

        // Wait until the remote instance has received the notification
        otherLatch.await(3, TimeUnit.SECONDS);
        // Give any potential self-notification a moment to arrive
        Thread.sleep(200);

        assertThat(selfCallCount.get())
                .as("instance1 watcher must not be triggered by its own publish")
                .isEqualTo(0);
    }
}
