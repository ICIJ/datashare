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
    public void update_publishes_message_with_caller_id() {
        ArgumentCaptor<PolicyUpdateMessage> captor = ArgumentCaptor.forClass(PolicyUpdateMessage.class);
        watcher.update();
        verify(topic).publish(captor.capture());
        assertThat(captor.getValue().callerId()).isNotEmpty();
        assertThat(captor.getValue().message()).isEqualTo("reload");
    }

    @Test
    public void set_update_callback_runnable_registers_listener() {
        when(topic.addListener(eq(PolicyUpdateMessage.class), any())).thenReturn(1);
        watcher.setUpdateCallback((Runnable) () -> {});
        verify(topic).addListener(eq(PolicyUpdateMessage.class), any());
    }

    @Test
    public void set_update_callback_consumer_registers_listener() {
        when(topic.addListener(eq(PolicyUpdateMessage.class), any())).thenReturn(1);
        watcher.setUpdateCallback(msg -> {});
        verify(topic).addListener(eq(PolicyUpdateMessage.class), any());
    }

    @Test
    public void close_removes_listener_after_registration() {
        when(topic.addListener(eq(PolicyUpdateMessage.class), any())).thenReturn(42);
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
        when(topic.addListener(eq(PolicyUpdateMessage.class), any())).thenAnswer(inv -> {
            org.redisson.api.listener.MessageListener<PolicyUpdateMessage> listener = inv.getArgument(1);
            listener.onMessage(null, new PolicyUpdateMessage("other-instance-id", "reload"));
            return 1;
        });
        watcher.setUpdateCallback((Runnable) () -> called[0] = true);
        assertThat(called[0]).isTrue();
    }

    @Test
    public void callback_is_invoked_when_caller_id_is_prefixed_extension_of_own_id() {
        // Guard against the old startsWith-based check: a callerId that merely starts
        // with our instanceId is NOT our message and must trigger the callback.
        boolean[] called = {false};
        ArgumentCaptor<PolicyUpdateMessage> publishCaptor = ArgumentCaptor.forClass(PolicyUpdateMessage.class);
        watcher.update();
        verify(topic).publish(publishCaptor.capture());
        String extendedCallerId = publishCaptor.getValue().callerId() + "-other";

        when(topic.addListener(eq(PolicyUpdateMessage.class), any())).thenAnswer(inv -> {
            org.redisson.api.listener.MessageListener<PolicyUpdateMessage> listener = inv.getArgument(1);
            listener.onMessage(null, new PolicyUpdateMessage(extendedCallerId, "reload"));
            return 1;
        });
        watcher.setUpdateCallback((Runnable) () -> called[0] = true);
        assertThat(called[0]).isTrue();
    }

    @Test
    public void callback_is_not_invoked_for_own_message() {
        boolean[] called = {false};
        ArgumentCaptor<PolicyUpdateMessage> publishCaptor = ArgumentCaptor.forClass(PolicyUpdateMessage.class);
        watcher.update();
        verify(topic).publish(publishCaptor.capture());
        PolicyUpdateMessage ownMessage = publishCaptor.getValue();

        when(topic.addListener(eq(PolicyUpdateMessage.class), any())).thenAnswer(inv -> {
            org.redisson.api.listener.MessageListener<PolicyUpdateMessage> listener = inv.getArgument(1);
            listener.onMessage(null, ownMessage);
            return 1;
        });
        watcher.setUpdateCallback((Runnable) () -> called[0] = true);
        assertThat(called[0]).isFalse();
    }
}
