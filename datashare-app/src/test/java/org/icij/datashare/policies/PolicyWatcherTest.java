package org.icij.datashare.policies;

import org.junit.Before;
import org.junit.Test;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import org.mockito.ArgumentCaptor;

import static org.fest.assertions.Assertions.assertThat;
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
    public void update_publishes_reload_message_with_instance_prefix() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        watcher.update();
        verify(topic).publish(captor.capture());
        assertThat(captor.getValue()).endsWith(":reload");
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
    public void callback_is_invoked_for_message_from_another_instance() {
        boolean[] called = {false};
        when(topic.addListener(eq(String.class), any())).thenAnswer(inv -> {
            org.redisson.api.listener.MessageListener<String> listener = inv.getArgument(1);
            listener.onMessage(null, "other-instance-id:reload");
            return 1;
        });
        watcher.setUpdateCallback((Runnable) () -> called[0] = true);
        assertThat(called[0]).isTrue();
    }

    @Test
    public void callback_is_not_invoked_for_own_message() {
        boolean[] called = {false};
        ArgumentCaptor<String> publishCaptor = ArgumentCaptor.forClass(String.class);
        watcher.update();
        verify(topic).publish(publishCaptor.capture());
        String ownMessage = publishCaptor.getValue();

        when(topic.addListener(eq(String.class), any())).thenAnswer(inv -> {
            org.redisson.api.listener.MessageListener<String> listener = inv.getArgument(1);
            listener.onMessage(null, ownMessage);
            return 1;
        });
        watcher.setUpdateCallback((Runnable) () -> called[0] = true);
        assertThat(called[0]).isFalse();
    }
}
