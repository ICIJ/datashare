package org.icij.datashare.text.processing;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Interface for NLP pipeline wrappers.
 *
 * Created by julien on 4/4/16.
 */
public interface NLPPipeline {

    void run(Path filepath) throws IOException;

    void run(String text) throws IOException;

}
