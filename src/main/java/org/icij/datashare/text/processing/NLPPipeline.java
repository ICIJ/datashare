package org.icij.datashare.text.processing;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.processing.corenlp.CoreNLPPipeline;
import org.icij.datashare.text.processing.gatenlp.GateNLPPipeline;
import org.icij.datashare.text.processing.opennlp.OpenNLPPipeline;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;

/**
 * Interface for NLP pipeline wrappers.
 *
 * Created by julien on 4/4/16.
 */
public interface NLPPipeline {

    enum NLPPipelineType {
        OPENNLP,
        CORENLP,
        GATENLP;

        public static Optional<NLPPipelineType> parse(final String pipeline) throws IllegalArgumentException {
            if (pipeline == null || pipeline.isEmpty())
                return Optional.empty();
            try {
                return Optional.of(valueOf(pipeline.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                //throw new IllegalArgumentException(String.format("\"%s\" is not a valid natural language processing pipeline.", pipeline));
                return Optional.empty();
            }
        }
    }

    static Optional<NLPPipeline> create(NLPPipelineType type, Properties props)  {
        if (type == null)
            return Optional.empty();

        switch (type) {
            case OPENNLP: return Optional.of(new OpenNLPPipeline(props));
            case CORENLP: return Optional.of(new CoreNLPPipeline(props));
            case GATENLP: return Optional.of(new GateNLPPipeline(props));
            default: {
                Logger.getLogger(NLPPipeline.class.getName()).log(WARNING, "Unknown NLPPipeline type " + type);
                return Optional.empty();
            }
        }
    }

    void setLanguage(Language language);

    void run(Document document);

    void run(Path path);

    void run(String text);

    List<NamedEntity> getEntities();

}
