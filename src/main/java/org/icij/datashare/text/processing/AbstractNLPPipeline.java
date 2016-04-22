package org.icij.datashare.text.processing;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
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

    // Named entity categories to recognize
    protected Set<NamedEntityCategory> entityCategories;

    // Sequence of processing stages to run
    protected List<NLPStage> stages;

    // Content language to process
    protected Language language;

    // Content charset
    protected Charset encoding;

    // Keep annotators in memory (and their model) from an execution to the next one?
    protected boolean annotatorsCaching;

    // Properties holding pipeline configuration / options
    protected final Properties properties;

    // Processing stages dependencies
    protected final Map<NLPStage, List<NLPStage>> stageDependencies;

    // Supported processing stages for each language
    protected final Map<Language, List<NLPStage>> supportedStages;

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

        entityCategories = getProperty("entityCategories", removeSpaces.andThen(splitComma).andThen(parseEntities))
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

        supportedStages = new HashMap<Language, List<NLPStage>>(){{
            put(ENGLISH, new ArrayList<>());
            put(SPANISH, new ArrayList<>());
            put(FRENCH,  new ArrayList<>());
            put(GERMAN,  new ArrayList<>());
        }};

    }

    public Language getLanguage() {
        return language;
    }

    public void setLanguage(Language language) {
        this.language = language;
    }


    public List<NLPStage> getStages() { return stages; }

    public void setStages(List<NLPStage> stages) {
        this.stages = stages;
    }


    public Set<NamedEntityCategory> getEntityCategories() {
        return entityCategories;
    }

    public void setEntityCategories(Set<NamedEntityCategory> entityCategories) {this.entityCategories = entityCategories; }


    public boolean isAnnotatorsCaching() {
        return annotatorsCaching;
    }

    public void setAnnotatorsCaching(boolean annotatorsCaching) {
        this.annotatorsCaching = annotatorsCaching;
    }


    public boolean supports(NLPStage stage, Language language){
        return supportedStages.get(stage).contains(language);
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
        }
        terminate();
    }

    protected boolean initialize() throws IOException {
        orderStages();
        for (NLPStage stage : getStages()) {
            if ( ! supports(stage, language)) {
                logger.log(SEVERE, "Initialization failed; Processing stage unsupported " + stage);
                return false;
            }
        }
        return true;
    }

    protected abstract void process(String input);

    protected abstract void terminate();


    protected String formatAnnotations(List<List<String[]>> sentences) {
        String sentSep  = "\n\n";
        String tokenSep =   "\n";
        String tagSep   =    "/";
        return String.join(sentSep, sentences.stream().map( tokens ->
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

    private void orderStages() {
        Comparator<NLPStage> comparator = (s1, s2) -> {
            Predicate<NLPStage> hasDeps =
                    (s) -> stageDependencies.containsKey(s) && ! stageDependencies.get(s).isEmpty();
            boolean s1HasDeps = hasDeps.test(s1);
            boolean s2HasDeps = hasDeps.test(s2);
            if ( ! s1HasDeps && ! s2HasDeps) { return 0; }
            if ( ! s1HasDeps) { return -1; }
            if ( ! s2HasDeps) { return 1; }
            if (stageDependencies.get(s1).contains(s2)) { return 1; }
            else if (stageDependencies.get(s2).contains(s1)) { return  -1; }
            return 0;
        };
        Collections.sort(stages, comparator);
    }


    protected List<NLPStage> getStageDependenciesTC(NLPStage stage) {
        List<NLPStage> stageDirectDeps = stageDependencies.get(stage);
        if (stageDirectDeps == null) {
            return new ArrayList<NLPStage>();
        } else {
            List<NLPStage> stageDepsTC = new ArrayList<>();
            for (NLPStage s : stageDirectDeps) {
                stageDepsTC.addAll(getStageDependenciesTC(s));
                stageDepsTC.add(s);
            }
            return stageDepsTC;
        }
    }

}
