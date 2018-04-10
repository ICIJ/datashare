package org.icij.datashare.text.nlp;

import org.icij.datashare.com.Channel;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.redis.RedisSubscriber;
import org.icij.datashare.monitoring.Monitorable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.Long.parseLong;
import static org.icij.datashare.com.Message.Type.EXTRACT_NLP;
import static org.icij.datashare.com.Message.Type.INIT_MONITORING;

public class NlpForwarder implements DatashareListener,Monitorable {
    private final BlockingQueue<Message> messageQueue;
    private final Runnable subscribedCallback;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final String busAddress;
    private final AtomicLong totalToProcess = new AtomicLong(0);
    private final AtomicLong processed = new AtomicLong(0);

    NlpForwarder(Properties properties, BlockingQueue<Message> messageQueue, Runnable subscribedCallback) {
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
        if (message.type == INIT_MONITORING) {
            String stringValue = message.content.get(Message.Field.VALUE);
            logger.debug("init monitoring with value {}", stringValue);
            totalToProcess.set(parseLong(stringValue));
            processed.set(0);
        }
        if (message.type == EXTRACT_NLP) {
            logger.debug("forwarding message {} to message queue", message);
            if (messageQueue.offer(message)) {
                processed.incrementAndGet();
            } else {
                logger.warn("cannot offer message {} to queue, it must be reprocessed later", message);
            }
        }
    }

    private RedisSubscriber createRedisSubscriber() {
        return new RedisSubscriber(new Jedis(busAddress), this::onMessage, subscribedCallback);
    }

    public double getProgressRate() {
        return totalToProcess.get() == 0 ? -1 : (double)(processed.get() - messageQueue.size()) / totalToProcess.get();
    }
}
