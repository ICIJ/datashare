package org.icij.datashare.com.bus;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.icij.datashare.PropertiesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * AmpInterlocutor has the responsibility for creating connections and publish channels.
 * <p>
 * It keeps tracks of the publish channels and closes them when close() is called.
 * </p>
 * Consumer channels are kept inside AbstractConsumer and closed by the
 */
public class AmqpInterlocutor {
    private static final Logger logger = LoggerFactory.getLogger(AmqpInterlocutor.class);
    private static AmqpInterlocutor instance;
    final Configuration configuration;
    private final ConnectionFactory connectionFactory;
    private final Connection connection;
    private final ConcurrentHashMap<AmqpQueue, AmqpChannel> publishChannels = new ConcurrentHashMap<>();

    public static AmqpInterlocutor getInstance() throws IOException {
        if (instance == null)
            synchronized(logger) { instance = new AmqpInterlocutor(new Configuration(new PropertiesProvider().getProperties())); }
        return instance;
    }

    static AmqpInterlocutor initWith(Configuration configuration) throws IOException {
        if (instance == null) {
            synchronized (logger) {
                instance = new AmqpInterlocutor(configuration);
            }
        }
        return instance;
    }

    private AmqpInterlocutor(Configuration configuration) throws IOException {
        this.configuration = configuration;
        this.connectionFactory = createConnectionFactory(configuration);
        this.connection = createConnection();
    }

    Connection createConnection() throws IOException {
        try {
            logger.info("Trying to connect AMQP on " + configuration.host + ":" + configuration.port + "...");
            Connection connection = connectionFactory.newConnection();
            logger.info("...connection to AMQP created");
            return connection;
        } catch (TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void close() throws IOException, TimeoutException {
        closeChannelsAndConnection();
    }

    public void publish(AmqpQueue queue, Event event) throws IOException {
        getChannel(queue).publish(event);
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

    synchronized Channel createChannel() throws IOException {
        return connection.createChannel();
    }

    public synchronized AmqpChannel createAmqpChannelForPublish(AmqpQueue queue) throws IOException {
        return createChannelAndDeclareQueue(queue, false);
    }
    public synchronized AmqpChannel createAmqpChannelForConsume(AmqpQueue queue) throws IOException {
        return createChannelAndDeclareQueue(queue, true);
    }

    private AmqpChannel createChannelAndDeclareQueue(AmqpQueue queue, boolean forConsumer) throws IOException {
        logger.info("create channel and declare queue " + queue);
        Channel channel = createChannel();
        boolean durable = true;
        boolean exclusive = false;
        boolean autoDelete = false;
        Map<String, Object> queueParameters = new HashMap<>() {{ if (queue.deadLetterQueue != null) put("x-dead-letter-exchange", queue.deadLetterQueue.exchange);}};
        channel.exchangeDeclare(queue.exchange, queue.exchangeType, durable);
        channel.queueDeclare(queue.name(), durable, exclusive, autoDelete, queueParameters);
        channel.queueBind(queue.name(), queue.exchange, queue.routingKey);
        channel.basicQos(configuration.nbMaxMessages);
        AmqpChannel amqpChannel = new AmqpChannel(channel, queue);
        if (!forConsumer) {
            publishChannels.put(queue, amqpChannel);
        }
        logger.info("channel " + channel + " has been created for queue {}", queue.name());
        return amqpChannel;
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

    void closeChannelsAndConnection() throws IOException, TimeoutException {
        for (AmqpChannel channel : publishChannels.values()) {
            if (channel.rabbitMqChannel.isOpen()) {
                channel.rabbitMqChannel.close();
                logger.info("channel " + channel + " was open it has been closed");
            }
        }
        if (connection.isOpen()) {
            connection.close();
            logger.info("closing connection to {}:{}", configuration.host, configuration.port);
        }
    }

    private static class UnknownChannelException extends RuntimeException {
        public UnknownChannelException(AmqpQueue queue) {
            super("Unknown channel for queue " + queue);
        }
    }
}
