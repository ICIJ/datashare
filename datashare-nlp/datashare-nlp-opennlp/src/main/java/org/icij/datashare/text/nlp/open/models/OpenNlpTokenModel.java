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

import opennlp.tools.tokenize.TokenizerModel;

import static org.icij.datashare.text.nlp.NlpStage.TOKEN;
import org.icij.datashare.text.Language;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Language.SPANISH;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.Language.GERMAN;


/**
 * OpenNLP Tokenizer models handling singleton
 *
 * Created by julien on 8/11/16.
 */
public enum OpenNlpTokenModel {
    INSTANCE;

    private static final Logger LOGGER = LogManager.getLogger(OpenNlpTokenModel.class);

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


    // Token models base directory
    private final Path modelDir;

    // Token model paths (per Language)
    private final Map<Language, Path> modelPath;

    // Model locks
    private final ConcurrentHashMap<Language, Lock> modelLock;

    // Models
    private final Map<Language, TokenizerModel> model;


    OpenNlpTokenModel() {
        modelDir = OpenNlpModels.DIRECTORY.apply(TOKEN);
        modelPath = new HashMap<Language, Path>(){{
            put(ENGLISH, modelDir.resolve("en-token.bin"));
            put(SPANISH, modelDir.resolve("en-token.bin"));
            put(FRENCH,  modelDir.resolve("fr-token.bin"));
            put(GERMAN,  modelDir.resolve("de-token.bin"));
        }};
        modelLock =  new ConcurrentHashMap<Language, Lock>(){{
            modelPath.keySet()
                    .forEach( language -> put(language, new ReentrantLock()) );
        }};
        model = new HashMap<>();
    }


    /**
     * Lock and get tokenizer model for language
     *
     * @param language the annotator language
     * @return an Optional of Tokenizer model if successfully (loaded and) retrieved; empty Optional otherwise
     */
    public Optional<TokenizerModel> get(Language language) {
        sharedModels.apply(language)
                .forEach( lang -> {
                    modelLock.get(lang).lock();
                });

        try {
            if ( ! load(language, Thread.currentThread().getContextClassLoader()) ) {
                return Optional.empty();
            }
            return Optional.of(model.get(language));
        } finally {
            sharedModels.apply(language)
                    .forEach( lang -> {
                        modelLock.get(lang).unlock();
                    });
        }
    }

    private boolean load(Language language, ClassLoader loader) {
        if ( model.containsKey(language) ) {
            return true;
        }
        LOGGER.info(getClass().getName() + " - LOADING TOKEN model for " + language);
        try (InputStream modelIS = loader.getResourceAsStream(modelPath.get(language).toString())) {
            TokenizerModel tokenizerModel = new TokenizerModel(modelIS);
            sharedModels.apply(language)
                    .forEach( lang ->
                            model.put(lang, tokenizerModel)
                    );
        } catch (IOException e) {
            LOGGER.error("- FAILED LOADING " + TokenizerModel.class.getName(), e);
            return false;
        }
        LOGGER.info(getClass().getName() + " - LOADED TOKEN model for " + language);
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
