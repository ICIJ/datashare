package org.icij.datashare.text.processing.corenlp;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.*;
import static java.util.logging.Level.INFO;

import edu.stanford.nlp.ling.CoreAnnotations.NamedEntityTagAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.PartOfSpeechAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.SentencesAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TextAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TokensAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.BeforeAnnotation;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.util.CoreMap;
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

    private static final Map<NLPStage, Path> MODELS_DIR =
            new HashMap<NLPStage, Path>(){{
                put(POS, Paths.get(MODELS_BASEDIR, "pos-tagger") );
                put(NER, Paths.get(MODELS_BASEDIR, "ner") );
            }};

    private static final String MODEL_PROP_KEY_POS = "pos.model";
    private static final Map<Language, Path> MODELS_PATH_POS =
            new HashMap<Language, Path>(){{
                put(ENGLISH, MODELS_DIR.get(POS).resolve(Paths.get("english-left3words", "english-left3words-distsim.tagger")));
                put(SPANISH, MODELS_DIR.get(POS).resolve(Paths.get("spanish",            "spanish-distsim.tagger")));
                put(FRENCH,  MODELS_DIR.get(POS).resolve(Paths.get("french",             "french.tagger")));
                put(GERMAN,  MODELS_DIR.get(POS).resolve(Paths.get("german",             "german-hgc.tagger")));
            }};

    private static final String MODEL_PROP_KEY_NER = "ner.model";
    private static final Map<Language, Path> MODELS_PATH_NER =
            new HashMap<Language, Path>(){{
                put(ENGLISH, MODELS_DIR.get(NER).resolve("english.all.3class.distsim.crf.ser.gz"));
                put(SPANISH, MODELS_DIR.get(NER).resolve("spanish.ancora.distsim.s512.crf.ser.gz"));
                put(GERMAN,  MODELS_DIR.get(NER).resolve("german.hgc_175m_600.crf.ser.gz"));
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


    // Pipelines of CORENLP annotators (Tokens, Sentence, PoS, NER)
    private Map<Language, StanfordCoreNLP> pipeline;


    public CoreNLPPipeline(final Properties properties) {
        super(properties);

        stageDependencies.get(SENTENCE).add(TOKEN);
        stageDependencies.get(POS)     .add(SENTENCE);
        stageDependencies.get(LEMMA)   .add(POS);
        stageDependencies.get(NER)     .add(LEMMA);

        supportedStages.get(ENGLISH).addAll(Arrays.asList(SENTENCE, TOKEN, POS, LEMMA, NER));
        supportedStages.get(SPANISH).addAll(Arrays.asList(SENTENCE, TOKEN, POS, LEMMA, NER));
        supportedStages.get(GERMAN) .addAll(Arrays.asList(SENTENCE, TOKEN, POS, LEMMA, NER));
        supportedStages.get(FRENCH) .addAll(Arrays.asList(SENTENCE, TOKEN, POS, LEMMA));
        if (targetStages.isEmpty())
            targetStages = supportedStages.get(language);

        pipeline = new HashMap<>();
    }

    @Override
    protected boolean initialize() {
        if ( ! super.initialize())
            return false;
        // Load stage- & language-specific models, w.r.t. to props
        if ( ! pipeline.containsKey(language)) {
            Properties props = new Properties();
            // Set chain stages (CoreNLP annotators)
            props.setProperty("annotators", String.join(", ", getAnnotators()));
            props.setProperty("ner.useSUTime", "false");
            // Set stage-specific properties
            if (stages.contains(POS))
                props.setProperty(MODEL_PROP_KEY_POS, MODELS_PATH_POS.get(language).toString());
            if (stages.contains(NER))
                props.setProperty(MODEL_PROP_KEY_NER, MODELS_PATH_NER.get(language).toString());
            pipeline.put(language, new StanfordCoreNLP(props, true));
        }
        return true;
    }

    @Override
    protected void process(String input) {
        // Split input into tokens
        // Group tokens into sentences
        // Tag tokens with their part-of-speech
        // Tag tokens with their recognized named entity category
        Annotation annotateDoc = new Annotation(input);
        pipeline.get(language).annotate(annotateDoc);

        Optional<String> docHash = (document != null) ? document.getHash()              : Optional.empty();
        Optional<Path>   docPath = (document != null) ? Optional.of(document.getPath()) : Optional.empty();

        // Distance to beginning of document in chars
        int offset = 0;

        // For each detected sentence
        List<List<String[]>> sentences = new ArrayList<>();
        for (CoreMap sentence: annotateDoc.get(SentencesAnnotation.class)) {

            Optional<NamedEntityCategory> prevCat = Optional.empty();
            List<String> mentionParts = new ArrayList<>();
            int mentionOffset = 0;

            // For each detected token in sentence
            for (CoreLabel token: sentence.get(TokensAnnotation.class)) {
                String word = token.get(TextAnnotation.class);
                String pos  = token.get(PartOfSpeechAnnotation.class);
                String ne   = token.get(NamedEntityTagAnnotation.class);
                String wsb  = token.get(BeforeAnnotation.class);

                // Add whitespaces preceding current token to offset
                offset += (wsb.isEmpty() ? 0 : 1);

                Optional<NamedEntityCategory> currCat = NamedEntityCategory.parse(ne);
                if ( ! prevCat.equals(currCat) ) {
                    if ( currCat.isPresent()) {
                        mentionParts.add(word);
                        mentionOffset = offset;
                    } else {
                        NamedEntityCategory category = prevCat.orElse(NamedEntityCategory.NONE);
                        String mention = String.join(" ", mentionParts);
                        Optional<NamedEntity> optEntity = NamedEntity.create(category, mention, mentionOffset);
                        if (optEntity.isPresent()) {
                            NamedEntity entity = optEntity.get();
                            docHash.ifPresent(entity::setDocument);
                            docPath.ifPresent(entity::setDocumentPath);
                            entity.setExtractor(NLPPipelineType.CORENLP);
                            entities.add(entity);
                        }
                        mentionParts = new ArrayList<>();
                    }
                } else if ( currCat.isPresent()) {
                    mentionParts.add(word);
                }

                prevCat = currCat;
                // Update offset
                offset += word.length();
            }
        }

    }

    @Override
    protected void terminate() {
        super.terminate();
        // Don't keep models in memory (to GC)
        if ( ! annotatorsCaching)
            pipeline.remove(language);
    }

    private List<String> getAnnotators() {
        return getStages()
                .stream()
                .map(CoreNLPPipeline::ANNOTATOR_NAME)
                .collect(Collectors.toList());
    }

}
