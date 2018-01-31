package org.icij.datashare.text.nlp;

import com.google.inject.Inject;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.redis.RedisSubscriber;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import redis.clients.jedis.Jedis;

import static org.icij.datashare.com.Message.Field.DOC_ID;
import static org.icij.datashare.com.Message.Type.EXTRACT_NLP;

public class NLPDatashareEventListener implements DatashareEventListener {
    private final AbstractPipeline nlpPipeline;
    private final Indexer indexer;
    private final String busAddress;

    @Inject
    public NLPDatashareEventListener(PropertiesProvider provider, AbstractPipeline nlpPipeline, Indexer indexer) {
        this.nlpPipeline = nlpPipeline;
        this.indexer = indexer;
        String messageBusProperties = provider.getProperties().getProperty("messageBusProperties");
        busAddress = messageBusProperties == null ? "localhost": messageBusProperties;
    }

    @Override
    public void waitForEvents() {
         new RedisSubscriber(new Jedis(busAddress), message -> {
             onMessage(message);
             return null;
         }).run();
     }

    void onMessage(Message message) {
        if (message.type == EXTRACT_NLP) {
            Document doc = indexer.get(message.content.get(DOC_ID));

        }
    }
}
