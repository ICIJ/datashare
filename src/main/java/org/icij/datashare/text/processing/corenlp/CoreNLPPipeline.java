package org.icij.datashare.text.processing.corenlp;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.*;
import java.util.logging.Logger;
import static java.util.logging.Level.INFO;

import edu.stanford.nlp.ling.CoreAnnotations.NamedEntityTagAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.PartOfSpeechAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.SentencesAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TextAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TokensAnnotation;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;

import org.icij.datashare.text.Language;
import org.icij.datashare.text.processing.AbstractNLPPipeline;
import org.icij.datashare.text.processing.NLPStage;

import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.processing.NLPStage.*;
import static org.icij.datashare.util.function.ThrowingFunctions.parseStages;
import static org.icij.datashare.util.function.ThrowingFunctions.removeSpaces;
import static org.icij.datashare.util.function.ThrowingFunctions.splitComma;


/**
 * CoreNLP pipeline
 *
 * Created by julien on 3/24/16.
 */
public class CoreNLPPipeline extends AbstractNLPPipeline {

    // Pipelines of CoreNLP annotators (Tokens, Sentence, PoS, NER)
    private Map<Language, StanfordCoreNLP> pipeline;


    public CoreNLPPipeline(final Logger logger, final Properties properties) {
        super(logger, properties);

        stageDependencies.get(SENTENCE).add(TOKEN);
        stageDependencies.get(POS)     .addAll(Arrays.asList(SENTENCE, TOKEN));
        stageDependencies.get(LEMMA)   .addAll(Arrays.asList(SENTENCE, TOKEN, POS));
        stageDependencies.get(NER)     .addAll(Arrays.asList(SENTENCE, TOKEN, LEMMA));

        supportedStages.get(ENGLISH).addAll(Arrays.asList(SENTENCE, TOKEN, POS, LEMMA, NER));
        supportedStages.get(SPANISH).addAll(Arrays.asList(SENTENCE, TOKEN, POS, LEMMA, NER));
        supportedStages.get(GERMAN) .addAll(Arrays.asList(SENTENCE, TOKEN, POS, LEMMA, NER));
        supportedStages.get(FRENCH) .addAll(Arrays.asList(SENTENCE, TOKEN, POS, LEMMA));

        if (stages == null || stages.isEmpty()) {
            stages = supportedStages.get(language);
        }

        pipeline = new HashMap<>();
    }

    @Override
    protected boolean initialize() throws IOException {
        if ( ! super.initialize()) {
            return false;
        }

        // Load stage- and language-specific models, wrt to props
        if ( ! pipeline.containsKey(language)) {
            Properties props = new Properties();
            // Set chain stages (CoreNLP annotators)
            props.setProperty("annotators", String.join(", ", getAnnotators()));
            // Set stage-specific properties
            props.setProperty("pos.model", MODELS_PATH_POS.get(language).toString());
            props.setProperty("ner.model", MODELS_PATH_NER.get(language).toString());

            pipeline.put(language, new StanfordCoreNLP(props, true));
        }

        return true;
    }

    @Override
    protected void process(String input) {
        logger.log(INFO, "Processing language: " + language);

        Annotation document = new Annotation(input);
        // Split input into tokens
        // Group tokens into sentences
        // Tag tokens with their part-of-speech
        // Tag tokens with their recognized named entity category
        pipeline.get(language).annotate(document);

        List<List<String[]>> sentences = new ArrayList<>();
        for (CoreMap sentence: document.get(SentencesAnnotation.class)) {
            List<String[]> sent  =  new ArrayList<>();
            for (CoreLabel token: sentence.get(TokensAnnotation.class)) {
                String word = token.get(TextAnnotation.class);
                String pos  = token.get(PartOfSpeechAnnotation.class);
                String ne   = token.get(NamedEntityTagAnnotation.class);
                sent.add(new String[]{word, pos, ne});
            }
            sentences.add(sent);
        }
        String out = formatAnnotations(sentences);

        logger.log(INFO, out);
    }

    @Override
    protected void terminate() {
        // Don't keep models in memory (to GC)
       if ( !annotatorsCaching) {
            pipeline.remove(language);
        }
    }


    private String getAnnotatorName(NLPStage stage) {
        return stageToAnnotatorNameMap.get(stage);
    }

    private List<String> getAnnotators() {
        return getStages()
                .stream()
                .map(s -> getAnnotatorName(s))
                .collect(Collectors.toList());
    }

    private static final Map<NLPStage, String> stageToAnnotatorNameMap =
            new HashMap<NLPStage, String>(){{
                put(SENTENCE, "ssplit");
                put(TOKEN,    "tokenize");
                put(LEMMA,    "lemma");
                put(POS,      "pos");
                put(NER,      "ner");
            }};

    private static final String MODELS_BASEDIR = "edu/stanford/nlp/models";

    private static final Map<NLPStage, Path> MODELS_DIR =
            new HashMap<NLPStage, Path>(){{
                put(POS, Paths.get(MODELS_BASEDIR, "pos-tagger") );
                put(NER, Paths.get(MODELS_BASEDIR, "ner") );
            }};

    private static final Map<Language, Path> MODELS_PATH_POS =
            new HashMap<Language, Path>(){{
                put(ENGLISH, MODELS_DIR.get(POS).resolve(Paths.get("english-left3words", "english-left3words-distsim.tagger")));
                put(SPANISH, MODELS_DIR.get(POS).resolve(Paths.get("spanish",            "spanish-distsim.tagger")));
                put(FRENCH,  MODELS_DIR.get(POS).resolve(Paths.get("french",             "french.tagger")));
                put(GERMAN,  MODELS_DIR.get(POS).resolve(Paths.get("german",             "german-hgc.tagger")));
            }};

    private static final Map<Language, Path> MODELS_PATH_NER =
            new HashMap<Language, Path>(){{
                put(ENGLISH, MODELS_DIR.get(NER).resolve("english.all.3class.distsim.crf.ser.gz"));
                put(SPANISH, MODELS_DIR.get(NER).resolve("spanish.ancora.distsim.s512.crf.ser.gz"));
                put(GERMAN,  MODELS_DIR.get(NER).resolve("german.hgc_175m_600.crf.ser.gz"));
            }};

}
