package org.icij.datashare.text.nlp.corenlp.models;

import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.AbstractModels;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.text.nlp.Pipeline;

import java.io.IOException;
import java.util.*;

import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.nlp.NlpStage.*;

public class CoreNlpPipelineModels extends AbstractModels<StanfordCoreNLP> {
    public static final Map<Language, Set<NlpStage>> SUPPORTED_STAGES =
            new HashMap<Language, Set<NlpStage>>() {{
                put(ENGLISH, new HashSet<>(asList(SENTENCE, TOKEN, POS, LEMMA, NER)));
                put(SPANISH, new HashSet<>(asList(SENTENCE, TOKEN, POS, LEMMA, NER)));
                put(FRENCH, new HashSet<>(asList(SENTENCE, TOKEN, POS, LEMMA, NER)));
                put(CHINESE, new HashSet<>(asList(SENTENCE, TOKEN, POS, LEMMA, NER)));
                put(GERMAN, new HashSet<>(asList(SENTENCE, TOKEN, POS, LEMMA, NER)));
            }};
    private static volatile CoreNlpPipelineModels instance;
    private static final Object mutex = new Object();

    private static final Map<NlpStage, String> CORENLP_STAGE_NAMES =
            new HashMap<NlpStage, String>() {{
                put(SENTENCE, "ssplit");
                put(TOKEN, "tokenize");
                put(LEMMA, "lemma");
                put(POS, "pos");
                put(NER, "ner");
            }};

    @Override
    protected StanfordCoreNLP loadModelFile(Language language, ClassLoader loader) throws IOException {
        LOGGER.info("loading pipeline Annotator for " + language);
        Properties properties = new Properties();
        properties.setProperty("annotators", String.join(", ", getCoreNlpStages()));
        properties.setProperty("ner.useSUTime", "false");
        properties.setProperty("ner.applyNumericClassifiers", "false");
        properties.setProperty("tokenize.language", language.iso6391Code());

        stream(NlpStage.values()).map(this::getModels).filter(Optional::isPresent).map(Optional::get).
                forEach(m -> properties.setProperty(m.getPropertyName(), m.getInJarModelPath(language)));

        return new StanfordCoreNLP(properties, true);
    }

    public static CoreNlpPipelineModels getInstance() {
        CoreNlpPipelineModels local_instance = instance;
        if (local_instance == null) {
            synchronized (mutex) {
                local_instance = instance;
                if (local_instance == null) {
                    instance = new CoreNlpPipelineModels();
                }
            }
        }
        return instance;
    }

    private Optional<CoreNlpModels> getModels(NlpStage stage) {
        switch (stage) {
            case NER:
                return Optional.of(CoreNlpNerModels.getInstance());
            case POS:
                return Optional.of(CoreNlpPosModels.getInstance());
            default:
                return Optional.empty();
        }
    }

    private CoreNlpPipelineModels() {
        super(Pipeline.Type.CORENLP, null);
    }

    private List<String> getCoreNlpStages() {
        return stream(NlpStage.values()).map(this::getModels).filter(Optional::isPresent).
                map(m -> CORENLP_STAGE_NAMES.get(m.get().stage)).collect(toList());
    }

    @Override
    protected String getVersion() {
        return CoreNlpModels.VERSION;
    }
}
