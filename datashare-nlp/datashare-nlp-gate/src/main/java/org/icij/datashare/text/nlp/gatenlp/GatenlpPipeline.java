package org.icij.datashare.text.nlp.gatenlp;

import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.Annotations;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.text.nlp.Pipeline;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static java.util.Arrays.asList;
import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.NamedEntity.Category.*;
import static org.icij.datashare.text.nlp.NlpStage.*;


/**
 * {@link Pipeline}
 * {@link AbstractPipeline}
 * {@link Type#GATENLP}
 *
 * <a href="https://github.com/ICIJ/entity-extractor/tree/production">OEG UPM Gate-based entity-extractor</a>
 * <a href="https://gate.ac.uk/">Gate</a>
 * JAPE rules defined in {@code src/main/resources/org/icij/datashare/text/nlp/gatenlp/ner/rules/} (to evolve)
 *
 * Created by julien on 5/19/16.
 */
public final class GatenlpPipeline extends AbstractPipeline {

    private static final Map<Language, Set<NlpStage>> SUPPORTED_STAGES =
            new HashMap<Language, Set<NlpStage>>(){{
                put(ENGLISH, new HashSet<>(asList(TOKEN, NER)));
                put(SPANISH, new HashSet<>(asList(TOKEN, NER)));
                put(FRENCH,  new HashSet<>(asList(TOKEN, NER)));
                put(GERMAN,  new HashSet<>(asList(TOKEN, NER)));
            }};

    // Resources base directory (configuration, dictionaries, rule-based grammar)
    private static final Path RESOURCES_DIR = Paths.get(
            System.getProperty("user.dir"), "src", "main", "resources",
            Paths.get( GatenlpPipeline.class.getPackage().getName().replace(".", "/") ).toString()
    );

    // NamedEntityCategory to Gate annotation types
    private static final Map<NamedEntity.Category, String> GATE_NER_CATEGORY_NAME =
            new HashMap<NamedEntity.Category, String>(){{
                put(ORGANIZATION, "Company");
                put(PERSON,       "Person");
                put(LOCATION,     "Country");
            }};

    private static final Map<NlpStage, String> GATE_STAGE_NAME =
            new HashMap<NlpStage, String>(){{
                put(TOKEN,    "Token");
                put(SENTENCE, "Sentence");
            }};

    public GatenlpPipeline(Properties properties) {
        super(properties);
        // TOKEN <-- NER
        stageDependencies.get(NER).add(TOKEN);
    }


    @Override
    public Map<Language, Set<NlpStage>> supportedStages() {
        return SUPPORTED_STAGES;
    }

    @Override
    protected boolean initialize(Language language) {
        if ( ! super.initialize(language))
            return false;
        LOGGER.warn("initialize GATENLP pipeline is disabled");
        return true;
    }

    @Override
    protected Annotations process(String input, String hash, Language language) {
        LOGGER.warn("process GATENLP pipeline is disabled");
        return null;
    }

    @Override
    protected void terminate(Language language) {
        super.terminate(language);
        LOGGER.warn("terminate GATENLP pipeline is disabled");
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        LOGGER.warn("finalize GATENLP pipeline is disabled");
    }

    @Override
    public Optional<String> getPosTagSet(Language language) {
        return Optional.empty();
    }

}
