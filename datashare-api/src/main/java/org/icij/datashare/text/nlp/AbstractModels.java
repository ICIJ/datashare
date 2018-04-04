package org.icij.datashare.text.nlp;

import org.icij.datashare.PropertiesProvider;
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

import static java.lang.Boolean.parseBoolean;

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
    protected final Pipeline.Type type;
    private final boolean syncModels;

    protected AbstractModels(final Pipeline.Type type, final NlpStage stage) {
        this(type, stage, parseBoolean(new PropertiesProvider().getProperties()
                .getProperty("syncNlpModels", "true")));
    }

    protected AbstractModels(final Pipeline.Type type, final NlpStage stage, final boolean syncModels) {
        this.stage = stage;
        this.type = type;
        this.models = new HashMap<>();
        this.syncModels = syncModels;
    }

    protected abstract T loadModelFile(Language language, ClassLoader loader) throws IOException;
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
            if (syncModels) {
                downloadIfNecessary(language, loader);
            }
            models.put(language, loadModelFile(language, loader));
            LOGGER.info("loaded {} model for {}", stage, language);
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

    public void unload(Language language) {
        Lock l = modelLock.get(language);
        l.lock();
        try {
            models.remove(language);
        } finally {
            l.unlock();
        }
    }

    public boolean isLoaded(Language language) { return models.containsKey(language);}
    protected RemoteFiles getRemoteFiles() { return RemoteFiles.getDefault();}
}
