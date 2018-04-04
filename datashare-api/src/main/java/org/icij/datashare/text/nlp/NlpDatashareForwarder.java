package org.icij.datashare.text.nlp;

import org.icij.datashare.com.Channel;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.redis.RedisSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.util.Properties;
import java.util.concurrent.BlockingQueue;

public class NlpDatashareForwarder implements DatashareListener {
    private final BlockingQueue<Message> messageQueue;
    private final Runnable subscribedCallback;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    final String busAddress;

    NlpDatashareForwarder(Properties properties, BlockingQueue<Message> messageQueue, Runnable subscribedCallback) {
        this.messageQueue = messageQueue;
        this.subscribedCallback = subscribedCallback;
        String messageBusAddress = properties.getProperty("messageBusAddress");
        busAddress = messageBusAddress == null ? "localhost": messageBusAddress;
    }

    @Override
    public void run() {
        logger.info("waiting for messages on host [{}]", busAddress);
        createRedisSubscriber().subscribe(Channel.NLP).run();
    }

    void onMessage(final Message message) {
        logger.debug("forwarding message {} to message queue", message);
        if (!messageQueue.offer(message)) {
            logger.warn("cannot offer message {} to queue, it must be reprocessed later", message);
        }
    }

    private RedisSubscriber createRedisSubscriber() {
        return new RedisSubscriber(new Jedis(busAddress), this::onMessage, subscribedCallback);
    }
}
