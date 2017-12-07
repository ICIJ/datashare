package org.icij.datashare.text.nlp.open.models;

import opennlp.tools.util.model.BaseModel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.icij.datashare.io.RemoteFiles;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.NlpStage;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public abstract class OpenNlpAbstractModel {
    static final Logger LOGGER = LogManager.getLogger(OpenNlpPosModel.class);
    private final ConcurrentHashMap<Language, Lock> modelLock = new ConcurrentHashMap<Language, Lock>() {{
        for (Language l : Language.values()) {
            put(l, new ReentrantLock());
        }
    }};
    final NlpStage stage;

    public OpenNlpAbstractModel(NlpStage stage) { this.stage = stage;}

    abstract BaseModel getModel(Language language);
    abstract void putModel(Language language, InputStream content);
    abstract String getModelPath(Language language);

    public Optional<? extends BaseModel> get(Language language, ClassLoader loader) {
        Lock l = modelLock.get(language);
        l.lock();
        try {
            if (!load(language, loader))
                return Optional.empty();
            return Optional.of(getModel(language));
        } finally {
            l.unlock();
        }
    }

    boolean load(Language language, ClassLoader loader) {
        if (getModel(language) != null)
            return true;

        if (!isDownloaded(language, loader)) {
            download(language);
        }

        LOGGER.info(getClass().getName() + " - LOADING " + stage + " model for " + language);
        try (InputStream modelIS = loader.getResourceAsStream(getModelPath(language))) {
            putModel(language, modelIS);
        } catch (IOException e) {
            LOGGER.error(getClass().getName() + " - FAILED LOADING " + stage, e);
            return false;
        }
        LOGGER.info(getClass().getName() + " - LOADED " + stage + " model for " + language);
        return true;
    }

    boolean isDownloaded(Language language, ClassLoader loader) {
        return loader.getResource(getModelPath(language)) != null;
    }

    boolean download(Language language) {
        LOGGER.info(getClass().getName() + " - DOWNLOADING " + stage + " model for " + language);
        try {
            getRemoteFiles().download("/dist/models/opennlp/" + language.iso6391Code(), new File(getModelPath(language)));
            return true;
        } catch (InterruptedException | IOException e) {
            LOGGER.error(getClass().getName() + " - FAILED DOWNLOADING " + stage, e);
            return false;
        }
    }

    RemoteFiles getRemoteFiles() {
        return null;
    }
}
