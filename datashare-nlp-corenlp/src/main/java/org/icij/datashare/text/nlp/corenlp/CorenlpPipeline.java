package org.icij.datashare.text.nlp.corenlp;

import static org.icij.datashare.text.nlp.corenlp.models.CoreNlpModels.SUPPORTED_LANGUAGES;

import com.google.inject.Inject;
import edu.stanford.nlp.pipeline.CoreDocument;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import java.util.List;
import java.util.Set;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.function.ThrowingFunctions;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Hasher;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntitiesBuilder;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.text.nlp.corenlp.models.CoreNlpModels;


/**
 * {@link Pipeline}
 * {@link AbstractPipeline}
 * {@link Type#CORENLP}
 *
 * <a href="http://stanfordnlp.github.io/CoreNLP">Stanford CoreNLP</a>
 * Models v3.6.0:
 * <a href="http://nlp.stanford.edu/software/stanford-english-corenlp-2016-01-10-models.jar">English</a>,
 * <a href="http://nlp.stanford.edu/software/stanford-spanish-corenlp-2015-10-14-models.jar">Spanish</a>,
 * <a href="http://nlp.stanford.edu/software/stanford-german-2016-01-19-models.jar">German</a>,
 * <a href="http://nlp.stanford.edu/software/stanford-french-corenlp-2016-01-14-models.jar">French</a> (English used for NER)
 * Created by julien on 3/24/16.
 */
public final class CorenlpPipeline extends AbstractPipeline {
    @Inject
    public CorenlpPipeline(final PropertiesProvider propertiesProvider) {
        super(propertiesProvider.getProperties());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean initialize(Language language) throws InterruptedException {
        if (!super.initialize(language)) {
            return false;
        }
        return initializePipelineAnnotator(language);
    }

    @Override
    public List<NamedEntity> process(Document doc) throws InterruptedException {
        return process(doc, doc.getContentTextLength(), 0);
    }

    @Override
    public List<NamedEntity> process(Document doc, int contentLength, int contentOffset) throws InterruptedException {
        return processPipeline(doc, contentLength, contentOffset);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void terminate(Language language) throws InterruptedException {
        super.terminate(language);
        // (Don't) keep pipelines and models
        if (!caching) {
            CoreNlpModels.getInstance().unload(language);
        }
    }

    @Override
    public Set<Language> supportedLanguages() {
        return SUPPORTED_LANGUAGES;
    }


    private boolean initializePipelineAnnotator(Language language) throws InterruptedException {
        CoreNlpModels.getInstance().get(language);
        return true;
    }

    /**
     * Named Entity Classifier (Conditional Random Fields) only
     *
     * @param doc the document
     */
    private List<NamedEntity> processPipeline(Document doc, int contentLength, int contentOffset)
        throws InterruptedException {
        NamedEntitiesBuilder namedEntitiesBuilder =
            new NamedEntitiesBuilder(getType(), doc.getId(), doc.getLanguage()).withRoot(doc.getRootDocument());
        LOGGER.info("name-finding for {} in document {} (offset {})", doc.getLanguage(), Hasher.shorten(doc.getId(), 4),
            contentOffset);
        final StanfordCoreNLP annotator;
        annotator = CoreNlpModels.getInstance().get(doc.getLanguage());
        String text = doc.getContent()
            .substring(contentOffset, Math.min(contentOffset + contentLength, doc.getContentTextLength()));
        CoreDocument codeDoc = annotator.processToCoreDocument(text);
        codeDoc.entityMentions().forEach(e -> {
            NamedEntity.Category category = NamedEntity.Category.parse(e.entityType());
            String mention = ThrowingFunctions.removeNewLines.apply(e.text());
            namedEntitiesBuilder.add(category, mention, e.charOffsets().first + contentOffset);
        });
        return namedEntitiesBuilder.build();
    }
}
