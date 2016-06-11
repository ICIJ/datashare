package org.icij.datashare.text.processing.corenlp;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.*;
import static java.util.Arrays.asList;
import static java.util.logging.Level.SEVERE;

import edu.stanford.nlp.ie.AbstractSequenceClassifier;
import edu.stanford.nlp.ie.crf.CRFClassifier;
import edu.stanford.nlp.ling.CoreAnnotations.CharacterOffsetBeginAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.NamedEntityTagAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.PartOfSpeechAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.SentencesAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TokensAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TextAnnotation;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.tagger.maxent.MaxentTagger;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.Triple;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;

import org.icij.datashare.text.Language;
import org.icij.datashare.text.processing.AbstractNLPPipeline;
import org.icij.datashare.text.processing.NLPStage;
import org.icij.datashare.text.processing.NamedEntity;
import org.icij.datashare.text.processing.NamedEntityCategory;

import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.processing.NLPStage.*;


/**
 * CoreNLP pipeline
 *
 * Created by julien on 3/24/16.
 */
public final class CoreNLPPipeline extends AbstractNLPPipeline {

    private static final String MODELS_BASEDIR = "edu/stanford/nlp/models";

    private static final HashMap<NLPStage, String> MODEL_PROP_KEY =
            new HashMap<NLPStage, String>(){{
                put(POS, "pos.model");
                put(NER, "ner.model");
            }};

    private static final Map<NLPStage, Path> MODEL_DIR =
            new HashMap<NLPStage, Path>(){{
                put(POS, Paths.get(MODELS_BASEDIR, "pos-tagger") );
                put(NER, Paths.get(MODELS_BASEDIR, "ner") );
            }};

    private static final Map<NLPStage, HashMap<Language, Path>> MODEL_PATH =
            new HashMap<NLPStage, HashMap<Language, Path>>(){{
                put(POS, new HashMap<Language, Path>() {{
                    put(ENGLISH, MODEL_DIR.get(POS).resolve(Paths.get("english-left3words", "english-left3words-distsim.tagger")));
                    put(SPANISH, MODEL_DIR.get(POS).resolve(Paths.get("spanish",            "spanish-distsim.tagger")));
                    put(FRENCH,  MODEL_DIR.get(POS).resolve(Paths.get("french",             "french.tagger")));
                    put(GERMAN,  MODEL_DIR.get(POS).resolve(Paths.get("german",             "german-hgc.tagger")));
                }});
                put(NER, new HashMap<Language, Path>() {{
                    put(ENGLISH, MODEL_DIR.get(NER).resolve("english.all.3class.distsim.crf.ser.gz"));
                    put(SPANISH, MODEL_DIR.get(NER).resolve("spanish.ancora.distsim.s512.crf.ser.gz"));
                    put(FRENCH,  MODEL_DIR.get(NER).resolve("english.all.3class.distsim.crf.ser.gz"));
                    put(GERMAN,  MODEL_DIR.get(NER).resolve("german.hgc_175m_600.crf.ser.gz"));
                }});
            }};

    private static final Map<NLPStage, String> STAGE_TO_ANNOTATOR_NAME =
            new HashMap<NLPStage, String>(){{
                put(SENTENCE, "ssplit");
                put(TOKEN,    "tokenize");
                put(LEMMA,    "lemma");
                put(POS,      "pos");
                put(NER,      "ner");
            }};

    private static String ANNOTATOR_NAME(NLPStage stage) {
        return STAGE_TO_ANNOTATOR_NAME.get(stage);
    }


    // Pipelines of CoreNLP annotators <Tokens; Sentence; PoS; NER>
    private Map<Language, StanfordCoreNLP> pipeline;

    // Named Entity Classifiers (Conditional Random Fields)
    private Map<Language, AbstractSequenceClassifier<CoreLabel>> nerClassifier;

    // Part-of-Speech Classifiers (Maximum entropy)
    private Map<Language, MaxentTagger> posTagger;


    public CoreNLPPipeline(final Properties properties) {
        super(properties);

        stageDependencies.get(SENTENCE).add(TOKEN);
        stageDependencies.get(POS)     .add(SENTENCE);
        stageDependencies.get(LEMMA)   .add(POS);
        stageDependencies.get(NER)     .add(LEMMA);

        supportedStages.get(ENGLISH).addAll(asList(SENTENCE, TOKEN, POS, LEMMA, NER));
        supportedStages.get(SPANISH).addAll(asList(SENTENCE, TOKEN, POS, LEMMA, NER));
        supportedStages.get(GERMAN) .addAll(asList(SENTENCE, TOKEN, POS, LEMMA, NER));
        supportedStages.get(FRENCH) .addAll(asList(SENTENCE, TOKEN, POS, LEMMA, NER));

        if (targetStages.isEmpty())
            targetStages = supportedStages.get(language);

        pipeline = new HashMap<>();
        nerClassifier = new HashMap<>();
        posTagger = new HashMap<>();
    }

    @Override
    protected boolean initialize() {
        if ( ! super.initialize())
            return false;

        if (targetStages.equals(Collections.singleton(NER)))
            return initializeNERClassifier();

        if (targetStages.equals(Collections.singleton(POS)))
            return initializePoSClassifier();

        return initializePipeline();
    }

    /**
     * Initialize full pipeline
     *
     * @return true if initialized properly; false otherwise
     */
    private boolean initializePipeline() {
        // Already loaded for language?
        if ( pipeline.containsKey(language))
            return true;

        Properties props = new Properties();
        // Set chain stages (CoreNLP annotators)
        props.setProperty("annotators", String.join(", ", getAnnotators()));
        props.setProperty("ner.useSUTime", "false");
        props.setProperty("ner.applyNumericClassifiers", "false");
        // Set model paths (stage- & language-specific)
        MODEL_PATH.keySet().stream()
                .filter(stages::contains)
                .forEach(s -> props.setProperty(MODEL_PROP_KEY.get(s), MODEL_PATH.get(s).get(language).toString()));
        // Build pipeline
        try {

            pipeline.put(language, new StanfordCoreNLP(props, true));

        } catch (Exception e) {
            LOGGER.log(SEVERE, "Failed to load pipeline", e);
            System.out.println(getAnnotators());
            System.out.println(props.getProperty(MODEL_PROP_KEY.get(POS)));
            return false;
        }
        return true;
    }

    /**
     * Initialize Named Entity Classifier (Conditional Random Fields) only
     *
     * @return  true if initialized properly; false otherwise
     */
    private boolean initializeNERClassifier()  {
        // Already loaded for language?
        if ( nerClassifier.containsKey(language) )
            return true;

        try {

            nerClassifier.put(language, CRFClassifier.getClassifier(MODEL_PATH.get(NER).get(language).toString()));

        } catch (ClassNotFoundException | IOException e) {
            LOGGER.log(SEVERE, "Failed to load NER classifier");
            return false;
        }
        return true;
    }

    /**
     * Initialize Part-of-Speech Classifier (Maximum Entropy) only
     *
     * @return true if initialized properly; false otherwise
     */
    private boolean initializePoSClassifier() {
        // Already loaded for language?
        if (posTagger.containsKey(language))
            return true;

        try {

            posTagger.put(language, new MaxentTagger(MODEL_PATH.get(POS).get(language).toString()));

        } catch (Exception e) {
            LOGGER.log(SEVERE, "Failed to load PoS classifier");
            return false;
        }
        return true;
    }


    @Override
    protected void process(String input) {
        // NER unique target stage? Process with NER classifier only then
        if (Collections.singleton(NER).equals(targetStages)) {
            processNERClassifier(input);
            return;
        }
        // POS unique target stage? Process with POS classifier only then
        if (Collections.singleton(POS).equals(targetStages)) {
            processNERClassifier(input);
            return;
        }
        // Split input into tokens
        // Group tokens into sentences
        // Tag tokens with their part-of-speech
        // Tag tokens with their recognized named entity category
        Annotation annotateDoc = new Annotation(input);
        pipeline.get(language).annotate(annotateDoc);

        // For each detected sentence
        List<List<String[]>> sentences = new ArrayList<>();
        for (CoreMap sentence: annotateDoc.get(SentencesAnnotation.class)) {

            Optional<NamedEntityCategory> prevCat = Optional.empty();
            List<String> mentionParts = new ArrayList<>();
            List<String> mentionPoSs = new ArrayList<>();
            int mentionOffset = 0;

            // For each detected token in sentence
            for (CoreLabel token: sentence.get(TokensAnnotation.class)) {
                int offset  = token.get(CharacterOffsetBeginAnnotation.class);
                String word = token.get(TextAnnotation.class);
                String pos  = token.get(PartOfSpeechAnnotation.class);
                String ne   = token.get(NamedEntityTagAnnotation.class);

                Optional<NamedEntityCategory> currCat = NamedEntityCategory.parse(ne);
                if ( ! prevCat.equals(currCat) ) {
                    if ( currCat.isPresent()) {
                        mentionParts.add(word);
                        mentionPoSs.add(pos);
                        mentionOffset = offset;
                    } else {
                        NamedEntityCategory category = prevCat.orElse(NamedEntityCategory.NONE);
                        String mention = String.join(" ", mentionParts);
                        String mentionPos = String.join(" ", mentionPoSs);
                        Optional<NamedEntity> optEntity = NamedEntity.create(category, mention, mentionOffset);
                        if (optEntity.isPresent()) {
                            NamedEntity entity = optEntity.get();
                            Optional.ofNullable(documentHash).ifPresent(entity::setDocument);
                            Optional.ofNullable(documentPath).ifPresent(entity::setDocumentPath);
                            entity.setExtractor(NLPPipelineType.CORENLP);
                            entity.setExtractorLanguage(language);
                            entity.setPartOfSpeech(mentionPos);
                            entities.add(entity);
                        }
                        mentionParts = new ArrayList<>();
                        mentionPoSs = new ArrayList<>();
                    }
                } else if ( currCat.isPresent()) {
                    mentionParts.add(word);
                    mentionPoSs.add(pos);
                }

                // Update category
                prevCat = currCat;
            }

        }

    }

    /**
     * Named Entity Classifier (Conditional Random Fields)
     *
     * @param input
     */
    private void processNERClassifier(String input) {
        // Recognize named entities within input (as List<Triple>: <category, begin, end>)
        List<Triple<String, Integer, Integer>> list = nerClassifier.get(language).classifyToCharacterOffsets(input);

        // For each detected named entity
        for (Triple<String, Integer, Integer> item : list) {
            NamedEntityCategory category = NamedEntityCategory.parse(item.first()).orElse(NamedEntityCategory.NONE);
            String  mention = input.substring(item.second(), item.third());
            int offset = item.second();

            Optional<NamedEntity> optEntity = NamedEntity.create(category, mention, offset);
            if (optEntity.isPresent()) {
                NamedEntity entity = optEntity.get();
                Optional.ofNullable(documentHash).ifPresent(entity::setDocument);
                Optional.ofNullable(documentPath).ifPresent(entity::setDocumentPath);
                entity.setExtractor(NLPPipelineType.CORENLP);
                entity.setExtractorLanguage(language);
                entities.add(entity);
            }
        }
    }

    /**
     * Part-of-Speech Classification (Maximum entropy)
     *
     * @param input
     */
    private void processPoSClassifier(String input) {
        String tagged = posTagger.get(language).tagString(input);
        System.out.println(tagged);
    }


    @Override
    protected void terminate() {
        super.terminate();
        // Don't keep models in memory (to GC)
        if ( ! annotatorsCaching) {
            pipeline.remove(language);
            nerClassifier.remove(language);
            posTagger.remove(language);
        }
    }

    private List<String> getAnnotators() {
        return getStages()
                .stream()
                .map(CoreNLPPipeline::ANNOTATOR_NAME)
                .collect(Collectors.toList());
    }

}
