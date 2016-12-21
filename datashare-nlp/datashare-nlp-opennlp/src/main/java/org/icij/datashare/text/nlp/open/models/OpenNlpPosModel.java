package org.icij.datashare.text.nlp.open.models;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import opennlp.tools.postag.POSModel;

import static org.icij.datashare.text.nlp.NlpStage.POS;
import org.icij.datashare.text.Language;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.Language.SPANISH;
import static org.icij.datashare.text.Language.GERMAN;


/**
 * OpenNLP Part-of-Speech tagger models handling singleton
 *
 * Created by julien on 8/11/16.
 */
public enum OpenNlpPosModel {
    INSTANCE;

    private static final Logger LOGGER = LogManager.getLogger(OpenNlpPosModel.class);

    // Part-of-speech refence tag set
    public static final String POS_TAGSET = "Penn Treebank";


    // Models base directory
    private final Path modelDir;

    // Model paths (per Language)
    private final Map<Language, Path> modelPath;

    // Model lock
    private final ConcurrentHashMap<Language, Lock> modelLock;

    // Model
    private final Map<Language, POSModel> model;


    OpenNlpPosModel() {
        modelDir = OpenNlpModels.DIRECTORY.apply(POS);
        modelPath  = new HashMap<Language, Path>(){{
            put(ENGLISH, modelDir.resolve("en-pos-maxent.bin"));
            put(SPANISH, modelDir.resolve("es-pos-maxent.bin"));
            put(FRENCH,  modelDir.resolve("fr-pos-maxent.bin"));
            put(GERMAN,  modelDir.resolve("de-pos-maxent.bin"));
        }};
        modelLock = new ConcurrentHashMap<Language, Lock>(){{
            modelPath.keySet()
                    .forEach( language -> put(language, new ReentrantLock()) );
        }};
        model = new HashMap<>();
    }


    public Optional<POSModel> get(Language language) {
        Lock l = modelLock.get(language);
        l.lock();
        try {
            if ( ! load(language, Thread.currentThread().getContextClassLoader())) {
                return Optional.empty();
            }
            return Optional.of(model.get(language));
        } finally {
            l.unlock();
        }
    }

    private boolean load(Language language, ClassLoader loader) {
        if ( model.containsKey(language) && model.get(language) == null) {
            return true;
        }
        LOGGER.info("Loading POS model for " + language);
        try (InputStream modelIS = loader.getResourceAsStream(modelPath.get(language).toString())) {
            model.put(language, new POSModel(modelIS));

        } catch (IOException e) {
            LOGGER.error("Failed to load " + POSModel.class.getName(), e);
            return false;
        }
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


