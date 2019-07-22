package org.icij.datashare.text.nlp.ixapipe;

import com.google.common.io.Files;
import com.google.inject.Inject;
import eus.ixa.ixa.pipe.pos.Annotate;
import ixa.kaflib.Entity;
import ixa.kaflib.KAFDocument;
import ixa.kaflib.KAFDocument.LinguisticProcessor;
import ixa.kaflib.Term;
import ixa.kaflib.WF;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.Annotations;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.text.nlp.Pipeline;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.*;
import java.util.function.BiFunction;

import static java.util.Arrays.asList;
import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.nlp.NlpStage.*;


/**
 * {@link Pipeline}
 * {@link AbstractPipeline}
 * {@link Type#IXAPIPE}
 * <p>
 * <a href="http://ixa2.si.ehu.es/ixa-pipes">Ixa Pipes</a>
 * <a href="https://github.com/ixa-ehu/ixa-pipe-tok">Ixa Pipe Tok</a>
 * <a href="https://github.com/ixa-ehu/ixa-pipe-pos">Ixa Pipe Pos</a>
 * <a href="https://github.com/ixa-ehu/ixa-pipe-nerc">Ixa Pipe Nerc</a>
 * <p>
 * Created by julien on 9/22/16.
 */
public class IxapipePipeline extends AbstractPipeline {
    private static final String VERSION_TOK = "2.0.0";
    private static final Map<Language, Set<NlpStage>> SUPPORTED_STAGES =
            new HashMap<Language, Set<NlpStage>>() {{
                put(ENGLISH, new HashSet<>(asList(TOKEN, POS, NER)));
                put(SPANISH, new HashSet<>(asList(TOKEN, POS, NER)));
                put(FRENCH, new HashSet<>(asList(TOKEN, POS)));
                put(GERMAN, new HashSet<>(asList(TOKEN, POS, NER)));
                put(DUTCH, new HashSet<>(asList(TOKEN, POS, NER)));
                put(ITALIAN, new HashSet<>(asList(TOKEN, POS, NER)));
                put(BASQUE, new HashSet<>(asList(TOKEN, POS, NER)));
            }};

    Map<NlpStage, BiFunction<ClassLoader, Language, Boolean>> annotatorLoader =
            new HashMap<NlpStage, BiFunction<ClassLoader, Language, Boolean>>() {{
                put(POS, IxapipePipeline.this::loadPos);
                put(NER, IxapipePipeline.this::loadName);
                put(TOKEN, IxapipePipeline.this::loadToken);
            }};

    private static final String VERSION_POS = "1.5.2";

    private static final String VERSION_NER = "1.6.1";
    private static final String KAF_VERSION = "v1.naf";

    private static final String DEFAULT_NORMALIZE = "default"; // alpino, ancora, ctag, default, ptb, tiger, tutpenn
    private static final String DEFAULT_UNTOKENIZABLE = "no";      // yes, no
    private static final String DEFAULT_HARD_PARAGRAPH = "no";      // yes, no

    @Inject
    public IxapipePipeline(PropertiesProvider propertiesProvider) {
        super(propertiesProvider.getProperties());

        // TOKEN <-- POS <-- NER
        stageDependencies.get(POS).add(TOKEN);
        stageDependencies.get(NER).add(POS);
    }

    @Override
    public Map<Language, Set<NlpStage>> supportedStages() {
        return SUPPORTED_STAGES;
    }

    @Override
    public boolean initialize(Language language) throws InterruptedException {
        if (!super.initialize(language))
            return false;
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        stages.forEach( stage -> annotatorLoader.get(stage).apply(classLoader, language));
        return true;
    }

    @Override
    public Annotations process(String content, String docId, Language language) {
        Annotations annotations = new Annotations(docId, getType(), language);
        // KAF document annotated by IXAPIPE annotators
        KAFDocument kafDocument = new KAFDocument(language.toString(), KAF_VERSION);

        // tokenize( input )
        LOGGER.info("tokenizing for " + language.toString());
        if (!tokenize(new StringReader(content), kafDocument, docId, language))
            return annotations;

        // pos-tag( tokenize( input ) )
        LOGGER.info("POS-tagging for " + language.toString());
        if (!postag(kafDocument, docId, language))
            return annotations;

        // Feed annotations with tokens and pos
        for (int s = kafDocument.getFirstSentence(); s <= kafDocument.getNumSentences(); s++) {
            List<Term> sentenceTerms = kafDocument.getSentenceTerms(s);
            for (Term term : sentenceTerms) {
                WF wfBegin = term.getWFs().get(0);
                WF wfEnd = term.getWFs().get(term.getWFs().size() - 1);
                int tokenBegin = wfBegin.getOffset();
                int tokenEnd = wfEnd.getOffset() + wfEnd.getLength();
                annotations.add(TOKEN, tokenBegin, tokenEnd);
                if (targetStages.contains(POS)) {
                    String posTag = term.getPos();
                    annotations.add(POS, tokenBegin, tokenEnd);
                }
            }
            if (sentenceTerms.size() > 0) {
                Term termBegin = sentenceTerms.get(0);
                Term termEnd = sentenceTerms.get(sentenceTerms.size() - 1);
                WF wfBegin = termBegin.getWFs().get(0);
                WF wfEnd = termEnd.getWFs().get(termEnd.getWFs().size() - 1);
                int sentenceBegin = wfBegin.getOffset();
                int sentenceEnd = wfEnd.getOffset() + wfEnd.getLength();
                annotations.add(SENTENCE, sentenceBegin, sentenceEnd);
            }
        }

        // ner( pos-tag( tokenize( input ) ) )
        if (targetStages.contains(NER)) {
            LOGGER.info("name-finding for " + language.toString());
            if (!recognize(kafDocument, docId, language))
                return annotations;

            // Feed annotations with ne
            for (Entity entity : kafDocument.getEntities()) {
                List<Term> terms = entity.getTerms();
                Term termBegin = terms.get(0);
                Term termEnd = terms.get(terms.size() - 1);
                WF wfBegin = termBegin.getWFs().get(0);
                WF wfEnd = termEnd.getWFs().get(termEnd.getWFs().size() - 1);
                String cat = entity.getType();
                int nerBegin = wfBegin.getOffset();
                int nerEnd = wfEnd.getOffset() + wfEnd.getLength();
                annotations.add(NER, nerBegin, nerEnd, NamedEntity.Category.parse(cat));
            }
        }

        return annotations;
    }

    private boolean tokenize(Reader reader, KAFDocument kafDocument, String hash, Language language) {
        try {
            LinguisticProcessor newLp = kafDocument.addLinguisticProcessor("text",
                    "ixapipe-pipe-tok-" + language.toString(), VERSION_TOK);
            try (BufferedReader buffReader = new BufferedReader(reader)) {
                eus.ixa.ixa.pipe.tok.Annotate annotator = new eus.ixa.ixa.pipe.tok.Annotate(buffReader,
                        tokenAnnotatorProperties(language, DEFAULT_NORMALIZE, DEFAULT_UNTOKENIZABLE, DEFAULT_HARD_PARAGRAPH));
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

    private boolean postag(KAFDocument kafDocument, String hash, Language language) {
        String modelName = Files.getNameWithoutExtension(IxaPosModels.MODEL_NAMES.get(language));
        LinguisticProcessor newLp = kafDocument.addLinguisticProcessor("terms", "ixapipe-pipe-pos-" + modelName, VERSION_POS);
        newLp.setBeginTimestamp();
        final IxaAnnotate<Annotate> annotate;
        try {
            annotate = IxaPosModels.getInstance().get(language);
        } catch (InterruptedException e) {
            return false;
        }
        annotate.annotate.annotatePOSToKAF(kafDocument);
        newLp.setEndTimestamp();
        return true;
    }

    private boolean recognize(KAFDocument kafDocument, String hash, Language language) {
        String model = IxaNerModels.MODEL_NAMES.get(language);
        String modelName = Files.getNameWithoutExtension(model);
        LinguisticProcessor newLp = kafDocument.addLinguisticProcessor("entities", "ixapipe-pipe-nerc-" + modelName, VERSION_NER);
        try {
            newLp.setBeginTimestamp();
            final IxaAnnotate<eus.ixa.ixa.pipe.nerc.Annotate> ixaAnnotate = IxaNerModels.getInstance().get(language);
            ixaAnnotate.annotate.annotateNEs(kafDocument);
            newLp.setEndTimestamp();
            return true;
        } catch (IOException |InterruptedException e) {
            LOGGER.error("failed name-finding for " + language.toString().toUpperCase(), e);
            return false;
        }
    }

    private static Properties tokenAnnotatorProperties(Language lang,
                                                       String normalize,
                                                       String untokenizable,
                                                       String hardParagraph) {
        final Properties annotateProperties = new Properties();
        annotateProperties.setProperty("language", lang.iso6391Code());
        annotateProperties.setProperty("normalize", normalize);
        annotateProperties.setProperty("untokenizable", untokenizable);
        annotateProperties.setProperty("hardParagraph", hardParagraph);
        return annotateProperties;
    }


    @Override
    public Optional<String> getPosTagSet(Language language) {
        return IxaPosModels.getInstance().getPosTagSet(language);
    }

    private boolean loadPos(ClassLoader classLoader, Language language) {
        try {
            return IxaPosModels.getInstance().get(language) != null;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private boolean loadName(ClassLoader classLoader, Language language) {
        try {
            return IxaNerModels.getInstance().get(language) != null;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private boolean loadToken(ClassLoader classLoader, Language language) {
        return true;
    }
}
