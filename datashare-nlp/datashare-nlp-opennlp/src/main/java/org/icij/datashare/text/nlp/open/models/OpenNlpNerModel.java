package org.icij.datashare.text.nlp.open.models;

import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.util.model.ArtifactProvider;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;

import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.NamedEntity.Category.*;
import static org.icij.datashare.text.nlp.NlpStage.NER;


public class OpenNlpNerModel extends OpenNlpAbstractModel {
    private static volatile OpenNlpNerModel instance;
    private final Path modelDir;
    private final Map<Language, Map<NamedEntity.Category, Path>> modelPath;
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
        modelDir = DIRECTORY.apply(NER);
        modelPath = new HashMap<Language, Map<NamedEntity.Category, Path>>(){{
            put(ENGLISH, new HashMap<NamedEntity.Category, Path>(){{
                put(PERSON,       modelDir.resolve("en-ner-person.bin"));
                put(ORGANIZATION, modelDir.resolve("en-ner-organization.bin"));
                put(LOCATION,     modelDir.resolve("en-ner-location.bin"));
            }});
            put(SPANISH, new HashMap<NamedEntity.Category, Path>(){{
                put(PERSON,       modelDir.resolve("es-ner-person.bin"));
                put(ORGANIZATION, modelDir.resolve("es-ner-organization.bin"));
                put(LOCATION,     modelDir.resolve("es-ner-location.bin"));
            }});
            put(FRENCH, new HashMap<NamedEntity.Category, Path>(){{
                put(PERSON,       modelDir.resolve("en-ner-person.bin"));
                put(ORGANIZATION, modelDir.resolve("en-ner-organization.bin"));
                put(LOCATION,     modelDir.resolve("en-ner-location.bin"));
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
        return DIRECTORY.apply(NER).toString();
    }

    boolean load(Language language, ClassLoader loader) {
        if (getModel(language) != null)
            return true;

        if (!isDownloaded(language, loader)) {
            download(language);
        }

        OpenNlpCompositeModel models = new OpenNlpCompositeModel(language);
        for (Path p: modelPath.get(language).values()) {
            LOGGER.info(getClass().getName() + " - LOADING NER model " + p);
            try (InputStream modelIS = loader.getResourceAsStream(p.toString())) {
                models.add(new TokenNameFinderModel(modelIS));
            } catch (IOException e) {
                LOGGER.error(getClass().getName() + " - FAILED LOADING " + p, e);
                return false;
            }
        }
        model.put(language, models);

        LOGGER.info(getClass().getName() + " - LOADED NER model for " + language);
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
