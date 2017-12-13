package org.icij.datashare.text.nlp.open.models;

import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.util.model.BaseModel;
import org.icij.datashare.text.Language;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;

import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.nlp.NlpStage.SENTENCE;


public class OpenNlpSentenceModel extends OpenNlpAbstractModel {
    private static volatile OpenNlpSentenceModel instance;

    private final Map<Language, String> modelFilenames;
    private final Map<Language, SentenceModel> model;

    public static OpenNlpSentenceModel getInstance() {
        OpenNlpSentenceModel local_instance = instance; // avoid accessing volatile field
         if (local_instance == null) {
             synchronized(OpenNlpAbstractModel.mutex) {
                 local_instance = instance;
                 if (local_instance == null) {
                     instance = new OpenNlpSentenceModel();
                 }
             }
         }
         return instance;
     }

    private OpenNlpSentenceModel() {
        super(SENTENCE);
        modelFilenames = new HashMap<Language, String>(){{
            put(ENGLISH, "en-sent.bin");
            put(SPANISH, "en-sent.bin");
            put(FRENCH,  "fr-sent.bin");
            put(GERMAN,  "de-sent.bin");
        }};
        model = new HashMap<>();
    }

    @Override
     BaseModel getModel(Language language) {
         return model.get(language);
     }

     @Override
     void putModel(Language language, InputStream content) throws IOException {
         model.put(language, new SentenceModel(content));
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
