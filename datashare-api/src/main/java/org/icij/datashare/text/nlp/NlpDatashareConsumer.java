package org.icij.datashare.text.nlp;

import com.google.inject.Inject;
import org.icij.datashare.com.Message;
import org.icij.datashare.text.indexing.Indexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.icij.datashare.com.Message.Field.DOC_ID;
import static org.icij.datashare.com.Message.Field.R_ID;

public class NlpDatashareConsumer extends NlpDatashareListener {
    private final BlockingQueue<Message> messageQueue;
    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Inject
    public NlpDatashareConsumer(AbstractPipeline pipeline, Indexer indexer, BlockingQueue<Message> messageQueue) {
        super(pipeline, indexer);
        this.messageQueue = messageQueue;
    }

    @Override
    public void run() {
        boolean exitAsked = false;
        while (! exitAsked) {
            try {
                Message message = messageQueue.poll(30, TimeUnit.SECONDS);
                if (message != null) {
                    switch (message.type) {
                        case EXTRACT_NLP:
                            extractNamedEntities(message.content.get(DOC_ID), message.content.get(R_ID));
                            break;
                        case SHUTDOWN:
                            exitAsked = true;
                            break;
                        default:
                            logger.warn("cannot handle {}", message);
                    }
                }
            } catch (InterruptedException e) {
                logger.warn("poll interrupted", e);
            }
        }
        logger.info("exiting main loop");
    }
}
