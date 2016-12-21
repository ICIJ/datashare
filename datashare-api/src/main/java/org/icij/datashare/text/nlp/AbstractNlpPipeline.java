package org.icij.datashare.text.nlp;

import java.nio.file.Path;
import java.nio.charset.Charset;
import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.icij.datashare.text.hashing.Hasher;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import static org.icij.datashare.util.function.ThrowingFunctions.*;


/**
 * Common structure and behavior of NlpPipeline implementations
 *
 * Pipelines (models and annotators) are loaded lazily,
 * at {@link NlpPipeline#run(String)} invocation)
 *
 * Created by julien on 3/29/16.
 */
public abstract class AbstractNlpPipeline implements NlpPipeline {

    protected static final Hasher HASHER = Document.HASHER;


    protected final Logger LOGGER = LogManager.getLogger(getClass());

    // Content charset
    protected final Charset encoding;

    // Processing stages' dependencies
    protected final Map<NlpStage, List<NlpStage>> stageDependencies;

    // Final processing stages
    protected final List<NlpStage> targetStages;

    // Named entity categories to recognize
    protected final List<NamedEntity.Category> targetEntities;

    // Keep annotators (and models) in memory from run to run?
    protected final boolean caching;

    // Complete set of processing stages to actually run (dependencies included)
    protected List<NlpStage> stages;



    protected AbstractNlpPipeline(Properties properties) {

        targetEntities = getProperty(Property.ENCODING.getName(), properties,
                removeSpaces
                        .andThen(splitComma)
                        .andThen(NamedEntity.Category.parseAll))
                .orElse(DEFAULT_ENTITIES);

        targetStages = getProperty(Property.STAGES.getName(), properties,
                removeSpaces
                        .andThen(splitComma)
                        .andThen(NlpStage.parseAll))
                .orElse(DEFAULT_TARGET_STAGES);

        encoding = getProperty(Property.ENCODING.getName(), properties,
                parseCharset.compose(String::trim))
                .orElse(DEFAULT_ENCODING);

        caching = getProperty(Property.CACHING.getName(), properties,
                trim.andThen(Boolean::parseBoolean))
                .orElse(DEFAULT_CACHING);

        stageDependencies = new HashMap<NlpStage, List<NlpStage>>() {{
            Arrays.stream(NlpStage.values())
                    .forEach( stage ->
                            put(stage, new ArrayList<>())
                    );
        }};

    }

    @Override
    public Type getType() { return Type.fromClassName(getClass().getSimpleName()).get(); }

    @Override
    public List<NamedEntity.Category> getTargetEntities() { return targetEntities; }

    @Override
    public List<NlpStage> getStages() { return stages; }

    @Override
    public boolean isCaching() { return caching; }

    @Override
    public Charset getEncoding() { return encoding; }


    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<Annotation> run(Path path) {
        Optional<Document> document = Document.create(path);
        if (document.isPresent() ) {
            return run(document.get());
        }
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<Annotation>  run(Path path, Language language) {
        Optional<Document> document = Document.create(path);
        if (document.isPresent() ) {
            return run(document.get(), language);
        }
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<Annotation>  run(Document document) {
        Optional<Language> language = document.getLanguage();
        if (language.isPresent()) {
            return run(document.getContent(), language.get());
        }
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<Annotation>  run(Document document, Language language) {
        return run(document.getContent(), language);
     }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<Annotation>  run(String input, Language language) {
        Optional<Annotation> annotation = Optional.empty();
        if (initialize(language)) {
            try{
                annotation = process(input, HASHER.hash(input), language);
            } catch (Exception e) {
                LOGGER.error("Failed to process", e);
            }
        }
        terminate(language);
        return annotation;
    }


    /**
     * Prepare pipeline run
     * Check language support for implied stages.
     *
     * @return false if any stage is not supported in language; true otherwise
     */
    protected boolean initialize(Language language) {
        // Pull all dependencies from targeted stages
        stages = stagesDependenciesTC(targetStages);

        // Check all dependencies for support in language
        if ( ! checkStages(language)) {
            LOGGER.info(
                    String.join(" - ",
                            getType().toString(),
                            "Skipping... Stage unsupported for ",
                            language.toString().toUpperCase(Locale.ROOT),
                            stages.toString(),
                            Thread.currentThread().getName()));
            return false;
        }

        LOGGER.info(
                String.join(" - ",
                        getType().toString(),
                        language.toString().toUpperCase(Locale.ROOT),
                        stages.toString(),
                        Thread.currentThread().getName()));
        return true;
    }

    /**
     * Apply all specified stages/annotators on input
     *
     * @param input is the source String to process
     * @param hash  the input hash code
     */
    protected abstract Optional<Annotation>  process(String input, String hash, Language language);

    /**
     * Post-processing operations.
     */
    protected void terminate(Language language) {
        stages = stagesDependenciesTC(targetStages);
    }


    /**
     * @return Language . NlpStage support matrix
     */
    public abstract Map<Language, Set<NlpStage>> supportedStages();

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supports(NlpStage stage, Language language) {
        Set<NlpStage> supStagesForLang = supportedStages().get(language);
        if (supStagesForLang == null || supStagesForLang.isEmpty()) {
            return false;
        }
        return supStagesForLang.contains(stage);
    }


    /**
     * Check every stage supports language
     *
     * @return true if every stage supports language, false otherwise
     */
    private boolean checkStages(Language language) {
        for (NlpStage stage : getStages()) {
            if ( ! supports(stage, language)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Transitive closure of stage dependencies
     *
     * @param coreStages the set of stages to expand
     * @return the topological sort of all depending stages
     */
    private List<NlpStage> stagesDependenciesTC(List<NlpStage> coreStages) {
        Set<NlpStage>  visited = new HashSet<>();
        List<NlpStage> tc      = new ArrayList<>();
        for (NlpStage stage : coreStages) {
            dfs(stage, visited, tc, stageDependencies);
        }
        return tc;
    }

    /**
     * Depth-First Search traversal of stage dependencies
     *
     * @param stage     the current stage being traversed
     * @param visited   keeps the set of already seen stages during traversal
     * @param sorted    represents the stages, in post-fix DFS traversal order
     * @param stagesMatrix holds stages dependencies
     */
    private void dfs(NlpStage stage,
                     Set<NlpStage> visited,
                     List<NlpStage> sorted,
                     Map<NlpStage, List<NlpStage>> stagesMatrix) {
        visited.add(stage);
        stagesMatrix.get(stage)
                .forEach( stageDep -> {
                    if ( ! visited.contains(stageDep)) {
                        dfs(stageDep, visited, sorted, stagesMatrix);
                    }
                });
        sorted.add(stage);
    }

}
