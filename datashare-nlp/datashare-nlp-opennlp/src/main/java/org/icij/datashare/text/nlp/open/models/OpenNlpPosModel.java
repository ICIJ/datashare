package org.icij.datashare.text.nlp.open.models;

import opennlp.tools.postag.POSModel;
import opennlp.tools.util.model.BaseModel;
import org.icij.datashare.text.Language;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;

import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.nlp.NlpStage.POS;


public class OpenNlpPosModel extends OpenNlpAbstractModel {
    private static volatile OpenNlpPosModel instance;

    public static final Map<Language, String> POS_TAGSET = new HashMap<Language, String>() {{
        put(ENGLISH, "PENN TREEBANK");
        put(SPANISH, "ANCORA");
        put(FRENCH,  "CC");
        put(GERMAN,  "STTS");
    }};

    private final Map<Language, String> modelFilenames;
    private final Map<Language, POSModel> model;

    public static OpenNlpPosModel getInstance() {
        OpenNlpPosModel local_instance = instance; // avoid accessing volatile field
         if (local_instance == null) {
             synchronized(OpenNlpAbstractModel.mutex) {
                 local_instance = instance;
                 if (local_instance == null) {
                     instance = new OpenNlpPosModel();
                 }
             }
         }
         return instance;
     }

    private OpenNlpPosModel() {
        super(POS);
        modelFilenames = new HashMap<Language, String>(){{
            put(ENGLISH, "en-pos-maxent.bin");
            put(SPANISH, "es-pos-maxent.bin");
            put(FRENCH,  "fr-pos-maxent.bin");
            put(GERMAN,  "de-pos-maxent.bin");
        }};
        model = new HashMap<>();
    }

    @Override
    BaseModel getModel(Language language) {
        return model.get(language);
    }

    @Override
    void putModel(Language language, InputStream content) throws IOException {
        model.put(language, new POSModel(content));
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


