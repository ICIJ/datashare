package org.icij.datashare.asynctasks.bus.amqp;

import org.junit.Test;

import java.util.UUID;

import static org.fest.assertions.Assertions.assertThat;

public class AmqpChannelTest {
    @Test
    public void test_get_hostname() {
        String hostname = AmqpChannel.getHostname();
        assertThat(hostname).isNotNull();
    }

    @Test
    public void test_get_hostname_error_returns_uuid() {
        String hostname = AmqpChannel.getHostname("hostname -unknown-option");
        assertThat(hostname).isNotNull();
        assertThat(isUuid(hostname)).isTrue();
    }

    boolean isUuid(String uuid) {
        try {
            UUID.fromString(uuid);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}