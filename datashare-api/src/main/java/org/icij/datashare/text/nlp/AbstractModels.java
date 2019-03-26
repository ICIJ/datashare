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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import static java.lang.Boolean.parseBoolean;

public abstract class AbstractModels<T> {
    public final static String JVM_PROPERTY_NAME = "DS_SYNC_NLP_MODELS";
    protected final Logger LOGGER = LoggerFactory.getLogger(getClass());
    private static final Path BASE_DIR = Paths.get(".").toAbsolutePath().normalize();
    protected static final Path BASE_CLASSPATH = Paths.get("models");
    private static final String PREFIX = "dist";
    protected final ConcurrentHashMap<Language, Semaphore> modelLock = new ConcurrentHashMap<Language, Semaphore>() {{
        for (Language l : Language.values()) {
            put(l, new Semaphore(1, true));
        }
    }};
    public final NlpStage stage;
    protected final Map<Language, T> models;
    protected final Pipeline.Type type;

    protected AbstractModels(final Pipeline.Type type, final NlpStage stage) {
        this.stage = stage;
        this.type = type;
        this.models = new HashMap<>();
    }

    protected abstract T loadModelFile(Language language, ClassLoader loader) throws IOException;
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
                downloadIfNecessary(language, getLoader());
            }
            models.put(language, loadModelFile(language, getLoader()));
            LOGGER.info("loaded {} model for {}", stage, language);
        } catch (IOException e) {
            LOGGER.error("failed loading " + stage, e);
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

    public void addResourceToContextClassLoader(Path resourcePath, ClassLoader loader) {
        final URL resource = loader.getResource(resourcePath.toString());

        URLClassLoader classLoader = (URLClassLoader)ClassLoader.getSystemClassLoader();
        try {
            LOGGER.info("adding {} to system classloader", resourcePath);
            Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            method.setAccessible(true);
            method.invoke(classLoader, resource); // hack to load jar for CoreNLP resources
        } catch (NoSuchMethodException|IllegalAccessException|InvocationTargetException e) {
            LOGGER.error("cannot invoke SystemClassloader.addURL. Cannot load language resource for " + resourcePath, e);
        }
    }

    protected boolean isPresent(Language language, ClassLoader loader) {
        return loader.getResource(getModelsBasePath(language).toString()) != null;
    }

    protected void downloadIfNecessary(Language language, ClassLoader loader) {
        String remoteKey = getModelsFilesystemPath(language).toString();
        RemoteFiles remoteFiles = getRemoteFiles();
        try {
            if (isPresent(language, loader) && remoteFiles.isSync(remoteKey, BASE_DIR.toFile())) {
                return;
            }
            LOGGER.info("downloading models for language {} under {}", language, remoteKey);
            remoteFiles.download(remoteKey, BASE_DIR.toFile());
            LOGGER.info("models successfully downloaded for language {}", language);
        } catch (InterruptedException | IOException e) {
            LOGGER.error("failed downloading models for " + language, e);
        } finally {
            remoteFiles.shutdown();
        }
    }

    protected ClassLoader getLoader() {
        return Thread.currentThread().getContextClassLoader();
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
