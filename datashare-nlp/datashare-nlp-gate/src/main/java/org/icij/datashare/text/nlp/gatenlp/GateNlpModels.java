package org.icij.datashare.text.nlp.gatenlp;

import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.AbstractModels;
import org.icij.datashare.text.nlp.Pipeline;

import java.io.IOException;
import java.nio.file.Path;

public class GateNlpModels extends AbstractModels<String> {
    GateNlpModels() {
        super(Pipeline.Type.GATENLP, null);
    }

    @Override
    public Path getModelsBasePath(Language language) {
        return BASE_CLASSPATH.resolve(type.name().toLowerCase()).resolve(getVersion().replace('.', '-'));
    }

    @Override
    protected String loadModelFile(Language language, ClassLoader loader) throws IOException { return getVersion();}

    @Override
    protected String getVersion() { return "1.0-oeg";}
}
