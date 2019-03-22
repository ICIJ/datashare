package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Channel;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.com.ShutdownMessage;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;

import static java.lang.String.valueOf;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.text.nlp.Pipeline.Type.parseAll;

public class ResumeNlpTask implements Callable<Long>, UserTask {
    Logger logger = LoggerFactory.getLogger(getClass());
    private final Pipeline.Type[] nlpPipelines;
    private final User user;
    private String indexName;
    private final Publisher publisher;
    private final Indexer indexer;

    @Inject
    public ResumeNlpTask(final Publisher publisher, final Indexer indexer, final PropertiesProvider propertiesProvider, @Assisted final User user) {
        this.publisher = publisher;
        this.indexer = indexer;
        this.nlpPipelines = parseAll(propertiesProvider.get("nlpPipelines").orElse(""));
        this.user = user;
        this.indexName = propertiesProvider.get("indexName").orElse(user.indexName());
    }

    @Override
    public Long call() throws IOException {
        logger.info("resuming NLP name finding for index {} and {}", indexName, nlpPipelines);
        Indexer.Searcher searcher = indexer.search(indexName, Document.class).withSource("rootDocument").without(nlpPipelines);
        List<? extends Entity> docsToProcess = searcher.scroll().collect(toList());
        long totalHits = searcher.totalHits();
        this.publisher.publish(Channel.NLP, new Message(Message.Type.INIT_MONITORING).add(Message.Field.VALUE, valueOf(totalHits)));

        do {
            docsToProcess.forEach(doc -> this.publisher.publish(Channel.NLP,
                    new Message(Message.Type.EXTRACT_NLP)
                            .add(Message.Field.INDEX_NAME, indexName)
                            .add(Message.Field.DOC_ID, doc.getId())
                            .add(Message.Field.R_ID, ((Document) doc).getRootDocument())));
            docsToProcess = searcher.scroll().collect(toList());
        } while (docsToProcess.size() != 0);
        logger.info("sent {} message for {} files without {} pipeline tags", Message.Type.EXTRACT_NLP, totalHits, nlpPipelines);

        searcher.clearScroll();
        this.publisher.publish(Channel.NLP, new ShutdownMessage());

        return totalHits;
    }

    @Override
    public User getUser() { return user;}
}
