package org.icij.datashare.text.nlp.mitie;

import java.util.*;

import static java.lang.Math.toIntExact;
import static java.util.Arrays.asList;

import edu.mit.ll.mitie.*;

import org.icij.datashare.text.nlp.AbstractNlpPipeline;
import org.icij.datashare.text.nlp.Annotation;
import org.icij.datashare.text.Language;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Language.SPANISH;
import org.icij.datashare.text.nlp.NlpStage;
import static org.icij.datashare.text.nlp.NlpStage.TOKEN;
import static org.icij.datashare.text.nlp.NlpStage.NER;
import org.icij.datashare.text.nlp.mitie.annotators.MitieNlpNerAnnotator;
import org.icij.datashare.text.nlp.mitie.annotators.MitieNlpTokenAnnotator;


/**
 * {@link org.icij.datashare.text.nlp.NlpPipeline}
 * {@link org.icij.datashare.text.nlp.AbstractNlpPipeline}
 * {@link Type#MITIE}
 *
 * /!\ Library is not thread-safe; hence the synchronization
 * <a href="https://github.com/mit-nlp/MITIE">MIT Information Extraction</a>
 *
 * Created by julien on 9/19/16.
 */
public class MitieNlpPipeline extends AbstractNlpPipeline {

    private static final Map<Language, Set<NlpStage>> SUPPORTED_STAGES =
            new HashMap<Language, Set<NlpStage>>(){{
                put(ENGLISH, new HashSet<>(asList(TOKEN, NER)));
                put(SPANISH, new HashSet<>(asList(TOKEN, NER)));
            }};


    public MitieNlpPipeline(Properties properties) {
        super(properties);

        // TOKEN <-- NER
        stageDependencies.get(NER).add(TOKEN);
    }


    @Override
    public Map<Language, Set<NlpStage>> supportedStages() {
        return SUPPORTED_STAGES;
    }

    @Override
    protected Optional<Annotation> process(String input, String hash, Language language) {
        Annotation annotation = new Annotation(hash, getType(), language);

        // Tokenize input
        LOGGER.info(getClass().getName() + " - TOKENIZING for " + language.toString());
        TokenIndexVector tokens = MitieNlpTokenAnnotator.INSTANCE.apply(input);
        // Feed annotation
        for (int i = 0; i < tokens.size(); ++i) {
            TokenIndexPair tokenIndexPair = tokens.get(i);
            int tokenBegin = toIntExact(tokenIndexPair.getIndex());
            int tokenEnd   = toIntExact(tokenBegin + tokenIndexPair.getToken().length());
            annotation.add(TOKEN, tokenBegin, tokenEnd);
        }

        // NER input
        if (targetStages.contains(NER)) {
            LOGGER.info(getClass().getName() + " - NAME-FINDING for " + language.toString());
            EntityMentionVector entities = MitieNlpNerAnnotator.INSTANCE.apply(tokens, language);
            // Feed annotation
            // transform index offset given in bytes of utf-8 representation to chars offset in string
            byte[] inputBytes = input.getBytes(getEncoding());
            for (int i = 0; i < entities.size(); ++i) {
                EntityMention entity         = entities.get(i);
                TokenIndexPair tokenBegin    = tokens.get(entity.getStart());
                TokenIndexPair tokenEnd      = tokens.get(entity.getEnd() - 1);
                int            nerBeginBytes = toIntExact(tokenBegin.getIndex());
                int            nerEndBytes   = toIntExact(tokenEnd.getIndex() + tokenEnd.getToken().length());
                final String   nerPrefix     = new String(inputBytes, 0, nerBeginBytes, getEncoding());
                final String   nerContent    = new String(inputBytes, nerBeginBytes, nerEndBytes - nerBeginBytes, getEncoding());
                int    nerBegin = nerPrefix.length();
                int    nerEnd   = nerBegin + nerContent.length();
                String category = MitieNlpNerAnnotator.INSTANCE.getTagSet(language).get(entity.getTag());
                annotation.add(NER, nerBegin, nerEnd, category);
            }
        }
        return Optional.of(annotation);
    }

    private static void printEntity(TokenIndexVector tokens, EntityMention entity) {
        Double        score    = entity.getScore();
        String        scoreStr = String.format("%1$,.3f",score);
        // Print all the words in the range indicated by the entity ent.
        for (int i = entity.getStart(); i < entity.getEnd(); ++i) {
            System.out.print(tokens.get(i).getToken() + " ");
        }
        System.out.println("");
    }

    @Override
    public Optional<String> getPosTagSet(Language language) {
        return Optional.empty();
    }

}
