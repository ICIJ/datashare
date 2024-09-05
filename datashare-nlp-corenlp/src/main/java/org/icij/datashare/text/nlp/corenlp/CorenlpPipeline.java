package org.icij.datashare.text.nlp.corenlp;

import static org.icij.datashare.text.nlp.corenlp.models.CoreNlpPipelineModels.SUPPORTED_LANGUAGES;

import com.google.inject.Inject;
import edu.stanford.nlp.pipeline.CoreDocument;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.util.Pair;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.NlpTag;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.text.nlp.corenlp.models.CoreNlpPipelineModels;


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
    public List<List<NlpTag>> processText(Stream<String> batch, Language language) throws InterruptedException {
        return processPipeline(batch, language);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void terminate(Language language) throws InterruptedException {
        super.terminate(language);
        // (Don't) keep pipelines and models
        if (!caching) {
            CoreNlpPipelineModels.getInstance().unload(language);
        }
    }

    @Override
    public Set<Language> supportedLanguages() {
        return SUPPORTED_LANGUAGES;
    }


    private boolean initializePipelineAnnotator(Language language) throws InterruptedException {
        CoreNlpPipelineModels.getInstance().get(language);
        return true;
    }

    private List<List<NlpTag>> processPipeline(Stream<String> batch, Language language) throws InterruptedException {
        final StanfordCoreNLP annotator = CoreNlpPipelineModels.getInstance().get(language);
        return batch.map(text -> {
            CoreDocument codeDoc = annotator.processToCoreDocument(text);
            return codeDoc.entityMentions().stream().map(e -> {
                NamedEntity.Category category = NamedEntity.Category.parse(e.entityType());
                Pair<Integer, Integer> offsets = e.charOffsets();
                return new NlpTag(offsets.first, offsets.first, category);
            }).toList();
        }).toList();
    }
}
