package org.icij.datashare.text.nlp.ixa;

import com.google.common.io.Files;
import ixa.kaflib.Entity;
import ixa.kaflib.KAFDocument;
import ixa.kaflib.KAFDocument.LinguisticProcessor;
import ixa.kaflib.Term;
import ixa.kaflib.WF;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.AbstractNlpPipeline;
import org.icij.datashare.text.nlp.Annotation;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.text.nlp.ixa.models.IxaModels;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.*;
import java.util.function.Function;

import static java.util.Arrays.asList;
import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.nlp.NlpStage.*;


/**
 * {@link org.icij.datashare.text.nlp.NlpPipeline}
 * {@link org.icij.datashare.text.nlp.AbstractNlpPipeline}
 * {@link Type#IXA}
 *
 * <a href="http://ixa2.si.ehu.es/ixa-pipes">Ixa Pipes</a>
 * <a href="https://github.com/ixa-ehu/ixa-pipe-tok">Ixa Pipe Tok</a>
 * <a href="https://github.com/ixa-ehu/ixa-pipe-pos">Ixa Pipe Pos</a>
 * <a href="https://github.com/ixa-ehu/ixa-pipe-nerc">Ixa Pipe Nerc</a>
 *
 * Created by julien on 9/22/16.
 */
public class IxaNlpPipeline extends AbstractNlpPipeline {

    private static final Map<Language, Set<NlpStage>> SUPPORTED_STAGES =
            new HashMap<Language, Set<NlpStage>>(){{
                put(ENGLISH, new HashSet<>(asList(TOKEN, POS, NER)));
                put(SPANISH, new HashSet<>(asList(TOKEN, POS, NER)));
                put(FRENCH,  new HashSet<>(asList(TOKEN, POS)));
                put(GERMAN,  new HashSet<>(asList(TOKEN, POS, NER)));
                put(DUTCH,   new HashSet<>(asList(TOKEN, POS, NER)));
                put(ITALIAN, new HashSet<>(asList(TOKEN, POS, NER)));
                put(BASQUE,  new HashSet<>(asList(TOKEN, POS, NER)));
            }};

    private static final String VERSION_TOK = "2.0.0";
    private static final String VERSION_POS = "1.5.2";
    private static final String VERSION_NER = "1.6.1";

    private static final String  KAF_VERSION            = "v1.naf";
    private static final String  DEFAULT_NORMALIZE      = "default"; // alpino, ancora, ctag, default, ptb, tiger, tutpenn
    private static final String  DEFAULT_UNTOKENIZABLE  = "no";      // yes, no
    private static final String  DEFAULT_HARD_PARAGRAPH = "no";      // yes, no
    private static final boolean DEFAULT_MULTIWORDS     = false;
    private static final boolean DEFAULT_DICTAG         = false;


    // Part-of-Speech annotators
    private Map<Language, eus.ixa.ixa.pipe.pos.Annotate> posTagger;

    // Named Entity Recognition annotators
    private Map<Language, eus.ixa.ixa.pipe.nerc.Annotate> nerFinder;

    // Annotator loading functions (per NlpStage)
    private final Map<NlpStage, Function<Language, Boolean>> annotatorLoader;


    public IxaNlpPipeline(Properties properties) {
        super(properties);

        // TOKEN <-- POS <-- NER
        stageDependencies.get(POS).add(TOKEN);
        stageDependencies.get(NER).add(POS);

        annotatorLoader = new HashMap<NlpStage, Function<Language, Boolean>>(){{
            put(POS, IxaNlpPipeline.this::loadPosTagger);
            put(NER, IxaNlpPipeline.this::loadNameFinder);
        }};

        posTagger = new HashMap<>();
        nerFinder = new HashMap<>();
    }


    @Override
    public Map<Language, Set<NlpStage>> supportedStages() {
        return SUPPORTED_STAGES;
    }

    @Override
    protected boolean initialize(Language language) {
        if ( ! super.initialize(language))
            return false;
        asList(POS, NER).forEach( stage ->
                annotatorLoader.get(stage).apply(language)
        );
        return true;
    }

    @Override
    protected Optional<Annotation> process(String input, String hash, Language language) {
        Annotation annotation = new Annotation(hash, getType(), language);
        // KAF document annotated by IXA annotators
        KAFDocument kafDocument = new KAFDocument(language.toString(), KAF_VERSION);

        // tokenize( input )
        LOGGER.info("tokenizing for "  + language.toString() );
        if ( ! tokenize(new StringReader(input), kafDocument, hash, language) )
            return Optional.empty();

        // pos-tag( tokenize( input ) )
        LOGGER.info("POS-tagging for " + language.toString());
        if ( ! postag(kafDocument, hash, language) )
            return Optional.of(annotation);

        // Feed annotation with tokens and pos
        for (int s = kafDocument.getFirstSentence(); s <= kafDocument.getNumSentences(); s++) {
            List<Term> sentenceTerms = kafDocument.getSentenceTerms(s);
            for(Term term : sentenceTerms) {
                WF wfBegin     = term.getWFs().get(0);
                WF wfEnd       = term.getWFs().get(term.getWFs().size() - 1);
                int tokenBegin = wfBegin.getOffset();
                int tokenEnd   = wfEnd.getOffset() + wfEnd.getLength();
                annotation.add(TOKEN, tokenBegin, tokenEnd);
                if (targetStages.contains(POS)) {
                    String posTag = term.getPos();
                    annotation.add(POS, tokenBegin, tokenEnd, posTag);
                }
            }
            Term termBegin     = sentenceTerms.get(0);
            Term termEnd       = sentenceTerms.get(sentenceTerms.size() - 1);
            WF   wfBegin       = termBegin.getWFs().get(0);
            WF   wfEnd         = termEnd.getWFs().get(termEnd.getWFs().size() - 1);
            int  sentenceBegin = wfBegin.getOffset();
            int  sentenceEnd   = wfEnd.getOffset() + wfEnd.getLength();
            annotation.add(SENTENCE, sentenceBegin, sentenceEnd);
        }

        // ner( pos-tag( tokenize( input ) ) )
        if (targetStages.contains(NER)) {
            LOGGER.info("name-finding for " + language.toString());
            if ( ! recognize(kafDocument, hash, language) )
                return Optional.of(annotation);

            // Feed annotation with ne
            for (Entity entity : kafDocument.getEntities()) {
                List<Term> terms     = entity.getTerms();
                Term       termBegin = terms.get(0);
                Term       termEnd   = terms.get(terms.size() - 1);
                WF         wfBegin   = termBegin.getWFs().get(0);
                WF         wfEnd     = termEnd.getWFs().get(termEnd.getWFs().size() - 1);
                String     cat       = entity.getType();
                int        nerBegin  = wfBegin.getOffset();
                int        nerEnd    = wfEnd.getOffset() + wfEnd.getLength();
                annotation.add(NER, nerBegin, nerEnd, cat);
            }
        }

        return Optional.of(annotation);
    }


    public boolean tokenize(Reader reader, KAFDocument kafDocument, String hash, Language language) {
        String lang = language.toString();
        Properties properties = tokenAnnotatorProperties(lang, DEFAULT_NORMALIZE, DEFAULT_UNTOKENIZABLE, DEFAULT_HARD_PARAGRAPH);
        try {
            LinguisticProcessor newLp = kafDocument.addLinguisticProcessor("text", "ixa-pipe-tok-" + lang, VERSION_TOK);
            try (BufferedReader buffReader = new BufferedReader(reader)) {
                eus.ixa.ixa.pipe.tok.Annotate annotator = new eus.ixa.ixa.pipe.tok.Annotate(buffReader, properties);
                newLp.setBeginTimestamp();
                annotator.tokenizeToKAF(kafDocument);
                newLp.setEndTimestamp();
                return true;
            }
        } catch (Exception e) {
            LOGGER.error("failed tokenizing for " + language.toString(), e);
            return false;
        }
    }

    private Properties tokenAnnotatorProperties(String lang,
                                                String normalize,
                                                String untokenizable,
                                                String hardParagraph) {
        final Properties annotateProperties = new Properties();
        annotateProperties.setProperty("language",      lang);
        annotateProperties.setProperty("normalize",     normalize);
        annotateProperties.setProperty("untokenizable", untokenizable);
        annotateProperties.setProperty("hardParagraph", hardParagraph);
        return annotateProperties;
    }


    /**
     * Load PoS tagger (language-specific)
     *
     * @return true if successfully loaded; false otherwise
     */
    private boolean loadPosTagger(Language language) {
        if ( posTagger.containsKey(language) )
            return true;
        try {
            LOGGER.info("loading POS annotator for " + language.toString().toUpperCase());
            String     lang       = language.toString();
            String     model      = IxaModels.PATH.get(POS).get(language).toString();
            String     lemmaModel = IxaModels.PATH.get(LEMMA).get(language).toString();
            String     dictag     = Boolean.toString(DEFAULT_DICTAG);
            String     multiwords = Boolean.toString(DEFAULT_MULTIWORDS);
            if(asList(SPANISH, GALICIAN).contains(language)) {
                dictag     = Boolean.toString(true);
                multiwords = Boolean.toString(true);
            }
            Properties properties = posAnnotatorProperties(model, lemmaModel, lang, multiwords, dictag);
            posTagger.put(language, new eus.ixa.ixa.pipe.pos.Annotate(properties));
        } catch (IOException e) {
            LOGGER.error("failed loading POS annotator", e);
            return false;
        }
        LOGGER.info("loaded POS annotator for " + language.toString().toUpperCase());
        return true;
    }

    private boolean postag(KAFDocument kafDocument, String hash, Language language) {
        String  model     = IxaModels.PATH.get(POS).get(language).toString();
        String  modelName = Files.getNameWithoutExtension(model);
        LinguisticProcessor newLp = kafDocument.addLinguisticProcessor("terms", "ixa-pipe-pos-" + modelName, VERSION_POS);
        newLp.setBeginTimestamp();
        posTagger.get(language).annotatePOSToKAF(kafDocument);
        newLp.setEndTimestamp();
        return true;
    }

    private Properties posAnnotatorProperties(String model,
                                              String lemmatizerModel,
                                              String language,
                                              String multiwords,
                                              String dictag) {
        final Properties annotateProperties = new Properties();
        annotateProperties.setProperty("model",           model);
        annotateProperties.setProperty("lemmatizerModel", lemmatizerModel);
        annotateProperties.setProperty("language",        language);
        annotateProperties.setProperty("multiwords",      multiwords);
        annotateProperties.setProperty("dictag",          dictag);
        return annotateProperties;
    }


    /**
     * Load ner finder (language-specific)
     *
     * @return true if successfully loaded; false otherwise
     */
    private boolean loadNameFinder(Language language) {
        if (nerFinder.containsKey(language))
            return true;
        try {
            LOGGER.info("loading NER annotator for " + language.toString().toUpperCase());
            String     lang          = language.toString();
            String     model         = IxaModels.PATH.get(NER).get(language).toString();
            String     lexer         = eus.ixa.ixa.pipe.nerc.train.Flags.DEFAULT_LEXER;
            String     dictTag       = eus.ixa.ixa.pipe.nerc.train.Flags.DEFAULT_DICT_OPTION;
            String     dictPath      = eus.ixa.ixa.pipe.nerc.train.Flags.DEFAULT_DICT_PATH;
            String     clearFeatures = eus.ixa.ixa.pipe.nerc.train.Flags.DEFAULT_FEATURE_FLAG;
            Properties properties    = nerAnnotatorProperties(model, lang, lexer, dictTag, dictPath, clearFeatures);
            eus.ixa.ixa.pipe.nerc.Annotate annotate = new eus.ixa.ixa.pipe.nerc.Annotate(properties);
            nerFinder.put(language, annotate);
        } catch (IOException e) {
            LOGGER.error("failed loading NER annotator for " + language.toString().toUpperCase(), e);
            return false;
        }
        LOGGER.info("loaded NER annotator for " + language.toString().toUpperCase());
        return true;
    }

    private boolean recognize(KAFDocument kafDocument, String hash, Language language) {
        String model     = IxaModels.PATH.get(NER).get(language).toString();
        String modelName = Files.getNameWithoutExtension(model);
        LinguisticProcessor newLp = kafDocument.addLinguisticProcessor("entities", "ixa-pipe-nerc-" + modelName, VERSION_NER);
        try {
            newLp.setBeginTimestamp();
            nerFinder.get(language).annotateNEs(kafDocument);
            newLp.setEndTimestamp();
        } catch (IOException e) {
            LOGGER.error("failed name-finding for " + language.toString().toUpperCase(), e);
            return false;
        }
        return true;
    }

    private Properties nerAnnotatorProperties(String model,
                                              String language,
                                              String lexer,
                                              String dictTag,
                                              String dictPath,
                                              String clearFeatures) {
        Properties annotateProperties = new Properties();
        annotateProperties.setProperty("model",           model);
        annotateProperties.setProperty("language",        language);
        annotateProperties.setProperty("ruleBasedOption", lexer);
        annotateProperties.setProperty("dictTag",         dictTag);
        annotateProperties.setProperty("dictPath",        dictPath);
        annotateProperties.setProperty("clearFeatures",   clearFeatures);
        return annotateProperties;
    }

    @Override
    public Optional<String> getPosTagSet(Language language) {
        return Optional.of(IxaModels.POS_TAGSET.get(language));
    }

}
