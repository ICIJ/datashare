package org.icij.datashare.text.nlp;

import org.icij.datashare.io.RemoteFiles;
import org.icij.datashare.text.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
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
    public static final String PREFIX = "dist";

    protected final Logger LOGGER = LoggerFactory.getLogger(getClass());

    protected final ConcurrentHashMap<Language, Lock> modelLock = new ConcurrentHashMap<Language, Lock>() {{
        for (Language l : Language.values()) {
            put(l, new ReentrantLock());
        }
    }};
    public final NlpStage stage;
    protected final Map<Language, T> models;
    private final Pipeline.Type type;

    protected AbstractModels(final Pipeline.Type type, final NlpStage stage) {
        this.stage = stage;
        this.type = type;
        models = new HashMap<>();
    }

    protected abstract T loadModelFile(Language language, ClassLoader loader) throws IOException;

    public void addResourceToContextClassLoader(Path resourcePath, ClassLoader loader) {
        final URL resource = loader.getResource(resourcePath.toString());

        URLClassLoader classLoader = (URLClassLoader)ClassLoader.getSystemClassLoader();
        try {
            LOGGER.info("adding " + resourcePath + " to system classloader");
            Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            method.setAccessible(true);
            method.invoke(classLoader, resource); // hack to load jar for CoreNLP resources
        } catch (NoSuchMethodException|IllegalAccessException|InvocationTargetException e) {
            LOGGER.error("cannot invoke SystemClassloader.addURL. Cannot load language resource for " + resourcePath);
        }
    }

    protected abstract String getVersion();

    public Optional<T> get(Language language, ClassLoader loader) {
        if (!isLoaded(language)) {
            load(language, loader);
        }
        return Optional.of(models.get(language));
    }

    private void load(Language language, ClassLoader loader) {
        Lock l = modelLock.get(language);
        l.lock();
        try {
            if (!isDownloaded(language, loader)) {
                download(language);
            }
            models.put(language, loadModelFile(language, loader));
            LOGGER.info("loaded " + stage + " model for " + language);
        } catch (IOException e) {
            LOGGER.error("failed loading " + stage, e);
        } finally {
            l.unlock();
        }
    }

    public Path getModelsBasePath(Language language) {
        return BASE_CLASSPATH.
                resolve(type.name().toLowerCase()).
                resolve(getVersion().replace('.', '-')).
                resolve(language.iso6391Code());
    }

    protected Path getModelsFilesystemPath(Language language) {
        return Paths.get(PREFIX).resolve(getModelsBasePath(language));
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
