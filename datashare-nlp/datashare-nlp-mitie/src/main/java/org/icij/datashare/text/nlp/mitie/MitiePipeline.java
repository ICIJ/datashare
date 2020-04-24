package org.icij.datashare.text.nlp.mitie;

import com.google.inject.Inject;
import edu.mit.ll.mitie.*;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.Annotations;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.text.nlp.Pipeline;

import java.util.*;

import static java.lang.Math.toIntExact;
import static java.util.Arrays.asList;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Language.SPANISH;
import static org.icij.datashare.text.nlp.NlpStage.NER;
import static org.icij.datashare.text.nlp.NlpStage.TOKEN;


/**
 * {@link Pipeline}
 * {@link AbstractPipeline}
 * {@link Type#MITIE}
 * <p>
 * /!\ Library is not thread-safe; hence the synchronization
 * <a href="https://github.com/mit-nlp/MITIE">MIT Information Extraction</a>
 * <p>
 * Created by julien on 9/19/16.
 */
public class MitiePipeline extends AbstractPipeline {

    private static final Map<Language, Set<NlpStage>> SUPPORTED_STAGES =
            new HashMap<Language, Set<NlpStage>>() {{
                put(ENGLISH, new HashSet<>(asList(TOKEN, NER)));
                put(SPANISH, new HashSet<>(asList(TOKEN, NER)));
            }};

    @Inject
    public MitiePipeline(PropertiesProvider propertiesProvider) {
        super(propertiesProvider.getProperties());

        // TOKEN <-- NER
        stageDependencies.get(NER).add(TOKEN);
    }


    @Override
    public Map<Language, Set<NlpStage>> supportedStages() {
        return SUPPORTED_STAGES;
    }

    @Override
    public Annotations process(String content, String docId, Language language) {
        Annotations annotations = new Annotations(docId, getType(), language);

        // Tokenize input
        LOGGER.info("tokenizing for " + language.toString());
        TokenIndexVector tokens = new TokenIndexVector();
        try {
            tokens = global.tokenizeWithOffsets(content);
        } catch (Exception e) {
            LOGGER.error("failed tokenizing input ", e);
        }

        // Feed annotations
        for (int i = 0; i < tokens.size(); ++i) {
            TokenIndexPair tokenIndexPair = tokens.get(i);
            int tokenBegin = toIntExact(tokenIndexPair.getIndex());
            int tokenEnd = toIntExact(tokenBegin + tokenIndexPair.getToken().length());
            annotations.add(TOKEN, tokenBegin, tokenEnd);
        }

        // NER input
        if (targetStages.contains(NER)) {
            LOGGER.info("name-finding for " + language);
            EntityMentionVector entities = null;
            try {
                entities = MitieNlpModels.getInstance().extract(tokens, language);
                // Feed annotations
                // transform index offset given in bytes of utf-8 representation to chars offset in string
                byte[] inputBytes = content.getBytes(getEncoding());
                for (int i = 0; i < entities.size(); ++i) {
                    EntityMention entity = entities.get(i);
                    TokenIndexPair tokenBegin = tokens.get(entity.getStart());
                    TokenIndexPair tokenEnd = tokens.get(entity.getEnd() - 1);
                    int nerBeginBytes = toIntExact(tokenBegin.getIndex());
                    int nerEndBytes = toIntExact(tokenEnd.getIndex() + tokenEnd.getToken().length());
                    final String nerPrefix = new String(inputBytes, 0, nerBeginBytes, getEncoding());
                    final String nerContent = new String(inputBytes, nerBeginBytes, nerEndBytes - nerBeginBytes, getEncoding());
                    int nerBegin = nerPrefix.length();
                    int nerEnd = nerBegin + nerContent.length();
                    String category = MitieNlpModels.getInstance().getTagSet(language).get(entity.getTag());
                    annotations.add(NER, nerBegin, nerEnd, NamedEntity.Category.parse(category));
                }
            } catch (InterruptedException e) {
                LOGGER.error("entities extraction interrupted", e);
            }
        }
        return annotations;
    }

    private static void printEntity(TokenIndexVector tokens, EntityMention entity) {
        Double score = entity.getScore();
        String scoreStr = String.format("%1$,.3f", score);
        // Print all the words in the range indicated by the entity ent.
        for (int i = entity.getStart(); i < entity.getEnd(); ++i) {
            System.out.print(tokens.get(i).getToken() + " ");
        }
        System.out.println();
    }

    @Override
    public Optional<String> getPosTagSet(Language language) {
        return Optional.empty();
    }

}
