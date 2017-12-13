package org.icij.datashare.text.nlp.open.models;

import opennlp.tools.util.model.ArtifactProvider;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.AbstractModels;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.text.nlp.Pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.locks.Lock;

public abstract class OpenNlpModels extends AbstractModels<ArtifactProvider> {
    static final String VERSION = "1.5";

    OpenNlpModels(NlpStage stage) { super(Pipeline.Type.OPENNLP, stage);}

    @Override
    protected String getVersion() { return VERSION;}

    @Override
    protected ArtifactProvider loadModelFile(Language language, ClassLoader loader) throws IOException {
        final String modelPath = getModelPath(language);
        LOGGER.info("loading model file " + modelPath);
        try (InputStream modelIS = loader.getResourceAsStream(modelPath)) {
            return createModel(modelIS);
        }
     }

    abstract ArtifactProvider createModel (InputStream is) throws IOException;
    abstract String getModelPath (Language languate);

    public void unload(Language language) {
        Lock l = modelLock.get(language);
        l.lock();
        try {
            models.remove(language);
        } finally {
            l.unlock();
        }
    }
}