package org.icij.datashare;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;

import org.icij.datashare.text.Language;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Language.SPANISH;
import static org.icij.datashare.text.Language.FRENCH;

import org.icij.datashare.text.processing.NamedEntityCategory;
import static org.icij.datashare.text.processing.NamedEntityCategory.PERSON;
import static org.icij.datashare.text.processing.NamedEntityCategory.ORGANIZATION;
import static org.icij.datashare.text.processing.NamedEntityCategory.LOCATION;

import org.icij.datashare.text.processing.NLPStage;
import static org.icij.datashare.text.processing.NLPStage.POS;
import static org.icij.datashare.text.processing.NLPStage.NER;

import org.icij.datashare.text.processing.NLPPipeline;
import org.icij.datashare.text.processing.NLPPipelineFactory;
import org.icij.datashare.text.processing.NLPPipelineFactory.NLPPipelineType;

import static org.icij.datashare.text.processing.NLPPipelineFactory.NLPPipelineType.CORENLP;
import static org.icij.datashare.text.processing.NLPPipelineFactory.NLPPipelineType.OPENNLP;

import static org.icij.datashare.util.function.ThrowingFunctionUtils.join;

/**
 * Datashare
 *
 * Created by julien on 3/9/16.
 */
public class Main {

    public static final Logger logger = Logger.getLogger("datashare");


    public static void main(String[] args) {

        try {
            Language                    language  = ENGLISH;
            List<NLPStage>              stages    = Arrays.asList(POS, NER);
            List<NamedEntityCategory>   entities  = Arrays.asList(PERSON, ORGANIZATION, LOCATION);
            List<NLPPipelineType>       pipelines = Arrays.asList(OPENNLP, CORENLP);

            String                    text      = PARAGRAPH.get(language);
            //Path                      path      = Paths.get(filepath);

            Properties props = new Properties();
            props.setProperty("language", language.toString());
            props.setProperty("stages",   join.apply(",").apply(stages));
            props.setProperty("entities", join.apply(",").apply(entities));

            for (NLPPipelineType type : pipelines) {
                Optional<NLPPipeline> pipeline = NLPPipelineFactory.build(type, props, logger);
                if (pipeline.isPresent()) {
                    pipeline.get().run(text);
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        /*
        LOGGER.log(Level.INFO, "> Lauching evaluation.");
        NLPPipelinesEvaluation evaluation = new NLPPipelinesEvaluation();
        evaluation.run("prometheus");
        LOGGER.log(Level.INFO, "< Evaluation completed.");
*/

    }

    private static final String DATA_BASEDIR = "/home/julien/data/";

    private static final Map<Language, String> PARAGRAPH =
            new HashMap<Language, String>(){{

                put(ENGLISH, "Hi. How are you? This is Mike Brandson. I'd like you to tell me \n" +
                        "how POS-tagging and NER work, please!\n" +
                        "Thank you very much, Albert Einstein. Greetings from Berne, Switzerland.");

                put(FRENCH, "Salut! Comment ça va ? C'est M. Henri Dupont. J'aimerais que vous me disiez comment l'étiquetteur\n" +
                        "\"morpho-syntaxique\" (POS tagger) et la reconnaissance d'entités nommées fonctionnent, s'il-vous-plaît !\n" +
                        "Merci beaucoup, Julien Martin. Salutations de Paris, France.");

                put(SPANISH, "Leer y ﬁrmar dos originales del acuerdo adjunto entre Pablo Calleja y sus cliente in Barcelona, España,\n" +
                        "así como también el \"Schedule B\". Un original firmado por los socios será devuelto a ustedes Julien Martin\n" +
                        "para su referencia.");

            }};

}
