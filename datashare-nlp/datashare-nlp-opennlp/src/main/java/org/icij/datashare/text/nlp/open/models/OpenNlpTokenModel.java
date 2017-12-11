package org.icij.datashare.text.nlp.open.models;

import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.model.BaseModel;
import org.icij.datashare.text.Language;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;

import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.nlp.NlpStage.TOKEN;


public class OpenNlpTokenModel extends OpenNlpAbstractModel {
    private static volatile OpenNlpTokenModel instance;

    private final Map<Language, String> modelFilenames;
    private final Map<Language, TokenizerModel> model;

    public static OpenNlpTokenModel getInstance() {
        OpenNlpTokenModel local_instance = instance; // avoid accessing volatile field
        if (local_instance == null) {
            synchronized(OpenNlpAbstractModel.mutex) {
                local_instance = instance;
                if (local_instance == null) {
                    instance = new OpenNlpTokenModel();
                }
            }
        }
        return instance;
    }

    private OpenNlpTokenModel() {
        super(TOKEN);
        modelFilenames = new HashMap<Language, String>(){{
            put(ENGLISH, "en-token.bin");
            put(SPANISH, "en-token.bin");
            put(FRENCH,  "fr-token.bin");
            put(GERMAN,  "de-token.bin");
        }};
        model = new HashMap<>();
    }

    @Override
    BaseModel getModel(Language language) {
        return model.get(language);
    }

    @Override
    void putModel(Language language, InputStream content) throws IOException {
        model.put(language, new TokenizerModel(content));
    }

    @Override
    String getModelPath(Language language) {
        return BASE_DIR.resolve(language.iso6391Code()).resolve(modelFilenames.get(language)).toString();
    }

    public void unload(Language language) {
        Lock l = modelLock.get(language);
        l.lock();
        try {
            model.remove(language);
        } finally {
            l.unlock();
        }
    }
}
