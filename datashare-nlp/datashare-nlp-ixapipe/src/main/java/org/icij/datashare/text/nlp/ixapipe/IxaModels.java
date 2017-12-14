package org.icij.datashare.text.nlp.ixapipe;

import org.icij.datashare.text.nlp.AbstractModels;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.text.nlp.Pipeline;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;


abstract class IxaModels<T> extends AbstractModels<IxaAnnotate<T>> {
    private final String VERSION = "1.5";

    IxaModels(NlpStage stage) { super(Pipeline.Type.IXAPIPE, stage);}

    @Override
    protected String getVersion() { return VERSION;}

    URL createResourceOrThrowIoEx(Path path, ClassLoader loader) throws IOException {
        final URL resource = loader.getResource(path.toString());
        if (resource == null) {
            throw new IOException("cannot load resource from classpath " + path);
        }
        return resource;
    }
}
