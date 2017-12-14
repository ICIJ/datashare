package org.icij.datashare.text.nlp.opennlp.models;

import opennlp.tools.postag.POSModel;
import opennlp.tools.util.model.ArtifactProvider;
import org.icij.datashare.text.Language;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.nlp.NlpStage.POS;


public class OpenNlpPosModels extends OpenNlpModels {
    private static volatile OpenNlpPosModels instance;
    private static final Object mutex = new Object();

    private final Map<Language, String> modelFilenames;

    public static final Map<Language, String> POS_TAGSET = new HashMap<Language, String>() {{
        put(ENGLISH, "PENN TREEBANK");
        put(SPANISH, "ANCORA");
        put(FRENCH,  "CC");
        put(GERMAN,  "STTS");
    }};


    public static OpenNlpPosModels getInstance() {
        OpenNlpPosModels local_instance = instance; // avoid accessing volatile field
         if (local_instance == null) {
             synchronized(mutex) {
                 local_instance = instance;
                 if (local_instance == null) {
                     instance = new OpenNlpPosModels();
                 }
             }
         }
         return instance;
     }

    private OpenNlpPosModels() {
        super(POS);
        modelFilenames = new HashMap<Language, String>(){{
            put(ENGLISH, "en-pos-maxent.bin");
            put(SPANISH, "es-pos-maxent.bin");
            put(FRENCH,  "fr-pos-maxent.bin");
            put(GERMAN,  "de-pos-maxent.bin");
        }};
    }
    protected ArtifactProvider createModel(InputStream content) throws IOException {
        return new POSModel(content);
    }

    protected String getModelPath(Language language) {
        return getModelsBasePath(language).resolve(modelFilenames.get(language)).toString();
    }

}


