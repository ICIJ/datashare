package org.icij.datashare.policies;

import io.lettuce.core.RedisURI;
import org.casbin.watcherEx.RedisWatcherEx;
import org.casbin.watcherEx.WatcherOptions;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.mockito.Mockito.mock;

public class AuthorizerRedisSyncIntTest {
    private Authorizer instance1;
    private Authorizer instance2;
    private RedisWatcherEx watcher1;
    private RedisWatcherEx watcher2;

    @Before
    public void setUp() throws Exception {
        String redisAddress = EnvUtils.resolveUri("redis", "redis://redis:6379");
        watcher1 = createWatcher(redisAddress);
        watcher2 = createWatcher(redisAddress);
        instance1 = new Authorizer(mock(CasbinRuleAdapter.class), watcher1);
        instance2 = new Authorizer(mock(CasbinRuleAdapter.class), watcher2);
    }

    @After
    public void tearDown() throws Exception {
        instance1.close();
        instance2.close();
    }

    @Test
    public void policy_added_on_instance1_triggers_watcher_on_instance2() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        watcher2.setUpdateCallback((Runnable) latch::countDown);

        User user = new User("alice");
        Domain domain = Domain.of("test_domain");
        Project project = project("test_project");

        instance1.addRoleForUserInProject(user, Role.PROJECT_MEMBER, domain, project);

        boolean triggered = latch.await(3, TimeUnit.SECONDS);
        assertThat(triggered).as("watcher2 should have been notified within 3s").isTrue();
    }

    private static RedisWatcherEx createWatcher(String redisAddress) {
        WatcherOptions options = new WatcherOptions();
        options.setOptions(RedisURI.create(redisAddress));
        options.setChannel("datashare:policy-updated");
        options.setIgnoreSelf(false);
        return new RedisWatcherEx(options);
    }
}
