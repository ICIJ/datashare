package org.icij.datashare.text.nlp.open.models;

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

import opennlp.tools.sentdetect.SentenceModel;

import static org.icij.datashare.text.nlp.NlpStage.SENTENCE;
import org.icij.datashare.text.Language;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.Language.SPANISH;
import static org.icij.datashare.text.Language.GERMAN;


/**
 * OpenNLP Sentence splitter models handling singleton
 *
 * Created by julien on 8/11/16.
 */
public enum OpenNlpSentenceModel {
    INSTANCE;

    private static final Logger LOGGER = LogManager.getLogger(OpenNlpSentenceModel.class);

    private static final Set<Set<Language>> SHARED_MODELS =
            new HashSet<Set<Language>>(){{
                add( new HashSet<>(asList(ENGLISH, SPANISH)) );
                add( new HashSet<>(singletonList(FRENCH)) );
                add( new HashSet<>(singletonList(GERMAN)) );
            }};

    private static Function<Language, Set<Language>> sharedModels = language ->
        SHARED_MODELS.stream()
                .filter( sharedSet -> sharedSet.contains(language) )
                .flatMap( Set::stream )
                .collect(Collectors.toSet());


    // Models base directory
    private final Path modelDir;

    // Model path (per Language)
    private final Map<Language, Path> modelPath;

    // Model locks
    private final ConcurrentHashMap<Language, Lock> modelLock;

    // Models
    private final Map<Language, SentenceModel> model;


    OpenNlpSentenceModel() {
        modelDir = OpenNlpModels.DIRECTORY.apply(SENTENCE);
        modelPath = new HashMap<Language, Path>(){{
            put(ENGLISH, modelDir.resolve("en-sent.bin"));
            put(SPANISH, modelDir.resolve("en-sent.bin"));
            put(FRENCH,  modelDir.resolve("fr-sent.bin"));
            put(GERMAN,  modelDir.resolve("de-sent.bin"));
        }};
        modelLock = new ConcurrentHashMap<Language, Lock>(){{
            modelPath.keySet()
                    .forEach( language -> put(language, new ReentrantLock()) );
        }};
        model = new HashMap<>();
    }


    public Optional<SentenceModel> get(Language language) {
        sharedModels.apply(language).forEach( lang ->
                modelLock.get(lang).lock()
        );
        try {
            if ( ! load(language, Thread.currentThread().getContextClassLoader()))
                return Optional.empty();

            return Optional.of(model.get(language));
        } finally {
            sharedModels.apply(language)
                    .forEach( lang ->
                        modelLock.get(lang).unlock()
                    );
        }
    }

    private boolean load(Language language, ClassLoader loader){
        if ( model.containsKey(language) && model.get(language) != null)
            return true;

        LOGGER.info(getClass().getName() + " - LOADING SENTENCE model for " + language);
        try (InputStream modelIS = loader.getResourceAsStream(modelPath.get(language).toString())) {
            SentenceModel sentenceModel = new SentenceModel(modelIS);
            sharedModels.apply(language)
                    .forEach( lang ->
                            model.put(lang, sentenceModel)
                    );
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " - FAILED LOADING " + SentenceModel.class.getName(), e);
            return false;
        }
        LOGGER.info(getClass().getName() + " - LOADED SENTENCE model for " + language);
        return true;
    }

    public void unload(Language language) {
        Lock l = modelLock.get(language);
        l.lock();
        try {
            model.remove(language);
        } finally {
            l.unlock();
        }
    }

}
