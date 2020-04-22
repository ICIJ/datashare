package org.icij.datashare.text.nlp;

import com.google.inject.Inject;
import org.icij.datashare.com.Message;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.Indexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.icij.datashare.com.Message.Field.*;
import static org.icij.datashare.text.NamedEntity.allFrom;

public class NlpConsumer implements DatashareListener {
    private final Indexer indexer;
    private final BlockingQueue<Message> messageQueue;
    private final AbstractPipeline nlpPipeline;
    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Inject
    public NlpConsumer(AbstractPipeline pipeline, Indexer indexer, BlockingQueue<Message> messageQueue) {
        this.indexer = indexer;
        this.messageQueue = messageQueue;
        this.nlpPipeline = pipeline;
    }

    @Override
    public Integer call() {
        boolean exitAsked = false;
        int nbMessages = 0;
        while (! exitAsked) {
            try {
                Message message = messageQueue.poll(30, TimeUnit.SECONDS);
                if (message != null) {
                    switch (message.type) {
                        case EXTRACT_NLP:
                            findNamedEntities(message.content.get(INDEX_NAME), message.content.get(DOC_ID), message.content.get(R_ID));
                            nbMessages++;
                            break;
                        case SHUTDOWN:
                            exitAsked = true;
                            break;
                        default:
                            logger.info("ignore {}", message);
                    }
                    synchronized (messageQueue) {
                        if (messageQueue.isEmpty()) {
                            logger.debug("queue is empty notifying messageQueue {}", messageQueue.hashCode());
                            messageQueue.notify();
                        }
                    }
                }
            } catch (Throwable e) {
                logger.warn("error in consumer main loop", e);
            }
        }
        logger.info("exiting main loop");
        return nbMessages;
    }

    void findNamedEntities(final String projectName, final String id, final String routing) throws InterruptedException {
        try {
            Document doc = indexer.get(projectName, id, routing);
            if (doc != null) {
                logger.info("extracting {} entities for document {}", nlpPipeline.getType(), doc.getId());
                if (nlpPipeline.initialize(doc.getLanguage())) {
                    Annotations annotations = nlpPipeline.process(doc.getContent(), doc.getId(), doc.getLanguage());
                    List<NamedEntity> namedEntities = allFrom(doc.getContent(), annotations);
                    namedEntities.addAll(nlpPipeline.processHeaders(doc));
                    indexer.bulkAdd(projectName, nlpPipeline.getType(), namedEntities, doc);
                    logger.info("added {} named entities to document {}", namedEntities.size(), doc.getId());
                    nlpPipeline.terminate(doc.getLanguage());
                }
            } else {
                logger.warn("no document found in index with id " + id);
            }
        } catch (IOException e) {
            logger.error("cannot extract entities of doc " + id, e);
        }
    }
}
