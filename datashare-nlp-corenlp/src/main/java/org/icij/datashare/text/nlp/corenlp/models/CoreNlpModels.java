package org.icij.datashare.text.nlp.corenlp.models;

import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.AbstractModels;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.text.nlp.Pipeline;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static java.util.Arrays.asList;

public abstract class CoreNlpModels<T> extends AbstractModels<CoreNlpAnnotator<T>> {
    static final String VERSION = "3.9.2";
    final Map<Language, String> modelNames = new HashMap<>();
    private static final Path IN_JAR_BASE_PATH = Paths.get("edu/stanford/nlp/models");

    CoreNlpModels(NlpStage stage) {
        super(Pipeline.Type.CORENLP, stage);
    }

    String getJarFileName(Language language) {
        return String.join("-", asList("stanford",
                language.name().toLowerCase(), "corenlp-2018-10-05-models.jar"));
    }

    protected String getInJarModelPath(Language language) {
        return IN_JAR_BASE_PATH.resolve(modelNames.get(language)).toString();
    }

    @Override
    protected String getVersion() { return VERSION;}
    abstract String getPropertyName();
}
