package org.icij.datashare.text.processing;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

import static org.icij.datashare.util.function.ThrowingFunctionUtils.*;
import org.icij.datashare.text.Language;

import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.processing.NamedEntityCategory.*;
import static org.icij.datashare.text.processing.NLPStage.*;


/**
 * Base class of NLP pipelines
 *
 * Created by julien on 3/29/16.
 */
public abstract class AbstractNLPPipeline implements NLPPipeline {

    // Content language to process
    protected Language language;

    // Final processing stages
    protected Set<NLPStage> targetStages;

    // Complete set of processing stages to actually run (transitive closure of targetStages dependencies)
    protected List<NLPStage> stages;

    // Processing stages dependencies
    protected final Map<NLPStage, List<NLPStage>> stageDependencies;

    // Supported processing stages for each language
    protected final Map<Language, Set<NLPStage>> supportedStages;

    // Named entity categories to recognize
    protected Set<NamedEntityCategory> targetEntities;

    // Content charset
    protected Charset encoding;

    // Keep annotators (with model) in memory from a run to the next one?
    protected boolean annotatorsCaching;

    // Properties holding pipeline configuration / options
    protected final Properties properties;

    protected final Logger logger;


    public static final Set<NamedEntityCategory> DEFAULT_ENTITY_CATEGORIES =
            new HashSet<>(Arrays.asList(PERSON, ORGANIZATION, LOCATION));

    public static final Language DEFAULT_LANGUAGE = ENGLISH;

    public static final Charset  DEFAULT_ENCODING = StandardCharsets.UTF_8;

    public static final boolean  DEFAULT_MODELCACHING = true;


    public AbstractNLPPipeline(final Logger log, final Properties props) {
        logger = log;

        properties = props;

        language = getProperty("language", properties, removeSpaces.andThen(Language::parse))
                .orElse(DEFAULT_LANGUAGE);

        targetStages = getProperty("stages", properties, removeSpaces.andThen(splitComma).andThen(parseStages))
                .orElse(new HashSet<>());

        targetEntities = getProperty("entities", properties, removeSpaces.andThen(splitComma).andThen(parseEntities))
                .orElse(DEFAULT_ENTITY_CATEGORIES);

        encoding = getProperty("encoding", properties, parseCharset.compose(String::trim))
                .orElse(DEFAULT_ENCODING);

        annotatorsCaching = getProperty("annotatorsCaching", properties, trim.andThen(Boolean::parseBoolean))
                .orElse(DEFAULT_MODELCACHING);

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

    public void setLanguage(Language language) {
        this.language = language;
    }


    public List<NLPStage> getStages() { return stages; }


    public Set<NamedEntityCategory> getTargetEntities() {
        return targetEntities;
    }

    public void setTargetEntities(Set<NamedEntityCategory> targetEntities) {this.targetEntities = targetEntities; }


    public boolean isAnnotatorsCaching() {
        return annotatorsCaching;
    }

    public void setAnnotatorsCaching(boolean annotatorsCaching) {
        this.annotatorsCaching = annotatorsCaching;
    }


    public boolean supports(NLPStage stage, Language language){
        return supportedStages.get(language).contains(stage);
    }

    /**
     * Run the specified NLPPipeline on a (plain text) File
     *
     * @param filePath is the document's Path to process
     * @throws IOException
     */
    @Override
    public void run(Path filePath) throws IOException {
        byte[] encoded = Files.readAllBytes(filePath);
        String text = new String(encoded, StandardCharsets.UTF_8);
        run(text);
    }

    /**
     * Run the specified NLPPipeline on a String
     *
     * @param input is the String to process
     * @throws IOException
     */
    @Override
    public void run(String input) throws IOException {
        if (initialize())
            process(input);
        else
            logger.log(SEVERE, "Failed to initialize");
        terminate();
    }

    /**
     * Initialize and check NLPPipeline stages
     *
     * @return true if initialization succeeded, false otherwise
     * @throws IOException
     */
    protected boolean initialize() throws IOException {
        initStages();
        return checkStages();
    }

    /**
     * Apply all specified stage annotators
     *
     * @param input is the source String to annotate
     */
    protected abstract void process(String input);

    /**
     * Release annotators after processing iff annotatorsCaching == true*
     */
    protected abstract void terminate();


    protected String formatAnnotations(List<List<String[]>> sentences) {
        String sentSep  = "\n\n";
        String tokenSep =   "\n";
        String tagSep   =    "/";
        return
                String.join(sentSep, sentences.stream().map( tokens ->
                        String.join(tokenSep, tokens.stream().map( tags ->
                                String.join(tagSep, Arrays.asList(tags))
                        ).collect(Collectors.toList()))
                ).collect(Collectors.toList()));
    }

    /**
     * Initialize stages with the topological sort of transitive closure of target stages dependencies
     */
    private void initStages() {
        stages = stagesDependenciesTC(targetStages);
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
     * Transitive closure and topological sort of stage dependencies
     *
     * @param coreStages is the stage Set to expand
     * @return the topological sort of all depending stages
     */
    protected List<NLPStage> stagesDependenciesTC(Set<NLPStage> coreStages) {
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
