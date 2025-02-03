package org.icij.datashare.asynctasks.bus.amqp;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.icij.datashare.PropertiesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * AmpInterlocutor has the responsibility for creating a connection and publish channels.
 * There *must* be only one instance of this class in each app as one connection per app
 * is enough: <a href="https://stackoverflow.com/questions/40627474/how-many-connections-to-maintain-in-rabbitmq">see this SO post</a>
 * <p>
 * It keeps tracks of the publish channels and closes them when close() is called.
 * </p>
 * Consumer channels are kept inside AbstractConsumer and closed by each consumer.
 */
public class AmqpInterlocutor implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(AmqpInterlocutor.class);
    final Configuration configuration;
    private final Connection connection;
    private final ConcurrentHashMap<AmqpQueue, AmqpChannel> publishChannels = new ConcurrentHashMap<>();


    public AmqpInterlocutor(Configuration configuration, AmqpQueue[] queues) throws IOException {
        this.configuration = configuration;
        ConnectionFactory connectionFactory = createConnectionFactory(configuration);
        this.connection = createConnection(connectionFactory);
        createPublishChannels(queues);
    }

    public AmqpInterlocutor(PropertiesProvider propertiesProvider, AmqpQueue[] queues) throws IOException, URISyntaxException {
        this(new Configuration(new URI(propertiesProvider.get("messageBusAddress").orElse("amqp://rabbitmq:5672"))), queues);

    }

    public AmqpInterlocutor(PropertiesProvider propertiesProvider) throws IOException, URISyntaxException {
        this(propertiesProvider, AmqpQueue.values());
    }

    Connection createConnection(ConnectionFactory connectionFactory) throws IOException {
        try {
            logger.info("Trying to connect AMQP on {}:{}...", configuration.host, configuration.port);
            Connection connection = connectionFactory.newConnection();
            logger.info("...connection to AMQP created");
            return connection;
        } catch (TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void close() throws IOException {
        closeChannelsAndConnection();
    }

    public void publish(AmqpQueue queue, Event event) throws IOException {
        getChannel(queue).publish(event);
    }

    public void publish(AmqpQueue amqpQueue, String key, Event event) throws IOException {
        getChannel(amqpQueue).publish(event, key);
    }

    AmqpChannel getChannel(AmqpQueue queue) {
        if (queue == null) {
            throw new UnknownChannelException(queue);
        }
        AmqpChannel channel = publishChannels.get(queue);
        if (channel == null) {
            throw new UnknownChannelException(queue);
        }
        return channel;
    }

    AmqpInterlocutor createPublishChannels(AmqpQueue... amqpQueues) {
        for (AmqpQueue queue: amqpQueues) {
            try {
                createAmqpChannelForPublish(queue);
            } catch (IOException e) {
                logger.error("cannot create channel for publish for queue {}", queue, e);
            }
        }
        return this;
    }

    synchronized AmqpInterlocutor createAmqpChannelForPublish(AmqpQueue queue) throws IOException {
        if (queue != AmqpQueue.MONITORING || hasMonitoringQueue()) {
            AmqpChannel channel = new AmqpChannel(connection.createChannel(), queue);
            if (queue == AmqpQueue.MONITORING) {
                channel.initForConsume(configuration.rabbitMq, configuration.nbMaxMessages); // for creating a queue
            } else {
                channel.initForPublish();
            }
            publishChannels.put(queue, channel);
            logger.info("publish channel {} has been created for exchange {}", channel, queue.exchange);
        }
        return this;
    }

    AmqpChannel createAmqpChannelForConsume(AmqpQueue queue, String key) throws IOException {
        AmqpChannel channel = new AmqpChannel(connection.createChannel(), queue, key);
        channel.initForConsume(configuration.rabbitMq, configuration.nbMaxMessages);
        logger.info("consume channel {} has been created for queue {}", channel, channel.queueName(AmqpChannel.WORKER_PREFIX));
        return channel;
    }

    private ConnectionFactory createConnectionFactory(Configuration configuration) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUsername(configuration.user);
        factory.setPassword(configuration.password);
        factory.setVirtualHost("/");
        factory.setHost(configuration.host);
        factory.setPort(configuration.port);
        factory.setRequestedHeartbeat(60);
        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(configuration.connectionRecoveryDelay);
        return factory;
    }

    void closeChannelsAndConnection() throws IOException {
        for (AmqpChannel channel : publishChannels.values()) {
            channel.close();
        }
        if (connection.isOpen()) {
            connection.close();
            logger.info("closing connection to {}:{}", configuration.host, configuration.port);
        }
    }

    public void deleteQueues(AmqpQueue... amqpQueues) throws IOException {
        try (Channel channel = connection.createChannel()) {
            for (AmqpQueue queue : amqpQueues) {
                channel.queueDelete(queue.name());
            }
        } catch (TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    private static class UnknownChannelException extends RuntimeException {
        public UnknownChannelException(AmqpQueue queue) {
            super("Unknown channel for queue " + queue);
        }
    }
    public boolean hasMonitoringQueue() {return configuration.monitoring;}
    public boolean isConnectionOpen() {return connection.isOpen();}
}
