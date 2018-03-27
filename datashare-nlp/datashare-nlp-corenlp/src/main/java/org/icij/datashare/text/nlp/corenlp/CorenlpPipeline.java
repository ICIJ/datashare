package org.icij.datashare.text.nlp.corenlp;

import com.google.inject.Inject;
import edu.stanford.nlp.ie.AbstractSequenceClassifier;
import edu.stanford.nlp.ling.CoreAnnotations.*;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.TaggedWord;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.tagger.maxent.MaxentTagger;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.Triple;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.Annotations;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.text.nlp.corenlp.models.CoreNlpAnnotator;
import org.icij.datashare.text.nlp.corenlp.models.CoreNlpNerModels;
import org.icij.datashare.text.nlp.corenlp.models.CoreNlpPipelineModels;
import org.icij.datashare.text.nlp.corenlp.models.CoreNlpPosModels;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Collections.singletonList;
import static org.icij.datashare.text.nlp.NlpStage.*;


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
 *
 * Created by julien on 3/24/16.
 */
public final class CorenlpPipeline extends AbstractPipeline {
    @Inject
    public CorenlpPipeline(final PropertiesProvider propertiesProvider) {
        super(propertiesProvider.getProperties());

        // TOKEN <-- SENTENCE <-- POS <-- LEMMA <-- NER
        stageDependencies.get(SENTENCE).add(TOKEN);
        stageDependencies.get(POS)     .add(SENTENCE);
        stageDependencies.get(LEMMA)   .add(POS);
        stageDependencies.get(NER)     .add(LEMMA);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<Language, Set<NlpStage>> supportedStages() {
        return CoreNlpPipelineModels.SUPPORTED_STAGES;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean initialize(Language language) {
        if( ! super.initialize(language) )
            return false;

        if (singletonList(NER).equals(targetStages))
            return initializeNerAnnotator(language);

        if (singletonList(POS).equals(targetStages))
            return initializePosAnnotator(language);

        return initializePipelineAnnotator(language);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Annotations process(String input, String hash, Language language) {
        // Is NER the unique target stage?
        if (singletonList(NER).equals(targetStages))
            return processNerClassifier(input, hash, language);

        // Is POS the unique target stage?
        if (singletonList(POS).equals(targetStages))
            return processPosClassifier(input, hash, language);

        // Otherwise
        return processPipeline(input, hash, language);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void terminate(Language language) {
        super.terminate(language);
        // (Don't) keep pipelines and models
        if ( ! caching) {
            CoreNlpPipelineModels.getInstance().unload(language);
            CoreNlpNerModels.getInstance().unload(language);
            CoreNlpPosModels.getInstance().unload(language);
        }
    }


    private boolean initializePipelineAnnotator(Language language) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Optional<StanfordCoreNLP> stanfordCoreNLP = CoreNlpPipelineModels.getInstance().get(language, classLoader);
        if ( ! stanfordCoreNLP.isPresent() ) {
            LOGGER.error("failed to get pipelines annotator. Aborting...");
            return false;
        }
        return true;
    }

    /**
     * Process with entire pipelines
     *
     * @param input    the string to annotator
     * @param hash     the input hash code
     * @param language the input language
     * @return
     */
    private Annotations processPipeline(String input, String hash, Language language) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Annotations annotations = new Annotations(hash, getType(), language);

        // CoreNLP annotations data-structure
        edu.stanford.nlp.pipeline.Annotation coreNlpAnnotation = new edu.stanford.nlp.pipeline.Annotation(input);

        LOGGER.info("sentencing ~ tokenizing ~ POS-tagging ~ name-finding for " + language.toString());

        // Sentencize input
        // Tokenize
        // Pos-tag
        // NER
        CoreNlpPipelineModels.getInstance().get(language, classLoader).
                ifPresent(stanfordCoreNLP1 -> stanfordCoreNLP1.annotate(coreNlpAnnotation));

        // Feed annotations
        List<CoreMap> sentences = coreNlpAnnotation.get(SentencesAnnotation.class);
        for (CoreMap sentence: sentences) {
            int sentenceBegin = sentence.get(CharacterOffsetBeginAnnotation.class);
            int sentenceEnd   = sentence.get(CharacterOffsetEndAnnotation.class);
            annotations.add(SENTENCE, sentenceBegin, sentenceEnd);

            int                            nerBegin   = 0;
            NamedEntity.Category prevCat = NamedEntity.Category.NONE;

            List<CoreLabel> tokens = sentence.get(TokensAnnotation.class);
            for (CoreLabel token: tokens) {
                int    tokenBegin = token.get(CharacterOffsetBeginAnnotation.class);
                int    tokenEnd   = token.get(CharacterOffsetEndAnnotation.class);
                String pos        = token.get(PartOfSpeechAnnotation.class);
                annotations.add(TOKEN, tokenBegin, tokenEnd);
                annotations.add(POS, tokenBegin, tokenEnd, pos);

                String cat   = token.get(NamedEntityTagAnnotation.class);
                NamedEntity.Category currCat = NamedEntity.Category.parse(cat);
                if (currCat != NamedEntity.Category.NONE) {
                    if ( prevCat != currCat ) {
                        nerBegin = tokenBegin;
                    }
                } else {
                    if ( prevCat != currCat ) {
                        annotations.add(NER, nerBegin, tokenBegin, prevCat.toString());
                    }
                }
                prevCat = currCat;
            }
        }
        return annotations;
    }


    private boolean initializeNerAnnotator(Language language) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Optional<CoreNlpAnnotator<AbstractSequenceClassifier<CoreLabel>>> classifier =
                CoreNlpNerModels.getInstance().get(language, classLoader);
        if ( ! classifier.isPresent()) {
            LOGGER.error("failed initializing NER annotator. Aborting...");
            return false;
        }
        return true;
    }

    /**
     * Named Entity Classifier (Conditional Random Fields) only
     *
     * @param input    the string to annotator
     * @param hash     the input hash code
     * @param language the input language
     */
    private Annotations processNerClassifier(String input, String hash, Language language) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Annotations annotations = new Annotations(hash, getType(), language);

        LOGGER.info("name-finding for " + language.toString());
        // Recognize named entities from input
        final Optional<CoreNlpAnnotator<AbstractSequenceClassifier<CoreLabel>>> abstractSequenceClassifierCoreNlpAnnotator =
                CoreNlpNerModels.getInstance().get(language, classLoader);
        if (abstractSequenceClassifierCoreNlpAnnotator.isPresent()) {
            List<Triple<String, Integer, Integer>> items = abstractSequenceClassifierCoreNlpAnnotator.get().annotator.classifyToCharacterOffsets(input);
            // For each recognized named entity
            for (Triple<String, Integer, Integer> item : items) {
                // Triple: <category, begin, end>
                NamedEntity.Category category = NamedEntity.Category.parse(item.first());
                int begin = item.second();
                int end = item.third();
                annotations.add(NER, begin, end, category.toString());
            }
        }
        return annotations;
    }


    private boolean initializePosAnnotator(Language language) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Optional<CoreNlpAnnotator<MaxentTagger>> tagger = CoreNlpPosModels.getInstance().get(language, classLoader);
        if ( ! tagger.isPresent()) {
            LOGGER.error("failed initializing POS annotator. Aborting...");
            return false;
        }
        return true;
    }

    /**
     * Part-of-Speech Classification (Maximum entropy) only
     *
     * @param input    the string to annotator
     * @param hash     the input hash code
     * @param language the input language
     */
    private Annotations processPosClassifier(String input, String hash, Language language) {
        Annotations annotations = new Annotations(hash, getType(), language);
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        LOGGER.info("POS-tagging for " + language.toString());

        // Split input into sentences
        final Optional<CoreNlpAnnotator<MaxentTagger>> nlpAnnotator = CoreNlpPosModels.getInstance().get(language, classLoader);
        if (nlpAnnotator.isPresent()) {

            List<List<HasWord>> sentences = MaxentTagger.tokenizeText(new StringReader(input));
            for (List<HasWord> sentence : sentences) {
                // Tag with parts-of-speech
                List<TaggedWord> taggedSentence = nlpAnnotator.get().annotator.tagSentence(sentence);
                // Feed annotatopn
                for (TaggedWord word : taggedSentence) {
                    int begin = word.beginPosition();
                    int end = word.endPosition();
                    String pos = word.tag();
                    annotations.add(POS, begin, end, pos);
                }
            }
        }
        return annotations;
    }

    @Override
    public Optional<String> getPosTagSet(Language language) {
        return Optional.of(CoreNlpPosModels.POS_TAGSET.get(language));
    }

}
