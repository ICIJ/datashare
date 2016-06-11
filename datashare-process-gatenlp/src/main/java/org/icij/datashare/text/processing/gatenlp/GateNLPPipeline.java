package org.icij.datashare.text.processing.gatenlp;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static java.util.Arrays.asList;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

import gate.Annotation;
import gate.AnnotationSet;
import gate.util.GateException;
import gate.creole.ResourceInstantiationException;
import es.upm.oeg.icij.entity.extractor.extractor.gate.GATENLPDocument;
import es.upm.oeg.icij.entity.extractor.extractor.gate.GATENLPApplication;

import org.icij.datashare.text.hashing.Hasher;
import org.icij.datashare.text.processing.AbstractNLPPipeline;
import org.icij.datashare.text.processing.NamedEntity;
import org.icij.datashare.text.processing.NamedEntityCategory;

import org.icij.datashare.text.Language;
import static org.icij.datashare.text.Language.NONE;
import static org.icij.datashare.text.Language.UNKNOWN;

import static org.icij.datashare.text.processing.NLPStage.*;

import static org.icij.datashare.text.processing.NamedEntityCategory.LOCATION;
import static org.icij.datashare.text.processing.NamedEntityCategory.ORGANIZATION;
import static org.icij.datashare.text.processing.NamedEntityCategory.PERSON;


/**
 * GateNLP pipeline
 *
 * Created by julien on 5/19/16.
 */
public final class GateNLPPipeline extends AbstractNLPPipeline {

    private static final Path RESOURCES_BASEDIR =
            Paths.get( GateNLPPipeline.class.getPackage().getName().replace(".", "/") );

    // Gate annotation types: { Organization->Company, Location->Country, Person->Person }
    public static final Map<NamedEntityCategory, String> NECATEGORY_TO_GATETYPE =
            new HashMap<NamedEntityCategory, String>(){{
                put(ORGANIZATION, "Company");
                put(PERSON,       "Person");
                put(LOCATION,     "Country");
            }};

    public static final Map<String, NamedEntityCategory> GATETYPE_TO_NECATEGORY=
            new HashMap<String, NamedEntityCategory>(){{
                put("Company", ORGANIZATION);
                put("Person",  PERSON);
                put("Country", LOCATION);
            }};


    // Gate pipeline (language agnostic)
    private GATENLPApplication pipeline;


    public GateNLPPipeline(Properties props) {
        super(props);

        stageDependencies.get(TOKEN).add(SENTENCE);
        stageDependencies.get(NER)  .add(TOKEN);

        // Supposed to support any language
        for (Language lang : Language.values()) {
            supportedStages.put(lang, new HashSet<>(asList(SENTENCE, TOKEN, NER)));
        }
        supportedStages.remove(NONE);
        supportedStages.remove(UNKNOWN);

        if (targetStages.isEmpty())
            targetStages = supportedStages.get(language);

    }

    @Override
    protected void process(String input) {

        Optional<String> docHash = Optional.ofNullable(documentHash);
        Optional<Path>   docPath = Optional.ofNullable(documentPath);

        // Create Gate document
        String gateDocName = docHash.isPresent() ?
                docHash.get() :
                String.join(".", asList(Hasher.SHA_512.hash(input), "txt"));

        try {
            GATENLPDocument gateDoc = new GATENLPDocument(gateDocName, input);

            // Split input into tokens
            // Group tokens into sentences
            // Tag tokens with their recognized named entity category
            pipeline.annotate(gateDoc);

            // Retrieve recognized Named Entities
            for (NamedEntityCategory category : targetEntities) {
                AnnotationSet annotSet = gateDoc.getAnnotationSet(NECATEGORY_TO_GATETYPE.get(category));
                if (annotSet != null) {
                    for (Annotation annotation : new ArrayList<>(annotSet)) {
                        String mention = annotation.getFeatures().get("string").toString();
                        int mentionOffset = annotation.getStartNode().getOffset().intValue();
                        Optional<NamedEntity> optEntity = NamedEntity.create(category, mention, mentionOffset);
                        if (optEntity.isPresent()) {
                            NamedEntity entity = optEntity.get();
                            docHash.ifPresent(entity::setDocument);
                            docPath.ifPresent(entity::setDocumentPath);
                            entity.setExtractor(NLPPipelineType.GATENLP);
                            entity.setExtractorLanguage(language);
                            entities.add(entity);
                        }
                    }

                } else {
                    LOGGER.log(INFO, "Failed to retrieve any named entity");
                }
            }

        } catch (ResourceInstantiationException e) {
            LOGGER.log(SEVERE, "Failed to create Gate Document");
        }

    }

    @Override
    protected boolean initialize() {
        if ( ! super.initialize())
            return false;

        if (pipeline != null)
            return true;

        // ClassLoader loader = this.getClass().getClassLoader();

        try {
//        URL resDirURL = loader.getResource(RESOURCES_BASEDIR.toString());
//        if (resDirURL == null ) {
//            LOGGER.log(SEVERE, "Failed to get resource folder");
//            return false;
//        }
//        File resourceDirectory = new File( resDirURL.getFile() );

            Path resourceDirectoryPath = Paths.get(
                    System.getProperty("user.dir"),
                    "src", "main", "resources",
                    RESOURCES_BASEDIR.toString());

            pipeline = new GATENLPApplication(resourceDirectoryPath.toFile());

        } catch (GateException | IOException e) {
            LOGGER.log(SEVERE, "Failed to build GateNLP Application");
            return false;
        }

        return true;
    }

    @Override
    protected void terminate() {
        super.terminate();
        pipeline = null;
    }

}
