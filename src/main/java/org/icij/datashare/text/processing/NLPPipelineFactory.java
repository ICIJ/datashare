package org.icij.datashare.text.processing;

import org.icij.datashare.text.processing.corenlp.CoreNLPPipeline;
import org.icij.datashare.text.processing.opennlp.OpenNLPPipeline;

import java.io.IOException;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;


/**
 * Created by julien on 4/15/16.
 */
public class NLPPipelineFactory {

    public static Optional<NLPPipeline> build(String name, Logger logger, Properties props)  {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }

        switch (name.trim().toLowerCase()) {
            case "opennlp": return Optional.of(new OpenNLPPipeline(logger, props));
            case "corenlp": return Optional.of(new CoreNLPPipeline(logger, props));
            default:
                logger.log(WARNING, "Unknown NLPPipeline " + name);
                return Optional.empty();
        }
    }

}
