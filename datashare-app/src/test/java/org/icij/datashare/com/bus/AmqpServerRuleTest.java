package org.icij.datashare.com.bus;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.icij.datashare.com.bus.AmqpServerRule;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;

public class AmqpServerRuleTest {
    @ClassRule static public AmqpServerRule qpid = new AmqpServerRule(12345);

    @Test
    public void test_amqp_client_connection() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri("amqp://admin:admin@localhost:12345");
        try {
            Connection connection = factory.newConnection();
            connection.close();
        } catch (Exception e) {
            Assert.fail(e.getMessage());
        }
    }
}