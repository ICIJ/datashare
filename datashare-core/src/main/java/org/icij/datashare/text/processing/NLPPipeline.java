package org.icij.datashare.text.processing;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import static java.util.Arrays.asList;
import static java.util.logging.Level.WARNING;
import static org.icij.datashare.text.processing.NLPPipeline.NLPPipelineType.CORENLP;
import static org.icij.datashare.text.processing.NLPPipeline.NLPPipelineType.GATENLP;
import static org.icij.datashare.text.processing.NLPPipeline.NLPPipelineType.OPENNLP;

/**
 * Interface for NLP pipeline wrappers.
 *
 * Created by julien on 4/4/16.
 */
public interface NLPPipeline {

    enum NLPPipelineType {
        OPENNLP ("opennlp.OpenNLPPipeline"),
        CORENLP ("corenlp.CoreNLPPipeline"),
        GATENLP ("gatenlp.GateNLPPipeline");


        private final String fullyQualifiedClassName;

        NLPPipelineType(String className) {
            String basePackageName = NLPPipeline.class.getPackage().getName().replace("/", ".");
            fullyQualifiedClassName = String.join(".", basePackageName, className);
        }

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

        public String getFullyQualifiedClassName() {
            return fullyQualifiedClassName;
        }

    }

    static Optional<NLPPipeline> create(NLPPipelineType type, Properties props)  {
        if (type == null)
            return Optional.empty();

        String thisClassName = NLPPipeline.class.getName();
        if (asList(CORENLP, OPENNLP, GATENLP).contains(type)) {
            try {
                String className = type.getFullyQualifiedClassName();
                Class<?> nlpPipelineClass = Class.forName(className);
                Object nlpPipelineInstance = nlpPipelineClass.getDeclaredConstructor(new Class[]{Properties.class}).newInstance(props);
                return Optional.of((NLPPipeline) nlpPipelineInstance);
            } catch (ClassNotFoundException e) {
                Logger.getLogger(thisClassName).log(WARNING, "NLPPipeline type " + type + " not installed.", e);
                return Optional.empty();
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                Logger.getLogger(thisClassName).log(WARNING, "Failed to instantiate NLPPipeline type " + type, e);
                return Optional.empty();
            }
        } else {
            Logger.getLogger(thisClassName).log(WARNING, "Unknown NLPPipeline type " + type);
            return Optional.empty();
        }
    }

    void setLanguage(Language language);

    void run(Document document);

    void run(Path path);

    void run(String text);

    List<NamedEntity> getEntities();

}
