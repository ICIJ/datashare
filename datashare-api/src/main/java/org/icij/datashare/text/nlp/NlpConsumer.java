package org.icij.datashare.text.nlp;

import com.google.inject.Inject;
import org.icij.datashare.com.Message;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.Indexer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableSet;
import static java.util.stream.Collectors.joining;
import static org.icij.datashare.com.Message.Field.*;
import static org.icij.datashare.text.NamedEntity.allFrom;

/**
 * Consumer for name finding waiting for messages to come
 *
 * if the pipeline is Email extractor and the document is an rfc822 email
 * then we also parse a list of headers coming from https://tools.ietf.org/html/rfc2076
 * and transformed by tika/extract (for the keys).
 *
 * These fields are supposed to contain email addresses that we want to
 * save as named entities.
 *
 */
public class NlpConsumer implements DatashareListener {
    private static final String DEFAULT_METADATA_FIELD_PREFIX = "tika_metadata_";
    private static final String RAW_HEADER_FIELD_PREFIX = "Message-Raw-Header-";
    private static final String MESSAGE_FIELD_PREFIX = "Message-";
    private final Indexer indexer;
    private final BlockingQueue<Message> messageQueue;
    private final AbstractPipeline nlpPipeline;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Set<String> parsedEmailHeaders = unmodifiableSet(new HashSet<>(asList(
            tika("Dc-Title"),
            tika("Dc-Creator"),
            tika("Creator"),
            tika("Author"),
            tika("Meta-Author"),

            tikaMsgHeader("To"),
            tikaMsgHeader("From"),
            tikaMsgHeader("Cc"),
            tikaMsgHeader("Bcc"),

            tikaRawHeader("Return-Path"),
            tikaRawHeader("Delivered-To"),
            tikaRawHeader("Reply-To"),
            tikaRawHeader("Followup-To"),
            tikaRawHeader("Alternate-Recipient"),
            tikaRawHeader("For-Handling"),
            tikaRawHeader("Resent-Reply-To"),
            tikaRawHeader("Resent-Sender"),
            tikaRawHeader("Resent-From"),
            tikaRawHeader("Resent-To"),
            tikaRawHeader("Resent-cc"),
            tikaRawHeader("Resent-bcc")
    )));

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
                            findNamedEntities(message.content.get(INDEX_NAME), message.content.get(DOC_ID), message.content.get(R_ID));
                            break;
                        case SHUTDOWN:
                            exitAsked = true;
                            break;
                        default:
                            logger.info("ignore {}", message);
                    }
                    synchronized (messageQueue) {
                        if (messageQueue.isEmpty()) {
                            messageQueue.notify();
                        }
                    }
                }
            } catch (Throwable e) {
                logger.warn("error in consumer main loop", e);
            }
        }
        logger.info("exiting main loop");
    }

    void findNamedEntities(final String projectName, final String id, final String routing) throws InterruptedException {
        try {
            Document doc = indexer.get(projectName, id, routing);
            if (doc != null) {
                logger.info("extracting {} entities for document {}", nlpPipeline.getType(), doc.getId());
                if (nlpPipeline.initialize(doc.getLanguage())) {
                    Annotations annotations = nlpPipeline.process(doc.getContent(), doc.getId(), doc.getLanguage());
                    List<NamedEntity> namedEntities = allFrom(doc.getContent(), annotations);
                    if (Pipeline.Type.EMAIL.equals(nlpPipeline.getType()) && "message/rfc822".equals(doc.getContentType())) {
                        namedEntities.addAll(parseEmailMetadata(doc));
                    }
                    indexer.bulkAdd(projectName, nlpPipeline.getType(), namedEntities, doc);
                    logger.info("added {} named entities to document {}", namedEntities.size(), doc.getId());
                    nlpPipeline.terminate(doc.getLanguage());
                }
            } else {
                logger.warn("no document found in index with id " + id);
            }
        } catch (IOException e) {
            logger.error("cannot extract entities of doc " + id, e);
        }
    }

    @NotNull
    private List<NamedEntity> parseEmailMetadata(Document doc) throws InterruptedException {
        String metadataString = parsedEmailHeaders.stream().map(key -> doc.getMetadata().getOrDefault(key, "").toString()).collect(joining(" "));
        Annotations metaDataAnnotations = nlpPipeline.process(metadataString, doc.getId(), doc.getLanguage());
        return allFrom(metadataString, metaDataAnnotations).stream().map(ne ->
                NamedEntity.create(ne.getCategory(), ne.getMention(), -1,
                        ne.getDocumentId(), ne.getRootDocument(), ne.getExtractor(),
                        ne.getExtractorLanguage())).collect(Collectors.toList());
    }

    static String tikaRawHeader(String s) {
        return tika(RAW_HEADER_FIELD_PREFIX + s);
    }

    static String tikaMsgHeader(String s) {
        return tika(MESSAGE_FIELD_PREFIX + s);
    }

    static String tika(String s) {
        return DEFAULT_METADATA_FIELD_PREFIX + s.toLowerCase().replace("-", "_");
    }
}
