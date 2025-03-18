package org.icij.datashare.text.nlp;

import org.icij.datashare.DynamicClassLoader;
import org.icij.datashare.io.RemoteFiles;
import org.icij.datashare.text.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import static java.lang.Boolean.parseBoolean;

public abstract class AbstractModels<T> {
    public final static String JVM_PROPERTY_NAME = "DS_SYNC_NLP_MODELS";
    protected final Logger LOGGER = LoggerFactory.getLogger(getClass());
    private static final Path BASE_DIR = Paths.get(".").toAbsolutePath().normalize();
    protected static final Path BASE_CLASSPATH = Paths.get("models");
    private static final String PREFIX = "dist";
    protected final ConcurrentHashMap<Language, Semaphore> modelLock = new ConcurrentHashMap<>() {{
        for (Language l : Language.values()) {
            put(l, new Semaphore(1, true));
        }
    }};
    protected final Map<Language, T> models;
    protected final Pipeline.Type type;

    protected AbstractModels(final Pipeline.Type type) {
        this.type = type;
        this.models = new HashMap<>();
    }

    /**
     * generic model file loading
     * @param language input language
     * @return the model Object
     * @throws IOException when we cannot access to model files
     * @throws InterruptedException because loadModelFile could need to load dependent models of another language (cf CoreNlp) see get-
     */
    protected abstract T loadModelFile(Language language) throws IOException, InterruptedException;
    protected abstract String getVersion();

    public T get(Language language) throws InterruptedException {
        if (!isLoaded(language)) {
            load(language);
        }
        return models.get(language);
    }

    private void load(Language language) throws InterruptedException {
        Semaphore l = modelLock.get(language);
        l.acquire();
        try {
            if (isLoaded(language)) return;
            if (isSync()) {
                downloadIfNecessary(language);
            }
            models.put(language, loadModelFile(language));
            LOGGER.info("loaded model for {}", language);
        } catch (IOException e) {
            LOGGER.error("failed loading ", e);
        } finally {
            l.release();
        }
    }

    public Path getModelsBasePath(Language language) {
        return BASE_CLASSPATH.
                resolve(type.name().toLowerCase()).
                resolve(getVersion().replace('.', '-')).
                resolve(language.iso6391Code());
    }

    public Path getModelsFilesystemPath(Language language) {
        return Paths.get(PREFIX).resolve(getModelsBasePath(language));
    }

    public void addResourceToContextClassLoader(Path resourcePath) {
        DynamicClassLoader classLoader = (DynamicClassLoader)ClassLoader.getSystemClassLoader();
        final URL resource = classLoader.getResource(resourcePath.toString());
        LOGGER.info("adding {} to system classloader", resource == null? null: resource.getPath());
        classLoader.add(resource);
    }

    protected boolean isPresent(Language language) {
        return Thread.currentThread().getContextClassLoader().getResource(getModelsBasePath(language).toString()) != null;
    }

    protected void downloadIfNecessary(Language language) {
        String remoteKey = getModelsFilesystemPath(language).toString().replace("\\", "/");
        RemoteFiles remoteFiles = getRemoteFiles();
        try {
            if (isPresent(language) && remoteFiles.isSync(remoteKey, BASE_DIR.toFile())) {
                return;
            }
            LOGGER.info("downloading models for language {} under {}", language, remoteKey);
            remoteFiles.download(remoteKey, BASE_DIR.toFile());
            LOGGER.info("models successfully downloaded for language {}", language);
        } catch (InterruptedException | IOException e) {
            LOGGER.error("failed downloading models for {}", language, e);
        } finally {
            remoteFiles.shutdown();
        }
    }

    public void unload(Language language) throws InterruptedException {
        Semaphore l = modelLock.get(language);
        l.acquire();
        try {
            models.remove(language);
        } finally {
            l.release();
        }
    }
    public static void syncModels(final boolean sync) {
        LoggerFactory.getLogger(AbstractModels.class).info("synchronize models is set to {}", sync);
        System.setProperty(JVM_PROPERTY_NAME, String.valueOf(sync));
    }
    public static boolean isSync() {
        return parseBoolean(System.getProperty(JVM_PROPERTY_NAME, "true"));
    }

    public boolean isLoaded(Language language) { return models.containsKey(language);}
    protected RemoteFiles getRemoteFiles() { return RemoteFiles.getDefault();}
}
