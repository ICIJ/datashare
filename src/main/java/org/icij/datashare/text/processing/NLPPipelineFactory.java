package org.icij.datashare.text.processing;

import org.icij.datashare.text.processing.corenlp.CoreNLPPipeline;
import org.icij.datashare.text.processing.opennlp.OpenNLPPipeline;

import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;


/**
 * Created by julien on 4/15/16.
 */
public class NLPPipelineFactory {

    public enum NLPPipelineType {
        OPENNLP,
        CORENLP,
        NONE,
        UNKNOWN;

        public static NLPPipelineType parse(final String pipeline) throws IllegalArgumentException {
            if (pipeline == null || pipeline.isEmpty())
                return NONE;
            try {
                return valueOf(pipeline.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                //throw new IllegalArgumentException(String.format("\"%s\" is not a valid natural language processing pipeline.", pipeline));
                return UNKNOWN;
            }
        }
    }

    public static Optional<NLPPipeline> build(NLPPipelineType type, Properties props, Logger logger)  {
        if (type == null)
            return Optional.empty();

        switch (type) {
            case OPENNLP: return Optional.of(new OpenNLPPipeline(logger, props));
            case CORENLP: return Optional.of(new CoreNLPPipeline(logger, props));
            default:
                logger.log(WARNING, "Unknown NLPPipeline type " + type);
                return Optional.empty();
        }
    }

}
