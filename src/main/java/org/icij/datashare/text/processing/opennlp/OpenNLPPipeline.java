package org.icij.datashare.text.processing.opennlp;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import static java.util.Arrays.asList;
import static java.util.Arrays.copyOfRange;
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

import static java.util.logging.Level.SEVERE;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Language.SPANISH;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.Language.GERMAN;

import org.icij.datashare.text.processing.NamedEntity;
import org.icij.datashare.text.processing.NamedEntityCategory;
import static org.icij.datashare.text.processing.NamedEntityCategory.*;

import org.icij.datashare.text.processing.NLPStage;
import static org.icij.datashare.text.processing.NLPStage.SENTENCE;
import static org.icij.datashare.text.processing.NLPStage.TOKEN;
import static org.icij.datashare.text.processing.NLPStage.POS;
import static org.icij.datashare.text.processing.NLPStage.NER;

import org.icij.datashare.text.processing.AbstractNLPPipeline;


/**
 * OpenNLP pipeline
 *
 * Created by julien on 3/29/16.
 */
public final class OpenNLPPipeline extends AbstractNLPPipeline {

    private static final Path MODELS_BASEDIR =
            Paths.get( OpenNLPPipeline.class.getPackage().getName().replace(".", "/"), "models" );

    private static final Function<NLPStage, Path> MODEL_DIR =
            (stage) -> MODELS_BASEDIR.resolve(stage.toString());

    private static final Map<Language, Path> MODEL_PATH_SENT =
            new HashMap<Language, Path>(){{
                put(ENGLISH, MODEL_DIR.apply(SENTENCE).resolve("en-sent.bin"));
                put(SPANISH, MODEL_DIR.apply(SENTENCE).resolve("en-sent.bin"));
                put(FRENCH,  MODEL_DIR.apply(SENTENCE).resolve("fr-sent.bin"));
                put(GERMAN,  MODEL_DIR.apply(SENTENCE).resolve("de-sent.bin"));
            }};

    private static final Map<Language, Path> MODEL_PATH_TOK =
            new HashMap<Language, Path>(){{
                put(ENGLISH, MODEL_DIR.apply(TOKEN).resolve("en-token.bin"));
                put(SPANISH, MODEL_DIR.apply(TOKEN).resolve("en-token.bin"));
                put(FRENCH,  MODEL_DIR.apply(TOKEN).resolve("fr-token.bin"));
                put(GERMAN,  MODEL_DIR.apply(TOKEN).resolve("de-token.bin"));
            }};

    private static final Map<Language, Path> MODEL_PATH_POS =
            new HashMap<Language, Path>(){{
                put(ENGLISH, MODEL_DIR.apply(POS).resolve("en-pos-maxent.bin"));
                put(SPANISH, MODEL_DIR.apply(POS).resolve("es-pos-maxent.bin"));
                put(FRENCH,  MODEL_DIR.apply(POS).resolve("fr-pos-maxent.bin"));
                put(GERMAN,  MODEL_DIR.apply(POS).resolve("de-pos-maxent.bin"));
            }};

    private static final Map<Language, Map<NamedEntityCategory, Path>> MODEL_PATH_NER =
            new HashMap<Language, Map<NamedEntityCategory, Path>>(){{
                put(ENGLISH, new HashMap<NamedEntityCategory, Path>(){{
                    put(PERSON,       MODEL_DIR.apply(NER).resolve("en-ner-person.bin"));
                    put(ORGANIZATION, MODEL_DIR.apply(NER).resolve("en-ner-organization.bin"));
                    put(LOCATION,     MODEL_DIR.apply(NER).resolve("en-ner-location.bin"));
                }});
                put(SPANISH, new HashMap<NamedEntityCategory, Path>(){{
                    put(PERSON,       MODEL_DIR.apply(NER).resolve("es-ner-person.bin"));
                    put(ORGANIZATION, MODEL_DIR.apply(NER).resolve("es-ner-organization.bin"));
                    put(LOCATION,     MODEL_DIR.apply(NER).resolve("es-ner-location.bin"));
                }});
                put(FRENCH, new HashMap<NamedEntityCategory, Path>(){{
                    put(PERSON,       MODEL_DIR.apply(NER).resolve("en-ner-person.bin"));
                    put(ORGANIZATION, MODEL_DIR.apply(NER).resolve("en-ner-organization.bin"));
                    put(LOCATION,     MODEL_DIR.apply(NER).resolve("en-ner-location.bin"));
                }});
            }};


    // Sentence annotators (split string into sentences)
    private Map<Language, SentenceDetector> sentencer;

    // Token annotators (split string into tokens)
    private Map<Language, Tokenizer> tokenizer;

    // Part-of-Speech annotators (associate pos with tokens)
    private Map<Language, POSTagger> postagger;

    // Named Entity Recognition annotators (associate entity category with tokens)
    private Map<Language, Map<NamedEntityCategory, NameFinderME>> ner;


    public OpenNLPPipeline(final Properties properties) {
        super(properties);

        stageDependencies.get(TOKEN).add(SENTENCE);
        stageDependencies.get(POS)  .add(TOKEN);
        stageDependencies.get(NER)  .add(TOKEN);

        supportedStages.get(ENGLISH).addAll(asList(SENTENCE, TOKEN, POS, NER));
        supportedStages.get(SPANISH).addAll(asList(SENTENCE, TOKEN, POS, NER));
        supportedStages.get(FRENCH) .addAll(asList(SENTENCE, TOKEN, POS, NER));
        supportedStages.get(GERMAN) .addAll(asList(SENTENCE, TOKEN, POS));

        if (targetStages.isEmpty())
            targetStages = supportedStages.get(language);

        sentencer = new HashMap<>();
        tokenizer = new HashMap<>();
        postagger = new HashMap<>();
        ner       = new HashMap<>();
    }

    @Override
    protected boolean initialize() {
        if ( ! super.initialize())
            return false;

        ClassLoader loader = this.getClass().getClassLoader();
        // Load sentence splitting model (language-specific)
        if (stages.contains(SENTENCE)) {
            if ( ! sentencer.containsKey(language) || sentencer.get(language) == null) {

                LOGGER.log(INFO, "Loading " + SENTENCE + " model (" + language + ") from " + MODEL_PATH_SENT.get(language).toString());

                try(InputStream sModelIS = loader.getResourceAsStream(MODEL_PATH_SENT.get(language).toString())) {
                    sentencer.put(language, new SentenceDetectorME(new SentenceModel(sModelIS)));
                } catch (Exception e) {
                    LOGGER.log(SEVERE, "Failed to load SentenceDetector", e);
                    return false;
                }
            }
        }
        // Load tokenization model (language-specific)
        if (stages.contains(TOKEN)) {
            if ( ! tokenizer.containsKey(language) || tokenizer.get(language) == null) {

                LOGGER.log(INFO, "Loading " + TOKEN + " model (" + language + ") from " + MODEL_PATH_TOK.get(language).toString());

                try (InputStream tagModelIS = loader.getResourceAsStream(MODEL_PATH_TOK.get(language).toString())) {
                    tokenizer.put(language, new TokenizerME(new TokenizerModel(tagModelIS)));
                } catch (IOException e) {
                    LOGGER.log(SEVERE, "Failed to load Tokenizer", e);
                    return false;
                }
            }
        }
        // Load part-of-speech tagging model (language-specific)
        if (stages.contains(POS)) {
            if ( ! postagger.containsKey(language) || postagger.get(language) == null) {

                LOGGER.log(INFO, "Loading " + POS + " model (" + language + ") from " + MODEL_PATH_POS.get(language).toString());

                try (InputStream posModelIS = loader.getResourceAsStream(MODEL_PATH_POS.get(language).toString())) {
                    postagger.put(language, new POSTaggerME(new POSModel(posModelIS)));
                } catch (IOException e) {
                    LOGGER.log(SEVERE, "Failed to load Part-of-Speech Tagger", e);
                    return false;
                }
            }
        }
        // Load named entity recogniser from language-specific models
        if (stages.contains(NER)) {
            for (NamedEntityCategory cat : targetEntities) {
                if ( ! ner.containsKey(language) || ! ner.get(language).containsKey(cat) || ner.get(language).get(cat) == null) {

                    LOGGER.log(INFO, "Loading " + cat + " model (" + language + ") from " + MODEL_PATH_NER.get(language).get(cat).toString());

                    try (InputStream nerModelIS = loader.getResourceAsStream(MODEL_PATH_NER.get(language).get(cat).toString())) {
                        if ( ! ner.containsKey(language))
                            ner.put(language, new HashMap<NamedEntityCategory, NameFinderME>() {{
                                put(cat, new NameFinderME(new TokenNameFinderModel(nerModelIS)));
                            }});
                        else
                            ner.get(language).put(cat, new NameFinderME(new TokenNameFinderModel(nerModelIS)));
                    } catch (IOException e) {
                        LOGGER.log(SEVERE, "Failed to load Named Entity Recognizer", e);
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Override
    protected void process(String input) {
        // Distance to beginning of document in chars
        int offset = 0;

        // Split input into sentences
        for (String sentence : sentencize(input)) {

            // Tokenize sentence
            Span[] tokenSpans = tokenize(sentence);
            String[] tokens = tokens(tokenSpans, sentence);

            // Part-of-Speech tags
            String[] postags;
            if (stages.contains(POS))
                postags = postag(tokens);
            else
                postags = new String[0];

            // Extract Named Entities
            if (stages.contains(NER)) {
                List<NamedEntity> nes = extractEntities(tokens, tokenSpans, offset, postags);
                for (NamedEntity entity : nes) {
                    Optional.ofNullable(documentHash).ifPresent(entity::setDocument);
                    Optional.ofNullable(documentPath).ifPresent(entity::setDocumentPath);
                    entity.setExtractor(NLPPipelineType.OPENNLP);
                    entity.setExtractorLanguage(language);
                    entities.add(entity);
                }
            }

            // Update offset with end of last token position
            offset += tokenSpans[tokenSpans.length-1].getEnd();
        }

    }

    @Override
    protected void terminate() {
        super.terminate();
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
     * @param input is the String to split
     * @return Detected sentences as an array of String
     */
    private String[] sentencize(String input) {
        if (sentencer.containsKey(language) && sentencer.get(language) != null)
            return sentencer.get(language).sentDetect(input);
        return new String[0];
    }

    /**
     * Tokenize input string
     *
     * @param input is the String to tokenize
     * @return Detected token Spans (tokens boundaries)
     */
    private Span[] tokenize(String input) {
        if (tokenizer.containsKey(language) && tokenizer.get(language) != null)
            return tokenizer.get(language).tokenizePos(input);
        return new Span[0];
    }

    /**
     * Get actual tokens from Spans in the context of sentence
     *
     * @param spans represent the tokens boundaries
     * @param sentence is the String in which Spans apply
     * @return Detected tokens
     */
    private String[] tokens(Span[] spans, String sentence) {
        return Span.spansToStrings(spans, sentence);
    }

    /**
     * Get part-of-speech from tokens
     *
     * @param tokens is the sequence of tokens to annotate
     * @return Detected PoS as an array of String
     */
    private String[] postag(String[] tokens) {
        if (postagger.containsKey(language) && postagger.get(language) != null)
            return postagger.get(language).tag(tokens);
        return new String[0];
    }

    /**
     * Recognize ne entity category
     *
     * @param tokens is the sequence of tokens to annotate
     * @param cat is the named entity category to recognize
     * @return Spans (boundaries) of recognized entities
     */
    private Span[] recognize(String[] tokens, NamedEntityCategory cat) {
        if (ner.containsKey(language) && ner.get(language).containsKey(cat) && ner.get(language).get(cat) != null)
            return ner.get(language).get(cat).find(tokens);
        return new Span[0];
    }

    /**
     * Extract all specified targetEntities categories
     *
     * @param tokens is the sequence of tokens to annotate
     * @param offset is the (global) number of chars from beginning of input
     * @return List of extracted Named Entities
     */
    private List<NamedEntity> extractEntities(String[] tokens, Span[] tokenSpans, int offset, String[] postags) {
        List<NamedEntity> entities = new ArrayList<>();

        // For each Named Entity Category
        for (NamedEntityCategory category : getTargetEntities()) {

            // Recognize Named Entity mentions in sentence (as tokens)
            Span[] spans = recognize(tokens, category);

            // Create List of NamedEntity
            for (Span span : spans) {
                String mention = String.join(" ", asList(copyOfRange(tokens, span.getStart(), span.getEnd())));
                int mentionOffset = offset + tokenSpans[span.getStart()].getStart();

                Optional<NamedEntity> optEntity = NamedEntity.create(category, mention, mentionOffset);

                if (postags.length > 0) {
                    String pos = String.join(" ", asList(copyOfRange(postags, span.getStart(), span.getEnd())));
                    if (optEntity.isPresent()) {
                        optEntity.get().setPartOfSpeech(pos);
                    }
                }

                optEntity.ifPresent(entities::add);
            }
        }
        return entities;
    }

}
