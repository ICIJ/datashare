package org.icij.datashare.text.nlp.corenlp;

import com.google.inject.Inject;
import edu.stanford.nlp.ie.AbstractSequenceClassifier;
import edu.stanford.nlp.ling.CoreAnnotations.*;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.TaggedWord;
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
    public boolean initialize(Language language) throws InterruptedException {
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
    public Annotations process(String content, String docId, Language language) throws InterruptedException {
        // Is NER the unique target stage?
        if (singletonList(NER).equals(targetStages))
            return processNerClassifier(content, docId, language);

        // Is POS the unique target stage?
        if (singletonList(POS).equals(targetStages))
            return processPosClassifier(content, docId, language);

        // Otherwise
        return processPipeline(content, docId, language);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void terminate(Language language) throws InterruptedException {
        super.terminate(language);
        // (Don't) keep pipelines and models
        if ( ! caching) {
            CoreNlpPipelineModels.getInstance().unload(language);
            CoreNlpNerModels.getInstance().unload(language);
            CoreNlpPosModels.getInstance().unload(language);
        }
    }


    private boolean initializePipelineAnnotator(Language language) throws InterruptedException {
        CoreNlpPipelineModels.getInstance().get(language);
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
    private Annotations processPipeline(String input, String hash, Language language) throws InterruptedException {
        Annotations annotations = new Annotations(hash, getType(), language);

        // CoreNLP annotations data-structure
        edu.stanford.nlp.pipeline.Annotation coreNlpAnnotation = new edu.stanford.nlp.pipeline.Annotation(input);

        LOGGER.info("sentencing ~ tokenizing ~ POS-tagging ~ name-finding for " + language.toString());

        // Sentencize input
        // Tokenize
        // Pos-tag
        // NER
        CoreNlpPipelineModels.getInstance().get(language).annotate(coreNlpAnnotation);
        // Feed annotations
        List<CoreMap> sentences = coreNlpAnnotation.get(SentencesAnnotation.class);
        for (CoreMap sentence : sentences) {
            int sentenceBegin = sentence.get(CharacterOffsetBeginAnnotation.class);
            int sentenceEnd = sentence.get(CharacterOffsetEndAnnotation.class);
            annotations.add(SENTENCE, sentenceBegin, sentenceEnd);

            int nerBegin = 0;
            NamedEntity.Category prevCat = NamedEntity.Category.NONE;

            List<CoreLabel> tokens = sentence.get(TokensAnnotation.class);
            for (CoreLabel token : tokens) {
                int tokenBegin = token.get(CharacterOffsetBeginAnnotation.class);
                int tokenEnd = token.get(CharacterOffsetEndAnnotation.class);
                String pos = token.get(PartOfSpeechAnnotation.class); // for now we don't use POS tagging
                annotations.add(TOKEN, tokenBegin, tokenEnd);
                annotations.add(POS, tokenBegin, tokenEnd);

                String cat = token.get(NamedEntityTagAnnotation.class);
                NamedEntity.Category currCat = NamedEntity.Category.parse(cat);
                if (currCat != NamedEntity.Category.NONE) {
                    if (prevCat != currCat) {
                        nerBegin = tokenBegin;
                    }
                } else {
                    if (prevCat != currCat) {
                        annotations.add(NER, nerBegin, tokenBegin, prevCat);
                    }
                }
                prevCat = currCat;
            }
        }
        return annotations;
    }


    private boolean initializeNerAnnotator(Language language) throws InterruptedException {
        CoreNlpNerModels.getInstance().get(language);
        return true;
    }

    /**
     * Named Entity Classifier (Conditional Random Fields) only
     *
     * @param input    the string to annotator
     * @param hash     the input hash code
     * @param language the input language
     */
    private Annotations processNerClassifier(String input, String hash, Language language) throws InterruptedException {
        Annotations annotations = new Annotations(hash, getType(), language);

        LOGGER.info("name-finding for " + language.toString());
        // Recognize named entities from input
        final CoreNlpAnnotator<AbstractSequenceClassifier<CoreLabel>> abstractSequenceClassifierCoreNlpAnnotator;
        abstractSequenceClassifierCoreNlpAnnotator = CoreNlpNerModels.getInstance().get(language);
        List<Triple<String, Integer, Integer>> items = abstractSequenceClassifierCoreNlpAnnotator.annotator.classifyToCharacterOffsets(input);
        // For each recognized named entity
        for (Triple<String, Integer, Integer> item : items) {
            // Triple: <category, begin, end>
            NamedEntity.Category category = NamedEntity.Category.parse(item.first());
            int begin = item.second();
            int end = item.third();
            annotations.add(NER, begin, end, category);
        }

        return annotations;
    }


    private boolean initializePosAnnotator(Language language) {
        try {
            CoreNlpPosModels.getInstance().get(language);
        } catch (InterruptedException e) {
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
    private Annotations processPosClassifier(String input, String hash, Language language) throws InterruptedException {
        Annotations annotations = new Annotations(hash, getType(), language);
        LOGGER.info("POS-tagging for " + language.toString());

        // Split input into sentences
        final CoreNlpAnnotator<MaxentTagger> nlpAnnotator;
        nlpAnnotator = CoreNlpPosModels.getInstance().get(language);
        List<List<HasWord>> sentences = MaxentTagger.tokenizeText(new StringReader(input));
        for (List<HasWord> sentence : sentences) {
            // NlpTag with parts-of-speech
            List<TaggedWord> taggedSentence = nlpAnnotator.annotator.tagSentence(sentence);
            // Feed annotatopn
            for (TaggedWord word : taggedSentence) {
                int begin = word.beginPosition();
                int end = word.endPosition();
                String pos = word.tag(); // like line 157 we don't use POS tagging
                annotations.add(POS, begin, end);
            }
        }
        return annotations;
    }

    @Override
    public Optional<String> getPosTagSet(Language language) {
        return Optional.of(CoreNlpPosModels.POS_TAGSET.get(language));
    }

}
