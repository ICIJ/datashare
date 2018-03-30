package org.icij.datashare.text.nlp;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.Indexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public abstract class NlpDatashareListener implements DatashareListener {

    protected final Logger logger;
    private final AbstractPipeline nlpPipeline;
    protected final Indexer indexer;

    NlpDatashareListener(AbstractPipeline nlpPipeline, Indexer indexer) {
        this.nlpPipeline = nlpPipeline;
        this.logger = LoggerFactory.getLogger(nlpPipeline.getClass());
        this.indexer = indexer;
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
