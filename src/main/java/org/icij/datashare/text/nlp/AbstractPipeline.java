package org.icij.datashare.text.nlp;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.util.*;

import static org.icij.datashare.function.ThrowingFunctions.*;
import static org.icij.datashare.text.nlp.Pipeline.Type.valueOf;


public abstract class AbstractPipeline implements Pipeline {
    public static final String NLP_STAGES_PROP = "nlpStages";
    protected final Logger LOGGER = LoggerFactory.getLogger(getClass());


    protected final Charset encoding;
    protected final Map<NlpStage, List<NlpStage>> stageDependencies;
    protected final List<NlpStage> targetStages;
    protected final List<NamedEntity.Category> targetEntities;
    protected final boolean caching;
    protected List<NlpStage> stages;

    protected AbstractPipeline(Properties properties) {
        targetEntities = getProperty(Property.ENTITIES.getName(), properties,
                removeSpaces
                        .andThen(splitComma)
                        .andThen(NamedEntity.Category.parseAll))
                .orElse(DEFAULT_ENTITIES);

        targetStages = getProperty(NLP_STAGES_PROP, properties,
                        removeSpaces.andThen(splitComma).andThen(NlpStage.parseAll))
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

    public static AbstractPipeline create(final String pipelineName, final PropertiesProvider propertiesProvider) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException, ClassNotFoundException {
        Class<? extends AbstractPipeline> pipelineClass = (Class<? extends AbstractPipeline>) Class.forName(valueOf(pipelineName).getClassName());
        return pipelineClass.getDeclaredConstructor(PropertiesProvider.class).newInstance(propertiesProvider);
    }

    /**
     * Prepare pipeline run
     * Check language support for implied stages.
     *
     * @return false if any stage is not supported in language; true otherwise
     */
    public boolean initialize(Language language) throws InterruptedException {
        // Pull all dependencies from targeted stages
        stages = stagesDependenciesTC(targetStages);
        // Check all dependencies for support in language
        if ( ! checkStages(language)) {
            LOGGER.info("initializing " + getType() + " Skipping... Stage unsupported for " +
                            language + " " + stages);
            return false;
        }
        LOGGER.info("initializing " + getType() + " " + language + " " + stages);
        return true;
    }

    /**
     * Apply all specified stages/annotators on input
     *  @param doc is the document source to process */
    public abstract List<NamedEntity> process(Document doc) throws InterruptedException;

    /**
     * Post-processing operations
     */
    public void terminate(Language language) throws InterruptedException {
        LOGGER.info("ending " + getType() + " " + language + " " + stages.toString());
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
        if (supStagesForLang == null || supStagesForLang.isEmpty())
            return false;
        return supStagesForLang.contains(stage);
    }


    /**
     * Check every stage supports language
     *
     * @return true if every stage supports language, false otherwise
     */
    private boolean checkStages(Language language) {
        for (NlpStage stage : getStages()) {
            if ( ! supports(stage, language))
                return false;
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
                    if ( ! visited.contains(stageDep))
                        dfs(stageDep, visited, sorted, stagesMatrix);
                });
        sorted.add(stage);
    }
}
