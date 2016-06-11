package org.icij.datashare.text.processing;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.util.function.ThrowingFunctions;

import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.processing.NamedEntityCategory.*;
import static org.icij.datashare.text.processing.NLPStage.*;


/**
 * Base class of NLP pipelines
 *
 * Created by julien on 3/29/16.
 */
public abstract class AbstractNLPPipeline implements NLPPipeline {

    public static final Set<NamedEntityCategory> DEFAULT_ENTITY_CATEGORIES = new HashSet<>(Arrays.asList(
            PERSON, ORGANIZATION, LOCATION
    ));

    public static final Language DEFAULT_LANGUAGE = ENGLISH;

    public static final Charset  DEFAULT_ENCODING = StandardCharsets.UTF_8;

    public static final boolean DEFAULT_MODEL_CACHING = false;


    protected final Logger LOGGER = Logger.getLogger(getClass().getName());

    // Supported stages for each language
    protected final Map<Language, Set<NLPStage>> supportedStages;

    // Processing stages dependencies
    protected final Map<NLPStage, List<NLPStage>> stageDependencies;


    // Content language to process
    protected Language language;

    // Final processing stages
    protected Set<NLPStage> targetStages;

    // Complete set of processing stages to actually run (dependencies included)
    protected List<NLPStage> stages;

    // Named entity categories to recognize
    protected Set<NamedEntityCategory> targetEntities;

    // Recognized named entities
    protected List<NamedEntity> entities;

    // Content charset
    protected Charset encoding;

    // Keep annotators (with model) in memory from a run to the next one?
    protected boolean annotatorsCaching;

    // Content Hash of document being processed by pipeline
    protected String documentHash;

    // Path of document being processed by pipeline
    protected Path documentPath;


    protected AbstractNLPPipeline(final Properties properties) {

        language = ThrowingFunctions.getProperty("language", properties, ThrowingFunctions.removeSpaces.andThen(Language::parse).andThen(Optional::get))
                .orElse(DEFAULT_LANGUAGE);

        targetEntities = ThrowingFunctions.getProperty("entities", properties, ThrowingFunctions.removeSpaces.andThen(ThrowingFunctions.splitComma).andThen(ThrowingFunctions.parseEntities))
                .orElse(DEFAULT_ENTITY_CATEGORIES);

        targetStages = ThrowingFunctions.getProperty("stages", properties, ThrowingFunctions.removeSpaces.andThen(ThrowingFunctions.splitComma).andThen(ThrowingFunctions.parseStages))
                .orElse(new HashSet<>());

        encoding = ThrowingFunctions.getProperty("encoding", properties, ThrowingFunctions.parseCharset.compose(String::trim))
                .orElse(DEFAULT_ENCODING);

        annotatorsCaching = ThrowingFunctions.getProperty("annotatorsCaching", properties, ThrowingFunctions.trim.andThen(Boolean::parseBoolean))
                .orElse(DEFAULT_MODEL_CACHING);

        stageDependencies = new HashMap<NLPStage, List<NLPStage>>(){{
            put(TOKEN,    new ArrayList<>());
            put(SENTENCE, new ArrayList<>());
            put(LEMMA,    new ArrayList<>());
            put(POS,      new ArrayList<>());
            put(NER,      new ArrayList<>());
        }};

        supportedStages = new HashMap<Language, Set<NLPStage>>(){{
            put(ENGLISH, new HashSet<>());
            put(SPANISH, new HashSet<>());
            put(FRENCH,  new HashSet<>());
            put(GERMAN,  new HashSet<>());
        }};

    }


    public Language getLanguage() {
        return language;
    }

    @Override
    public void setLanguage(Language language) {
        this.language = language;
    }

    @Override
    public List<NamedEntity> getEntities() { return entities; }


    public List<NLPStage> getStages() { return stages; }


    public Set<NamedEntityCategory> getTargetEntities() {
        return targetEntities;
    }

    public void setTargetEntities(Set<NamedEntityCategory> targetEntities) { this.targetEntities = targetEntities; }


    public boolean isAnnotatorsCaching() {
        return annotatorsCaching;
    }

    public void setAnnotatorsCaching(boolean annotatorsCaching) { this.annotatorsCaching = annotatorsCaching; }


    /**
     * Run the specified NLPPipeline from a Path
     *
     * @param path is the file Path to process
     */
    @Override
    public void run(Path path) {
        Optional<Document> optDoc = Document.create(path);
        if ( ! optDoc.isPresent()) {
            LOGGER.log(SEVERE, "Failed to create new Document " + path);
            return;
        }
        Document document = optDoc.get();
        run(document);
    }

    /**
     * Run the specified NLPPipeline from a Document
     *
     * @param document is the document to process
     */
    @Override
    public void run(Document document) {
        // Read document
        if ( ! document.read()) {
            LOGGER.log(SEVERE, "Failed to read Document");
            return;
        }
        // Get read content
        Optional<String> contOpt = document.getContent();
        if ( ! contOpt.isPresent()) {
            LOGGER.log(SEVERE, "Failed to get content from Document " + ThrowingFunctions.path);
            return;
        }
        // Get read content hash
        Optional<String> hashOpt = document.getHash();
        if ( ! hashOpt.isPresent()) {
            LOGGER.log(SEVERE, "Failed to get document's content hash");
            return;
        }

        documentHash = hashOpt.get();
        documentPath = document.getPath();

        // Run on read content
        run(contOpt.get());
    }

    /**
     * Running scheme from a String
     *
     * @param input is the String to process
     */
    @Override
    public void run(String input) {
        if (initialize())
            process(input);
        else
            LOGGER.log(SEVERE, "Failed to initialize");

        terminate();
    }

    /**
     * Is stage supported for language?
     *
     * @param stage is the stage to test for support
     * @param lang is the language for which stage is tested
     * @return true if stage supports language; false otherwise
     */
    public boolean supports(NLPStage stage, Language lang) {
        Set<NLPStage> suppStagesForLang = supportedStages.get(lang);
        if (suppStagesForLang == null || suppStagesForLang.isEmpty())
            return false;
        return suppStagesForLang.contains(stage);
    }

    /**
     * Initialize NLPPipeline stages
     *
     * @return true if initialization succeeded, false otherwise
     */
    protected boolean initialize() {
        // Reset recognised entities
        entities = new ArrayList<>();

        // Pull all dependencies from targeted stages
        stages = stagesDependenciesTC(targetStages);
        // all dependencies are supported for language
        if (checkStages()) {
            LOGGER.log(INFO, language.toString().toUpperCase());
            return true;
        }
        // or get subset of stages supported for language
        stages = stagesDependenciesTC(supportedStages.getOrDefault(language, new HashSet<>()));
        // or set default language
        if (stages.isEmpty()) {
            LOGGER.log(INFO, language.toString().toUpperCase(Locale.ROOT) + " language is not supported." +
                    "Defaulting to " + DEFAULT_LANGUAGE.toString().toUpperCase(Locale.ROOT));
            language = DEFAULT_LANGUAGE;
            stages = stagesDependenciesTC(supportedStages.get(language));
        }

        LOGGER.log(INFO, language.toString().toUpperCase());

        return ! stages.isEmpty();
    }

    /**
     * Apply all specified stage annotators
     *
     * @param input is the source String to process
     */
    protected abstract void process(String input);

    /**
     * Post-processing operations. Release document
     */
    protected void terminate() {
        documentHash = null;
        documentPath = null;
    }

    /**
     * Check if every stage supports language
     * @return true if every stage supports language, false otherwise
     */
    private boolean checkStages() {
        for (NLPStage stage : getStages())
            if ( ! supports(stage, language))
                return false;
        return true;
    }

    /**
     * Transitive closure and then topological sort of stage dependencies
     *
     * @param coreStages is the stage Set to expand
     * @return the topological sort of all depending stages
     */
    private List<NLPStage> stagesDependenciesTC(Set<NLPStage> coreStages) {
        Set<NLPStage> visited = new HashSet<>();
        List<NLPStage> tc = new ArrayList<>();
        for (NLPStage stage : coreStages)
            if ( ! visited.contains(stage))
                dfs(stage, visited, tc, stageDependencies);
        return tc;
    }

    /**
     * Depth-First Search traversal of stage dependencies
     *
     * @param stage is the current stage being traversed
     * @param visited keeps the set of already seen stages currently in traversal
     * @param sorted represents the stages in post-fix DFS traversal order
     * @param stagesMap holds stages dependencies
     */
    private void dfs(NLPStage stage, Set<NLPStage> visited, List<NLPStage> sorted, Map<NLPStage, List<NLPStage>> stagesMap) {
        visited.add(stage);
        for (NLPStage stageDep : stagesMap.get(stage))
            if ( ! visited.contains(stageDep))
                dfs(stageDep, visited, sorted, stagesMap);
        sorted.add(stage);
    }

}
