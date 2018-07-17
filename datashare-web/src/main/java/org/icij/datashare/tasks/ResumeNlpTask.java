package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.Entity;
import org.icij.datashare.user.User;
import org.icij.datashare.com.Channel;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.com.ShutdownMessage;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.UserTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;

import static java.lang.String.valueOf;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.text.nlp.Pipeline.Type.parseAll;

public class ResumeNlpTask implements Callable<Integer>, UserTask {
    private final static int SEARCH_SIZE = 10000;
    Logger logger = LoggerFactory.getLogger(getClass());
    private final Pipeline.Type[] nlpPipelines;
    private final User user;
    private final Publisher publisher;
    private final Indexer indexer;

    @Inject
    public ResumeNlpTask(final Publisher publisher, final Indexer indexer, @Assisted final String nlpPipelines, @Assisted final User user) {
        this.publisher = publisher;
        this.indexer = indexer;
        this.nlpPipelines = parseAll(nlpPipelines);
        this.user = user;
    }

    @Override
    public Integer call() throws IOException {
        List<? extends Entity> docsToProcess =
                indexer.search(user.indexName(), Document.class).withSource("rootDocument").limit(SEARCH_SIZE).without(nlpPipelines).execute().collect(toList());

        this.publisher.publish(Channel.NLP, new Message(Message.Type.INIT_MONITORING).add(Message.Field.VALUE, valueOf(docsToProcess.size())));

        docsToProcess.forEach(doc -> this.publisher.publish(Channel.NLP,
                        new Message(Message.Type.EXTRACT_NLP)
                                .add(Message.Field.USER_ID, user.id)
                                .add(Message.Field.DOC_ID, doc.getId())
                                .add(Message.Field.R_ID, ((Document)doc).getRootDocument())));

        this.publisher.publish(Channel.NLP, new ShutdownMessage());

        logger.info("sent {} message for {} files without {} pipeline tags", Message.Type.EXTRACT_NLP, docsToProcess.size(), nlpPipelines);
        return docsToProcess.size();
    }

    @Override
    public User getUser() {
        return user;
    }
}
