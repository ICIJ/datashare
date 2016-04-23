package org.icij.datashare.text.processing;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.logging.Logger;
import static java.util.logging.Level.SEVERE;

import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntityCategory;
import org.icij.datashare.util.function.ThrowingFunction;
import static org.icij.datashare.util.function.ThrowingFunctions.*;

import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.NamedEntityCategory.*;
import static org.icij.datashare.text.processing.NLPStage.*;


/**
 * Base class of NLP pipelines.
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

    // Keep annotators in memory (and their model) from an execution to the next one?
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

        language = getProperty("language", removeSpaces.andThen(Language::parse))
                .orElse(DEFAULT_LANGUAGE);

        targetStages = getProperty("stages", removeSpaces.andThen(splitComma).andThen(parseStages))
                .orElse(new HashSet<>());

        targetEntities = getProperty("entities", removeSpaces.andThen(splitComma).andThen(parseEntities))
                .orElse(DEFAULT_ENTITY_CATEGORIES);

        encoding = getProperty("encoding", parseCharset.compose(String::trim))
                .orElse(DEFAULT_ENCODING);

        annotatorsCaching = getProperty("annotatorsCaching", trim.andThen(Boolean::parseBoolean))
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


    @Override
    public void run(Path filepath) throws IOException {
        byte[] encoded = Files.readAllBytes(filepath);
        String text = new String(encoded, StandardCharsets.UTF_8);
        run(text);
    }

    @Override
    public void run(String text) throws IOException {
        if (initialize()) {
            process(text);
        } else {
            logger.log(SEVERE, "Failed to initialize");
        }
        terminate();
    }

    protected boolean initialize() throws IOException {
        initStages();
        orderStages();
        return checkStages();
    }

    protected abstract void process(String input);

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

    private Optional<String> getProperty(String key) {
        if (properties == null) {
            return Optional.empty();
        }
        String val = properties.getProperty(key);
        return Optional.ofNullable( (val == null || val.isEmpty()) ? null : val );
    }

    protected <T> Optional<T> getProperty(String key, Function<String, ? extends T> func) {
        return getProperty(key).map(func);
    }

    protected <T> Optional<T> getProperty(String key, ThrowingFunction<String, ? extends T> func) {
        return getProperty(key).map(val -> {
            try {
                return func.apply(val);
            } catch (Exception e) {
                logger.log(SEVERE, "Invalid property transformation; has to default now", e);
                return null;
            }
        });
    }


    // Init stages with target stages and all their dependencies
    private void initStages() {
        stages = new ArrayList<>(targetStages
                .stream()
                .flatMap(stg -> stageDependenciesTC(stg).stream())
                .collect(Collectors.toSet()));
    }

    // Order stages wrt dependencies
    private void orderStages() {
        Comparator<NLPStage> comparator = (s1, s2) -> {
            Set<NLPStage> s1DepsTC = stageDependenciesTC(s1);
            Set<NLPStage> s2DepsTC = stageDependenciesTC(s2);
            boolean s1HasDeps = s1DepsTC.size() > 1;
            boolean s2HasDeps = s2DepsTC.size() > 1;
            if ( ! s1HasDeps && ! s2HasDeps) { return 0; }
            if ( ! s1HasDeps) { return -1; }
            if ( ! s2HasDeps) { return  1; }
            if (s1DepsTC.contains(s2))      { return  1; }
            else if (s2DepsTC.contains(s1)) { return -1; }
            return 0;
        };
        Collections.sort(stages, comparator);
    }

    // Check each stage is supported
    private boolean checkStages() {
        for (NLPStage stage : getStages()) {
            if ( ! supports(stage, language)) {
                return false;
            }
        }
        return true;
    }

    // Transitive closure of stage dependencies
    protected Set<NLPStage> stageDependenciesTC(NLPStage stage) {
        if (stage == null){
            return new HashSet<>();
        }
        return stageDependenciesTCRec(stage, new HashSet<>());
    }

    private Set<NLPStage> stageDependenciesTCRec(NLPStage stage, Set<NLPStage> tc) {
        tc.add(stage);
        for (NLPStage stageDep : stageDependencies.getOrDefault(stage, new ArrayList<>())) {
            Set<NLPStage> stageDepTC = stageDependenciesTCRec(stageDep, tc);
            tc.addAll(stageDepTC);
        }
        return tc;
    }

}
