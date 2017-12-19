package org.icij.datashare.text.nlp.corenlp.models;

import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.AbstractModels;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.text.nlp.Pipeline;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static java.util.Arrays.asList;

public abstract class CoreNlpModels<T> extends AbstractModels<CoreNlpAnnotator<T>> {
    static final String VERSION = "3.8.0";
    static final Map<Language, String> MODEL_NAMES = new HashMap<>();
    private static final Path IN_JAR_BASE_PATH = Paths.get("edu/stanford/nlp/models");

    CoreNlpModels(NlpStage stage) {
        super(Pipeline.Type.CORENLP, stage);
    }

    private String getJarFileName(Language language) {
        return String.join("-", asList("stanford-corenlp",
                getVersion(), "models", language.name().toLowerCase())) + ".jar";
    }

    protected String getInJarModelPath(Language language) {
        return IN_JAR_BASE_PATH.resolve(MODEL_NAMES.get(language)).toString();
    }

    protected void addJarToContextClassLoader(Language language, ClassLoader loader) throws IOException {
        final URL resource = loader.getResource(getModelsBasePath(language).resolve(getJarFileName(language)).toString());
        URLClassLoader urlClassLoader = new URLClassLoader(new URL[] {resource}, loader);
        Thread.currentThread().setContextClassLoader(urlClassLoader);
    }

    @Override
    protected String getVersion() { return VERSION;}
    abstract String getPropertyName();
}
