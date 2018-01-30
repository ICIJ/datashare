package org.icij.datashare.text.nlp;

import com.google.inject.Inject;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.redis.RedisSubscriber;
import org.icij.datashare.text.DatashareEventListener;
import org.icij.datashare.text.indexing.Indexer2;
import redis.clients.jedis.Jedis;

import static org.icij.datashare.com.Message.Field.DOC_ID;
import static org.icij.datashare.com.Message.Type.EXTRACT_NLP;

public class NLPDatashareEventListener implements DatashareEventListener {
    private final AbstractPipeline nlpPipeline;
    private final Indexer2 indexer;
    private final String busAddress;

    @Inject
    public NLPDatashareEventListener(PropertiesProvider provider, AbstractPipeline nlpPipeline, Indexer2 indexer) {
        this.nlpPipeline = nlpPipeline;
        this.indexer = indexer;
        String messageBusProperties = provider.getProperties().getProperty("messageBusProperties");
        busAddress = messageBusProperties == null ? "localhost": messageBusProperties;
    }

    @Override
    public void waitForEvents() {
         new RedisSubscriber(new Jedis(busAddress), message -> {
             if (message.type == EXTRACT_NLP) {
                 indexer.get(message.content.get(DOC_ID));
             }
             return null;
         }).run();
     }
}
