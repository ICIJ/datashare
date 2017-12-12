package org.icij.datashare.text.nlp.open.models;

import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.util.model.ArtifactProvider;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;

import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.NamedEntity.Category.*;
import static org.icij.datashare.text.nlp.NlpStage.NER;


public class OpenNlpNerModel extends OpenNlpAbstractModel {
    private static volatile OpenNlpNerModel instance;
    private final Map<Language, Map<NamedEntity.Category, String>> modelsFilenames;
    private final Map<Language, OpenNlpCompositeModel> model = new HashMap<>();

    public static OpenNlpNerModel getInstance() {
        OpenNlpNerModel local_instance = instance;
         if (local_instance == null) {
             synchronized(OpenNlpAbstractModel.mutex) {
                 local_instance = instance;
                 if (local_instance == null) {
                     instance = new OpenNlpNerModel();
                 }
             }
         }
         return instance;
     }

    private OpenNlpNerModel() {
        super(NER);
        modelsFilenames = new HashMap<Language, Map<NamedEntity.Category, String>>(){{
            put(ENGLISH, new HashMap<NamedEntity.Category, String>(){{
                put(PERSON,       "en-ner-person.bin");
                put(ORGANIZATION, "en-ner-organization.bin");
                put(LOCATION,     "en-ner-location.bin");
            }});
            put(SPANISH, new HashMap<NamedEntity.Category, String>(){{
                put(PERSON,       "es-ner-person.bin");
                put(ORGANIZATION, "es-ner-organization.bin");
                put(LOCATION,     "es-ner-location.bin");
            }});
            put(FRENCH, new HashMap<NamedEntity.Category, String>(){{
                put(PERSON,       "fr-ner-person.bin");
                put(ORGANIZATION, "fr-ner-organization.bin");
                put(LOCATION,     "fr-ner-location.bin");
            }});
        }};
    }

    @Override
    ArtifactProvider getModel(Language language) {
        return model.get(language);
    }

    @Override
    void putModel(Language language, InputStream content) throws IOException {
        throw new IllegalStateException("putModel not available for " + this.getClass());
    }

    @Override
    String getModelPath(Language language) {
        return BASE_DIR.resolve(language.iso6391Code()).toString();
    }

    boolean load(Language language, ClassLoader loader) {
        if (getModel(language) != null)
            return true;

        if (!isDownloaded(language, loader)) {
            download(language);
        }

        OpenNlpCompositeModel models = new OpenNlpCompositeModel(language);
        for (String p: modelsFilenames.get(language).values()) {
            LOGGER.info("LOADING NER model " + p);
            try (InputStream modelIS = loader.getResourceAsStream(BASE_DIR.resolve(language.iso6391Code()).resolve(p).toString())) {
                models.add(new TokenNameFinderModel(modelIS));
            } catch (IOException e) {
                LOGGER.error("FAILED LOADING " + p, e);
                return false;
            }
        }
        model.put(language, models);

        LOGGER.info("LOADED NER models for " + language);
        return true;
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
