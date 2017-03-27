package org.icij.datashare.text.nlp.open.models;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import opennlp.tools.namefind.TokenNameFinderModel;

import static org.icij.datashare.text.nlp.NlpStage.NER;
import org.icij.datashare.text.Language;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.Language.SPANISH;
import static org.icij.datashare.text.Language.GERMAN;
import org.icij.datashare.text.NamedEntity;
import static org.icij.datashare.text.NamedEntity.Category.LOCATION;
import static org.icij.datashare.text.NamedEntity.Category.ORGANIZATION;
import static org.icij.datashare.text.NamedEntity.Category.PERSON;


/**
 * OpenNLP Named Entity Recognizer models handling singleton
 *
 * Created by julien on 8/11/16.
 */
public enum OpenNlpNerModel {
    INSTANCE;

    private static final Logger LOGGER = LogManager.getLogger(OpenNlpNerModel.class);

    private static final Set<Set<Language>> SHARED_MODELS =
            new HashSet<Set<Language>>(){{
                add( new HashSet<>( asList(ENGLISH, FRENCH)) );
                add( new HashSet<>( singletonList(FRENCH)) );
                add( new HashSet<>( singletonList(GERMAN)) );
            }};

    private static Function<Language, Set<Language>> sharedModels = language ->
            SHARED_MODELS.stream()
                    .filter( sharedSet -> sharedSet.contains(language) )
                    .flatMap( Set::stream )
                    .collect(Collectors.toSet());

    private final Set<NamedEntity.Category> supportedEntities;

    private final Set<Language> supportedLanguages;

    // Models base directory
    private final Path modelDir;

    // Model paths (per Language)
    private final Map<Language, Map<NamedEntity.Category, Path>> modelPath;

    // Model locks
    private final ConcurrentHashMap<Language, ConcurrentHashMap<NamedEntity.Category, Lock>> modelLock;

    // Models
    private final Map<Language, Map<NamedEntity.Category, TokenNameFinderModel>> model;


    OpenNlpNerModel() {
        modelDir = OpenNlpModels.DIRECTORY.apply(NER);
        modelPath = new HashMap<Language, Map<NamedEntity.Category, Path>>(){{
            put(ENGLISH, new HashMap<NamedEntity.Category, Path>(){{
                put(PERSON,       modelDir.resolve("en-ner-person.bin"));
                put(ORGANIZATION, modelDir.resolve("en-ner-organization.bin"));
                put(LOCATION,     modelDir.resolve("en-ner-location.bin"));
            }});
            put(SPANISH, new HashMap<NamedEntity.Category, Path>(){{
                put(PERSON,       modelDir.resolve("es-ner-person.bin"));
                put(ORGANIZATION, modelDir.resolve("es-ner-organization.bin"));
                put(LOCATION,     modelDir.resolve("es-ner-location.bin"));
            }});
            put(FRENCH, new HashMap<NamedEntity.Category, Path>(){{
                put(PERSON,       modelDir.resolve("en-ner-person.bin"));
                put(ORGANIZATION, modelDir.resolve("en-ner-organization.bin"));
                put(LOCATION,     modelDir.resolve("en-ner-location.bin"));
            }});
        }};
        supportedEntities  = new HashSet<>( asList(PERSON, ORGANIZATION, LOCATION) );
        supportedLanguages = new HashSet<>( modelPath.keySet() );
        modelLock = new ConcurrentHashMap<Language, ConcurrentHashMap<NamedEntity.Category, Lock>>() {{
            supportedLanguages
                    .forEach( language ->
                            put(language, new ConcurrentHashMap<NamedEntity.Category, Lock>() {{
                                supportedEntities
                                        .forEach( category ->
                                                put(category, new ReentrantLock()));
                            }})
                    );
        }};
        model = new HashMap<Language, Map<NamedEntity.Category, TokenNameFinderModel>>(){{
            supportedLanguages
                    .forEach( language ->
                            put(language, new HashMap<>())
                    );
        }};
    }


    public Optional<TokenNameFinderModel> get(Language language, NamedEntity.Category category) {
        sharedModels.apply(language).forEach( lang ->
                modelLock.get(lang).get(category).lock()
        );
        try {
            if ( ! load(language, category, Thread.currentThread().getContextClassLoader()))
                return Optional.empty();

            return Optional.of(model.get(language).get(category));
        } finally {
            sharedModels.apply(language).forEach( lang ->
                    modelLock.get(lang).get(category).unlock()
            );
        }
    }

    private boolean load(Language language, NamedEntity.Category category, ClassLoader loader) {
        if (model.containsKey(language) && model.get(language).containsKey(category) )
            return true;

        LOGGER.info(getClass().getName() + " - LOADING NER " + category + " model for " + language);
        try (InputStream modelIS = loader.getResourceAsStream(modelPath.get(language).get(category).toString())) {
            TokenNameFinderModel nerModel = new TokenNameFinderModel(modelIS);
            sharedModels.apply(language)
                    .forEach( lang ->
                            model.get(lang).put(category, nerModel)
                    );
        } catch (IOException e) {
            LOGGER.error(getClass().getName() + " - FAILED LOADING " + TokenNameFinderModel.class.getName(), e);
            return false;
        }
        LOGGER.info(getClass().getName() + " - LOADED NER " + category + " model for " + language);
        return true;
    }

    public void unload(Language language, NamedEntity.Category category) {
        Lock l = modelLock.get(language).get(category);
        l.lock();
        try {
            model.remove(language);
        } finally {
            l.unlock();
        }
    }

}
