package org.icij.datashare.com.bus;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.json.JsonObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;


public class AmqpInterlocutor {
    private static final Logger logger = LoggerFactory.getLogger(AmqpInterlocutor.class);
    private static AmqpInterlocutor instance;
    final Configuration configuration;
    private final ConnectionFactory connectionFactory;
    private final Connection connection;
    private final ConcurrentHashMap<AmqpQueue, AmqpChannel> channels = new ConcurrentHashMap<>();

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
        AmqpChannel channel = getChannel(queue);
        channel.rabbitMqChannel.basicPublish(queue.exchange, queue.routingKey, null, event.serialize());
    }


    Event deserialize(byte[] body) throws IOException {
        return JsonObjectMapper.MAPPER.readValue(JsonObjectMapper.MAPPER.writeValueAsString(body), Event.class);
    }

    AmqpChannel getChannel(AmqpQueue queue) {
        if (queue == null) {
            throw new UnknownChannelException(queue);
        }
        AmqpChannel channel = channels.get(queue);
        if (channel == null) {
            throw new UnknownChannelException(queue);
        }
        return channel;
    }

    synchronized Channel createChannel() throws IOException {
        return connection.createChannel();
    }

    public synchronized AmqpInterlocutor createAmqpChannel(AmqpQueue queue) throws IOException, TimeoutException {
        createChannelAndDeclareQueue(queue);
        // republishErrorEvents(queue);
        return this;
    }

    private void createChannelAndDeclareQueue(AmqpQueue queue) throws IOException, TimeoutException {
        logger.info("create channel and declare queue " + queue);
        Channel channel = createChannel();
        boolean durable = true;
        boolean exclusive = false;
        boolean autoDelete = false;
        String exchangeType = "direct";
        Map<String, Object> queueParameters = new HashMap<>() {{ if (queue.deadLetterQueue != null) put("x-dead-letter-exchange", queue.deadLetterQueue.exchange);}};
        channel.exchangeDeclare(queue.exchange, exchangeType, durable);
        channel.queueDeclare(queue.name(), durable, exclusive, autoDelete, queueParameters);
        channel.queueBind(queue.name(), queue.exchange, queue.routingKey);
        channel.basicQos(configuration.nbMaxMessages);
        channels.put(queue, new AmqpChannel(channel, queue));
        logger.info("channel " + channel + " has been created for queue {}", queue.name());
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
        for (AmqpChannel channel : channels.values()) {
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
