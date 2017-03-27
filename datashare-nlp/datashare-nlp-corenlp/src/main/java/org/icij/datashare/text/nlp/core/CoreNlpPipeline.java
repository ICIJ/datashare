package org.icij.datashare.text.nlp.core;

import java.io.StringReader;
import java.util.*;
import java.util.function.BiPredicate;

import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.Triple;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.ie.AbstractSequenceClassifier;
import edu.stanford.nlp.tagger.maxent.MaxentTagger;
import edu.stanford.nlp.ling.CoreAnnotations.CharacterOffsetBeginAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.CharacterOffsetEndAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.NamedEntityTagAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.PartOfSpeechAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.SentencesAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TokensAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TextAnnotation;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.TaggedWord;

import org.icij.datashare.text.nlp.*;

import static java.util.Collections.singletonList;
import static org.icij.datashare.text.nlp.NlpStage.*;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.core.annotators.CoreNlpNerAnnotator;
import org.icij.datashare.text.nlp.core.annotators.CoreNlpPosAnnotator;
import org.icij.datashare.text.nlp.core.annotators.CoreNlpPipelineAnnotator;


/**
 * {@link NlpPipeline}
 * {@link AbstractNlpPipeline}
 * {@link Type#CORE}
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
public final class CoreNlpPipeline extends AbstractNlpPipeline {

    // Pipeline annotator (TOKEN, SENTENCE, POS, NER)
    private Map<Language, StanfordCoreNLP> pipeline;

    // POS annotator
    private Map<Language, MaxentTagger> posTagger;

    // NER annotator
    private Map<Language, AbstractSequenceClassifier<CoreLabel>> nerClassifier;


    public CoreNlpPipeline(final Properties properties) {
        super(properties);

        // TOKEN <-- SENTENCE <-- POS <-- LEMMA <-- NER
        stageDependencies.get(SENTENCE).add(TOKEN);
        stageDependencies.get(POS)     .add(SENTENCE);
        stageDependencies.get(LEMMA)   .add(POS);
        stageDependencies.get(NER)     .add(LEMMA);

        pipeline      = new HashMap<>();
        nerClassifier = new HashMap<>();
        posTagger     = new HashMap<>();
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public Map<Language, Set<NlpStage>> supportedStages() {
        return CoreNlpPipelineAnnotator.SUPPORTED_STAGES;
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
    protected Optional<Annotation> process(String input, String hash, Language language) {
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
        // (Don't) keep pipeline and models
        if ( ! caching) {
            pipeline.remove(language);
            posTagger.remove(language);
            nerClassifier.remove(language);
        }
    }


    private boolean initializePipelineAnnotator(Language language) {
        if (pipeline.containsKey(language))
            return true;
        Optional<StanfordCoreNLP> stanfordCoreNLP = CoreNlpPipelineAnnotator.INSTANCE.get(language);
        if ( ! stanfordCoreNLP.isPresent() ) {
            LOGGER.error(getClass().getName() + " FAILED to get PIPELINE annotator. Aborting...");
            return false;
        }
        pipeline.put(language, stanfordCoreNLP.get());
        return true;
    }

    /**
     * Process with entire pipeline
     *
     * @param input    the string to annotate
     * @param hash     the input hash code
     * @param language the input language
     * @return
     */
    private Optional<Annotation> processPipeline(String input, String hash, Language language) {
        Annotation annotation = new Annotation(hash, getType(), language);

        // CoreNLP annotation data-structure
        edu.stanford.nlp.pipeline.Annotation coreNlpAnnotation = new edu.stanford.nlp.pipeline.Annotation(input);

        LOGGER.info(getClass().getName() + " - SENTENCING ~ TOKENIZING ~ POS-TAGGING ~ NAME-FINDING for " + language.toString());

        // Sentencize input
        // Tokenize
        // Pos-tag
        // NER
        pipeline.get(language).annotate(coreNlpAnnotation);

        final BiPredicate<Optional<NamedEntity.Category>, Optional<NamedEntity.Category>>
                differentCategories = (c1, c2) -> ! c1.equals(c2);
        // Feed annotation
        List<CoreMap> sentences = coreNlpAnnotation.get(SentencesAnnotation.class);
        for (CoreMap sentence: sentences) {
            int sentenceBegin = sentence.get(CharacterOffsetBeginAnnotation.class);
            int sentenceEnd   = sentence.get(CharacterOffsetEndAnnotation.class);
            annotation.add(SENTENCE, sentenceBegin, sentenceEnd);

            int                            nerBegin   = 0;
            List<String>                   nerComps   = new ArrayList<>();
            List<String>                   nerPoSs    = new ArrayList<>();
            Optional<NamedEntity.Category> prevCat = Optional.empty();

            // Feed annotation with TOKEN, POS, NER
            List<CoreLabel> tokens = sentence.get(TokensAnnotation.class);
            for (CoreLabel token: tokens) {
                int    tokenBegin = token.get(CharacterOffsetBeginAnnotation.class);
                int    tokenEnd   = token.get(CharacterOffsetEndAnnotation.class);
                String pos        = token.get(PartOfSpeechAnnotation.class);
                annotation.add(TOKEN, tokenBegin, tokenEnd);
                annotation.add(POS, tokenBegin, tokenEnd, pos);

                // Current named entity category
                String word = token.get(TextAnnotation.class);
                String cat   = token.get(NamedEntityTagAnnotation.class);
                Optional<NamedEntity.Category> currCat = NamedEntity.Category.parse(cat);
                if (currCat.isPresent()) {
                    nerComps.add(word);
                    nerPoSs .add(pos);
                    if ( differentCategories.test(prevCat, currCat) ) {
                        nerBegin = tokenBegin;
                    }
                } else {
                    if ( differentCategories.test(prevCat, currCat) ) {
                        String               mention    = String.join(" ", nerComps);
                        String               mentionPos = String.join(" ", nerPoSs);
                        NamedEntity.Category category   = prevCat.orElse(NamedEntity.Category.UNKNOWN);
                        annotation.add(NER, nerBegin, tokenBegin, category.toString());
                        nerComps = new ArrayList<>();
                        nerPoSs  = new ArrayList<>();
                    }
                }
                // Update category
                prevCat = currCat;
            }
        }
        return Optional.of( annotation );
    }


    private boolean initializeNerAnnotator(Language language) {
        if (nerClassifier.containsKey(language))
            return true;

        Optional<AbstractSequenceClassifier<CoreLabel>> classifier = CoreNlpNerAnnotator.INSTANCE.get(language);
        if ( ! classifier.isPresent()) {
            LOGGER.error(getClass().getName() + " - FAILED INITIALIZING NER annotator. Aborting...");
            return false;
        }
        nerClassifier.put(language, classifier.get());
        return true;
    }

    /**
     * Named Entity Classifier (Conditional Random Fields) only
     *
     * @param input    the string to annotate
     * @param hash     the input hash code
     * @param language the input language
     */
    private Optional<Annotation> processNerClassifier(String input, String hash, Language language) {
        Annotation annotation = new Annotation(hash, getType(), language);

        LOGGER.info(getClass().getName() + " - NAME-FINDING for " + language.toString());
        // Recognize named entities from input
        List<Triple<String, Integer, Integer>> items = nerClassifier.get(language).classifyToCharacterOffsets(input);
        // For each recognized named entity
        for (Triple<String, Integer, Integer> item : items) {
            // Triple: <category, begin, end>
            NamedEntity.Category category = NamedEntity.Category.parse(item.first()).orElse(NamedEntity.Category.NONE);
            String mention = input.substring(item.second(), item.third());
            int    begin   = item.second();
            int    end     = item.third();
            annotation.add(NER, begin, end, category.toString());
        }
        return Optional.of( annotation );
    }


    private boolean initializePosAnnotator(Language language) {
        if (posTagger.containsKey(language))
            return true;

        Optional<MaxentTagger> tagger = CoreNlpPosAnnotator.INSTANCE.get(language);
        if ( ! tagger.isPresent()) {
            LOGGER.error(getClass().getName() + " - FAILED INITIALIZING POS annotator. Aborting...");
            return false;
        }
        posTagger.put(language, tagger.get());
        return true;
    }

    /**
     * Part-of-Speech Classification (Maximum entropy) only
     *
     * @param input    the string to annotate
     * @param hash     the input hash code
     * @param language the input language
     */
    private Optional<Annotation> processPosClassifier(String input, String hash, Language language) {
        Annotation annotation = new Annotation(hash, getType(), language);

        LOGGER.info(getClass().getName() + " - POS-TAGGING for " + language.toString());

        // Split input into sentences
        List<List<HasWord>> sentences = MaxentTagger.tokenizeText(new StringReader(input));
        for (List<HasWord> sentence : sentences) {
            // Tag with parts-of-speech
            List<TaggedWord> taggedSentence = posTagger.get(language).tagSentence(sentence);
            // Feed annotatopn
            for (TaggedWord word : taggedSentence) {
                int    begin = word.beginPosition();
                int    end   = word.endPosition();
                String pos   = word.tag();
                annotation.add(POS, begin, end, pos);
            }
        }
        return Optional.of( annotation );
    }

    @Override
    public Optional<String> getPosTagSet(Language language) {
        return Optional.of(CoreNlpPosAnnotator.POS_TAGSET.get(language));
    }

}
