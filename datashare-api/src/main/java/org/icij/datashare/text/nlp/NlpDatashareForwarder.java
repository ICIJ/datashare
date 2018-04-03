package org.icij.datashare.text.nlp;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Channel;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.redis.RedisSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.util.List;
import java.util.concurrent.BlockingQueue;

public class NlpDatashareForwarder implements DatashareListener {
    private final List<BlockingQueue<Message>> messageQueues;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    final String busAddress;

    NlpDatashareForwarder(PropertiesProvider provider, List<BlockingQueue<Message>> messageQueues) {
        this.messageQueues = messageQueues;
        String messageBusAddress = provider.getProperties().getProperty("messageBusAddress");
        busAddress = messageBusAddress == null ? "localhost": messageBusAddress;
    }

    @Override
    public void run() {
        logger.info("waiting for messages on host [{}]", busAddress);
        createRedisSubscriber().subscribe(Channel.NLP).run();
    }

    void onMessage(final Message message) {
        logger.debug("forwarding message {} to {} queue(s)", message, messageQueues.size());
        messageQueues.forEach(q -> {
            if (!q.offer(message)) {
                logger.warn("cannot offer message {} to queue, it must be reprocessed later", message);
            }
        });
    }

    RedisSubscriber createRedisSubscriber() {
        return new RedisSubscriber(new Jedis(busAddress), this::onMessage);
    }
}
