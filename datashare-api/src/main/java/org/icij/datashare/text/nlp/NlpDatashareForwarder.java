package org.icij.datashare.text.nlp;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Channel;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.redis.RedisSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import javax.inject.Inject;
import java.util.concurrent.BlockingQueue;

public class NlpDatashareForwarder implements DatashareListener {
    private final BlockingQueue<Message> messageQueue;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    final String busAddress;

    @Inject
    NlpDatashareForwarder(PropertiesProvider provider, BlockingQueue<Message> messageQueue) {
        this.messageQueue = messageQueue;
        String messageBusAddress = provider.getProperties().getProperty("messageBusAddress");
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

    RedisSubscriber createRedisSubscriber() {
        return new RedisSubscriber(new Jedis(busAddress), this::onMessage);
    }
}
