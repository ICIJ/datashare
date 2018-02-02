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

import static org.icij.datashare.com.Message.Field.DOC_ID;
import static org.icij.datashare.com.Message.Type.EXTRACT_NLP;

public class NlpDatashareListener implements DatashareListener, Runnable {
    private Logger logger = LoggerFactory.getLogger(getClass());
    private final AbstractPipeline nlpPipeline;
    private final Indexer indexer;
    private final String busAddress;

    @Inject
    public NlpDatashareListener(PropertiesProvider provider, AbstractPipeline nlpPipeline, Indexer indexer) {
        this.nlpPipeline = nlpPipeline;
        this.indexer = indexer;
        String messageBusProperties = provider.getProperties().getProperty("messageBusProperties");
        busAddress = messageBusProperties == null ? "localhost": messageBusProperties;
    }

    @Override
    public void waitForEvents() {
        logger.info("waiting for messages on host [{}]", busAddress);
        new RedisSubscriber(new Jedis(busAddress), this::onMessage).subscribe(Channel.NLP).run();
     }

    Void onMessage(Message message) {
        if (message.type == EXTRACT_NLP) {
            String id = message.content.get(DOC_ID);
            Document doc = indexer.get(id);
            if (doc != null) {
                try {
                    nlpPipeline.initialize(doc.getLanguage());
                    Annotations annotations = nlpPipeline.process(doc.getContent(), doc.getId(), doc.getLanguage());
                    for (NamedEntity ne : NamedEntity.allFrom(doc, annotations)) {
                        indexer.add(ne);
                    }
                    nlpPipeline.terminate(doc.getLanguage());
                } catch (Throwable e) {
                    logger.error("cannot extract entities of doc " + doc.getId(), e);
                }
            } else {
                logger.warn("no document found in index with id " + id);
            }
        }
        return null;
    }

    @Override
    public void run() { waitForEvents();}
}
