package org.icij.datashare.text.nlp.gatenlp;

import gate.*;
import gate.creole.ExecutionException;
import gate.creole.ResourceInstantiationException;
import gate.util.GateException;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.Annotation;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.text.nlp.Pipeline;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static java.util.Arrays.asList;
import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.NamedEntity.Category.*;
import static org.icij.datashare.text.nlp.NlpStage.*;


public final class GatenlpPipeline extends AbstractPipeline {

    private static final Map<Language, Set<NlpStage>> SUPPORTED_STAGES =
            new HashMap<Language, Set<NlpStage>>() {{
                put(ENGLISH, new HashSet<>(asList(TOKEN, NER)));
                put(GERMAN, new HashSet<>(asList(TOKEN, NER)));
                put(FRENCH, new HashSet<>(asList(TOKEN, NER)));
                put(ITALIAN, new HashSet<>(asList(TOKEN, NER)));
            }};

    // Resources base directory (configuration, dictionaries, rule-based grammar)
    private static final Path RESOURCES_DIR = Paths.get(
            System.getProperty("user.dir"), "src", "main", "resources",
            Paths.get(GatenlpPipeline.class.getPackage().getName().replace(".", "/")).toString()
    );

    // NamedEntityCategory to Gate annotation types
    private static final Map<NamedEntity.Category, String> GATE_NER_CATEGORY_NAME =
            new HashMap<NamedEntity.Category, String>() {{
                put(ORGANIZATION, "Company");
                put(PERSON, "Person");
                put(LOCATION, "Country");
            }};

    private static final Map<NlpStage, String> GATE_STAGE_NAME =
            new HashMap<NlpStage, String>() {{
                put(TOKEN, "Token");
                put(SENTENCE, "Sentence");
            }};


    public GatenlpPipeline(Properties properties) {
        super(properties);
        // TOKEN <-- NER
        stageDependencies.get(NER).add(TOKEN);
    }

    static {
        Gate.setGateHome(RESOURCES_DIR.toFile());
        Gate.setPluginsHome(GateNlpPlugins.getInstance().getModelsFilesystemPath().toFile());
        try {
            Gate.init();
        } catch (GateException e) {
            Pipeline.LOGGER.error("cannot init GateNLP pipeline", e);
        }
    }

    @Override
    public Map<Language, Set<NlpStage>> supportedStages() {
        return SUPPORTED_STAGES;
    }

    @Override
    protected boolean initialize(Language language) {
        if (!super.initialize(language))
            return false;
        return GateNlpPlugins.getInstance().get(language, Thread.currentThread().getContextClassLoader()).isPresent();
    }

    @Override
    protected Optional<Annotation> process(String input, String hash, Language language) {
        Annotation annotation = new Annotation(hash, getType(), language);
        try {
            LOGGER.info("tokenizing ~ NAME-FINDING");

            final Optional<LanguageAnalyser> languageAnalyser = GateNlpPlugins.getInstance().get(language, Thread.currentThread().
                    getContextClassLoader());

            if (languageAnalyser.isPresent()) {
                Corpus corpus = Factory.newCorpus(String.join(".", asList(Document.HASHER.hash(input), "txt")));
                            gate.Document document = Factory.newDocument(input);
                            corpus.add(document);
                languageAnalyser.get().setCorpus(corpus);
                languageAnalyser.get().execute();

                // Feed annotation
                AnnotationSet tokenAnnotationSet = document.getAnnotations(GATE_STAGE_NAME.get(TOKEN));
                if (tokenAnnotationSet != null) {
                    for (gate.Annotation gateAnnotation : new ArrayList<>(tokenAnnotationSet)) {
                        String word = gateAnnotation.getFeatures().get("string").toString();
                        int tokenOffsetBegin = gateAnnotation.getStartNode().getOffset().intValue();
                        int tokenOffsetEnd = gateAnnotation.getEndNode().getOffset().intValue();
                        annotation.add(TOKEN, tokenOffsetBegin, tokenOffsetEnd);
                    }
                }

                // Feed annotation for NER
                if (targetStages.contains(NER)) {
                    document.getAnnotations().get(new HashSet<>(Arrays.asList("Person", "Organization", "Location")))
                                            .forEach(a -> annotation.add( //Utils.stringFor(document, a)
                                                    NER, a.getStartNode().getOffset().intValue(),
                                                    a.getEndNode().getOffset().intValue(),
                                                    a.getType()));
                }

                Factory.deleteResource(document);
                Factory.deleteResource(corpus);
                Factory.deleteResource(languageAnalyser.get());
            }
            return Optional.of(annotation);
        } catch (ResourceInstantiationException | ExecutionException e) {
            LOGGER.error("Failed to createList Gate Document", e);
            return Optional.empty();
        }
    }

    @Override
    protected void terminate(Language language) {
        super.terminate(language);
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
    }

    @Override
    public Optional<String> getPosTagSet(Language language) {
        return Optional.empty();
    }

}
