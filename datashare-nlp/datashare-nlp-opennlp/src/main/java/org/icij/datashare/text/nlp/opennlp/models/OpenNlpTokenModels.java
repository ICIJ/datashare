package org.icij.datashare.text.nlp.opennlp.models;

import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.model.ArtifactProvider;
import org.icij.datashare.text.Language;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.nlp.NlpStage.TOKEN;


public class OpenNlpTokenModels extends OpenNlpModels {
    private static volatile OpenNlpTokenModels instance;
    private static final Object mutex = new Object();

    private final Map<Language, String> modelFilenames;

    public static OpenNlpTokenModels getInstance() {
        OpenNlpTokenModels local_instance = instance; // avoid accessing volatile field
        if (local_instance == null) {
            synchronized(mutex) {
                local_instance = instance;
                if (local_instance == null) {
                    instance = new OpenNlpTokenModels();
                }
            }
        }
        return instance;
    }

    private OpenNlpTokenModels() {
        super(TOKEN);
        modelFilenames = new HashMap<Language, String>(){{
            put(ENGLISH, "en-token.bin");
            put(SPANISH, "en-token.bin");
            put(FRENCH,  "fr-token.bin");
            put(GERMAN,  "de-token.bin");
        }};
    }

    @Override
    protected ArtifactProvider createModel(InputStream content) throws IOException {
        return new TokenizerModel(content);
    }

    @Override
    protected String getModelPath(Language language) {
        return getModelsBasePath(language).resolve(modelFilenames.get(language)).toString();
    }
}
