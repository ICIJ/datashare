package org.icij.datashare.text.nlp;

import org.icij.datashare.com.Channel;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.redis.RedisSubscriber;
import org.icij.datashare.text.indexing.Indexer;
import redis.clients.jedis.Jedis;

import java.util.Properties;

import static org.icij.datashare.com.Message.Field.DOC_ID;
import static org.icij.datashare.com.Message.Field.R_ID;
import static org.icij.datashare.com.Message.Type.EXTRACT_NLP;

public class NlpDatashareSubscriber extends NlpDatashareListener {
    protected final String busAddress;

    public NlpDatashareSubscriber(AbstractPipeline pipeline, Indexer indexer, Properties properties) {
        super(pipeline, indexer);
        String messageBusAddress = properties.getProperty("messageBusAddress");
        busAddress = messageBusAddress == null ? "localhost": messageBusAddress;
    }

    @Override
    public void run() {
        logger.info("waiting for messages on host [{}]", busAddress);
        createRedisSubscriber().subscribe(Channel.NLP).run();
     }

    void onMessage(Message message) {
        if (message.type == EXTRACT_NLP) {
            extractNamedEntities(message.content.get(DOC_ID), message.content.get(R_ID));
        }
    }

    RedisSubscriber createRedisSubscriber() {
        return new RedisSubscriber(new Jedis(busAddress), this::onMessage);
    }
}
