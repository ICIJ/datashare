package org.icij.datashare.text.processing.opennlp;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;

import opennlp.tools.postag.POSTagger;
import opennlp.tools.sentdetect.SentenceDetector;
import opennlp.tools.tokenize.Tokenizer;
import opennlp.tools.util.*;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.namefind.NameFinderME;

import org.icij.datashare.text.Language;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Language.SPANISH;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.Language.GERMAN;

import org.icij.datashare.text.NamedEntityCategory;
import static org.icij.datashare.text.NamedEntityCategory.*;

import static org.icij.datashare.text.processing.NLPStage.SENTENCE;
import static org.icij.datashare.text.processing.NLPStage.TOKEN;
import static org.icij.datashare.text.processing.NLPStage.POS;
import static org.icij.datashare.text.processing.NLPStage.NER;
import org.icij.datashare.text.processing.AbstractNLPPipeline;
import org.icij.datashare.text.processing.NLPStage;


/**
 * OpenNLP pipeline
 *
 * Created by julien on 3/29/16.
 */
public class OpenNLPPipeline extends AbstractNLPPipeline {

    // Sentence annotators (split string into sentences)
    private Map<Language, SentenceDetector> sentencer;

    // Token annotators (split string into tokens)
    private Map<Language, Tokenizer> tokenizer;

    // Part-of-Speech annotators (associate pos with tokens)
    private Map<Language, POSTagger> postagger;

    // Named Entity Recognition annotators (associate entity category with tokens)
    private Map<Language, Map<NamedEntityCategory, NameFinderME>> ner;


    public OpenNLPPipeline(final Logger logger, final Properties properties) {
        super(logger, properties);

        stageDependencies.get(TOKEN).add(SENTENCE);
        stageDependencies.get(POS)  .add(TOKEN);
        stageDependencies.get(NER)  .add(TOKEN);

        supportedStages.get(ENGLISH).addAll(Arrays.asList(SENTENCE, TOKEN, POS, NER));
        supportedStages.get(SPANISH).addAll(Arrays.asList(SENTENCE, TOKEN, POS, NER));
        supportedStages.get(FRENCH) .addAll(Arrays.asList(SENTENCE, TOKEN, POS, NER));
        supportedStages.get(GERMAN) .addAll(Arrays.asList(SENTENCE, TOKEN, POS));
        if (targetStages.isEmpty())
            targetStages = supportedStages.get(language);

        sentencer = new HashMap<>();
        tokenizer = new HashMap<>();
        postagger = new HashMap<>();
        ner       = new HashMap<>();
    }

    @Override
    protected boolean initialize() throws IOException {
        if ( ! super.initialize()) {
            return false;
        }

        ClassLoader loader =  this.getClass().getClassLoader();

        // Load sentence splitting model (language-specific)
        if (stages.contains(SENTENCE)) {
            if ( ! sentencer.containsKey(language) || sentencer.get(language) == null) {
                logger.log(INFO, "Loading " + language + " " + SENTENCE + " model" +
                        " from file " + MODELS_PATH_SENT.get(language).toString());
                InputStream   sModelIS = loader.getResourceAsStream(MODELS_PATH_SENT.get(language).toString());
                SentenceModel smodel   = new SentenceModel(sModelIS);
                sModelIS.close();
                sentencer.put(language, new SentenceDetectorME(smodel));
            }
        }

        // Load tokenization model (language-specific)
        if (stages.contains(TOKEN)) {
            if ( ! tokenizer.containsKey(language) || tokenizer.get(language) == null) {
                logger.log(INFO, "Loading " + language + " " + TOKEN + " model" +
                        " from file " + MODELS_PATH_TOK.get(language).toString());
                InputStream    tagModelIS = loader.getResourceAsStream(MODELS_PATH_TOK.get(language).toString());
                TokenizerModel tmodel     = new TokenizerModel(tagModelIS);
                tagModelIS.close();
                tokenizer.put(language, new TokenizerME(tmodel));
            }
        }

        // Load part-of-speech tagging model (language-specific)
        if (stages.contains(POS)) {
            if ( ! postagger.containsKey(language) || postagger.get(language) == null) {
                logger.log(INFO, "Loading " + language + " " + POS + " model" +
                        " from file " + MODELS_PATH_POS.get(language).toString());
                InputStream posModelIS = loader.getResourceAsStream(MODELS_PATH_POS.get(language).toString());
                POSModel    pmodel     = new POSModel(posModelIS);
                posModelIS.close();
                postagger.put(language, new POSTaggerME(pmodel));
            }
        }

        // Load language-specific named targetEntities recognition model
        if (stages.contains(NER)) {
            for (NamedEntityCategory ne : targetEntities) {
                if ( ! ner.containsKey(language) || ! ner.get(language).containsKey(ne) || ner.get(language).get(ne) == null) {
                    logger.log(INFO, "Loading " +  language + " " + ne + " model" +
                            " from file " + MODELS_PATH_NER.get(language).get(ne).toString());
                    InputStream nerModelIS        = loader.getResourceAsStream(MODELS_PATH_NER.get(language).get(ne).toString());
                    TokenNameFinderModel nerModel = new TokenNameFinderModel(nerModelIS);
                    nerModelIS.close();
                    if ( ! ner.containsKey(language)) {
                        ner.put(language,
                                new HashMap<NamedEntityCategory, NameFinderME>() {{
                                    put(ne, new NameFinderME(nerModel));
                                }});
                    } else {
                        ner.get(language).put(ne, new NameFinderME(nerModel));
                    }
                }
            }
        }

        return true;
    }

    @Override
    protected void process(String input) {
        logger.log(INFO, "Processing language: " + language);

        List<List<String[]>> sentences = new ArrayList<>();
        // Split input into sentences
        for (String sentence : sentencize(input)) {
            // Tokenize sentence
            String[] tokens  = tokenize(sentence);
            // Tag tokens with their part-of-speech
            String[] postags = postag(tokens);
            // Tag tokens with their recognized named entity category
            Map<Integer, NamedEntityCategory> nes = recognize(tokens);

            List<String[]> sent  =  new ArrayList<>();
            for (int i = 0; i < tokens.length; i++) {
                String word = tokens[i];
                String pos  = postags[i];
                String ne   = nes.getOrDefault(i, NONE).toString();
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
        if ( ! annotatorsCaching) {
            sentencer.remove(language);
            tokenizer.remove(language);
            postagger.remove(language);
            ner.remove(language);
        }
    }


    /**
     * Split input string into sentences
     *
     * @param input
     * @return
     */
    private String[] sentencize(String input) {
        if (sentencer.containsKey(language) && sentencer.get(language) != null) {
            return sentencer.get(language).sentDetect(input);
        }
        return new String[0];
    }

    /**
     * Tokenize input string
     *
     * @param input
     * @return
     */
    private String[] tokenize(String input) {
        if (tokenizer.containsKey(language) && tokenizer.get(language) != null) {
            return tokenizer.get(language).tokenize(input);
        }
        return new String[0];
    }

    /**
     * Get part-of-speech from tokens
     *
     * @param tokens
     * @return
     */
    private String[] postag(String[] tokens) {
        if (postagger.containsKey(language) && postagger.get(language) != null) {
            return postagger.get(language).tag(tokens);
        }
        return new String[0];
    }

    /**
     * Recognize ne entity category
     *
     * @param tokens is the sequence of tokens to annotate
     * @param ne is the named entity category to recognize
     * @return
     */
    private Span[] recognize(String[] tokens, NamedEntityCategory ne) {
        if (ner.containsKey(language) && ner.get(language).containsKey(ne) && ner.get(language).get(ne) != null) {
            return ner.get(language).get(ne).find(tokens);
        }
        return new Span[0];
    }

    /**
     * Recognize all specified list of targetEntities categories
     *
     * @param tokens is the sequence of tokens to annotate
     * @return
     */
    private Map<Integer, NamedEntityCategory> recognize(String[] tokens) {
        Map<Integer, NamedEntityCategory> nes = new HashMap<>();
        for (NamedEntityCategory ne : getTargetEntities()) {
            Span[] spans = recognize(tokens, ne);
            for (Span span : spans) {
                for (int i = span.getStart(); i < span.getEnd(); i++) {
                    nes.put(i, ne);
                }
            }
        }
        return nes;
/*
    Map<NamedEntityCategory, Span[]> entitySpans = new HashMap<>();
    for (NamedEntityCategory ne: getTargetEntities()) {
        Span[] spans = recognize(tokens, ne);
        for (Span span : spans) {
            for (int i = span.getStart(); i < span.getEnd(); i++) {
                nes.put(i, ne);
            }
        }
        entitySpans.put(ne, spans);
    }
    for (Span span : entitySpans.get(PERSON)) {
        for (int i = span.getStart(); i < span.getEnd(); i++) {
            System.out.print(tokens[i]);
            if (i < span.getEnd()) {
                System.out.print(" ");
            }
        }
        System.out.println();
    }
*/
    }


    private static final Path MODELS_BASEDIR =
            Paths.get( OpenNLPPipeline.class.getPackage().getName().replace(".", "/"), "models" );

    private static final Function<NLPStage, Path> MODELS_DIR =
            (s) -> MODELS_BASEDIR.resolve(s.toString());

    private static final Map<Language, Path> MODELS_PATH_SENT =
            new HashMap<Language, Path>(){{
                put(ENGLISH, MODELS_DIR.apply(SENTENCE).resolve("en-sent.bin"));
                put(SPANISH, MODELS_DIR.apply(SENTENCE).resolve("en-sent.bin"));
                put(FRENCH,  MODELS_DIR.apply(SENTENCE).resolve("fr-sent.bin"));
                put(GERMAN,  MODELS_DIR.apply(SENTENCE).resolve("de-sent.bin"));
            }};

    private static final Map<Language, Path> MODELS_PATH_TOK =
            new HashMap<Language, Path>(){{
                put(ENGLISH, MODELS_DIR.apply(TOKEN).resolve("en-token.bin"));
                put(SPANISH, MODELS_DIR.apply(TOKEN).resolve("en-token.bin"));
                put(FRENCH,  MODELS_DIR.apply(TOKEN).resolve("fr-token.bin"));
                put(GERMAN,  MODELS_DIR.apply(TOKEN).resolve("de-token.bin"));
            }};

    private static final Map<Language, Path> MODELS_PATH_POS =
            new HashMap<Language, Path>(){{
                put(ENGLISH, MODELS_DIR.apply(POS).resolve("en-pos-maxent.bin"));
                put(SPANISH, MODELS_DIR.apply(POS).resolve("es-pos-maxent.bin"));
                put(FRENCH,  MODELS_DIR.apply(POS).resolve("fr-pos-maxent.bin"));
                put(GERMAN,  MODELS_DIR.apply(POS).resolve("de-pos-maxent.bin"));
            }};

    private static final Map<Language, Map<NamedEntityCategory, Path>> MODELS_PATH_NER =
            new HashMap<Language, Map<NamedEntityCategory, Path>>(){{
                put(ENGLISH, new HashMap<NamedEntityCategory, Path>(){{
                    put(PERSON,       MODELS_DIR.apply(NER).resolve("en-ner-person.bin"));
                    put(ORGANIZATION, MODELS_DIR.apply(NER).resolve("en-ner-organization.bin"));
                    put(LOCATION,     MODELS_DIR.apply(NER).resolve("en-ner-location.bin"));
                }});
                put(SPANISH, new HashMap<NamedEntityCategory, Path>(){{
                    put(PERSON,       MODELS_DIR.apply(NER).resolve("es-ner-person.bin"));
                    put(ORGANIZATION, MODELS_DIR.apply(NER).resolve("es-ner-organization.bin"));
                    put(LOCATION,     MODELS_DIR.apply(NER).resolve("es-ner-location.bin"));
                }});
                put(FRENCH, new HashMap<NamedEntityCategory, Path>(){{
                    put(PERSON,       MODELS_DIR.apply(NER).resolve("en-ner-person.bin"));
                    put(ORGANIZATION, MODELS_DIR.apply(NER).resolve("en-ner-organization.bin"));
                    put(LOCATION,     MODELS_DIR.apply(NER).resolve("en-ner-location.bin"));
                }});
            }};

}
