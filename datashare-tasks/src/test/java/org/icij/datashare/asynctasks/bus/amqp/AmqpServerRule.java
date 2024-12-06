package org.icij.datashare.asynctasks.bus.amqp;

import com.rabbitmq.client.ConnectionFactory;
import org.junit.rules.ExternalResource;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

public class AmqpServerRule extends ExternalResource {
    public final QpidAmqpServer amqpServer;
    public  AmqpServerRule(int port) {
        amqpServer = new QpidAmqpServer(port,  15672, "qpid-config-test.json");
    }

    @Override
    protected void before() throws Throwable {
        amqpServer.start();
        waitForQpid();
    }

    @Override
    protected void after() {
        amqpServer.shutdown();
    }

    protected void waitForQpid() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(String.format("amqp://admin:admin@localhost:%s", System.getProperty("qpid.amqp_port")));
        for(int nbTries = 10; nbTries > 0 ; nbTries--) {
            try {
                factory.newConnection();
                return;
            } catch (ConnectException e) {
                Thread.sleep(500); // ms
            }
        }
        throw new TimeoutException("Connection to Qpid failed");
    }

    public void waitCancel(AmqpConsumer<?, ?> consumer) throws InterruptedException {
        while(!consumer.isCanceled()) {
            Thread.sleep(100);
        }
    }
}
