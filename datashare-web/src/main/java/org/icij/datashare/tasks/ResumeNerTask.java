package org.icij.datashare.tasks;

import com.google.inject.Inject;
import org.icij.datashare.Entity;
import org.icij.datashare.com.Channel;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Callable;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.text.Document.Status.INDEXED;

public class ResumeNerTask implements Callable<Integer> {
    Logger logger = LoggerFactory.getLogger(getClass());
    private final Publisher publisher;
    private final Indexer indexer;

    @Inject
    public ResumeNerTask(final Publisher publisher, final Indexer indexer) {
        this.publisher = publisher;
        this.indexer = indexer;
    }

    @Override
    public Integer call() {
        List<? extends Entity> indexedDocs =
                indexer.search(Document.class).withSource("parentDocument").ofStatus(INDEXED).execute().collect(toList());
        indexedDocs.forEach(doc -> this.publisher.publish(Channel.NLP,
                        new Message(Message.Type.EXTRACT_NLP)
                                .add(Message.Field.DOC_ID, doc.getId())
                                .add(Message.Field.P_ID, ofNullable(((Document)doc).getParentDocument()).orElse(doc.getId()))));
        logger.info("sent {} message for {} {} files", Message.Type.EXTRACT_NLP, indexedDocs.size(), INDEXED);
        return indexedDocs.size();
    }
}
