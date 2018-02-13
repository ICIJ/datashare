package org.icij.datashare.text.nlp;

import com.google.inject.Inject;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Channel;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.redis.RedisSubscriber;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.Indexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.util.List;

import static org.icij.datashare.com.Message.Field.DOC_ID;
import static org.icij.datashare.com.Message.Type.EXTRACT_NLP;

public class NlpDatashareListener implements DatashareListener {
    private final Logger logger;
    private final AbstractPipeline nlpPipeline;
    private final Indexer indexer;
    private final String busAddress;

    @Inject
    public NlpDatashareListener(PropertiesProvider provider, AbstractPipeline nlpPipeline, Indexer indexer) {
        this.logger = LoggerFactory.getLogger(nlpPipeline.getClass());
        this.nlpPipeline = nlpPipeline;
        this.indexer = indexer;
        String messageBusAddress = provider.getProperties().getProperty("messageBusAddress");
        busAddress = messageBusAddress == null ? "localhost": messageBusAddress;
    }

    @Override
    public void waitForEvents() {
        logger.info("waiting for messages on host [{}]", busAddress);
        new RedisSubscriber(new Jedis(busAddress), this::onMessage).subscribe(Channel.NLP).run();
     }

    void onMessage(Message message) {
        if (message.type == EXTRACT_NLP) {
            extractNamedEntities(message.content.get(DOC_ID));
        }
    }

    private void extractNamedEntities(String id) {
        try {
            Document doc = indexer.get(id);
            if (doc != null) {
                logger.info("{} extracting entities for document {}", nlpPipeline.getType(), doc.getId());
                if (nlpPipeline.initialize(doc.getLanguage())) {
                    Annotations annotations = nlpPipeline.process(doc.getContent(), doc.getId(), doc.getLanguage());
                    List<NamedEntity> namedEntities = NamedEntity.allFrom(doc, annotations);
                    for (NamedEntity ne : namedEntities) {
                        indexer.add(ne);
                    }
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
