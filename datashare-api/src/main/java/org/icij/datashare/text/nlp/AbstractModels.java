package org.icij.datashare.text.nlp;

import org.icij.datashare.io.RemoteFiles;
import org.icij.datashare.text.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public abstract class AbstractModels<T> {
    public static final Path BASE_DIR = Paths.get(".").toAbsolutePath().normalize();
    protected static final Path BASE_CLASSPATH = Paths.get("models");

    protected final Logger LOGGER = LoggerFactory.getLogger(getClass());

    protected final ConcurrentHashMap<Language, Lock> modelLock = new ConcurrentHashMap<Language, Lock>() {{
        for (Language l : Language.values()) {
            put(l, new ReentrantLock());
        }
    }};
    protected final NlpStage stage;
    protected final Map<Language, T> models;
    private final Pipeline.Type type;

    protected AbstractModels(final Pipeline.Type type, final NlpStage stage) {
        this.stage = stage;
        this.type = type;
        models = new HashMap<>();
    }

    protected abstract T loadModelFile(Language language, ClassLoader loader) throws IOException;
    protected abstract String getVersion();

    public Optional<T> get(Language language, ClassLoader loader) {
        Lock l = modelLock.get(language);
        l.lock();
        try {
            if (!load(language, loader))
                return Optional.empty();
            return Optional.of(models.get(language));
        } finally {
            l.unlock();
        }
    }

    protected boolean load(Language language, ClassLoader loader) {
        if (models.get(language) != null)
            return true;

        if (!isDownloaded(language, loader)) {
            download(language);
        }

        try {
            models.put(language, loadModelFile(language, loader));
            LOGGER.info("loaded " + stage + " model for " + language);
            return true;
        } catch (IOException e) {
            LOGGER.error("failed loading " + stage, e);
            return false;
        }
    }

    protected Path getModelsBasePath(Language language) {
        return BASE_CLASSPATH.
                resolve(type.name().toLowerCase()).
                resolve(getVersion().replace('.', '-')).
                resolve(language.iso6391Code());
    }

    protected Path getModelsFilesystemPath(Language language) {
        return Paths.get("dist").resolve(getModelsBasePath(language));
    }

    protected boolean isDownloaded(Language language, ClassLoader loader) {
        return loader.getResource(getModelsBasePath(language).toString()) != null;
    }

    private void download(Language language) {
        String remoteKey = getModelsFilesystemPath(language).toString();
        LOGGER.info("downloading models for language " + language + " under " + remoteKey);
        try {
            getRemoteFiles().download(remoteKey, BASE_DIR.toFile());
            LOGGER.info("models successfully downloaded for language " + language);
        } catch (InterruptedException | IOException e) {
            LOGGER.error("failed downloading models for " + language, e);
        }
    }

    protected RemoteFiles getRemoteFiles() {
        return RemoteFiles.getDefault();
    }

    public void unload(Language language) {
        Lock l = modelLock.get(language);
        l.lock();
        try {
            models.remove(language);
        } finally {
            l.unlock();
        }
    }

    public boolean isLoaded(Language language) {
        return models.containsKey(language);
    }
}
