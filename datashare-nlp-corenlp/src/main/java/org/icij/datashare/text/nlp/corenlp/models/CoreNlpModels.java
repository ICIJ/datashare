package org.icij.datashare.text.nlp.corenlp.models;

import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.AbstractModels;
import org.icij.datashare.text.nlp.Pipeline;

import java.util.HashMap;
import java.util.Map;

import static java.util.Arrays.asList;

public abstract class CoreNlpModels<T> extends AbstractModels<CoreNlpAnnotator<T>> {
    static final String VERSION = "4.5.5";
    final Map<Language, String> modelNames = new HashMap<>();
    private static final String IN_JAR_BASE_PATH = "edu/stanford/nlp/models/";

    CoreNlpModels() {
        super(Pipeline.Type.CORENLP);
    }

    String getJarFileName(Language language) {
        return String.join("-", asList("stanford", "corenlp", getVersion(), "models", language.iso6391Code() + ".jar"));
    }

    protected String getInJarModelPath(Language language) {
        return IN_JAR_BASE_PATH + modelNames.get(language);
    }

    @Override
    protected String getVersion() { return VERSION;}
    abstract String getPropertyName();
}
