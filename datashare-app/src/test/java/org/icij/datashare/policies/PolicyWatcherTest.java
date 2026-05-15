package org.icij.datashare.policies;

import org.junit.Before;
import org.junit.Test;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class PolicyWatcherTest {
    private RTopic topic;
    private PolicyWatcher watcher;

    @Before
    public void setUp() {
        RedissonClient redisson = mock(RedissonClient.class);
        topic = mock(RTopic.class);
        when(redisson.getTopic("datashare:policy-updated")).thenReturn(topic);
        watcher = new PolicyWatcher(redisson);
    }

    @Test
    public void update_publishes_reload_message() {
        watcher.update();
        verify(topic).publish("reload");
    }

    @Test
    public void set_update_callback_runnable_registers_listener() {
        when(topic.addListener(eq(String.class), any())).thenReturn(1);
        watcher.setUpdateCallback((Runnable) () -> {});
        verify(topic).addListener(eq(String.class), any());
    }

    @Test
    public void set_update_callback_consumer_registers_listener() {
        when(topic.addListener(eq(String.class), any())).thenReturn(1);
        watcher.setUpdateCallback(msg -> {});
        verify(topic).addListener(eq(String.class), any());
    }

    @Test
    public void close_removes_listener_after_registration() {
        when(topic.addListener(eq(String.class), any())).thenReturn(42);
        watcher.setUpdateCallback((Runnable) () -> {});
        watcher.close();
        verify(topic).removeListener(42);
    }

    @Test
    public void close_before_registration_is_noop() {
        watcher.close();
        verify(topic, never()).removeListener(anyInt());
    }

    @Test
    public void set_update_callback_runnable_invokes_callback_on_message() {
        boolean[] called = {false};
        when(topic.addListener(eq(String.class), any())).thenAnswer(inv -> {
            // The MessageListener is the second argument; invoke it to simulate a Redis message
            org.redisson.api.listener.MessageListener<String> listener = inv.getArgument(1);
            listener.onMessage(null, "reload");
            return 1;
        });
        watcher.setUpdateCallback((Runnable) () -> called[0] = true);
        assert called[0];
    }
}
