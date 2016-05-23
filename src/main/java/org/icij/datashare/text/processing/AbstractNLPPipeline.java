package org.icij.datashare.text.processing;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.reading.DocumentParserException;

import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.processing.NamedEntityCategory.*;
import static org.icij.datashare.text.processing.NLPStage.*;

import static org.icij.datashare.util.function.ThrowingFunctions.*;


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

    public static final boolean DEFAULT_MODEL_CACHING = true;

    protected final Logger LOGGER = Logger.getLogger(getClass().getName());

    // Processing stages dependencies
    protected final Map<NLPStage, List<NLPStage>> stageDependencies;

    // Supported processing stages for each language
    protected final Map<Language, Set<NLPStage>> supportedStages;

    // Properties holding pipeline configuration / options
    protected final Properties properties;


    // Content language to process
    protected Language language;

    // Final processing stages
    protected Set<NLPStage> targetStages;

    // Complete set of processing stages to actually run (transitive closure of targetStages dependencies)
    protected List<NLPStage> stages;

    // Named entity categories to recognize
    protected Set<NamedEntityCategory> targetEntityCategories;

    // Recognized named entities
    protected List<NamedEntity> entities;

    // Content charset
    protected Charset encoding;

    // Keep annotators (with model) in memory from a run to the next one?
    protected boolean annotatorsCaching;

    // Document being processed by pipeline
    protected Document document;


    protected AbstractNLPPipeline(final Properties props) {
        properties = props;

        language = getProperty("language", properties, removeSpaces.andThen(Language::parse).andThen(Optional::get))
                .orElse(DEFAULT_LANGUAGE);

        targetEntityCategories = getProperty("entities", properties, removeSpaces.andThen(splitComma).andThen(parseEntities))
                .orElse(DEFAULT_ENTITY_CATEGORIES);

        targetStages = getProperty("stages", properties, removeSpaces.andThen(splitComma).andThen(parseStages))
                .orElse(new HashSet<>());

        encoding = getProperty("encoding", properties, parseCharset.compose(String::trim))
                .orElse(DEFAULT_ENCODING);

        annotatorsCaching = getProperty("annotatorsCaching", properties, trim.andThen(Boolean::parseBoolean))
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


    public List<NLPStage> getStages() { return stages; }


    public Set<NamedEntityCategory> getTargetEntityCategories() {
        return targetEntityCategories;
    }

    public void setTargetEntityCategories(Set<NamedEntityCategory> targetEntityCategories) {
        this.targetEntityCategories = targetEntityCategories;
    }

    public boolean isAnnotatorsCaching() {
        return annotatorsCaching;
    }

    public void setAnnotatorsCaching(boolean annotatorsCaching) {
        this.annotatorsCaching = annotatorsCaching;
    }

    @Override
    public List<NamedEntity> getEntities() { return entities; }


    /**
     * Is stage supported for language?
     *
     * @param stage
     * @param lang
     * @return
     */
    public boolean supports(NLPStage stage, Language lang){
        return supportedStages.get(lang).contains(stage);
    }


    /**
     * Run the specified NLPPipeline from a Path
     *
     * @param path is the file Path to process
     */
    @Override
    public void run(Path path) {
        Optional<Document> optDoc = Document.create(path);
        if ( ! optDoc.isPresent()) {
            LOGGER.log(SEVERE, "Failed to run pipeline; failed to create new Document " + path);
            return;
        }
        Document document = optDoc.get();
        run(document);
    }

    /**
     * Run the specified NLPPipeline from a Document
     *
     * @param doc is the document to process
     */
    @Override
    public void run(Document doc) {
        document = doc;
        if ( ! document.read()) {
            LOGGER.log(SEVERE, "Failed to read Document");
            return;
        }

        Optional<String> optCont = document.getContent();
        if ( ! optCont.isPresent()) {
            LOGGER.log(SEVERE, "Failed to get content from Document " + path);
            return;
        }

        String content = optCont.get();
        run(content);
    }

    /**
     * Run the specified NLPPipeline from a String
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
     * Initialize NLPPipeline stages
     *
     * @return true if initialization succeeded, false otherwise
     */
    protected boolean initialize() {
        // Initialize entities
        entities = new ArrayList<>();
        // Pull all dependencies from specified stages
        stages = stagesDependenciesTC(targetStages);
        // all specified stages and dependencies are supported for language
        if (checkStages())
            return true;
        // or get all supported stages for language
        stages = stagesDependenciesTC(supportedStages.getOrDefault(language, new HashSet<>()));
        // TODO: or set default language?
        return ! stages.isEmpty();
    }

    /**
     * Apply all specified stage annotators
     *
     * @param input is the source String to annotate
     */
    protected abstract void process(String input);

    /**
     * Post-processing operations. Release document
     */
    protected void terminate() { document = null; }

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
     * Transitive closure and topological sort of stage dependencies
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
