package org.icij.datashare.asynctasks.bus.amqp;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.fest.assertions.Fail;
import org.junit.ClassRule;
import org.junit.Test;

import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import static org.fest.assertions.Assertions.assertThat;

public class AmqpServerRuleTest {
    @ClassRule static public AmqpServerRule qpid = new AmqpServerRule(12345);

    @Test
    public void test_amqp_client_connection() throws Exception {
        assertAmqpIsUp(true);
    }

    @Test
    public void test_restart_server() throws Exception {
        System.out.println(Thread.getAllStackTraces().keySet());
        assertThat(qpid.amqpServer.shutdown()).isTrue();
        assertAmqpIsUp(false);
        qpid.amqpServer.start();
        assertAmqpIsUp(true);
    }

    private static void assertAmqpIsUp(boolean shouldBeUp) throws URISyntaxException, NoSuchAlgorithmException, KeyManagementException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri("amqp://admin:admin@localhost:12345");
        try {
            Connection connection = factory.newConnection();
            if (!shouldBeUp) {
                Fail.fail("should not be able to connect broker");
            }
            connection.close();
        } catch (Exception e) {
            if (shouldBeUp) {
                Fail.fail("cannot connect broker", e);
            }
        }
    }
}