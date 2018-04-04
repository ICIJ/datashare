package org.icij.datashare.text.nlp;

import com.google.inject.Inject;
import org.icij.datashare.com.Message;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.Indexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.icij.datashare.com.Message.Field.DOC_ID;
import static org.icij.datashare.com.Message.Field.R_ID;

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

    void extractNamedEntities(final String id, final String routing) {
        try {
            Document doc = indexer.get(id, routing);
            if (doc != null) {
                logger.info("extracting {} entities for document {}", nlpPipeline.getType(), doc.getId());
                if (nlpPipeline.initialize(doc.getLanguage())) {
                    Annotations annotations = nlpPipeline.process(doc.getContent(), doc.getId(), doc.getLanguage());
                    List<NamedEntity> namedEntities = NamedEntity.allFrom(doc, annotations);
                    indexer.bulkAdd(nlpPipeline.getType(), namedEntities, doc);
                    logger.info("added {} named entities to document {}", namedEntities.size(), doc.getId());
                    nlpPipeline.terminate(doc.getLanguage());
                }
            } else {
                logger.warn("no document found in index with id " + id);
            }
        } catch (Throwable e) {
            logger.error("cannot extract entities of doc " + id, e);
        }
    }
}
