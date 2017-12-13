package org.icij.datashare.text.nlp.open.models;

import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.util.model.ArtifactProvider;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.NamedEntity.Category.*;
import static org.icij.datashare.text.nlp.NlpStage.NER;


public class OpenNlpNerModels extends OpenNlpModels {
    private static volatile OpenNlpNerModels instance;
    private static final Object mutex = new Object();
    private final Map<Language, Map<NamedEntity.Category, String>> modelsFilenames;

    public static OpenNlpNerModels getInstance() {
        OpenNlpNerModels local_instance = instance;
         if (local_instance == null) {
             synchronized(mutex) {
                 local_instance = instance;
                 if (local_instance == null) {
                     instance = new OpenNlpNerModels();
                 }
             }
         }
         return instance;
     }

    private OpenNlpNerModels() {
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
    protected ArtifactProvider loadModelFile(Language language, ClassLoader loader) throws IOException {
        OpenNlpCompositeModel compositeModels = new OpenNlpCompositeModel(language);
        for (String p: modelsFilenames.get(language).values()) {
            LOGGER.info("loading NER model " + p);
            try (InputStream modelIS = loader.getResourceAsStream(BASE_CLASSPATH.resolve(language.iso6391Code()).resolve(p).toString())) {
                compositeModels.add(createModel(modelIS));
            }
        }
        return compositeModels;
    }

    @Override
    ArtifactProvider createModel(InputStream is) throws IOException {
        return new TokenNameFinderModel(is);
    }

    @Override
    String getModelPath(Language language) {
        throw new NotImplementedException();
    }
}
