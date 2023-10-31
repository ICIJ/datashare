package org.icij.datashare.nlp;

import com.google.inject.Inject;
import org.icij.datashare.com.Message;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.DatashareListener;
import org.icij.datashare.text.nlp.Pipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.icij.datashare.com.Message.Field.*;

public class NlpConsumer implements DatashareListener {
    private static final int DEFAULT_MAX_CONTENT_LENGTH = 1024 * 1024;
    private final Indexer indexer;
    private final int maxContentLengthChars;
    private final BlockingQueue<Message> messageQueue;
    private final Pipeline nlpPipeline;
    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Inject
    public NlpConsumer(Pipeline pipeline, Indexer indexer, BlockingQueue<Message> messageQueue) {
        this.indexer = indexer;
        this.messageQueue = messageQueue;
        this.nlpPipeline = pipeline;
        this.maxContentLengthChars = DEFAULT_MAX_CONTENT_LENGTH;
    }

    NlpConsumer(Pipeline pipeline, Indexer indexer, int maxContentLengthBytes) {
        this.indexer = indexer;
        this.messageQueue = null;
        this.nlpPipeline = pipeline;
        this.maxContentLengthChars = maxContentLengthBytes;
    }

    @Override
    public Integer call() {
        boolean exitAsked = false;
        int nbMessages = 0;
        while (! exitAsked) {
            try {
                assert messageQueue != null;
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
                    int nbEntities = 0;
                    if (doc.getContent().length() < this.maxContentLengthChars) {
                        List<NamedEntity> namedEntities = nlpPipeline.process(doc);
                        indexer.bulkAdd(projectName, nlpPipeline.getType(), namedEntities, doc);
                        nbEntities = namedEntities.size();
                    } else {
                        int nbChunks = doc.getContent().length() / this.maxContentLengthChars + 1;
                        logger.info("document is too large, extracting entities for {} document chunks", nbChunks);
                        for (int chunkIndex = 0; chunkIndex < nbChunks; chunkIndex++) {
                            List<NamedEntity> namedEntities = nlpPipeline.process(doc, maxContentLengthChars, chunkIndex * maxContentLengthChars);
                            if (chunkIndex < nbChunks - 1) {
                                indexer.bulkAdd(projectName, namedEntities);
                            } else {
                                indexer.bulkAdd(projectName, nlpPipeline.getType(), namedEntities, doc);
                            }
                            nbEntities += namedEntities.size();
                        }
                    }
                    logger.info("added {} named entities to document {}", nbEntities, doc.getId());
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
