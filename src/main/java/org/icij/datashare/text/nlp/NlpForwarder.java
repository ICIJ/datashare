package org.icij.datashare.text.nlp;

import org.icij.datashare.com.Channel;
import org.icij.datashare.com.DataBus;
import org.icij.datashare.com.Message;
import org.icij.datashare.monitoring.Monitorable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.Long.parseLong;
import static org.icij.datashare.com.Message.Type.EXTRACT_NLP;
import static org.icij.datashare.com.Message.Type.INIT_MONITORING;

public class NlpForwarder implements DatashareListener,Monitorable {
    private final DataBus dataBus;
    private final BlockingQueue<Message> messageQueue;
    private final Runnable subscribedCallback;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final AtomicLong totalToProcess = new AtomicLong(0);
    private final AtomicLong processed = new AtomicLong(0);

    NlpForwarder(DataBus dataBus, BlockingQueue<Message> messageQueue, Runnable subscribedCallback) {
        this.dataBus = dataBus;
        this.messageQueue = messageQueue;
        this.subscribedCallback = subscribedCallback;
    }

    @Override
    public Integer call() throws InterruptedException {
        return dataBus.subscribe(this::onMessage, subscribedCallback, Channel.NLP);
    }

    void onMessage(final Message message) {
        if (message.type == INIT_MONITORING) {
            String stringValue = message.content.get(Message.Field.VALUE);
            logger.debug("init monitoring with value {}", stringValue);
            totalToProcess.getAndAdd(parseLong(stringValue));
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

    public double getProgressRate() {
        return totalToProcess.get() == 0 ? -1 : (double)(processed.get() - messageQueue.size()) / totalToProcess.get();
    }
}
