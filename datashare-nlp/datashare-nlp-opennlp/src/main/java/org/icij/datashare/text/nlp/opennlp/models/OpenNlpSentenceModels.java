package org.icij.datashare.text.nlp.opennlp.models;

import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.util.model.ArtifactProvider;
import org.icij.datashare.text.Language;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.nlp.NlpStage.SENTENCE;


public class OpenNlpSentenceModels extends OpenNlpModels {
    private static volatile OpenNlpSentenceModels instance;
    private static final Object mutex = new Object();

    private final Map<Language, String> modelFilenames;
    private final Map<Language, SentenceModel> model;

    public static OpenNlpSentenceModels getInstance() {
        OpenNlpSentenceModels local_instance = instance; // avoid accessing volatile field
        if (local_instance == null) {
            synchronized (mutex) {
                local_instance = instance;
                if (local_instance == null) {
                    instance = new OpenNlpSentenceModels();
                }
            }
        }
        return instance;
    }

    private OpenNlpSentenceModels() {
        super(SENTENCE);
        modelFilenames = new HashMap<Language, String>() {{
            put(ENGLISH, "en-sent.bin");
            put(SPANISH, "en-sent.bin");
            put(FRENCH, "fr-sent.bin");
            put(GERMAN, "de-sent.bin");
        }};
        model = new HashMap<>();
    }

    protected ArtifactProvider createModel(InputStream content) throws IOException {
        return new SentenceModel(content);
    }

    protected String getModelPath(Language language) {
        return getModelsBasePath(language).resolve(modelFilenames.get(language)).toString();
    }
}
